package org.linuxdo.android.net

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.linuxdo.android.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.linuxdo.android.Config
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.data.TopicRepository
import org.linuxdo.android.net.gecko.GeckoAuthBridge
import org.linuxdo.android.net.gecko.GeckoManager
import org.mozilla.geckoview.GeckoView
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 全应用唯一的 WebView 会话:
 * - 登录/交互验证时被界面显示出来;
 * - 其余时间躺在界面下层,用于静默通过 Cloudflare 挑战,或作为路径 B 的网络层。
 * 同一个 WebView 与同一个固定 UA,才能保证 cf_clearance 与后续请求的特征一致。
 */
class BrowserSession(
    private val appContext: Context,
    val userAgent: String,
    private val store: SessionStore,
    private val diagnostics: Diagnostics,
    private val httpClientProvider: (() -> OkHttpClient)? = null,
    private val isProxyOrVpnActiveProvider: (() -> Boolean)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webViewInstance: WebView? = null
    private var authWebView: WebView? = null
    private var pendingAuthHtml: String? = null
    private var authHtmlInjected: Boolean = false
    private var cfCompletionProbing: Boolean = false
    private var cfQuietRounds: Int = 0
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TransportResult>>()
    private val probeRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val solveMutex = Mutex()

    @Volatile
    private var finishedCount = 0
    @Volatile
    private var hasMainFrameError = false

    /**
     * 全屏验证页的阶段。验证页只由可见的 [mainGeckoView] 完成，状态也只看它；
     * 隐藏的系统 WebView 没有 ECH，直连时连不上 /challenge，不能再拿它的加载结果驱动界面。
     */
    private enum class VerifyPhase { IDLE, RUNNING, DONE }

    @Volatile private var verifyPhase = VerifyPhase.IDLE
    private var verificationStartedAt = 0L

    /** 本轮验证的 Gecko 页面是否已加载完一页站内文档。 */
    @Volatile private var verificationPageLoaded = false

    /** 本轮验证的 Gecko 主框架加载失败（连不上、证书错误），显示 Gecko 自己的错误页。 */
    @Volatile private var verifyLoadFailed = false

    /** 本轮验证页超时未加载完成的诊断是否已记录，避免界面每 150ms 轮询时重复写日志。 */
    @Volatile private var verifyStallLogged = false

    /** 本轮验证页首次到达源站（Discourse 404）的时间，0 表示尚未到达。 */
    @Volatile private var verifyOriginReachedAt = 0L

    /** 进入本轮验证前已存在的 cf_clearance，再次读到它们不算新签发。 */
    private var verifyStaleClearances: Set<String> = emptySet()
    private var verifyProbeJob: Job? = null
    private var prewarmJob: Job? = null
    private var mainGeckoInitJob: Job? = null

    /** 系统 WebView 的 /challenge 导航是否已穿过 Cloudflare 到达 Discourse 源站（返回源站 404），供路径 B 判断就绪。 */
    @Volatile private var challengeOriginReached = false

    /** 验证页是否已确认存在需要人工交互的挑战组件（如 Turnstile），仅在为 true 时揭开遮罩露出验证页。 */
    @Volatile private var verificationNeedsInteraction = false

    /** 本轮验证是否曾见到 Cloudflare 挑战标记，用于判断后续跳回裸 /challenge 是否为验证完成跳转。 */
    @Volatile private var verificationSawChallenge = false

    /** 首次探测到活跃挑战元素的时间戳，给自动过盾留出短暂窗口，避免非交互挑战一闪而过。 */
    @Volatile private var activeChallengeDetectedAt = 0L

    fun isChallengeOriginReached(): Boolean = challengeOriginReached

    /** 最近一次静默重验证的结论；TIMEOUT 仅表示等待内未取得凭据，不证明需要人工交互。 */
    enum class SilentOutcome { CLEARED, CHALLENGE, UNREACHABLE, TIMEOUT }

    @Volatile var lastSilentOutcome: SilentOutcome? = null
        private set
    /** 最近一次成功完成 CF 验证/重验证的时间戳（elapsedRealtime），用于并发过盾锁内去重合并。 */
    @Volatile private var lastClearedAtElapsed: Long = 0L
    private var verificationClearance: String? = null

    private var onAuthCallback: ((success: Boolean, errorMsg: String?, need2FA: Boolean) -> Unit)? = null
    private var cachedLogin: String = ""
    private var cachedPassword: String = ""
    /** 非空表示当前内联页处于"发送登录邮件"模式，值为目标邮箱。 */
    private var cachedEmailSend: String? = null
    /** 非空表示当前内联页处于"邮件链接/验证码登录兑换"模式，值为提取出的 token。 */
    private var cachedEmailLoginToken: String? = null
    /** 非空表示认证 WebView 正在整页打开邮件登录链接（Fluxdo 同款兑换方式）。 */
    private var pendingEmailLink: String? = null
    private var emailLinkCompleting: Boolean = false
    /** 首个 onPageFinished 往往早于 Cookie 落盘，剩余复查次数。 */
    private var emailLinkRechecksRemaining: Int = 0
    private var cachedSecondFactor: String? = null
    private var isDarkCached: Boolean = false
    private var authSubmitJob: Job? = null
    @Volatile
    private var authSubmitCompletion: CompletableDeferred<AuthSubmitResult>? = null
    @Volatile
    private var authAbortAcknowledgement: CompletableDeferred<Unit>? = null
    @Volatile
    private var authAbortSubmissionId: String? = null
    private var authAbortJob: Job? = null
    @Volatile
    private var cancelledSubmissionId: String? = null
    @Volatile
    private var authSubmissionGeneration: Long = 0L
    @Volatile
    private var currentSubmissionId: String? = null
    @Volatile
    private var currentSubmissionCallback: ((Boolean, String?, Boolean) -> Unit)? = null
    private var isInCfMode: Boolean = false
    private var loginCfRecoveryId: Long? = null
    private var cfRetryUsed: Boolean = false
    private var cfProbeScheduled: Boolean = false
    private var cfClearanceBeforeChallenge: String? = null
    private var cfStaleClearances: Set<String> = emptySet()

    /** 本次 CF 弹窗是否见过挑战标记；见过之后跳回裸 /challenge 才是完成跳转，需要先盖住回源 404。 */
    private var cfSawChallenge: Boolean = false
    private var pendingHcaptchaToken: String? = null
    private var hcaptchaTempReady: Boolean = false
    private var retryingLoginWithHcaptchaToken: Boolean = false
    private var awaitingSecondFactorLogin: Boolean = false
    private var attachedActivityContext: Context? = null
    private var cfDialog: Dialog? = null
    private var cfWebView: WebView? = null
    private var cfGeckoView: org.mozilla.geckoview.GeckoView? = null
    private var cfGeckoSession: org.mozilla.geckoview.GeckoSession? = null
    private var authGeckoView: org.mozilla.geckoview.GeckoView? = null
    private var authGeckoSession: org.mozilla.geckoview.GeckoSession? = null
    private var mainGeckoView: org.mozilla.geckoview.GeckoView? = null
    private var mainGeckoSession: org.mozilla.geckoview.GeckoSession? = null
    private val geckoBridges = mutableMapOf<View, GeckoAuthBridge>()
    private var authGeckoInitJob: Job? = null
    private var cfGeckoInitJob: Job? = null
    private var authGeckoCookiesPrepared = false
    private var authGeckoPreparing = false

    private fun currentAuthView(): View? = authGeckoView ?: authWebView

    private fun currentCfView(): View? = cfGeckoView ?: cfWebView

    private fun evaluateBrowserScript(view: View, script: String, callback: ((String?) -> Unit)? = null) {
        if (view is WebView) {
            view.evaluateJavascript(script) { callback?.invoke(it) }
        } else {
            val bridge = geckoBridges[view]
            if (bridge == null) callback?.invoke(null)
            else bridge.evaluateJavascript(script) { callback?.invoke(it) }
        }
    }

    private fun stopBrowserLoading(view: View) {
        if (view is WebView) view.stopLoading()
        else (view as? GeckoView)?.session?.stop()
    }

    private suspend fun syncBrowserCookies(
        view: View,
        authoritativeAuth: Boolean = false,
        authorityStillValid: (() -> Boolean)? = null,
    ): Map<String, String> {
        val bridge = geckoBridges[view]
        val generation = store.sessionGeneration
        fun checkSyncCurrent() {
            if (store.sessionChanging || generation != store.sessionGeneration || authorityStillValid?.invoke() == false) {
                throw CancellationException("浏览器会话已变更")
            }
        }
        checkSyncCurrent()
        val previousCookies = store.syncFromWebView()
        val staleForSync = if (isInCfMode) {
            cfStaleClearances + setOfNotNull(cfClearanceBeforeChallenge).filter { it.isNotBlank() }
        } else {
            setOfNotNull(cfClearanceBeforeChallenge).filter { it.isNotBlank() }.toSet()
        }
        var bridgeClearance: String? = null
        if (bridge != null) {
            val snapshot = bridge.readCookies()
            checkSyncCurrent()
            val cookies = (0 until snapshot.length()).mapNotNull { index ->
                val cookie = snapshot.getJSONObject(index)
                val domain = cookie.optString("domain").removePrefix(".")
                if (domain != Config.HOST) return@mapNotNull null
                val name = cookie.getString("name")
                val value = cookie.getString("value")
                if (name.equals(CF_CLEARANCE_COOKIE, ignoreCase = true) && value.isNotBlank()) {
                    val isCandidateFresh = value !in staleForSync && !store.isClearanceChallenged(value)
                    if (isCandidateFresh || bridgeClearance == null) {
                        bridgeClearance = value
                    }
                    if (!isCandidateFresh && bridgeClearance != null && bridgeClearance != value) {
                        return@mapNotNull null
                    }
                }
                Cookie.Builder().apply {
                    name(name)
                    value(value)
                    if (cookie.optBoolean("hostOnly")) hostOnlyDomain(domain) else domain(domain)
                    path(cookie.optString("path", "/"))
                    if (cookie.optBoolean("secure")) secure()
                    if (cookie.optBoolean("httpOnly")) httpOnly()
                    if (cookie.has("expirationDate")) expiresAt((cookie.getDouble("expirationDate") * 1000).toLong())
                }.build()
            }
            if (authoritativeAuth) {
                val tokens = cookies.filter {
                    it.name == Config.SESSION_COOKIE && it.domain == Config.HOST && it.path == "/" &&
                        it.value.isNotBlank() && it.expiresAt > System.currentTimeMillis()
                }.map { it.value }.distinct()
                check(tokens.size == 1) { "登录凭据缺失或存在冲突，无法确认登录" }
            }
            store.applyResponseCookies(
                cookies,
                requestGeneration = generation,
                sentCookies = previousCookies,
                authoritativeAuth = authoritativeAuth,
                authorityStillValid = authorityStillValid,
                authBrowserSnapshot = view === currentAuthView(),
            )
        }
        checkSyncCurrent()
        val geckoClearance = bridgeClearance?.takeIf { it !in staleForSync && !store.isClearanceChallenged(it) }
            ?: if (view is GeckoView) {
                val context = attachedActivityContext ?: appContext
                withContext(Dispatchers.IO) {
                    selectFreshCfClearance(GeckoManager.cfClearancesFromDb(context), staleForSync)
                }
            } else bridgeClearance
        checkSyncCurrent()
        if (
            !geckoClearance.isNullOrBlank() &&
            !store.isClearanceChallenged(geckoClearance) &&
            (!isInCfMode || isFreshCfClearance(geckoClearance))
        ) {
            adoptGeckoClearance(geckoClearance)
        }
        return store.syncFromWebView()
    }

    private fun cookieSyncGuard(view: View): () -> Boolean {
        val generation = store.sessionGeneration
        val submissionGeneration = authSubmissionGeneration
        val authView = view === currentAuthView()
        val challengeView = view === currentCfView()
        return {
            !store.sessionChanging && generation == store.sessionGeneration &&
                submissionGeneration == authSubmissionGeneration &&
                ((authView && view === currentAuthView()) || (challengeView && view === currentCfView()))
        }
    }

    private fun refreshBrowserCookies(view: View, onComplete: () -> Unit) {
        val stillCurrent = cookieSyncGuard(view)
        scope.launch(Dispatchers.Main) {
            try {
                syncBrowserCookies(view, authorityStillValid = stillCurrent)
                if (stillCurrent()) onComplete()
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                diagnostics.log("浏览器 Cookie 同步失败: ${error.message}")
                if (isInCfMode && currentCfView() === view) scheduleCfCompletionProbe(view)
            }
        }
    }

    private fun onGeckoAuthCallback(view: View, name: String, payload: String) {
        if (view === mainGeckoView) {
            onVerifyGeckoCallback(view, name, payload)
            return
        }
        if (name == "onAuthAborted" && geckoBridges.containsKey(view)) {
            Bridge().onAuthAborted(payload)
            return
        }
        if (currentAuthView() !== view && currentCfView() !== view) return
        val bridge = Bridge()
        when (name) {
            "onAuthResult" -> {
                val result = runCatching { JSONObject(payload) }.getOrElse {
                    diagnostics.log("忽略无法解析的登录回执")
                    return
                }
                val submissionId = result.optString("submissionId")
                val completion = authSubmitCompletion
                val generation = store.sessionGeneration
                val submissionGeneration = authSubmissionGeneration
                fun stillCurrent(): Boolean =
                    currentAuthView() === view && !store.sessionChanging && generation == store.sessionGeneration &&
                        submissionGeneration == authSubmissionGeneration && submissionId.isNotBlank() &&
                        submissionId == currentSubmissionId && cancelledSubmissionId != submissionId &&
                        completion != null && authSubmitCompletion === completion && !completion.isCompleted
                if (!stillCurrent()) {
                    diagnostics.log("忽略已过期或已取消的登录回执，未同步 Cookie")
                    return
                }
                val authoritativeAuth = isSuccessfulSessionAuthResult(result, !cachedSecondFactor.isNullOrBlank())
                scope.launch(Dispatchers.Main) {
                    try {
                        syncBrowserCookies(view, authoritativeAuth, ::stillCurrent)
                        if (stillCurrent()) bridge.onAuthResult(payload)
                    } catch (_: CancellationException) {
                    } catch (error: Exception) {
                        diagnostics.log("登录 Cookie 同步失败: ${error.javaClass.simpleName}")
                        if (stillCurrent()) {
                            bridge.onAuthResult(result.put("phase", "exception")
                                .put("body", "登录凭据保存失败，请稍后重试").toString())
                        }
                    }
                }
            }
            "onHcaptchaPass" -> bridge.onHcaptchaPass(payload)
            "onAuthAborted" -> bridge.onAuthAborted(payload)
            "onDiagnostic" -> {
                if (payload == "Gecko认证桥就绪") Log.d("GeckoAuthBridge", "认证页面与 Native 双向回调已验证")
                bridge.onDiagnostic(payload)
            }
            "onChallengeOriginReached" -> if (currentCfView() === view) awaitFreshClearanceThen(view, payload)
            "onChallengeNavigating" -> if (currentCfView() === view) view.alpha = 0f
        }
    }

    private enum class AuthSubmitResult {
        RESPONSE,
        ABORTED,
        SCRIPT_MISSING,
    }

    fun cancelCaptchaLogin() {
        authSubmissionGeneration += 1L
        authGeckoInitJob?.cancel()
        val submissionId = currentSubmissionId
        val completion = authSubmitCompletion
        val view = currentAuthView()
        val hasActiveSubmission = submissionId != null && completion != null && !completion.isCompleted
        if (submissionId != null && completion != null && !completion.isCompleted) {
            cancelledSubmissionId = submissionId
            currentSubmissionCallback = null
            if (view != null) {
                abortActiveAuthSubmission(submissionId, view, completion)
            } else {
                if (authAbortSubmissionId != submissionId || authAbortJob?.isActive != true) {
                    currentSubmissionId = null
                    currentSubmissionCallback = null
                    completion.complete(AuthSubmitResult.ABORTED)
                }
            }
        } else {
            currentSubmissionId = null
            currentSubmissionCallback = null
            authSubmitJob?.cancel()
            authSubmitJob = null
            completion?.complete(AuthSubmitResult.ABORTED)
            authSubmitCompletion = null
        }
        isInCfMode = false
        cfRetryUsed = false
        cfProbeScheduled = false
        cfCompletionProbing = false
        cfQuietRounds = 0
        cfClearanceBeforeChallenge = null
        cfStaleClearances = emptySet()
        cfSawChallenge = false
        pendingHcaptchaToken = null
        hcaptchaTempReady = false
        retryingLoginWithHcaptchaToken = false
        awaitingSecondFactorLogin = false
        pendingAuthHtml = null
        authHtmlInjected = false
        cachedEmailSend = null
        cachedEmailLoginToken = null
        dismissCfDialog()
        onAuthCallback = null
        pendingEmailLink = null
        emailLinkCompleting = false
        if (!hasActiveSubmission) {
            currentAuthView()?.let { authView ->
                evaluateBrowserScript(authView, "if (typeof window.__linuxdoAbortLogin === 'function') window.__linuxdoAbortLogin();")
                destroyAuthBrowser(authView)
            }
        }
    }

    fun hasPendingSecondFactorLogin(): Boolean = awaitingSecondFactorLogin

    /**
     * Fluxdo 的 2FA 重试继续使用已有 WebView 的 h_captcha_temp_id；不要退回 OkHttp，
     * 否则 Cloudflare 指纹与登录临时会话都会断开。
     */
    fun resumeSecondFactorLogin(
        secondFactorToken: String,
        secondFactorMethod: Int,
        onResult: (success: Boolean, errorMsg: String?, need2FA: Boolean) -> Unit,
    ): Boolean {
        if (!awaitingSecondFactorLogin || secondFactorToken.isBlank()) return false
        onAuthCallback = onResult
        cachedSecondFactor = secondFactorToken
        cachedSecondFactorMethod = secondFactorMethod
        isInCfMode = false
        val view = currentAuthView() ?: return false
        view.post {
            executeWebLogin(view, null, cachedSecondFactor, cachedSecondFactorMethod)
        }
        return true
    }

    fun discardPendingSecondFactorLogin() {
        awaitingSecondFactorLogin = false
        cachedSecondFactor = null
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, status: Int, mitigated: String, body: String) {
            onResult(id, status, mitigated, body, "")
        }

        @JavascriptInterface
        fun onResult(id: String, status: Int, mitigated: String, body: String, retryAfter: String) {
            val retryAfterSeconds = if (status == 429 || status == 503) {
                HttpHeaderUtils.extractRetryAfterSeconds(retryAfter, body = body)
            } else {
                HttpHeaderUtils.parseRetryAfter(retryAfter)
            }
            pending.remove(id)?.complete(
                TransportResult(
                    status = status,
                    body = body,
                    challenged = ChallengeDetector.isChallenge(status, mitigated, body),
                    retryAfterSeconds = retryAfterSeconds,
                    cfMitigated = mitigated,
                ),
            )
        }

        @JavascriptInterface
        fun onProbeComplete(reqId: String, payloadJson: String) {
            probeRequests.remove(reqId)?.complete(payloadJson)
        }

        @JavascriptInterface
        fun onChallengeNavigating(reason: String) {
            cfWebView?.post {
                if (isInCfMode) {
                    cfWebView?.alpha = 0f
                }
            }
        }

        @JavascriptInterface
        fun onChallengeOriginReached(reason: String) {
            challengeOriginReached = true
            cfWebView?.post {
                val view = cfWebView
                if (isInCfMode && view != null) {
                    view.alpha = 0f
                    awaitFreshClearanceThen(view, "源站 404 页面($reason)")
                }
            }
        }

        @JavascriptInterface
        fun onAuthResult(jsonStr: String) {
            var resultSubmissionId = ""
            try {
                val obj = JSONObject(jsonStr)
                val submissionId = obj.optString("submissionId")
                resultSubmissionId = submissionId
                val completion = authSubmitCompletion
                if (submissionId.isBlank() || submissionId != currentSubmissionId || completion == null || completion.isCompleted) {
                    diagnostics.log("忽略已过期或已取消的登录结果: subId=$submissionId, current=$currentSubmissionId")
                    return
                }
                val phase = obj.optString("phase")
                val status = obj.optInt("status")
                diagnostics.log("安全验证响应: phase=$phase HTTP=$status")
                val body = obj.optString("body")
                val retryAfterStr = obj.optString("retryAfter")
                val retryAfterSeconds = HttpHeaderUtils.parseRetryAfter(retryAfterStr)
                val callback = currentSubmissionCallback?.takeIf { cancelledSubmissionId != submissionId }

                if (status == 429) {
                    TopicRepository.enterRateLimitCooldown("WebView 登录流程($phase)", retryAfterSeconds, sessionStore = store)
                }
                currentSubmissionId = null
                currentSubmissionCallback = null
                completion.complete(AuthSubmitResult.RESPONSE)
                if (authSubmitCompletion === completion) authSubmitCompletion = null

                if (cancelledSubmissionId == submissionId) return
                val cb = callback ?: return
                if (status == 429) {
                    awaitingSecondFactorLogin = false
                    cb(false, "操作过于频繁，请稍后再试", false)
                    return
                }

                when (phase) {
                    "session" -> {
                        if (!pendingHcaptchaToken.isNullOrBlank()) hcaptchaTempReady = true
                        retryingLoginWithHcaptchaToken = false
                        pendingHcaptchaToken = null
                        val resObj = try { JSONObject(body) } catch (_: Exception) { null }
                        val error = resObj?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: resObj?.optString("errors")?.takeIf { it.isNotBlank() && it != "[]" && it != "null" }
                        val user = resObj?.optJSONObject("user")?.optString("username")

                        val has2faToken = !cachedSecondFactor.isNullOrBlank()
                        val is2FA = isSecondFactorResponse(resObj, has2faToken)

                        if (is2FA) {
                            awaitingSecondFactorLogin = true
                            val msg = extractSecondFactorMessage(resObj, has2faToken)
                            cb(false, msg, true)
                        } else if (!error.isNullOrBlank() && user.isNullOrBlank()) {
                            awaitingSecondFactorLogin = false
                            cb(false, error, false)
                        } else if (isSuccessfulSessionAuthResult(obj, has2faToken)) {
                            awaitingSecondFactorLogin = false
                            cb(true, null, false)
                        } else {
                            awaitingSecondFactorLogin = false
                            cb(false, "登录失败: HTTP $status", false)
                        }
                    }
                    "cf_needed", "csrf" -> {
                        if (obj.optBoolean("captchaAccepted")) {
                            hcaptchaTempReady = true
                            pendingHcaptchaToken = null
                        } else {
                            obj.optString("hcaptchaToken")
                                .takeIf { it.isNotBlank() }
                                ?.let { pendingHcaptchaToken = it }
                        }
                        if (!cfRetryUsed) {
                            cfRetryUsed = true
                            diagnostics.log("CSRF 遇到 Cloudflare 盾 ($status)，正在安全弹窗内启动 /challenge 验证...")
                            launchCfChallenge()
                        } else {
                            cb(false, "Cloudflare 验证已失效，请重试 (CSRF $status)", false)
                        }
                    }
                    "hcaptcha" -> {
                        hcaptchaTempReady = false
                        if (retryingLoginWithHcaptchaToken || cachedEmailSend != null || cachedEmailLoginToken != null) {
                            // 令牌可能在用户完成 CF 的过程中到期；回退到可见 hCaptcha，
                            // 不能把已经通过的 CF 也当作失败。
                            retryingLoginWithHcaptchaToken = false
                            pendingHcaptchaToken = null
                            diagnostics.log("hCaptcha 令牌已过期，重新展示图形验证")
                            currentAuthView()?.post {
                                loadCaptchaLogin(
                                    login = cachedLogin,
                                    password = cachedPassword,
                                    secondFactorToken = cachedSecondFactor,
                                    secondFactorMethod = cachedSecondFactorMethod,
                                    isDark = isDarkCached,
                                    resetCfRetry = false,
                                    emailSendTo = cachedEmailSend,
                                    emailLoginToken = cachedEmailLoginToken,
                                    onResult = cb,
                                )
                            }
                        } else {
                            awaitingSecondFactorLogin = false
                            cb(false, "人机验证未通过，请重试", false)
                        }
                    }
                    "email_sent" -> {
                        diagnostics.log("登录邮件已通过内联安全验证发送")
                        awaitingSecondFactorLogin = false
                        cb(true, null, false)
                    }
                    "exception" -> cb(false, "网络请求异常: $body", false)
                    else -> cb(false, "未知异常 ($phase)", false)
                }
            } catch (e: Exception) {
                diagnostics.log("解析登录结果失败: ${e.message}")
                if (resultSubmissionId.isNotBlank() && resultSubmissionId == currentSubmissionId) {
                    val callback = currentSubmissionCallback?.takeIf { cancelledSubmissionId != resultSubmissionId }
                    currentSubmissionId = null
                    currentSubmissionCallback = null
                    authSubmitCompletion?.complete(AuthSubmitResult.RESPONSE)
                    authSubmitCompletion = null
                    callback?.invoke(false, "响应解析失败: ${e.message}", false)
                }
            }
        }

        @JavascriptInterface
        fun onAuthAborted(submissionId: String) {
            if (submissionId.isBlank() || submissionId != currentSubmissionId || submissionId != authAbortSubmissionId) {
                diagnostics.log("忽略过期登录中止回执: subId=$submissionId, current=$currentSubmissionId")
                return
            }
            authAbortAcknowledgement?.complete(Unit)
        }

        @JavascriptInterface
        fun onHcaptchaPass(token: String) {
            if (token.isBlank()) return
            val view = currentAuthView() ?: return
            view.postDelayed(
                {
                    if (currentAuthView() !== view || onAuthCallback == null) return@postDelayed
                    diagnostics.log("hCaptcha 验证通过，在原浏览器会话中提交登录")
                    pendingHcaptchaToken = token
                    hcaptchaTempReady = false
                    retryingLoginWithHcaptchaToken = false
                    if (view is WebView) {
                        store.restoreMissingToWebView(exclude = setOf(Config.SESSION_COOKIE))
                        CookieManager.getInstance().flush()
                    }
                    executeWebLogin(view, token, cachedSecondFactor, cachedSecondFactorMethod)
                },
                HCAPTCHA_CALLBACK_SETTLE_DELAY_MS,
            )
        }

        /** 登录页在关键节点回传的现场快照，只写诊断日志，不参与流程判定。 */
        @JavascriptInterface
        fun onDiagnostic(message: String) {
            if (message.isBlank()) return
            diagnostics.log("登录页诊断: $message")
        }
    }

    /**
     * Fluxdo 把 CF 验证放在独立 WebView 中，登录页保持存活，以便验证后直接复用
     * hCaptcha token / h_captcha_temp_id 继续提交 session。Android 也必须保持这个边界。
     */
    private fun launchCfChallenge() {
        if (loginCfRecoveryId != null) return
        val loginView = currentAuthView() ?: return
        val stillCurrent = cookieSyncGuard(loginView)
        isInCfMode = true
        cfProbeScheduled = false
        cfCompletionProbing = false
        cfQuietRounds = 0
        cfSawChallenge = false
        scope.launch(Dispatchers.Main) {
            if (!isInCfMode || !stillCurrent() || loginCfRecoveryId != null) return@launch
            val recoveryId = store.beginClearanceRecovery()
            loginCfRecoveryId = recoveryId
            var dialogOwnsRecovery = false
            try {
                if (!isInCfMode || !stillCurrent()) return@launch
                syncBrowserCookies(loginView, authorityStillValid = stillCurrent)
                val context = attachedActivityContext ?: appContext
                val geckoStale = withContext(Dispatchers.IO) { GeckoManager.cfClearancesFromDb(context) }
                if (!isInCfMode || !stillCurrent()) return@launch
                cfClearanceBeforeChallenge = currentCfClearance()
                cfStaleClearances = store.cfClearancesForVerification() + geckoStale + setOfNotNull(cfClearanceBeforeChallenge)
                geckoBridges[loginView]?.clearCfClearance()
                if (!isInCfMode || !stillCurrent()) return@launch
                store.clearCookie(CF_CLEARANCE_COOKIE)
                CookieManager.getInstance().flush()
                if (!isInCfMode || !stillCurrent()) return@launch
                diagnostics.log("保留原 hCaptcha 会话，打开独立 Cloudflare 验证窗口 (排除旧凭据 ${cfStaleClearances.size} 枚)")
                showCfChallengeDialog()
                dialogOwnsRecovery = cfDialog?.isShowing == true
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (!stillCurrent()) return@launch
                isInCfMode = false
                diagnostics.log("CF 验证会话初始化失败: ${error.message}")
                dismissCfDialog()
                onAuthCallback?.invoke(false, "无法初始化安全验证，请重试", false)
            } finally {
                if (!dialogOwnsRecovery || !stillCurrent()) endLoginClearanceRecovery(recoveryId)
            }
        }
    }

    private fun showCfChallengeDialog() {
        if (!isInCfMode || cfDialog?.isShowing == true) return
        val context = attachedActivityContext ?: run {
            isInCfMode = false
            onAuthCallback?.invoke(false, "无法打开 Cloudflare 验证窗口", false)
            return
        }

        diagnostics.log(
            "正在使用 Mozilla GeckoView 打开 Cloudflare 安全验证窗口 (${GeckoManager.runtimeNetworkSummary(context)})...",
        )

        val geckoView = org.linuxdo.android.net.gecko.GeckoManager.createGeckoView(context)
        val geckoSession = org.linuxdo.android.net.gecko.GeckoManager.createChallengeSession(
            context = context,
            onLocationChanged = { url ->
                diagnostics.log("CF GeckoView 导航至: ${GeckoManager.diagnosticUrl(url)}")
                // 挑战完成后跳回裸 /challenge 即将回源 404，先盖住；不依赖页面里的卸载钩子（CSP 下可能装不上）
                if (cfSawChallenge && isBareChallengeUrl(url) && cfGeckoView === geckoView) geckoView.alpha = 0f
            },
            onPageFinished = { url ->
                diagnostics.log("CF GeckoView 页面加载完成: ${GeckoManager.diagnosticUrl(url)}")
                detectCfChallengeCompletion(geckoView, url)
            },
            onPageError = { url, error ->
                diagnostics.log("CF GeckoView 加载失败 [${error.category}/${error.code}]: ${GeckoManager.diagnosticUrl(url)}")
                geckoView.alpha = 1f
            },
            onDiagnostic = { message ->
                if (isInCfMode && cfGeckoView === geckoView) {
                    diagnostics.log("登录CF窗口 Gecko $message")
                }
            },
        )
        geckoView.setSession(geckoSession)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val isDark = isDarkCached
        val surfaceColor = if (isDark) Color.rgb(28, 28, 30) else Color.WHITE
        val labelColor = if (isDark) Color.rgb(245, 245, 247) else Color.rgb(28, 28, 30)
        val secondaryColor = if (isDark) Color.rgb(142, 142, 147) else Color.rgb(142, 142, 147)
        val dividerColor = if (isDark) Color.rgb(56, 56, 58) else Color.rgb(229, 229, 234)
        val warnBg = if (isDark) Color.rgb(58, 46, 18) else Color.rgb(254, 243, 199)
        val warnText = if (isDark) Color.rgb(253, 230, 138) else Color.rgb(146, 64, 14)
        val warnBtn = if (isDark) Color.rgb(245, 158, 11) else Color.rgb(180, 83, 9)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(surfaceColor)
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val icon = TextView(context).apply {
            text = "🛡️"
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val title = TextView(context).apply {
            text = "安全验证"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        val close = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(secondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) Color.rgb(44, 44, 46) else Color.rgb(242, 242, 247))
            }
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(View(context).apply { setBackgroundColor(dividerColor) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val timeoutBanner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(warnBg)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        val warnMsg = TextView(context).apply {
            text = "验证尚未完成，请完成页面验证或点击刷新重试"
            setTextColor(warnText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.2f)
        }
        val retryBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "刷新"
            setTextColor(warnBtn)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                geckoSession.reload()
            }
        }
        timeoutBanner.addView(warnMsg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeoutBanner.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        root.addView(timeoutBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(geckoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(false)
            close.setOnClickListener { dismiss() }
            setOnDismissListener {
                if (cfDialog === this && isInCfMode) {
                    endLoginClearanceRecovery()
                    val dismissedSession = cfGeckoSession
                    val dismissedView = cfGeckoView
                    cfDialog = null
                    cfGeckoView = null
                    cfGeckoSession = null
                    isInCfMode = false
                    cfGeckoInitJob?.cancel()
                    dismissedView?.let { geckoBridges.remove(it)?.close() }
                    dismissedSession?.close()
                    dismissedView?.releaseSession()
                    onAuthCallback?.invoke(false, "Cloudflare 验证已取消，请重试", false)
                }
            }
        }
        cfDialog = dialog
        cfGeckoView = geckoView
        cfGeckoSession = geckoSession
        val stillCurrent = cookieSyncGuard(geckoView)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.92f).toInt(),
            )
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            attributes = attributes.apply { dimAmount = 0.5f }
        }
        geckoView.requestFocus()
        geckoView.postDelayed({
            if (isInCfMode && cfDialog?.isShowing == true && !isFreshCfClearance(currentCfClearance())) {
                timeoutBanner.visibility = View.VISIBLE
            }
        }, 5_000L)
        cfGeckoInitJob = scope.launch(Dispatchers.Main) {
            try {
                if (!stillCurrent()) return@launch
                val bridge = GeckoAuthBridge.create(
                    context,
                    geckoSession,
                    onReady = { onCfGeckoReady(geckoView) },
                    onCallback = { name, payload -> onGeckoAuthCallback(geckoView, name, payload) },
                )
                if (!isInCfMode || !stillCurrent()) {
                    bridge.close()
                    return@launch
                }
                geckoBridges[geckoView] = bridge
                geckoSession.loadUri("${Config.BASE_URL}$CF_CHALLENGE_PATH")
            } catch (_: CancellationException) {
                if (stillCurrent()) {
                    isInCfMode = false
                    dismissCfDialog()
                    onAuthCallback?.invoke(false, "安全验证初始化已取消，请重试", false)
                }
            } catch (error: Exception) {
                diagnostics.log("CF 认证桥初始化失败: ${error.message}")
                if (stillCurrent()) {
                    isInCfMode = false
                    dismissCfDialog()
                    onAuthCallback?.invoke(false, "安全验证组件初始化失败，请重试", false)
                }
            }
        }
    }

    private fun onCfGeckoReady(view: GeckoView) {
        if (!isInCfMode || cfGeckoView !== view) return
        installOriginFallbackMask(view)
        scope.launch(Dispatchers.Main) {
            val markers = probeGeckoMarkers(view)
            if (!isInCfMode || cfGeckoView !== view) return@launch
            if (markers?.get(MARKER_ORIGIN_NOT_FOUND) == true && markers[MARKER_CHALLENGE] != true) {
                awaitFreshClearanceThen(view, "已到达源站，等待新的安全凭据")
            } else {
                if (markers?.get(MARKER_CHALLENGE) == true) cfSawChallenge = true
                view.alpha = 1f
                hideOriginFallbackMask(view)
                scheduleCfCompletionProbe(view)
            }
        }
    }

    /** CF 弹窗当前页是否仍有挑战标记：GeckoView 走隔离环境探测，系统 WebView 沿用页面脚本。null 表示本轮没读到。 */
    private fun probeCfActiveChallenge(view: View, onResult: (Boolean?) -> Unit) {
        if (view is GeckoView) {
            scope.launch(Dispatchers.Main) { onResult(probeGeckoMarkers(view)?.get(MARKER_CHALLENGE)) }
        } else {
            evaluateBrowserScript(view, ACTIVE_CHALLENGE_PROBE_JS) { raw ->
                onResult(when (decodeJsValue(raw)) { "false" -> false; "true" -> true; else -> null })
            }
        }
    }

    /** 把 Gecko 签发的 cf_clearance 写进系统 CookieManager 并同步到加密存储，OkHttp 从那里取。只搬 cf_clearance，不动会话 Cookie。 */
    private fun adoptGeckoClearance(clearance: String) {
        CookieManager.getInstance().setCookie(Config.BASE_URL, "$CF_CLEARANCE_COOKIE=$clearance; Path=/; Domain=.linux.do; Secure; HttpOnly; SameSite=None")
        CookieManager.getInstance().flush()
        store.syncFromWebView()
    }

    private fun detectCfChallengeCompletion(challengeView: View, url: String?) {
        if (!isInCfMode) return
        diagnostics.log("Cloudflare 页面已完成一次加载: ${url.orEmpty()}")
        scheduleCfCompletionProbe(challengeView, 250L)
    }

    /** CF 成功时不保证会再触发导航回调，因此持续观察新的 cf_clearance Cookie。 */
    private fun scheduleCfCompletionProbe(challengeView: View, delayMs: Long = CF_PROBE_INTERVAL_MS) {
        if (
            !isInCfMode ||
            cfProbeScheduled ||
            cfCompletionProbing ||
            currentCfView() !== challengeView ||
            cfDialog?.isShowing != true
        ) return
        cfProbeScheduled = true
        challengeView.postDelayed(
            {
                cfProbeScheduled = false
                if (!isInCfMode || currentCfView() !== challengeView || cfDialog?.isShowing != true) return@postDelayed
                if (cfCompletionProbing) return@postDelayed
                refreshBrowserCookies(challengeView) {
                    if (!isInCfMode || currentCfView() !== challengeView || cfCompletionProbing) return@refreshBrowserCookies
                    val freshClearance = currentCfClearance()
                    if (!isFreshCfClearance(freshClearance)) {
                        scheduleCfCompletionProbe(challengeView)
                        return@refreshBrowserCookies
                    }
                    probeCfActiveChallenge(challengeView) { active ->
                        if (!isInCfMode || currentCfView() !== challengeView || cfDialog?.isShowing != true) {
                            return@probeCfActiveChallenge
                        }
                        if (active == true) cfSawChallenge = true
                        // 沿用 master：新凭据出现后仍需连续确认挑战已结束；没读到标记不算确认。
                        if (active != false) {
                            cfQuietRounds = 0
                            scheduleCfCompletionProbe(challengeView)
                            return@probeCfActiveChallenge
                        }
                        cfQuietRounds++
                        if (cfQuietRounds >= CF_QUIET_ROUNDS_REQUIRED) {
                            completeCfChallenge(challengeView, "新 cf_clearance 且页面连续 $cfQuietRounds 轮无挑战标记")
                        } else {
                            scheduleCfCompletionProbe(challengeView)
                        }
                    }
                }
            },
            delayMs,
        )
    }

    /**
     * "没被 CF 接管"不等于"本轮签发了新凭据"。
     *
     * 源站 404 只说明这次请求回了源,而旧 clearance 残留时 CF 同样会直接放行回源。
     * 此前在这两种情况下都直接收口,于是拿着一枚已被判废的旧 clearance 去重放登录,
     * 必然再撞一次 403,最后以"Cloudflare 验证已失效"收场。改为先确认新 clearance
     * 真的出现;等不到就如实报失败,而不是让用户再看一遍同样的错误。
     */
    private fun awaitFreshClearanceThen(view: View, reason: String) {
        if (!isInCfMode || cfCompletionProbing) return
        val stillCurrent = cookieSyncGuard(view)
        // 已回源：等新凭据期间不露出 Discourse 的 404 页
        view.alpha = 0f
        cfCompletionProbing = true
        cfProbeScheduled = false
        diagnostics.log("$reason，开始确认是否签发了新 cf_clearance")
        val deadline = SystemClock.elapsedRealtime() + CF_COMPLETION_PROBE_TIMEOUT_MS

        scope.launch(Dispatchers.Main) {
            delay(CF_COOKIE_SETTLE_DELAY_MS)
            while (isInCfMode && stillCurrent() && cfDialog?.isShowing == true) {
                try {
                    syncBrowserCookies(view, authorityStillValid = stillCurrent)
                } catch (_: CancellationException) {
                    return@launch
                } catch (error: Exception) {
                    diagnostics.log("等待 CF 凭据同步: ${error.message}")
                }
                if (!isInCfMode || !stillCurrent()) return@launch
                if (isFreshCfClearance(currentCfClearance())) {
                    cfCompletionProbing = false
                    completeCfChallenge(view, reason)
                    return@launch
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    cfCompletionProbing = false
                    isInCfMode = false
                    diagnostics.log("$reason，但未签发新 cf_clearance，验证未完成")
                    dismissCfDialog()
                    onAuthCallback?.invoke(false, "Cloudflare 未签发新的验证凭据，请重试", false)
                    return@launch
                }
                delay(CF_PROBE_INTERVAL_MS)
            }
        }
    }

    /**
     * 与 Fluxdo 一致：新 clearance 出现即收口。不要在这里再请求 /session/csrf，
     * 否则验证后的短暂限流会把成功的 CF 流程误判为失败。
     */
    private fun completeCfChallenge(probeView: View, reason: String) {
        if (!isInCfMode) return
        val loginView = currentAuthView() ?: run {
            isInCfMode = false
            dismissCfDialog()
            return
        }
        diagnostics.log(
            "Cloudflare 验证完成($reason)，clearance=${clearanceFingerprint(currentCfClearance())}，" +
                "关闭验证窗口并恢复原认证请求",
        )
        isInCfMode = false
        cfProbeScheduled = false
        cfCompletionProbing = false
        try {
            CookieManager.getInstance().flush()
            store.syncFromWebView()
        } finally {
            dismissCfDialog()
        }
        // 对齐 Fluxdo：底层网络栈沉淀后直接重放，绝不手动回灌以破坏原生 Cookie
        loginView.postDelayed(
            {
                if (currentAuthView() !== loginView || onAuthCallback == null) return@postDelayed
                CookieManager.getInstance().flush()
                resumePendingWebLogin(loginView)
            },
            CF_SETTLE_DELAY_MS,
        )
    }

    private fun currentCfClearance(): String? {
        if (!store.sessionChanging && Looper.myLooper() == Looper.getMainLooper()) {
            return store.syncFromWebView()[CF_CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }
        }
        val cm = CookieManager.getInstance()
        val header1 = cm.getCookie(Config.BASE_URL)
        val header2 = cm.getCookie("${Config.BASE_URL}/")
        return (SessionStore.parseCookieHeader(header1)[CF_CLEARANCE_COOKIE]
            ?: SessionStore.parseCookieHeader(header2)[CF_CLEARANCE_COOKIE])
            ?.takeIf { it.isNotBlank() && !store.isClearanceChallenged(it) }
    }

    private fun isFreshCfClearance(value: String?): Boolean =
        !value.isNullOrBlank() &&
            value != cfClearanceBeforeChallenge &&
            value !in cfStaleClearances &&
            !store.isClearanceChallenged(value)

    /**
     * 只认不带查询串的 /challenge。
     *
     * CF 在挑战过程中会带上 __cf_chl_* 查询串反复请求同一路径，那些响应不能当作
     * "已回源"的判据；对齐 Fluxdo 的 _isBareVerifyUrl。
     */
    private fun isBareChallengeUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return parsed.host == Config.HOST &&
            parsed.path == CF_CHALLENGE_PATH &&
            parsed.query.isNullOrEmpty()
    }

    private fun isChallengePathUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return parsed.host == Config.HOST && parsed.path == CF_CHALLENGE_PATH
    }

    /**
     * 对齐 Fluxdo：在 /challenge 页面注入防 404 闪现遮罩与卸载/DOM 404 监听器。
     * 一旦 CF 挑战完成触发页面卸载（beforeunload/pagehide），或 DOM 出现源站 404 特征，
     * 立即在当前 JS 帧塞入全屏纯色遮罩并通知原生层隐藏 WebView。
     */
    private fun installOriginFallbackMask(view: View) {
        val nightMode = (appContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val maskColor = if (isDarkCached || nightMode) "#1c1c1e" else "#f2f2f7"
        evaluateBrowserScript(view, buildOriginFallbackMaskScript(maskColor))
    }

    private fun hideOriginFallbackMask(view: View) {
        evaluateBrowserScript(view, "if (typeof window.__linuxdoHideCfMask === 'function') window.__linuxdoHideCfMask();")
    }

    /** 带 `cf-mitigated: challenge` 的响应是 CF 自己接管的挑战，不能算放行。 */
    private fun isCfMitigatedChallenge(response: WebResourceResponse?): Boolean =
        response?.responseHeaders
            ?.entries
            ?.any { it.key.equals("cf-mitigated", ignoreCase = true) && it.value.orEmpty().contains("challenge", true) }
            ?: false

    private fun cfMitigatedSuffix(response: WebResourceResponse?): String {
        val headers = response?.responseHeaders ?: return ""
        val interesting = headers.entries
            .filter { it.key.equals("cf-mitigated", true) || it.key.equals("cf-ray", true) }
            .joinToString(" ") { "${it.key.lowercase()}=${it.value}" }
        return if (interesting.isEmpty()) "" else " [$interesting]"
    }

    private fun resumePendingWebLogin(loginView: View) {
        when {
            awaitingSecondFactorLogin && !cachedSecondFactor.isNullOrBlank() -> {
                executeWebLogin(loginView, null, cachedSecondFactor, cachedSecondFactorMethod)
            }
            hcaptchaTempReady -> {
                retryingLoginWithHcaptchaToken = true
                executeWebLogin(loginView, null, cachedSecondFactor, cachedSecondFactorMethod)
            }
            !pendingHcaptchaToken.isNullOrBlank() -> {
                val hcaptchaToken = pendingHcaptchaToken
                retryingLoginWithHcaptchaToken = true
                executeWebLogin(loginView, hcaptchaToken, cachedSecondFactor, cachedSecondFactorMethod)
            }
            else -> {
                diagnostics.log("CF 通过后未保留 hCaptcha token，重新展示图形验证")
                loadCaptchaLogin(
                    login = cachedLogin,
                    password = cachedPassword,
                    secondFactorToken = cachedSecondFactor,
                    secondFactorMethod = cachedSecondFactorMethod,
                    isDark = isDarkCached,
                    resetCfRetry = false,
                    emailSendTo = cachedEmailSend,
                    emailLoginToken = cachedEmailLoginToken,
                    onResult = onAuthCallback ?: { _, _, _ -> },
                )
            }
        }
    }

    private fun executeWebLogin(
        view: View,
        hcaptchaToken: String?,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        val submissionCallback = onAuthCallback ?: return
        val submissionId = UUID.randomUUID().toString()
        val submissionGeneration = authSubmissionGeneration
        val hcaptchaJson = hcaptchaToken?.let(JSONObject::quote) ?: "null"
        val secondFactorJson = secondFactorToken?.let(JSONObject::quote) ?: "null"
        val secondFactorMethodJson = JSONObject.quote(secondFactorMethod.toString())
        val submissionIdJson = JSONObject.quote(submissionId)
        val previousJob = authSubmitJob
        authSubmitJob = scope.launch(Dispatchers.Main) {
            previousJob?.join()
            if (submissionGeneration != authSubmissionGeneration) return@launch
            TopicRepository.postGate.withLock {
                if (submissionGeneration != authSubmissionGeneration) return@withLock
                TopicRepository.throttlePost(burst = true)
                if (submissionGeneration != authSubmissionGeneration) return@withLock

                currentSubmissionId = submissionId
                currentSubmissionCallback = submissionCallback
                val completion = CompletableDeferred<AuthSubmitResult>()
                authSubmitCompletion = completion
                try {
                    evaluateBrowserScript(view,
                        """
                            (function () {
                              if (typeof window.__linuxdoRunLogin !== 'function') return false;
                              if (typeof window.__linuxdoShowSubmitting === 'function') {
                                window.__linuxdoShowSubmitting();
                              }
                              window.__linuxdoRunLogin($submissionIdJson, $hcaptchaJson, $secondFactorJson, $secondFactorMethodJson);
                              return true;
                            })();
                        """.trimIndent(),
                    ) { raw ->
                        if (decodeJsValue(raw) == "true") return@evaluateBrowserScript
                        retryingLoginWithHcaptchaToken = false
                        diagnostics.log("网页登录脚本已丢失，重新展示图形验证")
                        if (currentSubmissionId == submissionId) {
                            currentSubmissionId = null
                            currentSubmissionCallback = null
                            completion.complete(AuthSubmitResult.SCRIPT_MISSING)
                            loadCaptchaLogin(
                                login = cachedLogin,
                                password = cachedPassword,
                                secondFactorToken = cachedSecondFactor,
                                secondFactorMethod = cachedSecondFactorMethod,
                                isDark = isDarkCached,
                                resetCfRetry = false,
                                emailSendTo = cachedEmailSend,
                                emailLoginToken = cachedEmailLoginToken,
                                onResult = submissionCallback,
                            )
                        }
                    }
                    val result = withTimeoutOrNull(25_000L) {
                        completion.await()
                    }

                    if (result == null && currentSubmissionId == submissionId) {
                        diagnostics.log("WebView 登录等待超时(25s)，主动中断底层网络请求")
                        abortActiveAuthSubmission(submissionId, view, completion)
                        val abortResult = completion.await()
                        if (abortResult == AuthSubmitResult.ABORTED && cancelledSubmissionId != submissionId) {
                            submissionCallback(false, "登录超时，请重试", false)
                        }
                    }
                } finally {
                    if (currentSubmissionId == submissionId) {
                        currentSubmissionId = null
                        currentSubmissionCallback = null
                    }
                    if (authSubmitCompletion === completion) authSubmitCompletion = null
                    if (cancelledSubmissionId == submissionId) cancelledSubmissionId = null
                    TopicRepository.markPostCompleted()
                }
            }
        }
    }

    private fun executeNativeLogin(
        hcaptchaToken: String?,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        val client = httpClientProvider?.invoke()
        if (client == null) {
            diagnostics.log("Native 登录失败: HTTP 客户端未就绪")
            onAuthCallback?.invoke(false, "网络通道未就绪，请稍后重试", false)
            return
        }

        val submissionCallback = onAuthCallback ?: return
        val isEmailMode = !cachedEmailSend.isNullOrBlank()
        val isEmailTokenMode = !cachedEmailLoginToken.isNullOrBlank()

        authWebView?.post {
            authWebView?.evaluateJavascript(
                "(function(){ if (typeof window.__linuxdoShowSubmitting === 'function') window.__linuxdoShowSubmitting(); })()",
                null,
            )
        }

        val previousJob = authSubmitJob
        authSubmitJob = scope.launch(Dispatchers.IO) {
            previousJob?.join()
            TopicRepository.postGate.withLock {
                if (TopicRepository.isRateLimitCoolingDown()) {
                    val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                    withContext(Dispatchers.Main) {
                        submissionCallback(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
                    }
                    return@withLock
                }
                TopicRepository.throttlePost(burst = true)
                try {
                    // 1. 获取 CSRF Token
                    diagnostics.log("Native 登录第 1 步: 请求 /session/csrf...")
                    val csrfReq = Request.Builder()
                        .url("${Config.BASE_URL}/session/csrf")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Accept", "application/json")
                        .build()

                    var csrfToken: String? = null
                    client.newCall(csrfReq).execute().use { resp ->
                        val cookies = Cookie.parseAll(resp.request.url, resp.headers)
                        if (cookies.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                store.applyResponseCookies(cookies)
                                CookieManager.getInstance().flush()
                            }
                        }
                        if (resp.code == 429) {
                            val retryAfter = HttpHeaderUtils.parseRetryAfter(resp.header("Retry-After"))
                            TopicRepository.enterRateLimitCooldown("/session/csrf", retryAfter, sessionStore = store)
                        }
                        if (!resp.isSuccessful) {
                            diagnostics.log("CSRF 请求失败: HTTP ${resp.code}")
                            withContext(Dispatchers.Main) {
                                if (resp.code == 403) {
                                    val isCf = resp.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true
                                    if (isCf && !cfRetryUsed) {
                                        cfRetryUsed = true
                                        diagnostics.log("CSRF 遇到 Cloudflare 盾 (403)，正在安全弹窗内启动 /challenge 验证...")
                                        launchCfChallenge()
                                    } else {
                                        submissionCallback(false, "安全防护拦截 (HTTP 403)，请重试或开启网络加速", false)
                                    }
                                } else if (resp.code == 429) {
                                    val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                                    submissionCallback(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
                                } else {
                                    submissionCallback(false, "CSRF 获取失败: HTTP ${resp.code}", false)
                                }
                            }
                            return@launch
                        }
                        val body = resp.body?.string().orEmpty()
                        csrfToken = try {
                            JSONObject(body).optString("csrf")
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (csrfToken.isNullOrBlank()) {
                        diagnostics.log("CSRF Token 解析为空")
                        withContext(Dispatchers.Main) {
                            submissionCallback(false, "CSRF Token 解析失败", false)
                        }
                        return@launch
                    }
                    diagnostics.log("Native 登录: 获取 CSRF 成功")

                    // 2. 兑换 hCaptcha token (若有)
                    if (!hcaptchaToken.isNullOrBlank()) {
                        diagnostics.log("Native 登录第 2 步: 兑换 hCaptcha token...")
                        val endpoints = listOf("/captcha/hcaptcha/create.json", "/hcaptcha/create.json")
                        var hOk = false
                        var lastCode = 0
                        var isCfEncountered = false

                        for (ep in endpoints) {
                            val hBody = FormBody.Builder()
                                .add("token", hcaptchaToken)
                                .build()
                            val hReq = Request.Builder()
                                .url("${Config.BASE_URL}$ep")
                                .post(hBody)
                                .header("X-CSRF-Token", csrfToken)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json")
                                .build()

                            client.newCall(hReq).execute().use { resp ->
                                lastCode = resp.code
                                val cookies = Cookie.parseAll(resp.request.url, resp.headers)
                                if (cookies.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        store.applyResponseCookies(cookies)
                                        CookieManager.getInstance().flush()
                                    }
                                }
                                if (resp.code == 429) {
                                    val retryAfter = HttpHeaderUtils.parseRetryAfter(resp.header("Retry-After"))
                                    TopicRepository.enterRateLimitCooldown(ep, retryAfter, sessionStore = store)
                                }
                                if (resp.isSuccessful) {
                                    hOk = true
                                } else if (resp.code == 403) {
                                    val isCf = resp.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true
                                    if (isCf) isCfEncountered = true
                                }
                            }
                            if (hOk || lastCode != 404) break
                        }

                        if (!hOk) {
                            diagnostics.log("hCaptcha Token 兑换失败: HTTP $lastCode")
                            withContext(Dispatchers.Main) {
                                if (isCfEncountered && !cfRetryUsed) {
                                    cfRetryUsed = true
                                    diagnostics.log("hCaptcha 兑换遇到 Cloudflare 盾 (403)，正在安全弹窗内启动 /challenge 验证...")
                                    launchCfChallenge()
                                } else if (lastCode == 429) {
                                    val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                                    submissionCallback(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
                                } else {
                                    submissionCallback(false, "人机验证令牌兑换失败 (HTTP $lastCode)，请重试", false)
                                }
                            }
                            return@launch
                        }
                        diagnostics.log("Native 登录: hCaptcha Token 兑换成功")
                    }

                    // 3. 提交登录 /session.json 或 发送邮件 /u/email-login.json
                    if (isEmailMode) {
                        val email = cachedEmailSend.orEmpty()
                        diagnostics.log("Native 登录第 3 步: 提交发送登录邮件 $email (/u/email-login.json)...")
                        val jsonPayload = JSONObject().put("login", email).toString()
                        val emailReq = Request.Builder()
                            .url("${Config.BASE_URL}/u/email-login.json")
                            .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .header("X-CSRF-Token", csrfToken)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Referer", "${Config.BASE_URL}/login")
                            .header("Accept", "application/json")
                            .build()

                        client.newCall(emailReq).execute().use { resp ->
                            val cookies = Cookie.parseAll(resp.request.url, resp.headers)
                            if (cookies.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    store.applyResponseCookies(cookies)
                                    CookieManager.getInstance().flush()
                                }
                            }
                            if (resp.code == 429) {
                                val retryAfter = HttpHeaderUtils.parseRetryAfter(resp.header("Retry-After"))
                                TopicRepository.enterRateLimitCooldown("/u/email-login.json", retryAfter, sessionStore = store)
                            }
                            val bodyStr = resp.body?.string().orEmpty()
                            diagnostics.log("邮箱登录响应 HTTP ${resp.code}: ${bodyStr.take(200)}")

                            val resObj = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                            val success = resp.isSuccessful && (resObj?.optString("success") == "OK")
                            val msg = if (success) {
                                "登录邮件已发送至 $email，请查收邮件并点击其中的链接登录"
                            } else {
                                val err = resObj?.optString("errors") ?: resObj?.optString("error")
                                err?.takeIf { it.isNotBlank() } ?: "发送失败: HTTP ${resp.code}"
                            }
                            withContext(Dispatchers.Main) {
                                if (!success && resp.code == 403) {
                                    val isCf = resp.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true ||
                                        bodyStr.contains("challenge-platform", ignoreCase = true) ||
                                        bodyStr.contains("just a moment", ignoreCase = true)
                                    if (isCf && !cfRetryUsed) {
                                        cfRetryUsed = true
                                        diagnostics.log("邮箱登录遇到 Cloudflare 盾 (403)，正在安全弹窗内启动 /challenge 验证...")
                                        launchCfChallenge()
                                    } else {
                                        submissionCallback(false, "安全防护拦截 (HTTP 403)，请重试或开启网络加速", false)
                                    }
                                } else if (!success && resp.code == 429) {
                                    val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                                    submissionCallback(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
                                } else {
                                    submissionCallback(success, msg, false)
                                }
                            }
                        }
                    } else if (isEmailTokenMode) {
                        val token = cachedEmailLoginToken.orEmpty().trim()
                        val path = "/session/email-login/${Uri.encode(token)}"
                        diagnostics.log("Native 登录第 3 步: 提交邮件登录令牌兑换 $path...")
                        val payloadObj = JSONObject()
                        if (!secondFactorToken.isNullOrBlank()) {
                            payloadObj.put("second_factor_token", secondFactorToken.trim())
                            payloadObj.put("second_factor_method", secondFactorMethod)
                        }
                        val tokenReq = Request.Builder()
                            .url("${Config.BASE_URL}$path")
                            .post(payloadObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .header("X-CSRF-Token", csrfToken)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Referer", "${Config.BASE_URL}$path")
                            .header("Accept", "application/json")
                            .build()

                        client.newCall(tokenReq).execute().use { resp ->
                            val cookies = Cookie.parseAll(resp.request.url, resp.headers)
                            if (cookies.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    store.applyResponseCookies(cookies)
                                    CookieManager.getInstance().flush()
                                }
                            }
                            if (resp.code == 429) {
                                val retryAfter = HttpHeaderUtils.parseRetryAfter(resp.header("Retry-After"))
                                TopicRepository.enterRateLimitCooldown(path, retryAfter, sessionStore = store)
                            }
                            val bodyStr = resp.body?.string().orEmpty()
                            diagnostics.log("邮件令牌兑换响应 HTTP ${resp.code}: ${bodyStr.take(200)}")

                            val resObj = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                            val error = resObj?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: resObj?.optString("errors")?.takeIf { it.isNotBlank() }
                            val has2faToken = !secondFactorToken.isNullOrBlank()
                            val secondFactorRequired = isSecondFactorResponse(resObj, has2faToken)

                            withContext(Dispatchers.Main) {
                                if (secondFactorRequired) {
                                    awaitingSecondFactorLogin = true
                                    val msg = extractSecondFactorMessage(resObj, has2faToken)
                                    submissionCallback(false, msg, true)
                                } else if (!error.isNullOrBlank()) {
                                    awaitingSecondFactorLogin = false
                                    submissionCallback(false, error, false)
                                } else if (resp.isSuccessful) {
                                    awaitingSecondFactorLogin = false
                                    CookieManager.getInstance().flush()
                                    diagnostics.log("Native 邮件令牌兑换登录成功！")
                                    submissionCallback(true, null, false)
                                } else {
                                    awaitingSecondFactorLogin = false
                                    if (resp.code == 403) {
                                        val isCf = resp.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true ||
                                            bodyStr.contains("challenge-platform", ignoreCase = true) ||
                                            bodyStr.contains("just a moment", ignoreCase = true)
                                        if (isCf && !cfRetryUsed) {
                                            cfRetryUsed = true
                                            diagnostics.log("邮件令牌兑换遇到 Cloudflare 盾 (403)，正在安全弹窗内启动 /challenge 验证...")
                                            launchCfChallenge()
                                        } else {
                                            submissionCallback(false, "安全防护拦截 (HTTP 403)，请重试或开启网络加速", false)
                                        }
                                    } else if (resp.code == 429) {
                                        val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                                        submissionCallback(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
                                    } else {
                                        submissionCallback(false, "登录链接无效或已过期 (HTTP ${resp.code})", false)
                                    }
                                }
                            }
                        }
                    } else {
                        diagnostics.log("Native 登录第 3 步: 提交登录凭据 session.json...")
                        val sessionBodyBuilder = FormBody.Builder()
                            .add("login", cachedLogin)
                            .add("password", cachedPassword)

                        if (!secondFactorToken.isNullOrBlank()) {
                            sessionBodyBuilder
                                .add("second_factor_token", secondFactorToken)
                                .add("second_factor_method", secondFactorMethod.toString())
                        }

                        val sessionReq = Request.Builder()
                            .url("${Config.BASE_URL}/session.json")
                            .post(sessionBodyBuilder.build())
                            .header("X-CSRF-Token", csrfToken)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Referer", "${Config.BASE_URL}/login")
                            .header("Accept", "application/json")
                            .build()

                        client.newCall(sessionReq).execute().use { resp ->
                            val cookies = Cookie.parseAll(resp.request.url, resp.headers)
                            if (cookies.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    store.applyResponseCookies(cookies)
                                    CookieManager.getInstance().flush()
                                }
                            }
                            if (resp.code == 429) {
                                val retryAfter = HttpHeaderUtils.parseRetryAfter(resp.header("Retry-After"))
                                TopicRepository.enterRateLimitCooldown("/session.json", retryAfter, sessionStore = store)
                            }

                            val bodyStr = resp.body?.string().orEmpty()
                            diagnostics.log("登录响应 HTTP ${resp.code}: ${bodyStr.take(200)}")

                            val resObj = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                            val error = resObj?.optString("error")
                            val user = resObj?.optJSONObject("user")?.optString("username")

                            val has2faToken = !secondFactorToken.isNullOrBlank()
                            val is2FA = isSecondFactorResponse(resObj, has2faToken)

                            withContext(Dispatchers.Main) {
                                if (is2FA) {
                                    awaitingSecondFactorLogin = true
                                    val msg = extractSecondFactorMessage(resObj, has2faToken)
                                    submissionCallback(false, msg, true)
                                } else if (!error.isNullOrBlank() && user.isNullOrBlank()) {
                                    awaitingSecondFactorLogin = false
                                    submissionCallback(false, error, false)
                                } else if (resp.isSuccessful || !user.isNullOrBlank()) {
                                    awaitingSecondFactorLogin = false
                                    CookieManager.getInstance().flush()
                                    diagnostics.log("Native 登录成功！用户: ${user ?: cachedLogin}")
                                    submissionCallback(true, null, false)
                                } else {
                                    awaitingSecondFactorLogin = false
                                    if (resp.code == 403) {
                                        val isCf = resp.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true ||
                                            bodyStr.contains("challenge-platform", ignoreCase = true) ||
                                            bodyStr.contains("just a moment", ignoreCase = true)
                                        if (isCf && !cfRetryUsed) {
                                            cfRetryUsed = true
                                            diagnostics.log("Native 登录遇到 Cloudflare 盾 (403)，正在安全弹窗内启动 /challenge 验证...")
                                            launchCfChallenge()
                                        } else {
                                            submissionCallback(false, "登录安全防护校验拦截 (HTTP 403)，可开启网络加速完成登录", false)
                                        }
                                    } else if (resp.code == 429) {
                                        val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                                        submissionCallback(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
                                    } else {
                                        submissionCallback(false, "登录失败: HTTP ${resp.code}", false)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    diagnostics.log("Native 登录异常: ${e.message}")
                    withContext(Dispatchers.Main) {
                        submissionCallback(false, "登录网络异常: ${e.message ?: "连接失败"}", false)
                    }
                } finally {
                    TopicRepository.markPostCompleted()
                }
            }
        }
    }

    private fun abortActiveAuthSubmission(
        submissionId: String,
        view: View,
        completion: CompletableDeferred<AuthSubmitResult>,
    ) {
        if (authAbortSubmissionId == submissionId && authAbortJob?.isActive == true) return

        val acknowledgement = CompletableDeferred<Unit>()
        authAbortSubmissionId = submissionId
        authAbortAcknowledgement = acknowledgement
        if (authWebView === view) authWebView = null
        if (authGeckoView === view) {
            authGeckoView = null
            authGeckoSession = null
        }
        pendingAuthHtml = null
        authHtmlInjected = false
        val submissionIdJson = JSONObject.quote(submissionId)
        runCatching {
            evaluateBrowserScript(view,
                """
                    (function () {
                      if (typeof window.__linuxdoAbortLogin !== 'function') return false;
                      return window.__linuxdoAbortLogin($submissionIdJson) !== false;
                    })();
                """.trimIndent(),
            ) { raw ->
                if (decodeJsValue(raw) != "true") acknowledgement.complete(Unit)
                runCatching { stopBrowserLoading(view) }
            }
        }.onFailure { acknowledgement.complete(Unit) }

        authAbortJob = CoroutineScope(Dispatchers.Main).launch {
            withTimeoutOrNull(AUTH_ABORT_ACK_TIMEOUT_MS) { acknowledgement.await() }
            destroyAuthBrowser(view)
            if (currentSubmissionId == submissionId) {
                currentSubmissionId = null
                currentSubmissionCallback = null
            }
            if (authSubmitCompletion === completion) authSubmitCompletion = null
            if (cancelledSubmissionId == submissionId) cancelledSubmissionId = null
            if (authAbortSubmissionId == submissionId) {
                authAbortSubmissionId = null
                authAbortAcknowledgement = null
                authAbortJob = null
            }
            completion.complete(AuthSubmitResult.ABORTED)
        }
    }

    private fun destroyAuthBrowser(view: View) {
        if (authWebView === view) authWebView = null
        if (authGeckoView === view) {
            authGeckoView = null
            authGeckoSession = null
        }
        geckoBridges.remove(view)?.close()
        runCatching { (view.context as? MutableContextWrapper)?.baseContext = appContext }
        runCatching { stopBrowserLoading(view) }
        if (view is WebView) {
            runCatching { view.removeJavascriptInterface(BRIDGE_NAME) }
            runCatching { view.destroy() }
        } else if (view is GeckoView) {
            val session = view.session
            view.releaseSession()
            session?.close()
        }
    }

    private fun endLoginClearanceRecovery(recoveryId: Long? = loginCfRecoveryId) {
        if (recoveryId == null) return
        if (loginCfRecoveryId == recoveryId) loginCfRecoveryId = null
        store.endClearanceRecovery(recoveryId)
    }

    private fun dismissCfDialog() {
        endLoginClearanceRecovery()
        val dialog = cfDialog ?: return
        val view = cfWebView
        val gView = cfGeckoView
        val gSession = cfGeckoSession
        cfDialog = null
        cfWebView = null
        cfGeckoView = null
        cfGeckoSession = null
        cfProbeScheduled = false
        cfCompletionProbing = false
        cfQuietRounds = 0
        cfGeckoInitJob?.cancel()
        gView?.let { geckoBridges.remove(it)?.close() }
        dialog.setOnDismissListener(null)
        dialog.dismiss()
        view?.destroy()
        gSession?.close()
        gView?.releaseSession()
    }

    /**
     * 借鉴 Fluxdo 的安全认证机制：
     * 不加载臃肿的 Discourse Ember 单页应用，而是用 BaseURL=https://linux.do 加载极简的
     * 内联 HTML。在没有任何 CSS 污染的标准容器里渲染 hCaptcha，并在验证通过后直接在
     * WebView 内核中同源 fetch 完成 CSRF -> hCaptcha Token 兑换 -> session.json 登录。
     * 若 CSRF 被 CF 拦截，保留本页 hCaptcha/2FA 会话，交由独立 CF 窗口完成后自动重放。
     */
    private var cachedSecondFactorMethod: Int = 1

    fun loadCaptchaLogin(
        login: String,
        password: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
        isDark: Boolean = false,
        resetCfRetry: Boolean = true,
        /** 非空时切换为"发送登录邮件"模式：同一套 hCaptcha/CF 机制，提交的是 /u/email-login.json。 */
        emailSendTo: String? = null,
        /** 非空时切换为"邮件链接/验证码兑换"模式：提交 POST /session/email-login/:token。 */
        emailLoginToken: String? = null,
        onResult: (success: Boolean, errorMsg: String?, need2FA: Boolean) -> Unit,
    ) {
        if (TopicRepository.isRateLimitCoolingDown()) {
            val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
            onResult(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
            return
        }
        onAuthCallback = onResult
        cachedLogin = login
        cachedPassword = password
        cachedSecondFactor = secondFactorToken
        cachedSecondFactorMethod = secondFactorMethod
        cachedEmailSend = emailSendTo
        cachedEmailLoginToken = emailLoginToken
        isDarkCached = isDark
        isInCfMode = false
        hcaptchaTempReady = false
        if (resetCfRetry) {
            cfRetryUsed = false
            pendingHcaptchaToken = null
            retryingLoginWithHcaptchaToken = false
            awaitingSecondFactorLogin = false
        }

        val uJson = JSONObject.quote(login)
        val pJson = JSONObject.quote(password)
        val emailJson = JSONObject.quote(emailSendTo.orEmpty())
        val emailLoginTokenJson = JSONObject.quote(emailLoginToken.orEmpty())
        val emailMode = !emailSendTo.isNullOrBlank()
        val sfJson = if (!secondFactorToken.isNullOrBlank()) JSONObject.quote(secondFactorToken) else "null"
        val sfMethodJson = JSONObject.quote(secondFactorMethod.toString())
        val bgBadge = if (isDark) "rgba(10, 132, 255, 0.22)" else "rgba(0, 122, 255, 0.12)"
        val tipColor = if (isDark) "#f5f5f7" else "#1c1c1e"
        val statusColor = if (isDark) "#0a84ff" else "#007aff"
        val hTheme = if (isDark) "dark" else "light"

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
              <style>
                * { box-sizing: border-box; }
                html, body {
                  margin: 0; padding: 0; width: 100%; height: 100%;
                  background: ${if (isDark) "#1c1c1e" else "#ffffff"};
                  color-scheme: $hTheme;
                  overflow-x: hidden;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  -webkit-text-size-adjust: 100%;
                }
                .wrap {
                  width: 100%; min-height: 100%;
                  display: flex; flex-direction: column; align-items: center; justify-content: flex-start;
                  gap: 16px; padding: 24px 16px 20px 16px; text-align: center;
                }
                .badge {
                  width: 48px; height: 48px; border-radius: 50%; display: flex;
                  align-items: center; justify-content: center;
                  background: $bgBadge; font-size: 24px;
                }
                .tip {
                  font-size: 14px; line-height: 1.5; color: $tipColor; margin: 0; max-width: 300px;
                  text-align: center; font-weight: 500;
                }
                .h-captcha { min-height: 78px; margin-top: 4px; pointer-events: auto; touch-action: manipulation; }
                .spinner {
                  width: 30px; height: 30px; border: 3px solid rgba(0, 122, 255, 0.25);
                  border-radius: 50%; border-top-color: $statusColor;
                  animation: spin 0.8s linear infinite; display: inline-block;
                }
                .submitting {
                  position: fixed; inset: 0; z-index: 10; display: none;
                  flex-direction: column; align-items: center; justify-content: center; gap: 16px;
                  padding: 32px 16px; background: ${if (isDark) "rgba(28, 28, 30, 0.94)" else "rgba(255, 255, 255, 0.94)"};
                  color: $tipColor; font-size: 15px; font-weight: 500;
                }
                @keyframes spin { to { transform: rotate(360deg); } }
              </style>
            </head>
            <body>
              <div id="main-content" class="wrap">
                <div class="badge">🛡️</div>
                <p class="tip">${if (emailMode) "勾选下方方框，确认你不是机器人即可发送登录邮件" else "勾选下方方框，确认你不是机器人即可继续登录"}</p>
                <div id="cap" class="h-captcha"
                  data-sitekey="a776b4ac-8c4c-441e-986a-c6ee9ed8cf08"
                  data-callback="onPass"
                  data-theme="$hTheme"
                  data-size="normal"></div>
              </div>
              <div id="submitting" class="submitting">
                <div class="spinner"></div>
                <div>正在验证登录…</div>
              </div>
              <script>
                function callBridge(name, payload) {
                  try {
                    if (window.__ldb && window.__ldb[name]) {
                      window.__ldb[name](payload);
                    }
                  } catch (e) {}
                }

                function onPass(token) {
                  callBridge('onHcaptchaPass', token);
                }

                function showSubmitting() {
                  var overlay = document.getElementById('submitting');
                  if (overlay) overlay.style.display = 'flex';
                }

                var currentAbortController = null;
                var currentLoginSubmissionId = null;
                function abortCurrentLogin(subId) {
                  if (!subId || currentLoginSubmissionId !== subId || !currentAbortController) return false;
                  try {
                    currentAbortController.abort();
                    return true;
                  } catch (e) {
                    return false;
                  }
                }
                window.__linuxdoAbortLogin = abortCurrentLogin;
                function reportLoginAbort(subId) {
                  callBridge('onAuthAborted', subId);
                }

                async function runLogin(subId, hcaptchaToken, secondFactorOverride, secondFactorMethodOverride) {
                  if (typeof AbortController === 'undefined') {
                    callBridge('onAuthResult', JSON.stringify({
                      submissionId: subId,
                      phase: 'exception',
                      status: 0,
                      retryAfter: '',
                      body: 'AbortController is unavailable'
                    }));
                    return;
                  }
                  currentLoginSubmissionId = subId;
                  currentAbortController = new AbortController();
                  var signal = currentAbortController.signal;
                  var identifier = $uJson;
                  var password = $pJson;
                  var emailLoginToken = $emailLoginTokenJson;
                  var secondFactor = arguments.length > 2 ? secondFactorOverride : $sfJson;
                  var secondFactorMethod = arguments.length > 3 ? secondFactorMethodOverride : $sfMethodJson;

                  function isCloudflareChallenge(response, text) {
                    var mitigated = (response.headers.get('cf-mitigated') || '').toLowerCase();
                    var body = (text || '').toLowerCase();
                    return (response.status === 403 || response.status === 429) && (
                      mitigated === 'challenge' ||
                      body.indexOf('just a moment') !== -1 ||
                      body.indexOf('challenge-platform') !== -1 ||
                      body.indexOf('cf-chl-') !== -1 ||
                      body.indexOf('cf-mitigated') !== -1
                    );
                  }

                  // 把 CF 的权威判定头原样带回宿主。403 到底是 CF 拦的还是 Discourse 自己
                  // 返的，只看状态码分不出来，cf-ray 还能让站方按请求定位。
                  function reportResponse(label, response) {
                    try {
                      var names = [];
                      var raw = document.cookie || '';
                      raw.split(';').forEach(function (part) {
                        var i = part.indexOf('=');
                        if (i > 0) names.push(part.substring(0, i).trim());
                      });
                      callBridge('onDiagnostic',
                        label + ' status=' + response.status +
                        ' cf-mitigated=' + (response.headers.get('cf-mitigated') || '-') +
                        ' cf-ray=' + (response.headers.get('cf-ray') || '-') +
                        ' 页面可见Cookie=[' + names.join(',') + ']');
                    } catch (e) {}
                  }

                  try {
                    callBridge('onDiagnostic',
                      'fetch 前上下文 href=' + window.location.href +
                      ' origin=' + window.location.origin +
                      ' hasToken=' + (hcaptchaToken ? 'yes' : 'no'));
                  } catch (e) {}

                  try {
                    // 1. 获取 CSRF Token (与内核会话一致)。对齐 Fluxdo：单次获取，避免在遇到挑战或限流时高频轮询引发 429
                    var c = await fetch('/session/csrf', {
                      method: 'GET',
                      headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' },
                      credentials: 'include',
                      cache: 'no-store',
                      signal: signal
                    });
                    if (c.status !== 200) {
                      var cBody = await c.text();
                      reportResponse('csrf 失败', c);
                      var isCf = isCloudflareChallenge(c, cBody);
                      callBridge('onAuthResult', JSON.stringify({
                        submissionId: subId,
                        phase: isCf ? 'cf_needed' : 'csrf',
                        status: c.status,
                        retryAfter: c.headers.get('retry-after') || '',
                        body: cBody,
                        hcaptchaToken: isCf ? hcaptchaToken : null
                      }));
                      return;
                    }
                    var csrf = (await c.json()).csrf;

                    // 2. 首次登录兑换 hCaptcha token；2FA 重试沿用 h_captcha_temp_id。
                    if (hcaptchaToken) {
                      var endpoints = ['/captcha/hcaptcha/create.json', '/hcaptcha/create.json'];
                      var hOk = false;
                      var hLast = null;
                      for (var i = 0; i < endpoints.length; i++) {
                        var ep = endpoints[i];
                        try {
                          var h = await fetch(ep, {
                            method: 'POST',
                            credentials: 'include',
                            headers: {
                              'Content-Type': 'application/x-www-form-urlencoded',
                              'X-CSRF-Token': csrf,
                              'X-Requested-With': 'XMLHttpRequest'
                            },
                            body: 'token=' + encodeURIComponent(hcaptchaToken),
                            signal: signal
                          });
                          var hText = await h.text();
                          if (isCloudflareChallenge(h, hText)) {
                            callBridge('onAuthResult', JSON.stringify({
                              submissionId: subId,
                              phase: 'cf_needed',
                              status: h.status,
                              retryAfter: h.headers.get('retry-after') || '',
                              body: hText,
                              hcaptchaToken: hcaptchaToken,
                              captchaAccepted: false
                            }));
                            return;
                          }
                          hLast = { endpoint: ep, status: h.status, retryAfter: h.headers.get('retry-after') || '', body: hText };
                          if (h.status === 200) { hOk = true; break; }
                          if (h.status !== 404) break;
                        } catch (e) {
                          if (e && e.name === 'AbortError') {
                            reportLoginAbort(subId);
                            return;
                          }
                          hLast = { endpoint: ep, status: 0, retryAfter: '', body: String(e) };
                        }
                      }
                      if (!hOk) {
                        callBridge('onAuthResult', JSON.stringify({
                          submissionId: subId,
                          phase: 'hcaptcha',
                          status: hLast ? hLast.status : 0,
                          retryAfter: hLast ? hLast.retryAfter : '',
                          body: JSON.stringify(hLast)
                        }));
                        return;
                      }
                    }

                    // 3. 在同一浏览器会话中提交账号密码或邮件令牌。
                    var form = 'login=' + encodeURIComponent(identifier) + '&password=' + encodeURIComponent(password);
                    if (secondFactor) {
                      form += '&second_factor_token=' + encodeURIComponent(secondFactor) + '&second_factor_method=' + encodeURIComponent(secondFactorMethod);
                    }
                    var loginPath = '/session.json';
                    var loginContentType = 'application/x-www-form-urlencoded';
                    if (emailLoginToken) {
                      loginPath = '/session/email-login/' + encodeURIComponent(emailLoginToken);
                      loginContentType = 'application/json; charset=utf-8';
                      var tokenPayload = {};
                      if (secondFactor) {
                        tokenPayload.second_factor_token = secondFactor;
                        tokenPayload.second_factor_method = Number(secondFactorMethod);
                      }
                      form = JSON.stringify(tokenPayload);
                    }
                    var s = await fetch(loginPath, {
                      method: 'POST',
                      credentials: 'include',
                      headers: {
                        'Content-Type': loginContentType,
                        'X-CSRF-Token': csrf,
                        'X-Requested-With': 'XMLHttpRequest',
                        'Accept': 'application/json'
                      },
                      body: form,
                      signal: signal
                    });
                    var sText = await s.text();
                    if (s.status !== 200) reportResponse('session 非 200', s);
                    if (isCloudflareChallenge(s, sText)) {
                      callBridge('onAuthResult', JSON.stringify({
                        submissionId: subId,
                        phase: 'cf_needed',
                        status: s.status,
                        retryAfter: s.headers.get('retry-after') || '',
                        body: sText,
                        captchaAccepted: !!hcaptchaToken
                      }));
                      return;
                    }
                    callBridge('onAuthResult', JSON.stringify({
                      submissionId: subId,
                      phase: 'session',
                      status: s.status,
                      retryAfter: s.headers.get('retry-after') || '',
                      body: sText
                    }));
                  } catch (e) {
                    if (e && e.name === 'AbortError') {
                      reportLoginAbort(subId);
                      return;
                    }
                    callBridge('onAuthResult', JSON.stringify({
                      submissionId: subId,
                      phase: 'exception',
                      status: 0,
                      retryAfter: '',
                      body: String(e)
                    }));
                  } finally {
                    if (currentLoginSubmissionId === subId) {
                      currentLoginSubmissionId = null;
                      currentAbortController = null;
                    }
                  }
                }
                /**
                 * 发信模式：与 runLogin 共用同一套 CSRF / hCaptcha 兑换 / CF 挑战时序，
                 * 只把最后一步换成 POST /u/email-login.json。
                 * 站点的邮件接口在触发人机验证时返回 403 invalid_access ——
                 * 换到 h_captcha_temp_id 后再提交才会被放行。
                 */
                async function runEmailSend(subId, hcaptchaToken) {
                  if (typeof AbortController === 'undefined') {
                    callBridge('onAuthResult', JSON.stringify({
                      submissionId: subId,
                      phase: 'exception',
                      status: 0,
                      retryAfter: '',
                      body: 'AbortController is unavailable'
                    }));
                    return;
                  }
                  currentLoginSubmissionId = subId;
                  currentAbortController = new AbortController();
                  var signal = currentAbortController.signal;
                  var email = $emailJson;

                  function isCloudflareChallenge(response, text) {
                    var mitigated = (response.headers.get('cf-mitigated') || '').toLowerCase();
                    var body = (text || '').toLowerCase();
                    return (response.status === 403 || response.status === 429) && (
                      mitigated === 'challenge' ||
                      body.indexOf('just a moment') !== -1 ||
                      body.indexOf('challenge-platform') !== -1 ||
                      body.indexOf('cf-chl-') !== -1 ||
                      body.indexOf('cf-mitigated') !== -1
                    );
                  }

                  try {
                    var c = await fetch('/session/csrf', {
                      method: 'GET',
                      headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' },
                      credentials: 'include',
                      cache: 'no-store',
                      signal: signal
                    });
                    if (c.status !== 200) {
                      var cBody = await c.text();
                      var isCf = isCloudflareChallenge(c, cBody);
                      callBridge('onAuthResult', JSON.stringify({
                        submissionId: subId,
                        phase: isCf ? 'cf_needed' : 'csrf',
                        status: c.status,
                        retryAfter: c.headers.get('retry-after') || '',
                        body: cBody,
                        hcaptchaToken: isCf ? hcaptchaToken : null
                      }));
                      return;
                    }
                    var csrf = (await c.json()).csrf;

                    // 勾选后先换 h_captcha_temp_id（cookie 由内核自动带上）。
                    if (hcaptchaToken) {
                      var endpoints = ['/captcha/hcaptcha/create.json', '/hcaptcha/create.json'];
                      var hOk = false;
                      var hLast = null;
                      for (var i = 0; i < endpoints.length; i++) {
                        var ep = endpoints[i];
                        try {
                          var h = await fetch(ep, {
                            method: 'POST',
                            credentials: 'include',
                            headers: {
                              'Content-Type': 'application/x-www-form-urlencoded',
                              'X-CSRF-Token': csrf,
                              'X-Requested-With': 'XMLHttpRequest'
                            },
                            body: 'token=' + encodeURIComponent(hcaptchaToken),
                            signal: signal
                          });
                          var hText = await h.text();
                          if (isCloudflareChallenge(h, hText)) {
                            callBridge('onAuthResult', JSON.stringify({
                              submissionId: subId,
                              phase: 'cf_needed',
                              status: h.status,
                              retryAfter: h.headers.get('retry-after') || '',
                              body: hText,
                              hcaptchaToken: hcaptchaToken,
                              captchaAccepted: false
                            }));
                            return;
                          }
                          hLast = { endpoint: ep, status: h.status, retryAfter: h.headers.get('retry-after') || '', body: hText };
                          if (h.status === 200) { hOk = true; break; }
                          if (h.status !== 404) break;
                        } catch (e) {
                          if (e && e.name === 'AbortError') {
                            reportLoginAbort(subId);
                            return;
                          }
                          hLast = { endpoint: ep, status: 0, retryAfter: '', body: String(e) };
                        }
                      }
                      if (!hOk) {
                        callBridge('onAuthResult', JSON.stringify({
                          submissionId: subId,
                          phase: 'hcaptcha',
                          status: hLast ? hLast.status : 0,
                          retryAfter: hLast ? hLast.retryAfter : '',
                          body: JSON.stringify(hLast)
                        }));
                        return;
                      }
                    }

                    // 最后一个参数不传时站点默认发"登录链接"邮件。
                    var r = await fetch('/u/email-login.json', {
                      method: 'POST',
                      credentials: 'include',
                      headers: {
                        'Content-Type': 'application/json',
                        'X-CSRF-Token': csrf,
                        'X-Requested-With': 'XMLHttpRequest',
                        'Accept': 'application/json'
                      },
                      body: JSON.stringify({ login: email }),
                      signal: signal
                    });
                    var rText = await r.text();
                    if (isCloudflareChallenge(r, rText)) {
                      callBridge('onAuthResult', JSON.stringify({
                        submissionId: subId,
                        phase: 'cf_needed',
                        status: r.status,
                        retryAfter: r.headers.get('retry-after') || '',
                        body: rText,
                        hcaptchaToken: hcaptchaToken,
                        captchaAccepted: false
                      }));
                      return;
                    }
                    if (r.status === 403) {
                      callBridge('onAuthResult', JSON.stringify({
                        submissionId: subId,
                        phase: 'hcaptcha',
                        status: r.status,
                        retryAfter: r.headers.get('retry-after') || '',
                        body: rText
                      }));
                      return;
                    }
                    if (r.status !== 200) {
                      callBridge('onAuthResult', JSON.stringify({
                        submissionId: subId,
                        phase: 'exception',
                        status: r.status,
                        retryAfter: r.headers.get('retry-after') || '',
                        body: rText
                      }));
                      return;
                    }
                    callBridge('onAuthResult', JSON.stringify({
                      submissionId: subId,
                      phase: 'email_sent',
                      status: r.status,
                      retryAfter: r.headers.get('retry-after') || '',
                      body: rText
                    }));
                  } catch (e) {
                    if (e && e.name === 'AbortError') {
                      reportLoginAbort(subId);
                      return;
                    }
                    callBridge('onAuthResult', JSON.stringify({
                      submissionId: subId,
                      phase: 'exception',
                      status: 0,
                      retryAfter: '',
                      body: String(e)
                    }));
                  } finally {
                    if (currentLoginSubmissionId === subId) {
                      currentLoginSubmissionId = null;
                      currentAbortController = null;
                    }
                  }
                }
                window.__linuxdoRunLogin = ${if (emailMode) "runEmailSend" else "runLogin"};
                window.__linuxdoShowSubmitting = showSubmitting;
              </script>
              <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
            </body>
            </html>
        """.trimIndent()

        // 调用时 WebView 已长期挂在 AppShell 中且处于主线程。直接导航能避开弹窗
        // 切换期间 View.post 回调被旧父容器丢弃的问题。
        pendingAuthHtml = html
        pendingEmailLink = null
        currentAuthView()?.let { loadAuthHtml(it, html) }
    }

    /**
     * 邮件链接兑换：对齐 Fluxdo 的 WebViewLoginPage —— 不在原生栈里模拟请求，
     * 而是让认证 WebView 整页打开完整链接，由站点自己完成 CF/人机验证并种下 _t。
     *
     * 这是兑换链路的兜底：原生 POST /session/email-login/:token 被站点拦下时，
     * 整页导航往往还能过（真实浏览器指纹 + 站点自己的验证页）。
     */
    fun loadEmailLinkLogin(
        url: String,
        isDark: Boolean = false,
        onResult: (success: Boolean, errorMsg: String?, need2FA: Boolean) -> Unit,
    ) {
        if (TopicRepository.isRateLimitCoolingDown()) {
            val remainingSec = (TopicRepository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
            onResult(false, "操作过于频繁，请等待 ${remainingSec} 秒后再试", false)
            return
        }
        onAuthCallback = onResult
        cachedEmailSend = null
        isDarkCached = isDark
        isInCfMode = false
        pendingAuthHtml = null
        authHtmlInjected = false
        pendingEmailLink = url
        emailLinkCompleting = true
        emailLinkRechecksRemaining = EMAIL_LINK_RECHECK_TIMES
        diagnostics.log("在认证浏览器中打开邮件登录链接")
        authWebView?.let { view ->
            view.loadUrl(url)
        }
        authGeckoView?.let { view ->
            authGeckoCookiesPrepared = false
            loadAuthHtml(view, "")
            pendingAuthHtml = null
        }
    }

    /**
     * 整页链接登录的结果判定：只看是否真的落到了会话 Cookie _t。
     *
     * 兑换是"导航 → 302 → Set-Cookie → 跳首页"一长串跳转，首个 onPageFinished
     * 触发时 Cookie 往往还没被内核写进 CookieManager。这里先与 WebView 同步一次，
     * 没拿到就短间隔复查若干轮，而不是一次判死。
     */
    private fun checkEmailLinkLogin(view: View, url: String?) {
        if (!emailLinkCompleting || pendingEmailLink == null) return
        if (view is GeckoView) {
            refreshBrowserCookies(view) { checkEmailLinkResult(view, url) }
        } else {
            checkEmailLinkResult(view, url)
        }
    }

    private fun checkEmailLinkResult(view: View, url: String?) {
        if (!emailLinkCompleting || pendingEmailLink == null || currentAuthView() !== view) return
        CookieManager.getInstance().flush()
        val hasSession = runCatching { store.syncFromWebView().containsKey(Config.SESSION_COOKIE) }
            .getOrDefault(false)
        if (hasSession) {
            emailLinkCompleting = false
            pendingEmailLink = null
            diagnostics.log("邮件链接已在 WebView 内完成登录，会话 Cookie 已同步")
            onAuthCallback?.invoke(true, null, false)
            return
        }
        if (emailLinkRechecksRemaining > 0) {
            emailLinkRechecksRemaining--
            view.postDelayed(
                {
                    if (emailLinkCompleting && currentAuthView() === view) {
                        checkEmailLinkLogin(view, url)
                    }
                },
                EMAIL_LINK_RECHECK_INTERVAL_MS,
            )
            return
        }
        emailLinkCompleting = false
        diagnostics.log("邮件链接页面已停止跳转但仍无会话 Cookie")
        onAuthCallback?.invoke(false, "登录链接未能完成登录，请重新获取验证码后重试", false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            userAgentString = store.userAgentOrNull() ?: userAgent
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    /**
     * 先对站点做一次真实导航,再把内联登录页 document.write 进这个第一方文档。
     *
     * 不能直接 loadDataWithBaseURL:那样文档从未真正访问过站点,它发出的 fetch 在
     * Cloudflare 眼里与真实页面不是同一个主体(Sec-Fetch-Site / Origin / Referer 都不同,
     * opaque 上下文下 Origin 可能是 null),于是出现过"真实导航 /challenge 被直接放行、
     * 同一个 CookieManager 下的 fetch 却 403"的分裂。先导航再注入之后,
     * 登录用的三个 fetch 与签发 cf_clearance 的那次导航共享同一个第一方上下文。
     *
     * 附带收益:这次导航本身要过 Cloudflare。需要出盾时盾就落在登录窗口里,
     * 用户先过盾再见九宫格 —— 与 Fluxdo 先 CF 后 hCaptcha 的主路径同形,
     * 且不必改动登录入口。
     */
    private fun loadAuthHtml(view: View, html: String) {
        pendingAuthHtml = html
        authHtmlInjected = false
        diagnostics.log("认证浏览器正在对 $AUTH_BOOTSTRAP_PATH 做第一方导航，随后加载原有 hCaptcha 页面")
        if (view is WebView) {
            view.setBackgroundColor(Color.TRANSPARENT)
            view.loadUrl("${Config.BASE_URL}$AUTH_BOOTSTRAP_PATH")
        } else if (view is GeckoView && geckoBridges.containsKey(view)) {
            view.alpha = 0f // 导航前先透明，避免 /robots.txt 文本闪现
            view.session?.loadUri("${Config.BASE_URL}$AUTH_BOOTSTRAP_PATH")
        }
    }

    /** 退回旧的 data URL 方式。bootstrap 导航失败时宁可降级,也不能让登录整体不可用。 */
    private fun loadAuthHtmlInline(view: View, reason: String) {
        val html = pendingAuthHtml ?: return
        if (authHtmlInjected) return
        if (view !is WebView) {
            diagnostics.log("Gecko 认证同源上下文不可用: $reason")
            onAuthCallback?.invoke(false, "认证页面加载失败，请重试", false)
            return
        }
        authHtmlInjected = true
        diagnostics.log("bootstrap 导航不可用($reason),回退到 data URL 方式加载内联登录页")
        view.setBackgroundColor(Color.TRANSPARENT)
        view.loadDataWithBaseURL(Config.BASE_URL, html, "text/html", "utf-8", "${Config.BASE_URL}/login")
    }

    /**
     * bootstrap 导航完成后决定是否注入内联登录页。
     *
     * 探测结果同时写进诊断日志:origin 能直接证实/证伪"opaque 上下文"的猜测,
     * document.cookie 与宿主侧 CookieManager 的对照能区分"根本没有 clearance"
     * 和"有 clearance 但页面上下文带不上"这两种完全不同的根因。
     */
    private fun maybeInjectAuthHtml(view: View, url: String?) {
        val html = pendingAuthHtml ?: return
        if (authHtmlInjected) return
        val target = Uri.parse(url.orEmpty())
        if (target.scheme != "https" || target.host != Config.HOST) return
        if (view is GeckoView && (!authGeckoCookiesPrepared || !geckoBridges.containsKey(view))) return

        evaluateBrowserScript(view, AUTH_CONTEXT_PROBE_JS) { raw ->
            if (currentAuthView() !== view || authHtmlInjected) return@evaluateBrowserScript
            val probe = runCatching { JSONObject(decodeJsValue(raw)) }.getOrNull()
            val origin = probe?.optString("origin").orEmpty()
            val cookie = probe?.optString("cookie").orEmpty()
            val isChallenge = probe?.optBoolean("challenge") == true
            val hostCookie = CookieManager.getInstance().getCookie(Config.BASE_URL).orEmpty()
            diagnostics.log(
                "登录上下文探测: origin=$origin challenge=$isChallenge " +
                    "宿主侧Cookie=[${cookieNamesOf(hostCookie)}] " +
                    "clearance=${clearanceFingerprint(currentCfClearance())} " +
                    // cf_clearance 与 _forum_session 都是 HttpOnly,JS 永远读不到它们,
                    // 这一项只用来确认脚本确实跑在站点上下文里,不能当作"带不上 Cookie"的判据。
                    "页面非HttpOnly可见=[${cookieNamesOf(cookie)}]",
            )

            when {
                // 盾就在这个窗口里,此刻注入会把挑战页冲掉。按兵不动,
                // 等 CF 自己放行后重新导航回 bootstrap 页,下一次 onPageFinished 再注入。
                isChallenge -> {
                    diagnostics.log("bootstrap 导航遇到 Cloudflare 挑战,等待用户在登录窗口内完成验证")
                    view.post { view.alpha = 1f }
                }
                origin == Config.BASE_URL -> {
                    authHtmlInjected = true
                    diagnostics.log("已取得站点第一方上下文,注入内联 hCaptcha 登录页")
                    refreshBrowserCookies(view) {}
                    evaluateBrowserScript(view,
                        "(function(){document.open();document.write(${JSONObject.quote(html)});document.close();})();",
                    ) {
                        view.post { view.alpha = 1f }
                        if (view is GeckoView && currentAuthView() === view) {
                            evaluateBrowserScript(view, """
                                (function () {
                                  if (!window.__ldb || typeof window.__ldb.onDiagnostic !== 'function' ||
                                      typeof window.__linuxdoRunLogin !== 'function') return false;
                                  window.__ldb.onDiagnostic('Gecko认证桥就绪');
                                  return true;
                                })();
                            """.trimIndent()) { result ->
                                if (currentAuthView() === view && decodeJsValue(result) != "true") {
                                    diagnostics.log("Gecko 认证页面脚本未就绪")
                                    onAuthCallback?.invoke(false, "认证页面初始化失败，请重试", false)
                                }
                            }
                        }
                    }
                }
                else -> {
                    view.post { view.alpha = 1f }
                    loadAuthHtmlInline(view, "origin=$origin")
                }
            }
        }
    }

    private fun cookieNamesOf(header: String): String =
        SessionStore.parseCookieHeader(header).keys.joinToString(",")

    /**
     * clearance 的值指纹。
     * 日志里需要能区分"同一枚旧凭据"和"CF 刚换发的新凭据",但不该把完整凭据写进文件。
     */
    private fun clearanceFingerprint(value: String?): String =
        if (value.isNullOrBlank()) "无" else "${value.take(8)}…(${value.length}字符)"


    /**
     * 认证/登录视图：使用独立的可见 GeckoView (Firefox Necko+NSS，支持 DoH+ECH)，
     * 复用 master 的同源 bootstrap、内联 hCaptcha 和登录脚本。
     */
    fun attachAuth(activityContext: Context): View {
        check(Looper.myLooper() == Looper.getMainLooper()) { "只能在主线程访问" }
        attachedActivityContext = activityContext
        if (verifyPhase != VerifyPhase.RUNNING) {
            verifyProbeJob?.cancel()
            verifyProbeJob = null
        }
        authGeckoView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            return view
        }

        val geckoView = org.linuxdo.android.net.gecko.GeckoManager.createGeckoView(activityContext).apply {
            alpha = 0f // 初始完全透明，避免在加载 /robots.txt bootstrap 期间闪烁纯文本源码
        }
        val geckoSession = org.linuxdo.android.net.gecko.GeckoManager.createChallengeSession(
            context = activityContext,
            onLocationChanged = { url ->
                diagnostics.log("Auth GeckoView 导航至: ${GeckoManager.diagnosticUrl(url)}")
            },
            onPageFinished = { url ->
                diagnostics.log("Auth GeckoView 页面完成: ${GeckoManager.diagnosticUrl(url)}")
                if (authGeckoView === geckoView) {
                    maybeInjectAuthHtml(geckoView, url)
                    if (authGeckoCookiesPrepared && pendingEmailLink != null && !url.endsWith(AUTH_BOOTSTRAP_PATH)) {
                        checkEmailLinkLogin(geckoView, url)
                    }
                }
            },
            onPageError = { _, _ -> geckoView.post { geckoView.alpha = 1f } },
        )
        geckoView.setSession(geckoSession)
        authGeckoView = geckoView
        authGeckoSession = geckoSession
        authGeckoCookiesPrepared = false
        authGeckoPreparing = false
        val stillCurrent = cookieSyncGuard(geckoView)
        authGeckoInitJob = scope.launch(Dispatchers.Main) {
            try {
                if (!stillCurrent()) return@launch
                val browserUserAgent = withTimeoutOrNull(10_000L) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        geckoSession.userAgent.accept(
                            { value -> if (continuation.isActive) continuation.resume(value) },
                            { _ -> if (continuation.isActive) continuation.resume(null) },
                        )
                    }
                }?.takeIf { it.isNotBlank() } ?: error("无法读取认证浏览器标识")
                if (!stillCurrent()) return@launch
                if (store.userAgentOrNull() != browserUserAgent) {
                    store.saveUserAgent(browserUserAgent)
                    webViewInstance?.settings?.userAgentString = browserUserAgent
                    authWebView?.settings?.userAgentString = browserUserAgent
                    diagnostics.log("接口与认证浏览器的 User-Agent 已同步")
                }
                Log.d("GeckoAuthBridge", "认证会话 User-Agent 已对齐")
                val bridge = GeckoAuthBridge.create(
                    activityContext,
                    geckoSession,
                    onReady = { url -> onAuthGeckoReady(geckoView, url) },
                    onCallback = { name, payload -> onGeckoAuthCallback(geckoView, name, payload) },
                )
                if (!stillCurrent()) {
                    bridge.close()
                    return@launch
                }
                geckoBridges[geckoView] = bridge
                diagnostics.log("Gecko 认证桥已初始化，开始同源 bootstrap → hCaptcha 登录链路")
                geckoSession.loadUri("${Config.BASE_URL}$AUTH_BOOTSTRAP_PATH")
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                diagnostics.log("Gecko 认证桥初始化失败: ${error.message}")
                if (stillCurrent()) onAuthCallback?.invoke(false, "认证组件初始化失败，请重试", false)
            }
        }
        return geckoView
    }

    private fun onAuthGeckoReady(view: GeckoView, url: String) {
        if (authGeckoView !== view) return
        val bridge = geckoBridges[view] ?: return
        if (authGeckoCookiesPrepared) {
            maybeInjectAuthHtml(view, url)
            if (pendingEmailLink != null && !url.endsWith(AUTH_BOOTSTRAP_PATH)) checkEmailLinkLogin(view, url)
            return
        }
        if (authGeckoPreparing) return
        authGeckoPreparing = true
        val stillCurrent = cookieSyncGuard(view)
        scope.launch(Dispatchers.Main) {
            try {
                if (!stillCurrent()) return@launch
                bridge.prepareAuthCookies(store.syncFromWebView())
                if (!stillCurrent()) return@launch
                authGeckoCookiesPrepared = true
                val link = pendingEmailLink
                if (link != null) view.session?.loadUri(link)
                else maybeInjectAuthHtml(view, url)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                diagnostics.log("Gecko 认证会话准备失败: ${error.message}")
                if (stillCurrent()) onAuthCallback?.invoke(false, "认证会话准备失败，请重试", false)
            } finally {
                if (stillCurrent()) authGeckoPreparing = false
            }
        }
    }

    fun detachAuth(view: View) {
        if (authGeckoView !== view) return
        if (awaitingSecondFactorLogin) return
        authGeckoInitJob?.cancel()
        val submissionId = currentSubmissionId
        val completion = authSubmitCompletion
        if (submissionId != null && completion != null && !completion.isCompleted) {
            cancelledSubmissionId = submissionId
            abortActiveAuthSubmission(submissionId, view, completion)
            return
        }
        pendingAuthHtml = null
        authHtmlInjected = false
        destroyAuthBrowser(view)
        prewarmCfClearanceIfNeeded("登录认证视图已关闭")
    }

    fun loadUrlInAuthGecko(url: String) {
        authGeckoSession?.loadUri(url)
    }

    private fun createLoggingChromeClient(scope: String): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val msg = consoleMessage?.message().orEmpty()
            val source = consoleMessage?.sourceId().orEmpty()
            val line = consoleMessage?.lineNumber() ?: 0
            val level = consoleMessage?.messageLevel()
            
            // 过滤掉常见的非关键浏览器警告，避免日志刷屏
            if (shouldFilterConsoleMessage(msg, level)) {
                return true
            }
            
            diagnostics.log("$scope WebView Console [$level] ($source:$line): $msg")
            return true
        }
        
        private fun shouldFilterConsoleMessage(msg: String, level: ConsoleMessage.MessageLevel?): Boolean {
            // 仅过滤 WARNING 级别的已知噪音，保留 ERROR 以便排障
            if (level != ConsoleMessage.MessageLevel.WARNING) {
                return false
            }
            
            val lowerMsg = msg.lowercase()
            return when {
                // Permissions-Policy 未识别特性警告（浏览器兼容性问题，非应用错误）
                lowerMsg.contains("permissions-policy") && lowerMsg.contains("unrecognized feature") -> true
                // OTS 字体解析警告（第三方字体问题，不影响功能）
                lowerMsg.contains("ots parsing error") -> true
                // 字体压缩尺寸警告（性能优化提示，非功能错误）
                lowerMsg.contains("decompressed") && lowerMsg.contains("compressed size") -> true
                else -> false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView 只能在主线程访问" }
        webViewInstance?.let { return it }

        // 开启 Chrome 开发者工具远程调试支持（可通过电脑 chrome://inspect 深入审查页面与控制台）
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val webView = WebView(MutableContextWrapper(appContext))
        configureWebView(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                hasMainFrameError = false
                val currentUrl = url.orEmpty()
                if (view != null && isChallengePathUrl(currentUrl)) {
                    installOriginFallbackMask(view)
                }
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                val currentUrl = url.orEmpty()
                if (view != null && isChallengePathUrl(currentUrl)) {
                    installOriginFallbackMask(view)
                    if (challengeOriginReached) view.alpha = 0f
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                finishedCount++
                CookieManager.getInstance().flush()
                val currentUrl = url.orEmpty()
                if (view != null && isChallengePathUrl(currentUrl)) {
                    installOriginFallbackMask(view)
                }
                Log.d(TAG, "onPageFinished: $url")
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                // 安全规范：正式构建必须严格校验 SSL 证书，杜绝放行所有 SSL 错误带来的中间人攻击安全隐患。
                val errUrl = error?.url.orEmpty()
                val primaryErr = error?.primaryError ?: -1
                diagnostics.log("WebView SSL 错误 [$primaryErr]: $errUrl")
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "SSL 错误已忽略(DEBUG): $primaryErr")
                    handler?.proceed()
                } else {
                    hasMainFrameError = true
                    handler?.cancel()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val url = request?.url?.toString().orEmpty()
                val status = errorResponse?.statusCode ?: 0
                val reason = errorResponse?.reasonPhrase.orEmpty()
                // Cloudflare PAT (Private Access Token) 硬件探测在移动端 WebView 必然返回 401，由 Turnstile 自动降级，忽略不报障
                if (url.contains("/pat/") && status == 401) {
                    return
                }
                diagnostics.log("WebView HTTP 错误 [$status $reason]: $url" + cfMitigatedSuffix(errorResponse))
                if (request?.isForMainFrame == true) {
                    if (status == 404 && isBareChallengeUrl(url) && !isCfMitigatedChallenge(errorResponse)) {
                        // 对齐 Fluxdo：/challenge 是站点专用的 CF 验证入口，回源 404 本身就是已通过 CF 的完成信号，
                        // 绝不能当成主框架错误，且立即隐藏 WebView 避免露出 Discourse 404 页面。
                        challengeOriginReached = true
                        if (view != null) installOriginFallbackMask(view)
                        diagnostics.log("WebView 主框架 /challenge 返回源站 404，判定已通过 Cloudflare")
                        return
                    }
                    if (status !in listOf(403, 503)) {
                        hasMainFrameError = true
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasMainFrameError = true
                }
                val url = request?.url?.toString().orEmpty()
                val code = error?.errorCode ?: 0
                val desc = error?.description?.toString().orEmpty()
                diagnostics.log("WebView 资源错误 [$code: $desc]: $url")
            }
        }
        webView.webChromeClient = createLoggingChromeClient("登录")
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webViewInstance = webView
        return webView
    }

    /**
     * 全屏验证页挂载 GeckoView（DoH+ECH 免梯直连）。只准备视图、会话和脚本桥，不加载任何页面：
     * 验证页只在 [beginInteractiveVerification] 时打开，开机就后台跑 /challenge 会拿 Gecko 签发的凭据覆盖 OkHttp 正在用的那枚。
     */
    fun attach(activityContext: Context): View {
        check(Looper.myLooper() == Looper.getMainLooper()) { "只能在主线程访问" }
        attachedActivityContext = activityContext

        mainGeckoView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            return view
        }

        val geckoView = org.linuxdo.android.net.gecko.GeckoManager.createGeckoView(activityContext)
        val geckoSession = org.linuxdo.android.net.gecko.GeckoManager.createChallengeSession(
            context = activityContext,
            onLocationChanged = { url -> onVerifyGeckoLocation(geckoView, url) },
            onPageFinished = { url -> onVerifyGeckoPageFinished(geckoView, url) },
            onPageError = { url, error -> onVerifyGeckoLoadError(geckoView, url, error) },
            onDiagnostic = { message ->
                if (mainGeckoView === geckoView && verifyPhase == VerifyPhase.RUNNING) {
                    diagnostics.log("验证页 Gecko $message")
                }
            },
        )
        geckoView.setSession(geckoSession)
        mainGeckoView = geckoView
        mainGeckoSession = geckoSession

        mainGeckoInitJob = scope.launch(Dispatchers.Main) {
            try {
                val bridge = GeckoAuthBridge.create(
                    activityContext,
                    geckoSession,
                    onReady = { onVerifyGeckoReady(geckoView) },
                    onCallback = { name, payload -> onGeckoAuthCallback(geckoView, name, payload) },
                )
                if (mainGeckoView !== geckoView) {
                    bridge.close()
                    return@launch
                }
                geckoBridges[geckoView] = bridge
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                // 桥只用于读 Cookie 和探测页面标记，失败时退回读 cookies.sqlite，验证仍可进行
                diagnostics.log("验证页脚本桥初始化失败，退回数据库读取凭据: ${error.message}")
            }
            if (mainGeckoView === geckoView) {
                if (verifyPhase == VerifyPhase.RUNNING) {
                    startVerifyLoad(geckoView)
                } else {
                    prewarmCfClearanceIfNeeded("验证视图挂载")
                }
            }
        }
        return geckoView
    }

    /**
     * 当应用未持有有效 cf_clearance 且未处于交互验证/登录弹窗时，
     * 优先利用已挂载的 [mainGeckoView]，若未挂载则复用无头 [ensureCfClearance] 静默访问 /challenge 获取 cf_clearance。
     * 若当前已有有效 cf_clearance 则直接跳过，绝不覆盖正在使用的凭据。
     */
    fun prewarmCfClearanceIfNeeded(reason: String = "后台凭据预热") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) { prewarmCfClearanceIfNeeded(reason) }
            return
        }
        if (verifyPhase == VerifyPhase.RUNNING || verifyProbeJob?.isActive == true || prewarmJob?.isActive == true) return
        if (authGeckoView != null || isInCfMode || awaitingSecondFactorLogin) return
        if (currentCfClearance() != null) return
        val view = mainGeckoView
        if (view != null) {
            if (mainGeckoInitJob?.isActive == true) return
            startSilentPrewarmLoad(view, reason)
        } else {
            if (solveMutex.isLocked) return
            prewarmJob = scope.launch {
                runCatching { ensureCfClearance(reason) }
            }
        }
    }

    private fun startSilentPrewarmLoad(view: GeckoView, reason: String) {
        verifyProbeJob?.cancel()
        verifyProbeJob = scope.launch(Dispatchers.Main) {
            val recoveryId = store.beginClearanceRecovery()
            try {
                val context = attachedActivityContext ?: appContext
                val geckoStale = withContext(Dispatchers.IO) {
                    GeckoManager.cfClearancesFromDb(context)
                }
                val staleSet = (geckoStale + store.cfClearancesForVerification()).toMutableSet()
                if (
                    mainGeckoView !== view ||
                    verifyPhase == VerifyPhase.RUNNING ||
                    authGeckoView != null ||
                    isInCfMode ||
                    awaitingSecondFactorLogin ||
                    currentCfClearance() != null
                ) return@launch
                if (!org.linuxdo.android.net.gecko.GeckoManager.clearSiteCookies(context)) {
                    diagnostics.log("后台预热清理 Gecko 旧凭据失败，按旧值基线判定 ($reason)")
                }
                if (
                    mainGeckoView !== view ||
                    verifyPhase == VerifyPhase.RUNNING ||
                    authGeckoView != null ||
                    isInCfMode ||
                    awaitingSecondFactorLogin ||
                    currentCfClearance() != null
                ) return@launch
                diagnostics.log(
                    "GeckoView 后台静默预热 cf_clearance 开始: $reason (排除旧凭据 ${staleSet.size} 枚, " +
                        "${GeckoManager.runtimeNetworkSummary(context)})",
                )
                view.alpha = 0f
                view.session?.stop()
                view.session?.loadUri("${Config.BASE_URL}$CF_CHALLENGE_PATH")

                val deadline = SystemClock.elapsedRealtime() + Config.CHALLENGE_TIMEOUT_MS
                var lastAdopted: String? = null
                var stableFresh: String? = null
                var quietRounds = 0
                while (
                    SystemClock.elapsedRealtime() < deadline &&
                    verifyPhase != VerifyPhase.RUNNING &&
                    mainGeckoView === view &&
                    authGeckoView == null &&
                    !isInCfMode &&
                    !awaitingSecondFactorLogin
                ) {
                    delay(VERIFY_PROBE_INTERVAL_MS)
                    if (
                        verifyPhase == VerifyPhase.RUNNING ||
                        mainGeckoView !== view ||
                        authGeckoView != null ||
                        isInCfMode ||
                        awaitingSecondFactorLogin
                    ) return@launch
                    var fresh = readVerifyGeckoClearance(view, staleSet)
                        ?.takeIf { it !in staleSet && !store.isClearanceChallenged(it) }
                    if (!fresh.isNullOrBlank() && fresh != lastAdopted) {
                        adoptGeckoClearance(fresh)
                        val active = currentCfClearance()
                        if (active == fresh) {
                            lastAdopted = fresh
                            lastClearedAtElapsed = SystemClock.elapsedRealtime()
                            diagnostics.log("GeckoView 后台静默预热取得新 cf_clearance ($reason): ${clearanceFingerprint(fresh)}")
                        } else {
                            staleSet.add(fresh)
                            diagnostics.logThrottled(
                                "prewarm-adopt-shadowed",
                                "后台预热候选凭据 ${clearanceFingerprint(fresh)} 未成为当前权威值(当前=${clearanceFingerprint(active)})",
                            )
                            fresh = null
                        }
                    }
                    val markers = probeGeckoMarkers(view)
                    if (markers?.get(MARKER_CHALLENGE) == true) {
                        quietRounds = 0
                        continue
                    }
                    val bridgeUnavailable = geckoBridges[view] == null && mainGeckoInitJob?.isActive != true
                    val markersKnown = markers != null || bridgeUnavailable
                    quietRounds = when {
                        fresh == null || (!markersKnown && fresh != stableFresh) -> 0
                        !markersKnown -> quietRounds
                        fresh == stableFresh -> quietRounds + 1
                        else -> 1
                    }
                    stableFresh = fresh
                    if (fresh != null && quietRounds >= CF_QUIET_ROUNDS_REQUIRED) {
                        return@launch
                    }
                }
            } finally {
                store.endClearanceRecovery(recoveryId)
            }
        }
    }

    /** 从界面卸下时释放 Session。验证若仍在进行，重新挂载后由 [attach] 重新加载。 */
    fun detach() {
        mainGeckoInitJob?.cancel()
        mainGeckoInitJob = null
        verifyProbeJob?.cancel()
        verifyProbeJob = null
        mainGeckoView?.let { geckoBridges.remove(it)?.close() }
        mainGeckoView?.releaseSession()
        mainGeckoSession?.close()
        mainGeckoSession = null
        mainGeckoView = null
    }

    /**
     * 清掉 Gecko 侧旧凭据后打开 /challenge，并启动完成探测。
     * 进入前已有的 cf_clearance 记为旧值：清理是异步落库的，数据库里可能还能读到它们。
     */
    private fun startVerifyLoad(view: GeckoView) {
        verifyProbeJob?.cancel()
        resetVerifyPageFlags()
        verifyProbeJob = scope.launch(Dispatchers.Main) {
            val context = attachedActivityContext ?: appContext
            GeckoManager.getOrCreateRuntime(context)
            view.session?.stop()
            val geckoStale = withContext(Dispatchers.IO) {
                GeckoManager.cfClearancesFromDb(context)
            }
            verifyStaleClearances = verifyStaleClearances + geckoStale + setOfNotNull(verificationClearance)
            if (!org.linuxdo.android.net.gecko.GeckoManager.clearSiteCookies(context)) {
                diagnostics.log("验证页清理 Gecko 旧凭据失败，继续以旧值为基线判定新签发")
            }
            if (mainGeckoView !== view || verifyPhase != VerifyPhase.RUNNING) return@launch
            GeckoManager.getOrCreateRuntime(context)
            diagnostics.log(
                "正在用 GeckoView 打开安全验证页 (排除旧凭据 ${verifyStaleClearances.size} 枚, " +
                    "${GeckoManager.runtimeNetworkSummary(context)})",
            )
            view.session?.loadUri("${Config.BASE_URL}$CF_CHALLENGE_PATH")
            runVerifyProbe(view)
        }
    }

    private fun resetVerifyPageFlags() {
        verificationPageLoaded = false
        verifyLoadFailed = false
        verifyStallLogged = false
        verifyOriginReachedAt = 0L
        verificationNeedsInteraction = false
        verificationSawChallenge = false
        activeChallengeDetectedAt = 0L
    }

    private fun onVerifyGeckoLocation(view: GeckoView, url: String) {
        if (mainGeckoView !== view || verifyPhase != VerifyPhase.RUNNING) return
        val challenging = url.contains("__cf_chl")
        diagnostics.log("验证页 Gecko 导航: ${GeckoManager.diagnosticUrl(url)}${if (challenging) " (挑战中)" else ""}")
        // 对齐 Fluxdo：挑战完成后跳回裸 /challenge，回源 404 出现前先盖上遮罩
        if (verificationSawChallenge && isBareChallengeUrl(url)) {
            verificationNeedsInteraction = false
            view.alpha = 0f
        }
    }

    private fun onVerifyGeckoPageFinished(view: GeckoView, url: String) {
        if (mainGeckoView !== view || verifyPhase != VerifyPhase.RUNNING) return
        if (runCatching { Uri.parse(url).host }.getOrNull() == Config.HOST) {
            verificationPageLoaded = true
            verifyLoadFailed = false
            diagnostics.log("验证页 Gecko 加载完成: ${GeckoManager.diagnosticUrl(url)}")
        }
    }

    private fun onVerifyGeckoLoadError(view: GeckoView, url: String, error: org.mozilla.geckoview.WebRequestError) {
        if (mainGeckoView !== view || verifyPhase != VerifyPhase.RUNNING) return
        verifyLoadFailed = true
        activeChallengeDetectedAt = 0L
        verificationNeedsInteraction = false
        view.alpha = 0f
        diagnostics.log("验证页 Gecko 加载失败 [${error.category}/${error.code}]: ${GeckoManager.diagnosticUrl(url)}")
    }

    private fun onVerifyGeckoReady(view: GeckoView) {
        if (mainGeckoView !== view || verifyPhase != VerifyPhase.RUNNING) return
        installOriginFallbackMask(view)
    }

    private fun onVerifyGeckoCallback(view: View, name: String, payload: String) {
        if (verifyPhase != VerifyPhase.RUNNING) return
        when (name) {
            "onChallengeOriginReached" -> {
                activeChallengeDetectedAt = 0L
                verificationNeedsInteraction = false
                view.alpha = 0f
                if (verifyOriginReachedAt == 0L) {
                    verifyOriginReachedAt = SystemClock.elapsedRealtime()
                    diagnostics.log("验证页已回源($payload)，等待新的 cf_clearance")
                }
            }
            "onChallengeNavigating" -> {
                verificationNeedsInteraction = false
                view.alpha = 0f
            }
        }
    }

    /**
     * 验证页完成探测，规则沿用 CF 弹窗：新 cf_clearance 出现且连续 [CF_QUIET_ROUNDS_REQUIRED] 轮无挑战标记才收口；
     * 只有看到挑战组件才露出页面；回源 404 但等不到新凭据，如实收口交给上层用 OkHttp 复核。
     */
    private suspend fun runVerifyProbe(view: GeckoView) {
        var stableFresh: String? = null
        var quietRounds = 0
        while (verifyPhase == VerifyPhase.RUNNING && mainGeckoView === view) {
            delay(VERIFY_PROBE_INTERVAL_MS)
            if (verifyPhase != VerifyPhase.RUNNING || mainGeckoView !== view) return
            if (verifyLoadFailed) {
                activeChallengeDetectedAt = 0L
                verificationNeedsInteraction = false
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val fresh = readVerifyGeckoClearance(view, verifyStaleClearances)?.takeIf { it !in verifyStaleClearances }
            // markers 为 null：页面切换中桥暂未重连，或桥初始化失败。此时不改动遮罩状态。
            val markers = probeGeckoMarkers(view)
            if (verifyPhase != VerifyPhase.RUNNING || mainGeckoView !== view) return
            if (verifyLoadFailed) continue
            if (markers == null) {
                val bridge = geckoBridges[view]
                diagnostics.logThrottled(
                    "verify-markers-unavailable",
                    "验证页标记暂不可读: 脚本桥=${bridge != null} 桥已连接=${bridge?.isConnected() == true} " +
                        "已加载完成=$verificationPageLoaded 已见挑战=$verificationSawChallenge",
                )
            }

            val originNotFound = markers?.get(MARKER_ORIGIN_NOT_FOUND) == true
            if (markers?.get(MARKER_CHALLENGE) == true && !originNotFound) {
                verifyOriginReachedAt = 0L
                verificationSawChallenge = true
                quietRounds = 0
                if (activeChallengeDetectedAt == 0L) {
                    activeChallengeDetectedAt = now
                } else if (!verificationNeedsInteraction && now - activeChallengeDetectedAt >= VERIFY_REVEAL_DELAY_MS) {
                    revealVerifyPage(view)
                }
                continue
            }
            if (markers != null) {
                if (activeChallengeDetectedAt != 0L && verificationNeedsInteraction) {
                    // 对齐 Fluxdo：原本有挑战的页面挑战标记消失，立即盖回遮罩等待收口
                    verificationNeedsInteraction = false
                    view.alpha = 0f
                }
                activeChallengeDetectedAt = 0L
                if (originNotFound && verifyOriginReachedAt == 0L) {
                    verifyOriginReachedAt = now
                    verificationNeedsInteraction = false
                    view.alpha = 0f
                    diagnostics.log("验证页 DOM 检测到源站 404，挑战字样=${markers[MARKER_CHALLENGE]}，由 App 通道复核")
                }
            }

            val bridgeUnavailable = geckoBridges[view] == null && mainGeckoInitJob?.isActive != true
            val markersKnown = markers != null || bridgeUnavailable
            // CF 一次验证可能先后换发几枚 clearance，必须连续几轮读到同一个值、且确认无挑战标记才收口；
            // 页面切换中桥暂断时既不计入也不清零
            quietRounds = when {
                fresh == null || (!markersKnown && fresh != stableFresh) -> 0
                !markersKnown -> quietRounds
                fresh == stableFresh -> quietRounds + 1
                else -> 1
            }
            stableFresh = fresh
            if (fresh != null) {
                if (quietRounds >= CF_QUIET_ROUNDS_REQUIRED) {
                    if (finishVerify(fresh, "新 cf_clearance 且连续 $quietRounds 轮无挑战标记")) return
                }
                continue
            }
            if (verifyOriginReachedAt != 0L) {
                if (now - verifyOriginReachedAt >= CF_COMPLETION_PROBE_TIMEOUT_MS) {
                    finishVerify(null, "已回源但 Gecko 未签发新的 cf_clearance")
                    return
                }
                continue
            }
            // 既无挑战组件也未回源（如 CF 拦截页），超时后露出页面，不让用户对着加载圈干等
            if (markersKnown && verificationPageLoaded &&
                !verificationNeedsInteraction && !verificationSawChallenge &&
                now - verificationStartedAt >= VERIFY_FALLBACK_REVEAL_MS
            ) {
                diagnostics.log("验证页兜底露出: 脚本桥=${geckoBridges[view] != null} 标记=${markers ?: "未读到"}")
                revealVerifyPage(view)
            }
        }
    }

    private fun revealVerifyPage(view: GeckoView) {
        verificationNeedsInteraction = true
        hideOriginFallbackMask(view)
        view.alpha = 1f
    }

    /** [clearance] 为空表示 Gecko 没换到新凭据，store 保持原样；是否真能用由上层以 OkHttp 复核。 */
    private fun finishVerify(clearance: String?, reason: String): Boolean {
        if (clearance != null && store.isClearanceChallenged(clearance)) {
            diagnostics.logThrottled("verify-rejected-clearance", "验证页候选凭据已判废，继续等待新凭据")
            return false
        }
        if (clearance != null) {
            adoptGeckoClearance(clearance)
            lastClearedAtElapsed = SystemClock.elapsedRealtime()
            verificationClearance = null
        }
        verifyPhase = VerifyPhase.DONE
        verificationNeedsInteraction = false
        mainGeckoView?.alpha = 0f
        diagnostics.log("验证页面完成，等待 App 通道确认: $reason，clearance=${clearanceFingerprint(clearance)}")
        return true
    }

    /**
     * 优先经脚本桥读 Gecko 内存中的 Cookie；桥不可用或没读到时再查 cookies.sqlite（扩展接口可能看不到分区 Cookie）。
     * 数据库里清理前的旧值已记入 [verifyStaleClearances]，不会被误当新签发。
     */
    private suspend fun readVerifyGeckoClearance(
        view: GeckoView,
        excludeClearances: Set<String> = verifyStaleClearances,
    ): String? {
        val bridge = geckoBridges[view]
        if (bridge != null) {
            val snapshot = try {
                withTimeoutOrNull(VERIFY_BRIDGE_TIMEOUT_MS) { bridge.readCookies() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val fromBridge = snapshot?.let { cookies ->
                val values = (0 until cookies.length())
                    .map { cookies.getJSONObject(it) }
                    .filter { it.optString("name").equals(CF_CLEARANCE_COOKIE, ignoreCase = true) }
                    .map { it.optString("value") }
                    .filter { it.isNotBlank() }
                selectFreshCfClearance(values, excludeClearances)
            }
            if (fromBridge != null) return fromBridge
        }
        val context = attachedActivityContext ?: appContext
        return withContext(Dispatchers.IO) {
            selectFreshCfClearance(GeckoManager.cfClearancesFromDb(context), excludeClearances)
        }
    }

    private fun selectFreshCfClearance(values: Iterable<String>, staleClearances: Set<String>): String? =
        values.lastOrNull { it.isNotBlank() && it !in staleClearances && !store.isClearanceChallenged(it) }

    /**
     * 在扩展隔离环境里探测挑战组件与源站 404 标记；拿不到结果返回 null，不当作"无挑战"。
     * 不走 page.eval：页面 CSP 不允许 eval 时它只会返回 null，按"仍有挑战"处理就永远收不了口。
     */
    private suspend fun probeGeckoMarkers(view: View): Map<String, Boolean>? {
        val bridge = geckoBridges[view] ?: return null
        return try {
            withTimeoutOrNull(VERIFY_BRIDGE_TIMEOUT_MS) { bridge.probeMarkers(VERIFY_MARKER_GROUPS) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** 记录 Gecko 网络栈的出口摘要，用于和 OkHttp 的同名记录对照凭据为何不通用。 */
    suspend fun logVerifyGeckoEgress() = withContext(Dispatchers.Main) {
        val bridge = mainGeckoView?.let { geckoBridges[it] }
        val summary = if (bridge == null) {
            "验证页脚本桥不可用"
        } else {
            try {
                withTimeoutOrNull(5_000L) { bridge.readEgressTrace() }?.let(NetDiagnostics::egressTraceSummary) ?: "读取超时"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "读取失败: ${error.message.orEmpty().take(60)}"
            }
        }
        diagnostics.log("[诊断] 出口对照 Gecko验证页: $summary")
    }

    suspend fun openUrl(url: String) = withContext(Dispatchers.Main) {
        val view = webView()
        awaitFirstLayout(view)
        view.loadUrl(url)
    }

    /**
     * 等 WebView 完成首次布局再加载页面。
     *
     * AndroidView 的 factory 刚创建出 WebView 时还没测量,宽高是 0。此时 loadUrl,
     * 像 Cloudflare 挑战页那种用 `100vh` + flex 垂直居中的静态页面会按 0 高度算出
     * "中心",把内容全堆在顶部;等 WebView 测量完成变大,页面不监听 resize 也就不会重排,
     * 于是看到的就是内容偏上、顶部元素被切掉半截。
     */
    private suspend fun awaitFirstLayout(view: WebView) {
        if (view.width > 0 && view.height > 0) return
        withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View?, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        if (right - left <= 0 || bottom - top <= 0) return
                        view.removeOnLayoutChangeListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                view.addOnLayoutChangeListener(listener)
                continuation.invokeOnCancellation { view.removeOnLayoutChangeListener(listener) }
            }
        }
    }

    suspend fun currentUrl(): String? = withContext(Dispatchers.Main) { webView().url }

    data class ProbeResult(
        val status: String,
        val csrf: String = "",
        val error: String = "",
    )

    /**
     * 通过 JS Bridge 异步探测当前 WebView 会话状态与 CSRF 可用性。
     * 解决 evaluateJavascript 无法 await Promise（导致立即返回 "{}" 误判为超时）的问题。
     */
    suspend fun runProbe(timeoutMs: Long = 4_000L): ProbeResult? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        probeRequests[reqId] = deferred
        val script = """
            (async function(reqId) {
              try {
                if (location.href === 'about:blank') {
                  window.$BRIDGE_NAME.onProbeComplete(reqId, JSON.stringify({ status: 'ABOUT_BLANK', origin: location.origin }));
                  return;
                }
                if (location.protocol !== 'https:' || location.host !== ${JSONObject.quote(Config.HOST)}) {
                  window.$BRIDGE_NAME.onProbeComplete(reqId, JSON.stringify({ status: 'WRONG_ORIGIN', origin: location.origin }));
                  return;
                }
                const hasChallenge = (document.title || '').includes('Just a moment') ||
                  Boolean(document.querySelector('#challenge-running, #challenge-stage, form#challenge-form, #cf-wrapper, .cf-turnstile'));
                if (hasChallenge) {
                  window.$BRIDGE_NAME.onProbeComplete(reqId, JSON.stringify({ status: 'CF_CHALLENGE' }));
                  return;
                }
                const res = await fetch('/session/csrf.json', {
                  method: 'GET',
                  credentials: 'include',
                  headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
                });
                if (res.status === 200) {
                  const data = await res.json();
                  window.$BRIDGE_NAME.onProbeComplete(reqId, JSON.stringify({ status: 'CLEARED', csrf: data.csrf || '' }));
                } else {
                  window.$BRIDGE_NAME.onProbeComplete(reqId, JSON.stringify({ status: 'HTTP_' + res.status }));
                }
              } catch (e) {
                window.$BRIDGE_NAME.onProbeComplete(reqId, JSON.stringify({ status: 'ERROR', msg: String(e) }));
              }
            })(${JSONObject.quote(reqId)});
        """.trimIndent()

        return try {
            withContext(Dispatchers.Main) {
                webView().evaluateJavascript(script, null)
            }
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            ProbeResult(
                status = json.optString("status"),
                csrf = json.optString("csrf"),
                error = json.optString("msg"),
            )
        } catch (_: Exception) {
            null
        } finally {
            probeRequests.remove(reqId)
        }
    }

    suspend fun currentTitle(): String = evalString("document.title")

    /** 当前页已在站内且不再是 Cloudflare 挑战页，并严格排除 Chromium 错误页与非法源。 */
    suspend fun isPageCleared(): Boolean {
        if (hasMainFrameError) return false
        val url = currentUrl() ?: return false
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != Config.HOST) return false

        val title = currentTitle()
        if (title.isNotBlank() && ChallengeDetector.isChallengeTitle(title)) return false

        val jsEnvCheck = evalString(
            """
            (function() {
                try {
                    if (window.location.protocol !== 'https:' || window.location.host !== ${JSONObject.quote(Config.HOST)}) {
                        return 'invalid_origin';
                    }
                    if (document.title.includes('Just a moment') || document.querySelector('#challenge-running, #challenge-stage, form#challenge-form, #cf-wrapper, .cf-turnstile')) {
                        return 'challenge_running';
                    }
                    var metaToken = document.querySelector('meta[name="csrf-token"]')?.content;
                    if (metaToken && metaToken.length > 5) return 'cleared';
                    var hasDiscourse = Boolean(document.querySelector('#main-outlet, .d-header, header, #site-logo, .topic-list'));
                    if (hasDiscourse) return 'cleared';
                    return 'loading';
                } catch (e) {
                    return 'error:' + e;
                }
            })()
            """.trimIndent()
        )
        if (jsEnvCheck == "cleared") return true
        if (challengeOriginReached && jsEnvCheck == "loading") return true
        return false
    }

    /** 获取当前 WebView 站内页面真实绑定的 CSRF Token */
    suspend fun currentCsrfToken(): String? {
        val metaToken = evalString("document.querySelector('meta[name=\"csrf-token\"]')?.content || ''")
        if (metaToken.isNotBlank()) return metaToken
        val probe = runProbe(timeoutMs = 4_000L)
        return probe?.csrf?.takeIf { it.isNotBlank() }
    }

    /**
     * 确保 WebView 传输层处于真实有效的站内同源环境。
     * 若当前未就绪或处于错误页，尝试重新导航首页并等待加载/过盾。
     */
    suspend fun ensureReady(): Boolean {
        if (isPageCleared()) return true
        return clearChallenge("就绪检查")
    }

    /**
     * 打开全屏验证页（重试也走这里）。验证只在可见的 GeckoView 里进行：系统 WebView 没有 ECH，
     * 直连时根本连不上 /challenge，以前它在后台报错就把验证页判成"未完成"，而用户能操作的 Gecko 页面其实已经打开。
     */
    suspend fun beginInteractiveVerification() = withContext(Dispatchers.Main) {
        prewarmJob?.cancel()
        prewarmJob = null
        // 重试时现场已无 clearance，保留首次进入时记下的那枚，供放弃验证时回填
        verifyStaleClearances = store.cfClearancesForVerification()
        store.syncFromWebView()[Config.CF_CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }?.let { verificationClearance = it }
        verifyPhase = VerifyPhase.RUNNING
        verificationStartedAt = SystemClock.elapsedRealtime()
        resetVerifyPageFlags()
        store.clearCookie(Config.CF_CLEARANCE_COOKIE)
        val view = mainGeckoView
        if (view == null) {
            // 验证页视图尚未挂载，attach() 完成后会自动加载
            diagnostics.log("验证页视图尚未挂载，等待挂载后加载")
        } else if (mainGeckoInitJob?.isActive != true) {
            startVerifyLoad(view)
        }
    }

    enum class VerifyPageState { LOADING, READY, CONFIRMING, STALLED, MISMATCH }

    /**
     * 验证页当前状态，供界面决定显示原生加载/异常层还是露出 Gecko 页面。
     * 对齐 Fluxdo：默认由原生加载遮罩覆盖，只有探测到需要人工交互的 CF 挑战组件时才露出；
     * 非交互挑战自动通过或直接回源 404 时全程保持 LOADING，不露一帧 404 页面。
     * 未开始（IDLE）也返回 LOADING：进入验证页到 [beginInteractiveVerification] 生效之间不能露出上一轮的页面。
     */
    fun verifyPageState(): VerifyPageState = when {
        verifyPhase == VerifyPhase.DONE -> VerifyPageState.CONFIRMING
        verifyPhase != VerifyPhase.RUNNING -> VerifyPageState.LOADING
        verifyLoadFailed -> VerifyPageState.STALLED
        verifyOriginReachedAt != 0L -> VerifyPageState.LOADING
        // 挑战组件已在 DOM 里出现就可以让用户操作：CF 页面的第三方子资源（Turnstile 等）卡住时 onPageStop 迟迟不来，
        // 以前这里要求整页加载完成，结果挑战明明在却被判成"验证页面加载失败"。
        verificationNeedsInteraction && (verificationPageLoaded || verificationSawChallenge) -> VerifyPageState.READY
        !verificationPageLoaded && SystemClock.elapsedRealtime() - verificationStartedAt > VERIFY_STALL_MS -> {
            if (!verifyStallLogged) {
                verifyStallLogged = true
                val view = mainGeckoView
                val bridge = view?.let { geckoBridges[it] }
                diagnostics.log(
                    "验证页 ${VERIFY_STALL_MS / 1_000L}s 内未加载完成: 脚本桥=${bridge != null} " +
                        "桥已连接=${bridge?.isConnected() == true} 已见挑战=$verificationSawChallenge " +
                        "需交互=$verificationNeedsInteraction",
                )
            }
            VerifyPageState.STALLED
        }
        else -> VerifyPageState.LOADING
    }

    /** Gecko 页面此刻是否正显示活跃的 CF 挑战组件（用户可能正在操作），此时不对 CF 发旁路探测。 */
    fun isVerifyChallengeActive(): Boolean =
        verifyPhase == VerifyPhase.RUNNING && !verifyLoadFailed && verifyOriginReachedAt == 0L && activeChallengeDetectedAt != 0L

    /**
     * App 自己的请求通道已不再被挑战（开了代理、换了网络）时直接收口验证。
     * 此时 Gecko 页面可能停在回源 404 或加载失败上，既等不到新凭据也看不到挑战标记。
     */
    fun finishVerifyForAppPath(): Boolean {
        if (verifyPhase != VerifyPhase.RUNNING || isVerifyChallengeActive() || TopicRepository.isRateLimitCoolingDown()) return false
        verifyProbeJob?.cancel()
        verificationClearance = null
        return finishVerify(null, "App 请求通道已不被 Cloudflare 挑战")
    }

    /** 验证页加载异常：Gecko 主框架报错，或打开后 [VERIFY_STALL_MS] 内一页都没加载完。 */
    fun isVerificationPageStalled(): Boolean = verifyPageState() == VerifyPageState.STALLED

    /** 验证页已收口（取得新凭据，或已回源但等不到新凭据）。凭据是否真能用由上层用 OkHttp 复核。 */
    fun interactiveVerificationComplete(): Boolean = verifyPhase == VerifyPhase.DONE

    /**
     * 用户放弃验证时：若没拿到新 clearance，就把进入验证前的那枚回填。
     * beginInteractiveVerification 为检测换发会先删掉旧 clearance，验证页又打不开（DoH 直连）时
     * 直接返回会让后续 GET 也失去凭据。
     */
    suspend fun restoreClearanceIfVerificationAbandoned() = withContext(Dispatchers.Main) {
        val previous = verificationClearance?.takeIf { it.isNotBlank() } ?: return@withContext
        verificationClearance = null
        val current = store.syncFromWebView()[Config.CF_CLEARANCE_COOKIE]
        if (!current.isNullOrBlank()) return@withContext
        CookieManager.getInstance().apply {
            setCookie(
                Config.BASE_URL,
                "${Config.CF_CLEARANCE_COOKIE}=$previous; Domain=.${Config.HOST}; Path=/; Secure; HttpOnly; SameSite=None; Max-Age=86400",
            )
            flush()
        }
        diagnostics.log("验证已取消，回填进入验证前的 cf_clearance(指纹 ${NetDiagnostics.fingerprint(previous)})")
    }

    suspend fun stopSessionRequests() = withContext(Dispatchers.Main) {
        try {
            cancelCaptchaLogin()
            pending.values.forEach { it.cancel(CancellationException("会话已切换")) }
            pending.clear()
            probeRequests.values.forEach { it.cancel(CancellationException("会话已切换")) }
            probeRequests.clear()
            verifyPhase = VerifyPhase.IDLE
            verifyProbeJob?.cancel()
            verifyProbeJob = null
            prewarmJob?.cancel()
            prewarmJob = null
            resetVerifyPageFlags()
            mainGeckoSession?.let { session ->
                session.stop()
                session.loadUri("about:blank")
            }
            challengeOriginReached = false
            webViewInstance?.let { view ->
                view.alpha = 1f
                view.stopLoading()
                view.loadUrl("about:blank")
            }
        } finally {
            verifyPhase = VerifyPhase.IDLE
            endLoginClearanceRecovery()
            store.completeVerification()
        }
    }

    /**
     * 静默重验证:在 WebView 里重新加载首页,等待挑战自动通过,并把最新 Cookie 同步回加密存储。
     * 超时返回 false,由调用方决定是否转入可见的交互验证。
     */
    /** 当前 WebView 里是否已有 cf_clearance。 */
    suspend fun hasCfClearance(): Boolean = withContext(Dispatchers.Main) { currentCfClearance() != null }

    /**
     * 没有 cf_clearance 时，在隐藏 WebView 里静默打开 /challenge 换取一枚。已有则直接返回 true。
     *
     * cf_clearance 只在真正通过一次挑战后签发。网页授权在外部浏览器完成，凭据带不进 App，
     * 兑换阶段的几个 GET 又不会被挑战，于是登录成功却始终没有 cf_clearance，第一个 POST 必然撞写规则。
     * 首页通常不被挑战，加载首页拿不到凭据；/challenge 是站点专用验证入口，必定触发挑战，
     * 非交互挑战会在这里自动通过并签发凭据。
     */
    suspend fun ensureCfClearance(reason: String, timeoutMs: Long = Config.CHALLENGE_TIMEOUT_MS): Boolean =
        solveMutex.withLock {
            if (hasCfClearance() || challengeOriginReached) return@withLock true
            acquireCfClearanceLocked(reason, timeoutMs)
        }

    /** 调用方必须已持有 [solveMutex]。 */
    private suspend fun acquireCfClearanceLocked(reason: String, timeoutMs: Long = Config.CHALLENGE_TIMEOUT_MS): Boolean {
        val recoveryId = store.beginClearanceRecovery()
        return try {
            acquireCfClearanceInRecovery(reason, timeoutMs)
        } finally {
            store.endClearanceRecovery(recoveryId)
        }
    }

    private suspend fun acquireCfClearanceInRecovery(reason: String, timeoutMs: Long): Boolean {
        if (verifyPhase == VerifyPhase.RUNNING) {
            // 与验证页共用 Gecko Cookie 库，这里清 Cookie 会把用户正在过的挑战清掉
            diagnostics.log("静默补签让位: 验证页正在进行 ($reason)")
            lastSilentOutcome = SilentOutcome.CHALLENGE
            return false
        }
        withContext(Dispatchers.Main) {
            verifyProbeJob?.cancel()
            verifyProbeJob = null
        }
        val startedAt = SystemClock.elapsedRealtime()
        val context = attachedActivityContext ?: appContext
        diagnostics.log(
            "静默补签 cf_clearance 开始: $reason (${GeckoManager.runtimeNetworkSummary(context)})",
        )
        val generation = store.sessionGeneration

        val staleClearances = withContext(Dispatchers.Main) { store.cfClearancesForVerification() }.toMutableSet()
        staleClearances.addAll(withContext(Dispatchers.IO) { GeckoManager.cfClearancesFromDb(context) })
        diagnostics.log("静默补签基线: 排除 ${staleClearances.size} 枚旧凭据，其中已判废 ${staleClearances.count(store::isClearanceChallenged)} 枚")
        if (generation != store.sessionGeneration || store.sessionChanging) throw CancellationException("会话已切换")
        if (verifyPhase == VerifyPhase.RUNNING) {
            lastSilentOutcome = SilentOutcome.CHALLENGE
            return false
        }
        val authInProgress = withContext(Dispatchers.Main) { authGeckoView != null }
        if (authInProgress) {
            // 登录 Gecko 正在用同一 Cookie 库，整站清理会打断登录会话，仅通过认证桥定点清除 cf_clearance
            diagnostics.log("静默补签: 登录页正在进行，仅定点清理 cf_clearance 并按旧值基线判定")
            withContext(Dispatchers.Main) {
                runCatching { authGeckoView?.let { geckoBridges[it] }?.clearCfClearance() }
            }
        } else if (!GeckoManager.clearSiteCookies(context)) {
            diagnostics.log("静默补签: 清理 Gecko 旧凭据失败，仅按旧值基线判定")
        }

        var headlessLoadFailed = false
        var headlessFinishedAt = 0L
        val headlessSession = withContext(Dispatchers.Main) {
            if (generation != store.sessionGeneration || store.sessionChanging) throw CancellationException("会话已切换")
            if (verifyPhase == VerifyPhase.RUNNING) return@withContext null
            org.linuxdo.android.net.gecko.GeckoManager.createChallengeSession(
                context = context,
                onLocationChanged = { url -> diagnostics.log("Headless GeckoSession 导航: ${GeckoManager.diagnosticUrl(url)}") },
                onPageFinished = { url ->
                    if (headlessFinishedAt == 0L) headlessFinishedAt = SystemClock.elapsedRealtime()
                    diagnostics.log("Headless GeckoSession 完成: ${GeckoManager.diagnosticUrl(url)}")
                },
                onPageError = { url, error ->
                    headlessLoadFailed = true
                    diagnostics.log("Headless GeckoSession 加载失败 [${error.category}/${error.code}]: ${GeckoManager.diagnosticUrl(url)}")
                },
                onDiagnostic = { message ->
                    diagnostics.log("Headless GeckoSession $message")
                },
            ).also { it.loadUri("${Config.BASE_URL}$CF_CHALLENGE_PATH") }
        } ?: run {
            lastSilentOutcome = SilentOutcome.CHALLENGE
            return false
        }

        // 静默等待使用有限预算，主框架失败立即返回；未取得 Cookie 不能证明挑战需要人工操作。
        val effectiveTimeoutMs = timeoutMs.coerceAtMost(HEADLESS_REVALIDATE_MAX_MS)
        val deadline = startedAt + effectiveTimeoutMs
        var outcome = SilentOutcome.TIMEOUT
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_INTERVAL_MS)
                if (generation != store.sessionGeneration || store.sessionChanging) throw CancellationException("会话已切换")
                if (verifyPhase == VerifyPhase.RUNNING) {
                    outcome = SilentOutcome.CHALLENGE
                    break
                }
                if (headlessLoadFailed) {
                    outcome = SilentOutcome.UNREACHABLE
                    break
                }
                val candidates = withContext(Dispatchers.IO) { GeckoManager.cfClearancesFromDb(context) }
                val rejected = candidates.filter(store::isClearanceChallenged)
                if (staleClearances.addAll(rejected)) {
                    diagnostics.logThrottled("silent-rejected-clearance", "静默补签忽略已判废凭据，继续等待新值")
                }
                val clearance = selectFreshCfClearance(candidates, staleClearances)
                if (clearance == null) {
                    val finishedAt = headlessFinishedAt
                    if (finishedAt > 0L && SystemClock.elapsedRealtime() - finishedAt >= HEADLESS_LOADED_WAIT_MS) {
                        diagnostics.log("静默补签页面加载后的等待预算耗尽，尚未取得新凭据，未确认是否需要交互；交由可见验证页继续处理")
                        outcome = SilentOutcome.TIMEOUT
                        break
                    }
                    continue
                }
                val adopted = withContext(Dispatchers.Main) {
                    if (generation != store.sessionGeneration || store.sessionChanging) throw CancellationException("会话已切换")
                    if (verifyPhase == VerifyPhase.RUNNING || store.isClearanceChallenged(clearance)) {
                        false
                    } else {
                        adoptGeckoClearance(clearance)
                        val active = currentCfClearance()
                        if (active == clearance) {
                            true
                        } else {
                            staleClearances.add(clearance)
                            diagnostics.logThrottled(
                                "silent-adopt-shadowed",
                                "静默补签候选凭据 ${clearanceFingerprint(clearance)} 未成为当前权威值(当前=${clearanceFingerprint(active)})",
                            )
                            false
                        }
                    }
                }
                if (adopted) {
                    diagnostics.log("Headless GeckoSession 静默补签成功取得 cf_clearance: ${clearanceFingerprint(clearance)}")
                    outcome = SilentOutcome.CLEARED
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            diagnostics.log("[诊断] 静默补签 cf_clearance 被取消(+${SystemClock.elapsedRealtime() - startedAt}ms)")
            throw cancelled
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                headlessSession.close()
            }
        }
        lastSilentOutcome = outcome
        val cleared = outcome == SilentOutcome.CLEARED
        if (cleared) {
            lastClearedAtElapsed = SystemClock.elapsedRealtime()
        }
        diagnostics.log(
            "静默补签 cf_clearance ${if (cleared) "成功" else "未通过(${outcome.name})"}" +
                " 耗时=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        return cleared
    }

    suspend fun clearChallenge(
        reason: String,
        failedClearanceFp: String = "-",
        requestHost: String = Config.HOST,
    ): Boolean = solveMutex.withLock {
        if (!requestHost.equals(Config.HOST, ignoreCase = true)) {
            diagnostics.log("子站挑战不触发主站凭据重签: $requestHost")
            return@withLock false
        }
        val currentClearance = withContext(Dispatchers.Main) {
            CookieManager.getInstance().flush()
            currentCfClearance()
        }
        val currentFp = NetDiagnostics.fingerprint(currentClearance)
        val now = SystemClock.elapsedRealtime()
        val sinceLastClear = if (lastClearedAtElapsed > 0L) now - lastClearedAtElapsed else Long.MAX_VALUE
        // P0-1 锁内 Double-Check：
        // a. 请求携带的 failedClearanceFp 与当前 WebView 权威 currentFp 已不同（且当前值未被判死）；
        // b. 起飞时无凭据(failedClearanceFp == "-")，但距上次 CLEARED < 8s 且当前已持有未判死的 cf_clearance。
        val currentHealthy = currentFp != "-" && !store.isClearanceChallenged(currentClearance)
        val clearanceRotated = failedClearanceFp != "-" &&
            currentHealthy &&
            currentFp != failedClearanceFp
        val recentlyCleared = failedClearanceFp == "-" &&
            currentHealthy &&
            sinceLastClear in 0L..COALESCE_CLEAR_WINDOW_MS
        if (clearanceRotated || recentlyCleared) {
            withContext(Dispatchers.Main) { store.syncFromWebView() }
            lastSilentOutcome = SilentOutcome.CLEARED
            diagnostics.log(
                "静默重验证合并跳过(凭据已就绪): $reason | failedFp=$failedClearanceFp currentFp=$currentFp" +
                    " sinceLast=${if (sinceLastClear == Long.MAX_VALUE) -1L else sinceLastClear}ms",
            )
            return@withLock true
        }
        if (failedClearanceFp != "-") {
            store.noteClearanceChallenged(failedClearanceFp, host = Config.HOST)
        }
        // 在免梯环境下，系统 WebView 无法走 ECH，统一委派给 GeckoSession 静默补签
        return@withLock acquireCfClearanceLocked(reason)
    }

    /** 403 诊断：主站与 credit 子站的 CF/会话 Cookie 作用域与指纹。 */
    suspend fun logCookieInventory(stage: String) {
        val inventory = withContext(Dispatchers.Main) { runCatching { store.cookieInventory() }.getOrElse { "读取失败: ${it.javaClass.simpleName}" } }
        diagnostics.log("[诊断] Cookie清单($stage): $inventory")
    }

    @Volatile private var environmentLogged = false

    /** 每个进程记录一次：存储 UA、WebView 实际 UA、系统默认 UA 与 Client Hints 品牌版本是否一致。 */
    suspend fun logEnvironmentOnce() {
        if (environmentLogged) return
        environmentLogged = true
        val systemUa = runCatching { android.webkit.WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("")
        val pageUa = evalString("navigator.userAgent")
        val brands = evalString(
            "(navigator.userAgentData && navigator.userAgentData.brands) ? navigator.userAgentData.brands.map(function(b){return b.brand+'/'+b.version}).join(',') : 'none'",
        )
        val chromeVersion = { ua: String -> Regex("Chrome/([\\d.]+)").find(ua)?.groupValues?.get(1) ?: "-" }
        diagnostics.log(
            "[诊断] 环境: 固定UA Chrome=${chromeVersion(userAgent)} 页面UA Chrome=${chromeVersion(pageUa)}" +
                " 系统WebView Chrome=${chromeVersion(systemUa)} 页面UA与固定UA一致=${pageUa == userAgent}" +
                " ClientHints=$brands uaFp=${NetDiagnostics.fingerprint(userAgent)}",
        )
    }

    /** 路径 B:在已通过挑战的页面内用 fetch() 请求,走真实 Chrome 网络栈。 */
    suspend fun fetchJson(
        path: String,
        csrf: String? = null,
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/json; charset=utf-8",
        present: Boolean = true,
        allowRevalidate: Boolean = true,
    ): TransportResult {
        val generation = store.sessionGeneration
        val requestHost = Uri.parse(path).host ?: Config.HOST
        if (!isPageCleared()) {
            if (!allowRevalidate) {
                diagnostics.log("[B: WebView fetch] 前置检查未通过(禁止循环重验)，放弃请求: $path")
                return TransportResult(0, "WebView 前置检查未通过(页面未就绪)", false)
            }
            val cleared = clearChallenge("fetch 前置检查", requestHost = requestHost)
            if (!cleared || !isPageCleared()) {
                diagnostics.log("[B: WebView fetch] 前置检查未通过，放弃请求: $path (cleared=$cleared mainFrameError=$hasMainFrameError)")
                return TransportResult(0, "WebView 前置检查未通过(页面未就绪或无法连接)", false)
            }
        }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TransportResult>()
        pending[id] = deferred
        try {
            withContext(Dispatchers.Main) {
                if (generation != store.sessionGeneration || store.sessionChanging) throw CancellationException("会话已切换")
                webView().evaluateJavascript(buildFetchScript(id, path, csrf, method, body, contentType, present), null)
            }
            val result = withTimeoutOrNull(Config.FETCH_TIMEOUT_MS) { deferred.await() }
            if (generation != store.sessionGeneration) throw CancellationException("会话已切换")
            if (result == null || method != "GET" || result.status >= 400 || result.status == 0) {
                diagnostics.log(
                    "[诊断] B $method $path -> ${result?.status ?: "超时"} challenged=${result?.challenged}" +
                        " body=${result?.let { NetDiagnostics.bodyFeatures(it.body) } ?: "-"} hasCsrf=${!csrf.isNullOrBlank()}",
                )
            }
            return result ?: TransportResult(0, "WebView fetch 超时", false)
        } finally {
            pending.remove(id)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                webViewInstance?.evaluateJavascript("window.__requestAbort?.[${JSONObject.quote(id)}]?.abort()", null)
            }
        }
    }

    suspend fun reset() = withContext(Dispatchers.Main) {
        stopSessionRequests()
        val webView = webView()
        webView.loadUrl("about:blank")
        webView.clearCache(true)
        webView.clearHistory()
    }

    suspend fun clearResourceCache() = withContext(Dispatchers.Main) {
        webViewInstance?.clearCache(true)
    }

    private fun buildFetchScript(
        id: String,
        path: String,
        csrf: String?,
        method: String,
        body: String?,
        contentType: String,
        present: Boolean = true,
    ): String {
        val targetUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "${Config.BASE_URL}${if (path.startsWith("/")) "" else "/"}$path"
        }
        return """
        (function () {
          var id = ${JSONObject.quote(id)};
          window.__requestAbort = window.__requestAbort || {};
          var controller = new AbortController();
          window.__requestAbort[id] = controller;
          var csrfToken = ${JSONObject.quote(csrf.orEmpty())};
          if (!csrfToken) {
            var meta = document.querySelector('meta[name="csrf-token"]');
            if (meta && meta.content) csrfToken = meta.content;
          }
          var headers = {
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
            ${if (present) ", 'Discourse-Present': 'true'" else ""}
            ${if (path.startsWith("/topics/timings")) ", 'Discourse-Background': 'true', 'X-SILENCE-LOGGER': 'true'" else ""}
            ${if (body == null) "" else ", 'Content-Type': " + JSONObject.quote(contentType)}
          };
          if (csrfToken) {
            headers['X-CSRF-Token'] = csrfToken;
          }
          fetch(${JSONObject.quote(targetUrl)}, {
            signal: controller.signal,
            method: ${JSONObject.quote(method)},
            ${if (body == null) "" else "body: ${JSONObject.quote(body)},"}
            credentials: 'include',
            headers: headers
          }).then(function (r) {
            return r.text().then(function (t) {
              $BRIDGE_NAME.onResult(id, r.status, r.headers.get('cf-mitigated') || '', t, r.headers.get('retry-after') || '');
            });
          }).catch(function (e) {
            $BRIDGE_NAME.onResult(id, 0, '', String(e), '');
          }).finally(function () {
            delete window.__requestAbort[id];
          });
        })();
        """.trimIndent()
    }

    private suspend fun evalString(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            webView().evaluateJavascript(script) { raw -> if (continuation.isActive) continuation.resume(decodeJsValue(raw)) }
        }
    }

    private fun decodeJsValue(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            JSONTokener(raw).nextValue()?.toString().orEmpty()
        } catch (error: JSONException) {
            raw
        }
    }

    private companion object {
        const val TAG = "BrowserSession"
        const val BRIDGE_NAME = "__ldb"
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
        const val CF_CHALLENGE_PATH = "/challenge"
        const val UNREACHABLE_GRACE_MS = 3_000L
        /** Gecko 首次打开要冷启动、走 DoH 并取 ECH 配置，3.5 秒会把正常加载误报成连不上。 */
        const val VERIFY_STALL_MS = 8_000L
        const val VERIFY_REVEAL_DELAY_MS = 600L
        const val VERIFY_FALLBACK_REVEAL_MS = 1_800L
        const val VERIFY_PROBE_INTERVAL_MS = 500L
        const val VERIFY_BRIDGE_TIMEOUT_MS = 1_500L

        const val MARKER_CHALLENGE = "challenge"
        const val MARKER_ORIGIN_NOT_FOUND = "originNotFound"

        /** 与 [ACTIVE_CHALLENGE_PROBE_JS]、[ORIGIN_NOT_FOUND_PROBE_JS] 的标记词保持一致，供 Gecko 验证页在扩展隔离环境里匹配。 */
        val VERIFY_MARKER_GROUPS = mapOf(
            MARKER_CHALLENGE to listOf("cf-turnstile", "challenge-running", "challenge-stage", "cf_chl_opt"),
            MARKER_ORIGIN_NOT_FOUND to listOf(
                "page-not-found", "discourse-no-results", "\"errortype\":\"notfound\"", "404-body",
                "exist or is private", "page you requested", "该页面不存在",
            ),
        )

        /**
         * 登录页的第一方 bootstrap 路径。
         * 取站点上最轻的静态资源:只是为了拿到真实的 linux.do 文档上下文,不需要它的内容。
         */
        const val AUTH_BOOTSTRAP_PATH = "/robots.txt"

        const val CF_SETTLE_DELAY_MS = 1_500L
        const val CF_PROBE_INTERVAL_MS = 750L
        const val AUTH_ABORT_ACK_TIMEOUT_MS = 2_000L

        /** 邮件链接兑换时，首个 onPageFinished 之后还要复查几轮 Cookie。 */
        const val EMAIL_LINK_RECHECK_TIMES = 15
        const val EMAIL_LINK_RECHECK_INTERVAL_MS = 800L

        /** 等 Chromium 把 Set-Cookie 落到 CookieManager 的时间。 */
        const val CF_COOKIE_SETTLE_DELAY_MS = 300L

        /** 判定"已回源"后，等待新 cf_clearance 出现的上限，对齐 Fluxdo 的完成探测窗口。 */
        const val CF_COMPLETION_PROBE_TIMEOUT_MS = 8_000L

        /** 收口前要求连续多少轮查不到挑战标记，用来挡掉 CF 完成跳转瞬间的空窗。 */
        const val CF_QUIET_ROUNDS_REQUIRED = 2

        const val HCAPTCHA_CALLBACK_SETTLE_DELAY_MS = 300L
        const val POLL_INTERVAL_MS = 700L
        /** 无头静默补签总等待预算，耗尽后交由可见验证页处理。 */
        const val HEADLESS_REVALIDATE_MAX_MS = 10_000L
        /** 页面加载完成后的无凭据等待预算；耗尽仅表示验证结果未确认。 */
        const val HEADLESS_LOADED_WAIT_MS = 5_500L
        const val LAYOUT_TIMEOUT_MS = 1_500L
        /** 同批并发请求在锁内合并跳过重验证的时间窗口（8 秒，覆盖单轮重载耗时）。 */
        const val COALESCE_CLEAR_WINDOW_MS = 8_000L

        /** 探测登录 WebView 的真实执行上下文,顺带回报是否正停在 CF 挑战页。 */
        const val AUTH_CONTEXT_PROBE_JS = """
            (function () {
              var html = '';
              try { html = (document.documentElement ? document.documentElement.innerHTML : '').toLowerCase(); } catch (e) {}
              var title = (document.title || '').toLowerCase();
              var challenge = html.indexOf('cf_chl_opt') !== -1 ||
                html.indexOf('challenge-platform') !== -1 ||
                title.indexOf('just a moment') !== -1;
              return JSON.stringify({
                origin: window.location.origin,
                href: window.location.href,
                challenge: challenge,
                cookie: document.cookie || ''
              });
            })();
        """

        /**
         * 页面上是否还有活跃的 Cloudflare 挑战,对齐 Fluxdo 的 hasActiveCfChallenge。
         * 新 clearance 可能在 JS challenge 跑完之前就落下来,只有这些标记都消失才算真的过了。
         */
        const val ACTIVE_CHALLENGE_PROBE_JS = """
            (function () {
              var html = '';
              try { html = document.documentElement ? document.documentElement.innerHTML : ''; } catch (e) {}
              return html.indexOf('cf-turnstile') !== -1 ||
                html.indexOf('challenge-running') !== -1 ||
                html.indexOf('challenge-stage') !== -1 ||
                html.indexOf('cf_chl_opt') !== -1;
            })();
        """

        /**
         * 源站 404 的特征集,对齐 Fluxdo 的 isOriginNotFound。
         * 刻意不匹配裸 "404" —— 论坛标题、帖子内容里出现这三个字符太常见,会把
         * 仍在验证中的页面误判成已回源。
         */
        const val ORIGIN_NOT_FOUND_PROBE_JS = """
            (function () {
              var html = '';
              try { html = (document.documentElement ? document.documentElement.innerHTML : '') + ' ' + (document.title || ''); } catch (e) {}
              html = html.toLowerCase();
              return html.indexOf('page-not-found') !== -1 ||
                html.indexOf('discourse-no-results') !== -1 ||
                html.indexOf('"errortype":"notfound"') !== -1 ||
                html.indexOf('404-body') !== -1 ||
                html.indexOf('exist or is private') !== -1 ||
                html.indexOf('page you requested') !== -1 ||
                html.indexOf('该页面不存在') !== -1;
            })();
        """

        /**
         * 对齐 Fluxdo 的 _documentStartMaskScript：
         * 1. 在页面卸载（beforeunload/pagehide）瞬间立即在 DOM 顶层铺满纯色遮罩并通知原生层隐藏 WebView；
         * 2. 通过 MutationObserver 实时监听源站 404 DOM 特征，一旦命中同一帧内立刻遮罩并通知收口。
         */
        fun buildOriginFallbackMaskScript(maskColorHex: String): String = """
            (function () {
              window.__linuxdoCfMaskColor = ${JSONObject.quote(maskColorHex)};
              function root() {
                return document.body || document.documentElement;
              }
              function ensureMask() {
                var r = root();
                if (!r) return null;
                var mask = document.getElementById('__linuxdoCfMask');
                if (!mask) {
                  mask = document.createElement('div');
                  mask.id = '__linuxdoCfMask';
                  mask.style.cssText = 'position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;background:' +
                    (window.__linuxdoCfMaskColor || '#f2f2f7') +
                    '!important;z-index:2147483647!important;pointer-events:auto!important;';
                  r.appendChild(mask);
                }
                return mask;
              }
              window.__linuxdoShowCfMask = function (force) {
                if (window.__linuxdoCfMaskSuppressed && !force) return;
                window.__linuxdoCfMaskSuppressed = false;
                var mask = ensureMask();
                if (mask) mask.style.display = 'block';
              };
              window.__linuxdoHideCfMask = function () {
                window.__linuxdoCfMaskSuppressed = true;
                var mask = document.getElementById('__linuxdoCfMask');
                if (mask) mask.style.display = 'none';
              };
              function looksLikeOrigin404() {
                try {
                  var html = document.documentElement ? document.documentElement.innerHTML : '';
                  var title = document.title || '';
                  var haystack = (html + ' ' + title).toLowerCase();
                  return haystack.indexOf('page-not-found') !== -1 ||
                    haystack.indexOf('discourse-no-results') !== -1 ||
                    haystack.indexOf('"errortype":"notfound"') !== -1 ||
                    haystack.indexOf('404-body') !== -1 ||
                    haystack.indexOf('exist or is private') !== -1 ||
                    haystack.indexOf('page you requested') !== -1 ||
                    haystack.indexOf('该页面不存在') !== -1;
                } catch (e) {
                  return false;
                }
              }
              function probeOrigin404() {
                if (window.__linuxdoOrigin404Notified || !looksLikeOrigin404()) return;
                window.__linuxdoOrigin404Notified = true;
                window.__linuxdoShowCfMask(true);
                try {
                  window.$BRIDGE_NAME.onChallengeOriginReached('dom-observer');
                } catch (e) {}
              }
              probeOrigin404();
              if (!window.__linuxdoCfObserverInstalled) {
                window.__linuxdoCfObserverInstalled = true;
                try {
                  var observer = new MutationObserver(probeOrigin404);
                  observer.observe(document.documentElement || document, {
                    childList: true,
                    subtree: true,
                    characterData: true
                  });
                } catch (e) {}
                function notifyNav(reason) {
                  window.__linuxdoShowCfMask(true);
                  try {
                    window.$BRIDGE_NAME.onChallengeNavigating(reason);
                  } catch (e) {}
                }
                window.addEventListener('beforeunload', function () { notifyNav('beforeunload'); });
                window.addEventListener('pagehide', function () { notifyNav('pagehide'); });
              }
            })();
        """.trimIndent()

        internal fun isSuccessfulSessionAuthResult(result: JSONObject, hasSecondFactorToken: Boolean): Boolean {
            if (result.optString("phase") != "session" || result.optInt("status") != 200) return false
            val body = runCatching { JSONObject(result.optString("body")) }.getOrNull() ?: return false
            val hasError = listOf("error", "errors").any { key ->
                body.optString(key).let { it.isNotBlank() && it != "[]" && it != "null" }
            }
            return !hasError && !isSecondFactorResponse(body, hasSecondFactorToken)
        }

        /**
         * 判断 Discourse 登录响应是否要求提供/重试二步验证（2FA）。
         * 对齐 Fluxdo 与 Discourse 3.1+ 契约：
         * 1. reason 为 second_factor、invalid_second_factor、invalid_second_factor_method 或包含 second_factor；
         * 2. totp_enabled / backup_enabled / security_key_enabled / second_factor_required 标志位为 true；
         * 3. 首次未带 2FA 时，错误文案包含"双重身份验证"（如"所选双重身份验证方法无效。"）、"两步"、"二步"等。
         */
        fun isSecondFactorResponse(resObj: JSONObject?, hasSecondFactorToken: Boolean): Boolean {
            if (resObj == null) return false
            val reason = resObj.optString("reason")
            val error = resObj.optString("error").takeIf { it.isNotBlank() }
                ?: resObj.optString("errors").takeIf { it.isNotBlank() }
            val totpEnabled = resObj.optBoolean("totp_enabled")
            val backupEnabled = resObj.optBoolean("backup_enabled")
            val securityKeyEnabled = resObj.optBoolean("security_key_enabled")
            val secondFactorRequired = resObj.optBoolean("second_factor_required")
            val hasMulti2fa = resObj.has("multiple_second_factor_methods")

            val isReason2fa = reason == "second_factor" ||
                reason == "invalid_second_factor" ||
                reason == "invalid_second_factor_method" ||
                reason.contains("second_factor", ignoreCase = true)
            val isFlag2fa = totpEnabled || backupEnabled || securityKeyEnabled || secondFactorRequired || hasMulti2fa
            val isError2fa = !hasSecondFactorToken && !error.isNullOrBlank() && (
                error.contains("双重身份验证") ||
                    error.contains("两步") ||
                    error.contains("二步") ||
                    error.contains("second factor", ignoreCase = true) ||
                    error.contains("authentication code", ignoreCase = true)
            )
            return isReason2fa || isFlag2fa || isError2fa
        }

        fun extractSecondFactorMessage(resObj: JSONObject?, hasSecondFactorToken: Boolean): String {
            val error = resObj?.optString("error")?.takeIf { it.isNotBlank() }
                ?: resObj?.optString("errors")?.takeIf { it.isNotBlank() }
            return if (hasSecondFactorToken) {
                error ?: "二次验证码错误或已过期，请重新输入"
            } else {
                "需要进行二次验证 (2FA)"
            }
        }
    }
}

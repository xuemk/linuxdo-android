package org.linuxdo.android.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.linuxdo.android.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
) {
    private var webViewInstance: WebView? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TransportResult>>()
    private val solveMutex = Mutex()

    @Volatile
    private var finishedCount = 0

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, status: Int, mitigated: String, body: String) {
            pending.remove(id)?.complete(
                TransportResult(status, body, ChallengeDetector.isChallenge(status, mitigated, body)),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView 只能在主线程访问" }
        webViewInstance?.let { return it }
        val webView = WebView(MutableContextWrapper(appContext))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url
                if (uri != null && uri.scheme == "discourse" && uri.host == "auth_redirect") {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appContext.startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "启动深链失败: ${e.message}")
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                finishedCount++
                CookieManager.getInstance().flush()
                Log.d(TAG, "onPageFinished: $url")
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                // DEBUG ONLY: 本地代理(Clash/v2rayN)会插入自签证书导致 SSL 握手失败白屏。
                // 仅 debug 构建忽略，release 保持默认 cancel。
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "SSL 错误已忽略(DEBUG): ${error?.primaryError}")
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webViewInstance = webView
        return webView
    }

    /** 挂到界面时把 Context 换成 Activity,键盘/弹窗才正常;必要时先从旧父容器摘下。 */
    fun attach(activityContext: Context): WebView {
        val webView = webView()
        (webView.context as? MutableContextWrapper)?.baseContext = activityContext
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    /** 从界面卸下时还原为 Application Context,避免泄漏 Activity。 */
    fun detach() {
        (webViewInstance?.context as? MutableContextWrapper)?.baseContext = appContext
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

    suspend fun currentTitle(): String = evalString("document.title")

    /** 当前页已在站内且不再是 Cloudflare 挑战页。 */
    suspend fun isPageCleared(): Boolean {
        val host = currentUrl()?.let { Uri.parse(it).host }
        if (host != Config.HOST) return false
        val title = currentTitle()
        return title.isNotBlank() && !ChallengeDetector.isChallengeTitle(title)
    }

    /**
     * 静默重验证:在 WebView 里重新加载首页,等待挑战自动通过,并把最新 Cookie 同步回加密存储。
     * 超时返回 false,由调用方决定是否转入可见的交互验证。
     */
    suspend fun clearChallenge(reason: String): Boolean = solveMutex.withLock {
        diagnostics.log("静默重验证开始: $reason")
        val finishedBefore = finishedCount
        openUrl("${Config.BASE_URL}/")
        val deadline = SystemClock.elapsedRealtime() + Config.CHALLENGE_TIMEOUT_MS
        var cleared = false
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_INTERVAL_MS)
            if (finishedCount > finishedBefore && isPageCleared()) {
                cleared = true
                break
            }
        }
        withContext(Dispatchers.Main) { store.syncFromWebView() }
        diagnostics.log(if (cleared) "静默重验证成功" else "静默重验证超时")
        cleared
    }

    /** 路径 B:在已通过挑战的页面内用 fetch() 请求,走真实 Chrome 网络栈。 */
    suspend fun fetchJson(path: String, csrf: String? = null, method: String = "GET", body: String? = null): TransportResult {
        if (!isPageCleared()) clearChallenge("fetch 前置检查")
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TransportResult>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            webView().evaluateJavascript(buildFetchScript(id, path, csrf, method, body), null)
        }
        val result = withTimeoutOrNull(Config.FETCH_TIMEOUT_MS) { deferred.await() }
        pending.remove(id)
        return result ?: TransportResult(0, "WebView fetch 超时", false)
    }

    suspend fun reset() = withContext(Dispatchers.Main) {
        val webView = webView()
        webView.loadUrl("about:blank")
        webView.clearCache(true)
        webView.clearHistory()
    }

    private fun buildFetchScript(id: String, path: String, csrf: String?, method: String, body: String?): String = """
        (function () {
          var id = ${JSONObject.quote(id)};
          fetch(${JSONObject.quote(path)}, {
            method: ${JSONObject.quote(method)},
            ${if (body == null) "" else "body: ${JSONObject.quote(body)},"}
            credentials: 'include',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'
              ${if (csrf == null) "" else ", 'Content-Type': 'application/json; charset=utf-8', 'X-CSRF-Token': ${JSONObject.quote(csrf)}"} }
          }).then(function (r) {
            return r.text().then(function (t) {
              $BRIDGE_NAME.onResult(id, r.status, r.headers.get('cf-mitigated') || '', t);
            });
          }).catch(function (e) {
            $BRIDGE_NAME.onResult(id, 0, '', String(e));
          });
        })();
    """.trimIndent()

    private suspend fun evalString(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            webView().evaluateJavascript(script) { raw -> continuation.resume(decodeJsValue(raw)) }
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
        const val POLL_INTERVAL_MS = 700L
        const val LAYOUT_TIMEOUT_MS = 1_500L
    }
}

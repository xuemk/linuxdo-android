package org.linuxdo.android.net

import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.linuxdo.android.Config
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.data.TopicRepository

/**
 * Discourse User API Key 网页授权认证服务。
 * 流程对齐官方 DiscourseHub 与 FluxDO:
 * 1. 本地生成 2048 位 RSA 密钥对、client_id、nonce。
 * 2. 拼接授权 URL (scopes=one_time_password, auth_redirect=discourse://auth_redirect) 拉起浏览器。
 * 3. 浏览器授权后回调 discourse://auth_redirect?payload=...&oneTimePassword=...。
 * 4. RSA 私钥解密 payload 校验 nonce，解密 oneTimePassword 拿到一次性令牌。
 * 5. 请求 CSRF 后 POST /session/otp/:token 兑换 _t 会话 Cookie，完成登录。
 * 6. 吊销该临时 User API Key (用完即焚)。
 */
class UserApiKeyAuthService(
    private val store: SessionStore,
    private var httpClient: OkHttpClient,
    private val diagnostics: Diagnostics,
    /**
     * CSRF 请求被 Cloudflare 拦下时的静默重验证入口。
     * 网页授权是"外部浏览器授权 → 回到 App 内原生网络栈兑换"两段式，
     * 浏览器里的 cf_clearance 不会共享给 App；静默验证后最多重试一次，
     * 仍被挑战则交给界面显示人工验证，验证后重新获取匹配会话的 CSRF。
     */
    private val silentRevalidate: suspend (String) -> Boolean = { false },
) {
    /** 动态更新底层的 OkHttpClient（DoH 节点切换或开关变更后调用） */
    fun updateClient(newClient: OkHttpClient) {
        this.httpClient = newClient
    }

    private var pendingPrivateKey: PrivateKey? = null
    private var pendingNonce: String? = null
    private var pendingGeneration: Long? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 生成 RSA 密钥对并构造网页授权 URL。
     */
    @Synchronized
    fun buildAuthorizeUrl(): String {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        pendingPrivateKey = keyPair.private
        val nonce = UUID.randomUUID().toString()
        pendingNonce = nonce
        pendingGeneration = store.sessionGeneration
        val clientId = UUID.randomUUID().toString()

        val publicKeyBytes = keyPair.public.encoded
        val base64Pub = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
        val pem = buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            base64Pub.chunked(64).forEach { append(it).append("\n") }
            append("-----END PUBLIC KEY-----")
        }

        diagnostics.log("生成 User API Key 授权参数: clientId=$clientId, nonce=$nonce")

        return Uri.parse("${Config.BASE_URL}/user-api-key/new").buildUpon()
            .appendQueryParameter("application_name", "LINUX DO")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scopes", "one_time_password")
            .appendQueryParameter("public_key", pem)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("auth_redirect", AUTH_REDIRECT)
            .build()
            .toString()
    }

    /**
     * 用户取消授权或重置时清理密钥状态。
     */
    @Synchronized
    fun cancel() {
        pendingPrivateKey = null
        pendingNonce = null
        pendingGeneration = null
    }

    /**
     * 处理深链回调 discourse://auth_redirect?payload=...&oneTimePassword=...
     */
    suspend fun handleAuthRedirect(uri: Uri) = withContext(Dispatchers.IO) {
        val (privateKey, expectedNonce, generation) = synchronized(this@UserApiKeyAuthService) {
            val key = pendingPrivateKey ?: throw IllegalStateException("未发起过网页授权或授权已过期，请重新尝试")
            val nonce = pendingNonce ?: throw IllegalStateException("授权 nonce 丢失，请重新尝试")
            val generation = pendingGeneration ?: throw IllegalStateException("网页授权已过期，请重新尝试")
            Triple(key, nonce, generation)
        }
        checkSession(generation)

        val payloadParam = uri.getQueryParameter("payload")
            ?: throw IllegalArgumentException("授权回调缺少 payload 参数")
        val otpParam = uri.getQueryParameter("oneTimePassword")
            ?: throw IllegalArgumentException("授权回调缺少 oneTimePassword 一次性口令")

        // 1. RSA 私钥解密 payload
        val decryptedPayload = decryptRsa(payloadParam, privateKey)
            ?: throw IllegalStateException("授权 payload 解密失败")

        val payloadObj = runCatching { json.parseToJsonElement(decryptedPayload).jsonObject }.getOrNull()
            ?: throw IllegalStateException("授权 payload 不是合法的 JSON 格式")

        val returnedNonce = payloadObj["nonce"]?.jsonPrimitive?.contentOrNull
        if (returnedNonce != expectedNonce) {
            diagnostics.log("授权回调 nonce 不匹配: 期待 $expectedNonce, 实际 $returnedNonce")
            throw IllegalStateException("授权回调验证失败 (nonce 不匹配)")
        }

        val apiKey = payloadObj["key"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("授权回调未包含 API Key")

        // 2. RSA 私钥解密 oneTimePassword
        val otp = decryptRsa(otpParam, privateKey)
            ?: throw IllegalStateException("一次性登录令牌解密失败")

        val cleanOtp = otp.trim()
        if (!cleanOtp.matches(Regex("^[0-9a-fA-F]+$"))) {
            throw IllegalStateException("登录口令格式异常: $cleanOtp")
        }

        diagnostics.log("User API Key 解密成功，正在兑换会话 Cookie")

        // 3. 兑换 OTP 为会话 Cookie (_t)
        redeemOtp(cleanOtp, generation)

        // 4. 用完即焚：吊销该 User API Key
        revokeKey(apiKey, generation)

        // 5. 成功后清除私钥和 nonce
        synchronized(this@UserApiKeyAuthService) {
            if (pendingNonce == expectedNonce) {
                pendingPrivateKey = null
                pendingNonce = null
                pendingGeneration = null
            }
        }
    }

    private fun decryptRsa(base64Cipher: String, privateKey: PrivateKey): String? = runCatching {
        val normalized = base64Cipher.replace("\\s".toRegex(), "")
        val cipherBytes = Base64.decode(normalized, Base64.DEFAULT)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decryptedBytes = cipher.doFinal(cipherBytes)
        String(decryptedBytes, Charsets.UTF_8)
    }.getOrNull()

    /**
     * 兑换 OTP：
     * 先取 CSRF 及匹配的 _forum_session，然后不跟随重定向 POST /session/otp/:token，拦截第一跳 302 的 Set-Cookie。
     */
    private suspend fun redeemOtp(otp: String, generation: Long) {
        val csrf = fetchCsrf(generation)
        val previousToken = withContext(Dispatchers.Main) {
            checkSession(generation)
            store.syncFromWebView()[Config.SESSION_COOKIE]
        }

        val otpClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            // OTP 只能消费一次，连接异常不能让 OkHttp 自动重放 POST。
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("${Config.BASE_URL}/session/otp/$otp")
            .tag(ActiveSessionRequest::class.java, ActiveSessionRequest(generation))
            .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("X-CSRF-Token", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val response = executeAuthRequest(otpClient, request, "/session/otp", generation)
        diagnostics.log("兑换 OTP 响应码: ${response.status}")
        if (response.challenged) throw NeedsInteractiveVerification("网页授权需要完成安全验证")
        if (response.status !in 200..302) {
            throw HttpStatusException(response.status, "登录令牌兑换失败，请重新授权")
        }

        // 校验是否拿到会话 Cookie
        val hasSession = withContext(Dispatchers.Main) {
            checkSession(generation)
            val currentToken = store.syncFromWebView()[Config.SESSION_COOKIE]
            !currentToken.isNullOrBlank() && currentToken != previousToken
        }
        if (!hasSession) {
            throw IllegalStateException("登录令牌兑换后未获取到新的会话凭证 (_t)，请重新授权")
        }
        diagnostics.log("会话凭证 (_t) 已成功持久化")
    }

    /**
     * 获取 CSRF Token 并同步保存关联会话 Cookie。
     *
     * 只对明确的 Cloudflare 挑战做一次静默验证；429、网络错误和无效响应分别上报。
     * 静默验证未通过时保留授权上下文，由界面完成前台验证后恢复。
     */
    private suspend fun fetchCsrf(generation: Long): String {
        try {
            return requestCsrf(generation)
        } catch (_: NeedsInteractiveVerification) {
            diagnostics.log("网页授权 CSRF 遇到挑战，尝试一次静默验证")
        }
        throwIfRateLimitCoolingDown()
        val revalidated = silentRevalidate("网页授权 CSRF 需要安全验证")
        checkSession(generation)
        throwIfRateLimitCoolingDown()
        if (!revalidated) {
            throw NeedsInteractiveVerification("网页授权需要完成安全验证", readProbePath = "/session/csrf")
        }
        diagnostics.log("CSRF 静默重验证已完成，重放请求")
        return requestCsrf(generation)
    }

    /**
     * 单次 CSRF 请求。
     *
     * 端点是 Discourse 标准的 /session/csrf（不带 .json）：带后缀的地址在无会话 Cookie 时
     * 更容易被 301/404 或挑战页顶掉，取不到 JSON 体。Headers 与网页端完全一致，
     * 让服务端按 AJAX 请求处理。
     */
    private suspend fun requestCsrf(generation: Long): String {
        // OkHttp 侧读的是加密存储里的 Cookie 快照，必须先与 WebView 现场对齐，
        // 否则刚在静默重验证里拿到的 cf_clearance 根本带不上。
        withContext(Dispatchers.Main) {
            checkSession(generation)
            store.syncFromWebView()
        }

        val request = Request.Builder()
            .url("${Config.BASE_URL}/session/csrf")
            .tag(ActiveSessionRequest::class.java, ActiveSessionRequest(generation))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cache-Control", "no-cache")
            .build()

        val response = executeAuthRequest(httpClient, request, "/session/csrf", generation)
        if (response.challenged) throw NeedsInteractiveVerification("网页授权 CSRF 需要安全验证", readProbePath = "/session/csrf")
        if (response.status !in 200..299) {
            throw HttpStatusException(response.status, "获取网页登录凭据失败，请稍后重试")
        }
        val csrf = runCatching {
            json.parseToJsonElement(response.body).jsonObject["csrf"]?.jsonPrimitive
                ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return csrf ?: throw IllegalStateException("站点未返回有效的 CSRF 凭据，请检查网络后重试")
    }

    private fun checkSession(generation: Long) {
        check(store.sessionGeneration == generation && !store.sessionChanging) { "会话已切换，请重新授权" }
    }

    private fun throwIfRateLimitCoolingDown() {
        val remaining = TopicRepository.rateLimitRemainingMs()
        if (remaining > 0L) throw RateLimitException(remaining)
    }

    /** 与普通请求共用读写调度；验证由调用方在释放并发名额后执行。 */
    private suspend fun executeAuthRequest(
        client: OkHttpClient,
        request: Request,
        label: String,
        generation: Long,
    ): TransportResult {
        val execute: suspend () -> TransportResult = {
            checkSession(generation)
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                checkSession(generation)
                                val body = response.body?.string().orEmpty()
                                val retryAfter = if (response.code == 429 || response.code == 503) {
                                    HttpHeaderUtils.extractRetryAfterSeconds(
                                        retryAfterHeader = response.header("Retry-After"),
                                        resetHeader = response.header("x-ratelimit-reset") ?: response.header("ratelimit-reset"),
                                        body = body,
                                    )
                                } else {
                                    HttpHeaderUtils.parseRetryAfter(response.header("Retry-After"))
                                }
                                val mitigated = response.header("cf-mitigated")
                                diagnostics.log("网页授权 $label: HTTP ${response.code}, cf-mitigated=${mitigated ?: "-"}")
                                if (response.code == 429) {
                                    val remaining = TopicRepository.enterRateLimitCooldown(
                                        label, retryAfter, store,
                                        cfChallenge = mitigated?.contains("challenge", ignoreCase = true) == true ||
                                            TopicRepository.isCfChallengeBody(body),
                                    )
                                    throw RateLimitException(remaining)
                                }
                                TransportResult(
                                    response.code, body,
                                    ChallengeDetector.isChallenge(response.code, mitigated, body), retryAfter,
                                )
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                })
            }
        }
        return try {
            if (request.method == "POST") {
                TopicRepository.postGate.withLock {
                    TopicRepository.throttlePost(burst = true)
                    try {
                        execute()
                    } finally {
                        TopicRepository.markPostCompleted()
                    }
                }
            } else {
                TopicRepository.getSemaphore.withPermit {
                    TopicRepository.acquireGetSlot { false }
                    execute()
                }
            }
        } catch (error: IOException) {
            diagnostics.log("网页授权 $label 连接失败: ${error.javaClass.simpleName}")
            throw IllegalStateException("连接站点失败，请检查网络后重试", error)
        }
    }

    /**
     * 吊销临时生成的 User API Key，避免无用凭证留存服务端。
     */
    private suspend fun revokeKey(apiKey: String, generation: Long) {
        try {
            val request = Request.Builder()
                .url("${Config.BASE_URL}/user-api-key/revoke")
                .tag(ActiveSessionRequest::class.java, ActiveSessionRequest(generation))
                .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Api-Key", apiKey)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = executeAuthRequest(httpClient, request, "/user-api-key/revoke", generation)
            diagnostics.log("吊销 User API Key 响应码: ${response.status}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            diagnostics.log("吊销 User API Key 异常 (可忽略): ${e.message}")
        }
    }

    companion object {
        const val AUTH_REDIRECT = "discourse://auth_redirect"
    }
}

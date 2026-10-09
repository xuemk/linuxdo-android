package org.linuxdo.android.net

import android.net.Uri
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.linuxdo.android.Config
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.SessionStore

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
    private val httpClient: OkHttpClient,
    private val diagnostics: Diagnostics,
) {
    private var pendingPrivateKey: PrivateKey? = null
    private var pendingNonce: String? = null

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
    }

    /**
     * 处理深链回调 discourse://auth_redirect?payload=...&oneTimePassword=...
     */
    suspend fun handleAuthRedirect(uri: Uri) = withContext(Dispatchers.IO) {
        val (privateKey, expectedNonce) = synchronized(this@UserApiKeyAuthService) {
            val key = pendingPrivateKey ?: throw IllegalStateException("未发起过网页授权或授权已过期，请重新尝试")
            val nonce = pendingNonce ?: throw IllegalStateException("授权 nonce 丢失，请重新尝试")
            key to nonce
        }

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
        redeemOtp(cleanOtp)

        // 4. 用完即焚：吊销该 User API Key
        revokeKey(apiKey)

        // 5. 成功后清除私钥和 nonce
        synchronized(this@UserApiKeyAuthService) {
            pendingPrivateKey = null
            pendingNonce = null
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
     * 先取 CSRF，然后不跟随重定向 POST /session/otp/:token，拦截第一跳 302 的 Set-Cookie。
     */
    private suspend fun redeemOtp(otp: String) {
        val csrf = fetchCsrf()
            ?: throw IllegalStateException("获取 CSRF Token 失败，无法完成兑换")

        val otpClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("${Config.BASE_URL}/session/otp/$otp")
            .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("X-CSRF-Token", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val response = otpClient.newCall(request).execute()
        response.use { resp ->
            diagnostics.log("兑换 OTP 响应码: ${resp.code}")
            val cookies = Cookie.parseAll(resp.request.url, resp.headers)
            if (cookies.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    store.applyResponseCookies(cookies)
                }
            }
        }

        // 校验是否拿到会话 Cookie
        val hasSession = withContext(Dispatchers.Main) {
            store.hasSessionCookie()
        }
        if (!hasSession) {
            throw IllegalStateException("登录令牌兑换成功但未获取到会话凭证 (_t)，请重试")
        }
        diagnostics.log("会话凭证 (_t) 已成功持久化")
    }

    /**
     * 获取 CSRF Token
     */
    private fun fetchCsrf(): String? {
        val request = Request.Builder()
            .url("${Config.BASE_URL}/session/csrf.json")
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                obj["csrf"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }

    /**
     * 吊销临时生成的 User API Key，避免无用凭证留存服务端。
     */
    private fun revokeKey(apiKey: String) {
        try {
            val request = Request.Builder()
                .url("${Config.BASE_URL}/user-api-key/revoke")
                .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Api-Key", apiKey)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            httpClient.newCall(request).execute().use { resp ->
                diagnostics.log("吊销 User API Key 响应码: ${resp.code}")
            }
        } catch (e: Exception) {
            diagnostics.log("吊销 User API Key 异常 (可忽略): ${e.message}")
        }
    }

    companion object {
        const val AUTH_REDIRECT = "discourse://auth_redirect"
    }
}

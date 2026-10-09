package org.linuxdo.android.net

enum class NetworkPath(val label: String) {
    OKHTTP("A: OkHttp"),
    WEBVIEW("B: WebView fetch"),
}

data class TransportResult(
    val status: Int,
    val body: String,
    val challenged: Boolean,
)

interface Transport {
    val name: String

    suspend fun get(path: String): TransportResult
    suspend fun write(path: String, method: String, body: String?, csrf: String): TransportResult

    /** 以 multipart/form-data 上传一个文件。 */
    suspend fun upload(
        path: String,
        csrf: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        fields: Map<String, String>,
    ): TransportResult

    /**
     * 静默重验证,成功返回 true。
     *
     * 写操作的重试不在 write() 里做,而是由上层驱动 —— 重验证会在 WebView 里重新加载首页,
     * Discourse 可能借机换掉 _forum_session,那样旧的 CSRF token 就失效了,
     * 必须连 token 一起重取才能重发。
     */
    suspend fun revalidate(reason: String): Boolean
}

/** 静默重验证失败,必须让用户在可见 WebView 中完成验证。 */
class NeedsInteractiveVerification(message: String) : Exception(message)

/**
 * 请求压根没到服务器(建连失败、DNS 失败、读超时)。
 * Transport 把这类失败统一表示成 status = 0,上层据此换成用户能看懂的提示。
 */
class NetworkTimeoutException : Exception("连接超时，请检查网络")

/**
 * 服务端明确拒绝了登录(token 失效、待审核、二次验证不通过等)。
 * 与 HttpStatusException 分开是因为 Discourse 这类拒绝走的是 HTTP 200 + `{"error":...}`,
 * 套上 "HTTP 200" 前缀反而会让提示变得费解。
 */
class LoginRejectedException(message: String) : Exception(message)

/**
 * 账号开启了两步验证（2FA：TOTP 动态口令或备用恢复码），需要用户输入验证码后再次提交。
 */
class SecondFactorRequiredException(
    val totpEnabled: Boolean = true,
    val backupEnabled: Boolean = false,
    message: String = "该账号已开启两步验证，请输入验证码",
) : Exception(message)

class HttpStatusException(val status: Int, detail: String = "") :
    Exception("HTTP $status $detail".trim())

object ChallengeDetector {
    private val challengeTitles = listOf(
        "just a moment",
        "attention required",
        "请稍候",
        "verify you are human",
        "checking your browser",
    )

    fun isChallenge(status: Int, mitigatedHeader: String?, body: String): Boolean {
        if (!mitigatedHeader.isNullOrBlank()) return true
        if (status != 403 && status != 503) return false
        val head = body.take(4_000)
        return head.contains("challenge-platform") ||
            head.contains("Just a moment", ignoreCase = true) ||
            head.contains("cf-chl", ignoreCase = true)
    }

    fun isChallengeTitle(title: String): Boolean {
        val lower = title.lowercase()
        return challengeTitles.any { lower.contains(it) }
    }
}

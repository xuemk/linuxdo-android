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
}

/** 静默重验证失败,必须让用户在可见 WebView 中完成验证。 */
class NeedsInteractiveVerification(message: String) : Exception(message)

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

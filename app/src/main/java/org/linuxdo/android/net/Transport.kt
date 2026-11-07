package org.linuxdo.android.net

import org.linuxdo.android.Config

enum class NetworkPath(val label: String) {
    OKHTTP("A: OkHttp"),
    WEBVIEW("B: WebView fetch"),
}

data class TransportResult(
    val status: Int,
    val body: String,
    val challenged: Boolean,
    val retryAfterSeconds: Long? = null,
    val sentClearanceFp: String = "-",
    val cfMitigated: String? = null,
)

interface Transport {
    val name: String

    suspend fun get(path: String, present: Boolean = true): TransportResult

    /** 单次无重验 GET 请求（供 P0-4 切栈兜底使用，前置检查未通过时直接失败不循环重验）。 */
    suspend fun getWithoutRevalidate(path: String, present: Boolean = true): TransportResult =
        get(path, present)

    /** 验证期间的只读复核，不自动求解挑战，也不将候选凭据立即判废。 */
    suspend fun getForVerification(path: String, present: Boolean = false): TransportResult =
        getWithoutRevalidate(path, present)

    /**
     * 发写请求。[contentType] 默认 JSON；少数 Discourse 端点(如 `/topics/timings`)
     * 只认 `application/x-www-form-urlencoded` 的嵌套参数写法,由调用方显式指定。
     * [present] 标记前台用户活跃（Discourse-Present: true），避免后台请求误更 last_seen / days_visited。
     */
    suspend fun write(
        path: String,
        method: String,
        body: String?,
        csrf: String,
        contentType: String = JSON_CONTENT_TYPE,
        present: Boolean = true,
    ): TransportResult

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
     * [failedClearanceFp] 本次被挑战拦截时请求实际携带的 cf_clearance 指纹，用于锁内去重合并。
     */
    suspend fun revalidate(reason: String, failedClearanceFp: String = "-", requestHost: String = Config.HOST): Boolean

    /**
     * 确保传输层环境就绪并处于有效会话状态。
     * 默认实现返回 true；WebView 传输层会检查是否在同源且无挑战的正常站内页。
     */
    suspend fun ensureReady(): Boolean = true

    /** 当前传输层是否已自然处于就绪同源状态（只读检查，绝不触发后台网络加载或等待） */
    suspend fun isReady(): Boolean = true

    /**
     * 获取当前传输层环境原生绑定的 CSRF Token（如 WebView 页面内 meta 标签）。
     * 默认返回 null，使用上层统一获取的 token。
     */
    suspend fun obtainCsrfToken(): String? = null

    companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
    }
}

/** 静默重验证失败,必须让用户在可见 WebView 中完成验证。 */
class NeedsInteractiveVerification(
    message: String,
    val failedClearanceFp: String = "-",
    val isFrom429: Boolean = false,
    val readProbePath: String? = null,
) : Exception(message)

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

class HttpStatusException(val status: Int, detail: String = "", val retryAfterSeconds: Long? = null) :
    Exception("HTTP $status $detail".trim())

/** 服务端已明确限流。调用方应等待冷却结束，不能把它当作需要过盾的验证失败。 */
class RateLimitException(val retryAfterMs: Long) :
    Exception("请求过于频繁，请在 ${((retryAfterMs + 999L) / 1_000L).coerceAtLeast(1L)} 秒后重试")

object ChallengeDetector {
    private val challengeTitles = listOf(
        "just a moment",
        "attention required",
        "请稍候",
        "verify you are human",
        "checking your browser",
    )

    fun isChallenge(status: Int, mitigatedHeader: String?, body: String): Boolean {
        // 429 表示当前会话或出口正在限流。即使带有 CF 头也应进入退让，
        // 不能立即拉起验证页再额外制造首页、CSRF 和通知请求。
        if (status == 429) return false
        // 1. 最权威标志: CF 挑战专用响应头
        if (mitigatedHeader?.contains("challenge", ignoreCase = true) == true) return true
        if (status != 403 && status != 503) return false
        // 排除普通的 BAD CSRF 响应 (Discourse 自身的业务 403，绝非 CF 盾)
        if (body.contains("BAD CSRF", ignoreCase = true)) return false
        val head = body.take(4_000)
        return head.contains("challenge-platform") ||
            head.contains("Just a moment", ignoreCase = true) ||
            head.contains("cf-chl", ignoreCase = true)
    }

    /**
     * 区分 Cloudflare WAF 速率规则触发的挑战型 429（cf-mitigated: challenge）与真限流
     * （CF Error 1015 / Discourse JSON wait_seconds）。
     * 挑战型 429 可通过静默刷新 cf_clearance 恢复，不应直接打入全局限流冷却。
     */
    fun isChallenge429(status: Int, mitigatedHeader: String?, body: String): Boolean {
        if (status != 429) return false
        if (mitigatedHeader?.contains("challenge", ignoreCase = true) != true) return false
        val head = body.take(4_000)
        if (head.contains("Error 1015", ignoreCase = true) ||
            head.contains("You are being rate limited", ignoreCase = true) ||
            head.contains("\"wait_seconds\"", ignoreCase = true) ||
            head.contains("error_type", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    fun isChallengeTitle(title: String): Boolean {
        val lower = title.lowercase()
        return challengeTitles.any { lower.contains(it) }
    }
}

class RiskControlException(
    val status: Int = 403,
    override val message: String = "触发安全风控 (403)",
) : RuntimeException(message)
object HttpHeaderUtils {
    /** 无有效服务端提示时使用本地默认值；先限制秒数，避免毫秒换算溢出。 */
    fun rateLimitCooldownMs(retryAfterSeconds: Long?, defaultMs: Long): Long =
        retryAfterSeconds?.takeIf { it > 0L }?.coerceIn(1L, 86_400L)?.times(1_000L) ?: defaultMs

    private val HTTP_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )

    private val WAIT_SECONDS_JSON_REGEX = Regex(""""wait_seconds"\s*:\s*(\d+)""")
    private val CHINESE_WAIT_REGEX = Regex("""请等待\s*(\d+)\s*(天|小时|分钟|秒)""")
    private val ENGLISH_WAIT_REGEX = Regex(
        """Please wait\s+(\d+)\s+(second|seconds|minute|minutes|hour|hours|day|days)""",
        RegexOption.IGNORE_CASE,
    )

    fun extractRetryAfterSeconds(
        retryAfterHeader: String? = null,
        resetHeader: String? = null,
        body: String? = null,
    ): Long? {
        parseRetryAfter(retryAfterHeader)?.takeIf { it > 0L }?.let { return it }
        resetHeader?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { reset ->
            val nowSeconds = System.currentTimeMillis() / 1_000L
            val delta = if (reset > 1_000_000_000L) reset - nowSeconds else reset
            if (delta > 0L) return delta
        }
        return parseRetryAfterFromBody(body)
    }

    fun parseRetryAfterFromBody(body: String?): Long? {
        val text = body?.takeIf { it.isNotBlank() }?.take(4_000) ?: return null
        WAIT_SECONDS_JSON_REGEX.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.takeIf { it > 0L }?.let { return it }
        CHINESE_WAIT_REGEX.find(text)?.let { match ->
            val value = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@let
            val unit = match.groupValues.getOrNull(2).orEmpty()
            return secondsFromUnit(value, unit).takeIf { it > 0L }
        }
        ENGLISH_WAIT_REGEX.find(text)?.let { match ->
            val value = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@let
            val unit = match.groupValues.getOrNull(2).orEmpty().lowercase()
            return secondsFromUnit(value, unit).takeIf { it > 0L }
        }
        return null
    }

    private fun secondsFromUnit(value: Long, unit: String): Long = when {
        unit.contains("天") || unit.startsWith("day") -> value * 86_400L
        unit.contains("小时") || unit.startsWith("hour") -> value * 3_600L
        unit.contains("分钟") || unit.startsWith("minute") -> value * 60L
        else -> value
    }

    fun parseRetryAfter(headerValue: String?): Long? {
        val trimmed = headerValue?.trim() ?: return null
        trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        for (pattern in HTTP_DATE_PATTERNS) {
            try {
                val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                }
                val date = format.parse(trimmed)
                if (date != null) {
                    val diffMs = date.time - System.currentTimeMillis()
                    val seconds = (diffMs + 999L) / 1000L
                    return seconds.coerceAtLeast(0L)
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

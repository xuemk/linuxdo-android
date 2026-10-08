package org.linuxdo.android

object Config {
    const val BASE_URL = "https://linux.do"
    const val HOST = "linux.do"

    /** 两次请求之间的最小间隔,避免触发站方风控。 */
    const val MIN_REQUEST_INTERVAL_MS = 1_000L
    const val UNREAD_POLL_INTERVAL_MS = 10_000L

    /** 隐藏 WebView 静默通过 Cloudflare 挑战的最长等待时间。 */
    const val CHALLENGE_TIMEOUT_MS = 30_000L

    /** WebView fetch() 的单次请求超时。 */
    const val FETCH_TIMEOUT_MS = 30_000L

    /** 验收③使用的自动刷新间隔。 */
    const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1_000L

    /** Discourse 登录后下发的认证 Cookie 名。 */
    const val SESSION_COOKIE = "_t"
}

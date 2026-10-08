package org.linuxdo.android

object Config {
    const val BASE_URL = "https://linux.do"
    const val HOST = "linux.do"

    /** 两次请求之间的最小间隔,避免触发站方风控。 */
    const val MIN_REQUEST_INTERVAL_MS = 1_000L

    /**
     * 成组请求内部的最小间隔。
     * 只用于条数封顶的短突发 —— 通知分类扫描最多 5 轮、账号资料固定 2 个请求 ——
     * 所以峰值请求数是有上限的,不会变成持续高频。常规请求仍走 MIN_REQUEST_INTERVAL_MS。
     */
    const val BURST_REQUEST_INTERVAL_MS = 300L

    /** 「我的」各分区的缓存有效期,到期才在前台重新拉取。 */
    const val PROFILE_CACHE_TTL_MS = 3 * 60 * 1_000L

    const val UNREAD_POLL_INTERVAL_MS = 10_000L

    /** TCP/TLS 建连超时。连不上服务器时要尽快报错,而不是让用户干等。 */
    const val CONNECT_TIMEOUT_MS = 6_000L

    /**
     * 登录链路的单次请求总超时(含 DNS)。
     * connectTimeout 只覆盖建连,DNS 解析卡住时不生效,所以登录另外加这层硬上限。
     */
    const val LOGIN_CALL_TIMEOUT_MS = 6_000L

    /** 隐藏 WebView 静默通过 Cloudflare 挑战的最长等待时间。 */
    const val CHALLENGE_TIMEOUT_MS = 30_000L

    /** WebView fetch() 的单次请求超时。 */
    const val FETCH_TIMEOUT_MS = 30_000L

    /** 验收③使用的自动刷新间隔。 */
    const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1_000L

    /** Discourse 登录后下发的认证 Cookie 名。 */
    const val SESSION_COOKIE = "_t"
}

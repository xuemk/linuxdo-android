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

    /** 应用版本检查与升级（公开 APK 分发仓库 xuemk/linuxdo-android 根目录 version.json）。 */
    val UPDATE_VERSION_JSON_URLS = listOf(
        "https://api.github.com/repos/xuemk/linuxdo-android/contents/version.json",
        "https://raw.githubusercontent.com/xuemk/linuxdo-android/master/version.json",
        "https://fastly.jsdelivr.net/gh/xuemk/linuxdo-android@master/version.json",
    )
    const val UPDATE_RELEASES_API_URL = "https://api.github.com/repos/xuemk/linuxdo-android/releases/latest"
    const val UPDATE_RELEASES_PAGE_URL = "https://github.com/xuemk/linuxdo-android/releases/latest"

    /** 远端 version.json 尚未推送到仓库时的本地兜底清单（与根目录 version.json 保持一致，便于本地预演测试）。 */
    const val FALLBACK_VERSION_MANIFEST_JSON = """
        {
          "versionCode": 9,
          "versionName": "1.1.1",
          "forceUpdate": false,
          "minVersionCode": 1,
          "title": "v1.1.1 版本更新",
          "changelog": [
            "能力有限-囧，暂未搞定CF+图形验证双重检查，佬友们触发风控，就先用网页授权过渡下吧。"
          ],
          "apkUrl": "https://github.com/xuemk/linuxdo-android/releases/download/v1.1.1/linuxdo-1.1.1-release.apk"
        }
    """
}

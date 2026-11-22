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

    /** 升级进度（Connect）缓存有效期（6小时），避免频繁爬取 connect.linux.do */
    const val TRUST_REPORT_CACHE_TTL_MS = 6 * 60 * 60 * 1_000L

    /** 等级积分（LDC Credit）缓存有效期（6小时），静默更新 */
    const val LDC_USER_INFO_CACHE_TTL_MS = 6 * 60 * 60 * 1_000L

    /** 社区分（CDK score）缓存有效期（6小时）。分数由 CDK 服务端按徽章定时结算，变化很慢。 */
    const val CDK_SCORE_CACHE_TTL_MS = 6 * 60 * 60 * 1_000L

    /** 升级进度与积分的手动刷新统计时间窗口（30分钟） */
    const val CONNECT_MANUAL_REFRESH_WINDOW_MS = 30 * 60 * 1_000L

    /** 30分钟内最大允许手动刷新次数（超出后限流提醒） */
    const val CONNECT_MANUAL_REFRESH_MAX_COUNT = 5

    /** 静默更新遭遇风控/错误时的熔断退避时长（30分钟内不再静默重试） */
    const val CONNECT_SILENT_REFRESH_BACKOFF_MS = 30 * 60 * 1_000L

    /**
     * 后台静默预热升级进度与积分的等待时长。冷启动时首次预热不早于进程启动后 2 分钟：
     * 首屏列表、头像、未读、CSRF 都挤在开头，此时再打 connect / credit 极易被 CF 质询。
     * 账号切换等后续触发至少再等这么久。
     */
    const val CONNECT_IDLE_WARMUP_DELAY_MS = 30_000L
    const val CONNECT_FIRST_WARMUP_AFTER_START_MS = 2 * 60 * 1_000L

    /** 本地还没有升级进度 / 积分缓存时，进程启动后多久内不允许手动拉取（交给后台首轮预热） */
    const val CONNECT_FIRST_FETCH_LOCK_MS = 10 * 60 * 1_000L

    const val UNREAD_POLL_INTERVAL_MS = 20_000L

    /** TCP/TLS 建连超时。连不上服务器时要尽快报错,而不是让用户干等。 */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /**
     * 登录链路的单次请求总超时(含 DNS)。
     * connectTimeout 只覆盖建连,DNS 解析卡住时不生效,所以登录另外加这层硬上限。
     */
    const val LOGIN_CALL_TIMEOUT_MS = 20_000L

    /** 隐藏 WebView 静默通过 Cloudflare 挑战的最长等待时间。 */
    const val CHALLENGE_TIMEOUT_MS = 30_000L

    /** WebView fetch() 的单次请求超时。 */
    const val FETCH_TIMEOUT_MS = 30_000L

    /** 验收③使用的自动刷新间隔。 */
    const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1_000L

    /**
     * 开启「同步阅读进度与统计」后,在话题里停留多久才算一次有效阅读并静默上报。
     */
    const val READ_REPORT_THRESHOLD_MS = 3_500L

    /** 在话题内驻留阅读期间，周期性上报阅读时长的间隔（Discourse time_read 要求两次上报间隔 < 100s 才能累计）。 */
    const val READ_REPORT_HEARTBEAT_MS = 35_000L

    /** 离开话题时,停留时长比上次上报多出这么久才值得补报一次。 */
    const val READ_REPORT_TOP_UP_MS = 10_000L

    /** Discourse 登录后下发的认证 Cookie 名。 */
    const val SESSION_COOKIE = "_t"
    const val CF_CLEARANCE_COOKIE = "cf_clearance"

    /** 站点静态资源与图片 CDN 域名（默认对齐线上 CDN，首页 #data-discourse-setup 动态提取后覆盖并持久化）。 */
    const val DEFAULT_CDN_URL = "https://cdn.ldstatic.com"
    const val DEFAULT_S3_CDN_URL = "https://cdn3.ldstatic.com"
    @Volatile var cdnUrl: String? = DEFAULT_CDN_URL
    @Volatile var s3CdnUrl: String? = DEFAULT_S3_CDN_URL
    @Volatile var s3BaseUrl: String? = null

    fun updateCdnConfig(cdn: String?, s3Cdn: String?, s3Base: String?) {
        cdn?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let { cdnUrl = it }
        s3Cdn?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let { s3CdnUrl = it }
        s3Base?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { s3BaseUrl = it }
    }

    fun isStaticCdnPath(path: String): Boolean {
        val p = if (path.startsWith("/")) path else "/$path"
        if (p.startsWith("/uploads/short-url/")) return false
        return p.startsWith("/user_avatar/") ||
            p.startsWith("/letter_avatar") ||
            p.startsWith("/images/") ||
            p.startsWith("/svg-sprite/") ||
            p.startsWith("/uploads/default/") ||
            p.startsWith("/stylesheets/")
    }

    /**
     * 对齐 Fluxdo UrlHelper.resolveUrlWithCdn：将相对静态资源、头像、表情与 S3 上传路径
     * 自动改写为站点配置的 CDN / S3 CDN 地址，避免静默消耗主站源站限流预算。
     */
    fun resolveUrlWithCdn(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        val s3Base = s3BaseUrl
        val s3Cdn = s3CdnUrl
        if (!s3Base.isNullOrBlank() && !s3Cdn.isNullOrBlank()) {
            val fullS3Base = when {
                s3Base.startsWith("//") -> "https:$s3Base"
                s3Base.startsWith("http", ignoreCase = true) -> s3Base
                else -> "https://$s3Base"
            }
            if (normalized.startsWith(fullS3Base, ignoreCase = true)) {
                return s3Cdn + normalized.substring(fullS3Base.length)
            }
        }
        val activeCdn = cdnUrl
        if (normalized.startsWith("/")) {
            val base = if (!activeCdn.isNullOrBlank() && isStaticCdnPath(normalized)) activeCdn else BASE_URL
            return base + normalized
        }
        if (!activeCdn.isNullOrBlank() && normalized.startsWith(BASE_URL, ignoreCase = true)) {
            val rel = normalized.substring(BASE_URL.length)
            if (rel.startsWith("/") && isStaticCdnPath(rel)) {
                return activeCdn + rel
            }
        }
        return normalized
    }

    /** 应用版本检查与升级（公开 APK 分发仓库 xuemk/linuxdo-android 根目录 version.json）。 */
    val UPDATE_VERSION_JSON_URLS = listOf(
        "https://api.github.com/repos/xuemk/linuxdo-android/contents/version.json",
        "https://raw.githubusercontent.com/xuemk/linuxdo-android/master/version.json",
        "https://fastly.jsdelivr.net/gh/xuemk/linuxdo-android@master/version.json",
    )
    const val UPDATE_RELEASES_API_URL = "https://api.github.com/repos/xuemk/linuxdo-android/releases/latest"
    const val UPDATE_RELEASES_PAGE_URL = "https://github.com/xuemk/linuxdo-android/releases/latest"

    /**
     * 远端 version.json 尚未推送到仓库时的本地兜底清单（与根目录 version.json 保持一致，便于本地预演测试）。
     *
     * `apkSha256` 为可选字段：填了就在应用内下载完成后校验，不匹配拒绝安装；
     * 留空或省略则跳过校验，不阻塞发版流程。
     */
    const val FALLBACK_VERSION_MANIFEST_JSON = """
        {
          "versionCode": 19,
          "versionName": "1.6.1",
          "forceUpdate": false,
          "minVersionCode": 1,
          "title": "LINUX DO Android v1.6.1 正式版更新说明",
          "changelog": [
            "1、专属邮箱原生收发：内置 LINUX DO 邮箱收件箱、读信与写信，支持 H5 网页邮箱一键自动登录",
            "2、附件高速下载与灵动进度条：三通道智能防截断（直连/系统通道/内置浏览器），支持顶部悬浮进度条与离开话题后台持续下载",
            "3、阅读与交互体验升级：支持全局字体缩放（80%~120%）、右侧悬浮楼层导航条、用户签名小尾巴与超链接大图/商店直跳",
            "4、后台通知与角标进化：前台高敏轮询 + 息屏阶梯闹钟低功耗守护，深度适配主流国产系统桌面未读角标",
            "5、网络与风控稳定性加固：彻底根治新安装与日常 429 速率挑战循环，优化 Anycast 冷启动选优、VPN 智能让渡与会话保护"
          ],
          "apkUrl": "https://github.com/xuemk/linuxdo-android/releases/download/v1.6.1/linuxdo-1.6.1-release.apk",
          "apkSha256": "a776f371cc95175a32cd2906c8684f6045c9175218a691a733010dc8bf7a1f8c"
        }
    """
}

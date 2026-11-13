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

    const val UNREAD_POLL_INTERVAL_MS = 10_000L

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
          "versionCode": 18,
          "versionName": "1.6.0",
          "forceUpdate": false,
          "minVersionCode": 1,
          "title": "LINUX DO Android v1.6.0 正式版更新说明",
          "changelog": [
            "1、微信式下拉应用广场：自由拖拽排序，完整小程序容器与直连体验",
            "2、话题暂存工作台：感兴趣的话题随时存放，长帖分段流畅看，想看一键直达",
            "3、所见即所得发起投票：发帖左下角投票构建器，卡片内即时编辑回显",
            "4、一键卡片分享：长按预览，双Tab精致卡片一键转发佬友",
            "5、多媒体与排版升级：音视频帖内内联播放、@/#智能补全与折叠表格渲染",
            "6、网络稳定性与体验加固：429速率挑战智能退避换线、阅读同步与会话保护"
          ],
          "apkUrl": "https://github.com/xuemk/linuxdo-android/releases/download/v1.6.0/linuxdo-1.6.0-release.apk",
          "apkSha256": "f220a65278cee2790bc010d8a5251aade15ed0bc76c68eb4c88f0099466e25f8"
        }
    """
}

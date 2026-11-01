package org.linuxdo.android.net

/**
 * 单个 DoH 服务器的描述符。
 *
 * @param url        RFC 8484 标准 DoH 端点，必须以 https:// 开头
 * @param name       在 UI 中展示的名称
 * @param bootstrapIps DoH 服务器自身域名的预置 IP，用于在 DNS 不可用时直接建立 TLS 连接，
 *                   彻底避免「先有鸡还是先有蛋」的启动死锁。
 * @param isCustom   true 表示用户手动添加的自定义节点，false 表示内置预设节点
 */
data class DohServer(
    val url: String,
    val name: String,
    val bootstrapIps: List<String> = emptyList(),
    val isCustom: Boolean = false,
) {
    /**
     * 唯一标识，以 URL 为主键（不含尾部斜杠）。
     * 持久化存储与故障惩罚 Map 均以此为 key。
     */
    val id: String get() = url.trimEnd('/')

    override fun equals(other: Any?): Boolean = other is DohServer && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * 内置预置节点池。
 *
 * 设计原则：
 * 1. L 站专属优选优先（一摩尔氚节点，内置 CF 国内三网优选 IP，延迟最低）
 * 2. 国内公共 DNS 备用（阿里 / 腾讯，不需要翻墙）
 * 3. Cloudflare 兜底（国际标准，在网络环境较好时可用）
 *
 * 顺序即默认选择优先级。
 */
object DohPresets {
    /** 始皇DoH：官方专享优选节点 (oaifree) */
    val SHIHUANG_DOH = DohServer(
        url = "https://neo.ddd.oaifree.com/query-dns",
        name = "始皇DoH（官方优选）",
        // 默认不写死引导 IP，走动态系统解析，避免 CDN IP 漂移后失效
        bootstrapIps = emptyList(),
    )

    /** L 站专属社区优选 DoH（一摩尔氚社区节点） */
    val LINUXDO_EDGE = DohServer(
        url = "https://edge.1molchuan.top/linuxdo",
        name = "L站专属（一摩尔氚优选）",
        // 默认不写死引导 IP，走动态系统解析，避免边缘反代 IP 漂移后失效
        bootstrapIps = emptyList(),
    )

    /** 全部内置预置节点，按优先级排列 */
    val ALL: List<DohServer> = listOf(SHIHUANG_DOH, LINUXDO_EDGE)

    /** 默认启用的节点（首选） */
    val DEFAULT: DohServer = SHIHUANG_DOH
}

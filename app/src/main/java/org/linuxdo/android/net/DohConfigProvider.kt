package org.linuxdo.android.net

/**
 * DoH 配置能力接口，由宿主 App 的存储层实现，供 Tunnel 模块解耦读取。
 */
interface DohConfigProvider {
    /** 是否开启 DoH 直连 */
    fun isDohEnabled(): Boolean

    /** 是否开启自动故障切换 */
    fun isDohAutoFailoverEnabled(): Boolean

    /** 当前选中的 DoH 服务器 URL */
    fun getDohServerUrl(): String?

    /** 全部 DoH 节点的 JSON 序列化字符串 */
    fun getDohServersJson(): String?

    /** 用户自定义节点的 JSON 序列化字符串 */
    fun getDohCustomServersJson(): String?
}

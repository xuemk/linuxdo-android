package org.linuxdo.android.net

/**
 * Tunnel 模块内部默认配置与常量。
 * 保持与上层应用层解耦，可通过各组件的构造函数或方法参数覆盖默认目标域名。
 */
object TunnelConfig {
    val DEFAULT_TARGET_HOST: String = runCatching {
        NativeTunnelCore.getBorrowHosts().firstOrNull() ?: ""
    }.getOrElse { "" }
}

package org.linuxdo.android.net.anycast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dns
import org.linuxdo.android.net.TunnelConfig
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「直连优先」模式下的 DNS 适配层：对目标域名直接给出内置 Anycast IP，彻底跳过 A 记录解析。
 *
 * 只接管目标域名，其余一律委托 [fallback]：ProxyController 之外的第三方域名（hCaptcha、CDN）
 * 没有内置 IP 可用，硬接管只会让它们失联。
 *
 * 返回**整个有序池**而非单个最优节点 —— OkHttp 会按顺序逐个尝试，相当于免费获得一层连接级
 * 故障转移；只给一个 IP 的话，该节点瞬时抖动就会让请求直接失败。
 */
class AnycastDns(
    private val pool: AnycastPool,
    private val fallback: Dns,
    private val targetHost: String = TunnelConfig.DEFAULT_TARGET_HOST,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Dns {

    private val refreshing = AtomicBoolean(false)

    override fun lookup(hostname: String): List<InetAddress> {
        if (!isTargetHost(hostname)) return fallback.lookup(hostname)

        // lookup 在 OkHttp 的 IO 线程上同步调用，绝不能在此阻塞等探测结果：
        // 首轮未完成时 ordered() 会按种子优先级给出可用节点，探测结果供后续请求受益。
        scheduleRefreshIfIdle()

        val candidates = pool.ordered().mapNotNull { ip -> toInetAddress(hostname, ip) }
        // 全池耗尽时退回 fallback，而不是抛异常 —— 让上层编排器有机会降级（见 design §2.5）
        return candidates.ifEmpty { fallback.lookup(hostname) }
    }

    private fun scheduleRefreshIfIdle() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                pool.refresh()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** 必须用 [InetAddress.getByAddress] 绑定原始 hostname，否则 TLS 的 SNI 会变成 IP 字面量。 */
    private fun toInetAddress(hostname: String, ip: String): InetAddress? = runCatching {
        InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
    }.getOrNull()

    private fun isTargetHost(hostname: String): Boolean {
        val host = hostname.trimEnd('.').lowercase()
        return host == targetHost || host.endsWith(".$targetHost")
    }
}

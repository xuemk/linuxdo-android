package org.linuxdo.android.net.anycast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import org.linuxdo.android.net.TunnelConfig
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「直连优先」模式下的 DNS 适配层：对目标域名直接给出内置 Anycast IP，彻底跳过 A 记录解析。
 *
 * 主站与静态资源域 ldstatic.com 共用直连池，避免 DoH 不可用时正文正常、图片却解析失败。
 * 其余第三方域名（hCaptcha、其他 CDN）仍委托 [fallback]，不套用主站的 IP。
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
    @Volatile
    private var lastRefreshAtNanos: Long = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        if (!isTargetHost(hostname)) return fallback.lookup(hostname)

        // 冷启动若池内尚无已测速健康节点，发起后台探测并短暂等待首个健康节点就绪（最多 1.5s），
        // 避免首个业务请求盲选未探测的 DEFAULT_SEEDS[0] 卡死 12s 超时。
        scheduleRefreshIfIdle()
        if (!pool.hasHealthyProbedNode()) {
            runBlocking {
                withTimeoutOrNull(1_500L) {
                    while (!pool.hasHealthyProbedNode() && refreshing.get()) {
                        delay(50L)
                    }
                }
            }
        }

        val candidates = pool.ordered().mapNotNull { ip -> toInetAddress(hostname, ip) }
        // 全池耗尽时退回 fallback，而不是抛异常 —— 让上层编排器有机会降级（见 design §2.5）
        return candidates.ifEmpty { fallback.lookup(hostname) }
    }

    fun scheduleRefreshIfIdle() {
        val nowNanos = System.nanoTime()
        val refreshedRecently = lastRefreshAtNanos > 0L && nowNanos - lastRefreshAtNanos < MIN_REFRESH_INTERVAL_NANOS
        if (pool.hasHealthyProbedNode() && refreshedRecently) return
        if (!refreshing.compareAndSet(false, true)) return
        lastRefreshAtNanos = nowNanos
        scope.launch {
            try {
                pool.refresh()
                if (!pool.hasHealthyProbedNode()) {
                    lastRefreshAtNanos = 0L
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    private companion object {
        const val MIN_REFRESH_INTERVAL_NANOS = 5L * 60L * 1_000_000_000L
    }

    /** 必须用 [InetAddress.getByAddress] 绑定原始 hostname，否则 TLS 的 SNI 会变成 IP 字面量。 */
    private fun toInetAddress(hostname: String, ip: String): InetAddress? = runCatching {
        InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
    }.getOrNull()

    private fun isTargetHost(hostname: String): Boolean {
        val host = hostname.trimEnd('.').lowercase()
        return host == targetHost || host.endsWith(".$targetHost") ||
            host == "ldstatic.com" || host.endsWith(".ldstatic.com")
    }
}

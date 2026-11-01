package org.linuxdo.android.net.anycast

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.linuxdo.android.net.NativeTunnelCore

/**
 * 单个节点的探测结果。[elapsedMs] 为 TCP connect 与 TLS 握手的总耗时，仅在 [ok] 为 true 时有意义。
 */
data class ProbeResult(
    val ip: String,
    val ok: Boolean,
    val elapsedMs: Long,
    val failure: String? = null,
)

/**
 * Anycast 节点健康探测。
 *
 * **判据必须是完整的 TLS 握手，绝不能用 TCP 可达性。** 实测四个 CF Anycast IP（含此前被误判为
 * 「物理不可达」的两个）的 TCP 443 全部 OPEN，而携带目标域名的 TLS 握手全部在 0.2~0.4s 内被
 * 主动 RST —— 若以 TCP Ping 为判据，整池会恒被判为健康，淘汰机制形同虚设。
 */
object AnycastProbe {

    /** Cloudflare 全网统一的 ECH `public_name`，由 libtunnel_core.so 解密提供 */
    val PUBLIC_NAME: String = runCatching { NativeTunnelCore.getProbeSni() }.getOrElse { "" }

    const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000

    fun probe(
        ip: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): ProbeResult {
        val startedAt = System.nanoTime()
        var raw: Socket? = null
        return try {
            raw = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(ip, HTTPS_PORT), connectTimeoutMs)
                soTimeout = handshakeTimeoutMs
            }
            handshake(raw)
            ProbeResult(ip = ip, ok = true, elapsedMs = elapsedMsSince(startedAt))
        } catch (error: Exception) {
            ProbeResult(
                ip = ip,
                ok = false,
                elapsedMs = elapsedMsSince(startedAt),
                failure = "${error.javaClass.simpleName}:${error.message ?: ""}",
            )
        } finally {
            runCatching { raw?.close() }
        }
    }

    private fun handshake(raw: Socket) {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(raw, PUBLIC_NAME, HTTPS_PORT, false) as SSLSocket).use { ssl ->
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(PUBLIC_NAME))
            }
            ssl.startHandshake()
        }
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    private const val HTTPS_PORT = 443
}

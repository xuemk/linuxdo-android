package org.linuxdo.android.net.proxy

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.linuxdo.android.net.NativeTunnelCore
import org.linuxdo.android.net.TunnelConfig
import org.linuxdo.android.net.anycast.AnycastPool
import java.io.Closeable

/** 单笔 CONNECT 代理请求的诊断记录 */
data class LocalProxyTrace(
    val host: String,
    val port: Int,
    val upstreamIp: String,
    val recordSplit: Boolean,
    val error: String?,
    val elapsedMs: Long,
) {
    override fun toString(): String = buildString {
        append(host).append(':').append(port)
        append(" -> ").append(upstreamIp)
        append(if (recordSplit) " [RECORD-SPLIT]" else " [DIRECT]")
        append(" ").append(elapsedMs).append("ms")
        error?.let { append(" ERR=").append(it) }
    }
}

/**
 * 本地 HTTP CONNECT 代理服务器与 TLS Record 级分片服务（零 DoH 全端直连方案 路径 B）。
 *
 * 核心机制（RFC 8446 §5.1 / design §3.2）：
 * 1. 监听 127.0.0.1 动态端口，提供本地 HTTP CONNECT 隧道；
 * 2. 对目标域名（*.linux.do）：跳过系统 DNS，直连 [anycastPool] 优选出的 Anycast IP；
 * 3. 对 WebView 发出的首个 TLS ClientHello：在 SNI 域名中段切分，重构为两个各自具备完整 5 字节
 *    合法 Record Header 的 TLS Record 分开发送，从根源规避骨干网 DPI 的 TCP 流重组（Stream Reassembly）；
 * 4. 全程下沉至 libtunnel_core.so（C++ 原生层）执行，零明文 SNI 泄露，纳秒级物理延迟控制，高抗逆向防护。
 */
class LocalProxyServer(
    private val targetSuffix: String = TunnelConfig.DEFAULT_TARGET_HOST,
    private val anycastPool: AnycastPool,
) : Closeable {

    private val nativePort: Int = runCatching {
        NativeTunnelCore.startProxy(targetSuffix)
    }.getOrElse { -1 }

    /** 内核动态分配的本地监听端口（由 libtunnel_core.so 提供） */
    val port: Int get() = if (nativePort > 0) nativePort else NativeTunnelCore.getProxyPort()

    fun traces(): List<LocalProxyTrace> = emptyList()

    init {
        anycastPool.winner()?.let { winnerIp ->
            runCatching { NativeTunnelCore.setProxyUpstreamIp(winnerIp) }
        }
        Log.i(TAG, "LocalProxyServer (Native Engine) running on 127.0.0.1:$port (target: $targetSuffix)")
    }

    override fun close() {
        runCatching { NativeTunnelCore.stopProxy() }
        Log.i(TAG, "LocalProxyServer stopped")
    }

    companion object {
        private const val TAG = "LocalProxyServer"

        /** Cloudflare Turnstile 挑战平台后缀，仅允许其根域及实际验证使用的子域。 */
        const val CHALLENGES_HOST = "challenges.cloudflare.com"

        /**
         * 为系统原生 WebView 挂载本地代理反向绕过（Task 3.1）。
         *
         * 启用 [ProxyConfig.Builder.setReverseBypassEnabled]：
         * 仅目标域名（*.linux.do 以及 challenges.cloudflare.com 后缀）导流进入本地代理，hCaptcha / CDN 等第三方域名维持系统直连。
         */
        fun applyWebViewProxyOverride(
            localPort: Int,
            host: String = TunnelConfig.DEFAULT_TARGET_HOST,
            includeChallenges: Boolean = true,
            onApplied: (() -> Unit)? = null,
        ) {
            if (localPort <= 0 || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                webViewProxyActive = false
                Log.w(TAG, "WebViewFeature.PROXY_OVERRIDE is not supported on this device")
                return
            }
            webViewProxyActive = false

            val builder = ProxyConfig.Builder().addProxyRule("127.0.0.1:$localPort")
            val reverseBypass = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)
            if (reverseBypass) {
                builder.addBypassRule(host)
                    .addBypassRule("*.$host")
                if (includeChallenges) {
                    builder.addBypassRule(CHALLENGES_HOST)
                        .addBypassRule("*.$CHALLENGES_HOST")
                }
                builder.setReverseBypassEnabled(true)
            } else {
                builder.addBypassRule("*.hcaptcha.com")
                    .addBypassRule("hcaptcha.com")
                if (!includeChallenges) {
                    builder.addBypassRule(CHALLENGES_HOST)
                        .addBypassRule("*.$CHALLENGES_HOST")
                }
            }
            // 反向绕过时只有目标域会进代理，native 层据此拒绝其他目的地，防止被同机其他 App 借道
            runCatching { NativeTunnelCore.setAllowNonTarget(!reverseBypass) }

            // 注意：绝不调用 removeImplicitRules()，否则会导致 127.0.0.1 回环送入代理死锁
            ProxyController.getInstance().setProxyOverride(
                builder.build(),
                { it.run() },
                {
                    webViewProxyActive = true
                    Log.i(TAG, "WebView ProxyController active: 127.0.0.1:$localPort (target: $host, challenges: $includeChallenges)")
                    onApplied?.invoke()
                },
            )
        }

        /** 取消 WebView 代理挂载（让渡系统网络 / VPN） */
        fun clearWebViewProxyOverride(onCleared: (() -> Unit)? = null) {
            webViewProxyActive = false
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
            ProxyController.getInstance().clearProxyOverride({ it.run() }) {
                Log.i(TAG, "WebView ProxyController cleared")
                onCleared?.invoke()
            }
        }

        /** 已由 ProxyController 确认生效的 WebView CONNECT 通道。 */
        fun isWebViewProxyActive(): Boolean = webViewProxyActive

        @Volatile private var webViewProxyActive: Boolean = false
    }
}

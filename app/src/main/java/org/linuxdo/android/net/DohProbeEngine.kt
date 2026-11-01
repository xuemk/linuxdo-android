package org.linuxdo.android.net

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject

/**
 * 端到端探测结果明细
 */
data class DohProbeResult(
    val latencyMs: Long?,
    val echAccepted: Boolean,
    val summary: String,
)

/**
 * DoH 节点可用性与端到端 ECH 传输探测引擎。
 *
 * 对齐 LinuxDo 原生网络直连核心标准：
 * 1. 采用原生 Rust (Rustls + ECH) 建立真实 TLS 传输，验证 Inner SNI 加密与 ECH 协商。
 * 2. 真实请求 `https://linux.do/latest.json`，判定网站是否真正可直连访问。
 * 3. 区分各类关键状态：
 *    - 站点访问成功 (HTTP 200 + topic_list)
 *    - 站点要求网页验证 (CF 403 / Just a moment)
 *    - ECH 未协商 / 被拒 / 缺少 ECH 配置
 *    - TLS 握手失败 / DNS 超时
 */
object DohProbeEngine {

    private const val TAG = "DohProbe"
    private const val PROBE_TIMEOUT_SECONDS = 10L

    /**
     * 测试单个 DoH 节点的端到端延迟（兼容既有接口）。
     * 返回毫秒级直连延迟；失败/超时返回 null。
     */
    fun testLatency(server: DohServer, testHost: String = "linux.do"): Long? {
        return probeDetailed(server, testHost).latencyMs
    }

    /**
     * 对单节点发起端到端真实校验，返回详细诊断结果与延迟。
     */
    fun probeDetailed(server: DohServer, testHost: String = "linux.do"): DohProbeResult {
        // 优先使用 Rust 原生 ECH 传输层进行端到端探测
        return try {
            probeWithNativeEch(server)
        } catch (e: LinkageError) {
            Log.w(TAG, "DoH 原生库不可用，降级为常规 DNS 探测: ${e.message}")
            probeFallbackDns(server, testHost)
        } catch (e: Exception) {
            Log.w(TAG, "DoH ${server.name} 探测失败 [${e.javaClass.simpleName}]: ${e.message}")
            val msg = if (e is IOException) dohErrorMessage(e) else (e.message ?: "连接失败")
            DohProbeResult(latencyMs = null, echAccepted = false, summary = msg)
        }
    }

    /**
     * 对指定传输发起真实 TLS+ECH 握手探测（轻量 HEAD /robots.txt）。
     * 完全不消耗业务流量与 API 限额，仅验证 TCP 连接、TLS 1.3 握手与 ECH 协商。
     *
     * @param server DoH 节点配置（用于初始化 Transport）
     * @param overrideDns 若传入（如 AnycastDns），则走内置 IP 池直连；若为 null，则走该 server 的 DoH DNS 解析
     */
    fun probeTlsHandshake(
        server: DohServer,
        overrideDns: okhttp3.Dns? = null,
        timeoutSeconds: Long = 6L,
    ): DohProbeResult {
        var accepted = false
        val probeTransport = runCatching {
            DohTransport(server) { host, ech ->
                if (host == "linux.do") accepted = ech
            }
        }.getOrNull() ?: return DohProbeResult(null, false, "原生传输组件不可用")

        val startMs = System.currentTimeMillis()
        return try {
            val builder = OkHttpClient.Builder()
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds + 1, TimeUnit.SECONDS)

            probeTransport.configure(builder)
            if (overrideDns != null) {
                builder.dns(overrideDns)
            }

            val probeClient = builder.build()
            val request = Request.Builder()
                .url("https://linux.do/robots.txt")
                .head()
                .header("User-Agent", "xmk2025-DoH-Check/1")
                .build()

            probeClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startMs
                val isUsable = accepted && (response.isSuccessful || response.code in 300..499 && !isCloudflareChallenge(response, ""))
                val summary = if (accepted) {
                    if (isUsable) "TLS与ECH握手成功" else "HTTP ${response.code} (可能触发阻断)"
                } else {
                    "TLS成功但ECH未协商"
                }
                DohProbeResult(
                    latencyMs = if (isUsable) latency else null,
                    echAccepted = accepted,
                    summary = summary,
                )
            }
        } catch (e: Exception) {
            val msg = if (e is IOException) dohErrorMessage(e) else (e.message ?: "连接失败")
            DohProbeResult(latencyMs = null, echAccepted = accepted, summary = msg)
        } finally {
            runCatching { probeTransport.close() }
        }
    }

    /**
     * 使用 Rust 原生 DohTransport 发起端到端请求 https://linux.do/latest.json
     */
    private fun probeWithNativeEch(server: DohServer): DohProbeResult {
        var accepted = false
        val probeTransport = DohTransport(server) { host, ech ->
            if (host == "linux.do") accepted = ech
        }
        val startMs = System.currentTimeMillis()
        try {
            val probeClient = probeTransport.configure(
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .retryOnConnectionFailure(false)
                    .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(PROBE_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
            ).build()

            val request = Request.Builder()
                .url("https://linux.do/latest.json")
                .header("User-Agent", "xmk2025-DoH-Check/1")
                .build()

            probeClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startMs
                val body = response.body?.string().orEmpty()
                val summary = dohCheckResult(response, body, accepted)
                val isUsable = accepted && (response.isSuccessful || isCloudflareChallenge(response, body))
                Log.d(TAG, "DoH ${server.name} 端到端探测: ${latency}ms, ech=$accepted, summary=$summary")
                return DohProbeResult(
                    latencyMs = if (isUsable) latency else null,
                    echAccepted = accepted,
                    summary = summary
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "DoH ${server.name} 原生端到端异常: ${e.message}")
            return DohProbeResult(
                latencyMs = null,
                echAccepted = accepted,
                summary = dohErrorMessage(e)
            )
        } finally {
            runCatching { probeTransport.close() }
        }
    }

    /**
     * 降级方案：仅做纯 DNS 解析探测
     */
    private fun probeFallbackDns(server: DohServer, testHost: String): DohProbeResult {
        val startMs = System.currentTimeMillis()
        val bootstrapClient = buildBootstrapClient(server)
        val dohBuilder = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(server.url.toHttpUrl())
            .post(true)

        val parsedIps = server.bootstrapIps.mapNotNull { safeParseIp(it) }
        if (parsedIps.isNotEmpty()) {
            dohBuilder.bootstrapDnsHosts(parsedIps)
        }

        val doh = dohBuilder.build()
        val addresses = doh.lookup(testHost)
        val latency = System.currentTimeMillis() - startMs
        return if (addresses.isNotEmpty()) {
            DohProbeResult(latencyMs = latency, echAccepted = false, summary = "DNS 成功 (未启用 ECH 传输)")
        } else {
            DohProbeResult(latencyMs = null, echAccepted = false, summary = "解析返回空地址")
        }
    }

    private fun dohCheckResult(response: Response, body: String, accepted: Boolean): String = when {
        !accepted -> "DNS 成功，ECH 未协商"
        isCloudflareChallenge(response, body) -> "DNS 成功 · ECH 已接受 · 站点要求网页验证"
        !response.isSuccessful -> "DNS 成功 · ECH 已接受 · HTTP ${response.code}"
        runCatching { JSONObject(body).has("topic_list") }.getOrDefault(false) -> "DNS 成功 · ECH 已接受 · 站点访问成功"
        else -> "DNS 成功 · ECH 已接受 · 站点未返回话题数据"
    }

    private fun isCloudflareChallenge(response: Response, body: String): Boolean {
        if (response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) return true
        if (response.code in listOf(403, 503)) {
            if (body.contains("Just a moment", ignoreCase = true) ||
                body.contains("_cf_chl_opt", ignoreCase = true) ||
                body.contains("/cdn-cgi/challenge-platform/", ignoreCase = true)
            ) return true
        }
        return false
    }

    private fun dohErrorMessage(error: IOException): String = when {
        error.message.orEmpty().contains("ech_config_missing") -> "DNS 成功，服务器未提供 ECH 配置"
        error.message.orEmpty().contains("ech_rejected") -> "ECH 被拒绝，请重试"
        error.message.orEmpty().contains("ech_config_invalid") -> "ECH 配置无效"
        error.message.orEmpty().contains("doh_status_or_content_type") -> "DoH 未返回标准 DNS 数据"
        error.message.orEmpty().contains("dns_timeout") -> "DoH 查询超时"
        error.message.orEmpty().contains("bootstrap_lookup_failed") -> "无法解析 DoH 服务器"
        error.message.orEmpty().contains("tls_handshake_or_certificate_failed") -> "TLS 握手或证书校验失败"
        error.message.orEmpty().contains("native_unavailable") -> "原生组件不可用"
        error.message.orEmpty().contains("socket_timeout") -> "连接超时"
        else -> "DoH + ECH 连接失败 [${error.message?.take(30)}]"
    }

    private fun buildBootstrapClient(server: DohServer): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (server.bootstrapIps.isNotEmpty()) {
            builder.dns(BootstrapDns(server.url, server.bootstrapIps))
        }

        return builder.build()
    }

    private fun safeParseIp(ip: String): InetAddress? = runCatching {
        InetAddress.getByName(ip)
    }.getOrNull()

    private class BootstrapDns(
        serverUrl: String,
        bootstrapIps: List<String>,
    ) : okhttp3.Dns {
        private val serverHost: String = runCatching {
            serverUrl.toHttpUrl().host
        }.getOrDefault("")

        private val presetAddresses: List<InetAddress> =
            bootstrapIps.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == serverHost && presetAddresses.isNotEmpty()) {
                return presetAddresses
            }
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }
}

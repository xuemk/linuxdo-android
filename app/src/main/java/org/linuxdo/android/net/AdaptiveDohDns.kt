package org.linuxdo.android.net

import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * 自适应 DoH DNS 解析器（核心容灾组件）。
 *
 * ## 核心机制
 *
 * ### Split DNS 分流
 * 只有 `linux.do` 及其子域名才走 DoH，其余域名（github.com、jsdelivr.net 等）
 * 直透 `Dns.SYSTEM`。这确保：
 * - 应用内 APK 更新下载（GitHub Releases）不受影响
 * - FCM 推送连接不受干扰
 * - 非论坛请求的隐私与延迟不被 DoH 代理节点影响
 *
 * ### 故障链式切换
 * 当当前选中节点连续失败时，按优先级依次尝试备用节点，全部失败则回退 `Dns.SYSTEM`。
 *
 * ### 2 分钟惩罚冷却
 * 节点失败后进入 2 分钟惩罚窗口，窗口内直接跳过该节点。
 * 避免反复撞墙拖慢解析速度。
 *
 * ### 自动故障切换（可选）
 * [autoFailover] = true 时，主节点解析失败会自动切换到下一个可用节点。
 * 切换后保存到持久化存储，确保下次启动也使用成功的节点。
 *
 * @param servers          按优先级排列的节点列表（第 0 个为当前首选）
 * @param bootstrapClient  用于建立 DoH 连接的底层客户端（不挂载 DoH，避免递归）
 * @param autoFailover     是否启用自动故障切换，默认 true
 */
class AdaptiveDohDns(
    private val servers: List<DohServer>,
    private val bootstrapClient: OkHttpClient,
    private val autoFailover: Boolean = true,
) : Dns {

    companion object {
        private const val TAG = "AdaptiveDohDns"

        /** 故障惩罚窗口（毫秒），窗口内跳过该节点直接尝试下一个 */
        private const val FAILURE_PENALTY_MS = 2 * 60 * 1000L

        /**
         * 需要走 DoH 解析的域名后缀集合。
         * 精确匹配：只有这些域名及其子域才走 DoH，其余直透系统 DNS。
         */
        private val DOH_DOMAINS = setOf("linux.do")

        /**
         * 判断域名是否应该走 DoH 解析。
         * 匹配规则：精确等于目标域名，或是目标域名的子域（以 .linux.do 结尾）。
         */
        fun shouldUseDoh(hostname: String): Boolean =
            DOH_DOMAINS.any { domain ->
                hostname == domain || hostname.endsWith(".$domain")
            }
    }

    /** 故障惩罚截止时间戳 Map。key: 节点 URL，value: 惩罚截止时间（毫秒） */
    private val failurePenalties = ConcurrentHashMap<String, Long>()

    /** 各节点对应的 DnsOverHttps 实例，懒加载构建 */
    private val dohInstances = ConcurrentHashMap<String, DnsOverHttps>()

    /** 外部可更换当前首选节点的游标 */
    @Volatile
    var primaryServerId: String = servers.firstOrNull()?.id ?: ""

    override fun lookup(hostname: String): List<InetAddress> {
        // Split DNS：非论坛域名直透系统 DNS
        if (!shouldUseDoh(hostname)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        // 构建本次解析的节点尝试顺序：首选节点优先，其余按声明顺序补位
        val orderedServers = buildOrderedServers()

        for (server in orderedServers) {
            // 跳过仍在惩罚窗口内的节点
            val penalty = failurePenalties[server.id]
            if (penalty != null && System.currentTimeMillis() < penalty) {
                Log.d(TAG, "跳过惩罚中节点 ${server.name}，剩余冷却 ${(penalty - System.currentTimeMillis()) / 1000}s")
                continue
            }

            try {
                val doh = getOrCreateDoh(server)
                val addresses = doh.lookup(hostname)
                if (addresses.isNotEmpty()) {
                    if (server.id != primaryServerId && autoFailover) {
                        Log.i(TAG, "DoH 自动切换到 ${server.name}，解析 $hostname → ${addresses.first()}")
                        primaryServerId = server.id
                    } else {
                        Log.d(TAG, "DoH ${server.name} 解析 $hostname → ${addresses.first()}")
                    }
                    return addresses
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH ${server.name} 解析 $hostname 失败: ${e.message}，进入惩罚冷却")
                failurePenalties[server.id] = System.currentTimeMillis() + FAILURE_PENALTY_MS
            }
        }

        // 所有 DoH 节点均失败，回退系统 DNS
        Log.w(TAG, "所有 DoH 节点均失败，$hostname 回退 Dns.SYSTEM")
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            throw UnknownHostException("$hostname (所有 DoH 节点不可用且系统 DNS 也失败)").also { it.initCause(e) }
        }
    }

    /**
     * 构建本次解析的节点优先顺序。
     * 首选节点（primaryServerId 对应）排在第一位，其余按声明顺序跟随。
     */
    private fun buildOrderedServers(): List<DohServer> {
        val primary = servers.firstOrNull { it.id == primaryServerId }
        val rest = servers.filter { it.id != primaryServerId }
        return if (primary != null) listOf(primary) + rest else servers
    }

    /** 获取或懒加载创建节点对应的 DnsOverHttps 实例 */
    private fun getOrCreateDoh(server: DohServer): DnsOverHttps =
        dohInstances.getOrPut(server.id) {
            val dohBuilder = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(server.url.toHttpUrl())
                .post(true)

            val ips = server.bootstrapIps.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            if (ips.isNotEmpty()) {
                dohBuilder.bootstrapDnsHosts(ips)
            }

            dohBuilder.build()
        }

    /**
     * 手动清除指定节点的故障惩罚，供用户手动切换节点后立即生效使用。
     */
    fun clearPenalty(serverId: String) {
        failurePenalties.remove(serverId)
    }

    /**
     * 清除所有节点的故障惩罚（如用户手动重试全部节点）。
     */
    fun clearAllPenalties() {
        failurePenalties.clear()
    }
}

package org.linuxdo.android.net.anycast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.linuxdo.android.net.NativeTunnelCore
import org.linuxdo.android.net.TunnelConfig
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 池中单个节点的健康状态。
 *
 * [latencyMs] 为 [AnycastNode.UNPROBED] 表示尚未探测过 —— 这类节点不计入健康，但仍可在冷启动
 * 尚未完成首轮探测时作为兜底被取用。
 */
data class AnycastNode(
    val ip: String,
    val latencyMs: Long = UNPROBED,
    val consecutiveFailures: Int = 0,
) {
    val isProbed: Boolean get() = latencyMs != UNPROBED

    /**
     * 是否已尝试过探测（无论成功或失败）。
     *
     * 探测失败不会抹掉已测得的 [latencyMs]（只累加失败计数），因此单看 [isProbed] 无法区分
     * 「从未尝试」与「尝试过但失败」—— 必须结合失败计数判断，否则死节点会被当作新鲜种子反复取用。
     */
    val hasBeenTried: Boolean get() = isProbed || consecutiveFailures > 0

    /**
     * 成功测得过延迟、且连续失败次数未达 [FAILURE_TOLERANCE]，才算健康。
     *
     * 容忍少量连续失败是实测得出的必要设计：同一节点（104.26.0.1）在宿主机串行探测中 0.64s 正常
     * 握手，却在设备端的并发探测中失败一次。若一次失败即下线，会持续误杀仅是抖动的好节点。
     */
    val isHealthy: Boolean get() = isProbed && consecutiveFailures < FAILURE_TOLERANCE

    companion object {
        const val UNPROBED: Long = -1L

        /** 连续失败达到该次数才判定不健康。1 次失败多为瞬时抖动，不足以定罪。 */
        const val FAILURE_TOLERANCE = 2
    }
}

/**
 * Cloudflare Anycast 节点池，对应 design §2.4 容灾天梯的 L1 / L2 / L4。
 *
 * - **L1 内置常青种子池**：硬编码泛播段 IP（[DEFAULT_SEEDS]），零依赖，断网也能读，冷启动即可用；
 * - **L2 健康淘汰**：以完整 TLS 握手成功与耗时为判据，见 [AnycastProbe] 的说明；
 * - **L4 普通 DNS 借壳**：预置 IP 全部失效时，用系统 DNS 解析未被封锁的通用 CF 域名取 A 记录。
 *
 * L3（业务通道捎带 / OTA 热更新）留待远程托管位置确定后经 [merge] 接入。
 *
 * **全池不健康时 [best] 返回 null**，由上层可用性编排器据此降级到 DoH 通道；池内部不做静默兜底，
 * 以免把故障掩盖成「看起来还能用」。
 *
 * 本类线程安全：读路径走不加锁的不可变快照，写路径在 [lock] 内整体替换快照。
 */
class AnycastPool(
    seeds: List<String> = DEFAULT_SEEDS,
    private val targetHost: String = TunnelConfig.DEFAULT_TARGET_HOST,
) {

    private val lock = Any()

    @Volatile
    private var nodes: Map<String, AnycastNode> = seeds.associateWith { AnycastNode(ip = it) }

    @Volatile
    private var stickyWinner: String? = null

    /** 当前池快照，按优先级排序。 */
    fun snapshot(): List<AnycastNode> = nodes.values.sortedWith(PRIORITY)

    /**
     * 按优先级排序的可用 IP 列表（对齐 cfluxdo 1.1.0 的单一赢家与出口 IP 粘性策略）。
     *
     * 只要当前粘性赢家 [winner] 保持健康，所有请求均首选赢家节点（置于索引 0），
     * 避免多线程/图片并发拉取时出口 POP 在不同 Anycast IP 之间高频漂移引发 CF 风控跳盾。
     */
    fun ordered(): List<String> {
        val all = snapshot().map { it.ip }
        val currentWinner = winner() ?: return all
        return listOf(currentWinner) + (all - currentWinner)
    }

    /**
     * 获取当前粘性赢家节点（Sticky Winner）。
     * 若已绑定的赢家仍然健康，保持不变；若未绑定或已失效，则重新从 [best] 选优绑定。
     */
    fun winner(): String? {
        val current = stickyWinner
        if (current != null && nodes[current]?.isHealthy == true) {
            return current
        }
        val elected = best()
        stickyWinner = elected
        if (elected != null) {
            runCatching { NativeTunnelCore.setProxyUpstreamIp(elected) }
        }
        return elected
    }

    /**
     * 业务主动触发优选节点轮换（用于遇到 Cloudflare 403 质询或盾时避开被特定风控标记的 IP）。
     */
    fun rotateWinner(): String? = synchronized(lock) {
        val current = stickyWinner
        if (current != null) {
            val node = nodes[current]
            if (node != null) {
                nodes = nodes + (current to node.copy(consecutiveFailures = node.consecutiveFailures + 1))
            }
        }
        stickyWinner = null
        val newWinner = winner()
        if (newWinner != null) {
            runCatching { NativeTunnelCore.setProxyUpstreamIp(newWinner) }
        }
        newWinner
    }

    /** 当前最优节点；全池均已尝试且无一健康时返回 null，交由上层降级。 */
    fun best(): String? {
        val sorted = snapshot()
        // snapshot 已按 PRIORITY 排序，首个健康节点即最优
        sorted.firstOrNull { it.isHealthy }?.let { return it.ip }
        // 首轮探测尚未完成时，按种子优先级取第一个从未尝试过的节点兜底；
        // 全部尝试过且均失败则返回 null，不做静默兜底（见类注释）。
        return sorted.firstOrNull { !it.hasBeenTried }?.ip
    }

    /**
     * 主池是否已耗尽：所有节点都尝试过且无一健康。
     *
     * 上层编排器据此决定下一步 —— 先 [merge] 入 [FALLBACK_SEEDS] 或 [borrowViaSystemDns] 的结果
     * 再试一轮，仍不行才降级到 DoH 通道。
     */
    fun isExhausted(): Boolean {
        val all = nodes.values
        return all.isNotEmpty() && all.none { it.isHealthy } && all.all { it.hasBeenTried }
    }

    /**
     * 并发对全池发起健康探测并就地更新状态，返回更新后的快照。
     *
     * 并发度限制为 [PROBE_CONCURRENCY] 而非全量并发：实测全量并发会严重污染延迟测量 —— 同一节点
     * （104.18.0.1）宿主机串行测得 0.68s，设备端 15 路并发下测得 5606ms，相差 8 倍。带宽竞争使
     * 延迟数字失去可比性，而选优恰恰依赖延迟排序。
     */
    suspend fun refresh(): List<AnycastNode> {
        val targets = nodes.keys.toList()
        val gate = Semaphore(PROBE_CONCURRENCY)
        val results = coroutineScope {
            targets.map { ip ->
                async(Dispatchers.IO) { gate.withPermit { AnycastProbe.probe(ip) } }
            }.awaitAll()
        }
        synchronized(lock) {
            val updated = nodes.toMutableMap()
            results.forEach { result ->
                val current = updated[result.ip] ?: return@forEach
                updated[result.ip] = if (result.ok) {
                    current.copy(latencyMs = result.elapsedMs, consecutiveFailures = 0)
                } else {
                    // 保留历史延迟：失败可能只是抖动，等连续失败达阈值再判死（见 isHealthy）
                    current.copy(consecutiveFailures = current.consecutiveFailures + 1)
                }
            }
            nodes = updated
        }
        return snapshot()
    }

    /**
     * 业务侧真实失败回馈。
     *
     * 比定时探测灵敏得多：节点可能在两次探测之间被掐断，等下一轮探测才发现会白白损失多次请求。
     */
    fun reportFailure(ip: String) = synchronized(lock) {
        val current = nodes[ip] ?: return
        if (ip == stickyWinner) {
            stickyWinner = null
        }
        // 同 refresh：保留历史延迟，靠连续失败计数定罪，避免单次抖动误杀
        nodes = nodes + (ip to current.copy(consecutiveFailures = current.consecutiveFailures + 1))
    }

    /** 业务侧成功回馈，用于把此前被误判的节点放回健康集合。 */
    fun reportSuccess(ip: String, latencyMs: Long) = synchronized(lock) {
        val current = nodes[ip] ?: return
        nodes = nodes + (ip to current.copy(
            latencyMs = latencyMs.coerceAtLeast(0),
            consecutiveFailures = 0,
        ))
    }

    /** 合并新节点（L3 的 OTA 列表、L4 的借壳结果）。已存在的节点保留其既有健康状态。 */
    fun merge(ips: List<String>) = synchronized(lock) {
        val additions = ips.filter { it.isNotBlank() && it !in nodes }
        if (additions.isEmpty()) return
        nodes = nodes + additions.associateWith { AnycastNode(ip = it) }
    }

    /**
     * L4 紧急再生：用系统 DNS 解析通用 CF 域名，借其 A 记录作为 Anycast 节点。
     *
     * 这些域名在境内普遍未被污染，而它们的 A 记录本身就是 CF 泛播 IP，天然可复用。
     *
     * 结果经 [isUsablePublicIpv4] 过滤 —— 这一步不是形式主义：设备或宿主若运行分流工具，系统
     * DNS 会返回 `198.18.0.0/15` 段的 fake-ip，拿去直连必然失败且极难排查。
     */
    suspend fun borrowViaSystemDns(): List<String> = withContext(Dispatchers.IO) {
        val hosts = listOf(targetHost, "www.$targetHost")
        hosts.asSequence()
            .flatMap { host ->
                runCatching { InetAddress.getAllByName(host).asSequence() }
                    .getOrElse { emptySequence() }
            }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { isUsablePublicIpv4(it) }
            .distinct()
            .toList()
    }

    companion object {
        /**
         * L1 常青种子池：由 libtunnel_core.so 内存混淆安全解密加载，防静态脱壳提取。
         */
        val DEFAULT_SEEDS: List<String> = runCatching { NativeTunnelCore.getSeeds() }
            .getOrElse { emptyList() }

        /**
         * 通用 CF 泛播入口：由 libtunnel_core.so 内存混淆安全解密加载。
         */
        val FALLBACK_SEEDS: List<String> = runCatching { NativeTunnelCore.getFallbackSeeds() }
            .getOrElse { emptyList() }

        /**
         * 健康优先，同为健康时先比失败计数（抖动过的靠后）再比延迟；
         * 从未尝试的次之；确认失败的排最后（失败次数多的更靠后）。
         */
        private val PRIORITY = Comparator<AnycastNode> { left, right ->
            val leftRank = rankOf(left)
            val rightRank = rankOf(right)
            when {
                leftRank != rightRank -> leftRank.compareTo(rightRank)
                leftRank == 0 -> compareValuesBy(left, right, { it.consecutiveFailures }, { it.latencyMs })
                leftRank == 2 -> left.consecutiveFailures.compareTo(right.consecutiveFailures)
                else -> 0
            }
        }

        private fun rankOf(node: AnycastNode): Int = when {
            node.isHealthy -> 0
            !node.hasBeenTried -> 1
            else -> 2
        }

        /**
         * 探测并发度。不做全量并发 —— 见 [refresh] 的说明：带宽竞争会让延迟测量失去可比性。
         */
        private const val PROBE_CONCURRENCY = 4

        /**
         * 排除回环、私有、链路本地、组播以及 `198.18.0.0/15`。
         */
        internal fun isUsablePublicIpv4(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            val (first, second) = octets
            return when {
                first == 0 || first == 127 -> false
                first == 10 -> false
                first == 172 && second in 16..31 -> false
                first == 192 && second == 168 -> false
                first == 169 && second == 254 -> false
                first == 198 && second in 18..19 -> false
                first >= 224 -> false
                else -> true
            }
        }
    }
}

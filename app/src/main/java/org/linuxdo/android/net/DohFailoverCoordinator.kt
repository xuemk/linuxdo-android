package org.linuxdo.android.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * 传输层代次（Generation）快照。
 *
 * 持有当前代次的代号、绑定的 DoH 节点、底层原生传输 Transport 以及配置完成的 OkHttpClient。
 */
/**
 * 双轨网络路由模式。
 *
 * 当 DoH 开关开启时，系统支持智能双轨调度：
 * - [DIRECT]：通过 AnycastPool 接管解析并直接握手（零额外 DNS 往返，基础延迟极低）；
 * - [DOH]：通过 DoH 动态解析获取目标 A 记录后再进行原生 ECH 握手。
 */
enum class NetworkRouteMode(val label: String, val shortLabel: String) {
    DIRECT("本地直连 (Anycast)", "本地直连"),
    DOH("DoH 动态安全解析", "DoH 解析")
}

/**
 * 传输层代次（Generation）快照。
 *
 * 持有当前代次的代号、绑定的 DoH 节点、底层原生传输 Transport 以及配置完成的 OkHttpClient。
 */
data class TransportEpoch(
    val generation: Long,
    val server: DohServer,
    val transport: DohTransport?,
    val client: OkHttpClient,
    val routeMode: NetworkRouteMode = NetworkRouteMode.DIRECT,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 原生 DoH + ECH 自动故障转移协调器。
 *
 * 核心机制：
 * 1. 单飞并发控制（Single-Flight）：同一 generation 的批量故障仅触发一次原子轮换，忽略旧代次的滞后报错。
 * 2. 宽限期优雅停机（Graceful Retirement）：旧代次进入 30 秒宽限期队列，在后台异步释放，绝不瞬杀在途连接。
 * 3. 故障精准分类：
 *    - 确定性 DoH/ECH 故障（如 ECH 被拒、缺少配置、DNS 超时等）立即切换；
 *    - 普通连接超时/握手抖动设短窗口阈值防抖，避免弱网频繁切换。
 * 4. 偏好分离：用户持久化的“首选节点”与运行时的“有效节点”独立，自动故障转移不擅自覆盖用户配置。
 * 5. 全局动态分发：提供 Call.Factory 供 ImageLoader (Coil)、认证服务等全局共享，永远获取最新 Client。
 */
class DohFailoverCoordinator(
    private val context: Context,
    private val store: DohConfigProvider,
    private val userAgent: String,
    private val networkMonitor: NetworkStateMonitor,
    private val anycastPool: org.linuxdo.android.net.anycast.AnycastPool? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    var onEpochChanged: (TransportEpoch) -> Unit = {},
    var onLatenciesUpdated: ((latencies: Map<String, Long?>, summaries: Map<String, String>, selectedUrl: String) -> Unit)? = null,
    private val clientFactory: (dns: Dns?, transport: DohTransport?, coordinator: DohFailoverCoordinator, routeMode: NetworkRouteMode) -> OkHttpClient,
) : Call.Factory {

    companion object {
        private const val TAG = "DohCoordinator"
        /** 故障惩罚窗口：2 分钟 */
        private const val PENALTY_DURATION_MS = 120_000L
        /** 旧 Generation 宽限期：30 秒 */
        private const val GRACE_PERIOD_MS = 30_000L
        /** 抖动计数窗口：8 秒 */
        private const val JITTER_WINDOW_MS = 8_000L
        /** 抖动触发阈值：8 秒内连续发生 2 次才触发切换 */
        private const val JITTER_THRESHOLD = 2
        /** 智能双轨定时探测周期：10 分钟（低频防 CF 风控） */
        private const val SMART_ROUTE_INTERVAL_MS = 10 * 60 * 1000L
    }

    private val generationCounter = AtomicLong(1L)
    private val failoverMutex = Mutex()

    /** 节点故障熔断表：serverId -> 惩罚截止时间戳 */
    private val penalties = ConcurrentHashMap<String, Long>()

    /** 抖动计数跟踪：记录抖动次数与起始时间戳 */
    private var jitterCount = 0
    private var jitterWindowStart = 0L

    /** 运行时有效节点（与用户持久化的首选节点分离） */
    private val effectiveServer: AtomicReference<DohServer>

    /** 当前活跃代次 */
    val currentEpoch: AtomicReference<TransportEpoch>

    /** 当前生效的双轨模式 */
    val activeRouteMode = AtomicReference<NetworkRouteMode>(NetworkRouteMode.DIRECT)
    val latestDirectLatencyMs = AtomicLong(-1L)
    val latestDohLatencyMs = AtomicLong(-1L)
    val latestSmartSummary = AtomicReference<String>("尚未完成双轨探测")
    var onSmartRouteUpdated: ((mode: NetworkRouteMode, directMs: Long, dohMs: Long, summary: String) -> Unit)? = null

    @Volatile private var isAppInForeground: Boolean = true
    private var lastSmartProbeTimeMs = 0L
    private var smartRouteJob: kotlinx.coroutines.Job? = null

    init {
        val initialServer = resolveInitialServer()
        effectiveServer = AtomicReference(initialServer)
        val initialEpoch = buildEpoch(generationCounter.get(), initialServer)
        currentEpoch = AtomicReference(initialEpoch)
    }

    /** 作为 Call.Factory 分发给各业务方（Coil、后台服务等），保证新发请求永远走最新 generation */
    override fun newCall(request: Request): Call {
        return currentEpoch.get().client.newCall(request)
    }

    val currentClient: OkHttpClient
        get() = currentEpoch.get().client

    /**
     * 判断异常是否符合故障转移触发资格。
     */
    fun isFailoverEligible(error: Throwable): Boolean {
        // 如果未开启自动故障切换，或 DoH 本身已关闭，或当前处于 VPN/系统代理暂停中，则不触发切换
        if (!store.isDohEnabled() || !store.isDohAutoFailoverEnabled()) return false
        if (networkMonitor.detect().shouldPauseDoh) return false

        return isDefinitiveDohEchError(error) || isJitterError(error)
    }

    /**
     * 上报故障（由应用级拦截器触发）。
     *
     * 保证单飞执行：同一代次内的并发失败仅轮转一次。
     */
    fun reportFailure(fromGeneration: Long, serverId: String, error: Throwable) {
        val epoch = currentEpoch.get()
        // 忽略来自旧代次的滞后报错
        if (fromGeneration != epoch.generation) {
            Log.d(TAG, "忽略旧代次 (gen $fromGeneration < 当前 ${epoch.generation}) 的滞后异常")
            return
        }

        if (!isFailoverEligible(error)) {
            return
        }

        scope.launch {
            failoverMutex.withLock {
                val latestEpoch = currentEpoch.get()
                if (fromGeneration != latestEpoch.generation) {
                    return@withLock
                }

                val isDefinitive = isDefinitiveDohEchError(error)
                if (!isDefinitive) {
                    // 弱网普通抖动，检查是否达到短窗口阈值
                    val now = System.currentTimeMillis()
                    if (now - jitterWindowStart > JITTER_WINDOW_MS) {
                        jitterWindowStart = now
                        jitterCount = 1
                        Log.d(TAG, "节点 $serverId 发生首次弱网抖动，开启 8s 窗口防抖")
                        return@withLock
                    } else {
                        jitterCount++
                        if (jitterCount < JITTER_THRESHOLD) {
                            Log.d(TAG, "节点 $serverId 抖动计数 $jitterCount < $JITTER_THRESHOLD，暂不触发切换")
                            return@withLock
                        }
                    }
                }

                // 重置抖动计数
                jitterCount = 0
                jitterWindowStart = 0L

                // 确认为有效故障，执行代次轮换
                val nextServer = selectNextHealthyServer(latestEpoch.server)
                if (nextServer.id == latestEpoch.server.id) {
                    Log.w(TAG, "无其他可用候选节点，保留当前节点 ${latestEpoch.server.name}")
                    return@withLock
                }

                Log.w(TAG, "触发 DoH 自动故障转移 [gen ${latestEpoch.generation}]: ${latestEpoch.server.name} -> ${nextServer.name}，原因: ${error.message}")

                // 对故障节点施加 2 分钟熔断惩罚
                penalties[latestEpoch.server.id] = System.currentTimeMillis() + PENALTY_DURATION_MS

                // 原子推进到新代次
                val newGen = generationCounter.incrementAndGet()
                effectiveServer.set(nextServer)
                val newEpoch = buildEpoch(newGen, nextServer)
                val retiredEpoch = currentEpoch.getAndSet(newEpoch)

                // 优雅退役旧代次（宽限期延迟释放）
                retireEpochGracefully(retiredEpoch)

                // 回调通知外部客户端
                onEpochChanged(newEpoch)
            }
        }
    }

    /**
     * 等待或获取最新代次（供 GET/HEAD 请求安全重试）。
     */
    suspend fun awaitOrGetNextEpoch(fromGeneration: Long): TransportEpoch {
        // 如果已经切到新代次，直接返回
        val current = currentEpoch.get()
        if (current.generation > fromGeneration) return current

        // 若仍在同代次，短暂等待单飞协程完成轮换（最多等 1500ms）
        for (i in 0 until 15) {
            delay(100)
            val updated = currentEpoch.get()
            if (updated.generation > fromGeneration) return updated
        }
        return currentEpoch.get()
    }

    /**
     * 用户在设置页手动选择节点（优先级最高）。
     *
     * 重置该节点的故障惩罚，并同步重置有效节点。
     */
    fun manualSelect(server: DohServer) {
        scope.launch {
            failoverMutex.withLock {
                penalties.remove(server.id)
                jitterCount = 0
                val newGen = generationCounter.incrementAndGet()
                effectiveServer.set(server)
                val newEpoch = buildEpoch(newGen, server)
                val retiredEpoch = currentEpoch.getAndSet(newEpoch)
                retireEpochGracefully(retiredEpoch)
                onEpochChanged(newEpoch)
                Log.i(TAG, "用户手动切换节点至: ${server.name} (gen $newGen)")
            }
        }
    }

    /**
     * 当全局网络环境变动（如 VPN/代理开启或关闭，或 DoH 开关变更）时触发刷新。
     */
    /**
     * 回收当前客户端的连接池，并原子推进代次以建立新连接（如默认网络切换或后台久置回前台）。
     */
    fun recycleConnections(reason: String) {
        runCatching {
            currentEpoch.get().client.connectionPool.evictAll()
        }
        scope.launch {
            failoverMutex.withLock {
                val server = effectiveServer.get()
                val newGen = generationCounter.incrementAndGet()
                val newEpoch = buildEpoch(newGen, server)
                val retiredEpoch = currentEpoch.getAndSet(newEpoch)
                retireEpochGracefully(retiredEpoch)
                onEpochChanged(newEpoch)
                Log.i(TAG, "$reason，生成新代次 (gen $newGen)")
            }
        }
    }

    fun refresh() {
        scope.launch {
            failoverMutex.withLock {
                val server = effectiveServer.get()
                val newGen = generationCounter.incrementAndGet()
                val newEpoch = buildEpoch(newGen, server)
                val retiredEpoch = currentEpoch.getAndSet(newEpoch)
                retireEpochGracefully(retiredEpoch)
                onEpochChanged(newEpoch)
                Log.i(TAG, "网络环境变化，刷新 TransportEpoch (gen $newGen)")
            }
        }
    }

    /** 清除所有惩罚（供用户重置使用） */
    fun clearAllPenalties() {
        penalties.clear()
        jitterCount = 0
    }

    /** 设置是否开启自动优选切优 */
    fun setAutoOptimizeEnabled(enabled: Boolean) {
        // 保存或通知切优状态
    }

    /**
     * 优雅退役旧代次：等 30 秒宽限期后再释放 Native Profile，保证存量在途请求不受影响。
     */
    private fun retireEpochGracefully(epoch: TransportEpoch) {
        if (epoch.transport == null) return
        scope.launch {
            Log.d(TAG, "旧代次 [gen ${epoch.generation}] 进入 ${GRACE_PERIOD_MS / 1000}s 宽限期...")
            delay(GRACE_PERIOD_MS)
            try {
                epoch.client.connectionPool.evictAll()
                epoch.transport.close()
                Log.d(TAG, "旧代次 [gen ${epoch.generation}] 宽限期结束，已安全销毁 Profile")
            } catch (e: Exception) {
                Log.w(TAG, "销毁旧代次异常: ${e.message}")
            }
        }
    }

    private fun resolveInitialServer(): DohServer {
        val allServers = loadDohServers(store)
        val selectedUrl = store.getDohServerUrl() ?: DohPresets.DEFAULT.url
        return allServers.firstOrNull { it.url == selectedUrl } ?: allServers.firstOrNull() ?: DohPresets.DEFAULT
    }

    private fun selectNextHealthyServer(current: DohServer): DohServer {
        val allServers = loadDohServers(store)
        val now = System.currentTimeMillis()

        // 优先在未受惩罚的健康节点中选择下一个
        val healthyCandidates = allServers.filter { it.id != current.id && (penalties[it.id] ?: 0L) <= now }
        if (healthyCandidates.isNotEmpty()) {
            return healthyCandidates.first()
        }

        // 若全部节点都处于惩罚期，选熔断最快到期的节点
        val soonest = allServers.filter { it.id != current.id }.minByOrNull { penalties[it.id] ?: 0L }
        return soonest ?: current
    }

    private fun buildEpoch(gen: Long, server: DohServer): TransportEpoch {
        // VPN / 系统代理享有最高优先级：整体让渡系统网络，不做任何直连或 DoH 特殊处理。
        val shouldYieldToSystem = networkMonitor.detect().shouldPauseDoh
        // 「直连优先」：无 VPN 时**始终**建立原生传输层。它用内置 ECH 公钥握手，不依赖 DoH
        // 可达性（见 native 的 ech.rs），因此设置里的 DoH 开关只决定 A 记录解析源，
        // 不再决定直连能力的存亡 —— 旧实现把 transport 绑在 isDohEnabled 上，用户一关开关
        // 就退回明文 SNI，与「直连优先」直接冲突。
        val transport = if (!shouldYieldToSystem) {
            runCatching { DohTransport(server) }.getOrNull()
        } else null
        // 原生层构建失败时的纯 DNS 退路，仅在用户确实开启了 DoH 时才有意义。
        val dns = if (!shouldYieldToSystem && transport == null && store.isDohEnabled()) {
            buildDohDns(store)
        } else null

        val routeMode = if (!store.isDohEnabled()) {
            NetworkRouteMode.DIRECT
        } else {
            activeRouteMode.get()
        }

        val client = clientFactory(dns, transport, this, routeMode)

        return TransportEpoch(
            generation = gen,
            server = server,
            transport = transport,
            client = client,
            routeMode = routeMode,
        )
    }

    private fun isDefinitiveDohEchError(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return msg.contains("ech_rejected") ||
            msg.contains("ech_config_missing") ||
            msg.contains("doh_status_or_content_type") ||
            msg.contains("dns_timeout") ||
            msg.contains("bootstrap_lookup_failed") ||
            msg.contains("doh_request_failed") ||
            (t is UnknownHostException && msg.contains("DoH"))
    }

    private fun isJitterError(t: Throwable): Boolean {
        return t is SocketTimeoutException ||
            t is ConnectException ||
            (t is SSLHandshakeException && !isDefinitiveDohEchError(t)) ||
            (t is SSLException && !isDefinitiveDohEchError(t))
    }

    /**
     * 防颠簸滞后仲裁（Hysteresis）。
     *
     * 本地直连少一层 DNS 往返，基础优势明显，偏好直连；
     * 仅当 DoH 显著更快（比直连快 35% 以上且绝对差值 >= 40ms）时才切换至 DoH；
     * 当直连恢复良好或接近时，倾向切回直连，防止在临界点频繁颠簸触发 Cloudflare 会话与风控刷新。
     */
    fun arbitrateRoute(current: NetworkRouteMode, directMs: Long, dohMs: Long): Pair<NetworkRouteMode, String> {
        if (directMs > 0 && dohMs <= 0) {
            return NetworkRouteMode.DIRECT to "DoH 握手失败，保持/切回本地直连 (${directMs}ms)"
        }
        if (directMs <= 0 && dohMs > 0) {
            return NetworkRouteMode.DOH to "本地直连握手失败，智能切换至 DoH 动态解析 (${dohMs}ms)"
        }
        if (directMs <= 0 && dohMs <= 0) {
            return current to "两路握手均异常，保持当前 ${current.shortLabel} 模式"
        }

        return when (current) {
            NetworkRouteMode.DIRECT -> {
                if (dohMs < (directMs * 0.65).toLong() && (directMs - dohMs) >= 40L) {
                    NetworkRouteMode.DOH to "DoH 显著优于直连 (DoH: ${dohMs}ms < 直连: ${directMs}ms)，智能优选 DoH"
                } else {
                    NetworkRouteMode.DIRECT to "本地直连极速稳定 (直连: ${directMs}ms, DoH: ${dohMs}ms)，优选直连"
                }
            }
            NetworkRouteMode.DOH -> {
                if (directMs <= dohMs || (directMs - dohMs) < 30L) {
                    NetworkRouteMode.DIRECT to "本地直连恢复低延迟 (直连: ${directMs}ms, DoH: ${dohMs}ms)，切回直连"
                } else {
                    NetworkRouteMode.DOH to "DoH 表现持续更优 (DoH: ${dohMs}ms < 直连: ${directMs}ms)，保持 DoH"
                }
            }
        }
    }

    /**
     * 评估并执行智能双轨路由调度。
     */
    suspend fun evaluateSmartRoute(force: Boolean = false) {
        if (!store.isDohEnabled()) {
            activeRouteMode.set(NetworkRouteMode.DIRECT)
            latestDirectLatencyMs.set(-1L)
            latestDohLatencyMs.set(-1L)
            val summary = "DoH 开关已关闭，默认强制本地直连 (Anycast)"
            latestSmartSummary.set(summary)
            onSmartRouteUpdated?.invoke(NetworkRouteMode.DIRECT, -1L, -1L, summary)
            return
        }

        val netState = networkMonitor.detect()
        if (netState.shouldPauseDoh) {
            val summary = if (netState.isVpn) "VPN 已开启，直连已让渡系统网络" else "系统代理已开启，直连已让渡系统网络"
            latestSmartSummary.set(summary)
            onSmartRouteUpdated?.invoke(activeRouteMode.get(), -1L, -1L, summary)
            return
        }

        val currentServer = effectiveServer.get()
        val directDns = anycastPool?.let { org.linuxdo.android.net.anycast.AnycastDns(it, fallback = okhttp3.Dns.SYSTEM) }

        var directLatency: Long = -1L
        var dohLatency: Long = -1L
        kotlinx.coroutines.coroutineScope {
            val directJob = launch {
                directLatency = DohProbeEngine.probeTlsHandshake(currentServer, directDns, timeoutSeconds = 6).latencyMs ?: -1L
            }
            val dohJob = launch {
                dohLatency = DohProbeEngine.probeTlsHandshake(currentServer, null, timeoutSeconds = 6).latencyMs ?: -1L
            }
            directJob.join()
            dohJob.join()
        }

        latestDirectLatencyMs.set(directLatency)
        latestDohLatencyMs.set(dohLatency)
        lastSmartProbeTimeMs = System.currentTimeMillis()

        val currentMode = activeRouteMode.get()
        val (recommendedMode, summary) = arbitrateRoute(currentMode, directLatency, dohLatency)
        latestSmartSummary.set(summary)
        onSmartRouteUpdated?.invoke(recommendedMode, directLatency, dohLatency, summary)

        if (recommendedMode != currentMode || currentEpoch.get().routeMode != recommendedMode || force) {
            Log.i(TAG, "智能双轨决策更新: $currentMode -> $recommendedMode, 原因: $summary")
            activeRouteMode.set(recommendedMode)
            failoverMutex.withLock {
                val newGen = generationCounter.incrementAndGet()
                val newEpoch = buildEpoch(newGen, currentServer)
                val retiredEpoch = currentEpoch.getAndSet(newEpoch)
                retireEpochGracefully(retiredEpoch)
                onEpochChanged(newEpoch)
            }
        }
    }

    /** 手动触发一次智能双轨评估 */
    fun triggerSmartRouteEvaluation() {
        scope.launch {
            evaluateSmartRoute(force = true)
        }
    }

    /** 设置应用前后台状态，控制后台静默暂停定时测速 */
    fun setAppForeground(inForeground: Boolean) {
        val wasForeground = isAppInForeground
        isAppInForeground = inForeground
        if (!wasForeground && inForeground) {
            val elapsed = System.currentTimeMillis() - lastSmartProbeTimeMs
            if (store.isDohEnabled() && elapsed >= SMART_ROUTE_INTERVAL_MS) {
                scope.launch {
                    evaluateSmartRoute()
                }
            }
        }
    }

    /** 开启智能双轨低频定时监控（前台运行，10 分钟一次） */
    fun startSmartRouteMonitoring() {
        if (smartRouteJob != null) return
        smartRouteJob = scope.launch {
            while (true) {
                delay(SMART_ROUTE_INTERVAL_MS)
                if (isAppInForeground && store.isDohEnabled()) {
                    runCatching {
                        evaluateSmartRoute()
                    }.onFailure {
                        Log.w(TAG, "定时智能双轨评估异常: ${it.message}")
                    }
                }
            }
        }
    }
}

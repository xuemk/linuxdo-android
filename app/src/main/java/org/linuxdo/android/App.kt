package org.linuxdo.android

import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.AccountManager
import org.linuxdo.android.data.DraftStore
import org.linuxdo.android.data.HistoryStore
import org.linuxdo.android.data.ReadingStore
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.net.BrowserSession
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.net.OkHttpTransport
import org.linuxdo.android.net.Transport
import org.linuxdo.android.net.UserApiKeyAuthService
import org.linuxdo.android.net.WebViewFetchTransport
import org.linuxdo.android.net.AdaptiveDohDns
import org.linuxdo.android.net.DohFailoverCoordinator
import org.linuxdo.android.net.DohPresets
import org.linuxdo.android.net.DohTransport
import org.linuxdo.android.net.anycast.AnycastPool
import org.linuxdo.android.net.proxy.LocalProxyServer
import org.linuxdo.android.net.NetworkStateMonitor
import org.linuxdo.android.net.buildBootstrapClient
import org.linuxdo.android.net.buildSharedHttpClient

/** 实现 ImageLoaderFactory,Coil 的 AsyncImage 就会自动用上带凭据的共享客户端。 */
class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    private fun getProcessNameCompat(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return try {
            val cmdline = File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
            if (cmdline.isNotEmpty()) cmdline else packageName
        } catch (_: Exception) {
            packageName
        }
    }

    override fun onCreate() {
        super.onCreate()
        val currentProcess = getProcessNameCompat()
        if (currentProcess != packageName) {
            // 是 GeckoView 的沙盒子进程（如 :gpu, :tab 等），设置专属目录后缀并跳过主进程业务容器初始化
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val suffix = currentProcess.substringAfter(":", "child")
                try {
                    WebView.setDataDirectorySuffix(suffix)
                } catch (_: Exception) {}
            }
            return
        }

        container = AppContainer(this)

        org.linuxdo.android.push.LinuxDoPushSdk.init(
            org.linuxdo.android.push.PushConfig(
                targetActivityClass = MainActivity::class.java,
                smallIconResId = R.drawable.ic_launcher_foreground,
                baseUrl = Config.BASE_URL,
                httpClientProvider = { container.httpClient },
                sessionProvider = { ctx ->
                    val store = if (ctx === this) container.sessionStore else SessionStore(ctx)
                    object : org.linuxdo.android.push.PushSessionProvider {
                        override fun hasSession(): Boolean = store.hasSession()
                        override fun isBackgroundNotificationEnabled(): Boolean = store.isBackgroundNotificationEnabled()
                        override fun backgroundRequestDelayMs(): Long = maxOf(
                            org.linuxdo.android.data.TopicRepository.rateLimitRemainingMs(),
                            if (store.isClearanceRecoveryInProgress() || store.sessionChanging) 1_000L else 0L,
                        )
                        override fun getCookie(): String = store.storedCookies().entries.joinToString("; ") { "${it.key}=${it.value}" }
                        override fun getUserAgent(): String = container.userAgent
                        override fun getUserId(): Long = store.currentUserId()
                        override fun getSessionGeneration(): Long = store.sessionGeneration
                        override fun saveUserId(id: Long) = store.saveCurrentUserId(id)
                        override fun getLastSeenNotificationId(): Long = store.getLastSeenNotificationId()
                        override fun setLastSeenNotificationId(id: Long) = store.setLastSeenNotificationId(id)
                        override fun getLastBadgeCount(): Int = store.getLastBadgeCount()
                        override fun setLastBadgeCount(count: Int, force: Boolean) = store.setLastBadgeCount(count, force)
                        override fun incrementLastBadgeCount(): Int = store.incrementLastBadgeCount()
                        override fun isBadgeTestHolding(): Boolean = store.isBadgeTestHolding()
                        override fun setBadgeTestHoldUntil(timestamp: Long) = store.setBadgeTestHoldUntil(timestamp)
                        override fun getPendingTestReplayTag(): String? = store.getPendingTestReplayTag()
                        override fun setPendingTestReplayTag(tag: String?) = store.setPendingTestReplayTag(tag)
                        override fun isTestWaitScreenOnArmed(): Boolean = store.isTestWaitScreenOnArmed()
                        override fun setTestWaitScreenOnArmed(armed: Boolean) = store.setTestWaitScreenOnArmed(armed)
                        override fun isAppInForeground(): Boolean = store.isAppInForeground()
                        override fun setAppInForeground(inForeground: Boolean) = store.setAppInForeground(inForeground)
                        override fun getNotificationScope(): org.linuxdo.android.push.PushNotificationScope =
                            org.linuxdo.android.push.PushNotificationScope.fromRaw(store.getNotificationScope().code)
                        override fun getCustomRealtimeSeconds(): Int = store.getCustomRealtimeSeconds()
                        override fun setNotificationTransportHealthyAtMillis(timestamp: Long) {}
                    }
                },
                logger = { key, message, force ->
                    container.diagnostics.logThrottled(key, message, force)
                }
            )
        )
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}

class AppContainer(context: Context) {
    val preferences = context.getSharedPreferences("display_preferences", Context.MODE_PRIVATE)
    val diagnostics = Diagnostics(File(context.filesDir, "diagnostics.log")).also {
        org.linuxdo.android.net.NetDiagnostics.sink = it
    }

    init {
        installCrashHandler(context, diagnostics)
    }
    val sessionStore = SessionStore(context)
    val reading = ReadingStore(context)
    val accountManager = AccountManager(context)
    val draftStore = DraftStore(context)
    val historyStore = HistoryStore(context)
    private val initialUserAgent: String = resolveUserAgent(context, sessionStore)
    val userAgent: String get() = sessionStore.userAgentOrNull() ?: initialUserAgent
    val browser = BrowserSession(
        appContext = context,
        userAgent = userAgent,
        store = sessionStore,
        diagnostics = diagnostics,
        httpClientProvider = { dohCoordinator.currentClient },
        isProxyOrVpnActiveProvider = { networkMonitor.detect().shouldPauseDoh },
    )

    val networkMonitor = NetworkStateMonitor(context) {
        // 当开启/关闭 VPN 或系统代理时，通知协调器刷新代次以暂停或恢复 DoH 直连
        dohCoordinator.refresh()
        // VPN / 系统代理智能让渡：有真实 VPN 时清除 WebView 代理，无 VPN 时挂载本地代理
        updateWebViewProxyState()
        dohCoordinator.startSmartRouteMonitoring()
    }

    /**
     * 内置 Cloudflare Anycast IP 池 ——「直连优先」的寻址基座。
     *
     * 进程级单例：健康探测结果必须跨 transport 代次复用。若随代次重建，每次 VPN 切换或
     * 故障轮换都会把已测得的节点健康状态清空，重新交一遍探测学费。
     */
    val anycastPool: AnycastPool = AnycastPool()

    /**
     * 本地 HTTP CONNECT 代理与 TLS Record 级分片服务（路径 B）。
     * 为系统原生 WebView 访问 *.linux.do 提供免 DNS、Anycast 直连与 ClientHello Record 分片抗审查能力。
     */
    val localProxyServer: LocalProxyServer = LocalProxyServer(
        targetSuffix = Config.HOST,
        anycastPool = anycastPool,
    )

    /**
     * 原生 DoH + ECH 自动故障转移协调器。
     * 维护代次（Generation）、在途请求宽限期退役、单飞轮换与 Call.Factory 动态分发。
     */
    val dohCoordinator: DohFailoverCoordinator = DohFailoverCoordinator(
        context = context,
        store = sessionStore,
        userAgent = userAgent,
        networkMonitor = networkMonitor,
        anycastPool = anycastPool,
        clientFactory = { dns, transport, coordinator, routeMode ->
            buildSharedHttpClient(
                store = sessionStore,
                userAgent = userAgent,
                dns = dns,
                dohTransport = transport,
                failoverCoordinator = coordinator,
                anycastPool = anycastPool,
                routeMode = routeMode,
            )
        },
    )

    /** 动态分发的全局 Call.Factory：新发起的请求永远自动获取当前最新代次的 Client */
    val dynamicCallFactory: okhttp3.Call.Factory = dohCoordinator

    /** 保持向后兼容：全局共享的当前活跃 Client */
    val httpClient: okhttp3.OkHttpClient
        get() = dohCoordinator.currentClient

    val userApiKeyAuthService: UserApiKeyAuthService = UserApiKeyAuthService(
        sessionStore,
        dohCoordinator.currentClient,
        diagnostics,
        // 网页授权兑换发生在 App 内的原生网络栈，外部浏览器里的 cf_clearance 带不过来。
        // 撞盾时复用共享 WebView 静默过一次，比直接抛"获取 CSRF 失败"可靠得多。
        silentRevalidate = { reason -> browser.clearChallenge(reason) },
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .callFactory(dynamicCallFactory)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory(enforceMinimumFrameDelay = true))
            } else {
                add(GifDecoder.Factory(enforceMinimumFrameDelay = true))
            }
            add(SvgDecoder.Factory())
        }
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    private val okHttpTransport: OkHttpTransport = OkHttpTransport(
        sessionStore,
        browser,
        dohCoordinator.currentClient,
        diagnostics,
        epochLabel(dohCoordinator.currentEpoch.get()),
    )
    private val webViewTransport: WebViewFetchTransport = WebViewFetchTransport(browser, diagnostics)

    /** 最近一次退到后台的时间（elapsedRealtime）；0 表示当前在前台或尚未退过后台。 */
    @Volatile private var backgroundSince = 0L

    @Volatile private var lastShouldPauseDoh: Boolean? = null

    init {
        dohCoordinator.onEpochChanged = { epoch ->
            val label = epochLabel(epoch)
            okHttpTransport.updateClient(epoch.client, label)
            userApiKeyAuthService.updateClient(epoch.client)
            diagnostics.log("网络链路: 切换到 $label")
        }
        networkMonitor.onDefaultNetworkChanged = {
            diagnostics.log("网络链路: 默认网络已切换，回收连接")
            dohCoordinator.recycleConnections("默认网络切换")
            updateWebViewProxyState()
        }
        updateWebViewProxyState()
    }

    /**
     * 根据当前网络环境自动更新 WebView 代理挂载状态（Task 3.1 / 3.5）。
     * 检测到系统 VPN/代理开启时，主动释放本地代理，让渡系统网络；纯直连时挂载本地分片代理。
     * 并在代理与直连状态切换时，自动清除旧网络出口下的 cf_clearance，避免携带异地 IP 凭据撞盾。
     */
    fun updateWebViewProxyState() {
        val shouldPause = networkMonitor.detect().shouldPauseDoh
        val previousPause = lastShouldPauseDoh
        if (previousPause != null && previousPause != shouldPause) {
            val oldFp = sessionStore.currentCfClearanceFp()
            if (oldFp != "-") {
                sessionStore.clearCookie(Config.CF_CLEARANCE_COOKIE)
                sessionStore.clearChallengedClearances()
                diagnostics.log("网络链路: 出口代理状态切换(shouldPause: $previousPause -> $shouldPause)，重置旧 cf_clearance(fp=$oldFp) 避免异地 IP 撞盾")
            }
        }
        lastShouldPauseDoh = shouldPause
        if (shouldPause) {
            LocalProxyServer.clearWebViewProxyOverride()
        } else {
            LocalProxyServer.applyWebViewProxyOverride(localProxyServer.port)
        }
    }

    fun onAppBackground() {
        backgroundSince = android.os.SystemClock.elapsedRealtime()
        dohCoordinator.setAppForeground(false)
    }

    /**
     * 后台久置后回前台：期间 NAT 表项可能已过期，池里的旧连接复用会一路超时，先整体回收。
     * 必须在回前台的首批请求发出之前调用。
     */
    fun onAppForeground() {
        dohCoordinator.setAppForeground(true)
        val since = backgroundSince
        backgroundSince = 0L
        if (since == 0L) return
        val idleMs = android.os.SystemClock.elapsedRealtime() - since
        if (idleMs < STALE_CONNECTION_IDLE_MS) return
        diagnostics.log("网络链路: 后台 ${idleMs / 60_000L} 分钟后回前台，回收连接")
        dohCoordinator.recycleConnections("后台久置回前台")
    }

    fun transport(path: NetworkPath): Transport = when (path) {
        NetworkPath.OKHTTP -> okHttpTransport
        NetworkPath.WEBVIEW -> webViewTransport
    }

    val topicRepository: org.linuxdo.android.data.TopicRepository by lazy {
        org.linuxdo.android.data.TopicRepository(
            transportProvider = { transport(NetworkPath.OKHTTP) },
            diagnostics = diagnostics,
            reading = reading,
            sessionStore = sessionStore,
            fallbackTransportProvider = { transport(NetworkPath.WEBVIEW) },
            httpClientProvider = { httpClient },
        )
    }
    /**
     * 重建或刷新 OkHttp 客户端（DoH 偏好设置变更或手动切换后调用）。
     */
    fun rebuildHttpClient() {
        dohCoordinator.refresh()
        if (sessionStore.isDohEnabled()) {
            dohCoordinator.triggerSmartRouteEvaluation()
        }
    }

    companion object {
        /** 与共享连接池的 keep-alive 对齐：后台超过这么久，池里的连接就不再可信。 */
        private const val STALE_CONNECTION_IDLE_MS = 5 * 60_000L

        private fun epochLabel(epoch: org.linuxdo.android.net.TransportEpoch): String =
            "gen=${epoch.generation} " + if (epoch.transport != null) "${epoch.routeMode.label}(${epoch.server.name})" else "系统网络"

        /**
         * 全应用共用同一个 UA。首次运行时由系统 WebView 的默认 UA 规整成 Chrome 移动端的形态
         * (去掉 "; wv" 与 "Version/4.0"),并持久化——WebView 升级也不改变它,
         * 因为 cf_clearance 与 UA 绑定,UA 漂移会触发重新挑战。
         */
        fun resolveUserAgent(context: Context, store: SessionStore): String {
            val stored = store.userAgentOrNull()
            // 旧版本把设备段强行改成固定的 Android 10; K。它会与 WebView 自动
            // 发送的 Client Hints 不一致，CF 可能将同一会话视为指纹漂移。
            if (!stored.isNullOrBlank() && !stored.contains("Android 10; K")) return stored
            val raw = try {
                WebSettings.getDefaultUserAgent(context)
            } catch (_: Throwable) {
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            val normalized = normalize(raw)
            store.saveUserAgent(normalized)
            return normalized
        }

        fun normalize(raw: String): String = raw
            .replace(Regex("[;\\s]*\\bwv\\b[;\\s]*(?=\\))"), "")
            .replace(Regex("Version/[^ ]+ *"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}

private fun installCrashHandler(context: Context, diagnostics: Diagnostics) {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            diagnostics.recordCrash(thread, throwable, context)
        } catch (_: Throwable) {
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}


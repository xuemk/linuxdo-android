package org.linuxdo.android.net

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Cookie
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.linuxdo.android.Config
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.net.anycast.AnycastDns
import org.linuxdo.android.net.anycast.AnycastPool

/**
 * 全应用共用的 HTTP 客户端。
 *
 * 图片(Coil)和 JSON 请求必须走同一个客户端:cf_clearance 与 UA 绑定,
 * 图片请求若用了 Coil 默认客户端(不同 UA、没有 Cookie),会被 Cloudflare 直接拦掉,
 * 结果就是列表里头像全空。
 *
 * @param store     会话 Cookie 存储
 * @param userAgent 全局统一 UA
 * @param dns          可选的自定义 DNS 实现（注入 DoH 时传入 AdaptiveDohDns 实例；null 表示使用系统默认 DNS）
 * @param dohTransport 可选的原生 DoH + ECH TLS 传输层（注入后替换底层 Socket 与 TLS 握手）
 * @param anycastPool  可选的内置 Anycast IP 池；DoH 开关关闭时用它接管目标域名解析（零 DNS）
 */
fun buildSharedHttpClient(
    store: SessionStore,
    userAgent: String,
    dns: Dns? = null,
    dohTransport: DohTransport? = null,
    failoverCoordinator: DohFailoverCoordinator? = null,
    anycastPool: AnycastPool? = null,
    routeMode: NetworkRouteMode = NetworkRouteMode.DIRECT,
): OkHttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 24
    }
    val connectionPool = ConnectionPool(24, 5, TimeUnit.MINUTES)
    val builder = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .connectTimeout(Config.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // 读超时保持较长:Coil 的图片请求复用同一个客户端,大图需要时间读完。
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)

    if (failoverCoordinator != null) {
        // 应用级故障上报与幂等单次重试拦截器（置于最外层，感知 DNS/连接/TLS 全阶段）
        builder.addInterceptor(DohFailoverInterceptor(failoverCoordinator))
    }

    if (dohTransport != null) {
        // 原生 ECH + TLS 传输配置：注入 DNS、SocketFactory、SSLSocketFactory (Rustls ECH)
        dohTransport.configure(builder)
        // 智能双轨与直连优先：
        // 当 DoH 开关关闭，或者智能调度仲裁为 DIRECT 模式时，由内置 Anycast IP 池接管目标域名解析（零额外 DNS 往返）；
        // 仲裁为 DOH 模式时，保留 DohTransport 自带的 DoH 动态解析。
        val shouldUseDirect = !store.isDohEnabled() || routeMode == NetworkRouteMode.DIRECT
        if (anycastPool != null && shouldUseDirect) {
            builder.dns(AnycastDns(anycastPool, fallback = dohTransport.dns))
        }
    } else if (dns != null) {
        builder.dns(dns)
    }

    return builder
        .addInterceptor { chain ->
            val request = chain.request()
            val session = request.tag(ActiveSessionRequest::class.java)
                ?: ActiveSessionRequest(store.sessionGeneration)
            chain.proceed(request.newBuilder().tag(ActiveSessionRequest::class.java, session).build())
        }
        .addInterceptor(SiteImageGateInterceptor)
        .addNetworkInterceptor(
            SessionHeaderInterceptor(
                store,
                userAgent,
                transportLabel = when {
                    dohTransport != null -> if (!store.isDohEnabled() || routeMode == NetworkRouteMode.DIRECT) "Anycast直连" else "DoH解析"
                    dns != null -> "DoH-DNS"
                    else -> "系统网络"
                },
            ),
        )
        .build()
}

/**
 * 结合设置开关以及是否开启 VPN/代理综合判断，解析出当前应走哪个 HTTP 客户端。
 *
 * 判定矩阵：
 * 1. 优先复用 [AppContainer] 的当前活跃客户端（若实例存在）；
 *    - 进程存活时，协调器已持续监听 VPN 状态、用户开关与代次自动故障转移，享有活跃连接池和 ECH 会话复用；
 * 2. 兜底与静态独立唤醒路径：
 *    - 用户开关未开启 DoH (`!sessionStore.isDohEnabled()`) -> 走普通系统网络；
 *    - 检测到 VPN 或系统代理开启 (`networkMonitor.detect().shouldPauseDoh`) -> 自动暂停 DoH，走系统代理网络；
 *    - 开启 DoH 且处于无代理直连网络 -> 必须走原生 DoH + ECH TLS 传输通道（构建失败则降级纯 DNS）。
 */
fun resolveActiveHttpClient(
    context: Context,
    sessionStore: SessionStore,
): OkHttpClient {
    val app = context.applicationContext as? org.linuxdo.android.App
    val container = app?.container
    if (container != null) {
        return container.httpClient
    }

    val userAgent = org.linuxdo.android.AppContainer.resolveUserAgent(context, sessionStore)
    val isDohSwitchOn = sessionStore.isDohEnabled()
    val isVpnOrProxyActive = NetworkStateMonitor(context).detect().shouldPauseDoh
    val forceOverProxy = sessionStore.isDohForceOverProxyEnabled()

    // 综合判断：只有设置开启且（无 VPN/代理直连，或用户开启了代理下保持 DoH）时，才走 DoH + ECH
    val shouldUseDoh = isDohSwitchOn && (!isVpnOrProxyActive || forceOverProxy)

    val transport = if (shouldUseDoh) {
        buildActiveDohTransport(sessionStore)
    } else {
        null
    }

    val dns = if (shouldUseDoh && transport == null) {
        buildDohDns(sessionStore)
    } else {
        null
    }

    return buildSharedHttpClient(
        store = sessionStore,
        userAgent = userAgent,
        dns = dns,
        dohTransport = transport,
    )
}

/**
 * 构建仅用于 DoH 探测与 Bootstrap 连接的底层客户端。
 *
 * 此客户端：
 * - 不注入 DoH（避免递归依赖）
 * - 不注入 Cookie/UA（减少指纹）
 * - 超时参数与探测需求匹配（连接 5s，读 5s）
 *
 * 由 [AppContainer] 在初始化 DoH 时传入 [AdaptiveDohDns] 使用。
 */
fun buildBootstrapClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

/** 给站内请求统一注入固定 UA 与会话 Cookie。 */
private class SessionHeaderInterceptor(
    private val store: SessionStore,
    private val userAgent: String,
    private val transportLabel: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val isLinuxDoHost = host == Config.HOST || host.endsWith(".${Config.HOST}")
        val isChallengePlatformHost = host == CHALLENGES_HOST || host.endsWith(".$CHALLENGES_HOST")
        if ((!isLinuxDoHost && !isChallengePlatformHost) || !original.url.isHttps) {
            return chain.proceed(original.newBuilder().removeHeader("Cookie").removeHeader("X-CSRF-Token").build())
        }

        val generation = original.tag(ActiveSessionRequest::class.java)?.generation
            ?: store.sessionGeneration
        val sendCookies = original.tag(ActiveSessionRequest::class.java)?.sendCookies != false
        fun checkSession() {
            if (generation != store.sessionGeneration || store.sessionChanging) {
                throw IOException("会话已切换，请重新操作")
            }
        }
        checkSession()

        val builder = original.newBuilder().header("User-Agent", store.userAgentOrNull() ?: userAgent)
        val siteReferer = when (host) {
            "credit.linux.do" -> "https://credit.linux.do/home"
            "cdk.linux.do" -> "https://cdk.linux.do/dashboard"
            else -> null
        }
        if (siteReferer != null) {
            if (original.header("Origin") == null) {
                builder.header("Origin", "https://$host")
            }
            if (original.header("Referer") == null) {
                builder.header("Referer", siteReferer)
            }
        } else if (isLinuxDoHost && original.header("Referer") == null) {
            builder.header("Referer", "${Config.BASE_URL}/")
        }
        val encodedPath = original.url.encodedPath
        val isPublicStaticIcon = isLinuxDoHost && (
            encodedPath.startsWith("/user_avatar/") ||
                encodedPath.startsWith("/letter_avatar") ||
                encodedPath.startsWith("/images/emoji/") ||
                encodedPath.startsWith("/svg-sprite/")
            )
        var rawWebCookieHeader = ""
        val cookieHeader = if (!sendCookies) {
            ""
        } else if (isPublicStaticIcon) {
            // 对齐 fluxdo：公开静态头像/表情仅只读携带 cf_clearance 等过盾与匿名 Cookie，
            // 剥离 _t / _forum_session 登录凭据，既避免消耗源站容器的登录用户限流配额，
            // 又避免并发加载图标时阻塞主线程或触发持久化写入。
            checkSession()
            val manager = runCatching { CookieManager.getInstance() }.getOrNull()
            rawWebCookieHeader = manager?.getCookie(original.url.toString()).orEmpty()
            val webCookies = SessionStore.parseCookieHeader(rawWebCookieHeader).toMutableMap()
            val clearanceName = Config.CF_CLEARANCE_COOKIE
            val rawClearances = rawWebCookieHeader.split(';')
                .map { it.trim() }
                .filter { it.substringBefore('=').trim().equals(clearanceName, ignoreCase = true) }
                .map { it.substringAfter('=').trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (rawClearances.size > 1) {
                val bestRaw = rawClearances.lastOrNull { !store.isClearanceChallenged(it) }
                if (bestRaw != null) {
                    webCookies[clearanceName] = bestRaw
                } else {
                    webCookies.remove(clearanceName)
                }
            } else if (rawClearances.size == 1 && store.isClearanceChallenged(rawClearances.single())) {
                webCookies.remove(clearanceName)
            }
            val publicCookies = webCookies.filterKeys { key ->
                !key.equals(Config.SESSION_COOKIE, ignoreCase = true) &&
                    !key.equals("_forum_session", ignoreCase = true)
            }
            SessionStore.toCookieHeader(publicCookies)
        } else {
            runBlocking(Dispatchers.Main) {
                checkSession()
                rawWebCookieHeader = CookieManager.getInstance().getCookie(original.url.toString()).orEmpty()
                if (host == Config.HOST) SessionStore.toCookieHeader(store.syncFromWebView())
                else rawWebCookieHeader
            }
        }
        val sentClearance = SessionStore.parseCookieHeader(cookieHeader)[Config.CF_CLEARANCE_COOKIE]
        original.tag(ActiveSessionRequest::class.java)?.sentClearanceFp = NetDiagnostics.fingerprint(sentClearance)
        builder.removeHeader("Cookie")
        if (isPublicStaticIcon || !sendCookies) {
            builder.removeHeader("X-CSRF-Token")
        }
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        val outgoing = builder.build()
        val response = chain.proceed(outgoing)
        if (shouldTraceCfFlow(encodedPath, response.code)) {
            val webClearance = SessionStore.parseCookieHeader(rawWebCookieHeader)[Config.CF_CLEARANCE_COOKIE]
            val bodyFeatures = if (response.code >= 400) {
                runCatching { NetDiagnostics.bodyFeatures(response.peekBody(8_192L).string()) }.getOrDefault("读取失败")
            } else {
                "-"
            }
            Log.i(
                CF_TRACE_TAG,
                "HTTP ${original.method} $encodedPath -> ${response.code}" +
                    " sentCf=${NetDiagnostics.fingerprint(sentClearance)}" +
                    " webCf=${NetDiagnostics.fingerprint(webClearance)}" +
                    " mitigated=${response.header("cf-mitigated") ?: "-"}" +
                    " ray=${response.header("cf-ray") ?: "-"}" +
                    " body=$bodyFeatures route=$transportLabel",
            )
        }
        runCatching { NetDiagnostics.traceOkHttp(chain, outgoing, response, cookieHeader, rawWebCookieHeader, transportLabel) }
        if (isPublicStaticIcon || !sendCookies) {
            return response
        }
        try {
            // 每次重定向都在进入下一跳前落 Cookie；普通 API 和 OAuth 共用此入口。
            runBlocking(Dispatchers.Main) {
                checkSession()
                store.applyResponseCookies(
                    Cookie.parseAll(original.url, response.headers),
                    requestUrl = original.url.toString(),
                    requestGeneration = generation,
                    sentCookies = SessionStore.parseCookieHeader(cookieHeader),
                )
            }
            return response
        } catch (error: Exception) {
            response.close()
            throw error
        }
    }

    private fun shouldTraceCfFlow(path: String, status: Int): Boolean =
        status in setOf(401, 403, 429) ||
            path == "/session/csrf" ||
            path == "/session/csrf.json" ||
            path == "/session.json" ||
            path.startsWith("/session/email-login/") ||
            path == "/u/email-login.json" ||
            path == "/captcha/hcaptcha/create.json" ||
            path == "/hcaptcha/create.json"

    private companion object {
        const val CF_TRACE_TAG = "CF-TRACE"
        const val CHALLENGES_HOST = "challenges.cloudflare.com"
    }
}

data class ActiveSessionRequest(
    val generation: Long,
    @Volatile var sentClearanceFp: String = "-",
    val sendCookies: Boolean = true,
)

/**
 * 图片失败是否值得稍后自动重试：门禁拒绝、网络抖动、429/408/5xx。
 * 404、403（受保护附件 / 盾拦截）、解码失败等确定性错误返回 false，交给调用方降级或占位，不去反复撞墙。
 */
fun isTransientImageError(error: Throwable?): Boolean = when (error) {
    is coil.network.HttpException -> error.response.code.let { it == 408 || it == 429 || it >= 500 }
    is IOException -> true
    else -> false
}

/**
 * 等到下一次图片重试的时机：先等图片门禁冷却结束，再叠加指数退避与随机抖动，
 * 避免一屏失败的图在同一刻集体重发，重新把 Cloudflare 计数打满。
 */
suspend fun awaitImageRetrySlot(attempt: Int) {
    val backoffMs = 2_000L shl attempt.coerceIn(0, 3)
    val jitterMs = kotlin.random.Random.nextLong(400L, 1_800L)
    kotlinx.coroutines.delay(maxOf(SiteImageGateInterceptor.cooldownRemainingMs(), backoffMs) + jitterMs)
}

/** 每张图自动重试的上限，超过后停在占位，等用户手动重载或重新进入页面。 */
const val IMAGE_AUTO_RETRY_LIMIT = 2

/**
 * 站内图片（头像 / 字母头像 / 上传图）的独立计量池。
 *
 * 对齐 fluxdo DioHttpClient 三通道并发隔离：
 * - EMOJI 通道 12 槽（KB 级极小图，RTT 主导）
 * - AVATAR 通道 6 槽（对齐 fluxdo contentSemaphore = 6，避免十几张头像瞬时齐发冲破 CF 边缘速率阈值）
 * - MEDIA 通道 4 槽（正文上传大图/附件，独立槽位避免饿死头像）
 */
internal object SiteImageGateInterceptor : Interceptor {
    /** 门禁主动拒绝（排队超时 / 冷却中）。界面据此判定为瞬时失败，冷却后再重试，而不是降级或放弃。 */
    class ThrottledException(message: String) : IOException(message)

    private const val EMOJI_CONCURRENT = 12
    private const val AVATAR_CONCURRENT = 6
    private const val MEDIA_CONCURRENT = 4
    private const val ACQUIRE_TIMEOUT_MS = 15_000L
    private const val DEFAULT_COOLDOWN_MS = 4_000L
    private const val MAX_COOLDOWN_MS = 10_000L

    private const val EMOJI_PREFIX = "/images/emoji/"
    private val avatarPrefixes = listOf("/user_avatar/", "/letter_avatar")
    private const val UPLOADS_PREFIX = "/uploads/"

    private val emojiPermits = java.util.concurrent.Semaphore(EMOJI_CONCURRENT, true)
    private val avatarPermits = java.util.concurrent.Semaphore(AVATAR_CONCURRENT, true)
    private val mediaPermits = java.util.concurrent.Semaphore(MEDIA_CONCURRENT, true)
    @Volatile private var cooldownUntil = 0L

    private enum class ImageCategory {
        NONE, EMOJI, AVATAR, MEDIA
    }

    private fun categorize(url: okhttp3.HttpUrl): ImageCategory {
        val host = url.host
        if (host != Config.HOST && !host.endsWith(".${Config.HOST}")) return ImageCategory.NONE
        val path = url.encodedPath
        return when {
            path.startsWith(EMOJI_PREFIX) -> ImageCategory.EMOJI
            avatarPrefixes.any { path.startsWith(it) } -> ImageCategory.AVATAR
            path.startsWith(UPLOADS_PREFIX) -> ImageCategory.MEDIA
            else -> ImageCategory.NONE
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val category = categorize(request.url)
        if (category == ImageCategory.NONE) return chain.proceed(request)
        checkCooldown()
        val semaphore = when (category) {
            ImageCategory.EMOJI -> emojiPermits
            ImageCategory.AVATAR -> avatarPermits
            ImageCategory.MEDIA -> mediaPermits
            ImageCategory.NONE -> avatarPermits
        }
        if (!semaphore.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw ThrottledException("站内图片排队超时")
        }
        val response = try {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            checkCooldown()
            chain.proceed(request)
        } finally {
            // 头部到达即释放：读 body 不占着并发名额
            semaphore.release()
        }
        if (response.code == 429) enterCooldown(response)
        return response
    }

    fun cooldownRemainingMs(): Long =
        (cooldownUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun checkCooldown() {
        val remaining = cooldownRemainingMs()
        if (remaining > 0L) throw ThrottledException("站内图片限流冷却中，还剩 ${remaining / 1_000L}s")
    }

    private fun enterCooldown(response: Response) {
        val cfChallenge = response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true
        val cooldownMs = HttpHeaderUtils.rateLimitCooldownMs(
            HttpHeaderUtils.parseRetryAfter(response.header("Retry-After")),
            if (cfChallenge) DEFAULT_COOLDOWN_MS * 2 else DEFAULT_COOLDOWN_MS,
        ).coerceAtMost(MAX_COOLDOWN_MS)
        val now = android.os.SystemClock.elapsedRealtime()
        val first = synchronized(this) {
            val wasCooling = now < cooldownUntil
            cooldownUntil = maxOf(cooldownUntil, now + cooldownMs)
            !wasCooling
        }
        // 隔离改进：图片自身进入冷却即可（头像优雅降级为文字占位头像），不连带锁死前台主 API 计量池
        if (!first) return
        NetDiagnostics.log(
            "站内图片限流(${if (cfChallenge) "CF质询" else "429"}): ${response.request.url.encodedPath}，" +
                "图片暂停 ${cooldownMs / 1_000L}s (与主API隔离)",
        )
    }
}

/**
 * 403/挑战定位用的脱敏诊断：Cookie 值、CSRF 一律只记 SHA-256 前 6 位指纹，绝不落明文。
 * 由 AppContainer 注入 [sink]；未注入时静默不记。
 */
object NetDiagnostics {
    @Volatile var sink: org.linuxdo.android.data.Diagnostics? = null

    private val throttle = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cfErrorCode = Regex("error code:?\\s*(10\\d\\d)", RegexOption.IGNORE_CASE)

    fun fingerprint(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { "%02x".format(it) }
    }

    /** 同名 Cookie 在原始 Cookie 头里出现的所有值指纹，用于发现根域/子域/分区副本并存。 */
    fun cookieFingerprints(rawHeader: String, name: String): List<String> =
        rawHeader.split(';').map { it.trim() }
            .filter { it.substringBefore('=') == name }
            .map { fingerprint(it.substringAfter('=')) }

    fun cookieNames(rawHeader: String): String =
        rawHeader.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }.joinToString(",")

    /** 响应体只提取特征标签，不记正文。 */
    fun bodyFeatures(body: String): String {
        val head = body.take(8_000)
        val tags = buildList {
            if (head.contains("BAD CSRF", ignoreCase = true)) add("BAD_CSRF")
            if (head.contains("Just a moment", ignoreCase = true)) add("JUST_A_MOMENT")
            if (head.contains("challenge-platform")) add("CHALLENGE_PLATFORM")
            if (head.contains("cf-chl", ignoreCase = true)) add("CF_CHL")
            if (head.contains("turnstile", ignoreCase = true)) add("TURNSTILE")
            if (head.contains("Attention Required", ignoreCase = true)) add("ATTENTION_REQUIRED")
            if (head.contains("Sorry, you have been blocked", ignoreCase = true)) add("CF_BLOCKED")
            cfErrorCode.find(head)?.let { add("CF_" + it.groupValues[1]) }
            if (head.contains("not_logged_in", ignoreCase = true)) add("NOT_LOGGED_IN")
            if (head.contains("invalid_access", ignoreCase = true)) add("INVALID_ACCESS")
            if (head.trimStart().startsWith("{")) add("JSON")
            if (head.contains("<html", ignoreCase = true)) add("HTML")
        }
        return if (tags.isEmpty()) "len=${body.length}" else tags.joinToString("|") + ",len=${body.length}"
    }

    fun log(message: String) {
        sink?.log("[诊断] $message")
    }

    /** 同一类 GET 失败 60 秒内只记一次，写请求每次都记。 */
    private fun shouldTrace(method: String, path: String, status: Int): Boolean {
        if (method != "GET") return true
        if (status < 400) return false
        val key = "$method $path $status"
        val now = android.os.SystemClock.elapsedRealtime()
        val last = throttle[key]
        if (last != null && now - last < 60_000L) return false
        throttle[key] = now
        if (throttle.size > 256) throttle.clear()
        return true
    }

    fun traceOkHttp(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        response: Response,
        sentCookieHeader: String,
        rawWebCookieHeader: String,
        transportLabel: String,
    ) {
        if (sink == null) return
        val path = request.url.encodedPath
        if (!shouldTrace(request.method, path, response.code)) return
        val connection = chain.connection()
        val route = connection?.route()
        val proxyType = route?.proxy?.type()?.name ?: "?"
        val peer = route?.socketAddress?.address?.hostAddress ?: "?"
        val handshake = runCatching { connection?.handshake() }.getOrNull()
        val features = if (response.code >= 400) {
            runCatching { bodyFeatures(response.peekBody(16_384).string()) }.getOrDefault("peek失败")
        } else "-"
        val setCookieNames = response.headers("Set-Cookie").joinToString(",") { it.substringBefore('=') }.ifBlank { "-" }
        val webClearance = cookieFingerprints(rawWebCookieHeader, Config.CF_CLEARANCE_COOKIE)
        val sentClearance = cookieFingerprints(sentCookieHeader, Config.CF_CLEARANCE_COOKIE)
        val webToken = cookieFingerprints(rawWebCookieHeader, Config.SESSION_COOKIE)
        val sentToken = cookieFingerprints(sentCookieHeader, Config.SESSION_COOKIE)
        log(
            "A ${request.method} ${request.url.host}$path -> ${response.code}" +
                " | cf-mitigated=${response.header("cf-mitigated") ?: "-"}" +
                " cf-ray=${response.header("cf-ray") ?: "-"}" +
                " server=${response.header("server") ?: "-"}" +
                " ctype=${response.header("content-type")?.substringBefore(';') ?: "-"}" +
                " body=$features" +
                " | 发出cf_clearance=${sentClearance.size}份${sentClearance}" +
                " WebView原始cf_clearance=${webClearance.size}份${webClearance}" +
                " 发出_t=${sentToken} WebView_t=${webToken}" +
                " hasCsrf=${!request.header("X-CSRF-Token").isNullOrBlank()}" +
                " 发出Cookie名=[${cookieNames(sentCookieHeader)}]" +
                " Set-Cookie=[$setCookieNames]" +
                " | 链路 mode=$transportLabel proxy=$proxyType peer=$peer proto=${connection?.protocol() ?: "?"}" +
                " tls=${handshake?.tlsVersion?.javaName ?: "?"} cipher=${handshake?.cipherSuite?.javaName ?: "?"}" +
                " ua=${fingerprint(request.header("User-Agent"))}",
        )
    }
}

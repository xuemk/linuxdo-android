package org.linuxdo.android.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.webkit.CookieManager
import android.os.Looper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.Json
import org.linuxdo.android.Config

internal object NoExcerptModeMigration {
    private const val KEY_APPLIED = "feed_no_excerpt_default_v1"

    @Synchronized
    fun applyTo(preferences: SharedPreferences) {
        if (preferences.getBoolean(KEY_APPLIED, false)) return
        preferences.edit()
            .putString(SessionStore.KEY_FEED_DISPLAY_MODE, "no_excerpt")
            .remove(SessionStore.KEY_FEED_GRAPHIC_MODE)
            .putBoolean(KEY_APPLIED, true)
            .apply()
    }
}

/**
 * 会话 Cookie 的加密持久化,同时负责与 WebView CookieManager 双向同步。
 *
 * 约定:WebView 的 CookieManager 是"现场真相";只要它非空就以它为准并回写加密存储,
 * 只有它为空(例如 WebView 数据被清)时才用加密存储恢复。
 * 涉及 CookieManager 的方法请在主线程调用。
 */
class SessionStore(context: Context) : org.linuxdo.android.net.DohConfigProvider {
    private val prefs: SharedPreferences = createPrefs(context)
    @Volatile var cookieVersion: Long = 0L
        private set
    @Volatile var sessionGeneration: Long = 1L
        private set
    @Volatile var sessionChanging: Boolean = false
        private set
    @Volatile var verificationRequired: Boolean = false
        private set
    private val cookieMutationGate = Mutex()
    private val challengedClearanceLock = Any()
    private val challengedClearances = LinkedHashSet<String>()
    private val challengedClearanceFps = LinkedHashSet<String>()
    private var challengedClearancePauseUntil = 0L
    private val clearanceRecoveryLock = Any()
    private val clearanceRecoveryIds = mutableSetOf<Long>()
    private var nextClearanceRecoveryId = 0L

    fun requireVerification() { verificationRequired = true }
    fun completeVerification() { verificationRequired = false }

    fun beginClearanceRecovery(): Long = synchronized(clearanceRecoveryLock) {
        (++nextClearanceRecoveryId).also { clearanceRecoveryIds.add(it) }
    }

    fun endClearanceRecovery(id: Long) {
        synchronized(clearanceRecoveryLock) { clearanceRecoveryIds.remove(id) }
    }

    fun isClearanceRecoveryInProgress(): Boolean = synchronized(clearanceRecoveryLock) {
        verificationRequired || clearanceRecoveryIds.isNotEmpty()
    }

    /**
     * 登记本会话内已被 Cloudflare 挑战（403/429 challenge）判废的 cf_clearance 值或指纹。
     * 有界保留最近 [MAX_CHALLENGED_CLEARANCES] 枚，供多变体选优与换值重放时排除已知死值。
     */
    fun noteClearanceChallenged(
        valueOrFp: String?,
        host: String = Config.HOST,
        source: String = "主站请求",
    ) {
        if (!host.equals(Config.HOST, ignoreCase = true)) return
        val trimmed = valueOrFp?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "-") return
        val isFingerprint = trimmed.length == 6 && trimmed.all { it in '0'..'9' || it in 'a'..'f' }
        val fp = if (isFingerprint) trimmed else org.linuxdo.android.net.NetDiagnostics.fingerprint(trimmed)
        val firstChallenge = synchronized(challengedClearanceLock) {
            val first = fp != "-" && !challengedClearanceFps.contains(fp)
            if (!isFingerprint) {
                if (!challengedClearances.contains(trimmed)) {
                    if (challengedClearances.size >= MAX_CHALLENGED_CLEARANCES) {
                        val it = challengedClearances.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        }
                    }
                    challengedClearances.add(trimmed)
                }
            }
            if (fp != "-" && !challengedClearanceFps.contains(fp)) {
                challengedClearancePauseUntil = SystemClock.elapsedRealtime() + CHALLENGED_CLEARANCE_PAUSE_MS
                if (challengedClearanceFps.size >= MAX_CHALLENGED_CLEARANCES) {
                    val it = challengedClearanceFps.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                challengedClearanceFps.add(fp)
            }
            first
        }
        if (firstChallenge) {
            val safeSource = source.substringBefore('?').substringBefore('#')
                .replace('\r', ' ').replace('\n', ' ').take(160)
            org.linuxdo.android.net.NetDiagnostics.log(
                "主站 cf_clearance 首次判废: fp=$fp host=${Config.HOST} source=$safeSource",
            )
        }
    }

    /** 判断某枚 cf_clearance（原始值或 6 位指纹）是否在本会话内已被挑战判死。 */
    fun isClearanceChallenged(valueOrFp: String?): Boolean {
        val trimmed = valueOrFp?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "-") return false
        val isFingerprint = trimmed.length == 6 && trimmed.all { it in '0'..'9' || it in 'a'..'f' }
        val fp = if (isFingerprint) trimmed else org.linuxdo.android.net.NetDiagnostics.fingerprint(trimmed)
        return synchronized(challengedClearanceLock) {
            (!isFingerprint && trimmed in challengedClearances) || (fp != "-" && fp in challengedClearanceFps)
        }
    }

    /** 验证前在主线程记录全部旧值，包含重复 Cookie 与已判废值，不能使用已过滤的有效值作为基线。 */
    fun cfClearancesForVerification(): Set<String> {
        val manager = CookieManager.getInstance()
        val values = listOf(manager.getCookie(Config.BASE_URL), manager.getCookie("${Config.BASE_URL}/"))
            .flatMap { it.orEmpty().split(';') }
            .map { it.trim() }
            .filter { it.substringBefore('=').trim().equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) }
            .map { it.substringAfter('=', "").trim() }
            .filter { it.isNotBlank() }
            .toMutableSet()
        storedCookies()[Config.CF_CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }?.let(values::add)
        synchronized(challengedClearanceLock) { values.addAll(challengedClearances) }
        return values
    }

    fun clearChallengedClearances() {
        synchronized(challengedClearanceLock) {
            challengedClearances.clear()
            challengedClearanceFps.clear()
            challengedClearancePauseUntil = 0L
        }
    }

    /**
     * 新凭据刚被挑战且尚无替代值时，后台探测短暂让行。
     * 同一旧值重复报错不续期；即使后续请求不再需要 clearance，也不会永久停掉探测。
     */
    fun shouldPauseBackgroundForChallengedClearance(): Boolean {
        val isPausing = synchronized(challengedClearanceLock) {
            SystemClock.elapsedRealtime() < challengedClearancePauseUntil
        }
        if (!isPausing) return false
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return true
        val activeClearances = listOf(manager.getCookie(Config.BASE_URL), manager.getCookie("${Config.BASE_URL}/"))
            .flatMap { it.orEmpty().split(';') }
            .map { it.trim() }
            .filter { it.substringBefore('=').trim().equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) }
            .map { it.substringAfter('=', "").trim() }
            .filter { it.isNotBlank() && !isClearanceChallenged(it) }
        return activeClearances.isEmpty()
    }

    /** 获取当前经选优后的权威 cf_clearance 指纹（无有效值时返回 "-"）。 */
    fun currentCfClearanceFp(): String {
        val clearance = syncFromWebView()[Config.CF_CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }
        return org.linuxdo.android.net.NetDiagnostics.fingerprint(clearance)
    }

    fun advanceSessionGeneration(): Long {
        clearChallengedClearances()
        return synchronized(clearanceRecoveryLock) {
            clearanceRecoveryIds.clear()
            verificationRequired = false
            ++sessionGeneration
        }
    }

    fun storedCookies(): Map<String, String> {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun savedAtMillis(): Long = prefs.getLong(KEY_SAVED_AT, 0L)

    fun userAgentOrNull(): String? = prefs.getString(KEY_USER_AGENT, null)

    fun saveUserAgent(userAgent: String) {
        prefs.edit().putString(KEY_USER_AGENT, userAgent).apply()
    }

    /**
     * 以 WebView 现场 Cookie 为准同步到加密存储,返回当前有效的 Cookie。
     *
     * **合并,不是整份替换。** WebView 里缺某个 Cookie 不等于服务端撤销了它 ——
     * Cloudflare 会给匿名请求也种上 __cf_bm/cf_clearance,所以"WebView 非空"完全可能是
     * "只剩 CF 的 Cookie、_t 已经丢了"(系统清理 WebView 存储、进程被杀、过挑战都可能)。
     * 整份覆盖会把存储里完好的 _t 抹掉,而那是最后一份备份,抹掉就真掉登录且不可恢复。
     * 真正的撤销走 applyResponseCookies 的过期分支,或 clear()/clearSessionCookie()。
     *
     * **重要修复**：cf_clearance 等 CF Cookie 不再持久化保存。
     * 它们与网络环境/TLS 指纹强绑定，VPN 开关、DoH 节点切换、进程重启后环境变化，
     * 旧 clearance 失效会导致 403 循环。让它们保持 WebView 的临时状态，
     * 失效时由 Cloudflare 重新验证，而不是反复使用一枚已废弃的凭据。
     */
    fun syncFromWebView(): Map<String, String> {
        // 从持久化存储中排除 CF Cookie（不可跨环境复用）
        val stored = storedCookies().filterKeys {
            !it.equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) && !it.startsWith("__cf") && !it.startsWith("_cf")
        }
        if (sessionChanging) return stored
        val manager = CookieManager.getInstance()
        val rawHeader = manager.getCookie(Config.BASE_URL).orEmpty()
        val web = parseCookieHeader(rawHeader).toMutableMap()
        // 旧版可能留下同名 Domain/host-only 副本。优先读浏览器的结构化属性，
        // 无法确认时保留已有凭据，不按扁平 Cookie 头的偶然顺序覆盖它。
        val structured = runCatching { if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            CookieManagerCompat.getCookieInfo(manager, Config.BASE_URL)
                .mapNotNull { Cookie.parse(Config.BASE_URL.toHttpUrl(), it) }
        } else emptyList() }.getOrDefault(emptyList())
        for (name in listOf(Config.SESSION_COOKIE, FORUM_SESSION_COOKIE)) {
            val candidates = structured.filter { it.name == name && it.hostOnly && it.path == "/" }
                .map { it.value }.distinct()
            val rawValues = rawHeader.split(';')
                .map { it.trim() }.filter { it.substringBefore('=') == name }
                .map { it.substringAfter('=') }.distinct()
            when {
                candidates.size == 1 -> web[name] = candidates.single()
                rawValues.size > 1 && stored[name] in rawValues -> web[name] = stored.getValue(name)
                rawValues.size > 1 -> web.remove(name)
            }
        }
        // 对齐 Fluxdo BoundarySync + CfClearanceAuthority：
        // 当存在多个同名 cf_clearance 变体（如 .linux.do / linux.do / CHIPS Partitioned）时，
        // 排除已过期和本会话已被撞死的变体，按 expiresAt 最晚 + hostOnly + path 长度选出唯一权威值，
        // 杜绝 parseCookieHeader 按扁平字符串末尾顺序盲目用旧值/死值覆盖新值。
        val now = System.currentTimeMillis()
        val clearanceName = Config.CF_CLEARANCE_COOKIE
        val structuredClearances = structured.filter {
            it.name.equals(clearanceName, ignoreCase = true) && it.value.isNotBlank()
        }
        val rawClearances = rawHeader.split(';')
            .map { it.trim() }
            .filter { it.substringBefore('=').trim().equals(clearanceName, ignoreCase = true) }
            .map { it.substringAfter('=').trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (structuredClearances.isNotEmpty()) {
            val bestClearance = structuredClearances
                .filter { it.expiresAt > now && !isClearanceChallenged(it.value) }
                .sortedWith(
                    compareByDescending<Cookie> { it.expiresAt }
                        .thenByDescending { it.hostOnly }
                        .thenByDescending { it.path.length }
                )
                .firstOrNull()
            if (bestClearance != null) {
                web[clearanceName] = bestClearance.value
            } else {
                web.remove(clearanceName)
            }
        } else if (rawClearances.isNotEmpty()) {
            val bestRaw = rawClearances.lastOrNull { !isClearanceChallenged(it) }
            if (bestRaw != null) {
                web[clearanceName] = bestRaw
            } else {
                web.remove(clearanceName)
            }
        }
        if (web.isEmpty()) return stored
        // 关键修复：过滤掉 CF Cookie，不保存到持久化存储
        val businessCookies = web.filterKeys {
            !it.equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) && !it.startsWith("__cf") && !it.startsWith("_cf")
        }
        val merged = stored + businessCookies
        if (merged != stored) save(merged)
        // 返回完整的 Cookie（包含 CF Cookie），供本次会话使用，但不持久化
        return stored + web
    }

    /**
     * 探测当前 WebView/Cookie 存储中是否持有有效的 Cloudflare 盾凭据（cf_clearance 或以 cf_ / __cf 开头）。
     */
    fun hasCfClearance(): Boolean {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false
        val raw = manager.getCookie(Config.BASE_URL).orEmpty()
        val parsed = parseCookieHeader(raw)
        return parsed.keys.any { key ->
            key.equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) ||
            key.startsWith("cf_", ignoreCase = true) ||
            key.startsWith("__cf", ignoreCase = true)
        }
    }

    /** 只在服务端确认当前账号后迁移旧 Domain 副本，不能在未确认时猜测有效 token。 */
    suspend fun normalizeVerifiedAuthCookies(generation: Long) = cookieMutationGate.withLock {
        if (sessionChanging || generation != sessionGeneration || plainPrefs.getBoolean("auth_cookie_host_only_v2", false)) return@withLock
        val manager = CookieManager.getInstance()
        val cookies = syncFromWebView()
        for (name in listOf(Config.SESSION_COOKIE, FORUM_SESSION_COOKIE)) {
            val value = cookies[name]?.takeIf { it.isNotBlank() } ?: continue
            for (domain in listOf(Config.HOST, ".${Config.HOST}")) {
                setWebCookie(manager, "$name=; Max-Age=0; Domain=$domain; Path=/; Secure; HttpOnly")
            }
            if (generation != sessionGeneration || sessionChanging) return@withLock
            check(setWebCookie(manager, "$name=$value; Path=/; Secure; HttpOnly; SameSite=Lax"))
            check(parseCookieHeader(manager.getCookie(Config.BASE_URL))[name] == value)
        }
        manager.flush()
        plainPrefs.edit().putBoolean("auth_cookie_host_only_v2", true).apply()
    }

    /**
     * 把加密存储里有、而 WebView 里缺的 Cookie 补回 WebView。
     *
     * 不能只在 WebView 完全为空时才恢复:CF 的 Cookie 会让 WebView 看起来"非空",
     * 而真正要紧的 _t 可能已经没了。只补缺失项,WebView 里已有的不动 —— 那些更新。
     *
     * [exclude] 用于排除本次不该回灌的 Cookie。登录链路要排除会话凭据:
     * 一枚失效的旧 _t 被灌回去,会让 Discourse 在已有会话状态下处理 /session.json。
     */
    fun restoreMissingToWebView(exclude: Set<String> = emptySet()) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val stored = storedCookies()
        if (stored.isEmpty()) return
        val web = parseCookieHeader(manager.getCookie(Config.BASE_URL))
        val missing = stored.filterKeys { key ->
            key !in web && key !in exclude && !key.equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) && !key.startsWith("__cf") && !key.startsWith("_cf")
        }
        if (missing.isEmpty()) return
        missing.forEach { (name, value) ->
            // Discourse 会话 Cookie (_t / _forum_session) 必须严格保持 host-only，绝不附加 Domain 属性，
            // 否则会造成 Domain Cookie 与 Host-only Cookie 分叉并存，导致服务端静默删除会话掉登录。
            val header = if (name == Config.SESSION_COOKIE || name == FORUM_SESSION_COOKIE) {
                "$name=$value; Path=/; Secure; HttpOnly"
            } else {
                "$name=$value; Path=/; Secure"
            }
            manager.setCookie(Config.BASE_URL, header)
        }
        manager.flush()
    }

    /**
     * 只丢掉会话凭据,保留 CF 等其他 Cookie。
     *
     * 服务端明确判定未登录时才调用。合并式同步会一直保着 _t,不主动清掉的话每次启动都会
     * 拿这个已经失效的 token 白跑一次 /session/current,还可能被重新塞回 WebView。
     */
    fun clearSessionCookie() {
        advanceSessionGeneration()
        save(storedCookies() - Config.SESSION_COOKIE)
        val manager = CookieManager.getInstance()
        val deletionHeaders = listOf(
            "${Config.SESSION_COOKIE}=; Max-Age=0; Path=/; Secure; HttpOnly",
            "${Config.SESSION_COOKIE}=; Max-Age=0; Domain=${Config.HOST}; Path=/; Secure; HttpOnly",
            "${Config.SESSION_COOKIE}=; Max-Age=0; Domain=.${Config.HOST}; Path=/; Secure; HttpOnly",
            "${Config.SESSION_COOKIE}=; Max-Age=0; Path=/",
            "${Config.SESSION_COOKIE}=; Max-Age=0; Domain=${Config.HOST}; Path=/",
        )
        deletionHeaders.forEach { manager.setCookie(Config.BASE_URL, it) }
        manager.flush()
    }

    /**
     * 403 诊断用的 Cookie 清单（主线程调用）：列出主站与 credit 子站上 CF 与会话 Cookie 的
     * 作用域属性和值指纹，用来确认是否存在根域/子域/分区副本并存。值只记指纹。
     */
    fun cookieInventory(): String {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return "CookieManager 不可用"
        val interesting = { name: String ->
            name == Config.SESSION_COOKIE || name == FORUM_SESSION_COOKIE ||
                name.startsWith("cf_", ignoreCase = true) || name.startsWith("__cf", ignoreCase = true) ||
                name.startsWith("_cf", ignoreCase = true)
        }
        val structuredSupported = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO) }.getOrDefault(false)
        val stored = storedCookies()
        return listOf(Config.BASE_URL, "https://credit.linux.do").joinToString(" || ") { url ->
            val entries = if (structuredSupported) {
                runCatching { CookieManagerCompat.getCookieInfo(manager, url) }.getOrDefault(emptyList())
                    .mapNotNull { raw ->
                        val name = raw.substringBefore('=').trim()
                        if (!interesting(name)) return@mapNotNull null
                        val value = raw.substringAfter('=').substringBefore(';')
                        val attrs = raw.split(';').drop(1).map { it.trim() }
                            .filter { attr ->
                                val key = attr.substringBefore('=').lowercase()
                                key in setOf("domain", "path", "samesite", "secure", "httponly", "partitioned", "expires", "max-age")
                            }
                            .joinToString(";")
                        "$name#${org.linuxdo.android.net.NetDiagnostics.fingerprint(value)}{${attrs.ifBlank { "host-only" }}}"
                    }
            } else {
                manager.getCookie(url).orEmpty().split(';').map { it.trim() }
                    .filter { interesting(it.substringBefore('=')) }
                    .map { "${it.substringBefore('=')}#${org.linuxdo.android.net.NetDiagnostics.fingerprint(it.substringAfter('='))}" }
            }
            "${url.removePrefix("https://")}: ${entries.joinToString(", ").ifBlank { "无" }}"
        } + " || 结构化=${structuredSupported} 存储_t=${org.linuxdo.android.net.NetDiagnostics.fingerprint(stored[Config.SESSION_COOKIE])}" +
            " 存储_forum_session=${org.linuxdo.android.net.NetDiagnostics.fingerprint(stored[FORUM_SESSION_COOKIE])}"
    }

    /**
     * 针对 BAD CSRF 的轻度校准：
     * 仅清理引起会话与 CSRF Token 失步的 _forum_session Cookie，
     * 绝不触动 cf_clearance 和 _t，避免破坏 TLS 盾与登录凭据。
     */
    fun calibrateForumSessionForBadCsrf(): Boolean = synchronized(this) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false
        val rawHeader = manager.getCookie(Config.BASE_URL).orEmpty()
        val cookies = parseCookieHeader(rawHeader)
        if (!cookies.containsKey(FORUM_SESSION_COOKIE)) return true

        save(storedCookies() - FORUM_SESSION_COOKIE)
        val domains = listOf(null, Config.HOST, ".${Config.HOST}")
        val attributes = listOf(
            "",
            "; Secure",
            "; Secure; HttpOnly",
            "; Secure; HttpOnly; SameSite=Lax",
        )
        domains.forEach { domain ->
            val domainPart = domain?.let { "; Domain=$it" }.orEmpty()
            attributes.forEach { attribute ->
                manager.setCookie(
                    Config.BASE_URL,
                    "$FORUM_SESSION_COOKIE=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$domainPart$attribute",
                )
            }
        }
        manager.flush()
        return true
    }

    /**
     * 保 _t 洗 Cookie（深度自愈清洗）：
     * 彻底清除由子域跳转、CF Turnstile 或会话失步引入的污染 Cookie（cf_clearance、_forum_session、__cf*），
     * 同时纯净保留用户的登录核心凭证 _t，避免重新输入密码登录。
     *
     * 包含加锁保护、递增请求代次、彻底清空分区 Cookie、种回 host-only _t 并做读回验证。
     */
    fun purgeTransientCookiesPreservingLogin(): Boolean = synchronized(this) {
        advanceSessionGeneration()
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false
        val rawHeader = manager.getCookie(Config.BASE_URL).orEmpty()
        val currentCookies = parseCookieHeader(rawHeader)
        val token = currentCookies[Config.SESSION_COOKIE]
            ?: storedCookies()[Config.SESSION_COOKIE]
            ?: return false

        // 1. 从持久化存储中只保留核心业务凭证（_t）
        val cleanStored = storedCookies().filterKeys { key ->
            key == Config.SESSION_COOKIE || (!key.startsWith("_cf", ignoreCase = true) && !key.startsWith("__cf", ignoreCase = true) && key != FORUM_SESSION_COOKIE && !key.equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true))
        }
        save(cleanStored)

        // 2. 彻底清空所有子站和根域 (.linux.do) 残留的全部污染 Cookie
        manager.removeAllCookies(null)
        manager.flush()

        // 3. 仅把核心凭证 _t 纯净重新种回主站 Host
        manager.setCookie(Config.BASE_URL, "${Config.SESSION_COOKIE}=$token; Path=/; Secure; HttpOnly; SameSite=Lax")
        manager.flush()

        // 4. 读回验证 (Read-back verification)
        val readBack = parseCookieHeader(manager.getCookie(Config.BASE_URL))
        if (readBack[Config.SESSION_COOKIE] == token) {
            return true
        }
        // 紧急兜底再次重种
        manager.setCookie(Config.BASE_URL, "${Config.SESSION_COOKIE}=$token; Path=/; Secure; HttpOnly")
        manager.flush()
        return parseCookieHeader(manager.getCookie(Config.BASE_URL))[Config.SESSION_COOKIE] == token
    }

    suspend fun replaceBusinessCookies(token: String?, forumSession: String?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "CookieManager updates must run on the main thread" }
        val cookies = syncFromWebView().toMutableMap()
        advanceSessionGeneration()
        sessionChanging = true
        try {
            cookieMutationGate.withLock {
                withTimeout(5_000L) {
                    val manager = CookieManager.getInstance()
                    val replacements = mapOf(
                        Config.SESSION_COOKIE to token?.takeIf(String::isNotBlank),
                        FORUM_SESSION_COOKIE to forumSession?.takeIf(String::isNotBlank),
                    )
                    for ((name, value) in replacements) {
                        for (domain in listOf("", "; Domain=${Config.HOST}", "; Domain=.${Config.HOST}")) {
                            setWebCookie(manager, "$name=; Max-Age=0; Path=/$domain; Secure; HttpOnly")
                        }
                        cookies.remove(name)
                        if (value != null) {
                            check(setWebCookie(manager, "$name=$value; Path=/; Secure; HttpOnly; SameSite=Lax")) {
                                "无法恢复账号凭据"
                            }
                            cookies[name] = value
                        }
                        check(parseCookieHeader(manager.getCookie(Config.BASE_URL))[name] == value) {
                            "账号凭据写入未确认，请重试"
                        }
                    }
                    manager.flush()
                    save(cookies)
                }
            }
        } finally {
            sessionChanging = false
        }
    }

    suspend fun clearBusinessCookies() = replaceBusinessCookies(token = null, forumSession = null)
    /** 判断当前是否已登录社区凭据。 */
    fun hasSession(): Boolean = storedCookies().containsKey(Config.SESSION_COOKIE)

    /**
     * 清除一个指定 Cookie，供 CF 重新验证前淘汰可能失效的 clearance 使用。
     * 返回清除后 WebView 里是否已经不存在该 Cookie。
     *
     * Chromium 删除 Cookie 的判定要求属性匹配上原件,只写 Path/Domain/Secure 覆盖不到
     * cf_clearance 这种 `SameSite=None; Secure` 的 Cookie —— 更不用说 CF 可能额外下发的
     * Partitioned(CHIPS) 副本,它存在独立的分区 jar 里,删除头必须同带 Secure+Partitioned
     * 才能命中。漏掉这些属性时删除会静默失败,旧 clearance 继续留在 WebView 里,
     * CF 随后直接放行回源(不出盾),于是整条重新验证流程拿着一枚已被判废的凭据空转。
     */
    fun clearCookie(name: String): Boolean {
        save(storedCookies() - name)
        val manager = CookieManager.getInstance()
        // CF 可能以 host-only、linux.do 或 .linux.do 三种形式写入 Cookie,
        // 每种形式又要覆盖上述四类属性组合。
        val domains = listOf(null, Config.HOST, ".${Config.HOST}")
        val attributes = listOf(
            "",
            "; Secure",
            "; Secure; SameSite=None",
            "; Secure; SameSite=None; Partitioned",
        )
        domains.forEach { domain ->
            val domainPart = domain?.let { "; Domain=$it" }.orEmpty()
            attributes.forEach { attribute ->
                manager.setCookie(
                    Config.BASE_URL,
                    "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$domainPart$attribute",
                )
            }
        }
        manager.flush()
        return parseCookieHeader(manager.getCookie(Config.BASE_URL))[name].isNullOrBlank()
    }

    /** 应用 OkHttp 响应里的 Set-Cookie,同时写入加密存储与 WebView,保证两边一致。 */
    suspend fun applyResponseCookies(
        cookies: List<Cookie>,
        requestUrl: String = Config.BASE_URL,
        expectedCookieVersion: Long? = null,
        requestGeneration: Long? = null,
        sentCookies: Map<String, String> = emptyMap(),
    ) = cookieMutationGate.withLock {
        if (cookies.isEmpty() || sessionChanging) return@withLock
        if (requestGeneration != null && requestGeneration != sessionGeneration) return@withLock
        if (expectedCookieVersion != null && expectedCookieVersion != cookieVersion) return@withLock
        val manager = CookieManager.getInstance()
        val merged = syncFromWebView().toMutableMap()
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if ((requestGeneration != null && requestGeneration != sessionGeneration) || sessionChanging) return@withLock
            val targetUrl = requestUrl
            val auth = cookie.name == Config.SESSION_COOKIE || cookie.name == FORUM_SESSION_COOKIE
            val mainAuth = auth && requestUrl.toHttpUrl().host == Config.HOST && cookie.domain == Config.HOST
            // A 的旧 token 被撤销，不表示同账号刚轮换得到的 B 也被撤销。
            if (mainAuth && sentCookies[cookie.name] != merged[cookie.name]) return@forEach
            if (mainAuth) {
                listOf("; Domain=${Config.HOST}", "; Domain=.${Config.HOST}").forEach { domain ->
                    setWebCookie(manager, "${cookie.name}=; Max-Age=0; Path=/$domain; Secure; HttpOnly")
                }
            }
            if ((requestGeneration != null && requestGeneration != sessionGeneration) || sessionChanging) return@withLock
            if (cookie.expiresAt < now) {
                if (requestUrl.toHttpUrl().host == Config.HOST) merged.remove(cookie.name)
                val domain = if (mainAuth || cookie.hostOnly) "" else "; Domain=${cookie.domain}"
                setWebCookie(manager, "${cookie.name}=; Max-Age=0; Path=${cookie.path}$domain; Secure", targetUrl)
            } else {
                // 防止已被挑战判废的 cf_clearance 借响应 Set-Cookie 回写覆盖刚签发的新值
                if (requestUrl.toHttpUrl().host == Config.HOST && cookie.name.equals(Config.CF_CLEARANCE_COOKIE, ignoreCase = true) && isClearanceChallenged(cookie.value)) {
                    return@forEach
                }
                if (requestUrl.toHttpUrl().host == Config.HOST && cookie.matches(Config.BASE_URL.toHttpUrl())) {
                    merged[cookie.name] = cookie.value
                }
                val normalized = if (mainAuth) cookie.toString().replace(Regex("; domain=[^;]+", RegexOption.IGNORE_CASE), "") else cookie.toString()
                setWebCookie(manager, normalized, targetUrl)
            }
        }
        manager.flush()
        if (!sessionChanging && (requestGeneration == null || requestGeneration == sessionGeneration)) save(merged)
    }

    fun hasSessionCookie(): Boolean = syncFromWebView().containsKey(Config.SESSION_COOKIE)

    fun clear() {
        advanceSessionGeneration()
        clearTopicNotificationLevels()
        clearCurrentUserId()
        plainPrefs.edit().remove(KEY_LAST_SEEN_NOTIFICATION_ID).apply()
        prefs.edit().remove(KEY_COOKIES).remove(KEY_SAVED_AT).apply()
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    private fun save(cookies: Map<String, String>) {
        cookieVersion++
        prefs.edit()
            .putString(KEY_COOKIES, JSONObject(cookies as Map<*, *>).toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun homeFeed(): String? = prefs.getString(KEY_HOME_FEED, null)

    fun saveHomeFeed(feed: String) {
        prefs.edit().putString(KEY_HOME_FEED, feed).apply()
    }

    fun getTopicSortOrder(): TopicSortOrder =
        TopicSortOrder.fromName(prefs.getString(KEY_TOPIC_SORT_ORDER, null))

    fun setTopicSortOrder(order: TopicSortOrder) {
        prefs.edit().putString(KEY_TOPIC_SORT_ORDER, order.name).apply()
    }

    fun getTopicSortAscending(): Boolean =
        prefs.getBoolean(KEY_TOPIC_SORT_ASCENDING, false)

    fun setTopicSortAscending(ascending: Boolean) {
        prefs.edit().putBoolean(KEY_TOPIC_SORT_ASCENDING, ascending).apply()
    }

    fun isFloatingBallEnabled(): Boolean =
        plainPrefs.getBoolean(KEY_FLOATING_BALL_ENABLED, true)

    fun setFloatingBallEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_FLOATING_BALL_ENABLED, enabled).apply()
    }

    fun getSkinPresetId(): String = plainPrefs.getString(KEY_SKIN_PRESET, "spongebob") ?: "spongebob"

    fun setSkinPresetId(id: String) {
        plainPrefs.edit().putString(KEY_SKIN_PRESET, id).apply()
    }

    fun isUserCustomizedSkin(): Boolean = plainPrefs.getBoolean("user_customized_skin", false)

    fun setUserCustomizedSkin(customized: Boolean) {
        plainPrefs.edit().putBoolean("user_customized_skin", customized).apply()
    }

    fun isAutoReadClipboardEnabled(): Boolean =
        plainPrefs.getBoolean("auto_read_clipboard", true)

    fun setAutoReadClipboardEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean("auto_read_clipboard", enabled).apply()
    }

    fun getLastHandledClipboardUrl(): String =
        plainPrefs.getString("last_handled_clipboard_url", "").orEmpty()

    fun setLastHandledClipboardUrl(url: String) {
        plainPrefs.edit().putString("last_handled_clipboard_url", url).apply()
    }

    fun getClipboardConsumedTextHash(): String =
        plainPrefs.getString("clipboard_consumed_text_hash", "").orEmpty()

    fun isClipboardConsumptionMarkerVerified(): Boolean =
        plainPrefs.getBoolean("clipboard_consumption_marker_verified", false)

    fun setClipboardConsumedText(textHash: String, markerVerified: Boolean) {
        plainPrefs.edit()
            .putString("clipboard_consumed_text_hash", textHash)
            .putBoolean("clipboard_consumption_marker_verified", markerVerified)
            .apply()
    }

    fun getLastHandledClipboardTimestamp(): Long =
        plainPrefs.getLong("last_handled_clipboard_timestamp", 0L)

    fun setLastHandledClipboardTimestamp(timestamp: Long) {
        plainPrefs.edit().putLong("last_handled_clipboard_timestamp", timestamp).apply()
    }

    fun getString(key: String): String? = plainPrefs.getString(key, null)

    fun putString(key: String, value: String) {
        plainPrefs.edit().putString(key, value).apply()
    }

    fun removeString(key: String) {
        plainPrefs.edit().remove(key).apply()
    }

    fun getFloatingBallActions(): List<FloatingAction> =
        FloatingAction.parseActions(plainPrefs.getString(KEY_FLOATING_BALL_ACTIONS, null))

    fun setFloatingBallActions(actions: List<FloatingAction>) {
        plainPrefs.edit().putString(KEY_FLOATING_BALL_ACTIONS, FloatingAction.serializeActions(actions)).apply()
    }

    fun getFloatingBallXRatio(): Float =
        plainPrefs.getFloat(KEY_FLOATING_BALL_X_RATIO, if (isFloatingBallOnRight()) 0.95f else 0.05f)

    fun setFloatingBallXRatio(ratio: Float) {
        plainPrefs.edit().putFloat(KEY_FLOATING_BALL_X_RATIO, ratio.coerceIn(0f, 1f)).apply()
    }

    fun getFloatingBallYRatio(): Float =
        plainPrefs.getFloat(KEY_FLOATING_BALL_Y_RATIO, 1.0f)

    fun setFloatingBallYRatio(ratio: Float) {
        plainPrefs.edit().putFloat(KEY_FLOATING_BALL_Y_RATIO, ratio.coerceIn(0f, 1f)).apply()
    }

    fun isFloatingBallOnRight(): Boolean =
        plainPrefs.getBoolean(KEY_FLOATING_BALL_ON_RIGHT, true)

    fun setFloatingBallOnRight(onRight: Boolean) {
        plainPrefs.edit().putBoolean(KEY_FLOATING_BALL_ON_RIGHT, onRight).apply()
    }

    fun savedLogin(): String? = prefs.getString(KEY_SAVED_LOGIN, null)

    fun savedPassword(): String? = if (isRememberPassword()) prefs.getString(KEY_SAVED_PASSWORD, null) else null

    fun isRememberPassword(): Boolean = prefs.getBoolean(KEY_REMEMBER_PASSWORD, false)

    fun saveCredentials(login: String, password: String, remember: Boolean) {
        val editor = prefs.edit()
        editor.putString(KEY_SAVED_LOGIN, login)
        editor.putBoolean(KEY_REMEMBER_PASSWORD, remember)
        if (remember) {
            editor.putString(KEY_SAVED_PASSWORD, password)
        } else {
            editor.remove(KEY_SAVED_PASSWORD)
        }
        editor.apply()
    }

    private val plainPrefs: SharedPreferences = context.getSharedPreferences(PREFS_PLAIN_NAME, Context.MODE_PRIVATE)
    init {
        NoExcerptModeMigration.applyTo(plainPrefs)
    }
    private val json = Json { ignoreUnknownKeys = true }

    fun saveCategoryOrder(ids: List<Long>) {
        plainPrefs.edit().putString(KEY_CATEGORY_ORDER, ids.filter { it > 0L }.distinct().joinToString(",")).commit()
    }

    fun loadCategoryOrder(): List<Long> {
        val raw = plainPrefs.getString(KEY_CATEGORY_ORDER, null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0L } }.distinct()
    }

    fun resetCategoryOrder() {
        plainPrefs.edit().remove(KEY_CATEGORY_ORDER).commit()
    }

    fun saveCustomSection(section: CustomSection?) {
        if (section == null) {
            plainPrefs.edit().remove(KEY_CUSTOM_SECTION).commit()
        } else {
            val raw = json.encodeToString(CustomSection.serializer(), section)
            plainPrefs.edit().putString(KEY_CUSTOM_SECTION, raw).commit()
        }
    }

    fun loadCustomSection(): CustomSection? {
        val raw = plainPrefs.getString(KEY_CUSTOM_SECTION, null) ?: return null
        return runCatching {
            json.decodeFromString(CustomSection.serializer(), raw)
        }.getOrNull()
    }

    private val topicNotificationLevels = LinkedHashMap<Long, Int>(16, 0.75f, true).apply {
        plainPrefs.getString(KEY_TOPIC_NOTIFICATION_LEVELS, null)
            ?.split(',')
            ?.forEach { entry ->
                val parts = entry.split(':')
                if (parts.size == 2) {
                    val id = parts[0].trim().toLongOrNull()
                    val level = parts[1].trim().toIntOrNull()
                    if (id != null && id > 0L && level != null && level in 0..3) {
                        put(id, level)
                    }
                }
            }
        while (size > MAX_NOTIFICATION_LEVELS) remove(keys.first())
    }

    fun saveTopicNotificationLevel(topicId: Long, level: Int) {
        if (topicId <= 0L || level !in 0..3) return
        synchronized(topicNotificationLevels) {
            topicNotificationLevels.remove(topicId)
            topicNotificationLevels[topicId] = level
            while (topicNotificationLevels.size > MAX_NOTIFICATION_LEVELS) {
                val oldest = topicNotificationLevels.keys.firstOrNull() ?: break
                topicNotificationLevels.remove(oldest)
            }
            val csv = topicNotificationLevels.entries.joinToString(",") { "${it.key}:${it.value}" }
            plainPrefs.edit().putString(KEY_TOPIC_NOTIFICATION_LEVELS, csv).apply()
        }
    }

    private fun clearTopicNotificationLevels() = synchronized(topicNotificationLevels) {
        topicNotificationLevels.clear()
        plainPrefs.edit().remove(KEY_TOPIC_NOTIFICATION_LEVELS).apply()
    }

    fun getTopicNotificationLevel(topicId: Long): Int? = synchronized(topicNotificationLevels) {
        topicNotificationLevels[topicId]
    }

    fun allTopicNotificationLevels(): Map<Long, Int> = synchronized(topicNotificationLevels) {
        topicNotificationLevels.toMap()
    }

    fun isAutoRefreshIncomingEnabled(): Boolean = AUTOMATIC_NETWORK_FEATURES_AVAILABLE &&
        getAutoRefreshInterval() != AutoRefreshInterval.OFF

    fun getAutoRefreshInterval(): AutoRefreshInterval {
        val code = plainPrefs.getInt(KEY_AUTO_REFRESH_INTERVAL, -1)
        if (code >= 0) return AutoRefreshInterval.fromCode(code)
        // 兼容旧版 boolean（若用户曾保存过旧版设置）
        if (plainPrefs.contains(KEY_AUTO_REFRESH_INCOMING)) {
            val legacy = plainPrefs.getBoolean(KEY_AUTO_REFRESH_INCOMING, false)
            return if (legacy) AutoRefreshInterval.MIN_1 else AutoRefreshInterval.OFF
        }
        return AutoRefreshInterval.SEC_20
    }

    fun setAutoRefreshInterval(interval: AutoRefreshInterval) {
        plainPrefs.edit()
            .putInt(KEY_AUTO_REFRESH_INTERVAL, interval.code)
            .putBoolean(KEY_AUTO_REFRESH_INCOMING, interval != AutoRefreshInterval.OFF)
            .apply()
    }

    fun setAutoRefreshIncomingEnabled(enabled: Boolean) {
        setAutoRefreshInterval(if (enabled) AutoRefreshInterval.MIN_1 else AutoRefreshInterval.OFF)
    }

    fun isBackgroundNotificationEnabled(): Boolean = AUTOMATIC_NETWORK_FEATURES_AVAILABLE &&
        getNotificationPollInterval() != NotificationPollInterval.OFF

    fun getNotificationPollInterval(): NotificationPollInterval {
        val code = plainPrefs.getInt(KEY_BG_NOTIFICATION_INTERVAL, -1)
        if (code >= 0) return NotificationPollInterval.fromCode(code)
        // 兼容旧版 boolean
        val legacy = plainPrefs.getBoolean(KEY_BG_NOTIFICATION, false)
        return if (legacy) NotificationPollInterval.MIN_15 else NotificationPollInterval.OFF
    }

    fun setNotificationPollInterval(interval: NotificationPollInterval) {
        val editor = plainPrefs.edit()
            .putInt(KEY_BG_NOTIFICATION_INTERVAL, interval.code)
            .putBoolean(KEY_BG_NOTIFICATION, interval != NotificationPollInterval.OFF)
        if (interval != NotificationPollInterval.OFF) {
            editor.putInt(KEY_LAST_NON_OFF_INTERVAL, interval.code)
        }
        editor.apply()
    }

    fun setBackgroundNotificationEnabled(enabled: Boolean) {
        setKeepAliveServiceEnabled(enabled)
        if (!enabled) {
            setNotificationPollInterval(NotificationPollInterval.OFF)
        } else {
            val lastCode = plainPrefs.getInt(KEY_LAST_NON_OFF_INTERVAL, NotificationPollInterval.MIN_15.code)
            val restore = NotificationPollInterval.fromCode(lastCode).let {
                if (it == NotificationPollInterval.OFF) NotificationPollInterval.MIN_15 else it
            }
            setNotificationPollInterval(restore)
        }
    }

    fun getNotificationScope(): NotificationScope {
        val code = plainPrefs.getInt(KEY_NOTIFICATION_SCOPE, NotificationScope.INTERACTION_ONLY.code)
        return NotificationScope.fromCode(code)
    }

    fun setNotificationScope(scope: NotificationScope) {
        plainPrefs.edit().putInt(KEY_NOTIFICATION_SCOPE, scope.code).apply()
    }

    fun isKeepAliveServiceEnabled(): Boolean = AUTOMATIC_NETWORK_FEATURES_AVAILABLE &&
        plainPrefs.getBoolean(KEY_KEEP_ALIVE_SERVICE, false)

    fun setKeepAliveServiceEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_KEEP_ALIVE_SERVICE, enabled).apply()
    }

    fun getCustomRealtimeSeconds(): Int = plainPrefs.getInt(KEY_CUSTOM_REALTIME_SECONDS, 15).coerceIn(1, 60)

    fun setCustomRealtimeSeconds(seconds: Int) {
        val valid = seconds.coerceIn(1, 60)
        plainPrefs.edit().putInt(KEY_CUSTOM_REALTIME_SECONDS, valid).apply()
    }

    fun getLastSeenNotificationId(): Long = plainPrefs.getLong(KEY_LAST_SEEN_NOTIFICATION_ID, 0L)

    fun setLastSeenNotificationId(id: Long) {
        plainPrefs.edit().putLong(KEY_LAST_SEEN_NOTIFICATION_ID, id).apply()
    }

    fun currentUserId(): Long = plainPrefs.getLong(KEY_CURRENT_USER_ID, 0L)

    fun saveCurrentUserId(id: Long) {
        plainPrefs.edit().putLong(KEY_CURRENT_USER_ID, id).apply()
    }

    fun clearCurrentUserId() {
        plainPrefs.edit().remove(KEY_CURRENT_USER_ID).apply()
    }

    fun hasAutoPromptedBatteryWhitelist(): Boolean =
        plainPrefs.getBoolean(KEY_AUTO_PROMPTED_BATTERY_WHITELIST, false)

    fun markAutoPromptedBatteryWhitelist() {
        plainPrefs.edit().putBoolean(KEY_AUTO_PROMPTED_BATTERY_WHITELIST, true).apply()
    }

    fun getTestSimulatedUnreadCount(): Int = 0

    fun setTestSimulatedUnreadCount(count: Int) {
        plainPrefs.edit().remove(KEY_TEST_SIM_UNREAD_COUNT).commit()
    }

    fun incrementTestSimulatedUnreadCount(): Int = 0

    /**
     * 最近一次按通知作用域算出的桌面角标数,作为角标的单一真相源。
     *
     * MessageBus 实时推送只能拿到服务端的「全部未读数」,在「仅回复与互动类」作用域下
     * 含点赞等被过滤类型,直接写角标会出现「有角标却没有通知」的鬼数字。
     * 所以实时推送按实际弹出条数在这个值上递增,再由 REST 轮询与前台刷新校准为精确值。
     */
    fun getLastBadgeCount(): Int = plainPrefs.getInt(KEY_LAST_BADGE_COUNT, 0).coerceAtLeast(0)

    /**
     * 写入角标真相源。[force] 仅供实验室的角标实测按钮使用。
     *
     * 实测锁定期内拒绝常规来源(未读轮询、推送、本地已读)的写入,
     * 否则人造数字会在用户按 Home 之前就被真实未读覆盖掉,实测等于没测。
     */
    fun setLastBadgeCount(count: Int, force: Boolean = false) {
        if (!force && isBadgeTestHolding()) return
        plainPrefs.edit().putInt(KEY_LAST_BADGE_COUNT, count.coerceAtLeast(0)).commit()
    }

    fun incrementLastBadgeCount(): Int {
        val next = getLastBadgeCount() + 1
        setLastBadgeCount(next, force = true)
        return next
    }

    /**
     * 角标实测锁定窗口的到期时刻(墙上时钟)。0 表示未锁定。
     *
     * 实验室下发的人造角标数只用于验证厂商桌面接口是否生效,**不进入真实未读口径**,
     * 所以 APP 内的铃铛与未读计数不受这个窗口影响,只有桌面角标被临时冻结。
     */
    fun getBadgeTestHoldUntil(): Long = plainPrefs.getLong(KEY_BADGE_TEST_HOLD_UNTIL, 0L)

    fun setBadgeTestHoldUntil(timestamp: Long) {
        plainPrefs.edit().putLong(KEY_BADGE_TEST_HOLD_UNTIL, timestamp.coerceAtLeast(0L)).commit()
    }

    fun isBadgeTestHolding(): Boolean = System.currentTimeMillis() < getBadgeTestHoldUntil()

    fun decrementTestSimulatedUnreadCount(): Int = 0

    fun getPendingTestReplayTag(): String? =
        plainPrefs.getString(KEY_PENDING_TEST_REPLAY_TAG, null)?.takeIf { it.isNotBlank() }

    fun setPendingTestReplayTag(tag: String?) {
        plainPrefs.edit().putString(KEY_PENDING_TEST_REPLAY_TAG, tag).commit()
    }

    fun isTestWaitScreenOnArmed(): Boolean =
        plainPrefs.getBoolean(KEY_TEST_WAIT_SCREEN_ON, false)

    fun setTestWaitScreenOnArmed(armed: Boolean) {
        plainPrefs.edit().putBoolean(KEY_TEST_WAIT_SCREEN_ON, armed).commit()
    }

    /**
     * App 是否处于前台。
     *
     * 由 MainActivity 的 onResume / onStop 维护，供「亮屏最小化」兜底闹钟判断
     * 是否还需要续约下一跳。进程被杀后标记残留为 false，正好等价于「不在前台」。
     * 用 commit() 而非 apply()：onStop 之后进程随时可能被冻结或回收，
     * 异步落盘可能根本来不及写入。
     */
    fun isAppInForeground(): Boolean =
        plainPrefs.getBoolean(KEY_APP_IN_FOREGROUND, false)

    fun setAppInForeground(inForeground: Boolean) {
        plainPrefs.edit().putBoolean(KEY_APP_IN_FOREGROUND, inForeground).commit()
    }

    // ── DoH 偏好读写 ─────────────────────────────────────────────────────────────

    /** 是否开启 DoH（默认开启，支持免代理直连） */
    override fun isDohEnabled(): Boolean = plainPrefs.getBoolean(KEY_DOH_ENABLED, true)

    fun setDohEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DOH_ENABLED, enabled).apply()
    }

    /**
     * 当前选中的 DoH 服务器 URL。
     * 存的是节点的 [org.linuxdo.android.net.DohServer.url]，
     * null 表示未选择（使用预设默认节点）。
     */
    override fun getDohServerUrl(): String? {
        val saved = plainPrefs.getString(KEY_DOH_SERVER_URL, null) ?: return null
        if (saved.contains("kudo.ddd.oaifree.com")) {
            val migrated = saved.replace("kudo.ddd.oaifree.com", "neo.ddd.oaifree.com")
            plainPrefs.edit().putString(KEY_DOH_SERVER_URL, migrated).apply()
            return migrated
        }
        return saved
    }

    fun setDohServerUrl(url: String?) {
        if (url == null) {
            plainPrefs.edit().remove(KEY_DOH_SERVER_URL).apply()
        } else {
            plainPrefs.edit().putString(KEY_DOH_SERVER_URL, url).apply()
        }
    }

    /** 是否开启自动故障切换（默认开启） */
    override fun isDohAutoFailoverEnabled(): Boolean = plainPrefs.getBoolean(KEY_DOH_AUTO_FAILOVER, true)

    fun setDohAutoFailoverEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DOH_AUTO_FAILOVER, enabled).apply()
    }

    /** 是否开启智能定时低延迟优选与自动切换（默认开启） */
    fun isDohAutoOptimizeEnabled(): Boolean = plainPrefs.getBoolean(KEY_DOH_AUTO_OPTIMIZE, true)

    fun setDohAutoOptimizeEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DOH_AUTO_OPTIMIZE, enabled).apply()
    }

    /** 是否在开启系统代理/VPN 时强制保持 DoH + ECH 直连（防止直连分流规则导致阻断，默认关闭由系统让渡） */
    fun isDohForceOverProxyEnabled(): Boolean = plainPrefs.getBoolean(KEY_DOH_FORCE_OVER_PROXY, false)

    fun setDohForceOverProxyEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DOH_FORCE_OVER_PROXY, enabled).apply()
    }

    /** 首页信息流显示模式：强制全量收敛为无正文模式（图文模式代码保留后续再优化） */
    fun getFeedDisplayMode(): String = "no_excerpt"

    fun setFeedDisplayMode(mode: String) {
        plainPrefs.edit().putString(KEY_FEED_DISPLAY_MODE, "no_excerpt").apply()
    }

    // 兼容旧接口（强制关闭图文大卡片）
    fun isFeedGraphicMode(): Boolean = false

    fun setFeedGraphicMode(enabled: Boolean) {
        setFeedDisplayMode("no_excerpt")
    }

    /** 搜索历史记录（最多保留20条，最新在最前） */
    fun getSearchHistory(): List<String> {
        val raw = plainPrefs.getString(KEY_SEARCH_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty() && !list.contains(item)) {
                    list.add(item)
                }
            }
            list.take(20)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistory(keyword: String): List<String> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return getSearchHistory()
        val current = getSearchHistory().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val updated = current.take(20)
        val arr = org.json.JSONArray()
        updated.forEach { arr.put(it) }
        plainPrefs.edit().putString(KEY_SEARCH_HISTORY_JSON, arr.toString()).apply()
        return updated
    }

    fun getRateLimitedUntil(): Long = plainPrefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)

    fun setRateLimitedUntil(timeMs: Long) {
        plainPrefs.edit().putLong(KEY_RATE_LIMITED_UNTIL, timeMs).apply()
    }

    fun clearSearchHistory() {
        plainPrefs.edit().remove(KEY_SEARCH_HISTORY_JSON).apply()
    }

    /**
     * 全部 DoH 节点的 JSON 序列化串（支持增删改查与预设持久化）。
     * 格式：`[{"url":"https://...","name":"...","isCustom":true},...]`
     */
    override fun getDohServersJson(): String? = plainPrefs.getString(KEY_DOH_SERVERS_JSON, null)

    fun setDohServersJson(json: String?) {
        if (json == null) {
            plainPrefs.edit().remove(KEY_DOH_SERVERS_JSON).apply()
        } else {
            plainPrefs.edit().putString(KEY_DOH_SERVERS_JSON, json).apply()
        }
    }

    /**
     * 用户自定义节点的 JSON 序列化串（旧版兼容）。
     */
    override fun getDohCustomServersJson(): String? = plainPrefs.getString(KEY_DOH_CUSTOM_SERVERS_JSON, null)

    fun setDohCustomServersJson(json: String?) {
        if (json == null) {
            plainPrefs.edit().remove(KEY_DOH_CUSTOM_SERVERS_JSON).apply()
        } else {
            plainPrefs.edit().putString(KEY_DOH_CUSTOM_SERVERS_JSON, json).apply()
        }
    }

    companion object {
        const val AUTOMATIC_NETWORK_FEATURES_AVAILABLE = true
        private const val PREFS_NAME = "session_secure"
        private const val PREFS_PLAIN_NAME = "app_preferences"
        private const val KEY_CURRENT_USER_ID = "current_user_id"
        private const val FORUM_SESSION_COOKIE = "_forum_session"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_HOME_FEED = "home_feed"
        private const val KEY_TOPIC_SORT_ORDER = "topic_sort_order"
        private const val KEY_TOPIC_SORT_ASCENDING = "topic_sort_ascending"
        private const val KEY_FLOATING_BALL_ENABLED = "floating_ball_enabled"
        private const val KEY_FLOATING_BALL_ACTIONS = "floating_ball_actions"
        private const val KEY_FLOATING_BALL_X_RATIO = "floating_ball_x_ratio"
        private const val KEY_FLOATING_BALL_Y_RATIO = "floating_ball_y_ratio"
        private const val KEY_FLOATING_BALL_ON_RIGHT = "floating_ball_on_right"
        private const val KEY_SAVED_LOGIN = "saved_login"
        private const val KEY_LAST_SEEN_NOTIFICATION_ID = "last_seen_notification_id"
        private const val KEY_SAVED_PASSWORD = "saved_password"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_CATEGORY_ORDER = "category_custom_order"
        private const val KEY_CUSTOM_SECTION = "custom_section_json"
        private const val KEY_TOPIC_NOTIFICATION_LEVELS = "topic_notification_levels"
        private const val KEY_AUTO_REFRESH_INCOMING = "auto_refresh_incoming"
        private const val KEY_AUTO_REFRESH_INTERVAL = "auto_refresh_interval"
        private const val KEY_BG_NOTIFICATION = "bg_notification_enabled"
        private const val KEY_BG_NOTIFICATION_INTERVAL = "bg_notification_interval"
        private const val KEY_LAST_NON_OFF_INTERVAL = "last_non_off_notification_interval"
        private const val KEY_NOTIFICATION_SCOPE = "notification_scope"
        private const val KEY_KEEP_ALIVE_SERVICE = "keep_alive_service_enabled"
        private const val KEY_CUSTOM_REALTIME_SECONDS = "custom_realtime_seconds"
        private const val KEY_AUTO_PROMPTED_BATTERY_WHITELIST = "auto_prompted_battery_whitelist_v130"
        // 带版本后缀：旧版实验室「设为 99」会把 99 写进这里并被当成真实未读累加，
        // 换 key 让历史残留在升级后直接作废，无需用户手动归零。
        private const val KEY_TEST_SIM_UNREAD_COUNT = "test_sim_unread_count_v131"
        private const val KEY_LAST_BADGE_COUNT = "last_badge_count"
        private const val KEY_BADGE_TEST_HOLD_UNTIL = "badge_test_hold_until"
        private const val KEY_PENDING_TEST_REPLAY_TAG = "pending_test_replay_tag"
        private const val KEY_TEST_WAIT_SCREEN_ON = "test_wait_screen_on"
        private const val KEY_APP_IN_FOREGROUND = "app_in_foreground"
        private const val MAX_NOTIFICATION_LEVELS = 1000
        private const val MAX_CHALLENGED_CLEARANCES = 16
        private const val CHALLENGED_CLEARANCE_PAUSE_MS = 60_000L

        // ── DoH 相关偏好 ──────────────────────────────────────────────────────────
        const val KEY_DOH_ENABLED = "doh_enabled"
        const val KEY_DOH_SERVER_URL = "doh_server_url"
        const val KEY_DOH_AUTO_FAILOVER = "doh_auto_failover"
        const val KEY_DOH_AUTO_OPTIMIZE = "doh_auto_optimize"
        const val KEY_DOH_SERVERS_JSON = "doh_servers_json"
        const val KEY_DOH_CUSTOM_SERVERS_JSON = "doh_custom_servers_json"
        const val KEY_DOH_FORCE_OVER_PROXY = "doh_force_over_proxy"
        const val KEY_FEED_GRAPHIC_MODE = "feed_graphic_mode"
        const val KEY_FEED_DISPLAY_MODE = "feed_display_mode"
        const val KEY_SKIN_PRESET = "skin_preset"
        const val KEY_SEARCH_HISTORY_JSON = "search_history_json"
        const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until_wall_ms"

        fun parseCookieHeader(raw: String?): Map<String, String> =
            raw.orEmpty().split(";").mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()

        fun toCookieHeader(cookies: Map<String, String>): String =
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        private fun createPrefs(context: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                build()
            } catch (error: Exception) {
                // Keystore 密钥失效(如恢复备份/重装)会导致无法解密,清掉旧文件重新开始
                context.deleteSharedPreferences(PREFS_NAME)
                build()
            }
        }

        private suspend fun setWebCookie(manager: CookieManager, value: String, url: String = Config.BASE_URL): Boolean =
            withTimeout(3_000L) {
                suspendCancellableCoroutine { continuation ->
                    manager.setCookie(url, value) { accepted ->
                        if (continuation.isActive) continuation.resume(accepted)
                    }
                }
            }
    }
}

enum class AutoRefreshInterval(val code: Int, val label: String, val seconds: Long) {
    OFF(0, "关闭", 0L),
    SEC_20(7, "20 秒", 20L),
    SEC_30(1, "30 秒", 30L),
    MIN_1(2, "1 分钟", 60L),
    MIN_2(3, "2 分钟", 120L),
    MIN_5(4, "5 分钟", 300L),
    MIN_10(5, "10 分钟", 600L),
    MIN_15(6, "15 分钟", 900L);

    fun nextDelayMs(): Long = if (this == SEC_20) kotlin.random.Random.nextLong(18_000L, 25_001L) else seconds * 1_000L

    companion object {
        fun fromCode(code: Int): AutoRefreshInterval = entries.firstOrNull { it.code == code } ?: OFF
    }
}

enum class NotificationPollInterval(val code: Int, val label: String, val seconds: Long) {
    OFF(0, "关闭", 0L),
    REALTIME(1, "实时", 15L),
    MIN_1(2, "1 分钟", 60L),
    MIN_2(3, "2 分钟", 120L),
    MIN_5(4, "5 分钟", 300L),
    MIN_10(5, "10 分钟", 600L),
    MIN_15(6, "15 分钟", 900L);

    fun getDisplayLabel(store: SessionStore?): String = when (this) {
        REALTIME -> if (store != null) "实时 (${store.getCustomRealtimeSeconds()}秒)" else "实时"
        else -> label
    }

    fun getEffectiveSeconds(store: SessionStore?): Long = when (this) {
        REALTIME -> (store?.getCustomRealtimeSeconds() ?: seconds.toInt()).toLong()
        else -> seconds
    }

    companion object {
        fun fromCode(code: Int): NotificationPollInterval = entries.firstOrNull { it.code == code } ?: OFF
    }
}

enum class NotificationScope(val code: Int, val label: String, val description: String) {
    INTERACTION_ONLY(0, "仅回复与互动类通知", "仅推送与您直接相关的回复、@提及、引用、私信与简评"),
    ALL(1, "全部通知", "包含关注发帖、话题动态、点赞与全部社区消息");

    /**
     * 该作用域是否接收此类型通知。
     *
     * 推送下发、桌面角标与铃铛计数共用这一个判定 —— 三者各算一套口径,
     * 就会出现「后台弹了通知、前台一打开就把角标刷成 0」。
     */
    fun includes(notificationType: Int): Boolean = when (this) {
        ALL -> true
        INTERACTION_ONLY -> notificationType in INTERACTION_TYPES
    }

    companion object {
        /**
         * 严格与我直接相关的回复与互动类型：
         * 1:提及, 2:回复, 3:引用, 4:编辑, 6:私信, 7:邀请私信, 15:群组提及, 16:群私信, 29:聊天提及, 30:聊天消息, 33:聊天引用, 43:简评。
         * 注意：9 为 posted (关注话题中他人回帖/发帖)，14 为插件杂项，35 为问答公区评论，均属于全站/话题动态，绝非个人互动。
         */
        private val INTERACTION_TYPES = setOf(1, 2, 3, 4, 6, 7, 15, 16, 29, 30, 33, 43)

        fun fromCode(code: Int): NotificationScope = entries.firstOrNull { it.code == code } ?: INTERACTION_ONLY
    }
}


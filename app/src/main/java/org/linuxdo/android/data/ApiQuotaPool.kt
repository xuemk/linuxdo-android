package org.linuxdo.android.data

import android.os.SystemClock

internal enum class ExcerptFetchOutcome {
    COMPLETE, EMPTY, TRANSIENT_FAILURE, BLOCKED, PERMANENT_FAILURE, DEFERRED;

    companion object {
        fun fromStatus(status: Int, challenged: Boolean = false): ExcerptFetchOutcome = when {
            challenged || status in setOf(401, 403, 429) -> BLOCKED
            status in setOf(0, 408, 500, 502, 503, 504) -> TRANSIENT_FAILURE
            else -> PERMANENT_FAILURE
        }
    }
}

internal data class ExcerptFetchResult(
    val outcome: ExcerptFetchOutcome,
    val retryAfterMs: Long = 0L,
)

/** 进程级发送预算；每次重试与首次请求同样计数，局部让行不消耗额度。 */
internal class ExcerptRetryPolicy(
    private val requestLimit: Int = 15,
    private val attemptLimit: Int = 2,
    private val retryDelayMs: Long = 10_000L,
) {
    private data class Entry(var attempts: Int = 0, var readyAt: Long = 0L, var complete: Boolean = false)
    private val entries = mutableMapOf<Pair<Long, Long>, Entry>()
    private var issued = 0
    private var readyAt = 0L
    private var inFlight: Pair<Long, Long>? = null

    @Synchronized
    fun nextTopic(userId: Long, topicIds: List<Long>, now: Long): Long? {
        if (issued >= requestLimit || inFlight != null || now < readyAt) return null
        return topicIds.distinct().filter { eligible(userId, it) }
            .filter { now >= (entries[userId to it]?.readyAt ?: 0L) }
            .minByOrNull { entries[userId to it]?.attempts ?: 0 }
    }

    @Synchronized
    fun waitMs(userId: Long, topicIds: List<Long>, now: Long): Long? {
        if (issued >= requestLimit || inFlight != null) return null
        val next = topicIds.filter { eligible(userId, it) }
            .minOfOrNull { maxOf(readyAt, entries[userId to it]?.readyAt ?: 0L) } ?: return null
        return (next - now).coerceAtLeast(0L)
    }

    @Synchronized
    fun startAttempt(userId: Long, topicId: Long, now: Long): Boolean {
        if (nextTopic(userId, listOf(topicId), now) == null) return false
        val key = userId to topicId
        entries.getOrPut(key) { Entry() }.attempts++
        issued++
        inFlight = key
        return true
    }

    @Synchronized
    fun finishAttempt(userId: Long, topicId: Long, result: ExcerptFetchResult?, now: Long, spacingMs: Long) {
        val key = userId to topicId
        if (inFlight != key) return
        inFlight = null
        val entry = entries.getValue(key)
        val serverWait = result?.retryAfterMs?.coerceAtLeast(0L) ?: 0L
        val blockedWait = if (result?.outcome == ExcerptFetchOutcome.BLOCKED) 60_000L else 0L
        readyAt = maxOf(readyAt, deadline(now, maxOf(spacingMs.coerceAtLeast(3_000L), serverWait, blockedWait)))
        // 取消不会在后台调度重试；若再次成为可见候选，仍受原次数和退避限制。
        if (result == null || result.outcome == ExcerptFetchOutcome.TRANSIENT_FAILURE) {
            entry.complete = entry.attempts >= attemptLimit
            entry.readyAt = deadline(now, maxOf(retryDelayMs, serverWait))
        } else {
            entry.complete = true
        }
    }

    @Synchronized
    fun completeWithoutRequest(userId: Long, topicId: Long) {
        entries.getOrPut(userId to topicId) { Entry() }.complete = true
    }

    @Synchronized
    fun remainingBudget(): Int = (requestLimit - issued).coerceAtLeast(0)

    private fun eligible(userId: Long, topicId: Long): Boolean {
        val entry = entries[userId to topicId]
        return topicId > 0L && entry?.complete != true && (entry?.attempts ?: 0) < attemptLimit
    }

    private fun deadline(now: Long, delayMs: Long): Long =
        if (delayMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMs
}

/**
 * 后台自动请求的独立计量池。
 *
 * 每个池子各自记录发车时间、429 惩罚与连击次数，互不牵连：未读轮询撞了限流，
 * 不会让摘要预取的惩罚期一起翻倍，反之亦然。
 *
 * 但 linux.do / Cloudflare 是按 IP + Cookie 合并计数的，客户端拆池只能控节奏，不能扩额度。
 * 所以这里只做"准入"，总闸仍是 [TopicRepository] 里的全局 429 冷却与读写双轨调度器，两层叠加：
 * - 池级：最小间隔 + 前台让行 + 撞墙后指数惩罚（后台请求不排队，直接跳过本轮）
 * - 全局：任何一次 429 都冻结所有自动请求，前台也一并退让
 */
enum class ApiQuotaPool(
    val label: String,
    /** 两次发车的最小间隔。0 表示不限（由全局读轨道兜底）。 */
    private val minIntervalMs: Long,
    /** 用户刚有前台请求或刚发过写请求时，本轮让出，避免贴脸叠加。 */
    private val yieldToForeground: Boolean,
    /** 撞到 429 后的基础惩罚时长，连续撞墙按 2^n 递增。 */
    private val penaltyBaseMs: Long,
) {
    /** 前台未读角标 / 通知分类轮询。 */
    UNREAD_POLL("未读轮询", 20_000L, true, 30_000L),

    /** 列表顶部"有 N 条新话题"的静默探测。 */
    INCOMING_PROBE("新话题探测", 10_000L, true, 40_000L),

    /** 详情页单一长轮询；消息密集时也至少间隔三秒，复用全局限流。 */
    TOPIC_EVENTS("话题订阅", 3_000L, true, 45_000L),
    TOPIC_POSTS("新增回复", 3_000L, true, 45_000L),
    TOPIC_SYNC("话题校准", 60_000L, true, 60_000L),

    /** 列表首楼摘要的静默预取。前台活跃时让行并保持最小间隔，避免与前台抢占额度触发 429。 */
    EXCERPT_PREFETCH("摘要预取", 3_000L, true, 45_000L),
    ;

    private var lastAcquiredAt = 0L
    private var penaltyUntil = 0L
    private var strikes = 0

    /**
     * 申请一次发车。返回 false 时调用方应静默跳过本轮，不要抛异常、不要排队。
     * [force] 只跳过最小间隔与前台让行（用户主动触发），惩罚期与全局冷却仍然生效。
     */
    fun tryAcquire(force: Boolean = false): Boolean {
        if (TopicRepository.isRateLimitCoolingDown()) return false
        if (TopicRepository.isSubsiteAuthInProgress()) return false
        // 子站授权或 timings 撞 CF 速率挑战后：后台池整体让行（含强制未读刷新），界面会在窗口结束后自然补拉。
        if (TopicRepository.postOAuthQuietRemainingMs() > 0L) return false
        if (TopicRepository.backgroundChallengeQuietRemainingMs() > 0L) return false
        // 内置浏览器（小程序 / 专属邮箱等）打开期间整体让行，用户主动操作也不例外，避免与页面内验证叠加计数。
        if (inAppBrowserPauseRemainingMs() > 0L) return false
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now < penaltyUntil) return false
            if (!force) {
                if (minIntervalMs > 0L && now - lastAcquiredAt < minIntervalMs) return false
                if (yieldToForeground && now - lastActivityAt() < FOREGROUND_QUIET_MS) return false
            }
            lastAcquiredAt = now
            return true
        }
    }

    /** 本池请求成功：清掉连击，惩罚期自然结束。 */
    fun onSuccess() {
        synchronized(this) { strikes = 0 }
    }

    /** 距离本池下次可发车还要等多久（含全局冷却），供轮询循环自适应休眠。 */
    fun remainingWaitMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val poolWait = synchronized(this) {
            maxOf(penaltyUntil - now, lastAcquiredAt + minIntervalMs - now)
        }
        val foregroundWait = if (yieldToForeground) lastActivityAt() + FOREGROUND_QUIET_MS - now else 0L
        return maxOf(
            poolWait, TopicRepository.rateLimitRemainingMs(), TopicRepository.postOAuthQuietRemainingMs(),
            TopicRepository.backgroundChallengeQuietRemainingMs(),
            foregroundWait, inAppBrowserPauseRemainingMs(), 0L,
        )
    }

    private fun penalize(globalCooldownMs: Long, cfChallenge: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            // 同一惩罚窗口的并发 429 只续期，不重复升级，否则首轮就会被顶到上限。
            val continuingPenalty = now < penaltyUntil
            val strikeLevel = if (continuingPenalty) (strikes - 1).coerceAtLeast(0) else strikes
            val factor = 1L shl strikeLevel.coerceAtMost(MAX_STRIKE_SHIFT)
            val base = if (cfChallenge) penaltyBaseMs * 2 else penaltyBaseMs
            val penalty = (base * factor).coerceAtMost(MAX_PENALTY_MS)
            // 惩罚期至少要盖过全局冷却，否则冷却一结束所有后台池会同一秒齐射
            penaltyUntil = maxOf(penaltyUntil, now + maxOf(penalty, globalCooldownMs))
            if (!continuingPenalty) strikes = (strikes + 1).coerceAtMost(MAX_STRIKE_SHIFT)
        }
    }

    private fun resumeAfterCircuitRecovery(now: Long, recoveryMs: Long) {
        val staggerMs = (ordinal + 1) * 1_000L + kotlin.random.Random.nextLong(0L, 1_000L)
        synchronized(this) {
            strikes = 0
            penaltyUntil = now + recoveryMs.coerceAtLeast(FOREGROUND_QUIET_MS) + staggerMs
        }
    }

    fun penaltyRemainingMs(): Long = synchronized(this) {
        (penaltyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    companion object {
        /** 前台请求 / 写请求之后，后台池需要让出的静默窗口。 */
        const val FOREGROUND_QUIET_MS = 3_000L
        private const val MAX_STRIKE_SHIFT = 3
        private const val MAX_PENALTY_MS = 1 * 60_000L

        @Volatile private var lastForegroundAt = 0L

        /** 用户直接触发的 GET 发出时调用。 */
        fun markForeground() {
            lastForegroundAt = SystemClock.elapsedRealtime()
        }

        private fun lastActivityAt(): Long = maxOf(lastForegroundAt, TopicRepository.lastPostRequestAt)

        /** 距离最近一次前台 GET 或写请求（含 timings）已静默的毫秒数。 */
        fun sinceLastForegroundActivityMs(): Long {
            val last = lastActivityAt()
            if (last <= 0L) return Long.MAX_VALUE
            return (SystemClock.elapsedRealtime() - last).coerceAtLeast(0L)
        }

        fun foregroundQuietRemainingMs(): Long = maxOf(
            (lastActivityAt() + FOREGROUND_QUIET_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
            inAppBrowserPauseRemainingMs(),
        )

        /** 内置浏览器关闭后的随机静默窗口，避免关闭瞬间与积压的轮询同秒齐发。 */
        private const val IN_APP_BROWSER_RESUME_MIN_MS = 3_000L
        private const val IN_APP_BROWSER_RESUME_MAX_MS = 5_000L
        /** 内置浏览器可见期间，轮询循环的复查间隔。 */
        private const val IN_APP_BROWSER_POLL_MS = 2_000L
        private val inAppBrowserVisibleCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var inAppBrowserQuietUntil = 0L
        @Volatile private var lastInAppBrowserClosedAt = 0L
        /** 最近一次回到前台的时间；进程刚启动时视为刚回前台。 */
        @Volatile private var lastAppForegroundAt = SystemClock.elapsedRealtime()

        fun markAppForeground() {
            lastAppForegroundAt = SystemClock.elapsedRealtime()
        }

        fun sinceAppForegroundMs(): Long = (SystemClock.elapsedRealtime() - lastAppForegroundAt).coerceAtLeast(0L)

        /** 内置浏览器正在打开，或在 [withinMs] 内刚关闭（同一出口 IP 刚有页面流量）。 */
        fun inAppBrowserUsedWithin(withinMs: Long): Boolean {
            if (inAppBrowserVisibleCount.get() > 0) return true
            val closedAt = lastInAppBrowserClosedAt
            return closedAt > 0L && SystemClock.elapsedRealtime() - closedAt < withinMs
        }

        /** 由 AppContainer 注入；App 切到后台时不因残留的浏览器容器压住后台通知。 */
        @Volatile var appForegroundProvider: () -> Boolean = { true }

        /** 内置浏览器容器进入/离开可见状态时调用，必须成对出现。 */
        fun onInAppBrowserVisible(visible: Boolean) {
            if (visible) {
                inAppBrowserVisibleCount.incrementAndGet()
            } else {
                val left = inAppBrowserVisibleCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (left == 0) {
                    lastInAppBrowserClosedAt = SystemClock.elapsedRealtime()
                    inAppBrowserQuietUntil = SystemClock.elapsedRealtime() +
                        kotlin.random.Random.nextLong(IN_APP_BROWSER_RESUME_MIN_MS, IN_APP_BROWSER_RESUME_MAX_MS + 1)
                }
            }
        }

        /** 内置浏览器打开期间或关闭后的静默窗口内，自动请求应让行的剩余毫秒。 */
        fun inAppBrowserPauseRemainingMs(): Long {
            if (!runCatching { appForegroundProvider() }.getOrDefault(true)) return 0L
            if (inAppBrowserVisibleCount.get() > 0) return IN_APP_BROWSER_POLL_MS
            return (inAppBrowserQuietUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

        /**
         * 服务端任意一次 429 都会进到这里：服务端是全局计数的，撞墙说明总量超了，
         * 所有后台池都要退让；被 CF 质询页拦下（cf-mitigated=challenge）时惩罚加倍。
         */
        fun onServerRateLimited(globalCooldownMs: Long, cfChallenge: Boolean) {
            entries.forEach { it.penalize(globalCooldownMs, cfChallenge) }
        }

        /** 主站探针已解除客户端熔断；后台池按恢复期错峰重启，不再保留原来的长惩罚。 */
        fun onMainSiteCircuitRecovered(recoveryMs: Long) {
            val now = SystemClock.elapsedRealtime()
            entries.forEach { it.resumeAfterCircuitRecovery(now, recoveryMs) }
        }
    }
}

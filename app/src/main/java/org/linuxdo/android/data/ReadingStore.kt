package org.linuxdo.android.data

import android.content.Context
import androidx.core.content.edit

/**
 * 阅读状态的本地持久化:已读话题集合 + 是否同步阅读进度到服务端。
 *
 * 关闭同步时只在本地标记已读；开启时仅同步前台实际阅读的楼层与时间。
 */
class ReadingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var currentUserId: Long = 0L

    private fun seenKey(userId: Long = currentUserId) =
        if (userId > 0L) "${KEY_SEEN_TOPICS}_$userId" else KEY_SEEN_TOPICS

    private fun postCountsKey(userId: Long = currentUserId) =
        if (userId > 0L) "${KEY_SEEN_POST_COUNTS}_$userId" else KEY_SEEN_POST_COUNTS

    private fun lastPostsKey(userId: Long = currentUserId) =
        if (userId > 0L) "${KEY_LAST_SEEN_POSTS}_$userId" else KEY_LAST_SEEN_POSTS

    private val seen: LinkedHashSet<Long> = linkedSetOf()
    private val seenPostsCount: LinkedHashMap<Long, Int> = linkedMapOf()
    private val lastSeenPostNumbers: LinkedHashMap<Long, Int> = linkedMapOf()
    private val confirmedReads = ConfirmedReadPosts()

    init {
        synchronized(seen) {
            synchronized(lastSeenPostNumbers) {
                loadForUser(currentUserId)
            }
        }
    }

    private fun loadForUser(userId: Long) {
        val sKey = seenKey(userId)
        val cKey = postCountsKey(userId)
        val lKey = lastPostsKey(userId)

        var seenRaw = prefs.getString(sKey, null)
        var countRaw = prefs.getString(cKey, null)
        var lastPostsRaw = prefs.getString(lKey, null)

        // 迁移旧版未带 userId 的全局数据
        if (seenRaw == null && userId > 0L) {
            val legacySeen = prefs.getString(KEY_SEEN_TOPICS, null)
            if (!legacySeen.isNullOrBlank()) {
                seenRaw = legacySeen
                countRaw = prefs.getString(KEY_SEEN_POST_COUNTS, null)
                lastPostsRaw = prefs.getString(KEY_LAST_SEEN_POSTS, null)
                prefs.edit {
                    putString(sKey, seenRaw)
                    putString(cKey, countRaw)
                    putString(lKey, lastPostsRaw)
                    remove(KEY_SEEN_TOPICS)
                    remove(KEY_SEEN_POST_COUNTS)
                    remove(KEY_LAST_SEEN_POSTS)
                }
            }
        }

        seen.clear()
        seenRaw?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.let(seen::addAll)

        seenPostsCount.clear()
        countRaw?.split(',')
            ?.forEach { entry ->
                val parts = entry.split(':')
                if (parts.size == 2) {
                    val id = parts[0].trim().toLongOrNull()
                    val count = parts[1].trim().toIntOrNull()
                    if (id != null && count != null && count > 0) {
                        seenPostsCount[id] = count
                    }
                }
            }

        lastSeenPostNumbers.clear()
        lastPostsRaw?.split(',')
            ?.forEach { entry ->
                val parts = entry.split(':')
                if (parts.size == 2) {
                    val id = parts[0].trim().toLongOrNull()
                    val postNumber = parts[1].trim().toIntOrNull()
                    if (id != null && id > 0L && postNumber != null && postNumber > 0) {
                        lastSeenPostNumbers[id] = postNumber
                    }
                }
            }
    }

    fun switchUser(userId: Long) {
        val targetId = maxOf(0L, userId)
        if (currentUserId == targetId) return
        synchronized(seen) {
            synchronized(lastSeenPostNumbers) {
                currentUserId = targetId
                confirmedReads.switchUser(targetId)
                loadForUser(targetId)
            }
        }
    }

    @Volatile
    var syncReadProgress: Boolean = prefs.getBoolean(KEY_SYNC_READ_PROGRESS, true)
        private set

    fun setSyncReadProgress(enabled: Boolean) {
        if (syncReadProgress == enabled) return
        syncReadProgress = enabled
        prefs.edit { putBoolean(KEY_SYNC_READ_PROGRESS, enabled) }
    }

    fun seenTopicIds(): Set<Long> = if (syncReadProgress) confirmedReads.topicIds() else synchronized(seen) { seen.toSet() }

    fun isSeen(topicId: Long): Boolean = if (syncReadProgress) confirmedReads.posts(topicId).isNotEmpty()
        else synchronized(seen) { topicId in seen }

    fun confirmedPostNumbers(topicId: Long): Set<Int> = confirmedReads.posts(topicId)

    fun confirmPosts(userId: Long, topicId: Long, postNumbers: Set<Int>): Set<Int> =
        confirmedReads.confirm(userId, topicId, postNumbers)

    fun mergeReadState(topic: Topic): Topic = if (syncReadProgress) {
        mergeConfirmedTopicRead(topic, confirmedReads.posts(topic.id))
    } else if (topic.unseen && isUnreadExcluded(topic.id, topic.postsCount)) {
        topic.copy(unseen = false)
    } else topic

    fun getLastSeenPost(topicId: Long): Int? = synchronized(lastSeenPostNumbers) {
        lastSeenPostNumbers[topicId]
    }

    fun recordLastSeenPost(topicId: Long, postNumber: Int) {
        if (topicId <= 0L || postNumber <= 0) return
        val (serialized, key) = synchronized(lastSeenPostNumbers) {
            val previous = lastSeenPostNumbers[topicId] ?: 0
            if (postNumber <= previous) return
            lastSeenPostNumbers.remove(topicId)
            lastSeenPostNumbers[topicId] = postNumber
            while (lastSeenPostNumbers.size > MAX_SEEN_TOPICS) {
                lastSeenPostNumbers.remove(lastSeenPostNumbers.keys.first())
            }
            lastSeenPostNumbers.entries.joinToString(",") { "${it.key}:${it.value}" } to lastPostsKey()
        }
        prefs.edit { putString(key, serialized) }
    }

    /**
     * 开启同步时，只按成功回执与服务端最高楼层判断是否读完。
     * 关闭同步时，沿用本地浏览记录判断是否应从「未读」列表排除：
     * - 未在本地点开过 -> false（保留展示）
     * - 已点开过，且当前总楼层数 <= 本地已读记录楼层数（或旧记录未存楼层数） -> true（排除）
     * - 已点开过，但服务端总楼层数增长超过了本地记录 -> false（有新回复，重新展示）
     */
    fun isUnreadExcluded(topicId: Long, currentPostsCount: Int, highestPostNumber: Int? = null): Boolean {
        if (syncReadProgress) {
            return confirmedReads.hasReadThrough(topicId, highestPostNumber)
        }
        return synchronized(seen) {
            if (topicId !in seen) return@synchronized false
            val recorded = seenPostsCount[topicId] ?: 0
            recorded <= 0 || currentPostsCount <= recorded
        }
    }

    /** 返回 true 表示这次是新标记已读或更新了已读楼层上限，调用方可据此刷新界面。 */
    fun markSeen(topicId: Long, postsCount: Int = 0): Boolean {
        if (topicId <= 0L) return false
        var changed = false
        var seenCsv = ""
        var countsCsv = ""
        val (sKey, cKey) = synchronized(seen) {
            // 重新 add 一次把它挪到队尾,保证活跃话题不会被当成最早的淘汰掉。
            val fresh = seen.remove(topicId).not()
            seen.add(topicId)
            val prevCount = seenPostsCount[topicId] ?: 0
            val countUpdated = postsCount > prevCount
            if (postsCount > 0) {
                seenPostsCount[topicId] = maxOf(prevCount, postsCount)
            }
            while (seen.size > MAX_SEEN_TOPICS) {
                val oldest = seen.firstOrNull() ?: break
                seen.remove(oldest)
                seenPostsCount.remove(oldest)
            }
            changed = fresh || countUpdated
            seenCsv = seen.joinToString(",")
            countsCsv = seenPostsCount.entries.joinToString(",") { "${it.key}:${it.value}" }
            seenKey() to postCountsKey()
        }
        prefs.edit {
            putString(sKey, seenCsv)
            putString(cKey, countsCsv)
        }
        return changed
    }

    private companion object {
        const val PREFS_NAME = "reading_preferences"
        const val KEY_SEEN_TOPICS = "seen_topic_ids"
        const val KEY_SEEN_POST_COUNTS = "seen_topic_post_counts"
        const val KEY_LAST_SEEN_POSTS = "seen_topic_last_posts"
        const val KEY_SYNC_READ_PROGRESS = "sync_read_progress"
        const val MAX_SEEN_TOPICS = 2_000
    }
}

/** 与 Fluxdo 的话题会话状态一致：仅保存当前账号收到成功回执的精确楼层。 */
internal class ConfirmedReadPosts(private val capacity: Int = 200) {
    private var userId = 0L
    private val topics = LinkedHashMap<Long, Set<Int>>(16, 0.75f, true)

    @Synchronized
    fun switchUser(value: Long) {
        if (userId == value) return
        userId = value
        topics.clear()
    }

    @Synchronized
    fun confirm(accountId: Long, topicId: Long, postNumbers: Set<Int>): Set<Int> {
        if (accountId <= 0L || accountId != userId || topicId <= 0L) return emptySet()
        val valid = postNumbers.filter { it > 0 }.toSet()
        if (valid.isEmpty()) return topics[topicId].orEmpty()
        val merged = topics[topicId].orEmpty() + valid
        topics[topicId] = merged
        while (topics.size > capacity) topics.remove(topics.keys.first())
        return merged
    }

    @Synchronized
    fun posts(topicId: Long): Set<Int> = topics[topicId].orEmpty()

    @Synchronized
    fun hasReadThrough(topicId: Long, highestPostNumber: Int?): Boolean {
        val highest = highestPostNumber?.takeIf { it > 0 } ?: return false
        return (topics[topicId]?.maxOrNull() ?: 0) >= highest
    }

    @Synchronized
    fun topicIds(): Set<Long> = topics.keys.toSet()
}

internal data class ReadTimingBatch(val topicTimeMs: Long, val postTimesMs: Map<Int, Long>)

/** 新未读楼层提前合批，成功后留出间隔；失败按 Fluxdo 的 5/10/20/40 秒退避。 */
internal class ReadReportSchedule(
    startedAt: Long,
    private val heartbeatMs: Long = 35_000L,
    private val minimumGapMs: Long = 3_000L,
) {
    private var nextHeartbeatAt = startedAt + heartbeatMs
    private var nextAllowedAt = startedAt
    private var failures = 0

    fun canSend(now: Long, rush: Boolean): Boolean =
        now >= nextAllowedAt && (rush || now >= nextHeartbeatAt)

    fun completed(now: Long, success: Boolean, serverCooldownMs: Long = 0L) {
        failures = if (success) 0 else (failures + 1).coerceAtMost(4)
        val wait = if (success) minimumGapMs else 5_000L shl (failures - 1)
        nextAllowedAt = now + maxOf(wait, serverCooldownMs)
        nextHeartbeatAt = now + heartbeatMs
    }
}

/** 只累计当前可见楼层的单调时钟增量，网络发送与计时互不阻塞。 */
internal class TopicReadTracker {
    private var active = false
    private var visiblePosts = emptySet<Int>()
    private var lastTickAt = 0L
    private var lastScrolledAt = 0L
    private var topicTimeMs = 0L
    private val postTimesMs = linkedMapOf<Int, Long>()

    fun setActive(value: Boolean, now: Long) {
        if (active == value) return
        active = value
        discardPending(now)
        if (value) lastScrolledAt = now
    }

    fun setVisible(postNumbers: Collection<Int>, now: Long) {
        tick(now)
        visiblePosts = postNumbers.filter { it > 0 }.toSet()
        // 短暂掠过的楼层不留待以后凑足阈值。
        postTimesMs.entries.removeAll { it.key !in visiblePosts && it.value < MIN_POST_DWELL_MS }
    }

    fun scrolled(now: Long) {
        tick(now)
        if (active) lastScrolledAt = now
    }

    fun tick(now: Long) {
        val end = minOf(now, lastScrolledAt + IDLE_TIMEOUT_MS)
        val elapsed = (end - lastTickAt).coerceAtLeast(0L)
        lastTickAt = now
        if (!active || visiblePosts.isEmpty() || elapsed == 0L) return
        topicTimeMs += elapsed
        visiblePosts.forEach { number -> postTimesMs[number] = (postTimesMs[number] ?: 0L) + elapsed }
    }

    fun isIdle(now: Long): Boolean = !active || now - lastScrolledAt > IDLE_TIMEOUT_MS

    fun hasRush(alreadyReadPosts: Set<Int>): Boolean =
        topicTimeMs > 0L && postTimesMs.any { (number, time) -> time >= MIN_POST_DWELL_MS && number !in alreadyReadPosts }

    fun nextCheckDelayMs(alreadyReadPosts: Set<Int>): Long {
        if (!active || visiblePosts.isEmpty()) return 1_000L
        val maxUnreadElapsed = visiblePosts.asSequence()
            .filter { it !in alreadyReadPosts }
            .map { postTimesMs[it] ?: 0L }
            .filter { it in 1 until MIN_POST_DWELL_MS }
            .maxOrNull() ?: return 1_000L
        return (MIN_POST_DWELL_MS - maxUnreadElapsed).coerceIn(100L, 1_000L)
    }

    fun takeBatch(now: Long, maxPosts: Int): ReadTimingBatch? {
        tick(now)
        val times = postTimesMs.entries.asSequence()
            .filter { it.value >= MIN_POST_DWELL_MS }
            .take(maxPosts)
            .associate { it.key to it.value }
        if (times.isEmpty() || topicTimeMs <= 0L) return null
        val batch = ReadTimingBatch(topicTimeMs, times)
        times.keys.forEach(postTimesMs::remove)
        topicTimeMs = 0L
        return batch
    }

    fun restoreBatch(batch: ReadTimingBatch) {
        if (!active) return
        topicTimeMs = (topicTimeMs + batch.topicTimeMs).coerceAtMost(6 * 60_000L)
        batch.postTimesMs.forEach { (number, time) ->
            postTimesMs[number] = ((postTimesMs[number] ?: 0L) + time).coerceAtMost(6 * 60_000L)
        }
    }

    fun discardPending(now: Long) {
        postTimesMs.clear()
        topicTimeMs = 0L
        lastTickAt = now
    }

    companion object {
        const val MIN_POST_DWELL_MS = 1_000L
        const val IDLE_TIMEOUT_MS = 3 * 60_000L
    }
}

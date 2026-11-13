package org.linuxdo.android.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ReadingHistoryEntry(
    val topicId: Long,
    val title: String,
    val lastSeenAt: Long,
    val lastSeenPostNumber: Int = 0,
    val authorUsername: String? = null,
    val avatarTemplate: String? = null,
    val categoryName: String? = null,
    val categoryColor: String? = null,
    val totalReplies: Int = 0,
)

class HistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var currentUserId: Long = 0L

    @Synchronized
    fun switchUser(userId: Long) {
        currentUserId = maxOf(0L, userId)
    }

    private fun storageKey(userId: Long): String =
        if (userId > 0L) "${KEY_ENTRIES}_$userId" else KEY_ENTRIES

    @Synchronized
    fun entries(userId: Long = currentUserId): List<ReadingHistoryEntry> {
        val key = storageKey(userId)
        val raw = preferences.getString(key, null)
        if (raw == null && userId > 0L) {
            val legacy = preferences.getString(KEY_ENTRIES, null)
            if (!legacy.isNullOrBlank()) {
                preferences.edit {
                    putString(key, legacy)
                    remove(KEY_ENTRIES)
                }
                return runCatching {
                    json.decodeFromString<List<ReadingHistoryEntry>>(legacy)
                }.getOrDefault(emptyList()).sortedByDescending { it.lastSeenAt }
            }
        }
        return runCatching {
            json.decodeFromString<List<ReadingHistoryEntry>>(raw.orEmpty())
        }.getOrDefault(emptyList()).sortedByDescending { it.lastSeenAt }
    }

    @Synchronized
    fun record(
        topicId: Long,
        title: String,
        postNumber: Int = 0,
        authorUsername: String? = null,
        avatarTemplate: String? = null,
        categoryName: String? = null,
        categoryColor: String? = null,
        totalReplies: Int = 0,
        userId: Long = currentUserId,
    ) {
        if (topicId <= 0L || title.isBlank()) return
        val current = entries(userId).toMutableList()
        val previous = current.firstOrNull { it.topicId == topicId }
        current.removeAll { it.topicId == topicId }
        current.add(
            0,
            ReadingHistoryEntry(
                topicId = topicId,
                title = title,
                lastSeenAt = System.currentTimeMillis(),
                lastSeenPostNumber = maxOf(previous?.lastSeenPostNumber ?: 0, postNumber),
                authorUsername = authorUsername ?: previous?.authorUsername,
                avatarTemplate = avatarTemplate ?: previous?.avatarTemplate,
                categoryName = categoryName ?: previous?.categoryName,
                categoryColor = categoryColor ?: previous?.categoryColor,
                totalReplies = if (totalReplies > 0) totalReplies else (previous?.totalReplies ?: 0),
            ),
        )
        val raw = json.encodeToString(current.take(MAX_ENTRIES))
        preferences.edit { putString(storageKey(userId), raw) }
    }

    @Synchronized
    fun clear(userId: Long = currentUserId) {
        preferences.edit { remove(storageKey(userId)) }
    }

    @Synchronized
    fun updateMetadata(userId: Long, topicId: Long, title: String, categoryName: String?, categoryColor: String?) {
        val current = entries(userId)
        val updated = current.map { if (it.topicId == topicId) it.copy(title = title, categoryName = categoryName, categoryColor = categoryColor) else it }
        if (updated != current) check(preferences.edit().putString(storageKey(userId), json.encodeToString(updated)).commit()) { "历史记录更新失败" }
    }

    private companion object {
        const val PREFERENCES = "reading_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 500
    }
}

@Serializable
data class TopicReadingPosition(
    val postNumber: Int = 1,
    val partIndex: Int = 0,
    val offset: Int = 0,
    val atTop: Boolean = true,
    val filterUserId: Long? = null,
    val descending: Boolean = false,
    val collapsed: List<Long> = emptyList(),
    val expanded: List<Long> = emptyList(),
) : java.io.Serializable

@Serializable
data class SavedTopic(
    val topicId: Long,
    val title: String,
    val savedAt: Long,
    val categoryName: String? = null,
    val categoryColor: String? = null,
    val totalPosts: Int = 1,
    val pinned: Boolean = false,
    val position: TopicReadingPosition = TopicReadingPosition(),
)

/** 暂存不设置过期时间；账号切换只切换读写分区，清缓存不清暂存。 */
class SavedTopicStore(context: Context) {
    private val preferences = context.getSharedPreferences("saved_topics", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun entries(userId: Long): List<SavedTopic> {
        if (userId <= 0L) return emptyList()
        val raw = preferences.getString("entries_$userId", null) ?: return emptyList()
        return json.decodeFromString<List<SavedTopic>>(raw).filter { it.topicId > 0L }.distinctBy { it.topicId }
    }

    @Synchronized
    fun add(userId: Long, entry: SavedTopic): List<SavedTopic> {
        require(userId > 0L && entry.topicId > 0L)
        val current = entries(userId)
        if (current.any { it.topicId == entry.topicId }) return current
        check(current.size < 10) { "最多暂存 10 篇，请先移除不需要的话题" }
        return write(userId, current + entry)
    }

    @Synchronized
    fun updatePosition(userId: Long, topicId: Long, position: TopicReadingPosition, totalPosts: Int = 0): List<SavedTopic> {
        val current = entries(userId)
        if (current.none { it.topicId == topicId }) return current
        val updated = current.map {
            if (it.topicId == topicId) it.copy(position = position, totalPosts = maxOf(it.totalPosts, totalPosts, position.postNumber)) else it
        }
        return if (updated == current) current else write(userId, updated)
    }

    /** 用列表探测或实时消息带回的楼层数刷新总数；不新增条目，已移除的话题直接忽略。 */
    @Synchronized
    fun updateTotals(userId: Long, totals: Map<Long, Int>): List<SavedTopic> {
        val current = entries(userId)
        val updated = current.map { entry ->
            val fresh = totals[entry.topicId]?.takeIf { it > 0 } ?: return@map entry
            entry.copy(totalPosts = maxOf(fresh, entry.position.postNumber, 1))
        }
        return if (updated == current) current else write(userId, updated)
    }

    @Synchronized
    fun togglePin(userId: Long, topicId: Long): List<SavedTopic> {
        val current = entries(userId)
        return write(userId, current.map { if (it.topicId == topicId) it.copy(pinned = !it.pinned) else it })
    }

    @Synchronized
    fun updateMetadata(userId: Long, topicId: Long, title: String, categoryName: String?, categoryColor: String?): List<SavedTopic> {
        val current = entries(userId)
        val updated = current.map { if (it.topicId == topicId) it.copy(title = title, categoryName = categoryName, categoryColor = categoryColor) else it }
        return if (updated == current) current else write(userId, updated)
    }

    @Synchronized
    fun remove(userId: Long, topicId: Long): List<SavedTopic> {
        return write(userId, entries(userId).filterNot { it.topicId == topicId })
    }

    @Synchronized
    fun clear(userId: Long): List<SavedTopic> {
        return write(userId, emptyList())
    }

    @Synchronized
    fun restore(userId: Long, removed: List<SavedTopic>): List<SavedTopic> {
        val current = entries(userId)
        val combined = current + removed.filter { entry -> current.none { it.topicId == entry.topicId } }
        check(combined.size <= 10) { "撤销后将超过 10 篇，请先整理暂存" }
        return write(userId, combined)
    }

    private fun write(userId: Long, entries: List<SavedTopic>): List<SavedTopic> {
        require(userId > 0L)
        check(preferences.edit().putString("entries_$userId", json.encodeToString(entries)).commit()) {
            "暂存保存失败，请重试"
        }
        return entries
    }
}

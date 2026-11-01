package org.linuxdo.android.push

/**
 * 推送的安全核心。端点、正文清洗、去重和游标决策都驻留在 libpush_core.so，
 * Kotlin 只保留 Android JSON 读取和系统通知提交。
 */
internal object NativePushCore {
    private const val TOKEN_NOTIFICATIONS_PATH = 0
    private const val TOKEN_POSTS_BY_NUMBER_PATH = 1

    const val FIELD_ID = 2
    const val FIELD_READ = 3
    const val FIELD_NOTIFICATION_TYPE = 4
    const val FIELD_TOPIC_ID = 5
    const val FIELD_POST_NUMBER = 6
    const val FIELD_DATA = 7
    const val FIELD_TOPIC_TITLE = 8
    const val FIELD_DISPLAY_USERNAME = 9
    const val FIELD_ORIGINAL_USERNAME = 10
    const val FIELD_ORIGINAL_TEXT = 11
    const val FIELD_EXCERPT = 12
    const val FIELD_COOKED = 13
    const val FIELD_RAW = 14
    const val FIELD_NOTIFICATIONS = 15

    const val DECISION_IGNORE = 0
    const val DECISION_DELIVER = 1
    const val DECISION_SCOPE = 2
    const val DECISION_DELIVERED = 3
    const val DECISION_CAP = 4

    init {
        System.loadLibrary("push_core")
    }

    fun notificationsPath(): String = nativeGetToken(TOKEN_NOTIFICATIONS_PATH)

    fun postsByNumberPath(): String = nativeGetToken(TOKEN_POSTS_BY_NUMBER_PATH)

    fun field(token: Int): String = nativeGetToken(token)

    fun sanitizeContent(rawHtml: String): String = nativeSanitizeContent(rawHtml).take(240)

    fun countUnread(types: IntArray, reads: BooleanArray, scope: PushNotificationScope): Int {
        require(types.size == reads.size) { "通知状态数组长度不一致" }
        return nativeCountUnread(types, reads, scope.rawValue)
    }

    fun baselineCursor(ids: LongArray): Long = nativeBaselineCursor(ids)

    /**
     * 本页最大通知 id。与基线取值共用同一 native 实现，
     * 用于判定本地游标是否已超前于服务端（游标自愈的唯一依据）。
     */
    fun pageMaxId(ids: LongArray): Long = nativeBaselineCursor(ids)

    fun shouldFetchExcerpt(notificationType: Int): Boolean = nativeShouldFetchExcerpt(notificationType)

    fun decide(
        ids: LongArray,
        types: IntArray,
        reads: BooleanArray,
        topicIds: LongArray,
        postNumbers: IntArray,
        lastCursor: Long,
        scope: PushNotificationScope,
        maxPush: Int,
    ): LongArray {
        require(ids.size == types.size && ids.size == reads.size && ids.size == topicIds.size && ids.size == postNumbers.size) {
            "通知记录数组长度不一致"
        }
        val result = nativeDecide(ids, types, reads, topicIds, postNumbers, lastCursor, scope.rawValue, maxPush)
        check(result.size == ids.size + 1) { "Native 推送决策结果无效" }
        return result
    }

    fun cursor(result: LongArray): Long = result.firstOrNull() ?: 0L

    fun decisionAt(result: LongArray, index: Int): Int = result[index + 1].toInt()

    fun finalizeCursor(
        ids: LongArray,
        decisions: LongArray,
        delivered: BooleanArray,
        lastCursor: Long,
    ): Long {
        require(ids.size == delivered.size && decisions.size == ids.size + 1) {
            "Native 推送提交结果长度不一致"
        }
        return nativeFinalizeCursor(ids, decisions.copyOfRange(1, decisions.size), delivered, lastCursor)
    }

    fun markDelivered(notificationId: Long, topicId: Long, postNumber: Int) {
        nativeMarkDelivered(notificationId, topicId, postNumber)
    }

    private external fun nativeGetToken(token: Int): String
    private external fun nativeSanitizeContent(rawHtml: String): String
    private external fun nativeCountUnread(types: IntArray, reads: BooleanArray, scope: Int): Int
    private external fun nativeShouldFetchExcerpt(notificationType: Int): Boolean
    private external fun nativeBaselineCursor(ids: LongArray): Long
    private external fun nativeDecide(
        ids: LongArray,
        types: IntArray,
        reads: BooleanArray,
        topicIds: LongArray,
        postNumbers: IntArray,
        lastCursor: Long,
        scope: Int,
        maxPush: Int,
    ): LongArray
    private external fun nativeMarkDelivered(notificationId: Long, topicId: Long, postNumber: Int)
    private external fun nativeFinalizeCursor(
        ids: LongArray,
        decisions: LongArray,
        delivered: BooleanArray,
        lastCursor: Long,
    ): Long
}

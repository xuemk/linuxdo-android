package org.linuxdo.android.ui

import android.app.Application
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.text.Spanned
import android.widget.Toast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.linuxdo.android.data.SavedTopic
import org.linuxdo.android.data.SavedTopicStore
import org.linuxdo.android.data.TopicReadingPosition
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import org.linuxdo.android.data.TopicBusMessage
import org.linuxdo.android.data.mergeTopicCounters
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import org.linuxdo.android.App
import org.linuxdo.android.BuildConfig
import org.linuxdo.android.Config
import org.linuxdo.android.data.ApiQuotaPool
import org.linuxdo.android.data.TopicOpStore
import org.linuxdo.android.net.BrowserSession
import org.linuxdo.android.net.loadDohServers
import org.linuxdo.android.service.NotificationWorker
import org.linuxdo.android.service.NotificationKeepAliveService
import org.linuxdo.android.service.NotificationHelper
import org.linuxdo.android.service.ApkUpdateInstaller
import org.linuxdo.android.data.TopicRepository
import org.linuxdo.android.data.EditorSuggestion
import org.linuxdo.android.data.EditorCompletionRequest
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.data.Topic
import org.linuxdo.android.data.ExcerptCache
import org.linuxdo.android.data.ReadTimingBatch
import org.linuxdo.android.data.ReadReportSchedule
import org.linuxdo.android.data.TopicReadTracker
import org.linuxdo.android.data.AccountSession
import org.linuxdo.android.data.AccountManager
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.data.UserCardData
import org.linuxdo.android.data.AvatarCache
import org.linuxdo.android.data.isAnimatedAvatarPath
import org.linuxdo.android.data.ChatChannelSummary
import org.linuxdo.android.data.ChatMessageSummary
import org.linuxdo.android.data.TrustReport
import org.linuxdo.android.data.LdcUserInfo
import org.linuxdo.android.data.FollowUser
import org.linuxdo.android.data.BlockedUser
import org.linuxdo.android.data.DiscourseTagInfo
import org.linuxdo.android.data.DiscourseTagSearchResult
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.TopicSortOrder
import org.linuxdo.android.data.FloatingAction
import org.linuxdo.android.data.flattenCategories
import org.linuxdo.android.data.fixedTagsForCategory
import org.linuxdo.android.data.CustomSection
import org.linuxdo.android.data.HomeFeedType
import org.linuxdo.android.data.Post
import org.linuxdo.android.data.TopicDetail
import org.linuxdo.android.data.TopicListItem
import org.linuxdo.android.data.UserRepliesUiState
import org.linuxdo.android.data.UserReplyItem
import org.linuxdo.android.data.DraftItem
import org.linuxdo.android.data.AttachedMedia
import org.linuxdo.android.data.SearchOrder
import org.linuxdo.android.data.ProfileSection
import org.linuxdo.android.data.ProfilePage
import org.linuxdo.android.data.NotificationFilter
import org.linuxdo.android.data.UnreadSummary
import org.linuxdo.android.data.UploadLimits
import org.linuxdo.android.data.effectiveReplyToPostNumber
import org.linuxdo.android.data.threadRows
import org.linuxdo.android.data.TopicNotificationLevel
import org.linuxdo.android.data.PostResult
import org.linuxdo.android.data.PostFlagReason
import org.linuxdo.android.data.Reaction
import org.linuxdo.android.data.UserBadgeItem
import org.linuxdo.android.data.AutoRefreshInterval
import org.linuxdo.android.data.NotificationPollInterval
import org.linuxdo.android.data.NotificationScope
import org.linuxdo.android.net.HttpStatusException
import org.linuxdo.android.net.LoginRejectedException
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.net.NetworkTimeoutException
import org.linuxdo.android.net.RateLimitException
import org.linuxdo.android.net.RiskControlException
import org.linuxdo.android.net.SecondFactorRequiredException
import org.linuxdo.android.ui.screen.TopicListUiState
import org.linuxdo.android.data.TopicSearchHit
import org.linuxdo.android.data.TopicMetadata
import org.linuxdo.android.data.TopicEditRules
import org.linuxdo.android.data.TopicMetadataPatch
import org.linuxdo.android.data.topicMetadataPatch
import org.linuxdo.android.data.validateTopicMetadata

enum class Screen { Boot, Login, Verify, List }

data class UpdateInfo(
    val latestVersion: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val mandatory: Boolean,
    /** 发布清单里的可选校验和；为空表示不校验，只有 version.json 路径会带上它。 */
    val apkSha256: String? = null,
)

/** 应用内下载安装的阶段。 */
enum class ApkUpdateStage { Idle, Downloading, Verifying, AwaitingPermission, Installing, Failed }

data class UpdateUiState(
    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore("-"),
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val checking: Boolean = false,
    val manualChecking: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val showDialog: Boolean = false,
    val statusMessage: String? = null,
    val apkStage: ApkUpdateStage = ApkUpdateStage.Idle,
    val apkDownloadedBytes: Long = 0L,
    /** 总字节数；[ApkUpdateInstaller.TOTAL_UNKNOWN] 表示服务端没给 Content-Length。 */
    val apkTotalBytes: Long = 0L,
    val apkError: String? = null,
)

data class LoginUiState(
    val loading: Boolean = false,
    val sendingCode: Boolean = false,
    val error: String? = null,
    val emailSent: Boolean = false,
    val secondFactorRequired: Boolean = false,
    val backupEnabled: Boolean = false,
    val showWebLoginDialog: Boolean = false,
    val isRiskOrCaptchaBlocked: Boolean = false,
    val showRiskControlDialog: Boolean = false,
    val webAuthLoading: Boolean = false,
    val webAuthMessage: String? = null,
    val savedLogin: String = "",
    val savedPassword: String = "",
    val rememberPassword: Boolean = false,
)
enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

enum class UnreadDotPosition(val id: String, val label: String) {
    Avatar("avatar", "显示在头像上"),
    FloorRight("floor_right", "显示在楼层右边");

    companion object {
        fun fromId(id: String?): UnreadDotPosition =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Avatar
    }
}

enum class TopicUnreadDotPosition(val id: String, val label: String) {
    TitleLeft("title_left", "显示在话题最开始左侧"),
    Avatar("avatar", "显示在图像右上角");

    companion object {
        fun fromId(id: String?): TopicUnreadDotPosition =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Avatar
    }
}
enum class DetailLoad { Topic, Next, Previous }

private const val TOPIC_DETAIL_CACHE_CAPACITY = 20
private const val TOPIC_DETAIL_CACHE_TTL_MS = 3 * 60 * 1_000L
private const val SCROLL_IDLE_DELAY_MS = 400L
private const val UNREAD_CHALLENGE_BACKOFF_MS = 3 * 60 * 1_000L
private const val APP_PATH_PROBE_FIRST_DELAY_MS = 2_500L
private const val APP_PATH_PROBE_INTERVAL_MS = 3_000L

data class DetailUiState(
    val topic: TopicDetail? = null,
    val posts: List<Post> = emptyList(),
    val consumedIds: Set<Long> = emptySet(),
    val category: Category? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val replyCursors: Map<Long, Int> = emptyMap(),
    val completedReplies: Set<Long> = emptySet(),
    val loadingReplies: Set<Long> = emptySet(),
    val reactingPosts: Set<Long> = emptySet(),
    val reactionErrors: Map<Long, String> = emptyMap(),
    val emojiUrls: Map<String, String> = emptyMap(),
    val windowStartId: Long? = null,
    val failedLoad: DetailLoad = DetailLoad.Topic,
    /**
     * 刚发出去的楼层 id。界面据此滚过去并高亮,用完调 clearCreatedPost 清掉。
     * 放在这里而不是让界面自己猜"楼层数变多了":它和新楼层是同一次状态更新送达的,
     * 界面不会读到"编辑器已关、但列表还没更新"的中间态。
     */
    val createdPostId: Long? = null,
    val filterUserId: Long? = null,
    /** 评论区是否倒序(最新一级主评论在前)。1 楼主贴永远置顶,不参与翻转。 */
    val descending: Boolean = false,
    val noticeMessage: String? = null,
    val flagDialogPost: Post? = null,
    val flagError: String? = null,
    val flaggedPosts: Set<Long> = emptySet(),
    val notificationLevelUpdating: Boolean = false,
    /**
     * 定位版本号。每次带 postNumber 进入话题时（无论是否命中缓存）都递增，
     * TopicDetailScreen 据此重置 located 标志并强制重新执行滚动定位，
     * 彻底消除"已读过的帖子点通知后停在旧缓存位置"的问题。
     */
    val locateVersion: Int = 0,
    /** 显式跳楼/通知的目标，与不断前进的已读游标分开。 */
    val locatePostNumber: Int? = null,
    val realtimeVersion: Int = 0,
    /** 新消息尚未落地的内容由静默队列负责，避免界面的普通分页抢先请求。 */
    val realtimePostIds: Set<Long> = emptySet(),
    val confirmedReadPostNumbers: Set<Int> = emptySet(),
) {
    val notificationLevel: TopicNotificationLevel
        get() = TopicNotificationLevel.fromCode(topic?.details?.notificationLevel)

    private val windowStart: Int get() = topic?.postStream?.stream.orEmpty().indexOf(windowStartId).coerceAtLeast(0)
    val remaining: List<Long> get() {
        val stream = topic?.postStream?.stream.orEmpty()
        // 倒序下"加载更多"是往更早的楼层回溯,不受向下窗口起点的约束。
        return if (descending) stream.filterNot { it in consumedIds }
        else stream.drop(windowStart).filterNot { it in consumedIds }
    }
    /** 倒序时不提供"加载之前的楼层"缺口 —— 那和倒序的回溯加载是同一个方向,会互相抢楼。 */
    val previousIds: List<Long> get() = if (descending) emptyList()
        else topic?.postStream?.stream.orEmpty().take(windowStart).filterNot { it in consumedIds }
}

private class TopicLiveSession {
    val clientId = UUID.randomUUID().toString().replace("-", "")
    val updates = Channel<Unit>(Channel.CONFLATED)
    val messages = ArrayDeque<TopicBusMessage>()
    val pending = linkedSetOf<Long>()
    val readyPosts = linkedMapOf<Long, Post>()
    var cursor = -1L
    var sequence = 0L
    var initialized = false
    var needsCalibration = true
    var nextCalibrationAt = 0L
    var calibrationWakeAt = 0L
    var calibration: Pair<DetailUiState, TopicDetail>? = null
    var postFailures = 0
    var postRetryAt = 0L
}

internal fun mergeRealtimePosts(current: DetailUiState, fetched: List<Post>): DetailUiState {
    val topic = current.topic ?: return current
    val existing = current.posts.mapTo(hashSetOf()) { it.id }
    val added = fetched.distinctBy { it.id }.filter { it.id !in existing }
    if (added.isEmpty()) return current.copy(realtimePostIds = current.realtimePostIds - fetched.map { it.id }.toSet())
    val parentDeltas = added.mapNotNull { it.replyToPostNumber?.takeIf { number -> number > 1 } }
        .groupingBy { it }.eachCount()
    val parents = current.posts.map { post ->
        parentDeltas[post.postNumber]?.let { post.copy(replyCount = post.replyCount + it) } ?: post
    }
    val addedIds = added.map { it.id }
    return current.copy(
        topic = topic.copy(postStream = topic.postStream.copy(stream = (topic.postStream.stream + addedIds).distinct())),
        posts = (parents + added).sortedBy { it.postNumber },
        consumedIds = current.consumedIds + addedIds,
        completedReplies = current.completedReplies - current.posts.filter { it.postNumber in parentDeltas }.map { it.id }.toSet(),
        realtimePostIds = current.realtimePostIds - addedIds.toSet(),
        realtimeVersion = current.realtimeVersion + 1,
    )
}

internal fun mergeConfirmedDetailRead(current: DetailUiState, postNumbers: Set<Int>): DetailUiState {
    if (postNumbers.isEmpty()) return current
    val confirmed = current.confirmedReadPostNumbers + postNumbers
    fun markPosts(posts: List<Post>) = posts.map { post ->
        if (!post.read && post.postNumber in confirmed) post.copy(read = true) else post
    }
    return current.copy(
        posts = markPosts(current.posts),
        topic = current.topic?.let { topic -> topic.copy(
            lastReadPostNumber = maxOf(topic.lastReadPostNumber ?: 0, confirmed.maxOrNull() ?: 0),
            postStream = topic.postStream.copy(posts = markPosts(topic.postStream.posts)),
        ) },
        confirmedReadPostNumbers = confirmed,
    )
}

internal fun canAppendRealtimePosts(detail: DetailUiState, pending: Set<Long>, atLatestEnd: Boolean, scrolling: Boolean): Boolean {
    if (!atLatestEnd || scrolling || detail.filterUserId != null) return false
    val stream = detail.topic?.postStream?.stream.orEmpty().filterNot { it in pending }
    return if (detail.descending) stream.lastOrNull() in detail.consumedIds
    else detail.remaining.none { it !in pending }
}

internal fun topicPostRetryDelayMs(failures: Int): Long = 3_000L shl (failures - 1).coerceIn(0, 4)

/** 仅保存已完整加载的话题，避免重复进入详情时再发一次请求。 */
private class TopicDetailCache(
    private val capacity: Int,
    private val ttlMs: Long,
) {
    private data class Entry(
        val state: DetailUiState,
        val cachedAtMs: Long,
    )

    private val entries = LinkedHashMap<Long, Entry>(capacity, 0.75f, true)

    fun get(topicId: Long): DetailUiState? = synchronized(entries) {
        pruneExpired(SystemClock.elapsedRealtime())
        entries[topicId]?.state
    }

    fun put(topicId: Long, state: DetailUiState, refreshTtl: Boolean = true): Set<Long> = synchronized(entries) {
        val now = SystemClock.elapsedRealtime()
        val evicted = pruneExpired(now)
        val previous = entries[topicId]
        entries[topicId] = Entry(state, if (refreshTtl || previous == null) now else previous.cachedAtMs)
        while (entries.size > capacity) {
            val iterator = entries.entries.iterator()
            val eldest = iterator.next()
            evicted += eldest.key
            iterator.remove()
        }
        evicted - topicId
    }

    fun remove(topicId: Long) {
        synchronized(entries) { entries.remove(topicId) }
    }

    fun clear() = synchronized(entries) {
        entries.clear()
    }

    private fun pruneExpired(now: Long): MutableSet<Long> {
        val expired = linkedSetOf<Long>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.cachedAtMs >= ttlMs) {
                expired += entry.key
                iterator.remove()
            }
        }
        return expired
    }
}

/** 多个列表可能同时处于组合树中，因此按来源聚合滚动状态。 */
private class ScrollBusySignal(
    private val idleDelayMs: Long,
) {
    private val busySources = mutableSetOf<Any>()

    val isBusy: Boolean
        get() = synchronized(busySources) { busySources.isNotEmpty() }

    fun update(source: Any, busy: Boolean) {
        synchronized(busySources) {
            if (busy) busySources += source else busySources -= source
        }
    }

    fun clear() = synchronized(busySources) {
        busySources.clear()
    }

    suspend fun awaitIdle() {
        while (true) {
            while (isBusy) delay(50L)
            delay(idleDelayMs)
            if (!isBusy) return
        }
    }
}

enum class CategorySectionTab {
    CATEGORIES,
    TAGS,
}

data class CategoriesUiState(
    val items: List<Category> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val order: List<Long> = emptyList(),
)

data class TagsUiState(
    val tags: List<org.linuxdo.android.data.DiscourseTag> = emptyList(),
    val searchedTags: List<org.linuxdo.android.data.DiscourseTag> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/** 编辑器要做的事。三种目标共用一个输入框,区别只在提交时调哪个接口。 */
internal fun appendQuoteToDraft(draft: String, username: String, postNumber: Int, topicId: Long, selection: String): String {
    if (selection.isBlank() || postNumber <= 0 || topicId <= 0) return draft
    val author = username.filter { it.isLetterOrDigit() || it in "_.-" }
    val quoted = selection.trim().replace(Regex("\\[(/?quote)(?=[=\\]\\s])", RegexOption.IGNORE_CASE), "&#91;$1")
    val separator = if (draft.isEmpty() || draft.endsWith("\n\n")) "" else if (draft.endsWith("\n")) "\n" else "\n\n"
    return "$draft$separator[quote=\"$author, post:$postNumber, topic:$topicId\"]\n$quoted\n[/quote]\n\n"
}

sealed interface ComposerTarget {
    val topicId: Long

    /**
     * 回复整个话题。不带 reply_to_post_number,所以不挂在任何楼层下面,
     * 发出去的楼层直接排在话题末尾 —— 这是网页端"回复"按钮的行为。
     */
    data class NewReply(override val topicId: Long) : ComposerTarget

    /** 回复某一楼层,结果会挂到该楼层下面。 */
    data class ReplyTo(
        override val topicId: Long,
        val postNumber: Int,
        val username: String,
        val quote: String? = null,
    ) : ComposerTarget

    /** 编辑自己的楼层。 */
    data class Edit(
        override val topicId: Long,
        val postId: Long,
        val postNumber: Int,
    ) : ComposerTarget
}

data class ComposerUiState(
    val target: ComposerTarget? = null,
    val draft: String = "",
    /** 编辑要先取回原文,这期间输入框不可编辑。 */
    val loadingDraft: Boolean = false,
    val submitting: Boolean = false,
    /** 正在上传附件。期间不许提交 —— 不然短码还没插进草稿就发出去了。 */
    val uploading: Boolean = false,
    val attachments: List<AttachedMedia> = emptyList(),
    val error: String? = null,
) {
    val open: Boolean get() = target != null
    val canSubmit: Boolean get() = (draft.isNotBlank() || attachments.isNotEmpty()) && !submitting && !loadingDraft && !uploading
}

data class TopicCreatorUiState(
    val open: Boolean = false,
    val draftKey: String = "new_topic",
    val sequence: Long = 0L,
    val action: String = "createTopic",
    val replyTopicId: Long? = null,
    val replyToPostNumber: Int? = null,
    val title: String = "",
    val raw: String = "",
    val categoryId: Int? = null,
    val tags: List<String> = emptyList(),
    val availableTags: List<DiscourseTagInfo> = emptyList(),
    val requiredTagGroupName: String? = null,
    val requiredTagMinCount: Int = 1,
    val loadingTags: Boolean = false,
    val attachments: List<AttachedMedia> = emptyList(),
    val savingDraft: Boolean = false,
    val submitting: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val savedSnapshot: String = "",
    val emojiUrls: Map<String, String> = emptyMap(),
) {
    val isReplyDraft: Boolean
        get() = action != "createTopic" && replyTopicId != null && replyTopicId > 0L

    val currentSnapshot: String
        get() = "${title.trim()}\u0000${raw.trim()}\u0000${categoryId ?: ""}\u0000${tags.joinToString(",")}\u0000${attachments.joinToString(",") { it.id }}"

    val hasUnsavedChanges: Boolean
        get() = (title.trim().isNotEmpty() || raw.trim().isNotEmpty() || attachments.isNotEmpty()) && currentSnapshot != savedSnapshot

    val canSubmit: Boolean
        get() = !submitting && !uploading && !savingDraft &&
            (raw.trim().isNotEmpty() || attachments.isNotEmpty()) && (isReplyDraft || title.trim().isNotEmpty())

    val canSaveDraft: Boolean
        get() = !submitting && !uploading && !savingDraft &&
            (raw.trim().isNotEmpty() || title.trim().isNotEmpty() || attachments.isNotEmpty())
}

data class DraftsUiState(
    val items: List<DraftItem> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val deletingKeys: Set<String> = emptySet(),
    val error: String? = null,
)

data class AccountUiState(
    val accounts: List<AccountSession> = emptyList(),
    val activeUserId: Long = 0L,
    val switching: Boolean = false,
    val error: String? = null,
)

data class HistoryUiState(
    val entries: List<org.linuxdo.android.data.ReadingHistoryEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
)

data class UserCardUiState(
    val user: UserCardData? = null,
    val loading: Boolean = false,
    val following: Boolean = false,
    val followUpdating: Boolean = false,
    val isBlocked: Boolean = false,
    val blockUpdating: Boolean = false,
    val isSelf: Boolean = false,
    val error: String? = null,
)

data class BlacklistUiState(
    val users: List<BlockedUser> = emptyList(),
)

data class FollowUiState(
    val username: String? = null,
    val users: List<FollowUser> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class TrustReportUiState(
    val report: TrustReport? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val cachedAt: Long = 0L,
)

data class LdcUiState(
    val userInfo: LdcUserInfo? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val isExpired: Boolean = false,
    val cachedAt: Long = 0L,
)

/** 社区分(CDK score)。带 username 是为了切号后不把上一个账号的分数显示给新账号。 */
data class CdkScoreUiState(
    val username: String = "",
    val score: Int? = null,
    val cachedAt: Long = 0L,
)

data class ChatUiState(
    val channels: List<ChatChannelSummary> = emptyList(),
    val canLoadMorePastByChannel: Map<Long, Boolean> = emptyMap(),
    val loadingPastChannelId: Long? = null,
    val messagesByChannel: Map<Long, List<ChatMessageSummary>> = emptyMap(),
    val loadingChannels: Boolean = false,
    val loadingChannelId: Long? = null,
    val sendingChannelId: Long? = null,
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val results: TopicListUiState = TopicListUiState(initialLoading = false, hasMore = false),
    val excerpts: Map<Long, String> = emptyMap(),
    val order: SearchOrder = SearchOrder.Relevance,
)

data class TopicSearchUiState(
    val open: Boolean = false, val topicId: Long = 0, val title: String = "", val query: String = "",
    val hits: List<TopicSearchHit> = emptyList(), val page: Int = 0, val hasMore: Boolean = false,
    val loading: Boolean = false, val loadingMore: Boolean = false, val error: String? = null,
    val order: SearchOrder = SearchOrder.Relevance,
)

data class TopicMetadataEditorUiState(
    val open: Boolean = false, val topicId: Long = 0, val userId: Long = 0, val generation: Long = 0,
    val original: TopicMetadata? = null, val rules: TopicEditRules? = null,
    val title: String = "", val categoryId: Int? = null, val tags: List<String> = emptyList(),
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null,
    val tagQuery: String = "", val loadingTags: Boolean = false,
    val tagOptions: List<org.linuxdo.android.data.DiscourseTagInfo> = emptyList(),
    val tagError: String? = null, val requiredTagName: String? = null, val requiredTagMin: Int = 0,
) {
    val patch: TopicMetadataPatch? get() = original?.let { topicMetadataPatch(it, title, categoryId, tags, rules?.canTagTopics == true) }
    val dirty: Boolean get() = patch?.changed == true
    val validationError: String? get() = original?.let { original -> rules?.let { validateTopicMetadata(original, it, patch ?: TopicMetadataPatch()) } }
    val canSave: Boolean get() = open && !loading && !saving && !loadingTags && original?.canEdit == true && rules != null && dirty && validationError == null
}

data class ProfileUiState(
    val content: ProfilePage = ProfilePage(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** 这份数据拉完的时刻(elapsedRealtime),用来判断缓存有没有过期。 */
    val loadedAt: Long = 0L,
    /** 拉这份数据时的未读数。未读没涨说明列表大概率没变,可以接着用缓存。 */
    val unreadAtLoad: Int = 0,
)

data class UiState(
    val screen: Screen = Screen.Boot,
    val username: String? = null,
    val homeFeed: HomeFeedType = HomeFeedType.Latest,
    val latest: TopicListUiState = TopicListUiState(),
    val path: NetworkPath = NetworkPath.OKHTTP,
    val autoRefresh: Boolean = false,
    val cookieNames: List<String> = emptyList(),
    val cookieSavedAt: Long = 0L,
    val userAgent: String = "",
    val unreadCount: Int = 0,
    val unreadFilters: Set<NotificationFilter> = emptySet(),
    /** 各通知分类的未读条数。用于判断某个分类的缓存是否还新鲜。 */
    val unreadByFilter: Map<NotificationFilter, Int> = emptyMap(),
)


internal object ClipboardConsumption {
    // 普通空格将尾缀与 URL 隔开，沿用现有链接解析器的空白边界。
    const val MARKER = " \u200B"

    fun originalText(text: String): String = text.removeSuffix(MARKER)

    fun textHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun shouldSuppress(text: String, consumedHash: String, markerVerified: Boolean): Boolean {
        return consumedHash.isNotEmpty() && textHash(originalText(text)) == consumedHash &&
            (text.endsWith(MARKER) || !markerVerified)
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as App).container
    private val store = container.sessionStore
    private val browser = container.browser
    private val userApiKeyAuthService = container.userApiKeyAuthService
    val diagnostics = container.diagnostics

    private val savedTopicStore = SavedTopicStore(application)
    private val savedTopicWriteGate = Mutex()
    private val _savedTopics = MutableStateFlow<List<SavedTopic>>(emptyList())
    val savedTopics = _savedTopics.asStateFlow()
    private val _savedTopicsUserId = MutableStateFlow(0L)
    val savedTopicsUserId = _savedTopicsUserId.asStateFlow()
    private val _savedTopicsBusy = MutableStateFlow(false)
    val savedTopicsBusy = _savedTopicsBusy.asStateFlow()
    private val _savedTopicsNotice = MutableStateFlow<String?>(null)
    val savedTopicsNotice = _savedTopicsNotice.asStateFlow()
    private val _canUndoSavedTopics = MutableStateFlow(false)
    val canUndoSavedTopics = _canUndoSavedTopics.asStateFlow()
    private var removedSavedTopics: List<SavedTopic> = emptyList()
    private val topicReadingPositions = mutableMapOf<Long, TopicReadingPosition>()

    private fun publishSavedTopics(entries: List<SavedTopic>) {
        _savedTopics.value = entries.sortedWith(compareByDescending<SavedTopic> { it.pinned }.thenByDescending { it.savedAt })
    }

    private fun changeSavedTopics(
        userId: Long,
        operation: suspend () -> List<SavedTopic>,
        onSuccess: () -> Unit = {},
    ) {
        if (userId <= 0L || userId != _savedTopicsUserId.value || _savedTopicsBusy.value) return
        val generation = accountScopeGeneration
        _savedTopicsBusy.value = true
        viewModelScope.launch {
            try {
                // 锁内发布：避免排在后面的旧结果覆盖较新的列表。
                val published = savedTopicWriteGate.withLock {
                    val updated = withContext(Dispatchers.IO) { operation() }
                    val sameScope = generation == accountScopeGeneration && userId == _savedTopicsUserId.value
                    if (sameScope) publishSavedTopics(updated)
                    sameScope
                }
                if (published) onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == accountScopeGeneration) _savedTopicsNotice.value = error.message ?: "暂存操作失败，请重试"
            } finally {
                if (generation == accountScopeGeneration) _savedTopicsBusy.value = false
            }
        }
    }

    fun saveTopic(userId: Long, topicId: Long) {
        val detail = _details.value[topicId] ?: return
        val topic = detail.topic ?: return
        val entry = SavedTopic(
            topicId = topicId, title = topic.title, savedAt = System.currentTimeMillis(),
            categoryName = detail.category?.name, categoryColor = detail.category?.color,
            totalPosts = maxOf(topic.postsCount, topic.postStream.stream.size, 1),
            position = topicReadingPositions[topicId] ?: TopicReadingPosition(),
        )
        changeSavedTopics(userId, { savedTopicStore.add(userId, entry) }) {
            removedSavedTopics = emptyList()
            _canUndoSavedTopics.value = false
            _savedTopicsNotice.value = "已加入暂存"
        }
    }

    /** 列表长按加入暂存：没有阅读视口，按服务端已读楼层恢复。 */
    fun saveTopicFromList(topic: Topic) {
        val userId = _savedTopicsUserId.value
        if (userId <= 0L) return
        if (_savedTopics.value.any { it.topicId == topic.id }) {
            _savedTopicsNotice.value = "已在暂存中"
            return
        }
        val category = repository.cachedCategoryMap()[topic.categoryId]
        val lastRead = topic.lastReadPostNumber?.takeIf { it > 1 }
        val entry = SavedTopic(
            topicId = topic.id, title = topic.title, savedAt = System.currentTimeMillis(),
            categoryName = category?.name, categoryColor = category?.color,
            totalPosts = maxOf(topic.postsCount, topic.highestPostNumber ?: 0, 1),
            position = topicReadingPositions[topic.id]
                ?: lastRead?.let { TopicReadingPosition(postNumber = it, atTop = false) }
                ?: TopicReadingPosition(),
        )
        changeSavedTopics(userId, { savedTopicStore.add(userId, entry) }) {
            removedSavedTopics = emptyList()
            _canUndoSavedTopics.value = false
            _savedTopicsNotice.value = "已加入暂存"
        }
    }

    fun updateSavedTopicPosition(userId: Long, topicId: Long, position: TopicReadingPosition) {
        if (userId <= 0L || userId != _savedTopicsUserId.value) return
        if (topicReadingPositions.put(topicId, position) == position) return
        if (_savedTopics.value.none { it.topicId == topicId }) return
        val generation = accountScopeGeneration
        val totalPosts = _details.value[topicId]?.topic?.postsCount ?: 0
        viewModelScope.launch {
            try {
                savedTopicWriteGate.withLock {
                    val updated = withContext(Dispatchers.IO) { savedTopicStore.updatePosition(userId, topicId, position, totalPosts) }
                    if (generation == accountScopeGeneration && userId == _savedTopicsUserId.value) publishSavedTopics(updated)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("暂存阅读位置保存失败: ${error.message}")
            }
        }
    }

    /** 复用列表静默探测和话题实时消息的数据，不额外发请求。 */
    private fun syncSavedTopicTotals(totals: Map<Long, Int>) {
        val userId = _savedTopicsUserId.value
        if (userId <= 0L || totals.isEmpty()) return
        val changed = _savedTopics.value.any { entry ->
            totals[entry.topicId]?.let { it > 0 && it != entry.totalPosts } == true
        }
        if (!changed) return
        val generation = accountScopeGeneration
        viewModelScope.launch {
            try {
                savedTopicWriteGate.withLock {
                    val updated = withContext(Dispatchers.IO) { savedTopicStore.updateTotals(userId, totals) }
                    if (generation == accountScopeGeneration && userId == _savedTopicsUserId.value) publishSavedTopics(updated)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("暂存楼层数同步失败: ${error.message}")
            }
        }
    }

    fun removeSavedTopic(userId: Long, topicId: Long) {
        val removed = _savedTopics.value.filter { it.topicId == topicId }.map { it.copy(position = topicReadingPositions[it.topicId] ?: it.position) }
        changeSavedTopics(userId, { savedTopicStore.remove(userId, topicId) }) {
            removedSavedTopics = removed
            _canUndoSavedTopics.value = removed.isNotEmpty()
            _savedTopicsNotice.value = "已移除暂存"
        }
    }

    fun clearSavedTopics(userId: Long) {
        val removed = _savedTopics.value.map { it.copy(position = topicReadingPositions[it.topicId] ?: it.position) }
        changeSavedTopics(userId, { savedTopicStore.clear(userId) }) {
            removedSavedTopics = removed
            _canUndoSavedTopics.value = removed.isNotEmpty()
            _savedTopicsNotice.value = "已清空全部暂存"
        }
    }

    fun undoSavedTopics(userId: Long) {
        val removed = removedSavedTopics.map { it.copy(position = topicReadingPositions[it.topicId] ?: it.position) }
        if (removed.isEmpty()) return
        changeSavedTopics(userId, { savedTopicStore.restore(userId, removed) }) {
            removedSavedTopics = emptyList()
            _canUndoSavedTopics.value = false
            _savedTopicsNotice.value = "已撤销"
        }
    }

    fun pinSavedTopic(userId: Long, topicId: Long) {
        changeSavedTopics(userId, { savedTopicStore.togglePin(userId, topicId) })
    }

    fun clearSavedTopicsNotice() {
        _savedTopicsNotice.value = null
        _canUndoSavedTopics.value = false
        removedSavedTopics = emptyList()
    }

    private val initialHomeFeed = HomeFeedType.fromName(store.homeFeed())
    private val _feedStates = MutableStateFlow<Map<HomeFeedType, TopicListUiState>>(emptyMap())
    val feedStates = _feedStates.asStateFlow()
    private val _topicSortOrder = MutableStateFlow(store.getTopicSortOrder())
    val topicSortOrder: StateFlow<TopicSortOrder> = _topicSortOrder.asStateFlow()
    private val _topicSortAscending = MutableStateFlow(store.getTopicSortAscending())
    val topicSortAscending: StateFlow<Boolean> = _topicSortAscending.asStateFlow()
    private val _floatingBallEnabled = MutableStateFlow(store.isFloatingBallEnabled())
    val floatingBallEnabled: StateFlow<Boolean> = _floatingBallEnabled.asStateFlow()
    private val _floatingBallActions = MutableStateFlow(store.getFloatingBallActions())
    val floatingBallActions: StateFlow<List<FloatingAction>> = _floatingBallActions.asStateFlow()
    private val _floatingBallXRatio = MutableStateFlow(store.getFloatingBallXRatio())
    val floatingBallXRatio: StateFlow<Float> = _floatingBallXRatio.asStateFlow()
    private val _floatingBallYRatio = MutableStateFlow(store.getFloatingBallYRatio())
    val floatingBallYRatio: StateFlow<Float> = _floatingBallYRatio.asStateFlow()
    private val _floatingBallOnRight = MutableStateFlow(store.isFloatingBallOnRight())
    val floatingBallOnRight: StateFlow<Boolean> = _floatingBallOnRight.asStateFlow()
    private val _autoReadClipboardEnabled = MutableStateFlow(store.isAutoReadClipboardEnabled())
    val autoReadClipboardEnabled: StateFlow<Boolean> = _autoReadClipboardEnabled.asStateFlow()
    private val _detectedClipboardTarget = MutableStateFlow<Pair<String, org.linuxdo.android.data.InternalTopicLink>?>(null)
    val detectedClipboardTarget: StateFlow<Pair<String, org.linuxdo.android.data.InternalTopicLink>?> = _detectedClipboardTarget.asStateFlow()
    private var detectedClipboardText: String? = null
    private val _state = MutableStateFlow(
        UiState(
            userAgent = container.userAgent,
            homeFeed = initialHomeFeed,
            unreadCount = 0,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _accounts = MutableStateFlow(
        container.accountManager.snapshot().let { snapshot ->
            AccountUiState(accounts = snapshot.accounts, activeUserId = snapshot.activeUserId, error = snapshot.error)
        },
    )
    val accounts: StateFlow<AccountUiState> = _accounts.asStateFlow()
    private var lastAccountSwitchAt: Long? = null
    private var accountScopeGeneration = 0L
    private var incomingProbeGeneration = 0L

    private val repository = TopicRepository(
        transportProvider = { container.transport(_state.value.path) },
        diagnostics = diagnostics,
        reading = container.reading,
        sessionStore = store,
        fallbackTransportProvider = { container.transport(NetworkPath.WEBVIEW) },
        httpClientProvider = { container.httpClient },
    )

    private var bootJob: Job? = null
    private var sessionStorageNeedsRetry = false
    private var autoJob: Job? = null
    private var reconcileSessionJob: Job? = null
    private var verifying = false
    private var resumeAfterVerification: (() -> Unit)? = null
    private val loadingFeeds = java.util.concurrent.ConcurrentHashMap<HomeFeedType, Long>()
    private var searchJob: Job? = null
    private var unreadJob: Job? = null
    private var unreadFailures = 0
    private var nextUnreadRefreshAt = 0L
    /**
     * 未读刷新撞 CF 挑战后的退避截止时间。force 刷新（回前台、切页）也不越过它：
     * 每 10 秒拿同一枚 cf_clearance 去撞，会拖低它的评分，连带 /topics/timings 被挑战。
     * 手工验证完成后清零。
     */
    private var unreadChallengeBackoffUntil = 0L
    private val pageJobs = mutableMapOf<String, Job>()
    private val _details = MutableStateFlow<Map<Long, DetailUiState>>(emptyMap())
    val details = _details.asStateFlow()
    private val topicDetailCache = TopicDetailCache(TOPIC_DETAIL_CACHE_CAPACITY, TOPIC_DETAIL_CACHE_TTL_MS)
    private val topicLiveSessions = mutableMapOf<Long, TopicLiveSession>()
    private var topicLiveJob: Job? = null
    private var topicLiveId: Long? = null
    private val topicLiveAtEnd = mutableSetOf<Long>()
    private val topicLiveScrolling = mutableSetOf<Long>()
    private val scrollBusySignal = ScrollBusySignal(SCROLL_IDLE_DELAY_MS)
    private var excerptPrefetchJob: Job? = null
    private var previewListVisible = false
    private val knownPostsCountOnOpen = mutableMapOf<Long, Int>()
    private val _categories = MutableStateFlow(CategoriesUiState(order = store.loadCategoryOrder()))
    val categories = _categories.asStateFlow()
    private val _customSection = MutableStateFlow(store.loadCustomSection())
    val customSection = _customSection.asStateFlow()
    private val _categoryTopics = MutableStateFlow<Map<Int, TopicListUiState>>(emptyMap())
    val categoryTopics = _categoryTopics.asStateFlow()
    private val _categorySubTab = MutableStateFlow(CategorySectionTab.CATEGORIES)
    val categorySubTab = _categorySubTab.asStateFlow()
    private val _tags = MutableStateFlow(TagsUiState())
    val tags = _tags.asStateFlow()
    private val _tagSearchQuery = MutableStateFlow("")
    val tagSearchQuery = _tagSearchQuery.asStateFlow()
    private val _tagTopics = MutableStateFlow<Map<String, TopicListUiState>>(emptyMap())
    val tagTopics = _tagTopics.asStateFlow()
    private val _userTopicStates = MutableStateFlow<Map<String, TopicListUiState>>(emptyMap())
    val userTopicStates = _userTopicStates.asStateFlow()
    private val _userRepliesStates = MutableStateFlow<Map<String, UserRepliesUiState>>(emptyMap())
    val userRepliesStates = _userRepliesStates.asStateFlow()
    private val _search = MutableStateFlow(SearchUiState())
    val search = _search.asStateFlow()
    private var searchSequence = 0L
    private var topicSearchSequence = 0L
    private var topicSearchJob: Job? = null
    private val _topicSearch = MutableStateFlow(TopicSearchUiState())
    val topicSearch = _topicSearch.asStateFlow()
    private var metadataSequence = 0L
    private var metadataJob: Job? = null
    private var metadataTagJob: Job? = null
    private var metadataTagSequence = 0L
    private val _metadataEditor = MutableStateFlow(TopicMetadataEditorUiState())
    val metadataEditor = _metadataEditor.asStateFlow()
    private val _metadataUpdated = MutableStateFlow<TopicMetadata?>(null)
    val metadataUpdated = _metadataUpdated.asStateFlow()
    private val _searchHistory = MutableStateFlow(store.getSearchHistory())
    val searchHistory = _searchHistory.asStateFlow()
    private val _profile = MutableStateFlow<Map<ProfileSection, ProfileUiState>>(emptyMap())
    val profile = _profile.asStateFlow()
    private val _userBadges = MutableStateFlow<List<UserBadgeItem>?>(null)
    val userBadges = _userBadges.asStateFlow()
    private val _isUserBadgesLoading = MutableStateFlow(false)
    val isUserBadgesLoading = _isUserBadgesLoading.asStateFlow()
    private val _notificationFilter = MutableStateFlow(NotificationFilter.All)
    val notificationFilter = _notificationFilter.asStateFlow()
    /**
     * 通知各分类的数据。`_profile` 的键是 ProfileSection,四个分类挤同一个槽位,
     * 所以额外按分类留一份,切标签才能命中缓存直接显示。
     */
    private val notificationCache = mutableMapOf<NotificationFilter, ProfileUiState>()
    private val _composer = MutableStateFlow(ComposerUiState())
    val composer = _composer.asStateFlow()
    private val _topicCreator = MutableStateFlow(TopicCreatorUiState())
    val topicCreator = _topicCreator.asStateFlow()
    private val _drafts = MutableStateFlow(DraftsUiState())
    val drafts = _drafts.asStateFlow()
    private val _history = MutableStateFlow(HistoryUiState(entries = container.historyStore.entries()))
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()
    private val _userCard = MutableStateFlow(UserCardUiState())
    val userCard: StateFlow<UserCardUiState> = _userCard.asStateFlow()
    private val _followList = MutableStateFlow(FollowUiState())
    val followList: StateFlow<FollowUiState> = _followList.asStateFlow()
    private val _blockedUsers = MutableStateFlow<List<BlockedUser>>(repository.getBlockedUsers())
    val blockedUsers: StateFlow<List<BlockedUser>> = _blockedUsers.asStateFlow()
    val blockedUsernames = MutableStateFlow<Set<String>>(_blockedUsers.value.map { it.username.lowercase() }.toSet())
    val followedUsernames = MutableStateFlow<Set<String>>(emptySet())
    private val _trustReport = MutableStateFlow(TrustReportUiState())
    val trustReport: StateFlow<TrustReportUiState> = _trustReport.asStateFlow()
    private val _ldcState = MutableStateFlow(LdcUiState())
    val ldcState: StateFlow<LdcUiState> = _ldcState.asStateFlow()
    private val _cdkScore = MutableStateFlow(CdkScoreUiState())
    val cdkScore: StateFlow<CdkScoreUiState> = _cdkScore.asStateFlow()
    private var cdkScoreJob: Job? = null
    private var cdkScoreBackoffUntil: Long = 0L
    private val connectManualRefreshTimestamps = mutableListOf<Long>()
    private var connectSilentBackoffUntil: Long = 0L
    private var ldcBackoffUntil: Long = 0L
    private val ldcFetchGate = Mutex()
    private var ldcLastAttempt: Pair<Long, Result<LdcUserInfo>>? = null

    private fun connectSessionCheck(username: String): () -> Unit {
        val userId = store.currentUserId()
        val generation = store.sessionGeneration
        return {
            if (store.sessionChanging || store.sessionGeneration != generation ||
                store.currentUserId() != userId || _state.value.username?.equals(username, ignoreCase = true) != true) {
                throw CancellationException("账号已切换，取消等级积分更新")
            }
        }
    }

    private fun siteFailureDelayMs(error: Exception): Long = when (error) {
        is RateLimitException -> error.retryAfterMs.coerceAtLeast(1_000L)
        is NeedsInteractiveVerification -> Config.CONNECT_SILENT_REFRESH_BACKOFF_MS
        is HttpStatusException -> if (error.status == 401 || error.status == 403) Config.CONNECT_SILENT_REFRESH_BACKOFF_MS else 30_000L
        else -> 30_000L
    }

    private suspend fun <T> readSiteWithAuthorization(
        checkSession: () -> Unit,
        read: suspend () -> T,
        authorize: suspend () -> Boolean,
    ): T {
        checkSession()
        val result = try {
            repository.readAfterShortCooldown { checkSession(); read() }
        } catch (error: HttpStatusException) {
            checkSession()
            if (error.status != 401) throw error
            if (!authorize()) throw error
            checkSession()
            repository.readAfterShortCooldown { checkSession(); read() }
        }
        checkSession()
        return result
    }

    private suspend fun fetchAuthorizedLdc(username: String, checkSession: () -> Unit): LdcUserInfo {
        val queuedAt = SystemClock.elapsedRealtime()
        return ldcFetchGate.withLock {
            checkSession()
            // 同一轮已排队请求共享成功或失败，避免预热失败后手动请求紧接着重复 OAuth。
            ldcLastAttempt?.takeIf { it.first >= queuedAt }?.let { return@withLock it.second.getOrThrow() }
            try {
                val info = readSiteWithAuthorization(checkSession, { repository.fetchLdcUserInfo() }, { repository.authorizeLdc() })
                check(info.username.equals(username, ignoreCase = true)) { "积分账号与当前账号不一致，请重新授权" }
                ldcLastAttempt = SystemClock.elapsedRealtime() to Result.success(info)
                info
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                checkSession()
                ldcLastAttempt = SystemClock.elapsedRealtime() to Result.failure(error)
                throw error
            }
        }
    }

    private fun checkConnectManualRefreshRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        connectManualRefreshTimestamps.removeAll { now - it > Config.CONNECT_MANUAL_REFRESH_WINDOW_MS }
        if (connectManualRefreshTimestamps.size >= Config.CONNECT_MANUAL_REFRESH_MAX_COUNT) {
            val oldest = connectManualRefreshTimestamps.minOrNull() ?: now
            val waitMinutes = maxOf(1, ((Config.CONNECT_MANUAL_REFRESH_WINDOW_MS - (now - oldest)) / 60_000L).toInt())
            android.widget.Toast.makeText(
                getApplication(),
                "刷新过于频繁（30分钟限5次），请稍后再试（约 ${waitMinutes} 分钟后解禁）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return false
        }
        connectManualRefreshTimestamps.add(now)
        return true
    }

    private fun sinceProcessStartMs(): Long =
        SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()

    /**
     * 本地无缓存时的首拉锁：进程启动后 [Config.CONNECT_FIRST_FETCH_LOCK_MS] 内不允许手动拉取，
     * 交给 [scheduleIdleConnectWarmup] 在启动 2 分钟后错峰取第一份。返回剩余锁定毫秒，0 表示放行。
     */
    private fun connectFirstFetchLockRemainingMs(hasCache: Boolean): Long {
        if (hasCache) return 0L
        return (Config.CONNECT_FIRST_FETCH_LOCK_MS - sinceProcessStartMs()).coerceAtLeast(0L)
    }

    private fun connectFirstFetchLockMessage(remainingMs: Long): String {
        val minutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "首次获取正在错峰，后台会尝试获取；约 $minutes 分钟后可手动刷新"
    }

    private var idleWarmupJob: Job? = null

    private fun scheduleIdleConnectWarmup() {
        if (idleWarmupJob?.isActive == true) return
        val username = _state.value.username ?: return
        val checkSession = connectSessionCheck(username)
        idleWarmupJob = viewModelScope.launch {
            // 冷启动首轮不早于进程启动 2 分钟，避开首屏请求峰值；之后的触发至少再等 30 秒
            delay(maxOf(
                Config.CONNECT_IDLE_WARMUP_DELAY_MS,
                Config.CONNECT_FIRST_WARMUP_AFTER_START_MS - sinceProcessStartMs(),
            ))
            suspend fun awaitQuietWindow() {
                checkSession()
                while (!store.isAppInForeground() || store.isClearanceRecoveryInProgress() || repository.isRateLimitCoolingDown()) {
                    delay(maxOf(5_000L, TopicRepository.rateLimitRemainingMs()))
                    checkSession()
                }
            }
            repeat(2) { round ->
                awaitQuietWindow()
                var retryAfterMs = 0L
                val now = System.currentTimeMillis()
                val cachedTrust = repository.getCachedTrustReport(username)
                if (cachedTrust == null || now - cachedTrust.second >= Config.TRUST_REPORT_CACHE_TTL_MS) {
                    if (now >= connectSilentBackoffUntil) {
                        try {
                            val fresh = repository.readAfterShortCooldown { checkSession(); repository.fetchTrustReport(present = false) }
                            checkSession()
                            if (fresh != null) {
                                val updateTime = System.currentTimeMillis()
                                repository.saveCachedTrustReport(username, fresh)
                                _trustReport.update { it.copy(report = fresh, cachedAt = updateTime, error = null) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            checkSession()
                            val pause = siteFailureDelayMs(e)
                            connectSilentBackoffUntil = System.currentTimeMillis() + pause
                            if (pause <= 60_000L) retryAfterMs = maxOf(retryAfterMs, pause)
                            diagnostics.log("等级预热暂缓: ${e.message}")
                        }
                    }
                }

                // 两个接口之间错峰间隔 6 秒，杜绝并发特征
                delay(6_000L)
                awaitQuietWindow()

                // 2. 检查等级积分是否超过 6 小时
                val cachedLdc = repository.getCachedLdcUserInfo(username)
                if (cachedLdc == null || now - cachedLdc.second >= Config.LDC_USER_INFO_CACHE_TTL_MS) {
                    if (System.currentTimeMillis() >= ldcBackoffUntil) {
                        try {
                            val fresh = fetchAuthorizedLdc(username, checkSession)
                            checkSession()
                            val updateTime = System.currentTimeMillis()
                            repository.saveCachedLdcUserInfo(username, fresh)
                            _ldcState.update { it.copy(userInfo = fresh, cachedAt = updateTime, error = null) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            checkSession()
                            val pause = siteFailureDelayMs(e)
                            ldcBackoffUntil = System.currentTimeMillis() + pause
                            if (pause <= 60_000L) retryAfterMs = maxOf(retryAfterMs, pause)
                            _ldcState.update { it.copy(error = "积分预热暂缓: ${e.message}") }
                            diagnostics.log("积分预热暂缓: ${e.message}")
                        }
                    }
                }

                // 两个接口之间同样错峰 6 秒，再取社区分
                delay(6_000L)
                awaitQuietWindow()
                loadCdkScore(fromWarmup = true)
                cdkScoreJob?.join()
                checkSession()
                val cdkRemaining = cdkScoreBackoffUntil - System.currentTimeMillis()
                if (cdkRemaining in 1L..60_000L) retryAfterMs = maxOf(retryAfterMs, cdkRemaining)
                if (round > 0 || retryAfterMs <= 0L) return@launch
                delay(maxOf(30_000L, retryAfterMs))
            }
        }
    }
    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()
    private val _treeView = MutableStateFlow(container.preferences.getBoolean("tree_view", true))
    val treeView = _treeView.asStateFlow()
    private val _showUnreadDot = MutableStateFlow(container.preferences.getBoolean("show_post_unread_dot", true))
    val showUnreadDot = _showUnreadDot.asStateFlow()
    private val _unreadDotPosition = MutableStateFlow(
        UnreadDotPosition.fromId(container.preferences.getString("post_unread_dot_position", "avatar"))
    )
    val unreadDotPosition = _unreadDotPosition.asStateFlow()
    private val _showTopicUnreadDot = MutableStateFlow(container.preferences.getBoolean("show_topic_unread_dot", true))
    val showTopicUnreadDot = _showTopicUnreadDot.asStateFlow()
    private val _topicUnreadDotPosition = MutableStateFlow(
        TopicUnreadDotPosition.fromId(container.preferences.getString("topic_unread_dot_position", "avatar"))
    )
    val topicUnreadDotPosition = _topicUnreadDotPosition.asStateFlow()
    private val _isBarsVisible = MutableStateFlow(true)
    val isBarsVisible: StateFlow<Boolean> = _isBarsVisible.asStateFlow()
    val isBottomBarVisible: StateFlow<Boolean> get() = _isBarsVisible.asStateFlow()

    fun setBarsVisible(visible: Boolean) {
        if (_isBarsVisible.value != visible) {
            _isBarsVisible.value = visible
        }
    }
    fun setBottomBarVisible(visible: Boolean) = setBarsVisible(visible)
    private val _syncReadProgress = MutableStateFlow(container.reading.syncReadProgress)
    val syncReadProgress = _syncReadProgress.asStateFlow()
    data class NotificationTarget(val topicId: Long, val postNumber: Int?)
    private val _pendingNotificationTarget = MutableStateFlow<NotificationTarget?>(null)
    val pendingNotificationTarget = _pendingNotificationTarget.asStateFlow()
    private val pendingNotificationRefreshTopics = mutableMapOf<Long, Int?>()

    fun markNotificationTopicPending(topicId: Long, postNumber: Int? = null) {
        if (topicId > 0L) {
            synchronized(pendingNotificationRefreshTopics) {
                pendingNotificationRefreshTopics[topicId] = postNumber
            }
        }
    }

    private fun peekNotificationTopicPending(topicId: Long): Pair<Boolean, Int?>? {
        if (topicId <= 0L) return null
        return synchronized(pendingNotificationRefreshTopics) {
            if (topicId in pendingNotificationRefreshTopics) {
                true to pendingNotificationRefreshTopics[topicId]
            } else null
        }
    }

    private fun consumeNotificationTopicPending(topicId: Long): Int? {
        if (topicId <= 0L) return null
        return synchronized(pendingNotificationRefreshTopics) {
            pendingNotificationRefreshTopics.remove(topicId)
        }
    }

    fun navigateToTopicFromNotification(
        topicId: Long,
        postNumber: Int?,
        notificationId: Long? = null,
        notificationType: Int? = null,
    ) {
        markNotificationTopicPending(topicId, postNumber)
        _pendingNotificationTarget.value = NotificationTarget(topicId, postNumber)
        if (notificationId != null && notificationId > 0L) {
            readNotification(notificationId, notificationType)
        }
        markNotificationsReadForTopic(topicId)
    }

    private fun markNotificationsReadForTopic(topicId: Long) {
        val matchingNotifIds = mutableSetOf<Pair<Long, Int?>>()
        _profile.value[ProfileSection.Notifications]?.content?.entries?.forEach { entry ->
            if (entry.topicId == topicId && entry.unread) {
                entry.id.toLongOrNull()?.let { id ->
                    matchingNotifIds.add(id to entry.notificationType)
                }
            }
        }
        notificationCache.values.forEach { cached ->
            cached.content.entries.forEach { entry ->
                if (entry.topicId == topicId && entry.unread) {
                    entry.id.toLongOrNull()?.let { id ->
                        matchingNotifIds.add(id to entry.notificationType)
                    }
                }
            }
        }
        matchingNotifIds.forEach { (id, type) ->
            readNotification(id, type)
        }
    }

    fun clearPendingNotificationTarget() {
        _pendingNotificationTarget.value = null
    }

    private val _autoRefreshInterval = MutableStateFlow(store.getAutoRefreshInterval())
    val autoRefreshInterval = _autoRefreshInterval.asStateFlow()

    private val _notificationPollInterval = MutableStateFlow(store.getNotificationPollInterval())
    val notificationPollInterval = _notificationPollInterval.asStateFlow()

    private val _notificationScope = MutableStateFlow(store.getNotificationScope())
    val notificationScope = _notificationScope.asStateFlow()

    private val _autoRefreshIncoming = MutableStateFlow(store.isAutoRefreshIncomingEnabled())
    val autoRefreshIncoming = _autoRefreshIncoming.asStateFlow()
    private val _backgroundNotification = MutableStateFlow(store.isBackgroundNotificationEnabled())
    val backgroundNotification = _backgroundNotification.asStateFlow()
    private val _customRealtimeSeconds = MutableStateFlow(store.getCustomRealtimeSeconds())
    val customRealtimeSeconds = _customRealtimeSeconds.asStateFlow()
    private val _keepAliveService = MutableStateFlow(store.isKeepAliveServiceEnabled())
    val keepAliveService = _keepAliveService.asStateFlow()

    // ── DoH 状态 ─────────────────────────────────────────────────────────────────
    private val _dohEnabled = MutableStateFlow(store.isDohEnabled())
    val dohEnabled = _dohEnabled.asStateFlow()

    private val _selectedDohServerUrl = MutableStateFlow(
        store.getDohServerUrl() ?: org.linuxdo.android.net.DohPresets.DEFAULT.url
    )
    val selectedDohServerUrl = _selectedDohServerUrl.asStateFlow()

    private val _dohAutoFailover = MutableStateFlow(store.isDohAutoFailoverEnabled())
    val dohAutoFailover = _dohAutoFailover.asStateFlow()

    /** 节点延迟测试结果 Map：key = DohServer.id, value = 延迟(ms)；-1L 表示超时 */
    private val _dohLatencies = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val dohLatencies = _dohLatencies.asStateFlow()

    /** 节点探测诊断摘要 Map：key = DohServer.id, value = 状态摘要（如 ECH 已接受/站点访问成功等） */
    private val _dohSummaries = MutableStateFlow<Map<String, String>>(emptyMap())
    val dohSummaries = _dohSummaries.asStateFlow()

    /** 一键测速进行中状态（用于按钮转圈控制） */
    private val _isTestingAllDoh = MutableStateFlow(false)
    val isTestingAllDoh = _isTestingAllDoh.asStateFlow()

    /** 当前完整的 DoH 节点列表（支持增删改查与恢复预设） */
    private val _dohServers = MutableStateFlow<List<org.linuxdo.android.net.DohServer>>(
        org.linuxdo.android.net.loadDohServers(store)
    )
    val dohServers = _dohServers.asStateFlow()

    fun setDohEnabled(enabled: Boolean) {
        store.setDohEnabled(enabled)
        _dohEnabled.value = enabled
        container.rebuildHttpClient()
    }

    fun setDohServerSelected(server: org.linuxdo.android.net.DohServer) {
        store.setDohServerUrl(server.url)
        _selectedDohServerUrl.value = server.url
        container.dohCoordinator.manualSelect(server)
    }

    fun setDohAutoFailover(enabled: Boolean) {
        store.setDohAutoFailoverEnabled(enabled)
        _dohAutoFailover.value = enabled
        container.dohCoordinator.refresh()
    }

    private val _dohAutoOptimize = MutableStateFlow(store.isDohAutoOptimizeEnabled())
    val dohAutoOptimize = _dohAutoOptimize.asStateFlow()

    fun setDohAutoOptimize(enabled: Boolean) {
        store.setDohAutoOptimizeEnabled(enabled)
        _dohAutoOptimize.value = enabled
        container.dohCoordinator.setAutoOptimizeEnabled(enabled)
    }

    private val _dohForceOverProxy = MutableStateFlow(store.isDohForceOverProxyEnabled())
    val dohForceOverProxy = _dohForceOverProxy.asStateFlow()

    fun setDohForceOverProxy(enabled: Boolean) {
        store.setDohForceOverProxyEnabled(enabled)
        _dohForceOverProxy.value = enabled
        container.dohCoordinator.refresh()
    }

    // ── 智能双轨调度状态 ─────────────────────────────────────────────────────────────
    private val _smartRouteMode = MutableStateFlow(container.dohCoordinator.activeRouteMode.get())
    val smartRouteMode = _smartRouteMode.asStateFlow()

    private val _smartDirectLatency = MutableStateFlow<Long?>(
        container.dohCoordinator.latestDirectLatencyMs.get().takeIf { it >= 0 }
    )
    val smartDirectLatency = _smartDirectLatency.asStateFlow()

    private val _smartDohLatency = MutableStateFlow<Long?>(
        container.dohCoordinator.latestDohLatencyMs.get().takeIf { it >= 0 }
    )
    val smartDohLatency = _smartDohLatency.asStateFlow()

    private val _smartRouteSummary = MutableStateFlow(container.dohCoordinator.latestSmartSummary.get())
    val smartRouteSummary = _smartRouteSummary.asStateFlow()

    private val _isSmartEvaluating = MutableStateFlow(false)
    val isSmartEvaluating = _isSmartEvaluating.asStateFlow()

    fun evaluateSmartRoute() {
        if (_isSmartEvaluating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSmartEvaluating.value = true
            try {
                container.dohCoordinator.evaluateSmartRoute(force = true)
            } finally {
                _isSmartEvaluating.value = false
            }
        }
    }

    // ── 网络与会话自愈 ─────────────────────────────────────────────────────────────
    private val _isSelfHealing = MutableStateFlow(false)
    val isSelfHealing: StateFlow<Boolean> = _isSelfHealing.asStateFlow()

    fun selfHealNetwork(context: Context? = null, silent: Boolean = false) {
        if (_isSelfHealing.value) return
        viewModelScope.launch {
            if (repository.isRateLimitCoolingDown()) {
                val remainingSec = (repository.rateLimitRemainingMs() + 999L) / 1000L
                if (silent) {
                    diagnostics.log("后台智能自愈跳过: 服务端 429 冷却尚未结束(还剩 ${remainingSec}s)")
                } else {
                    diagnostics.log("手动自愈跳过: 服务端 429 冷却尚未结束(还剩 ${remainingSec}s)")
                    context?.let { ctx ->
                        android.widget.Toast.makeText(ctx, "服务端正处于限流保护中，请在 ${remainingSec} 秒后再试", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                return@launch
            }
            _isSelfHealing.value = true
            diagnostics.log(if (silent) "触发后台智能自愈网络与会话..." else "触发手动一键修复网络与会话...")
            try {
                // 0. 保 _t 洗 Cookie：清除由子站 Turnstile 或会话失步引入的临时/冲突凭证
                val preserved = store.purgeTransientCookiesPreservingLogin()
                diagnostics.log("自愈执行会话清洗(保_t): 保留登录凭证=$preserved，已全面清除临时防护与会话Cookie")

                // 1. 清理 WebView 资源缓存与临时渲染数据
                browser.clearResourceCache()

                // 2. 清理本地磁盘网络与图片缓存 (若传入 context)
                context?.let { ctx ->
                    runCatching {
                        listOfNotNull(ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir).forEach { dir ->
                            dir.deleteRecursively()
                        }
                    }
                }

                // 3. 重建 OkHttp 客户端与连接池、DoH
                container.rebuildHttpClient()

                if (repository.isRateLimitCoolingDown()) {
                    diagnostics.log("自愈在唤起 WebView 前检测到 429 冷却，终止后续网络访问")
                    return@launch
                }

                // 4. 唤起静默挑战解决 (在后台 WebView 访问首页获取有效 cf_clearance 并同步到 store)
                val challengeCleared = browser.clearChallenge(if (silent) "智能自愈" else "手动自愈")

                if (repository.isRateLimitCoolingDown()) {
                    diagnostics.log("自愈期间进入 429 冷却，跳过 CSRF 与未读刷新")
                    return@launch
                }

                // 5. 挑战通过后，重置 TopicRepository 的阅读进度冷却，确保心跳立即恢复
                repository.resetTimingsCooldown()

                // 6. 重新校准并刷新 CSRF Token
                val newCsrf = runCatching { repository.refreshCsrf() }.getOrNull()

                // 7. 刷新当前账号与未读通知
                runCatching { refreshUnread(force = true) }

                diagnostics.log("网络与会话自愈完成: 盾就绪=$challengeCleared, hasCsrf=${!newCsrf.isNullOrBlank()}")
                if (!silent && context != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "网络与会话自愈修复完成", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                diagnostics.log("网络与会话自愈异常: ${e.message}")
                if (!silent && context != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "自愈过程已完成，部分网络正在重连", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                _isSelfHealing.value = false
            }
        }
    }

    private val _feedGraphicMode = MutableStateFlow(store.isFeedGraphicMode())
    val feedGraphicMode = _feedGraphicMode.asStateFlow()

    private val _feedDisplayMode = MutableStateFlow(store.getFeedDisplayMode())
    val feedDisplayMode = _feedDisplayMode.asStateFlow()

    fun setFeedDisplayMode(mode: String) {
        store.setFeedDisplayMode(mode)
        _feedDisplayMode.value = mode
        _feedGraphicMode.value = (mode == "graphic")
    }

    fun setFeedGraphicMode(enabled: Boolean) {
        val mode = if (enabled) "graphic" else if (_feedDisplayMode.value == "graphic") "small_image" else _feedDisplayMode.value
        setFeedDisplayMode(mode)
    }

    fun testDohServer(server: org.linuxdo.android.net.DohServer) {
        viewModelScope.launch(Dispatchers.IO) {
            val netState = container.networkMonitor.detect()
            if (netState.shouldPauseDoh && !store.isDohForceOverProxyEnabled()) {
                val reason = if (netState.isVpn) "VPN 已开启，直连已暂停" else "系统代理已开启，直连已暂停"
                _dohLatencies.update { it + (server.id to -1L) }
                _dohSummaries.update { it + (server.id to reason) }
                return@launch
            }
            // 先置 null（测速中）
            _dohLatencies.update { it + (server.id to null) }
            val result = org.linuxdo.android.net.DohProbeEngine.probeDetailed(server)
            // -1 表示超时
            _dohLatencies.update { it + (server.id to (result.latencyMs ?: -1L)) }
            _dohSummaries.update { it + (server.id to result.summary) }
        }
    }

    fun testAllDohServers() {
        viewModelScope.launch(Dispatchers.IO) {
            _isTestingAllDoh.value = true
            try {
                val netState = container.networkMonitor.detect()
                if (netState.shouldPauseDoh && !store.isDohForceOverProxyEnabled()) {
                    val reason = if (netState.isVpn) "VPN 已开启，直连已暂停" else "系统代理已开启，直连已暂停"
                    val mapLatency = _dohServers.value.associate { it.id to -1L }
                    val mapSummary = _dohServers.value.associate { it.id to reason }
                    _dohLatencies.update { it + mapLatency }
                    _dohSummaries.update { it + mapSummary }
                    return@launch
                }
                val servers = _dohServers.value
                val jobs = servers.map { server ->
                    launch {
                        _dohLatencies.update { it + (server.id to null) }
                        val result = org.linuxdo.android.net.DohProbeEngine.probeDetailed(server)
                        _dohLatencies.update { it + (server.id to (result.latencyMs ?: -1L)) }
                        _dohSummaries.update { it + (server.id to result.summary) }
                    }
                }
                jobs.forEach { it.join() }
            } finally {
                _isTestingAllDoh.value = false
            }
        }
    }

    fun addCustomDohServer(url: String, name: String) {
        val existing = _dohServers.value
        val cleanUrl = url.trim()
        if (existing.any { it.url.trimEnd('/') == cleanUrl.trimEnd('/') }) return
        val newServer = org.linuxdo.android.net.DohServer(url = cleanUrl, name = name.trim().ifBlank { cleanUrl }, isCustom = true)
        val newList = existing + newServer
        saveDohServers(newList)
    }

    fun updateDohServer(oldServer: org.linuxdo.android.net.DohServer, newUrl: String, newName: String) {
        val existing = _dohServers.value
        val cleanUrl = newUrl.trim()
        val cleanName = newName.trim().ifBlank { cleanUrl }
        val updatedServer = oldServer.copy(url = cleanUrl, name = cleanName)
        val newList = existing.map { if (it.id == oldServer.id) updatedServer else it }
        saveDohServers(newList)
        if (_selectedDohServerUrl.value == oldServer.url) {
            setDohServerSelected(updatedServer)
        }
    }

    fun deleteDohServer(server: org.linuxdo.android.net.DohServer) {
        val newList = _dohServers.value.filter { it.id != server.id }
        val finalList = if (newList.isEmpty()) org.linuxdo.android.net.DohPresets.ALL else newList
        saveDohServers(finalList)
        if (_selectedDohServerUrl.value == server.url) {
            setDohServerSelected(finalList.first())
        }
    }

    fun resetDohServersToDefault() {
        store.setDohServersJson(null)
        store.setDohCustomServersJson(null)
        val defaultList = org.linuxdo.android.net.DohPresets.ALL
        _dohServers.value = defaultList
        setDohServerSelected(org.linuxdo.android.net.DohPresets.DEFAULT)
        container.rebuildHttpClient()
    }

    private fun saveDohServers(servers: List<org.linuxdo.android.net.DohServer>) {
        val jsonArr = org.json.JSONArray().apply {
            servers.forEach { s ->
                put(org.json.JSONObject().apply {
                    put("url", s.url)
                    put("name", s.name)
                    put("isCustom", s.isCustom)
                    if (s.bootstrapIps.isNotEmpty()) {
                        put("bootstrapIps", org.json.JSONArray(s.bootstrapIps))
                    }
                })
            }
        }
        store.setDohServersJson(jsonArr.toString())
        _dohServers.value = servers
        container.rebuildHttpClient()
    }

    private var autoProbeJob: Job? = null
    private var activeCategory: Pair<Int, String>? = null
    private var autoProbeVisible = false

    fun setAutoProbeVisible(visible: Boolean, categoryId: Int? = null, slug: String? = null) {
        val category = if (categoryId != null && !slug.isNullOrBlank()) {
            categoryId to slug
        } else {
            null
        }
        if (autoProbeVisible == visible && activeCategory == category) return
        val wasVisible = autoProbeVisible
        autoProbeVisible = visible
        activeCategory = category
        incomingProbeGeneration++
        // 从不可见到可见（切换回前台或列表）时快速延迟 3 秒触发一次探测
        val initialDelay = if (!wasVisible && visible) 3_000L else 3_000L
        restartAutoProbeLoop(initialDelay)
    }

    private fun canProbeIncomingTopics(): Boolean = autoProbeVisible &&
        _state.value.screen == Screen.List && !store.sessionChanging && !_accounts.value.switching

    private fun restartAutoProbeLoop(initialDelayMs: Long? = 3_000L) {
        autoProbeJob?.cancel()
        val interval = store.getAutoRefreshInterval()
        if (interval == AutoRefreshInterval.OFF || !canProbeIncomingTopics()) {
            autoProbeJob = null
            return
        }
        autoProbeJob = viewModelScope.launch {
            var firstRound = true
            while (isActive) {
                val delayTime = if (firstRound && initialDelayMs != null) {
                    firstRound = false
                    initialDelayMs
                } else {
                    firstRound = false
                    interval.nextDelayMs()
                }
                delay(delayTime)
                if (!canProbeIncomingTopics()) break
                val currentCategory = activeCategory
                if (currentCategory != null) {
                    probeIncomingTopics(_state.value.homeFeed, categoryId = currentCategory.first)
                } else {
                    probeIncomingTopics(_state.value.homeFeed)
                }
            }
        }
    }

    /** 进入话题详情时也探测一次新话题，使用户阅读完返回列表时新话题状态已刷新就绪 */
    fun probeOnEnteringDetail() {
        if (!store.isAutoRefreshIncomingEnabled() || repository.isRateLimitCoolingDown()) return
        viewModelScope.launch {
            delay(1_200L)
            if (!store.isAutoRefreshIncomingEnabled() || repository.isRateLimitCoolingDown()) return@launch
            val currentCategory = activeCategory
            if (currentCategory != null) {
                probeIncomingTopics(_state.value.homeFeed, categoryId = currentCategory.first, allowInDetail = true)
            } else {
                probeIncomingTopics(_state.value.homeFeed, allowInDetail = true)
            }
        }
    }
    private val readTrackers = mutableMapOf<Long, TopicReadTracker>()
    private val readReportJobs = mutableMapOf<Long, Job>()
    private val readSendJobs = mutableMapOf<Long, Job>()
    /** 当前处于前台可见、活跃状态的话题集合（被验证页遮挡、切后台或被下层路由盖住时不在其中）。 */
    private val activeTopicIds = Collections.synchronizedSet(mutableSetOf<Long>())
    /** 各话题在当前屏幕上当前可见的楼层号。 */
    private val currentVisiblePostsByTopic = mutableMapOf<Long, List<Int>>()
    private val highestVisiblePostByTopic = mutableMapOf<Long, Int>()
    fun setTopicActive(topicId: Long, active: Boolean) {
        if (active) {
            activeTopicIds.add(topicId)
            beginReadTracking(topicId)
            startTopicLiveUpdates(topicId)
        } else {
            activeTopicIds.remove(topicId)
            topicLiveAtEnd.remove(topicId)
            topicLiveScrolling.remove(topicId)
            if (topicLiveId == topicId) {
                topicLiveJob?.cancel()
                topicLiveId = null
            }
            readReportJobs.remove(topicId)?.cancel()
            readSendJobs.remove(topicId)?.cancel()
            readTrackers.remove(topicId)?.setActive(false, SystemClock.elapsedRealtime())
        }
    }

    private fun isTopicActive(topicId: Long): Boolean {
        return _state.value.screen == Screen.List && activeTopicIds.contains(topicId) &&
            !store.sessionChanging && !_accounts.value.switching
    }

    fun onTopicViewport(topicId: Long, atLatestEnd: Boolean, scrolling: Boolean) {
        if (atLatestEnd) topicLiveAtEnd.add(topicId) else topicLiveAtEnd.remove(topicId)
        if (scrolling) topicLiveScrolling.add(topicId) else topicLiveScrolling.remove(topicId)
        if (!scrolling) topicLiveSessions[topicId]?.updates?.trySend(Unit)
    }

    private fun canAppendTopicPosts(topicId: Long, detail: DetailUiState, live: TopicLiveSession): Boolean {
        return canAppendRealtimePosts(detail, live.pending, topicId in topicLiveAtEnd, topicId in topicLiveScrolling)
    }

    private fun startTopicLiveUpdates(topicId: Long) {
        if (topicLiveId == topicId && topicLiveJob?.isActive == true) return
        val previous = topicLiveJob
        previous?.cancel()
        topicLiveId = topicId
        val live = topicLiveSessions.getOrPut(topicId) { TopicLiveSession() }
        if (SystemClock.elapsedRealtime() >= live.nextCalibrationAt) live.needsCalibration = true
        val accountGeneration = accountScopeGeneration
        val sessionGeneration = store.sessionGeneration
        fun stillActive() = topicLiveId == topicId && isTopicActive(topicId) &&
            accountGeneration == accountScopeGeneration && sessionGeneration == store.sessionGeneration
        topicLiveJob = viewModelScope.launch {
            previous?.join()
            delay(350L) // 与 Fluxdo 订阅变更防抖一致，快速进出页面不建连接。
            if (!stillActive()) return@launch
            coroutineScope {
                launch {
                    var failures = 0
                    while (isActive && stillActive()) {
                        if (!live.initialized) {
                            val snapshot = _details.value[topicId]?.topic
                            if (snapshot == null) {
                                delay(350L)
                                continue
                            }
                            // Discourse 官方用快照游标订阅，补齐详情响应与订阅建立之间的消息。
                            snapshot.messageBusLastId?.let { cursor ->
                                live.cursor = cursor
                                live.initialized = true
                                live.updates.trySend(Unit)
                            }
                        }
                        if (repository.isSilentPrefetchBackingOff()) {
                            delay(60_000L)
                            continue
                        }
                        delay(maxOf(100L, ApiQuotaPool.TOPIC_EVENTS.remainingWaitMs()))
                        if (!stillActive()) break
                        if (!ApiQuotaPool.TOPIC_EVENTS.tryAcquire()) continue
                        try {
                            val messages = repository.pollTopicMessages(topicId, live.clientId, live.cursor, ++live.sequence)
                            if (!stillActive()) break
                            failures = 0
                            ApiQuotaPool.TOPIC_EVENTS.onSuccess()
                            for (message in messages) {
                                if (message.channel == "/__status") {
                                    val cursor = ((message.data as? JsonObject)?.get("/topic/$topicId") as? JsonPrimitive)?.longOrNull
                                    if (cursor != null) {
                                        // 服务端游标回退或跳过积压表示需要快照校准。
                                        if (live.initialized && cursor != live.cursor) live.needsCalibration = true
                                        live.cursor = cursor
                                    }
                                } else if (message.channel == "/topic/$topicId" && message.messageId > live.cursor) {
                                    live.cursor = message.messageId
                                    if (live.messages.size < 300) live.messages.addLast(message)
                                    else live.needsCalibration = true
                                }
                            }
                            if (!live.initialized) live.needsCalibration = true
                            live.initialized = true
                            live.updates.trySend(Unit)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (!stillActive()) break
                            failures++
                            repository.enterSilentPrefetchBackoff(error)
                            // 认证/权限错误不持续重试；重新进入页面后才建立新订阅。
                            if (error is HttpStatusException && error.status in setOf(401, 404)) break
                            val wait = when (error) {
                                is RateLimitException -> maxOf(15_000L, error.retryAfterMs)
                                is NeedsInteractiveVerification -> 120_000L
                                is HttpStatusException -> maxOf(3_000L * failures.coerceAtMost(60), (error.retryAfterSeconds ?: 0L) * 1_000L)
                                else -> (3_000L * failures.coerceAtMost(60)).coerceAtLeast(3_000L)
                            }
                            delay(wait)
                        }
                    }
                }
                live.updates.trySend(Unit)
                for (signal in live.updates) {
                    if (!stillActive()) break
                    if (!live.initialized || topicId in topicLiveScrolling) continue
                    val current = _details.value[topicId] ?: continue
                    if (current.topic == null || current.loading || current.refreshing || current.reactingPosts.isNotEmpty() ||
                        pageJobs["topic:$topicId"]?.isActive == true) continue
                    live.calibration?.let { (before, fresh) ->
                        mergeTopicCalibration(topicId, before, fresh, live)
                        live.calibration = null
                    }
                    applyTopicLiveMessages(topicId, live)
                    if (repository.isRateLimitCoolingDown() || repository.isSilentPrefetchBackingOff()) continue
                    if (live.needsCalibration) {
                        val now = SystemClock.elapsedRealtime()
                        if (now < live.nextCalibrationAt) {
                            if (live.calibrationWakeAt != live.nextCalibrationAt) {
                                live.calibrationWakeAt = live.nextCalibrationAt
                                launch {
                                    delay((live.nextCalibrationAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                                    live.calibrationWakeAt = 0L
                                    live.updates.trySend(Unit)
                                }
                            }
                        } else if (ApiQuotaPool.TOPIC_SYNC.tryAcquire()) {
                            live.nextCalibrationAt = now + 60_000L
                            live.needsCalibration = false
                            try {
                                val before = _details.value[topicId] ?: continue
                                val fresh = repository.fetchTopic(topicId, trackVisit = false, present = false)
                                if (!stillActive()) break
                                live.calibration = before to fresh
                                live.updates.trySend(Unit)
                                ApiQuotaPool.TOPIC_SYNC.onSuccess()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                repository.enterSilentPrefetchBackoff(error)
                                live.nextCalibrationAt = SystemClock.elapsedRealtime() + 120_000L
                            }
                        }
                    }
                    if (live.calibration != null) continue
                    var detail = _details.value[topicId] ?: continue
                    if (live.readyPosts.isNotEmpty() && canAppendTopicPosts(topicId, detail, live)) {
                        detail = mergeRealtimePosts(detail, live.readyPosts.values.toList())
                        _details.update { it + (topicId to detail) }
                        live.pending.removeAll(live.readyPosts.keys)
                        live.readyPosts.clear()
                        updateCachedTopicDetail(topicId, refreshTtl = false)
                    }
                    live.pending.removeAll(detail.consumedIds)
                    if (live.pending.isEmpty() || !canAppendTopicPosts(topicId, detail, live) || live.postFailures > 5) continue
                    val wait = maxOf(live.postRetryAt - SystemClock.elapsedRealtime(), ApiQuotaPool.TOPIC_POSTS.remainingWaitMs())
                    if (wait > 0L) delay(wait)
                    if (!stillActive() || repository.isSilentPrefetchBackingOff()) continue
                    val latest = _details.value[topicId] ?: continue
                    if (latest.loading || latest.refreshing || latest.reactingPosts.isNotEmpty() ||
                        pageJobs["topic:$topicId"]?.isActive == true || !canAppendTopicPosts(topicId, latest, live)) continue
                    if (!ApiQuotaPool.TOPIC_POSTS.tryAcquire()) continue
                    val requested = live.pending.take(20)
                    try {
                        val fetched = repository.fetchPosts(topicId, requested, present = false)
                            .filter { it.id in requested && (it.topicId == 0L || it.topicId == topicId) }
                        if (!stillActive()) break
                        // 在途期间开始滚动时缓存已取回的内容，下一次安全落地不重复联网。
                        fetched.forEach { live.readyPosts[it.id] = it }
                        live.updates.trySend(Unit)
                        if (fetched.size < requested.size) throw java.io.IOException("部分新增楼层暂不可用")
                        live.postFailures = 0
                        live.postRetryAt = 0L
                        ApiQuotaPool.TOPIC_POSTS.onSuccess()
                        if (live.pending.isNotEmpty()) live.updates.trySend(Unit)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        live.postFailures++
                        val blocked = repository.enterSilentPrefetchBackoff(error)
                        val waitMs = maxOf(topicPostRetryDelayMs(live.postFailures),
                            if (blocked) 120_000L else 0L,
                            (error as? RateLimitException)?.retryAfterMs ?: 0L)
                        live.postRetryAt = SystemClock.elapsedRealtime() + waitMs
                        if (live.postFailures <= 5) launch {
                            delay(waitMs)
                            live.updates.trySend(Unit)
                        }
                    }
                }
            }
        }
    }

    private fun applyTopicLiveMessages(topicId: Long, live: TopicLiveSession) {
        var detail = _details.value[topicId] ?: return
        while (live.messages.isNotEmpty()) {
            val data = live.messages.removeFirst().data as? JsonObject ?: continue
            if ((data["reload_topic"] as? JsonPrimitive)?.booleanOrNull == true) live.needsCalibration = true
            when ((data["type"] as? JsonPrimitive)?.contentOrNull) {
                "created" -> {
                    val postId = (data["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L } ?: continue
                    val topic = detail.topic ?: continue
                    if (postId in topic.postStream.stream || postId in detail.consumedIds || postId in live.pending) continue
                    val append = canAppendTopicPosts(topicId, detail, live)
                    live.pending.add(postId)
                    if (live.postFailures > 5) live.postFailures = 0
                    detail = detail.copy(
                        topic = topic.copy(postsCount = topic.postsCount + 1,
                            postStream = if (append) topic.postStream else topic.postStream.copy(stream = topic.postStream.stream + postId)),
                        realtimePostIds = detail.realtimePostIds + postId,
                    )
                }
                "stats" -> (data["posts_count"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }?.let { count ->
                    detail = detail.copy(topic = detail.topic?.copy(postsCount = count))
                }
                "deleted", "destroyed", "recovered" -> live.needsCalibration = true
            }
        }
        if (detail != _details.value[topicId]) {
            _details.update { it + (topicId to detail) }
            updateCachedTopicDetail(topicId, refreshTtl = false)
            updateListPostCount(topicId, detail.topic?.postsCount ?: return)
        }
    }

    private fun mergeTopicCalibration(topicId: Long, before: DetailUiState, fresh: TopicDetail, live: TopicLiveSession) {
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            // 手工跳楼、排序或刷新已经换了窗口时丢弃这份后台快照。
            if (current.locateVersion != before.locateVersion || current.windowStartId != before.windowStartId ||
                current.descending != before.descending || current.loading || current.refreshing) return@update states
            val currentCursor = current.topic?.messageBusLastId
            if (currentCursor != null && fresh.messageBusLastId != null && currentCursor > fresh.messageBusLastId) return@update states
            val oldStream = before.topic?.postStream?.stream.orEmpty().toSet()
            val lateIds = current.topic?.postStream?.stream.orEmpty().filter { it !in oldStream && it !in fresh.postStream.stream }
            val stream = (fresh.postStream.stream + lateIds).distinct()
            val added = stream.filter { it !in oldStream && it !in current.consumedIds }
            live.pending.addAll(added)
            live.pending.retainAll(stream.toSet())
            live.readyPosts.keys.retainAll(stream.toSet())
            val posts = current.posts.filter { it.id in stream }
            states + (topicId to current.copy(
                topic = fresh.copy(postsCount = fresh.postsCount + lateIds.size,
                    postStream = fresh.postStream.copy(posts = current.topic?.postStream?.posts.orEmpty(), stream = stream)),
                posts = posts,
                consumedIds = current.consumedIds.intersect(stream.toSet()),
                realtimePostIds = live.pending.toSet(),
                realtimeVersion = current.realtimeVersion + 1,
            ))
        }
        updateCachedTopicDetail(topicId)
        _details.value[topicId]?.topic?.let { updateListPostCount(topicId, it.postsCount, it.highestPostNumber) }
    }
    private val _themeMode = MutableStateFlow(ThemeMode.entries.firstOrNull {
        it.name == container.preferences.getString("theme_mode", ThemeMode.System.name)
    } ?: ThemeMode.System)
    val themeMode = _themeMode.asStateFlow()

    private val _skinPreset = MutableStateFlow(
        run {
            val saved = store.getSkinPresetId()
            if (saved == "classic" && !store.isUserCustomizedSkin()) {
                store.setSkinPresetId("spongebob")
                org.linuxdo.android.ui.design.SkinPreset.SPONGEBOB
            } else {
                org.linuxdo.android.ui.design.SkinPreset.fromId(saved)
            }
        }
    )
    val skinPreset: StateFlow<org.linuxdo.android.ui.design.SkinPreset> = _skinPreset.asStateFlow()

    fun setSkinPreset(preset: org.linuxdo.android.ui.design.SkinPreset) {
        store.setUserCustomizedSkin(true)
        _skinPreset.value = preset
        store.setSkinPresetId(preset.id)
    }

    private val _loginUiState = MutableStateFlow(
        LoginUiState(
            savedLogin = store.savedLogin().orEmpty(),
            savedPassword = store.savedPassword().orEmpty(),
            rememberPassword = store.isRememberPassword(),
        )
    )
    val loginUiState: StateFlow<LoginUiState> = _loginUiState.asStateFlow()

    private fun loadCachedUpdateInfo(): UpdateInfo? {
        val prefs = container.preferences
        val latestVersion = prefs.getString("cached_update_version", null)?.takeIf { it.isNotBlank() } ?: return null
        val currentVersion = BuildConfig.VERSION_NAME.substringBefore("-")
        if (compareVersions(latestVersion, currentVersion) <= 0) {
            clearCachedUpdateInfo()
            return null
        }
        return UpdateInfo(
            latestVersion = latestVersion,
            title = prefs.getString("cached_update_title", "").orEmpty(),
            changelog = prefs.getString("cached_update_changelog", "").orEmpty(),
            downloadUrl = prefs.getString("cached_update_download_url", "").orEmpty(),
            releasePageUrl = prefs.getString("cached_update_release_url", "").orEmpty(),
            mandatory = prefs.getBoolean("cached_update_mandatory", false),
            apkSha256 = prefs.getString("cached_update_apk_sha256", null),
        )
    }

    private fun saveCachedUpdateInfo(info: UpdateInfo) {
        container.preferences.edit {
            putString("cached_update_version", info.latestVersion)
            putString("cached_update_title", info.title)
            putString("cached_update_changelog", info.changelog)
            putString("cached_update_download_url", info.downloadUrl)
            putString("cached_update_release_url", info.releasePageUrl)
            putBoolean("cached_update_mandatory", info.mandatory)
            putString("cached_update_apk_sha256", info.apkSha256)
            putLong("cached_update_check_time", System.currentTimeMillis())
        }
    }

    private fun clearCachedUpdateInfo() {
        container.preferences.edit {
            remove("cached_update_version")
            remove("cached_update_title")
            remove("cached_update_changelog")
            remove("cached_update_download_url")
            remove("cached_update_release_url")
            remove("cached_update_mandatory")
            remove("cached_update_apk_sha256")
            remove("cached_update_check_time")
        }
    }

    fun onVersionItemClick() {
        val currentInfo = _updateState.value.updateInfo
        if (currentInfo != null) {
            _updateState.update { it.copy(showDialog = true) }
        } else {
            checkForUpdates(manual = true)
        }
    }

    private val _updateState = MutableStateFlow(
        UpdateUiState(
            updateInfo = loadCachedUpdateInfo(),
        )
    )
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private var apkUpdateJob: Job? = null

    fun dismissUpdateDialog(ignoreThisVersion: Boolean = false) {
        val current = _updateState.value
        val info = current.updateInfo ?: return
        if (info.mandatory) return
        if (ignoreThisVersion) {
            container.preferences.edit { putString("ignored_update_version", info.latestVersion) }
        }
        // 关掉对话框就不该再占着带宽继续下。
        cancelApkUpdate()
        _updateState.update { it.copy(showDialog = false) }
    }

    /**
     * 应用内下载新版本安装包并拉起系统安装器。
     *
     * 跑在 viewModelScope 而不是前台服务:包体不大,正常网络数秒完成,用户就停在对话框上。
     * 进程被杀则下载中断,重开再下即可,不值得为此多拉一个常驻载体。
     *
     * Android 不允许第三方静默安装,所以这里最多做到「下完直接弹安装确认」。
     */
    fun startApkUpdate() {
        val info = _updateState.value.updateInfo ?: return
        if (apkUpdateJob?.isActive == true) return
        val context = getApplication<Application>()

        // 「安装未知应用」是 Android 8.0 起的特殊权限,Manifest 声明不等于已授予。
        if (!ApkUpdateInstaller.canInstall(context)) {
            _updateState.update {
                it.copy(apkStage = ApkUpdateStage.AwaitingPermission, apkError = null)
            }
            return
        }

        apkUpdateJob = viewModelScope.launch {
            _updateState.update {
                it.copy(
                    apkStage = ApkUpdateStage.Downloading,
                    apkDownloadedBytes = 0L,
                    apkTotalBytes = 0L,
                    apkError = null,
                )
            }
            val result = ApkUpdateInstaller.download(
                context = context,
                url = info.downloadUrl,
                expectedSha256 = info.apkSha256,
            ) { downloaded, total ->
                // 字节读完但方法还没返回,这段时间正是在算摘要,如实显示为校验中。
                val verifying = total > 0L && downloaded >= total && !info.apkSha256.isNullOrBlank()
                _updateState.update {
                    it.copy(
                        apkStage = if (verifying) ApkUpdateStage.Verifying else ApkUpdateStage.Downloading,
                        apkDownloadedBytes = downloaded,
                        apkTotalBytes = total,
                    )
                }
            }
            when (result) {
                is ApkUpdateInstaller.Result.Success -> {
                    _updateState.update { it.copy(apkStage = ApkUpdateStage.Installing, apkError = null) }
                    if (!ApkUpdateInstaller.install(context, result.file)) {
                        _updateState.update {
                            it.copy(
                                apkStage = ApkUpdateStage.Failed,
                                apkError = "无法拉起安装程序，请改用浏览器下载。",
                            )
                        }
                    }
                }
                is ApkUpdateInstaller.Result.Failure -> {
                    _updateState.update {
                        it.copy(apkStage = ApkUpdateStage.Failed, apkError = result.message)
                    }
                }
            }
        }
    }

    /** 用户返回授权页后重新发起;权限仍未授予时会再次回到等待授权态。 */
    fun retryApkUpdateAfterPermission() {
        _updateState.update { it.copy(apkStage = ApkUpdateStage.Idle, apkError = null) }
        startApkUpdate()
    }

    fun cancelApkUpdate() {
        apkUpdateJob?.cancel()
        apkUpdateJob = null
        _updateState.update {
            it.copy(
                apkStage = ApkUpdateStage.Idle,
                apkDownloadedBytes = 0L,
                apkTotalBytes = 0L,
                apkError = null,
            )
        }
    }

    /**
     * 检查最新版本，优先读取公开仓库根目录的 `version.json` 静态版本清单，
     * 若 `version.json` 不可达则回退至 GitHub Releases 接口。
     *
     * 必升级触发规则（满足任一即为必升级）：
     * 1. `version.json` 中 `forceUpdate: true`
     * 2. `version.json` 中 `minVersionCode` 大于当前 `versionCode`（或 `minVersionName` 高于当前版本）
     * 3. 回退 Release 正文包含 `[force]`、`[mandatory]`、`[必升]`、`[min_version: X.Y.Z]` 或 `[min_code: N]`
     */
    fun checkForUpdates(manual: Boolean = false) {
        if (_updateState.value.checking) {
            if (manual) {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "正在检查更新中，请稍候...", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        viewModelScope.launch {
            _updateState.update {
                it.copy(
                    checking = true,
                    manualChecking = manual,
                    statusMessage = if (manual) "正在检查新版本..." else it.statusMessage,
                )
            }
            try {
                val currentVersion = _updateState.value.currentVersion
                val currentCode = _updateState.value.currentVersionCode
                val info = withContext(Dispatchers.IO) {
                    for (manifestUrl in Config.UPDATE_VERSION_JSON_URLS) {
                        val manifestJson = runCatching {
                            val req = Request.Builder()
                                .url(manifestUrl)
                                .header("Accept", "application/vnd.github.raw+json, application/json;q=0.9")
                                .header("Cache-Control", "no-cache")
                                .header("User-Agent", container.userAgent)
                                .get()
                                .build()
                            container.httpClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) {
                                    throw HttpStatusException(resp.code, "Manifest HTTP ${resp.code}")
                                }
                                resp.body?.string().orEmpty()
                            }
                        }.getOrNull()
                        if (!manifestJson.isNullOrBlank()) {
                            val (parsed, manifestInfo) = parseVersionManifest(manifestJson, currentVersion, currentCode)
                            if (parsed) {
                                return@withContext manifestInfo
                            }
                        }
                    }

                    val (_, localManifestInfo) = parseVersionManifest(
                        Config.FALLBACK_VERSION_MANIFEST_JSON,
                        currentVersion,
                        currentCode,
                    )
                    if (localManifestInfo != null) {
                        return@withContext localManifestInfo
                    }

                    val apiJson = runCatching {
                        val req = Request.Builder()
                            .url(Config.UPDATE_RELEASES_API_URL)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", container.userAgent)
                            .get()
                            .build()
                        container.httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw HttpStatusException(resp.code, "GitHub API HTTP ${resp.code}")
                            }
                            resp.body?.string().orEmpty()
                        }
                    }.getOrNull()

                    if (!apiJson.isNullOrBlank()) {
                        parseReleaseUpdateInfo(apiJson, currentVersion, currentCode)
                    } else {
                        // 兜底：若 api.github.com 触发匿名限流或连接受阻，直接请求 GitHub Releases 页面利用 302 重定向提取最新 tag。
                        val req = Request.Builder()
                            .url(Config.UPDATE_RELEASES_PAGE_URL)
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("User-Agent", container.userAgent)
                            .get()
                            .build()
                        container.httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw HttpStatusException(resp.code, "GitHub Releases HTTP ${resp.code}")
                            }
                            val finalUrl = resp.request.url.toString()
                            val html = resp.body?.string().orEmpty()
                            parseReleaseHtmlFallback(finalUrl, html, currentVersion, currentCode)
                        }
                    }
                }
                if (info != null) {
                    saveCachedUpdateInfo(info)
                    val ignoredVersion = container.preferences.getString("ignored_update_version", null)
                    val shouldPopup = info.mandatory || manual || ignoredVersion != info.latestVersion
                    diagnostics.log("检测到新版本: v${info.latestVersion} (必升=${info.mandatory})")
                    _updateState.update {
                        it.copy(
                            checking = false,
                            manualChecking = false,
                            updateInfo = info,
                            showDialog = shouldPopup,
                            statusMessage = "发现新版本 v${info.latestVersion}",
                        )
                    }
                } else {
                    clearCachedUpdateInfo()
                    diagnostics.log("版本检查完成: 当前已是最新 (v$currentVersion)")
                    _updateState.update {
                        it.copy(
                            checking = false,
                            manualChecking = false,
                            updateInfo = null,
                            showDialog = false,
                            statusMessage = if (manual) "当前已是最新版本 (v$currentVersion)" else null,
                        )
                    }
                    if (manual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(getApplication(), "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("检查更新失败: ${error.message}")
                _updateState.update {
                    it.copy(
                        checking = false,
                        manualChecking = false,
                        statusMessage = if (manual) "检查更新失败，请稍后重试" else it.statusMessage,
                    )
                }
                if (manual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun parseVersionManifest(
        rawJson: String,
        currentVersion: String,
        currentCode: Int,
    ): Pair<Boolean, UpdateInfo?> {
        val outer = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return false to null
        val root = if (outer["versionName"] == null && outer["encoding"]?.jsonPrimitive?.contentOrNull == "base64") {
            val encoded = outer["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val decoded = runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull().orEmpty()
            runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return false to null
        } else {
            outer
        }

        val latestVersion = root["versionName"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("v")?.removePrefix("V")?.substringBefore("-")?.trim().orEmpty()
        if (latestVersion.isEmpty()) return false to null

        val latestCode = root["versionCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val isNewer = if (latestCode > 0 && currentCode > 0) {
            latestCode > currentCode || compareVersions(latestVersion, currentVersion) > 0
        } else {
            compareVersions(latestVersion, currentVersion) > 0
        }
        if (!isNewer) return true to null

        val title = root["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "v$latestVersion 版本更新"
        val changelogElement = root["changelog"]
        val changelog = when {
            changelogElement == null -> ""
            runCatching { changelogElement.jsonArray }.isSuccess -> {
                changelogElement.jsonArray
                    .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            }
            else -> runCatching { changelogElement.jsonPrimitive.contentOrNull?.trim().orEmpty() }.getOrDefault("")
        }.ifBlank { "优化使用体验并修复已知问题。" }

        val forceUpdate = root["forceUpdate"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val minVersionCode = root["minVersionCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val minVersionName = root["minVersionName"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("v")?.removePrefix("V")?.trim()
        val mandatory = forceUpdate ||
            (minVersionCode != null && currentCode < minVersionCode) ||
            (!minVersionName.isNullOrBlank() && compareVersions(currentVersion, minVersionName) < 0)

        val apkUrl = root["apkUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: Config.UPDATE_RELEASES_PAGE_URL
        val releasePageUrl = root["releasePageUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: Config.UPDATE_RELEASES_PAGE_URL
        // 可选校验和：留空或缺省即跳过校验，不阻塞发版。
        val apkSha256 = root["apkSha256"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        return true to UpdateInfo(
            latestVersion = latestVersion,
            title = title,
            changelog = changelog,
            downloadUrl = apkUrl,
            releasePageUrl = releasePageUrl,
            mandatory = mandatory,
            apkSha256 = apkSha256,
        )
    }

    private fun parseReleaseUpdateInfo(
        rawJson: String,
        currentVersion: String,
        currentCode: Int,
    ): UpdateInfo? {
        val root = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val latestVersion = tagName.removePrefix("v").removePrefix("V").trim()
        if (latestVersion.isEmpty() || compareVersions(latestVersion, currentVersion) <= 0) {
            return null
        }
        val releaseName = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "v$latestVersion 版本更新"
        val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: Config.UPDATE_RELEASES_PAGE_URL
        val assets = root["assets"]?.jsonArray.orEmpty()
        val apkUrl = assets.firstNotNullOfOrNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) url else null
        } ?: htmlUrl

        val rawBody = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return buildUpdateInfo(latestVersion, releaseName, rawBody, apkUrl, htmlUrl, currentVersion, currentCode)
    }

    private fun parseReleaseHtmlFallback(
        finalUrl: String,
        html: String,
        currentVersion: String,
        currentCode: Int,
    ): UpdateInfo? {
        val tagName = finalUrl.substringAfter("/releases/tag/", "").substringBefore("?").substringBefore("#").trim()
            .ifBlank {
                Regex("""/releases/tag/(v?[0-9][^"'/\s<>]*)""").find(html)?.groupValues?.getOrNull(1).orEmpty()
            }
        val latestVersion = tagName.removePrefix("v").removePrefix("V").substringBefore("-").trim()
        if (latestVersion.isEmpty() || compareVersions(latestVersion, currentVersion) <= 0) {
            return null
        }
        val repoBase = Config.UPDATE_RELEASES_PAGE_URL.substringBefore("/releases")
        val apkUrl = "$repoBase/releases/download/$tagName/linuxdo-$latestVersion-release.apk"
        val bodyText = runCatching {
            org.jsoup.Jsoup.parse(html).select(".markdown-body").firstOrNull()?.wholeText()?.trim().orEmpty()
        }.getOrDefault("")
        return buildUpdateInfo(
            latestVersion = latestVersion,
            releaseName = "v$latestVersion 版本更新",
            rawBody = bodyText,
            apkUrl = apkUrl,
            htmlUrl = finalUrl.ifBlank { Config.UPDATE_RELEASES_PAGE_URL },
            currentVersion = currentVersion,
            currentCode = currentCode,
        )
    }

    private fun buildUpdateInfo(
        latestVersion: String,
        releaseName: String,
        rawBody: String,
        apkUrl: String,
        htmlUrl: String,
        currentVersion: String,
        currentCode: Int,
    ): UpdateInfo {
        val hasForceFlag = Regex("""\[(force|mandatory|必升)]|force_update\s*:\s*true""", RegexOption.IGNORE_CASE)
            .containsMatchIn(rawBody)
        val minVersionMatch = Regex("""\[min_version\s*:\s*v?([0-9.]+)]""", RegexOption.IGNORE_CASE)
            .find(rawBody)?.groupValues?.getOrNull(1)
        val minCodeMatch = Regex("""\[min_code\s*:\s*(\d+)]""", RegexOption.IGNORE_CASE)
            .find(rawBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val belowMinVersion = minVersionMatch != null && compareVersions(currentVersion, minVersionMatch) < 0
        val belowMinCode = minCodeMatch != null && currentCode < minCodeMatch
        val mandatory = hasForceFlag || belowMinVersion || belowMinCode

        val cleanChangelog = rawBody
            .replace(Regex("""\[(force|mandatory|必升)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[min_version\s*:\s*v?[0-9.]+]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[min_code\s*:\s*\d+]""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "优化使用体验并修复已知问题。" }

        return UpdateInfo(
            latestVersion = latestVersion,
            title = releaseName,
            changelog = cleanChangelog,
            downloadUrl = apkUrl,
            releasePageUrl = htmlUrl,
            mandatory = mandatory,
        )
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val s1 = v1.removePrefix("v").removePrefix("V").trim()
        val s2 = v2.removePrefix("v").removePrefix("V").trim()
        val parts1 = s1.substringBefore("-").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val parts2 = s2.substringBefore("-").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        // 当数字一致时（如 1.3.0 与 1.3.0-beta），正式版高于预发尝鲜版 (SemVer 规范)
        val isPre1 = s1.contains("beta", ignoreCase = true) || s1.contains("alpha", ignoreCase = true) || s1.contains("preview", ignoreCase = true)
        val isPre2 = s2.contains("beta", ignoreCase = true) || s2.contains("alpha", ignoreCase = true) || s2.contains("preview", ignoreCase = true)
        if (!isPre1 && isPre2) return 1
        if (isPre1 && !isPre2) return -1
        return 0
    }

    private var loginJob: Job? = null
    private var webAuthJob: Job? = null

    fun dismissLoginError() {
        _loginUiState.update { it.copy(error = null, emailSent = false, isRiskOrCaptchaBlocked = false) }
    }

    fun isDarkMode(): Boolean {
        if (_themeMode.value == ThemeMode.Dark) return true
        if (_themeMode.value == ThemeMode.Light) return false
        val app = getApplication<android.app.Application>()
        val uiMode = app.resources.configuration.uiMode
        return (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun openWebLoginModal(
        login: String? = null,
        password: String? = null,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
        rememberPassword: Boolean = false,
    ) {
        if (repository.isRateLimitCoolingDown()) {
            val remainingSec = (repository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
            _loginUiState.update {
                it.copy(
                    loading = false,
                    sendingCode = false,
                    showWebLoginDialog = false,
                    error = "操作过于频繁，请等待 ${remainingSec} 秒后再试",
                )
            }
            return
        }
        if (prepareAddAccountLogin { openWebLoginModal(login, password, secondFactorToken, secondFactorMethod, rememberPassword) }) return
        loginJob?.cancel()
        loginJob = null
        val u = login.orEmpty()
        val p = password.orEmpty()
        _loginUiState.update {
            it.copy(
                loading = false,
                sendingCode = false,
                error = null,
                isRiskOrCaptchaBlocked = false,
                showWebLoginDialog = true,
            )
        }
        val onResult: (Boolean, String?, Boolean) -> Unit = { success, errorMsg, need2FA ->
            viewModelScope.launch {
                when {
                    success -> {
                        diagnostics.log("安全验证与网页登录全流程成功，正在同步会话...")
                        try {
                            android.webkit.CookieManager.getInstance().flush()
                            store.syncFromWebView()
                            if (u.isNotBlank()) store.saveCredentials(u, p, rememberPassword)
                        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                            _loginUiState.update { it.copy(loading = false, error = error.message) }
                            return@launch
                        }
                        _loginUiState.update {
                            LoginUiState(
                                savedLogin = u,
                                savedPassword = if (rememberPassword) p else "",
                                rememberPassword = rememberPassword,
                            )
                        }
                        boot()
                    }
                    need2FA -> {
                        diagnostics.log("安全验证通过，需要进行二步验证 (2FA)")
                        val isRetrying2fa = !secondFactorToken.isNullOrBlank()
                        _loginUiState.update {
                            it.copy(
                                showWebLoginDialog = false,
                                secondFactorRequired = true,
                                loading = false,
                                error = if (isRetrying2fa) errorMsg ?: "两步验证码错误，请重新输入" else null,
                            )
                        }
                    }
                    else -> {
                        diagnostics.log("安全验证或登录失败: $errorMsg")
                        _loginUiState.update {
                            it.copy(
                                showWebLoginDialog = false,
                                loading = false,
                                error = errorMsg ?: "登录失败，请重试。",
                            )
                        }
                    }
                }
            }
        }
        if (!secondFactorToken.isNullOrBlank() && browser.resumeSecondFactorLogin(
                secondFactorToken = secondFactorToken,
                secondFactorMethod = secondFactorMethod,
                onResult = onResult,
            )
        ) {
            diagnostics.log("在原网页认证会话中续交 2FA 验证码")
            return
        }

        diagnostics.log("拉起内联安全认证弹框 (hCaptcha / CF / 2FA 全流程)")
        browser.loadCaptchaLogin(
            login = u,
            password = p,
            secondFactorToken = secondFactorToken,
            secondFactorMethod = secondFactorMethod,
            isDark = isDarkMode(),
            onResult = onResult,
        )
    }

    fun closeWebLoginModal() {
        browser.cancelCaptchaLogin()
        _loginUiState.update { it.copy(showWebLoginDialog = false) }
    }

    fun resetSecondFactor() {
        browser.discardPendingSecondFactorLogin()
        _loginUiState.update { it.copy(secondFactorRequired = false, backupEnabled = false, error = null) }
    }

    /**
     * 原生界面只收集凭据；账号密码、hCaptcha、Cloudflare 与 2FA 均在同一认证 WebView 中提交。
     * 先走 OkHttp 再让 WebView 重提会拆开浏览器指纹和 h_captcha_temp_id，导致 CF 后无法续交。
     */
    fun loginWithPassword(
        login: String,
        password: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
        rememberPassword: Boolean = false,
    ) {
        if (_loginUiState.value.showWebLoginDialog) return
        loginJob?.cancel()
        loginJob = null
        diagnostics.log("原生表单已提交，转入同一 WebView 认证会话")
        openWebLoginModal(login, password, secondFactorToken, secondFactorMethod, rememberPassword)
    }

    /**
     * 发送邮箱一次性登录链接。
     *
     * 与账号密码登录完全同构：**不先走原生 OkHttp**。站点的邮件接口在触发人机验证时
     * 返回裸 403，原生栈无法回答，而且 CF 拦截时还要先耗满 20 秒超时；直接进入
     * 内联认证 WebView，由页面内 JS 依次完成 CSRF → hCaptcha → CF → 发信。
     */
    fun sendLoginEmail(email: String) {
        if (_loginUiState.value.sendingCode || _loginUiState.value.showWebLoginDialog) return
        if (email.isBlank()) {
            _loginUiState.update { it.copy(error = "请输入邮箱") }
            return
        }
        loginJob?.cancel()
        loginJob = null
        diagnostics.log("转入内联安全认证会话发送登录邮件")
        _loginUiState.update { it.copy(sendingCode = false, emailSent = false, error = null) }
        openEmailSendModal(email)
    }

    /**
     * 在内联认证 WebView 里发送登录邮件：与账号密码登录共用同一套
     * CSRF → hCaptcha → CF 挑战 → 提交 的时序，只是最后一步调用 /u/email-login.json。
     */
    private fun openEmailSendModal(email: String) {
        if (repository.isRateLimitCoolingDown()) {
            val remainingSec = (repository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
            _loginUiState.update {
                it.copy(
                    loading = false,
                    sendingCode = false,
                    showWebLoginDialog = false,
                    error = "操作过于频繁，请等待 ${remainingSec} 秒后再试",
                )
            }
            return
        }
        if (prepareAddAccountLogin { openEmailSendModal(email) }) return
        _loginUiState.update {
            it.copy(showWebLoginDialog = true, sendingCode = false, error = null)
        }
        val onResult: (Boolean, String?, Boolean) -> Unit = { success, errorMsg, _ ->
            viewModelScope.launch {
                _loginUiState.update {
                    it.copy(
                        showWebLoginDialog = false,
                        sendingCode = false,
                        emailSent = success,
                        error = if (success) null else (errorMsg ?: "登录邮件发送失败，请重试。"),
                    )
                }
                if (success) diagnostics.log("登录邮件已发送（内联安全认证链路）")
            }
        }
        browser.loadCaptchaLogin(
            login = email,
            password = "",
            isDark = isDarkMode(),
            emailSendTo = email,
            onResult = onResult,
        )
    }

    /**
     * 邮件链接/令牌登录兑换：与账号密码登录共用同一套内联安全认证 (hCaptcha / CF / 2FA)
     * 并在验证通过后通过 Native 直连通道提交 POST /session/email-login/:token。
     */
    private fun openEmailLinkModal(
        email: String,
        token: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        if (repository.isRateLimitCoolingDown()) {
            val remainingSec = (repository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
            _loginUiState.update {
                it.copy(
                    loading = false,
                    showWebLoginDialog = false,
                    error = "操作过于频繁，请等待 ${remainingSec} 秒后再试",
                )
            }
            return
        }
        if (prepareAddAccountLogin { openEmailLinkModal(email, token, secondFactorToken, secondFactorMethod) }) return
        _loginUiState.update { it.copy(showWebLoginDialog = true, loading = false, error = null) }
        val onResult: (Boolean, String?, Boolean) -> Unit = { success, errorMsg, need2FA ->
            viewModelScope.launch {
                when {
                    success -> {
                        diagnostics.log("邮件登录令牌已在应用内完成安全验证与兑换")
                        try {
                            android.webkit.CookieManager.getInstance().flush()
                            store.syncFromWebView()
                        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                            _loginUiState.update { it.copy(loading = false, error = error.message) }
                            return@launch
                        }
                        _loginUiState.update { LoginUiState() }
                        boot()
                    }
                    need2FA -> {
                        diagnostics.log("邮件登录安全验证通过，需要进行二步验证 (2FA)")
                        val isRetrying2fa = !secondFactorToken.isNullOrBlank()
                        _loginUiState.update {
                            it.copy(
                                showWebLoginDialog = false,
                                secondFactorRequired = true,
                                loading = false,
                                error = if (isRetrying2fa) errorMsg ?: "两步验证码错误，请重新输入" else null,
                            )
                        }
                    }
                    else -> {
                        _loginUiState.update {
                            it.copy(
                                showWebLoginDialog = false,
                                loading = false,
                                error = errorMsg ?: "登录链接无效或已过期，请重试或重新获取验证码。",
                            )
                        }
                    }
                }
            }
        }
        if (!secondFactorToken.isNullOrBlank() && browser.resumeSecondFactorLogin(
                secondFactorToken = secondFactorToken,
                secondFactorMethod = secondFactorMethod,
                onResult = onResult,
            )
        ) {
            diagnostics.log("在原邮件认证会话中续交 2FA 验证码")
            return
        }
        browser.loadCaptchaLogin(
            login = email,
            password = "",
            secondFactorToken = secondFactorToken,
            secondFactorMethod = secondFactorMethod,
            isDark = isDarkMode(),
            emailLoginToken = token,
            onResult = onResult,
        )
    }

    /**
     * 从用户粘贴的内容里取出邮件登录 token。
     *
     * 用户多半是直接复制邮件里的整条链接，形如
     * `https://linux.do/session/email-login/<token>`（可能带查询串或末尾斜杠）。
     * 与其要求用户自己截取最后一段，不如这里统一解析：含标记就取标记之后的部分，
     * 否则按原样当作 token——保留旧的手工粘贴习惯。
     */
    private fun extractEmailLoginToken(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val marker = "/session/email-login/"
        if (!text.contains(marker)) return text
        return text.substringAfterLast(marker)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trim('/')
    }

    /**
     * 用邮件里的 token 完成登录。
     */
    fun loginWithEmailCode(
        email: String,
        token: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        if (_loginUiState.value.loading || _loginUiState.value.showWebLoginDialog) return
        val sanitizedToken = extractEmailLoginToken(token)
        if (sanitizedToken.isBlank()) {
            _loginUiState.update { it.copy(loading = false, error = "请输入邮件链接中的 token") }
            return
        }
        if (!sanitizedToken.matches(Regex("^[0-9a-fA-F]+$"))) {
            _loginUiState.update { it.copy(loading = false, error = "登录链接里的 token 格式不正确，请重新复制完整链接") }
            return
        }
        loginJob?.cancel()
        loginJob = null
        diagnostics.log("转入内联安全认证会话兑换邮件登录令牌")
        _loginUiState.update { it.copy(loading = false, error = null) }
        openEmailLinkModal(email, sanitizedToken, secondFactorToken, secondFactorMethod)
    }

    fun dismissRiskControlDialog() {
        _loginUiState.update { it.copy(showRiskControlDialog = false) }
    }

    /** 发起网页授权认证（拉起系统浏览器或外部应用） */
    fun startWebAuth(context: Context? = null) {
        if (webAuthJob?.isActive == true) return
        if (repository.isRateLimitCoolingDown()) {
            val remainingSec = (repository.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
            _loginUiState.update {
                it.copy(
                    showRiskControlDialog = false,
                    webAuthLoading = false,
                    webAuthMessage = null,
                    error = "操作过于频繁，请等待 ${remainingSec} 秒后再试",
                )
            }
            return
        }
        if (prepareAddAccountLogin { startWebAuth(context) }) return
        val targetContext = context ?: getApplication<Application>()
        viewModelScope.launch {
            _loginUiState.update {
                it.copy(
                    showRiskControlDialog = false,
                    webAuthLoading = true,
                    webAuthMessage = "正在准备网页授权...",
                    error = null,
                )
            }
            try {
                val url = userApiKeyAuthService.buildAuthorizeUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                targetContext.startActivity(intent)
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = true,
                        webAuthMessage = "已调起浏览器，请在网页中完成授权...",
                    )
                }
            } catch (e: Exception) {
                diagnostics.log("拉起网页授权失败: ${e.message}")
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = false,
                        webAuthMessage = null,
                        error = "无法打开浏览器，请检查是否安装了浏览器应用",
                    )
                }
            }
        }
    }

    fun handleWebAuthCallback(callbackUri: String) {
        val uri = runCatching { Uri.parse(callbackUri) }.getOrNull() ?: return
        handleWebAuthCallback(uri)
    }

    /** 接收深链回调并兑换令牌 */
    fun handleWebAuthCallback(uri: Uri, allowInteractiveVerification: Boolean = true) {
        // 浏览器可能重复投递深链，OTP 兑换必须单飞。
        if (webAuthJob?.isActive == true) return
        webAuthJob = viewModelScope.launch {
            _loginUiState.update {
                it.copy(
                    webAuthLoading = true,
                    webAuthMessage = "正在兑换登录令牌并验证...",
                    error = null,
                )
            }
            try {
                userApiKeyAuthService.handleAuthRedirect(uri)
                diagnostics.log("网页授权登录成功，重新加载会话")
                _loginUiState.update { it.copy(webAuthMessage = null) }
                _loginUiState.update { LoginUiState() }
                boot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: NeedsInteractiveVerification) {
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = false,
                        webAuthMessage = null,
                        error = if (allowInteractiveVerification) null
                            else "安全验证后仍无法完成兑换，请检查网络后重新授权",
                    )
                }
                if (allowInteractiveVerification) {
                    // 保留本轮授权；验证后重新取 CSRF，避免使用验证前的 _forum_session 配对。
                    resumeAfterVerification = { handleWebAuthCallback(uri, allowInteractiveVerification = false) }
                    enterWebScreen(Screen.Verify, error.message.orEmpty(), error.readProbePath)
                }
            } catch (e: Exception) {
                diagnostics.log("网页授权登录兑换失败: ${e.message}")
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = false,
                        webAuthMessage = null,
                        error = e.message ?: "网页授权登录失败，请重试",
                    )
                }
            }
        }
    }

    fun cancelWebAuth() {
        webAuthJob?.cancel()
        webAuthJob = null
        userApiKeyAuthService.cancel()
        _loginUiState.update {
            it.copy(
                webAuthLoading = false,
                webAuthMessage = null,
            )
        }
    }



    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        container.preferences.edit { putString("theme_mode", mode.name) }
    }

    fun setTreeView(enabled: Boolean) {
        _treeView.value = enabled
        container.preferences.edit { putBoolean("tree_view", enabled) }
    }

    fun setShowUnreadDot(enabled: Boolean) {
        _showUnreadDot.value = enabled
        container.preferences.edit { putBoolean("show_post_unread_dot", enabled) }
    }

    fun setUnreadDotPosition(position: UnreadDotPosition) {
        _unreadDotPosition.value = position
        container.preferences.edit { putString("post_unread_dot_position", position.id) }
    }

    fun setShowTopicUnreadDot(enabled: Boolean) {
        _showTopicUnreadDot.value = enabled
        container.preferences.edit { putBoolean("show_topic_unread_dot", enabled) }
    }

    fun setTopicUnreadDotPosition(position: TopicUnreadDotPosition) {
        _topicUnreadDotPosition.value = position
        container.preferences.edit { putString("topic_unread_dot_position", position.id) }
    }

    fun setAutoRefreshIncoming(enabled: Boolean) {
        setAutoRefreshInterval(if (enabled) AutoRefreshInterval.MIN_1 else AutoRefreshInterval.OFF)
    }

    fun setAutoRefreshInterval(interval: AutoRefreshInterval) {
        store.setAutoRefreshInterval(interval)
        _autoRefreshInterval.value = store.getAutoRefreshInterval()
        _autoRefreshIncoming.value = store.isAutoRefreshIncomingEnabled()
        restartAutoProbeLoop()
    }

    fun setBackgroundNotification(enabled: Boolean) {
        store.setBackgroundNotificationEnabled(enabled)
        val interval = store.getNotificationPollInterval()
        _notificationPollInterval.value = interval
        _backgroundNotification.value = store.isBackgroundNotificationEnabled()
        val app = getApplication<Application>()
        if (interval != NotificationPollInterval.OFF) {
            NotificationWorker.enqueue(app)
        } else {
            NotificationWorker.cancel(app)
        }
        syncKeepAliveService()
    }

    fun setNotificationPollInterval(interval: NotificationPollInterval) {
        store.setNotificationPollInterval(interval)
        _notificationPollInterval.value = store.getNotificationPollInterval()
        _backgroundNotification.value = store.isBackgroundNotificationEnabled()
        val app = getApplication<Application>()
        if (interval != NotificationPollInterval.OFF) {
            NotificationWorker.enqueue(app)
        } else {
            NotificationWorker.cancel(app)
        }
        // 常驻通知上写着当前检测频率,开着保活就重启一次让文案和轮询周期一起跟上新档位。
        syncKeepAliveService()
    }

    fun setCustomRealtimeSeconds(seconds: Int) {
        val valid = seconds.coerceIn(1, 60)
        store.setCustomRealtimeSeconds(valid)
        _customRealtimeSeconds.value = store.getCustomRealtimeSeconds()
        val app = getApplication<Application>()
        if (store.getNotificationPollInterval() == NotificationPollInterval.REALTIME) {
            NotificationWorker.syncSilentLoop(app)
            syncKeepAliveService()
        }
    }

    /**
     * 按「后台检测频率」与「保活开关」两个条件对齐前台服务的运行状态。
     *
     * 档位关掉时不该留着常驻通知,所以这里是两个条件的与,不只看保活开关。
     */
    private fun syncKeepAliveService() {
        val app = getApplication<Application>()
        if (store.isBackgroundNotificationEnabled()) {
            NotificationKeepAliveService.start(app)
        } else {
            NotificationKeepAliveService.stop(app)
        }
    }

    fun setNotificationScope(scope: NotificationScope) {
        store.setNotificationScope(scope)
        _notificationScope.value = store.getNotificationScope()
        // 未读口径跟着作用域变,立刻按新口径重算铃铛与桌面角标,否则要等下一轮刷新才对得上。
        refreshUnread(force = true, reloadNotifications = false)
    }

    fun setKeepAliveService(enabled: Boolean) {
        store.setKeepAliveServiceEnabled(enabled)
        _keepAliveService.value = store.isKeepAliveServiceEnabled()
        syncKeepAliveService()
    }

    /**
     * 开/关「同步阅读进度与统计」。
     *
     * 关闭后清空待上报时间并取消发送；已发往服务端的请求无法撤回。
     */
    fun setSyncReadProgress(enabled: Boolean) {
        if (_syncReadProgress.value == enabled) return
        _syncReadProgress.value = enabled
        container.reading.setSyncReadProgress(enabled)
        if (!enabled) {
            _readSyncVerifyBanner.value = false
            readSyncVerificationProbePath = null
            readReportJobs.values.forEach { it.cancel() }
            readReportJobs.clear()
            readSendJobs.values.forEach { it.cancel() }
            readSendJobs.clear()
            readTrackers.clear()
        } else {
            activeTopicIds.toList().forEach(::beginReadTracking)
        }
        diagnostics.log(if (enabled) "已开启阅读进度同步(低频 timings 上报)" else "已关闭阅读进度同步(仅本地标记已读)")
    }

    /** 记录用户在屏幕上实际浏览到的楼层号 */
    fun onPostsVisible(topicId: Long, postNumbers: List<Int>) {
        if (topicId <= 0L) return
        val valid = postNumbers.filter { it > 0 }.distinct()
        currentVisiblePostsByTopic[topicId] = valid
        valid.maxOrNull()?.let { highestVisiblePostByTopic[topicId] = maxOf(highestVisiblePostByTopic[topicId] ?: 0, it) }
        readTrackers[topicId]?.setVisible(valid, SystemClock.elapsedRealtime())
    }

    fun onTopicScrolled(topicId: Long) {
        if (isTopicActive(topicId)) readTrackers[topicId]?.scrolled(SystemClock.elapsedRealtime())
    }

    fun resolveResumePostNumber(topicId: Long, explicitPostNumber: Int? = null): Int? {
        explicitPostNumber?.takeIf { it > 0 }?.let { return it }
        val localPostNumber = container.reading.getLastSeenPost(topicId)
        val remotePostNumber = sequenceOf(
            _details.value[topicId]?.topic?.lastReadPostNumber,
            _state.value.latest.items.firstOrNull { it.topic.id == topicId }?.topic?.lastReadPostNumber,
            _feedStates.value.values.firstNotNullOfOrNull { list ->
                list.items.firstOrNull { it.topic.id == topicId }?.topic?.lastReadPostNumber
            },
            _categoryTopics.value.values.firstNotNullOfOrNull { list ->
                list.items.firstOrNull { it.topic.id == topicId }?.topic?.lastReadPostNumber
            },
            _userTopicStates.value.values.firstNotNullOfOrNull { list ->
                list.items.firstOrNull { it.topic.id == topicId }?.topic?.lastReadPostNumber
            },
            _search.value.results.items.firstOrNull { it.topic.id == topicId }?.topic?.lastReadPostNumber,
        ).filterNotNull().maxOrNull()
        return listOfNotNull(localPostNumber, remotePostNumber).maxOrNull()?.takeIf { it > 1 }
    }

    private fun knownPostsCount(topicId: Long): Int {
        _details.value[topicId]?.let { detail ->
            val fromDetail = maxOf(
                detail.topic?.postsCount ?: 0,
                detail.topic?.postStream?.stream?.size ?: 0,
                detail.posts.maxOfOrNull { it.postNumber } ?: 0,
            )
            if (fromDetail > 0) return fromDetail
        }
        _state.value.latest.items.firstOrNull { it.topic.id == topicId }?.topic?.postsCount?.takeIf { it > 0 }?.let { return it }
        _feedStates.value.values.firstNotNullOfOrNull { list ->
            list.items.firstOrNull { it.topic.id == topicId }?.topic?.postsCount?.takeIf { it > 0 }
        }?.let { return it }
        _categoryTopics.value.values.firstNotNullOfOrNull { list ->
            list.items.firstOrNull { it.topic.id == topicId }?.topic?.postsCount?.takeIf { it > 0 }
        }?.let { return it }
        _search.value.results.items.firstOrNull { it.topic.id == topicId }?.topic?.postsCount?.takeIf { it > 0 }?.let { return it }
        return 0
    }

    /**
     * 仅关闭同步时本地记下"这帖看过了"，同步模式由成功回执推进已读状态。
     * 连同当前总楼层数保存，就地把普通列表里的未读蓝点摘掉,
     * 并将该帖从首页「未读」列表中就地移除。
     *
     * 纯净模式下服务端压根不知道我们读过,所以不能等 unseen 从服务端变化 ——
     * 只有本地这份持久化集合能保证下拉刷新、切分类、重启后蓝点与已读帖都不再反弹。
     */
    private fun markTopicSeen(topicId: Long, postsCount: Int = knownPostsCount(topicId)) {
        if (_syncReadProgress.value) return
        val changed = container.reading.markSeen(topicId, postsCount)
        fun clearUnseen(list: TopicListUiState): TopicListUiState {
            val patched = list.items.map { item ->
                if (item.topic.id == topicId && item.topic.unseen) {
                    item.copy(topic = item.topic.copy(unseen = false))
                } else item
            }
            return if (patched != list.items) list.copy(items = patched) else list
        }
        fun removeReadFromUnread(list: TopicListUiState): TopicListUiState {
            val filtered = list.items.filterNot { it.topic.id == topicId }
            return if (filtered.size != list.items.size) list.copy(items = filtered) else list
        }
        _feedStates.update { states ->
            states.mapValues { (feed, list) ->
                if (feed == HomeFeedType.Unread) removeReadFromUnread(list) else clearUnseen(list)
            }
        }
        _state.update { state ->
            val updatedLatest = if (state.homeFeed == HomeFeedType.Unread) {
                removeReadFromUnread(state.latest)
            } else {
                clearUnseen(state.latest)
            }
            if (updatedLatest != state.latest) state.copy(latest = updatedLatest) else state
        }
        if (!changed) return
        _categoryTopics.update { states -> states.mapValues { (_, list) -> clearUnseen(list) } }
        _search.update { it.copy(results = clearUnseen(it.results)) }
    }

    private fun alreadyReadPostsForTracker(topicId: Long): Set<Int> {
        val detail = _details.value[topicId] ?: return container.reading.confirmedPostNumbers(topicId)
        val fromPosts = detail.posts.asSequence()
            .filter { it.read || it.yours }
            .map { it.postNumber }
            .toSet()
        return detail.confirmedReadPostNumbers + fromPosts
    }

    /**
     * 开始为这次阅读计时。
     * 新未读楼层有效停留后提前合批，相邻成功批次至少间隔 3 秒；
     * 已读楼层保持 35 秒心跳，上报成功才消点。
     */
    private fun beginReadTracking(topicId: Long) {
        if (!_syncReadProgress.value || !isTopicActive(topicId) || readReportJobs[topicId]?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        val accountId = store.currentUserId()
        val accountGeneration = accountScopeGeneration
        val sessionGeneration = store.sessionGeneration
        val tracker = TopicReadTracker().apply {
            setVisible(currentVisiblePostsByTopic[topicId].orEmpty(), now)
            setActive(true, now)
        }
        readTrackers[topicId] = tracker
        readReportJobs[topicId] = viewModelScope.launch {
            val schedule = ReadReportSchedule(now, Config.READ_REPORT_HEARTBEAT_MS)
            while (isActive && isTopicActive(topicId) && readTrackers[topicId] === tracker) {
                delay(tracker.nextCheckDelayMs(alreadyReadPostsForTracker(topicId)))
                if (!isTopicActive(topicId) || readTrackers[topicId] !== tracker) break
                val tickAt = SystemClock.elapsedRealtime()
                if (repository.readTimingsCooldownRemainingMs() > 0L || tracker.isIdle(tickAt)) {
                    tracker.discardPending(tickAt)
                    continue
                }
                tracker.tick(tickAt)
                if (readSendJobs[topicId]?.isActive == true) continue
                val rush = tracker.hasRush(alreadyReadPostsForTracker(topicId))
                if (!schedule.canSend(tickAt, rush)) continue
                val batch = tracker.takeBatch(tickAt, READ_REPORT_POST_CAP) ?: continue
                readSendJobs[topicId] = viewModelScope.launch {
                    var success = false
                    var cancelled = false
                    try {
                        success = sendTimings(topicId, batch, tracker)
                        if (success && accountGeneration == accountScopeGeneration && sessionGeneration == store.sessionGeneration &&
                            accountId == store.currentUserId()) {
                            confirmTopicRead(accountId, topicId, batch.postTimesMs.keys)
                        }
                    } catch (error: CancellationException) {
                        cancelled = true
                        throw error
                    } catch (error: Exception) {
                        diagnostics.log("阅读进度暂未同步: ${error.javaClass.simpleName}")
                    } finally {
                        if (readTrackers[topicId] === tracker && isTopicActive(topicId)) {
                            val completedAt = SystemClock.elapsedRealtime()
                            val cooldown = repository.readTimingsCooldownRemainingMs()
                            schedule.completed(completedAt, success, cooldown)
                            if (!success) {
                                if (cooldown > 0L || cancelled) tracker.discardPending(completedAt) else tracker.restoreBatch(batch)
                            }
                            diagnostics.log("阅读上报${if (success) "确认" else "未确认"}: 话题 $topicId, ${batch.postTimesMs.size} 层, " +
                                "${if (rush) "新未读合批" else "心跳"}, 请求等待 ${completedAt - tickAt}ms")
                        }
                        if (readSendJobs[topicId] === coroutineContext[Job]) readSendJobs.remove(topicId)
                    }
                }
            }
        }
    }

    private fun confirmTopicRead(accountId: Long, topicId: Long, postNumbers: Set<Int>) {
        val confirmed = container.reading.confirmPosts(accountId, topicId, postNumbers)
        if (confirmed.isEmpty()) return
        _details.update { states ->
            states[topicId]?.let { states + (topicId to mergeConfirmedDetailRead(it, confirmed)) } ?: states
        }
        updateCachedTopicDetail(topicId, refreshTtl = false)
        fun updateItems(items: List<TopicListItem>) = items.map { item ->
            if (item.topic.id == topicId) item.copy(topic = container.reading.mergeReadState(item.topic)) else item
        }
        mapTopicLists { list -> list.copy(items = updateItems(list.items),
            pendingPage = list.pendingPage?.let { it.copy(items = updateItems(it.items)) }) }
        fun keepUnread(items: List<TopicListItem>) = items.filterNot { item ->
            item.topic.id == topicId && container.reading.isUnreadExcluded(topicId, item.topic.postsCount, item.topic.highestPostNumber)
        }
        fun updateUnread(list: TopicListUiState) = list.copy(items = keepUnread(list.items),
            pendingPage = list.pendingPage?.let { it.copy(items = keepUnread(it.items)) })
        _feedStates.update { feeds ->
            feeds[HomeFeedType.Unread]?.let { feeds + (HomeFeedType.Unread to updateUnread(it)) } ?: feeds
        }
        _state.update { if (it.homeFeed == HomeFeedType.Unread) it.copy(latest = updateUnread(it.latest)) else it }
    }

    /** 离帖只保存本地位置；未发送的时间随失活丢弃，不在后台补报。 */
    fun leaveTopic(topicId: Long) {
        setTopicActive(topicId, false)
        val lastSeenPost = highestVisiblePostByTopic.remove(topicId) ?: 0
        val currentDetail = _details.value[topicId]
        currentDetail?.topic?.let { topic ->
            val cat = currentDetail.category ?: categories.value.items.firstOrNull { it.id == topic.categoryId }
            val firstPost = currentDetail.posts.firstOrNull { it.postNumber == 1 }
                ?: topic.postStream.posts.firstOrNull { it.postNumber == 1 }
            recordHistory(
                topicId = topicId,
                title = topic.title,
                postNumber = lastSeenPost,
                authorUsername = firstPost?.username?.takeIf { it.isNotBlank() },
                avatarTemplate = firstPost?.avatarTemplate,
                categoryName = cat?.name,
                categoryColor = cat?.color,
                totalReplies = (topic.postsCount - 1).coerceAtLeast(0),
            )
        }
        currentVisiblePostsByTopic.remove(topicId)
    }

    private suspend fun sendTimings(
        topicId: Long,
        batch: ReadTimingBatch,
        tracker: TopicReadTracker,
    ): Boolean = repository.recordTopicTimings(topicId, batch.topicTimeMs, batch.postTimesMs) {
        _syncReadProgress.value && isTopicActive(topicId) && readTrackers[topicId] === tracker
    }

    private fun recordHistory(
        topicId: Long,
        title: String,
        postNumber: Int,
        authorUsername: String? = null,
        avatarTemplate: String? = null,
        categoryName: String? = null,
        categoryColor: String? = null,
        totalReplies: Int = 0,
    ) {
        container.historyStore.record(
            topicId = topicId,
            title = title,
            postNumber = postNumber,
            authorUsername = authorUsername,
            avatarTemplate = avatarTemplate,
            categoryName = categoryName,
            categoryColor = categoryColor,
            totalReplies = totalReplies,
        )
        _history.update { it.copy(entries = container.historyStore.entries()) }
    }

    fun setHistoryQuery(query: String) {
        _history.update { it.copy(query = query) }
    }

    fun clearHistory() {
        container.historyStore.clear()
        _history.update { it.copy(entries = emptyList()) }
    }

    fun openUserCard(username: String) {
        openUserCard(username, null, null, null)
    }

    fun openUserCard(
        username: String,
        primaryAvatarUrl: String? = null,
        staticAvatarUrl: String? = null,
        fallbackDisplayName: String? = null,
    ) {
        val target = username.trim().removePrefix("@").takeIf { it.isNotBlank() } ?: return
        pageJobs["userCard"]?.cancel()

        val suppliedAnimatedAvatar = primaryAvatarUrl
            ?.takeIf { it.isNotBlank() && it != staticAvatarUrl }
        if (suppliedAnimatedAvatar != null) {
            AvatarCache.record(target, suppliedAnimatedAvatar)
        }
        val knownAvatar = suppliedAnimatedAvatar ?: AvatarCache.get(target)

        val currentAccount = _state.value.username
        val isSelf = currentAccount != null && target.equals(currentAccount, ignoreCase = true)
        if (followedUsernames.value.isEmpty() && currentAccount != null) {
            repository.getCachedFollowing(currentAccount)?.let { cachedList ->
                followedUsernames.value = cachedList.map { it.username.lowercase() }.toSet()
            }
        }
        val isFollowed = target.lowercase() in followedUsernames.value
        val isBlocked = target.lowercase() in blockedUsernames.value

        val cached = repository.getCachedUserCard(target)
        if (cached != null) {
            val effectiveCached = if (cached.animatedAvatar.isNullOrBlank() && !knownAvatar.isNullOrBlank()) {
                cached.copy(animatedAvatar = knownAvatar)
            } else cached
            _userCard.value = UserCardUiState(
                user = effectiveCached,
                following = isFollowed || effectiveCached.following,
                isBlocked = isBlocked,
                isSelf = isSelf,
                loading = false,
            )
        } else {
            val placeholder = UserCardData(
                username = target,
                name = fallbackDisplayName,
                avatarTemplate = staticAvatarUrl ?: primaryAvatarUrl,
                animatedAvatar = knownAvatar,
                canFollow = !isSelf,
                following = isFollowed,
            )
            _userCard.value = UserCardUiState(
                user = placeholder,
                following = isFollowed,
                isBlocked = isBlocked,
                isSelf = isSelf,
                loading = true,
            )
        }

        pageJobs["userCard"] = viewModelScope.launch {
            try {
                if (followedUsernames.value.isEmpty() && currentAccount != null) {
                    launch {
                        val fresh = runCatching { repository.fetchFollowing(currentAccount) }.getOrNull()
                        if (fresh != null) {
                            followedUsernames.value = fresh.map { it.username.lowercase() }.toSet()
                            if (target.lowercase() in followedUsernames.value) {
                                _userCard.update { it.copy(following = true) }
                            }
                        }
                    }
                }
                val user = repository.fetchUserCard(target, forceRefresh = cached != null)
                val effectiveUser = user.copy(
                    avatarTemplate = user.avatarTemplate ?: staticAvatarUrl ?: primaryAvatarUrl,
                    animatedAvatar = user.animatedAvatar ?: knownAvatar,
                )
                val curFollowed = isFollowed || target.lowercase() in followedUsernames.value || effectiveUser.following
                val curBlocked = isBlocked || target.lowercase() in blockedUsernames.value
                val hasMetrics = effectiveUser.topicCount > 0 || effectiveUser.postCount > 0 || effectiveUser.likesReceived > 0
                _userCard.value = UserCardUiState(
                    user = effectiveUser,
                    following = curFollowed,
                    isBlocked = curBlocked,
                    isSelf = isSelf,
                    loading = !hasMetrics,
                )

                // 快速并发拉取真实统计数据 (话题、帖子、获赞、徽章、动图头像)
                val stats = runCatching { repository.fetchUserCardStats(target) }.getOrNull()
                if (stats != null) {
                    val cur = _userCard.value.user
                    if (cur != null && cur.username.equals(target, ignoreCase = true)) {
                        val finalFollowed = curFollowed || (stats.isFollowed == true)
                        if (stats.isFollowed == true) {
                            followedUsernames.value += target.lowercase()
                        }
                        val updated = cur.copy(
                            topicCount = stats.topicCount,
                            postCount = stats.postCount,
                            likesReceived = stats.likesReceived,
                            badges = if (stats.badges.isNotEmpty()) stats.badges else cur.badges,
                            animatedAvatar = stats.animatedAvatar ?: cur.animatedAvatar,
                            following = finalFollowed,
                        )
                        _userCard.value = _userCard.value.copy(
                            user = updated,
                            following = finalFollowed,
                            loading = false,
                        )
                        repository.saveCachedUserCard(updated)
                    }
                } else {
                    _userCard.update { it.copy(loading = false) }
                }

                if (effectiveUser.animatedAvatar.isNullOrBlank()) {
                    val enriched = runCatching { repository.enrichUserCardAvatar(target, effectiveUser) }.getOrNull()
                    if (!enriched?.animatedAvatar.isNullOrBlank() &&
                        _userCard.value.user?.username?.equals(target, ignoreCase = true) == true
                    ) {
                        val statsUser = _userCard.value.user ?: enriched
                        val updatedWithAnim = statsUser.copy(animatedAvatar = enriched.animatedAvatar)
                        _userCard.value = _userCard.value.copy(
                            user = updatedWithAnim,
                            loading = false,
                        )
                        repository.saveCachedUserCard(updatedWithAnim)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_userCard.value.user == null) {
                    _userCard.value = UserCardUiState(error = error.message ?: "资料加载失败", loading = false)
                } else {
                    _userCard.value = _userCard.value.copy(loading = false)
                }
            }
        }
    }

    fun closeUserCard() {
        pageJobs.remove("userCard")?.cancel()
        _userCard.value = UserCardUiState()
    }

    fun openChatWithUser(username: String, onNavigate: (org.linuxdo.android.ui.nav.Route) -> Unit) {
        closeUserCard()
        viewModelScope.launch {
            try {
                val channels = if (_chat.value.channels.isEmpty()) {
                    runCatching { repository.fetchChatChannels() }.getOrNull()?.also {
                        _chat.value = _chat.value.copy(channels = it)
                    } ?: _chat.value.channels
                } else {
                    _chat.value.channels
                }
                val existing = channels.firstOrNull { ch ->
                    ch.isOneOnOneDm && ch.dmUsers.any { it.username.equals(username, ignoreCase = true) }
                }
                if (existing != null) {
                    onNavigate(org.linuxdo.android.ui.nav.Route.ChatRoom(existing.id, existing.displayTitle))
                    return@launch
                }
                val created = repository.createOrGetDirectMessageChannel(username)
                loadChatChannels()
                onNavigate(org.linuxdo.android.ui.nav.Route.ChatRoom(created.id, created.displayTitle.ifBlank { username }))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(getApplication(), "发起私聊失败: ${e.message ?: "网络异常"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun toggleFollowUser() {
        val current = _userCard.value
        val user = current.user ?: return
        if (current.followUpdating || (!user.canFollow && !current.following) || user.id == store.currentUserId()) return
        val previous = current.following
        val nextFollow = !previous
        _userCard.update { it.copy(following = nextFollow, followUpdating = true, error = null) }
        val targetLower = user.username.lowercase()
        if (nextFollow) {
            followedUsernames.update { it + targetLower }
        } else {
            followedUsernames.update { it - targetLower }
        }
        pageJobs["follow:${user.username}"] = viewModelScope.launch {
            try {
                repository.setFollow(user.username, nextFollow)
                _userCard.update { it.copy(followUpdating = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (nextFollow) {
                    followedUsernames.update { it - targetLower }
                } else {
                    followedUsernames.update { it + targetLower }
                }
                _userCard.update { it.copy(following = previous, followUpdating = false, error = error.message ?: "关注操作失败") }
            }
        }
    }

    fun unfollowUser(username: String) {
        val target = username.lowercase().trim().removePrefix("@")
        followedUsernames.update { it - target }
        _followList.update { cur ->
            cur.copy(users = cur.users.filterNot { it.username.equals(target, ignoreCase = true) })
        }
        if (_userCard.value.user?.username?.equals(target, ignoreCase = true) == true) {
            _userCard.update { it.copy(following = false) }
        }
        viewModelScope.launch {
            runCatching { repository.setFollow(username, false) }
        }
    }

    fun unfollowBatch(usernames: List<String>) {
        val targetSet = usernames.map { it.lowercase().trim().removePrefix("@") }.toSet()
        followedUsernames.update { it - targetSet }
        _followList.update { cur ->
            cur.copy(users = cur.users.filterNot { it.username.lowercase() in targetSet })
        }
        val curCard = _userCard.value.user?.username?.lowercase()
        if (curCard != null && curCard in targetSet) {
            _userCard.update { it.copy(following = false) }
        }
        viewModelScope.launch {
            usernames.forEach { u ->
                runCatching { repository.setFollow(u, false) }
            }
        }
    }

    fun addToBlacklist(
        username: String,
        name: String? = null,
        avatarTemplate: String? = null,
        trustLevel: Int? = null,
    ) {
        val target = username.trim().removePrefix("@")
        if (target.isBlank()) return
        val lower = target.lowercase()
        if (lower in blockedUsernames.value) return
        val newEntry = BlockedUser(
            username = target,
            name = name,
            avatarTemplate = avatarTemplate,
            trustLevel = trustLevel,
        )
        val nextList = _blockedUsers.value.filterNot { it.username.equals(target, ignoreCase = true) } + newEntry
        _blockedUsers.value = nextList
        blockedUsernames.value = nextList.map { it.username.lowercase() }.toSet()
        repository.saveBlockedUsers(nextList)
        if (_userCard.value.user?.username?.equals(target, ignoreCase = true) == true) {
            _userCard.update { it.copy(isBlocked = true) }
        }
    }

    fun removeFromBlacklist(username: String) {
        val target = username.trim().removePrefix("@")
        val nextList = _blockedUsers.value.filterNot { it.username.equals(target, ignoreCase = true) }
        _blockedUsers.value = nextList
        blockedUsernames.value = nextList.map { it.username.lowercase() }.toSet()
        repository.saveBlockedUsers(nextList)
        if (_userCard.value.user?.username?.equals(target, ignoreCase = true) == true) {
            _userCard.update { it.copy(isBlocked = false) }
        }
    }

    fun removeBatchFromBlacklist(usernames: List<String>) {
        val targetSet = usernames.map { it.lowercase().trim().removePrefix("@") }.toSet()
        val nextList = _blockedUsers.value.filterNot { it.username.lowercase() in targetSet }
        _blockedUsers.value = nextList
        blockedUsernames.value = nextList.map { it.username.lowercase() }.toSet()
        repository.saveBlockedUsers(nextList)
        val curCard = _userCard.value.user?.username?.lowercase()
        if (curCard != null && curCard in targetSet) {
            _userCard.update { it.copy(isBlocked = false) }
        }
    }

    fun toggleBlacklistForUserCard() {
        val current = _userCard.value
        val user = current.user ?: return
        val target = user.username
        if (current.following) {
            return
        }
        if (current.isBlocked) {
            removeFromBlacklist(target)
        } else {
            addToBlacklist(
                username = target,
                name = user.cleanDisplayName,
                avatarTemplate = user.avatarTemplate,
                trustLevel = user.trustLevel,
            )
        }
    }

    fun loadFollowing(username: String, refresh: Boolean = false) {
        if (_followList.value.loading) return
        val cached = repository.getCachedFollowing(username)
        if (cached != null && _followList.value.users.isEmpty()) {
            _followList.value = FollowUiState(username = username, users = cached)
            followedUsernames.value = cached.map { it.username.lowercase() }.toSet()
        }
        if (!refresh && _followList.value.username == username && _followList.value.users.isNotEmpty()) return
        _followList.update { it.copy(username = username, loading = true, error = null) }
        pageJobs["following"] = viewModelScope.launch {
            try {
                val users = repository.fetchFollowing(username)
                _followList.value = FollowUiState(username = username, users = users)
                followedUsernames.value = users.map { it.username.lowercase() }.toSet()
                repository.saveCachedFollowing(username, users)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _followList.update { it.copy(loading = false, error = error.message ?: "关注列表加载失败") }
            }
        }
    }

    fun loadChatChannels(refresh: Boolean = false) {
        if (_chat.value.loadingChannels) return
        if (!refresh && _chat.value.channels.isNotEmpty()) return
        _chat.update { it.copy(loadingChannels = true, error = null) }
        pageJobs["chatChannels"] = viewModelScope.launch {
            try {
                val channels = repository.fetchChatChannels()
                _chat.update { it.copy(channels = channels, loadingChannels = false, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _chat.update { it.copy(loadingChannels = false, error = error.message ?: "Chat 频道加载失败") }
            }
        }
    }

    fun loadChatRoom(channelId: Long, refresh: Boolean = false) {
        val jobKey = "chatRoom:$channelId"
        if (pageJobs[jobKey]?.isActive == true) return
        pageJobs[jobKey] = viewModelScope.launch {
            if (!refresh) _chat.update { it.copy(loadingChannelId = channelId, error = null) }
            try {
                val page = repository.fetchChatMessages(channelId, fromLastRead = !refresh)
                val messages = page.messages
                val oldMaxId = _chat.value.messagesByChannel[channelId].orEmpty().maxOfOrNull { it.id } ?: 0L
                val newMaxId = messages.maxOfOrNull { it.id } ?: 0L
                if (newMaxId > oldMaxId) repository.markChatChannelRead(channelId, newMaxId)
                _chat.update { current ->
                    val merged = if (refresh) {
                        (current.messagesByChannel[channelId].orEmpty() + messages)
                            .distinctBy { it.id }
                            .sortedBy { it.id }
                    } else messages.sortedBy { it.id }
                    current.copy(
                        loadingChannelId = null,
                        error = null,
                        messagesByChannel = current.messagesByChannel + (channelId to merged),
                        canLoadMorePastByChannel = current.canLoadMorePastByChannel + (channelId to page.canLoadMorePast),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _chat.update { it.copy(loadingChannelId = null, error = error.message ?: "聊天消息加载失败") }
            }
        }
    }

    fun loadMoreChatHistory(channelId: Long) {
        val jobKey = "chatHistory:$channelId"
        if (pageJobs[jobKey]?.isActive == true) return
        val current = _chat.value
        if (current.loadingPastChannelId == channelId || current.canLoadMorePastByChannel[channelId] == false) return
        val existing = current.messagesByChannel[channelId].orEmpty()
        val anchor = existing.firstOrNull() ?: return
        pageJobs[jobKey] = viewModelScope.launch {
            _chat.update { it.copy(loadingPastChannelId = channelId) }
            try {
                val page = repository.fetchChatMessages(
                    channelId = channelId,
                    direction = "past",
                    targetMessageId = anchor.id,
                )
                _chat.update { fresh ->
                    val currentMsgs = fresh.messagesByChannel[channelId].orEmpty()
                    val existingIds = currentMsgs.map { it.id }.toSet()
                    val older = page.messages.filter { it.id !in existingIds }.sortedBy { it.id }
                    val merged = older + currentMsgs
                    fresh.copy(
                        loadingPastChannelId = null,
                        messagesByChannel = fresh.messagesByChannel + (channelId to merged),
                        canLoadMorePastByChannel = fresh.canLoadMorePastByChannel + (channelId to page.canLoadMorePast),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _chat.update { it.copy(loadingPastChannelId = null) }
            }
        }
    }

    fun sendChatMessage(channelId: Long, raw: String, uploadIds: List<Long> = emptyList()) {
        val message = raw.trim()
        if (message.isBlank() || _chat.value.sendingChannelId != null) return
        pageJobs["chatSend"] = viewModelScope.launch {
            _chat.update { it.copy(sendingChannelId = channelId, error = null) }
            try {
                repository.sendChatMessage(channelId, message, uploadIds)
                loadChatRoom(channelId, refresh = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _chat.update { it.copy(error = error.message ?: "消息发送失败") }
            } finally {
                _chat.update { it.copy(sendingChannelId = null) }
            }
        }
    }

    fun uploadChatAttachment(channelId: Long, uri: Uri, onUploaded: (AttachedMedia) -> Unit, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { readPickedFile(uri) }
                val uploaded = repository.uploadFile(picked.name, picked.mimeType, picked.bytes)
                val isImg = uploaded.width > 0 || UploadLimits.isCompressibleImage(picked.name) ||
                    picked.mimeType.startsWith("image/")
                val media = AttachedMedia(
                    name = uploaded.fileName,
                    previewUrl = uri.toString(),
                    uploaded = uploaded,
                    isImage = isImg,
                )
                onUploaded(media)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val msg = error.message ?: "附件上传失败"
                _chat.update { it.copy(error = msg) }
                onError(msg)
                handlePageFailure(error) {}
            }
        }
    }

    suspend fun uploadAttachmentDirect(uri: Uri): AttachedMedia = withContext(Dispatchers.IO) {
        val picked = readPickedFile(uri)
        val uploaded = repository.uploadFile(picked.name, picked.mimeType, picked.bytes)
        val isImg = uploaded.width > 0 || UploadLimits.isCompressibleImage(picked.name) || picked.mimeType.startsWith("image/")
        AttachedMedia(
            name = uploaded.fileName,
            previewUrl = uri.toString(),
            uploaded = uploaded,
            isImage = isImg,
        )
    }

    suspend fun createPrivateMessage(targetUsernames: List<String>, title: String, raw: String): Long =
        repository.createPrivateMessage(targetUsernames, title, raw)

    suspend fun loadPostMarkdown(topicId: Long, postNumber: Int): String {
        val generation = store.sessionGeneration
        val raw = repository.fetchPostMarkdown(topicId, postNumber)
        if (generation != store.sessionGeneration) throw CancellationException("会话已切换")
        return raw
    }

    suspend fun loadPostRevision(postId: Long, revision: Int?): org.linuxdo.android.data.PostRevision {
        val generation = store.sessionGeneration
        val result = repository.fetchPostRevision(postId, revision)
        if (generation != store.sessionGeneration) throw CancellationException("会话已切换")
        return result
    }

    suspend fun loadTopicPreview(topicId: Long): org.linuxdo.android.data.TopicDetail {
        val generation = store.sessionGeneration
        val detail = repository.fetchTopic(topicId, trackVisit = false, present = false)
        if (generation != store.sessionGeneration) throw CancellationException("会话已切换")
        return detail
    }

    suspend fun searchEditorSuggestions(query: EditorCompletionRequest): List<EditorSuggestion> {
        if (!store.hasSession()) return emptyList()
        val generation = store.sessionGeneration
        val local = if (query.marker == '@') _details.value[query.topicId]?.posts.orEmpty()
            .filter { it.username.matches(Regex("[A-Za-z0-9_.-]+")) }
            .filter { it.username.contains(query.term, ignoreCase = true) || it.displayName.contains(query.term, ignoreCase = true) }
            .distinctBy { it.username.lowercase() }.take(8)
            .map { EditorSuggestion("@${it.username}", it.displayName, "本帖参与者 · @${it.username}", avatarUrlOf(it.avatarTemplate)) }
            else emptyList()
        val remote = try {
            repository.searchEditorSuggestions(query)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (local.isEmpty()) throw error else emptyList()
        }
        if (generation != store.sessionGeneration) throw CancellationException("会话已切换")
        return (local + remote).distinctBy { it.insertion.lowercase() }.take(8)
    }

    fun loadTrustReport(refresh: Boolean = false) {
        val username = _state.value.username ?: return
        val checkSession = connectSessionCheck(username)
        val current = _trustReport.value
        val now = System.currentTimeMillis()

        // 1. 若内存中尚无数据，优先从磁盘持久化缓存读取（0ms 秒开直出）
        var cached = current.report?.let { it to current.cachedAt }
        if (cached == null) {
            val fromDisk = repository.getCachedTrustReport(username)
            if (fromDisk != null) {
                _trustReport.update { it.copy(report = fromDisk.first, cachedAt = fromDisk.second) }
                cached = fromDisk
            }
        }

        // 本地还没有任何升级进度缓存：启动 10 分钟内不允许前台拉取，等后台首轮预热
        val firstFetchLockMs = connectFirstFetchLockRemainingMs(hasCache = cached != null)
        if (firstFetchLockMs > 0L) {
            if (!current.loading) {
                _trustReport.update { it.copy(error = connectFirstFetchLockMessage(firstFetchLockMs)) }
            }
            return
        }

        // 2. 如果不是手动刷新（首次进入或后台触发）
        if (!refresh) {
            if (current.loading) return
            if (now < connectSilentBackoffUntil) return
            if (cached != null) {
                val age = now - cached.second
                // 若仍在 6 小时缓存有效期内，完全不发网络请求
                if (age < Config.TRUST_REPORT_CACHE_TTL_MS) return
                // 若处于风控熔断退避期，静默跳过
                if (now < connectSilentBackoffUntil) return
            }
        } else {
            // 3. 手动刷新：严格检查 30 分钟内刷新频率（最多 5 次）
            if (current.loading) return
            if (!checkConnectManualRefreshRateLimit()) return
        }

        val isSilent = !refresh && cached != null
        viewModelScope.launch {
            checkSession()
            if (!isSilent) {
                _trustReport.update { it.copy(loading = true, error = null) }
            }
            try {
                val report = repository.readAfterShortCooldown { checkSession(); repository.fetchTrustReport(present = false) }
                checkSession()
                if (report != null) {
                    val updateTime = System.currentTimeMillis()
                    repository.saveCachedTrustReport(username, report)
                    _trustReport.update { it.copy(loading = false, report = report, error = null, cachedAt = updateTime) }
                } else if (!isSilent) {
                    _trustReport.update { it.copy(loading = false, error = "无法解析升级进度数据") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkSession()
                connectSilentBackoffUntil = System.currentTimeMillis() + siteFailureDelayMs(e)
                if (!isSilent) {
                    _trustReport.update { it.copy(loading = false, error = e.message ?: "加载升级进度失败") }
                }
            } finally {
                if (!isSilent && runCatching { checkSession() }.isSuccess) {
                    _trustReport.update { it.copy(loading = false) }
                }
            }
        }
    }

    /**
     * 加载社区分(CDK score)，展示在「我的」头部。
     *
     * 只读缓存不发请求；缓存过期(6 小时)才静默联网，且受三道保护：
     * 1. 本地没有缓存时，启动 10 分钟内交给后台预热([fromWarmup])错峰取，前台不抢；
     * 2. 短冷却到期后仅补取一次，真正授权/挑战失败才长期退避；
     * 3. 仅 CDK 返回 401(无会话)才做一次 OAuth 握手，握手结果是 Cookie，平时不重复握手。
     */
    fun loadCdkScore(fromWarmup: Boolean = false) {
        val username = _state.value.username ?: return
        val checkSession = connectSessionCheck(username)
        val now = System.currentTimeMillis()
        var current = _cdkScore.value
        if (!current.username.equals(username, ignoreCase = true)) {
            current = CdkScoreUiState(username = username)
            _cdkScore.value = current
        }
        if (current.score == null) {
            repository.getCachedCdkScore(username)?.let { (score, time) ->
                current = CdkScoreUiState(username = username, score = score, cachedAt = time)
                _cdkScore.value = current
            }
        }
        if (current.score != null && now - current.cachedAt < Config.CDK_SCORE_CACHE_TTL_MS) return
        if (now < cdkScoreBackoffUntil) return
        if (current.score == null && !fromWarmup && connectFirstFetchLockRemainingMs(hasCache = false) > 0L) return
        if (cdkScoreJob?.isActive == true) return
        cdkScoreJob = viewModelScope.launch {
            try {
                val score = readSiteWithAuthorization(checkSession, { repository.fetchCdkScore() }, { repository.authorizeCdk() })
                checkSession()
                cdkScoreBackoffUntil = 0L
                repository.saveCachedCdkScore(username, score)
                _cdkScore.value = CdkScoreUiState(username, score, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkSession()
                val pause = siteFailureDelayMs(e)
                cdkScoreBackoffUntil = System.currentTimeMillis() + pause
                diagnostics.log("获取 CDK 社区分暂缓(${(pause + 999L) / 1_000L}s): ${e.message}")
            }
        }
    }

    fun loadLdcUserInfo(refresh: Boolean = false) {
        val username = _state.value.username ?: return
        val checkSession = connectSessionCheck(username)
        val current = _ldcState.value
        val now = System.currentTimeMillis()

        // 1. 若内存中尚无数据，优先从磁盘持久化缓存读取（0ms 秒开直出）
        var cached = current.userInfo?.let { it to current.cachedAt }
        if (cached == null) {
            val fromDisk = repository.getCachedLdcUserInfo(username)
            if (fromDisk != null) {
                _ldcState.update { it.copy(userInfo = fromDisk.first, cachedAt = fromDisk.second) }
                cached = fromDisk
            }
        }

        // 本地还没有任何积分缓存：启动 10 分钟内不允许前台拉取，等后台首轮预热
        val firstFetchLockMs = connectFirstFetchLockRemainingMs(hasCache = cached != null)
        if (firstFetchLockMs > 0L) {
            if (!current.loading) {
                _ldcState.update { it.copy(error = connectFirstFetchLockMessage(firstFetchLockMs), isExpired = false) }
            }
            return
        }

        // 2. 如果不是手动刷新
        if (!refresh) {
            if (current.loading) return
            if (now < ldcBackoffUntil) return
            if (cached != null) {
                val age = now - cached.second
                // 若仍在 4 小时缓存有效期内（3-6小时区间），完全不发网络请求
                if (age < Config.LDC_USER_INFO_CACHE_TTL_MS) return
                // 若处于风控熔断退避期，静默跳过
                if (now < ldcBackoffUntil) return
            }
        } else {
            // 3. 手动刷新：严格检查 30 分钟内刷新频率（最多 5 次）
            if (current.loading) return
            if (!checkConnectManualRefreshRateLimit()) return
        }

        val isSilent = !refresh && cached != null
        _ldcState.update { it.copy(loading = true, error = null, isExpired = false) }
        viewModelScope.launch {
            try {
                val info = fetchAuthorizedLdc(username, checkSession)
                checkSession()
                ldcBackoffUntil = 0L
                val updateTime = System.currentTimeMillis()
                repository.saveCachedLdcUserInfo(username, info)
                _ldcState.update { it.copy(loading = false, userInfo = info, error = null, isExpired = false, cachedAt = updateTime) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkSession()
                val isExpired = (e as? HttpStatusException)?.status == 401
                ldcBackoffUntil = System.currentTimeMillis() + siteFailureDelayMs(e)
                if (!isSilent) {
                    val msg = if (isExpired) "积分授权已过期或未登录" else (e.message ?: "加载积分数据失败")
                    _ldcState.update { it.copy(loading = false, error = msg, isExpired = isExpired) }
                }
            } finally {
                if (runCatching { checkSession() }.isSuccess) {
                    _ldcState.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun authorizeLdc() {
        if (_ldcState.value.loading) return
        diagnostics.log("用户手动刷新 LDC，先复核已有授权，仅在 401 时重新授权")
        loadLdcUserInfo(refresh = true)
    }

    fun loadProfile(section: ProfileSection, refresh: Boolean = false, more: Boolean = false) {
        val username = _state.value.username ?: return
        if (section == ProfileSection.Summary && !more) {
            loadDrafts(refresh = refresh, silent = true)
            prewarmCurrentUserAvatar(username)
            loadUserBadges(username, refresh = refresh)
        }
        val filter = _notificationFilter.value
        val previous = _profile.value[section] ?: ProfileUiState()
        if (previous.loading) return
        if (!refresh && !more && previous.loaded && isCacheUsable(section, filter, previous)) return
        if (more && !previous.content.hasMore) return
        val cursor = if (more) previous.content.nextCursor else 0
        pageJobs["profile:$section"] = viewModelScope.launch {
            _profile.update { it + (section to previous.copy(loading = true, error = null)) }
            try {
                val fresh = fetchProfilePage(username, section, filter, previous, more)
                _profile.update { it + (section to fresh) }
                if (section == ProfileSection.Notifications) notificationCache[filter] = fresh
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _profile.update { it + (section to previous.copy(error = error.message ?: "加载失败")) }
                handlePageFailure(error) { loadProfile(section, refresh = true) }
            } finally {
                _profile.update { states -> states[section]?.let { states + (section to it.copy(loading = false)) } ?: states }
            }
        }
    }

    private var loadUserBadgesJob: Job? = null

    fun loadUserBadges(username: String? = null, refresh: Boolean = false) {
        val targetUser = username ?: _state.value.username ?: return
        if (_userBadges.value == null) {
            val cached = repository.getCachedUserBadges(targetUser)
            if (cached != null) {
                _userBadges.value = cached
            }
        }
        if (!refresh && loadUserBadgesJob?.isActive == true) {
            return
        }
        if (!refresh && _userBadges.value != null) {
            loadUserBadgesJob = viewModelScope.launch {
                try {
                    val list = repository.fetchUserBadges(targetUser, forceRefresh = false)
                    _userBadges.value = list
                } catch (_: Exception) {}
            }
            return
        }
        loadUserBadgesJob = viewModelScope.launch {
            _isUserBadgesLoading.value = true
            try {
                val list = repository.fetchUserBadges(targetUser, forceRefresh = refresh)
                _userBadges.value = list
            } catch (e: Exception) {
                // 加载失败静默处理
            } finally {
                _isUserBadgesLoading.value = false
            }
        }
    }

    /** 拉一页并组装成新状态。前台加载和后台预取共用这一条路径,避免两套逻辑走偏。 */
    private suspend fun fetchProfilePage(
        username: String,
        section: ProfileSection,
        filter: NotificationFilter,
        previous: ProfileUiState,
        more: Boolean,
    ): ProfileUiState {
        val cursor = if (more) previous.content.nextCursor else 0
        val excludeIds = if (more && section == ProfileSection.Notifications) {
            previous.content.entries.map { it.id }.toSet()
        } else emptySet()
        val unreadNow = unreadBaseline(section, filter)
        val result = repository.fetchProfile(username, section, cursor, filter, excludeIds)
        val entries = if (more) (previous.content.entries + result.entries).distinctBy { it.id } else result.entries
        return ProfileUiState(
            // 防死循环的守卫看的是游标有没有往前走,而不是条目有没有变多 ——
            // 低频通知类型完全可能某一轮一条都没命中,但后面还有。
            content = result.copy(entries = entries,
                hasMore = result.hasMore && (!more || result.nextCursor > previous.content.nextCursor)),
            loaded = true,
            loadedAt = SystemClock.elapsedRealtime(),
            unreadAtLoad = unreadNow,
        )
    }

    /**
     * 这个分区该拿哪个未读数做基准。
     * 通知按分类分项取;总结/活动/账号这些和未读无关的分区返回 0,等于只受 TTL 约束 ——
     * 别因为来了条回复就把账号资料的缓存也作废。
     */
    private fun unreadBaseline(section: ProfileSection, filter: NotificationFilter): Int = when (section) {
        ProfileSection.Notifications -> _state.value.unreadByFilter[filter] ?: 0
        ProfileSection.Messages -> _state.value.unreadByFilter[NotificationFilter.Messages] ?: 0
        else -> 0
    }

    /**
     * 这份缓存还能不能直接拿来显示。
     *
     * 两条判据取"与":该分区对应的未读没涨 **且** 没到 TTL。
     * 未读必须按分类取 —— 铃铛的总数只算回复类和私信类,点赞压根不计入,
     * 拿总数判断会让点赞分类永远判定为没变化、一直吃旧缓存。
     * 未读变少是用户自己读掉的,列表已经就地更新过,所以用 `<=` 而不是 `==`。
     */
    private fun isCacheUsable(
        section: ProfileSection,
        filter: NotificationFilter,
        state: ProfileUiState,
    ): Boolean {
        if (!state.loaded) return false
        if (SystemClock.elapsedRealtime() - state.loadedAt >= Config.PROFILE_CACHE_TTL_MS) return false
        return unreadBaseline(section, filter) <= state.unreadAtLoad
    }

    /**
     * 切换通知分类。
     *
     * 四个分类共用 `_profile` 里同一个槽位(键是 ProfileSection),所以这里额外用
     * notificationCache 按分类留一份。命中且仍新鲜就直接贴回去 —— 点标签立刻有内容。
     * 若未命中，优先直接从全量通知 (All) 智能派生对应分类数据，0ms 瞬间上屏且不发多余网络请求。
     * 再次点击当前已选中的分类标签时强制刷新。
     */
    fun setNotificationFilter(filter: NotificationFilter) {
        if (_notificationFilter.value == filter) {
            loadProfile(ProfileSection.Notifications, refresh = true)
            return
        }
        pageJobs["profile:${ProfileSection.Notifications}"]?.cancel()
        _notificationFilter.value = filter

        val cached = notificationCache[filter]
        val allCached = notificationCache[NotificationFilter.All]

        // 1. 若该分类已有新鲜缓存，且“全部”未产生更新的数据，直接秒显
        val isCachedFresh = cached != null && isCacheUsable(ProfileSection.Notifications, filter, cached)
        val allIsNewer = allCached != null && (cached == null || allCached.loadedAt > cached.loadedAt)

        if (isCachedFresh && !allIsNewer) {
            _profile.update { it + (ProfileSection.Notifications to cached!!.copy(loading = false, error = null)) }
            return
        }

        // 2. 优先从全量通知 (NotificationFilter.All) 本地派生，0ms 瞬间秒显且杜绝闪烁抹白
        if (allCached != null && allCached.loaded) {
            val derivedEntries = allCached.content.entries.filter { filter.includes(it.notificationType) }
            val derivedState = ProfileUiState(
                content = ProfilePage(
                    entries = derivedEntries,
                    hasMore = allCached.content.hasMore,
                    nextCursor = allCached.content.nextCursor,
                ),
                loaded = true,
                loadedAt = allCached.loadedAt,
                unreadAtLoad = unreadBaseline(ProfileSection.Notifications, filter),
            )
            notificationCache[filter] = derivedState
            _profile.update { it + (ProfileSection.Notifications to derivedState.copy(loading = false, error = null)) }

            // 若本地派生出的条目足够丰富 (>= 15 条) 或全部通知已无更多，无需发起网络请求
            if (derivedEntries.size >= 15 || !allCached.content.hasMore) {
                return
            }
            // 派生条目较少且服务端可能还有更多历史记录，后台静默补充拉取（不抹白）
            loadProfile(ProfileSection.Notifications, refresh = true)
            return
        }

        // 3. 冷启动无 All 缓存场景，保留现有旧数据静默加载，绝不清空抹白
        if (cached != null) {
            _profile.update { it + (ProfileSection.Notifications to cached.copy(loading = true, error = null)) }
        }
        loadProfile(ProfileSection.Notifications)
    }

    fun setTopicListScrollBusy(source: Any, isBusy: Boolean) {
        scrollBusySignal.update(source, isBusy)
        if (isBusy) clearExcerptPrefetchState()
    }

    fun recordTopicListOpen(topicId: Long, postsCount: Int) {
        if (topicId > 0L && postsCount > 0) knownPostsCountOnOpen[topicId] = postsCount
    }

    private fun restoreCachedTopicDetail(topicId: Long): DetailUiState? {
        val cached = topicDetailCache.get(topicId)?.let {
            mergeConfirmedDetailRead(it, container.reading.confirmedPostNumbers(topicId))
        }
        if (cached == null) {
            _details.update { states -> states - topicId }
            return null
        }
        _details.update { states ->
            if (states[topicId] == cached) states else states + (topicId to cached)
        }
        return cached
    }

    private fun cacheTopicDetail(topicId: Long, state: DetailUiState, refreshTtl: Boolean = true) {
        if (state.topic == null) return
        val confirmedState = mergeConfirmedDetailRead(state, container.reading.confirmedPostNumbers(topicId))
        val evicted = topicDetailCache.put(topicId, confirmedState.copy(
            loading = false,
            refreshing = false,
            error = null,
            loadingReplies = emptySet(),
            reactingPosts = emptySet(),
            reactionErrors = emptyMap(),
            failedLoad = DetailLoad.Topic,
            createdPostId = null,
            locatePostNumber = null,
            noticeMessage = null,
            flagDialogPost = null,
            flagError = null,
            notificationLevelUpdating = false,
        ), refreshTtl = refreshTtl)
        if (evicted.isNotEmpty()) {
            _details.update { states -> states.filterKeys { it !in evicted } }
            evicted.filter { it != topicLiveId }.forEach { topicLiveSessions.remove(it)?.updates?.close() }
        }
    }

    private fun updateCachedTopicDetail(topicId: Long, refreshTtl: Boolean = true) {
        _details.value[topicId]?.let { cacheTopicDetail(topicId, it, refreshTtl = refreshTtl) }
    }

    fun retryTopic(id: Long, postNumber: Int? = null) {
        val failed = _details.value[id]?.failedLoad
        loadTopic(id, more = failed == DetailLoad.Next, previous = failed == DetailLoad.Previous,
            retry = true, postNumber = postNumber)
    }

    fun refreshTopic(id: Long, silent: Boolean = false) {
        refreshTopic(id, silent, includeLatestPosts = false)
    }

    private fun refreshTopic(id: Long, silent: Boolean, includeLatestPosts: Boolean) {
        val current = _details.value[id] ?: return
        if (current.loading || current.refreshing || current.reactingPosts.isNotEmpty()) return
        if (pageJobs["topic:$id"]?.isActive == true) return
        val generation = accountScopeGeneration
        pageJobs["topic:$id"] = viewModelScope.launch {
            if (!silent) {
                _details.update { states ->
                    states[id]?.let { states + (id to it.copy(refreshing = true, error = null)) } ?: states
                }
            }
            try {
                val freshTopic = repository.fetchTopic(id, trackVisit = !silent, present = !silent)
                if (generation != accountScopeGeneration) return@launch
                topicLiveSessions.getOrPut(id) { TopicLiveSession() }.apply {
                    nextCalibrationAt = SystemClock.elapsedRealtime() + 60_000L
                    needsCalibration = false
                }
                val freshById = freshTopic.postStream.posts.associateBy { it.id }
                val latest = _details.value[id] ?: current
                val wasAtEnd = latest.remaining.isEmpty()
                val updatedExisting = latest.posts.map { freshById[it.id] ?: it }
                var mergedPosts = (updatedExisting + freshTopic.postStream.posts)
                    .distinctBy { it.id }
                    .sortedBy { it.postNumber }
                var consumed = latest.consumedIds + freshTopic.postStream.posts.map { it.id }

                val windowIdx = freshTopic.postStream.stream.indexOf(latest.windowStartId).coerceAtLeast(0)
                val newRemaining = freshTopic.postStream.stream.drop(windowIdx).filterNot { it in consumed }
                // 若刷新前用户已看完旧楼层，且服务端出现了新回复，直接把最新一批新回复拉下来并入列表。
                if ((wasAtEnd || includeLatestPosts) && newRemaining.isNotEmpty()) {
                    val tailBatch = newRemaining.take(20)
                    val fetchedTail = try { repository.fetchPosts(id, tailBatch, present = !silent) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { null }
                    if (generation != accountScopeGeneration) return@launch
                    if (fetchedTail != null) {
                        mergedPosts = (mergedPosts + fetchedTail).distinctBy { it.id }.sortedBy { it.postNumber }
                        consumed = consumed + fetchedTail.map { it.id }
                    }
                }
                _details.update { states ->
                    val cur = states[id] ?: return@update states
                    states + (id to cur.copy(
                        topic = freshTopic,
                        posts = mergedPosts,
                        consumedIds = consumed,
                        refreshing = false,
                        error = null,
                    ))
                }
                updateCachedTopicDetail(id)
                if (!silent) markTopicSeen(
                    id,
                    maxOf(
                        freshTopic.postsCount,
                        freshTopic.postStream.stream.size,
                        mergedPosts.maxOfOrNull { it.postNumber } ?: 0,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (silent) repository.enterSilentPrefetchBackoff(error)
                if (!silent) {
                    _details.update { states ->
                        states[id]?.let {
                            states + (id to it.copy(error = error.message ?: "刷新失败", failedLoad = DetailLoad.Topic))
                        } ?: states
                    }
                    handlePageFailure(error) { refreshTopic(id, silent = false, includeLatestPosts = includeLatestPosts) }
                }
            } finally {
                _details.update { states ->
                    states[id]?.let { states + (id to it.copy(refreshing = false)) } ?: states
                }
                if (pageJobs["topic:$id"] === coroutineContext[Job]) pageJobs.remove("topic:$id")
                topicLiveSessions[id]?.updates?.trySend(Unit)
            }
        }
    }

    fun loadTopic(
        id: Long,
        more: Boolean = false,
        retry: Boolean = false,
        postNumber: Int? = null,
        previous: Boolean = false,
        resumePostNumber: Int? = null,
        fromNotification: Boolean = false,
        restoredPosition: TopicReadingPosition? = null,
    ) {
        val pendingTarget = peekNotificationTopicPending(id)
        val isNotificationJump = fromNotification || (pendingTarget != null)
        val effectivePostNumber = postNumber ?: pendingTarget?.second
        val base = if (!more && !previous && !retry) {
            restoreCachedTopicDetail(id) ?: DetailUiState()
        } else {
            _details.value[id] ?: DetailUiState()
        }
        val current = restoredPosition?.let { base.copy(filterUserId = it.filterUserId, descending = it.descending) } ?: base
        if (restoredPosition != null) _details.update { it + (id to current) }
        val targetPostNumber = effectivePostNumber ?: resumePostNumber
        val requestPostNumber = effectivePostNumber ?: resumePostNumber?.takeIf { it > 20 }
        if (!more && !previous) {
            markTopicSeen(id)
            beginReadTracking(id)
            probeOnEnteringDetail()
            current.topic?.let { topic ->
                val cat = current.category ?: categories.value.items.firstOrNull { it.id == topic.categoryId }
                val firstPost = current.posts.firstOrNull { it.postNumber == 1 }
                    ?: topic.postStream.posts.firstOrNull { it.postNumber == 1 }
                recordHistory(
                    topicId = id,
                    title = topic.title,
                    postNumber = highestVisiblePostByTopic[id] ?: 0,
                    authorUsername = firstPost?.username?.takeIf { it.isNotBlank() },
                    avatarTemplate = firstPost?.avatarTemplate,
                    categoryName = cat?.name,
                    categoryColor = cat?.color,
                    totalReplies = (topic.postsCount - 1).coerceAtLeast(0),
                )
            }
        }
        val targetLoaded = targetPostNumber == null || current.posts.any { it.postNumber == targetPostNumber }
        val isExplicitJump = !more && !previous && requestPostNumber != null && !targetLoaded
        val knownPostsCount = knownPostsCountOnOpen.remove(id)
        val hasKnownNewPosts = knownPostsCount != null && knownPostsCount > (current.topic?.postsCount ?: 0)

        // 仅在非通知来源、没有已知新回复、且目标楼层已就位时，才直接复用缓存 return
        if (!isNotificationJump && !more && !previous && current.topic != null && !retry && targetLoaded && !hasKnownNewPosts) {
            if (effectivePostNumber != null) {
                _details.update { states ->
                    states[id]?.let { states + (id to it.copy(locateVersion = it.locateVersion + 1,
                        locatePostNumber = effectivePostNumber)) } ?: states
                }
            }
            if (effectivePostNumber != null) {
                refreshTopic(id, silent = true, includeLatestPosts = false)
            }
            return
        }

        if (current.reactingPosts.isNotEmpty() && !isNotificationJump) return
        if (current.loading && !isExplicitJump && !isNotificationJump) return

        if (isNotificationJump) {
            consumeNotificationTopicPending(id)
            if (targetPostNumber != null) {
                _details.update { states ->
                    states[id]?.let { states + (id to it.copy(locateVersion = it.locateVersion + 1,
                        locatePostNumber = targetPostNumber)) } ?: states
                }
            }
        }
        if (more && current.remaining.isEmpty()) return
        if (previous && current.previousIds.isEmpty()) return
        pageJobs["topic:$id"]?.cancel()
        pageJobs["topic:$id"] = viewModelScope.launch {
            _details.update { it + (id to current.copy(loading = true, error = null)) }
            try {
                if (more || previous) {
                    val requested = when {
                        previous -> current.previousIds.takeLast(20)
                        // 倒序是由新到旧回溯,所以从尚未加载的楼层尾部往前取。
                        current.descending -> current.remaining.takeLast(20)
                        else -> current.remaining.take(20)
                    }
                    val posts = repository.fetchPosts(id, requested,
                        present = requested.any { it !in current.realtimePostIds })
                    _details.update {
                        val original = it[id] ?: current
                        val latest = mergeRealtimePosts(original, posts.filter { post -> post.id in original.realtimePostIds })
                        it + (id to latest.copy(
                            posts = (latest.posts + posts).distinctBy { post -> post.id }.sortedBy { post -> post.postNumber },
                            consumedIds = latest.consumedIds + posts.map { post -> post.id },
                            windowStartId = if (previous) requested.first() else current.windowStartId,
                            error = null, failedLoad = DetailLoad.Topic,
                        ))
                    }
                } else {
                    val topic = repository.fetchTopic(id, requestPostNumber, trackVisit = true)
                    topicLiveSessions.getOrPut(id) { TopicLiveSession() }.apply {
                        nextCalibrationAt = SystemClock.elapsedRealtime() + 60_000L
                        needsCalibration = false
                    }
                    val category = repository.cachedCategoryMap()[topic.categoryId]
                    val emojis = current.emojiUrls
                    val rootPost = current.posts.filter { it.postNumber == 1 }
                    var posts = if (requestPostNumber != null) (rootPost + topic.postStream.posts).distinctBy { it.id }.sortedBy { it.postNumber }
                        else topic.postStream.posts.distinctBy { it.id }
                    var effectiveTargetPostNumber = targetPostNumber
                    if (isNotificationJump && effectivePostNumber == null && topic.postsCount > 20 && topic.postStream.stream.isNotEmpty()) {
                        val tailIds = topic.postStream.stream.takeLast(20)
                        val fetchedTail = runCatching { repository.fetchPosts(id, tailIds) }.getOrNull()
                        if (fetchedTail != null && fetchedTail.isNotEmpty()) {
                            posts = (posts + fetchedTail).distinctBy { it.id }.sortedBy { it.postNumber }
                            effectiveTargetPostNumber = posts.maxOfOrNull { it.postNumber }
                        }
                    }
                    val missing = requestPostNumber != null && posts.none { it.postNumber == requestPostNumber }
                    val newLocateVersion = if (effectiveTargetPostNumber != null) (current.locateVersion + 1) else current.locateVersion
                    _details.update {
                        it + (id to DetailUiState(topic, posts, posts.map { post -> post.id }.toSet(), category,
                            error = if (missing) "目标楼层 #$requestPostNumber 已删除或不可访问" else null,
                            emojiUrls = emojis, windowStartId = topic.postStream.posts.minByOrNull { it.postNumber }?.id,
                            locateVersion = newLocateVersion,
                            locatePostNumber = if (isNotificationJump) effectiveTargetPostNumber else effectivePostNumber,
                            confirmedReadPostNumbers = container.reading.confirmedPostNumbers(id),
                            filterUserId = restoredPosition?.filterUserId,
                            descending = restoredPosition?.descending ?: false))
                    }
                    val scopeGeneration = accountScopeGeneration
                    viewModelScope.launch {
                        val resolvedCategory = runCatching { repository.categoryById(topic.categoryId) }.getOrNull()
                        val resolvedEmojis = repository.emojiUrls()
                        if (scopeGeneration == accountScopeGeneration) _details.update { states ->
                            states[id]?.let {
                                states + (id to it.copy(
                                    category = resolvedCategory ?: it.category,
                                    emojiUrls = resolvedEmojis.ifEmpty { it.emojiUrls },
                                ))
                            } ?: states
                        }
                    }
                    val firstPost = posts.firstOrNull { it.postNumber == 1 }
                        ?: topic.postStream.posts.firstOrNull { it.postNumber == 1 }
                    recordHistory(
                        topicId = id,
                        title = topic.title,
                        postNumber = current.posts.maxOfOrNull { it.postNumber } ?: 0,
                        authorUsername = firstPost?.username?.takeIf { it.isNotBlank() },
                        avatarTemplate = firstPost?.avatarTemplate,
                        categoryName = category?.name,
                        categoryColor = category?.color,
                        totalReplies = (topic.postsCount - 1).coerceAtLeast(0),
                    )
                    markTopicSeen(
                        id,
                        maxOf(
                            topic.postsCount,
                            topic.postStream.stream.size,
                            posts.maxOfOrNull { it.postNumber } ?: 0,
                        ),
                    )
                }
                updateCachedTopicDetail(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RateLimitException) {
                if (current.topic != null) {
                    diagnostics.log("话题 $id 刷新遭遇限流: ${error.message}，保持展示本地缓存")
                    _details.update { it + (id to current.copy(loading = false, error = null)) }
                } else {
                    _details.update { it + (id to current.copy(error = error.message, failedLoad = DetailLoad.Topic)) }
                }
            } catch (error: Exception) {
                _details.update { it + (id to current.copy(error = error.message ?: "加载失败",
                    failedLoad = when { previous -> DetailLoad.Previous; more -> DetailLoad.Next; else -> DetailLoad.Topic })) }
                handlePageFailure(error) {
                    loadTopic(
                        id,
                        more,
                        retry = true,
                        postNumber = postNumber,
                        previous = previous,
                        resumePostNumber = resumePostNumber,
                    )
                }
            } finally {
                _details.update { states -> states[id]?.let { states + (id to it.copy(loading = false)) } ?: states }
                if (pageJobs["topic:$id"] === coroutineContext[Job]) pageJobs.remove("topic:$id")
                topicLiveSessions[id]?.updates?.trySend(Unit)
            }
        }
    }

    /**
     * 切换评论区正序 / 倒序。
     *
     * 短帖(楼层已全在手上)和切回正序都是纯内存翻转,0 网络请求。
     * 长帖切到倒序时只保留 **1 楼 + stream 尾部的连续窗口**,而不是把开头的 2~20 楼和
     * 尾部的 281~300 楼混排:混排一是滑过 #281 会直接跳到 #20、中间断层两百多楼,
     * 二是 #299 若回复过 #5,树形排列会把最新回复吸进排在最底部的 #5 楼中楼里。
     * 切回正序时 windowStartId 仍指着尾窗首个,现有的"加载之前的楼层"缺口会自动补位。
     */
    fun toggleCommentOrder(topicId: Long) {
        val current = _details.value[topicId] ?: return
        if (current.loading || current.refreshing) return
        val target = !current.descending
        val stream = current.topic?.postStream?.stream.orEmpty()
        val tail = stream.takeLast(TAIL_WINDOW)
        val tailMissing = tail.filterNot { it in current.consumedIds }
        if (!target || tailMissing.isEmpty()) {
            _details.update { states ->
                states[topicId]?.let { states + (topicId to it.copy(descending = target)) } ?: states
            }
            updateCachedTopicDetail(topicId)
            return
        }
        pageJobs["topic:$topicId"]?.cancel()
        pageJobs["topic:$topicId"] = viewModelScope.launch {
            _details.update { it + (topicId to current.copy(loading = true, error = null, descending = true)) }
            try {
                val fetched = repository.fetchPosts(topicId, tailMissing)
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    val merged = (latest.posts.filter { it.postNumber == 1 || it.id in tail } + fetched)
                        .distinctBy { it.id }
                        .sortedBy { it.postNumber }
                    val anchor = merged.firstOrNull { it.postNumber > 1 } ?: merged.firstOrNull()
                    states + (topicId to latest.copy(
                        posts = merged,
                        consumedIds = merged.map { it.id }.toSet(),
                        windowStartId = anchor?.id ?: latest.windowStartId,
                        // 窗口换了一批楼层,楼中楼的游标和"已取完"标记跟着作废,否则展开按钮会点不动。
                        replyCursors = emptyMap(),
                        completedReplies = emptySet(),
                        descending = true,
                        error = null,
                        failedLoad = DetailLoad.Topic,
                    ))
                }
                updateCachedTopicDetail(topicId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states ->
                    states[topicId]?.let {
                        states + (topicId to it.copy(descending = current.descending, error = error.message ?: "切换顺序失败"))
                    } ?: states
                }
                handlePageFailure(error) { toggleCommentOrder(topicId) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(loading = false)) } ?: states }
            }
        }
    }

    fun react(topicId: Long, postId: Long, reaction: String) {
        val current = _details.value[topicId] ?: return
        if (current.loading) return
        val original = current.posts.firstOrNull { it.id == postId } ?: return

        val alreadyReacted = original.currentUserReaction?.id == reaction
        val newCurrentUserReaction = if (alreadyReacted) null else Reaction(id = reaction, count = 1, canUndo = true)
        val newReactions = original.reactions.toMutableList()
        val existingIndex = newReactions.indexOfFirst { it.id == reaction }
        if (alreadyReacted) {
            if (existingIndex >= 0) {
                val currentCount = newReactions[existingIndex].count
                if (currentCount <= 1) {
                    newReactions.removeAt(existingIndex)
                } else {
                    newReactions[existingIndex] = newReactions[existingIndex].copy(count = currentCount - 1)
                }
            }
        } else {
            val oldReactionId = original.currentUserReaction?.id
            if (oldReactionId != null) {
                val oldIndex = newReactions.indexOfFirst { it.id == oldReactionId }
                if (oldIndex >= 0) {
                    val count = newReactions[oldIndex].count
                    if (count <= 1) newReactions.removeAt(oldIndex) else newReactions[oldIndex] = newReactions[oldIndex].copy(count = count - 1)
                }
            }
            if (existingIndex >= 0) {
                newReactions[existingIndex] = newReactions[existingIndex].copy(count = newReactions[existingIndex].count + 1)
            } else {
                newReactions.add(Reaction(id = reaction, count = 1, canUndo = true))
            }
        }
        val optimisticPost = original.copy(
            currentUserReaction = newCurrentUserReaction,
            reactions = newReactions,
        )
        _details.update { states ->
            val latest = states[topicId] ?: return@update states
            states + (topicId to latest.copy(
                posts = latest.posts.map { if (it.id == postId) optimisticPost else it },
                reactionErrors = latest.reactionErrors - postId,
            ))
        }
        updateCachedTopicDetail(topicId, refreshTtl = false)

        pageJobs["reaction:$postId"] = viewModelScope.launch {
            try {
                val updated = repository.toggleReaction(postId, reaction).copy(boosts = original.boosts, canBoost = original.canBoost)
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    states + (topicId to latest.copy(
                        posts = latest.posts.map { if (it.id == postId) updated else it }
                    ))
                }
                updateCachedTopicDetail(topicId, refreshTtl = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    states + (topicId to latest.copy(
                        posts = latest.posts.map { if (it.id == postId) original else it },
                        reactionErrors = latest.reactionErrors + (postId to (error.message ?: "回应失败")),
                    ))
                }
                updateCachedTopicDetail(topicId, refreshTtl = false)
                handlePageFailure(error) { react(topicId, postId, reaction) }
            }
        }
    }

    /**
     * 未读轮询下一轮的休眠时长：平时按 [baseDelayMs]，处于冷却 / 惩罚期时直接睡到解封，
     * 再加 1~5 秒随机抖动，避免解封瞬间和其他后台池同秒齐发。
     */
    fun nextUnreadPollDelayMs(baseDelayMs: Long): Long {
        val blockedMs = maxOf(
            ApiQuotaPool.UNREAD_POLL.remainingWaitMs(),
            nextUnreadRefreshAt - SystemClock.elapsedRealtime(),
            unreadChallengeBackoffUntil - SystemClock.elapsedRealtime(),
        )
        return if (blockedMs > baseDelayMs) blockedMs + (1_000L..5_000L).random() else baseDelayMs
    }

    fun refreshUnread(force: Boolean = false, reloadNotifications: Boolean = true) {
        if (unreadJob?.isActive == true || _state.value.screen != Screen.List) return
        if (SystemClock.elapsedRealtime() < unreadChallengeBackoffUntil) return
        if (force) {
            nextUnreadRefreshAt = 0L
            unreadFailures = 0
        } else if (SystemClock.elapsedRealtime() < nextUnreadRefreshAt) {
            return
        }
        // 独立计量池准入：冷却 / 惩罚期 / 前台让行时静默跳过，不再每轮抛 RateLimitException 刷屏
        if (!ApiQuotaPool.UNREAD_POLL.tryAcquire(force)) return
        val requestScopeGeneration = accountScopeGeneration
        unreadJob = viewModelScope.launch {
            try {
                val beforeByFilter = _state.value.unreadByFilter
                val summary = repository.fetchUnreadSummary(store.getNotificationScope())
                if (requestScopeGeneration != accountScopeGeneration) return@launch
                ApiQuotaPool.UNREAD_POLL.onSuccess()
                unreadFailures = 0
                nextUnreadRefreshAt = 0L
                _state.update { it.copy(
                    unreadCount = summary.count,
                    unreadFilters = summary.unreadFilters,
                    unreadByFilter = summary.unreadByFilter,
                ) }
                if (!store.isBadgeTestHolding()) {
                    store.setLastBadgeCount(summary.count)
                    NotificationHelper.updateBadgeCount(getApplication(), summary.count, source = "前台未读刷新")
                }
                // 任一分类的未读变多才需要动。变少是用户自己读掉的,列表已经就地更新过。
                val grew = summary.unreadByFilter.any { (filter, now) -> now > (beforeByFilter[filter] ?: 0) }
                if (reloadNotifications && grew) prefetchUnreadNotifications()
            }
            catch (error: CancellationException) { throw error }
            catch (error: RateLimitException) {
                // 冷却期内的本地拦截，并未发出请求；直接睡到冷却结束，日志限频避免刷屏
                nextUnreadRefreshAt = SystemClock.elapsedRealtime() + error.retryAfterMs
                diagnostics.logThrottled("unread-ratelimit", "未读刷新退让: 限流冷却还剩 ${error.retryAfterMs / 1_000L}s")
            }
            catch (error: Exception) {
                unreadFailures = (unreadFailures + 1).coerceAtMost(2)
                nextUnreadRefreshAt = SystemClock.elapsedRealtime() + 5_000L
                diagnostics.log("未读数量刷新失败: ${error.javaClass.simpleName}")
                val challenged = error is NeedsInteractiveVerification ||
                    (error is HttpStatusException && error.status == 403)
                if (challenged) {
                    unreadChallengeBackoffUntil = SystemClock.elapsedRealtime() + UNREAD_CHALLENGE_BACKOFF_MS
                    diagnostics.log("未读刷新撞盾，暂停 ${UNREAD_CHALLENGE_BACKOFF_MS / 60_000L} 分钟，避免连累阅读进度同步")
                }
            }
        }
    }

    /**
     * 预热有未读的通知分类,让用户点标签时直接有内容。
     *
     * 一轮只预热一个分类:repository 的 gate 是全局串行锁,预取一旦占住它,
     * 用户紧接着的操作就得排在后面等。未读每 10 秒轮询一次,几轮下来相关分类都会热好,
     * 而单次占用是有上限的。当前正在看的分类优先,其余按声明顺序补。
     */
    private fun prefetchUnreadNotifications() {
        if (loadingFeeds.isNotEmpty() || pageJobs.values.any { it.isActive }) return
        val username = _state.value.username ?: return
        val current = _notificationFilter.value
        val unread = _state.value.unreadFilters
        val target = (listOf(current) + NotificationFilter.entries).distinct()
            .filter { it == current || it in unread }
            .firstOrNull { filter ->
                val cached = notificationCache[filter]
                cached == null || !isCacheUsable(ProfileSection.Notifications, filter, cached)
            } ?: return

        pageJobs["prefetch:notifications"] = viewModelScope.launch {
            // 预取的正好是用户当前看的那一类时,把 loading 立起来:
            // 既让界面有反馈,也挡住 loadProfile 再发一次重复请求。
            val showing = _notificationFilter.value == target
            if (showing) {
                _profile.update { states ->
                    states + (ProfileSection.Notifications to
                        (states[ProfileSection.Notifications] ?: ProfileUiState()).copy(loading = true, error = null))
                }
            }
            try {
                val fresh = fetchProfilePage(username, ProfileSection.Notifications, target, ProfileUiState(), more = false)
                notificationCache[target] = fresh
                if (_notificationFilter.value == target) {
                    _profile.update { it + (ProfileSection.Notifications to fresh) }
                }
                diagnostics.log("已预取通知分类: ${target.label}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 用户还没点进来,预取失败不该弹错误;真点进去时会正常重试并展示错误。
                diagnostics.log("预取通知失败: ${error.javaClass.simpleName}")
            } finally {
                if (showing) {
                    _profile.update { states ->
                        states[ProfileSection.Notifications]?.let {
                            states + (ProfileSection.Notifications to it.copy(loading = false))
                        } ?: states
                    }
                }
            }
        }
    }

    fun markAllNotificationsRead() {
        if (pageJobs["notifications:mark_all_read"]?.isActive == true) return
        fun markAllReadInPage(page: ProfilePage) = page.copy(
            entries = page.entries.map { it.copy(unread = false) },
        )
        _profile.update { panels ->
            panels[ProfileSection.Notifications]?.let { notices ->
                panels + (ProfileSection.Notifications to notices.copy(content = markAllReadInPage(notices.content)))
            } ?: panels
        }
        notificationCache.replaceAll { _, cached -> cached.copy(content = markAllReadInPage(cached.content)) }
        _state.update { state ->
            state.copy(
                unreadCount = 0,
                unreadByFilter = emptyMap(),
                unreadFilters = emptySet(),
            )
        }
        if (!store.isBadgeTestHolding()) {
            store.setLastBadgeCount(0)
            NotificationHelper.updateBadgeCount(getApplication(), 0, source = "一键全部已读")
        }

        pageJobs["notifications:mark_all_read"] = viewModelScope.launch {
            try {
                repository.markAllNotificationsRead()
                unreadJob?.join()
                refreshUnread(force = true, reloadNotifications = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                diagnostics.log("全部通知已读状态未同步: ${error.javaClass.simpleName}")
            }
        }
    }

    fun readNotification(id: Long) {
        readNotification(id, null)
    }

    fun readNotification(id: Long, knownType: Int?) {
        if (pageJobs["notification:$id"]?.isActive == true) return
        // 先本地扣减再发请求。否则角标要等 markNotificationRead 和 fetchUnreadSummary
        // 两趟请求(各自还有 1 秒节流 + 往返)才更新,用户点进去再退回来会看到它还亮着。
        val type = knownType ?: _profile.value[ProfileSection.Notifications]?.content?.entries
            ?.firstOrNull { it.id == id.toString() }?.notificationType
            ?: notificationCache.values.firstNotNullOfOrNull { page ->
                page.content.entries.firstOrNull { it.id == id.toString() }?.notificationType
            }
        markReadLocally(id, type)
        pageJobs["notification:$id"] = viewModelScope.launch {
            try {
                repository.markNotificationRead(id)
                // 等正在进行的分页统计结束,避免已读后的强制刷新被 isActive 守卫丢掉。
                unreadJob?.join()
                // 这条通知上面已经就地标成已读了,不要再整段重拉 —— 那会把用户翻过的页面
                // 和滚动位置一起丢掉(低频分类还要为此多扫好几页)。
                refreshUnread(force = true, reloadNotifications = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { diagnostics.log("通知已读状态未同步: ${error.javaClass.simpleName}") }
        }
    }

    /**
     * 就地把一条通知标为已读,并把它从未读计数里扣掉。
     *
     * **按条减 1,不是清零。** 有 5 条未读回复时读掉 1 条,回复分类的计数变 4、角标继续亮着,
     * 减到 0 才摘点。拿不到这条的类型(不在当前已加载列表里)就默认扣减 1 个主铃铛数,随后的 refreshUnread 会用服务端权威数据覆盖。
     */
    private fun markReadLocally(id: Long, type: Int?) {
        val key = id.toString()
        fun markRead(page: ProfilePage) = page.copy(
            entries = page.entries.map { if (it.id == key) it.copy(unread = false) else it },
        )
        _profile.update { panels ->
            panels[ProfileSection.Notifications]?.let { notices ->
                panels + (ProfileSection.Notifications to notices.copy(content = markRead(notices.content)))
            } ?: panels
        }
        // 按分类的缓存也要跟着改,否则切出去再切回来这条又变回未读。
        notificationCache.replaceAll { _, cached -> cached.copy(content = markRead(cached.content)) }
        if (type == null) {
            _state.update { state ->
                state.copy(unreadCount = (state.unreadCount - 1).coerceAtLeast(0))
            }
            if (!store.isBadgeTestHolding()) {
                store.setLastBadgeCount(_state.value.unreadCount)
                NotificationHelper.updateBadgeCount(getApplication(), _state.value.unreadCount, source = "本地已读")
            }
            return
        }
        _state.update { state ->
            val reduced = state.unreadByFilter.mapValues { (filter, count) ->
                if (filter.includes(type)) (count - 1).coerceAtLeast(0) else count
            }
            state.copy(
                unreadByFilter = reduced,
                unreadFilters = reduced.filterValues { it > 0 }.keys,
                // 口径跟随通知作用域,与桌面角标、推送下发共用 NotificationScope.includes。
                unreadCount = if (store.getNotificationScope().includes(type)) {
                    (state.unreadCount - 1).coerceAtLeast(0)
                } else state.unreadCount,
            )
        }
        if (!store.isBadgeTestHolding()) {
            store.setLastBadgeCount(_state.value.unreadCount)
            NotificationHelper.updateBadgeCount(getApplication(), _state.value.unreadCount, source = "本地已读")
        }
    }

    fun refreshPost(topicId: Long, postId: Long) {
        changePost(topicId, postId) { repository.fetchPosts(topicId, listOf(postId)).single() }
    }

    fun setSolution(topicId: Long, postId: Long) {
        val post = _details.value[topicId]?.posts?.firstOrNull { it.id == postId } ?: return
        if (post.acceptedAnswer && !post.canUnacceptAnswer || !post.acceptedAnswer && !post.canAcceptAnswer) return
        changePost(topicId, postId) {
            val accepted = !it.acceptedAnswer
            repository.setSolution(postId, accepted)
            it.copy(acceptedAnswer = accepted, canAcceptAnswer = !accepted, canUnacceptAnswer = accepted)
        }
    }

    fun createBoost(topicId: Long, postId: Long, raw: String) {
        changePost(topicId, postId) { post ->
            val boost = repository.createBoost(postId, raw)
            post.copy(boosts = post.boosts + boost, canBoost = false)
        }
    }

    fun deleteBoost(topicId: Long, postId: Long, boostId: Long) {
        changePost(topicId, postId) { post ->
            repository.deleteBoost(boostId)
            post.copy(boosts = post.boosts.filterNot { it.id == boostId }, canBoost = !post.yours)
        }
    }

    fun flagBoost(topicId: Long, postId: Long, boostId: Long, flagTypeId: Int, message: String?) {
        changePost(topicId, postId) { post ->
            repository.flagBoost(boostId, flagTypeId, message)
            post.copy(boosts = post.boosts.map { boost ->
                if (boost.id == boostId) boost.copy(canFlag = false, userFlagStatus = boost.userFlagStatus ?: 1) else boost
            })
        }
    }

    private fun changePost(topicId: Long, postId: Long, action: suspend (Post) -> Post) {
        val current = _details.value[topicId] ?: return
        if (current.loading || current.reactingPosts.isNotEmpty()) return
        val original = current.posts.firstOrNull { it.id == postId } ?: return
        pageJobs["reaction:$postId"] = viewModelScope.launch {
            _details.update { it + (topicId to current.copy(reactingPosts = setOf(postId), reactionErrors = current.reactionErrors - postId)) }
            try {
                val updated = action(original)
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    states + (topicId to latest.copy(posts = latest.posts.map {
                        when {
                            it.id == updated.id -> updated
                            updated.acceptedAnswer && it.acceptedAnswer -> it.copy(acceptedAnswer = false,
                                canAcceptAnswer = it.canUnacceptAnswer, canUnacceptAnswer = false)
                            else -> it
                        }
                    }))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(reactionErrors = it.reactionErrors + (postId to (error.message ?: "回应失败"))))
                } ?: states }
                // toggle 不是幂等操作,验证后只刷新状态,不自动重放写请求。
                handlePageFailure(error) { loadTopic(topicId, retry = true) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(reactingPosts = emptySet())) } ?: states }
            }
        }
    }

    /** 打开编辑器。编辑已有楼层时顺带把原文取回来填进输入框。 */
    fun openComposer(target: ComposerTarget) {
        if (_composer.value.submitting) return
        val needsDraft = target is ComposerTarget.Edit
        var storageError: String? = null
        val localDraft = try {
            if (needsDraft) null else localDraftKey(target)?.let { key -> container.draftStore.get(store.currentUserId(), key) }
        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
            storageError = "已有草稿暂时无法读取，请稍后重试，原稿已保留"
            null
        }
        val draft = if (target is ComposerTarget.ReplyTo && !target.quote.isNullOrBlank()) {
            appendQuoteToDraft(localDraft.orEmpty(), target.username, target.postNumber, target.topicId, target.quote)
        } else localDraft.orEmpty()
        _composer.value = ComposerUiState(target = target, draft = draft, loadingDraft = needsDraft, error = storageError)
        if (storageError == null && target is ComposerTarget.ReplyTo && target.quote != null) {
            updateDraft(draft)
        }
        if (target !is ComposerTarget.Edit) return
        pageJobs["draft:${target.postId}"] = viewModelScope.launch {
            try {
                val raw = repository.fetchPostRaw(target.postId)
                _composer.update { if (it.target == target) it.copy(draft = raw, loadingDraft = false) else it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _composer.update {
                    if (it.target == target) it.copy(loadingDraft = false,
                        error = error.message ?: "原文加载失败，请关闭后重试") else it
                }
            }
        }
    }

    fun updateDraft(text: String) {
        val current = _composer.value
        val target = current.target
        _composer.update { if (it.open) it.copy(draft = text, error = null) else it }
        if (target != null) {
            try {
                localDraftKey(target)?.let { key -> container.draftStore.put(store.currentUserId(), key, text) }
            } catch (_: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                _composer.update { if (it.target == target) it.copy(error = "草稿尚未保存，请保持此页面，稍后重试") else it }
            }
        }
    }

    private fun localDraftKey(target: ComposerTarget): String? = when (target) {
        is ComposerTarget.NewReply -> "reply:${target.topicId}:topic"
        is ComposerTarget.ReplyTo -> "reply:${target.topicId}:post:${target.postNumber}"
        is ComposerTarget.Edit -> null
    }

    fun cancelComposerSubmission() {
        pageJobs["composer"]?.cancel()
        _composer.update { it.copy(submitting = false) }
    }

    fun closeComposer() {
        pageJobs["composer"]?.cancel()
        pageJobs.remove("upload")?.cancel()
        _composer.value = ComposerUiState()
    }

    private fun assembleRaw(raw: String, attachments: List<AttachedMedia>): String {
        if (attachments.isEmpty()) return raw
        val unincluded = attachments.filter { !raw.contains(it.uploaded.shortUrl) }
        if (unincluded.isEmpty()) return raw
        val gap = if (raw.isBlank() || raw.endsWith("\n")) "" else "\n\n"
        return raw + gap + unincluded.joinToString("\n") { it.toMarkdown() }
    }

    /**
     * 上传选中的文件或图片。
     * 上传成功后以缩略图/卡片原样展示供用户确认，不把难懂的 Markdown 短码强塞进输入框；
     * 最终在发送或保存草稿时自动转化为 Discourse 编码。
     */
    fun attachToComposer(uri: Uri) {
        val current = _composer.value
        if (!current.open || current.uploading || current.submitting) return
        pageJobs["upload"] = viewModelScope.launch {
            _composer.update { it.copy(uploading = true, error = null) }
            try {
                val picked = withContext(Dispatchers.IO) { readPickedFile(uri) }
                val uploaded = repository.uploadFile(picked.name, picked.mimeType, picked.bytes)
                val isImg = uploaded.width > 0 || UploadLimits.isCompressibleImage(picked.name) ||
                    picked.mimeType.startsWith("image/")
                val media = AttachedMedia(
                    name = uploaded.fileName,
                    previewUrl = uri.toString(),
                    uploaded = uploaded,
                    isImage = isImg,
                )
                _composer.update {
                    if (!it.open) it.copy(uploading = false) else {
                        it.copy(attachments = it.attachments + media, uploading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _composer.update { it.copy(uploading = false, error = error.message ?: "上传失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    fun removeComposerAttachment(id: String) {
        _composer.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }

    private data class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

    /** 先按大小、类型预检,再限量读取；位图按需降采样。 */
    private fun readPickedFile(uri: Uri): PickedFile {
        val resolver = getApplication<Application>().contentResolver
        var displayName: String? = null
        var reportedSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        reportedSize = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                    }
                }
            }
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "upload"
        val bytes = UploadLimits.readBytes(name, reportedSize) { resolver.openInputStream(uri) }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (!UploadLimits.isCompressibleImage(name)) return PickedFile(name, mime, bytes)
        return shrinkImage(name, bytes) ?: PickedFile(name, mime, bytes)
    }

    /**
     * 把超尺寸/超大的位图收一收。返回 null 表示不需要动或处理不了(调用方原样上传)。
     *
     * PNG 优先仍编码成 PNG —— 转 JPEG 会把透明区域填成黑色。只有降采样后 PNG 还是太大时
     * 才退成 JPEG,那种情况下用户本来也没得选(4MB 是硬上限)。
     * GIF/SVG/AVIF/JXL 不走这里:动图会被压成静态,后两者解码支持也不稳。
     */
    private fun shrinkImage(name: String, bytes: ByteArray): PickedFile? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return@runCatching null
        // 既没超尺寸也没超大小就别碰,重编码只会白掉画质。
        if (longEdge <= UploadLimits.MAX_IMAGE_EDGE && bytes.size <= UploadLimits.MAX_BYTES) return@runCatching null

        var sample = 1
        while (longEdge / sample > UploadLimits.MAX_IMAGE_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null

        val isPng = UploadLimits.extensionOf(name) == "png"
        val out = ByteArrayOutputStream()
        if (isPng) {
            decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (out.size() <= UploadLimits.MAX_BYTES) {
                decoded.recycle()
                return@runCatching PickedFile(name, "image/png", out.toByteArray())
            }
        }
        var quality = UploadLimits.JPEG_QUALITY
        do {
            out.reset()
            decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)
            quality -= 10
        } while (out.size() > UploadLimits.MAX_BYTES && quality >= 40)
        decoded.recycle()
        PickedFile("${name.substringBeforeLast('.', name)}.jpg", "image/jpeg", out.toByteArray())
    }.getOrNull()

    /** 提交编辑器内容。成功后就地并入已加载楼层,不整页重拉。 */
    fun submitComposer() {
        val current = _composer.value
        val target = current.target ?: return
        if (!current.canSubmit) return
        val raw = assembleRaw(current.draft, current.attachments)
        pageJobs["composer"] = viewModelScope.launch {
            _composer.update { it.copy(submitting = true, error = null) }
            try {
                kotlinx.coroutines.withTimeout(20_000L) {
                    when (target) {
                        is ComposerTarget.NewReply -> {
                            when (val result = repository.createPost(target.topicId, raw)) {
                                is PostResult.Success -> applyCreatedPost(target.topicId, result.post)
                                is PostResult.Enqueued -> setNoticeMessage(target.topicId, "您的回复已提交，正等待管理员审核后显示")
                            }
                        }
                        is ComposerTarget.ReplyTo -> {
                            when (val result = repository.createPost(target.topicId, raw, target.postNumber)) {
                                is PostResult.Success -> applyCreatedPost(target.topicId, result.post)
                                is PostResult.Enqueued -> setNoticeMessage(target.topicId, "您的回复已提交，正等待管理员审核后显示")
                            }
                        }
                        is ComposerTarget.Edit -> applyEditedPost(target.topicId, repository.updatePost(target.postId, raw))
                    }
                }
                try {
                    localDraftKey(target)?.let { key -> container.draftStore.put(store.currentUserId(), key, "") }
                } catch (_: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                    diagnostics.log("评论已提交，草稿暂未清除；不重试已成功的提交")
                }
                _composer.value = ComposerUiState()
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                _composer.update { it.copy(submitting = false, error = "提交结果未确认，请刷新楼层检查后再决定是否重试") }
            } catch (error: CancellationException) {
                _composer.update { it.copy(submitting = false) }
                throw error
            } catch (error: Exception) {
                _composer.update { it.copy(submitting = false, error = error.message ?: "提交失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    fun loadDrafts(refresh: Boolean = false, silent: Boolean = false) {
        if (_state.value.username == null) return
        val current = _drafts.value
        if (current.loading) return
        if (!refresh && current.loaded) return
        pageJobs["drafts"] = viewModelScope.launch {
            if (!silent || !current.loaded) {
                _drafts.update { it.copy(loading = true, error = null) }
            }
            try {
                val list = repository.fetchDrafts()
                _drafts.update {
                    it.copy(
                        items = list,
                        loaded = true,
                        loading = false,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _drafts.update {
                    it.copy(
                        loading = false,
                        error = if (silent && it.loaded) null else (error.message ?: "草稿加载失败"),
                    )
                }
                if (!silent) {
                    handlePageFailure(error) { loadDrafts(refresh = true) }
                }
            }
        }
    }

    fun deleteDraft(draft: DraftItem) {
        if (_state.value.username == null) return
        if (draft.draftKey in _drafts.value.deletingKeys) return
        pageJobs["deleteDraft:${draft.draftKey}"] = viewModelScope.launch {
            _drafts.update { it.copy(deletingKeys = it.deletingKeys + draft.draftKey, error = null) }
            try {
                repository.deleteDraft(draft.draftKey, draft.sequence)
                _drafts.update {
                    it.copy(
                        items = it.items.filterNot { item -> item.draftKey == draft.draftKey },
                        deletingKeys = it.deletingKeys - draft.draftKey,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _drafts.update {
                    it.copy(
                        deletingKeys = it.deletingKeys - draft.draftKey,
                        error = error.message ?: "删除草稿失败",
                    )
                }
                handlePageFailure(error) {}
            }
        }
    }

    /**
     * 打开创建新话题界面。
     * - 从首页进入时 `initialCategoryId` 为 `null`（分类默认为空）。
     * - 从指定分区进入时 `initialCategoryId` 为当前分区的 `categoryId`（已包含当前所选等级 Lv.0~3）。
     */
    /**
     * 校验预设分类或草稿分类是否允许当前用户创建话题（对齐 FluxDO `_applyCurrentFilter` 与 `_restoreCategoryFromDraft`）。
     * 若分类列表已就绪且该分类不在可创建列表中（如“未分类”、“公告”或高信任等级只读分区），则回退为 null。
     */
    private fun resolveCreatableCategoryId(categoryId: Int?): Int? {
        val targetId = categoryId ?: return null
        val currentItems = _categories.value.items
        if (currentItems.isEmpty()) return targetId
        val creatable = org.linuxdo.android.data.creatableCategories(currentItems)
        return targetId.takeIf { id -> creatable.any { it.id == id } }
    }

    /**
     * 若当前分类缓存尚未合并 `/site.json` 的发帖权限（例如首次升级后冷启动命中旧版持久化缓存），
     * 打开话题发布器时立即后台拉取合并一次，并二次校验当前已选分类。
     */
    private fun ensureTopicCreatorCategoryPermissions() {
        if (repository.hasSyncedCategoryPermissions()) return
        val requestScopeGeneration = accountScopeGeneration
        viewModelScope.launch {
            val updated = runCatching { repository.ensureCategoryPermissionsFromSite() }.getOrNull() ?: return@launch
            if (requestScopeGeneration != accountScopeGeneration) return@launch
            val (freshList, rawJson) = updated
            if (rawJson.isNotBlank()) {
                container.preferences.edit { putString("cached_categories_json", rawJson) }
            }
            _categories.value = CategoriesUiState(items = freshList, loaded = true, order = store.loadCategoryOrder())
            val currentCatId = _topicCreator.value.categoryId
            if (_topicCreator.value.open && currentCatId != null) {
                val validatedId = resolveCreatableCategoryId(currentCatId)
                if (validatedId != currentCatId) {
                    _topicCreator.update { state ->
                        val next = state.copy(categoryId = validatedId)
                        next.copy(savedSnapshot = next.currentSnapshot)
                    }
                }
            }
        }
    }

    fun openTopicCreator(initialCategoryId: Int? = null) {
        if (_topicCreator.value.submitting || _topicCreator.value.savingDraft) return
        if (!_categories.value.loaded && !_categories.value.loading) {
            loadCategories()
        }
        val validCategoryId = resolveCreatableCategoryId(initialCategoryId)
        val existingNewTopicDraft = _drafts.value.items.firstOrNull { it.draftKey == "new_topic" }
        val baseState = TopicCreatorUiState(
            open = true,
            draftKey = "new_topic",
            sequence = existingNewTopicDraft?.sequence ?: 0L,
            action = "createTopic",
            title = "",
            raw = "",
            categoryId = validCategoryId,
            tags = emptyList(),
        )
        _topicCreator.value = baseState.copy(savedSnapshot = baseState.currentSnapshot)
        ensureTopicCreatorCategoryPermissions()
        populateCreatorEmojisAndSequence("new_topic")
        loadTopicCreatorTags(validCategoryId)
    }

    /** 如果是话题回复类草稿，直接打开该话题的回复栏并将草稿内容填入。 */
    fun openReplyWithDraft(draft: DraftItem) {
        val topicId = draft.topicId ?: return
        val target = if (draft.replyToPostNumber != null) {
            ComposerTarget.ReplyTo(topicId = topicId, postNumber = draft.replyToPostNumber, username = "")
        } else {
            ComposerTarget.NewReply(topicId = topicId)
        }
        _composer.value = ComposerUiState(
            target = target,
            draft = draft.raw,
            loadingDraft = false,
            submitting = false,
            uploading = false,
        )
    }

    /** 从草稿列表点击编辑，将草稿内容填入发布器。 */
    fun openDraftInCreator(draft: DraftItem) {
        if (_topicCreator.value.submitting || _topicCreator.value.savingDraft) return
        if (!_categories.value.loaded && !_categories.value.loading) {
            loadCategories()
        }
        val validCategoryId = if (draft.isNewTopic) resolveCreatableCategoryId(draft.categoryId) else draft.categoryId
        val baseState = TopicCreatorUiState(
            open = true,
            draftKey = draft.draftKey,
            sequence = draft.sequence,
            action = draft.action,
            replyTopicId = draft.topicId,
            replyToPostNumber = draft.replyToPostNumber,
            title = draft.title,
            raw = draft.raw,
            categoryId = validCategoryId,
            tags = draft.tags,
        )
        _topicCreator.value = baseState.copy(savedSnapshot = baseState.currentSnapshot)
        ensureTopicCreatorCategoryPermissions()
        populateCreatorEmojisAndSequence(draft.draftKey)
        loadTopicCreatorTags(validCategoryId)
    }

    private fun populateCreatorEmojisAndSequence(targetDraftKey: String) {
        viewModelScope.launch {
            val emojis = runCatching { repository.emojiUrls() }.getOrDefault(emptyMap())
            if (emojis.isNotEmpty()) {
                _topicCreator.update { if (it.open) it.copy(emojiUrls = emojis) else it }
            }
            if (!_drafts.value.loaded) {
                runCatching { repository.fetchDrafts(burst = true) }.getOrNull()?.let { list ->
                    _drafts.update { it.copy(items = list, loaded = true, loading = false, error = null) }
                    val matched = list.firstOrNull { it.draftKey == targetDraftKey }
                    if (matched != null) {
                        _topicCreator.update {
                            if (it.open && it.draftKey == targetDraftKey) it.copy(sequence = matched.sequence) else it
                        }
                    }
                }
            }
        }
    }

    fun loadTopicCreatorTags(categoryId: Int?, query: String = "") {
        pageJobs["tags:creator"]?.cancel()
        pageJobs["tags:creator"] = viewModelScope.launch {
            _topicCreator.update { it.copy(loadingTags = true) }
            val currentSelected = _topicCreator.value.tags
            val searchResult = repository.searchTags(
                categoryId = categoryId,
                query = query,
                selectedTags = currentSelected,
                limit = 8,
                filterForInput = true,
            )
            _topicCreator.update {
                if (it.open) it.copy(
                    availableTags = searchResult.results,
                    requiredTagGroupName = searchResult.requiredTagName,
                    requiredTagMinCount = searchResult.requiredTagMinCount,
                    loadingTags = false
                ) else it
            }
        }
    }

    private var tagSearchDebounceJob: Job? = null
    fun searchTopicCreatorTags(query: String) {
        tagSearchDebounceJob?.cancel()
        tagSearchDebounceJob = viewModelScope.launch {
            delay(300L) // 照搬 FluxDO 300ms 防抖
            val currentCat = _topicCreator.value.categoryId
            loadTopicCreatorTags(currentCat, query)
        }
    }

    fun updateTopicCreatorTitle(title: String) {
        _topicCreator.update { if (it.open) it.copy(title = title, error = null, notice = null) else it }
    }

    fun updateTopicCreatorRaw(raw: String) {
        _topicCreator.update { if (it.open) it.copy(raw = raw, error = null, notice = null) else it }
    }

    fun updateTopicCreatorCategory(categoryId: Int?) {
        _topicCreator.update { if (it.open) it.copy(categoryId = categoryId, error = null, notice = null) else it }
        loadTopicCreatorTags(categoryId)
    }

    fun updateTopicCreatorTags(tags: List<String>) {
        _topicCreator.update { if (it.open) it.copy(tags = tags.distinct(), error = null, notice = null) else it }
    }

    fun clearTopicCreatorNotice() {
        _topicCreator.update { it.copy(notice = null) }
    }

    fun closeTopicCreator() {
        val current = _topicCreator.value
        if (current.submitting || current.savingDraft) return
        _topicCreator.value = TopicCreatorUiState()
    }

    fun attachToTopicCreator(uri: Uri) {
        val current = _topicCreator.value
        if (!current.open || current.uploading || current.submitting || current.savingDraft) return
        pageJobs["upload:creator"] = viewModelScope.launch {
            _topicCreator.update { it.copy(uploading = true, error = null, notice = null) }
            try {
                val picked = withContext(Dispatchers.IO) { readPickedFile(uri) }
                val uploaded = repository.uploadFile(picked.name, picked.mimeType, picked.bytes)
                val isImg = uploaded.width > 0 || UploadLimits.isCompressibleImage(picked.name) ||
                    picked.mimeType.startsWith("image/")
                val media = AttachedMedia(
                    name = uploaded.fileName,
                    previewUrl = uri.toString(),
                    uploaded = uploaded,
                    isImage = isImg,
                )
                _topicCreator.update {
                    if (!it.open) it.copy(uploading = false) else {
                        it.copy(attachments = it.attachments + media, uploading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _topicCreator.update { it.copy(uploading = false, error = error.message ?: "上传失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    fun removeTopicCreatorAttachment(id: String) {
        _topicCreator.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }

    fun saveTopicDraft(closeAfterSave: Boolean = false) {
        val current = _topicCreator.value
        if (!current.open || !current.canSaveDraft) return
        val rawToSave = assembleRaw(current.raw, current.attachments)
        pageJobs["saveDraft"] = viewModelScope.launch {
            _topicCreator.update { it.copy(savingDraft = true, error = null, notice = null) }
            try {
                val newSeq = repository.saveDraft(
                    draftKey = current.draftKey,
                    sequence = current.sequence,
                    title = current.title.trim(),
                    raw = rawToSave,
                    categoryId = current.categoryId,
                    tags = current.tags,
                    action = current.action,
                    topicId = current.replyTopicId,
                    replyToPostNumber = current.replyToPostNumber,
                )
                if (closeAfterSave) {
                    _topicCreator.value = TopicCreatorUiState()
                    Toast.makeText(getApplication(), "草稿已保存", Toast.LENGTH_SHORT).show()
                } else {
                    _topicCreator.update {
                        val next = it.copy(
                            sequence = newSeq,
                            savingDraft = false,
                            notice = "草稿已保存",
                        )
                        next.copy(savedSnapshot = next.currentSnapshot)
                    }
                }
                loadDrafts(refresh = true, silent = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _topicCreator.update { it.copy(savingDraft = false, error = error.message ?: "保存草稿失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    fun submitTopicCreator(onPublished: (Long, String, Int?) -> Unit) {
        val current = _topicCreator.value
        if (!current.open || !current.canSubmit) return
        if (!current.isReplyDraft && current.categoryId == null) {
            _topicCreator.update { it.copy(error = "请先选择话题分类") }
            return
        }
        val rawToSubmit = assembleRaw(current.raw, current.attachments)
        pageJobs["createTopic"] = viewModelScope.launch {
            _topicCreator.update { it.copy(submitting = true, error = null, notice = null) }
            try {
                if (current.isReplyDraft) {
                    val targetTopicId = requireNotNull(current.replyTopicId)
                    when (val result = repository.createPost(
                        topicId = targetTopicId,
                        raw = rawToSubmit,
                        replyToPostNumber = current.replyToPostNumber,
                        draftKey = current.draftKey,
                    )) {
                        is PostResult.Success -> {
                            applyCreatedPost(targetTopicId, result.post)
                            _drafts.update { state -> state.copy(items = state.items.filterNot { it.draftKey == current.draftKey }) }
                            _topicCreator.value = TopicCreatorUiState()
                            onPublished(targetTopicId, current.title.ifBlank { "话题 #$targetTopicId" }, result.post.postNumber)
                        }
                        is PostResult.Enqueued -> {
                            _drafts.update { state -> state.copy(items = state.items.filterNot { it.draftKey == current.draftKey }) }
                            _topicCreator.value = TopicCreatorUiState()
                            Toast.makeText(getApplication(), "您的回复已提交，正等待管理员审核后显示", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    when (val result = repository.createTopic(
                        title = current.title,
                        raw = rawToSubmit,
                        categoryId = current.categoryId,
                        tags = current.tags,
                        draftKey = current.draftKey,
                    )) {
                        is PostResult.Success -> {
                            val newTopicId = result.post.topicId ?: 0L
                            _drafts.update { state -> state.copy(items = state.items.filterNot { it.draftKey == current.draftKey }) }
                            _topicCreator.value = TopicCreatorUiState()
                            refreshLatest()
                            if (newTopicId > 0L) {
                                onPublished(newTopicId, current.title.trim(), null)
                            } else {
                                Toast.makeText(getApplication(), "话题已发布", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is PostResult.Enqueued -> {
                            _drafts.update { state -> state.copy(items = state.items.filterNot { it.draftKey == current.draftKey }) }
                            _topicCreator.value = TopicCreatorUiState()
                            Toast.makeText(getApplication(), "您的话题已提交，正等待管理员审核后显示", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _topicCreator.update { it.copy(submitting = false, error = error.message ?: "发布失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    /** 列表长按话题：将话题首帖添加到书签。 */
    fun bookmarkTopicFromList(topicId: Long, onResult: (String) -> Unit) {
        if (_state.value.username == null) {
            onResult("请先登录账号")
            return
        }
        if (pageJobs["listBookmark:$topicId"]?.isActive == true) return
        val cachedFirstPostId = _details.value[topicId]?.posts?.firstOrNull { it.postNumber == 1 }?.id
        pageJobs["listBookmark:$topicId"] = viewModelScope.launch {
            try {
                val bookmarkId = repository.bookmarkTopic(topicId, cachedFirstPostId)
                _details.update { states ->
                    val detail = states[topicId] ?: return@update states
                    states + (topicId to detail.copy(
                        posts = detail.posts.map {
                            if (it.postNumber == 1) it.copy(bookmarked = true, bookmarkId = bookmarkId) else it
                        }
                    ))
                }
                updateCachedTopicDetail(topicId, refreshTtl = false)
                onResult("已添加到书签")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onResult(error.message ?: "添加书签失败")
                handlePageFailure(error) {}
            }
        }
    }

    /** 列表长按话题：举报该话题（自动解析 1 楼 postId 提交举报）。 */
    fun submitTopicFlagFromList(
        topicId: Long,
        reasonId: Int,
        message: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (_state.value.username == null) {
            onError("请先登录账号")
            return
        }
        val reason = PostFlagReason.entries.firstOrNull { it.id == reasonId } ?: return
        if (reason.requiresMessage && message.isNullOrBlank()) {
            onError("请填写具体说明")
            return
        }
        if (pageJobs["listFlag:$topicId"]?.isActive == true) return
        val cachedFirstPostId = _details.value[topicId]?.posts?.firstOrNull { it.postNumber == 1 }?.id
        pageJobs["listFlag:$topicId"] = viewModelScope.launch {
            try {
                val postId = cachedFirstPostId ?: repository.fetchTopicFirstPostId(topicId)
                repository.flagPost(postId, reasonId, message)
                _details.update { states ->
                    val detail = states[topicId] ?: return@update states
                    states + (topicId to detail.copy(flaggedPosts = detail.flaggedPosts + postId))
                }
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error.message ?: "举报提交失败，请稍后再试")
                handlePageFailure(error) {}
            }
        }
    }

    fun setPreviewListVisible(visible: Boolean) {
        previewListVisible = visible
        if (!visible) clearExcerptPrefetchState()
    }

    private fun canPrefetchExcerpt(): Boolean = previewListVisible &&
        _state.value.screen == Screen.List && !store.sessionChanging && !_accounts.value.switching &&
        !scrollBusySignal.isBusy && !repository.isRateLimitCoolingDown() && !repository.isSilentPrefetchBackingOff()

    fun prefetchTopicExcerpt(topicId: Long?) {
        val previousJob = excerptPrefetchJob
        clearExcerptPrefetchState()
        if (topicId == null || topicId <= 0L || !canPrefetchExcerpt() || ExcerptCache.get(topicId) != null) return
        if (topicId in excerptAttemptedIds || excerptAttemptedIds.size >= EXCERPT_SESSION_BUDGET) return
        val generation = accountScopeGeneration
        excerptPrefetchJob = viewModelScope.launch {
            previousJob?.join()
            while (true) {
                val wait = maxOf(excerptNextAllowedAt - SystemClock.elapsedRealtime(), ApiQuotaPool.EXCERPT_PREFETCH.remainingWaitMs())
                if (wait <= 0L) break
                delay(wait)
            }
            if (generation != accountScopeGeneration || !canPrefetchExcerpt() || ExcerptCache.get(topicId) != null) return@launch
            if (topicId in excerptAttemptedIds || excerptAttemptedIds.size >= EXCERPT_SESSION_BUDGET) return@launch
            // 尝试即消耗预算，失败或取消也不自动补打；切后台、切号不重置进程预算。
            excerptAttemptedIds += topicId
            try {
                repository.prefetchExcerpt(topicId)
            } finally {
                excerptNextAllowedAt = SystemClock.elapsedRealtime() + kotlin.random.Random.nextLong(300L, 600L)
            }
        }
    }

    fun prefetchTopicExcerpt(source: Any, candidates: List<Long>, activeIdsProvider: () -> Set<Long>) {
        if (candidates.isEmpty()) {
            clearExcerptPrefetchState()
            return
        }
        val unattempted = candidates.filter { it > 0L && it !in excerptAttemptedIds && ExcerptCache.get(it) == null }
        if (unattempted.isEmpty()) return

        val previousJob = excerptPrefetchJob
        clearExcerptPrefetchState()
        val generation = accountScopeGeneration
        excerptPrefetchJob = viewModelScope.launch {
            previousJob?.join()
            for (topicId in unattempted) {
                if (!isActive || generation != accountScopeGeneration || !canPrefetchExcerpt()) break
                if (topicId in excerptAttemptedIds || excerptAttemptedIds.size >= EXCERPT_SESSION_BUDGET || ExcerptCache.get(topicId) != null) continue
                val activeIds = activeIdsProvider()
                if (activeIds.isNotEmpty() && topicId !in activeIds) continue

                while (true) {
                    val wait = maxOf(excerptNextAllowedAt - SystemClock.elapsedRealtime(), ApiQuotaPool.EXCERPT_PREFETCH.remainingWaitMs())
                    if (wait <= 0L) break
                    delay(wait)
                }
                if (!isActive || generation != accountScopeGeneration || !canPrefetchExcerpt() || ExcerptCache.get(topicId) != null) break
                if (topicId in excerptAttemptedIds || excerptAttemptedIds.size >= EXCERPT_SESSION_BUDGET) break
                excerptAttemptedIds += topicId
                try {
                    repository.prefetchExcerpt(topicId)
                } finally {
                    excerptNextAllowedAt = SystemClock.elapsedRealtime() + kotlin.random.Random.nextLong(300L, 600L)
                }
            }
        }
    }

    /** 列表批次只复用原生字段与本地缓存，不再额外搜索或拉取每条话题。 */
    fun prefetchTopicExcerpts(topics: List<Topic>) {
        ExcerptCache.loadBatchFromStore(topics.map { it.id })
    }

    private fun clearExcerptPrefetchState() {
        excerptPrefetchJob?.cancel()
        // 保留句柄供下一次请求等待取消完成，避免传输层尚未退出就启动另一条请求。
    }

    /**
     * 新楼层必须同时登记进 consumedIds 和 stream:
     * 不进 consumedIds,`remaining` 会把它当成"未加载楼层"再拉一次;
     * 不进 stream,话题总楼层数和楼层进度就对不上。
     */
    private fun applyCreatedPost(topicId: Long, post: Post) {
        if (applyNewPost(topicId, post)) {
            updateTopicPostCountInLists(topicId, post)
        }
        topicLiveSessions[topicId]?.pending?.remove(post.id)
        topicLiveSessions[topicId]?.readyPosts?.remove(post.id)
        updateCachedTopicDetail(topicId)
    }

    private fun applyNewPost(topicId: Long, post: Post): Boolean {
        var wasNewPost = false
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            val stream = current.topic?.postStream?.stream.orEmpty()
            val isNewContent = current.posts.none { it.id == post.id }
            val isNewPost = post.id !in stream && post.id !in current.realtimePostIds && isNewContent
            wasNewPost = isNewPost
            val topic = current.topic?.let {
                it.copy(
                    postStream = if (post.id !in stream) it.postStream.copy(stream = stream + post.id) else it.postStream,
                    postsCount = if (isNewPost) it.postsCount + 1 else it.postsCount,
                )
            }
            states + (topicId to current.copy(
                topic = topic,
                posts = (current.posts + post).distinctBy { it.id }.sortedBy { it.postNumber }
                    // 父楼层的 reply_count 同步 +1,否则"更多回复"的计数判断会漏掉服务端其余回复。1 楼为主贴不挂楼中楼。
                    .map { if (isNewContent && it.postNumber == post.replyToPostNumber && it.postNumber > 1) it.copy(replyCount = it.replyCount + 1) else it },
                consumedIds = current.consumedIds + post.id,
                realtimePostIds = current.realtimePostIds - post.id,
                windowStartId = current.windowStartId ?: post.id,
                createdPostId = post.id,
                filterUserId = null,
            ))
        }
        return wasNewPost
    }

    private fun updateTopicPostCountInLists(topicId: Long, post: Post) {
        val activityTime = post.createdAt ?: java.time.Instant.now().toString()
        fun updateItem(item: TopicListItem): TopicListItem {
            if (item.topic.id != topicId) return item
            val nestedReplyDelta = if (post.replyToPostNumber != null && post.replyToPostNumber > 1) 1 else 0
            return item.copy(
                topic = item.topic.copy(
                    postsCount = item.topic.postsCount + 1,
                    highestPostNumber = maxOf(item.topic.highestPostNumber ?: 0, post.postNumber),
                    replyCount = item.topic.replyCount + nestedReplyDelta,
                    lastPostedAt = activityTime,
                    bumpedAt = activityTime,
                    lastPosterUsername = post.username.ifBlank { item.topic.lastPosterUsername },
                ),
            )
        }
        fun updateList(list: TopicListUiState): TopicListUiState {
            val items = list.items.map(::updateItem)
            val pendingPage = list.pendingPage?.let { page -> page.copy(items = page.items.map(::updateItem)) }
            return if (items == list.items && pendingPage == list.pendingPage) list
            else list.copy(items = items, pendingPage = pendingPage)
        }
        _feedStates.update { feeds -> feeds.mapValues { (_, list) -> updateList(list) } }
        _state.update { current ->
            val latest = updateList(current.latest)
            if (latest == current.latest) current else current.copy(latest = latest)
        }
        _categoryTopics.update { topics -> topics.mapValues { (_, list) -> updateList(list) } }
        _userTopicStates.update { topics -> topics.mapValues { (_, list) -> updateList(list) } }
        _search.update { current -> current.copy(results = updateList(current.results)) }
    }

    /** 界面已经滚到新楼层了,清掉标记,免得后面再触发一次。 */
    fun clearCreatedPost(topicId: Long) {
        val shouldRefresh = _details.value[topicId]?.createdPostId != null
        _details.update { states ->
            states[topicId]?.takeIf { it.createdPostId != null }
                ?.let { states + (topicId to it.copy(createdPostId = null)) } ?: states
        }
        updateCachedTopicDetail(topicId)
        if (shouldRefresh) refreshTopic(topicId, silent = true)
    }

    fun setTopicAuthorFilter(topicId: Long, userId: Long?) {
        _details.update { states ->
            states[topicId]?.let { states + (topicId to it.copy(filterUserId = userId)) } ?: states
        }
        updateCachedTopicDetail(topicId)
    }

    fun votePoll(topicId: Long, postId: Long, pollName: String, optionIds: List<String>) {
        val current = _details.value[topicId] ?: return
        if (postId in current.reactingPosts || optionIds.isEmpty()) return
        val jobKey = "poll:$postId:$pollName"
        if (pageJobs[jobKey]?.isActive == true) return
        pageJobs[jobKey] = viewModelScope.launch {
            _details.update { states ->
                states[topicId]?.let { detail ->
                    states + (topicId to detail.copy(
                        reactingPosts = detail.reactingPosts + postId,
                        reactionErrors = detail.reactionErrors - postId,
                    ))
                } ?: states
            }
            try {
                val updatedPoll = repository.votePoll(postId, pollName, optionIds)
                _details.update { states ->
                    val detail = states[topicId] ?: return@update states
                    states + (topicId to detail.copy(
                        posts = detail.posts.map { post ->
                            if (post.id != postId) post
                            else post.copy(
                                polls = if (post.polls.any { it.name == pollName }) {
                                    post.polls.map { if (it.name == pollName) updatedPoll else it }
                                } else post.polls + updatedPoll,
                                pollsVotes = post.pollsVotes + (pollName to optionIds.distinct()),
                            )
                        },
                        reactionErrors = detail.reactionErrors - postId,
                    ))
                }
                updateCachedTopicDetail(topicId, refreshTtl = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states ->
                    states[topicId]?.let { detail ->
                        states + (topicId to detail.copy(
                            reactionErrors = detail.reactionErrors + (postId to (error.message ?: "投票失败")),
                        ))
                    } ?: states
                }
                handlePageFailure(error) { refreshPost(topicId, postId) }
            } finally {
                _details.update { states ->
                    states[topicId]?.let { detail ->
                        states + (topicId to detail.copy(reactingPosts = detail.reactingPosts - postId))
                    } ?: states
                }
            }
        }
    }

    private fun applyEditedPost(topicId: Long, post: Post) {
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            states + (topicId to current.copy(
                // 编辑只改内容。响应里缺字段会被反序列化成默认值,整条替换会清空点赞/简评,
                // 更糟的是 reply_to_post_number 变 null 会把这条回复抬成根节点、打乱树形排列,
                // 所以除 cooked 之外的既有状态一律保留。
                posts = current.posts.map { existing ->
                    if (existing.id != post.id) existing
                    else post.copy(
                        postNumber = existing.postNumber,
                        replyToPostNumber = existing.replyToPostNumber,
                        replyCount = existing.replyCount,
                        boosts = existing.boosts, reactions = existing.reactions,
                        currentUserReaction = existing.currentUserReaction, canBoost = existing.canBoost,
                        acceptedAnswer = existing.acceptedAnswer, canAcceptAnswer = existing.canAcceptAnswer,
                        canUnacceptAnswer = existing.canUnacceptAnswer,
                        avatarTemplate = post.avatarTemplate ?: existing.avatarTemplate,
                        userId = post.userId ?: existing.userId,
                        polls = post.polls.ifEmpty { existing.polls },
                        pollsVotes = post.pollsVotes.ifEmpty { existing.pollsVotes },
                        username = post.username.ifBlank { existing.username },
                        createdAt = post.createdAt ?: existing.createdAt,
                        yours = existing.yours,
                        canEdit = existing.canEdit,
                        canDelete = existing.canDelete,
                        bookmarked = existing.bookmarked,
                        bookmarkId = existing.bookmarkId,
                    )
                },
            ))
        }
    }

    /** 删除楼层。#1 楼会连带删除整个话题,由界面负责在确认框里说清楚。 */
    fun deletePost(topicId: Long, postId: Long) {
        val current = _details.value[topicId] ?: return
        if (current.loading || current.reactingPosts.isNotEmpty()) return
        if (current.posts.none { it.id == postId && it.canDelete }) return
        pageJobs["delete:$postId"] = viewModelScope.launch {
            _details.update { it + (topicId to current.copy(reactingPosts = setOf(postId),
                reactionErrors = current.reactionErrors - postId)) }
            try {
                repository.deletePost(postId)
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    val removed = latest.posts.firstOrNull { it.id == postId }
                    val topic = latest.topic?.let { topic ->
                        topic.copy(postStream = topic.postStream.copy(
                            stream = topic.postStream.stream.filterNot { it == postId }))
                    }
                    states + (topicId to latest.copy(
                        topic = topic,
                        posts = latest.posts.filterNot { it.id == postId }.map {
                            if (it.postNumber == removed?.replyToPostNumber && it.postNumber > 1) {
                                it.copy(replyCount = (it.replyCount - 1).coerceAtLeast(0))
                            } else it
                        },
                        consumedIds = latest.consumedIds - postId,
                        windowStartId = if (latest.windowStartId == postId) {
                            latest.posts.firstOrNull { it.id != postId }?.id
                        } else latest.windowStartId,
                    ))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(reactionErrors = it.reactionErrors + (postId to (error.message ?: "删除失败"))))
                } ?: states }
                handlePageFailure(error) { loadTopic(topicId, retry = true) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(reactingPosts = emptySet())) } ?: states }
            }
        }
    }

    fun setNoticeMessage(topicId: Long, message: String) {
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            states + (topicId to current.copy(noticeMessage = message))
        }
        viewModelScope.launch {
            delay(3500)
            _details.update { states ->
                val current = states[topicId] ?: return@update states
                if (current.noticeMessage == message) {
                    states + (topicId to current.copy(noticeMessage = null))
                } else states
            }
        }
    }

    fun toggleBookmark(topicId: Long, post: Post) {
        val snapshot = _details.value[topicId] ?: return
        val original = snapshot.posts.firstOrNull { it.id == post.id } ?: return
        if (_state.value.username == null || _composer.value.submitting) return
        if (original.bookmarked && (original.bookmarkId ?: 0L) <= 0L) {
            _details.update { states -> states[topicId]?.let {
                states + (topicId to it.copy(reactionErrors = it.reactionErrors +
                    (post.id to "缺少书签信息，请刷新楼层后再取消收藏")))
            } ?: states }
            return
        }
        val newBookmarked = !original.bookmarked
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            states + (topicId to current.copy(
                posts = current.posts.map { if (it.id == post.id) it.copy(bookmarked = newBookmarked) else it },
                reactionErrors = current.reactionErrors - post.id,
            ))
        }
        updateCachedTopicDetail(topicId, refreshTtl = false)
        pageJobs["bookmark:${post.id}"] = viewModelScope.launch {
            var confirmed = false
            try {
                val bookmarkId = if (newBookmarked) {
                    repository.bookmarkPost(post.id)
                } else {
                    repository.unbookmarkPost(requireNotNull(original.bookmarkId))
                    null
                }
                _details.update { states -> states[topicId]?.let { current ->
                    states + (topicId to current.copy(posts = current.posts.map {
                        if (it.id == post.id) it.copy(bookmarked = newBookmarked, bookmarkId = bookmarkId) else it
                    }))
                } ?: states }
                updateCachedTopicDetail(topicId, refreshTtl = false)
                confirmed = true
                setNoticeMessage(topicId, if (newBookmarked) "已添加到书签" else "已从书签移除")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(reactionErrors = it.reactionErrors +
                        (post.id to (error.message ?: "书签操作失败"))))
                } ?: states }
                handlePageFailure(error) {}
            } finally {
                if (!confirmed) {
                    _details.update { states ->
                        val current = states[topicId] ?: return@update states
                        states + (topicId to current.copy(
                            posts = current.posts.map {
                                if (it.id == post.id) it.copy(bookmarked = original.bookmarked, bookmarkId = original.bookmarkId) else it
                            },
                        ))
                    }
                    updateCachedTopicDetail(topicId, refreshTtl = false)
                }
            }
        }
    }

    fun openFlagDialog(topicId: Long, post: Post) {
        val username = _state.value.username ?: return
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            val latest = current.posts.firstOrNull { it.id == post.id } ?: return@update states
            if (latest.yours || latest.username.equals(username, true) || post.id in current.flaggedPosts ||
                current.reactingPosts.isNotEmpty()) return@update states
            states + (topicId to current.copy(flagDialogPost = latest, flagError = null))
        }
    }

    fun closeFlagDialog(topicId: Long) {
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            if (current.flagDialogPost?.id in current.reactingPosts) return@update states
            states + (topicId to current.copy(flagDialogPost = null, flagError = null))
        }
    }

    fun submitFlag(topicId: Long, postId: Long, reasonId: Int, message: String? = null) {
        val current = _details.value[topicId] ?: return
        val post = current.posts.firstOrNull { it.id == postId } ?: return
        val username = _state.value.username ?: return
        if (current.flagDialogPost?.id != postId || current.reactingPosts.isNotEmpty() ||
            post.yours || post.username.equals(username, true) || postId in current.flaggedPosts) return
        val reason = PostFlagReason.entries.firstOrNull { it.id == reasonId } ?: return
        if (reason.requiresMessage && message.isNullOrBlank()) return
        _details.update { states -> states[topicId]?.let {
            states + (topicId to it.copy(reactingPosts = it.reactingPosts + postId, flagError = null))
        } ?: states }
        pageJobs["flag:$postId"] = viewModelScope.launch {
            try {
                repository.flagPost(postId, reasonId, message)
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(flagDialogPost = null, flaggedPosts = it.flaggedPosts + postId))
                } ?: states }
                setNoticeMessage(topicId, "举报已提交，感谢您的反馈")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(flagError = error.message ?: "举报提交失败，请稍后再试"))
                } ?: states }
                handlePageFailure(error) {}
            } finally {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(reactingPosts = it.reactingPosts - postId))
                } ?: states }
            }
        }
    }

    fun setTopicNotificationLevel(topicId: Long, level: TopicNotificationLevel) {
        if (_state.value.username == null || pageJobs["notification:$topicId"]?.isActive == true) return
        val detailLevel = _details.value[topicId]?.topic?.details?.notificationLevel
        val snapshot = NotificationLevelSnapshot(detailLevel, _state.value.homeFeed, _state.value.latest, _feedStates.value,
            _categoryTopics.value, _userTopicStates.value, _search.value.results)
        val items = _feedStates.value.values.flatMap { it.items } + _state.value.latest.items +
            _categoryTopics.value.values.flatMap { it.items } + _userTopicStates.value.values.flatMap { it.items } +
            _search.value.results.items
        val previousLevel = store.getTopicNotificationLevel(topicId)?.let(TopicNotificationLevel::fromCode)
            ?: items.firstOrNull { it.topic.id == topicId }?.notificationLevel
        if (previousLevel == level && (detailLevel == null || detailLevel == level.code) &&
            items.filter { it.topic.id == topicId }.all { it.notificationLevel == level }) return
        updateNotificationLevel(topicId, level, changing = true)
        pageJobs["notification:$topicId"] = viewModelScope.launch {
            var confirmed = false
            try {
                repository.setTopicNotificationLevel(topicId, level.code)
                confirmed = true
                setNoticeMessage(topicId, "通知级别已设为「${level.title}」")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(getApplication(), error.message ?: "通知级别设置失败，已恢复原状态", Toast.LENGTH_LONG).show()
                handlePageFailure(error) {}
            } finally {
                if (confirmed) {
                    updateNotificationLevel(topicId, level, changing = false)
                    if (level == TopicNotificationLevel.Muted) removeMutedTopicFromLatest(topicId)
                } else {
                    restoreNotificationLevel(topicId, snapshot)
                }
            }
        }
    }

    private data class NotificationLevelSnapshot(
        val detailLevel: Int?,
        val homeFeed: HomeFeedType,
        val latest: TopicListUiState,
        val feeds: Map<HomeFeedType, TopicListUiState>,
        val categories: Map<Int, TopicListUiState>,
        val userTopics: Map<String, TopicListUiState>,
        val search: TopicListUiState,
    )

    private fun restoreNotificationLevel(topicId: Long, snapshot: NotificationLevelSnapshot) {
        fun restoreList(current: TopicListUiState, previous: TopicListUiState?): TopicListUiState {
            fun restoreItems(items: List<TopicListItem>, originals: List<TopicListItem>): List<TopicListItem> {
                val original = originals.firstOrNull { it.topic.id == topicId }
                return items.map { item ->
                    if (item.topic.id != topicId || !item.notificationLevelUpdating) item
                    else item.copy(
                        topic = item.topic.copy(notificationLevel = original?.topic?.notificationLevel),
                        notificationLevel = original?.notificationLevel,
                        notificationLevelUpdating = false,
                    )
                }
            }
            return current.copy(
                items = restoreItems(current.items, previous?.items.orEmpty()),
                pendingPage = current.pendingPage?.let { page ->
                    page.copy(items = restoreItems(page.items, previous?.pendingPage?.items.orEmpty()))
                },
            )
        }
        _details.update { states -> states[topicId]?.let { current ->
            states + (topicId to current.copy(
                topic = current.topic?.let { it.copy(details = it.details.copy(notificationLevel = snapshot.detailLevel ?: 1)) },
                notificationLevelUpdating = false,
            ))
        } ?: states }
        _feedStates.update { feeds -> feeds.mapValues { restoreList(it.value, snapshot.feeds[it.key]) } }
        _state.update { current -> current.copy(latest = restoreList(current.latest,
            if (current.homeFeed == snapshot.homeFeed) snapshot.latest else snapshot.feeds[current.homeFeed])) }
        _categoryTopics.update { categories -> categories.mapValues { restoreList(it.value, snapshot.categories[it.key]) } }
        _userTopicStates.update { users -> users.mapValues { restoreList(it.value, snapshot.userTopics[it.key]) } }
        _search.update { it.copy(results = restoreList(it.results, snapshot.search)) }
    }

    private fun updateNotificationLevel(
        topicId: Long,
        level: TopicNotificationLevel?,
        changing: Boolean,
        detailLevel: Int? = level?.code,
    ) {
        fun updateList(list: TopicListUiState): TopicListUiState {
            fun updateItems(items: List<TopicListItem>) = items.map { item ->
                if (item.topic.id == topicId) item.copy(
                    topic = item.topic.copy(notificationLevel = level?.code),
                    notificationLevel = level,
                    notificationLevelUpdating = changing,
                ) else item
            }
            return list.copy(items = updateItems(list.items),
                pendingPage = list.pendingPage?.let { it.copy(items = updateItems(it.items)) })
        }
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            val currentTopic = current.topic ?: return@update states
            val updatedTopic = currentTopic.copy(
                details = currentTopic.details.copy(notificationLevel = detailLevel ?: 1)
            )
            states + (topicId to current.copy(topic = updatedTopic, notificationLevelUpdating = changing))
        }
        _feedStates.update { feeds -> feeds.mapValues { updateList(it.value) } }
        _state.update { it.copy(latest = updateList(it.latest)) }
        _categoryTopics.update { categories -> categories.mapValues { updateList(it.value) } }
        _userTopicStates.update { users -> users.mapValues { updateList(it.value) } }
        _search.update { it.copy(results = updateList(it.results)) }
    }

    private fun removeMutedTopicFromLatest(topicId: Long) {
        fun removeTopic(list: TopicListUiState) = list.copy(
            items = list.items.filterNot { it.topic.id == topicId },
            pendingPage = list.pendingPage?.let { page -> page.copy(items = page.items.filterNot { it.topic.id == topicId }) },
            incomingTopicIds = list.incomingTopicIds - topicId,
            highlightedTopicIds = list.highlightedTopicIds - topicId,
        )
        _feedStates.update { feeds -> feeds[HomeFeedType.Latest]?.let {
            feeds + (HomeFeedType.Latest to removeTopic(it))
        } ?: feeds }
        if (_state.value.homeFeed == HomeFeedType.Latest) _state.update { it.copy(latest = removeTopic(it.latest)) }
    }

    /** 回到 1 楼。窗口化加载下 1 楼可能还没取回来,这时从头重拉整个话题。 */
    fun reloadFromStart(topicId: Long) {
        val current = _details.value[topicId] ?: return
        if (current.loading) return
        pageJobs["topic:$topicId"]?.cancel()
        topicDetailCache.remove(topicId)
        _details.update { it - topicId }
        loadTopic(topicId)
    }

    /** 快速跳转到指定楼层（抢占式单飞请求，自动取消旧在途任务，杜绝并发与 CF 风控）。 */
    fun jumpToFloor(topicId: Long, postNumber: Int) {
        val current = _details.value[topicId] ?: return
        pageJobs["topic:$topicId"]?.cancel()
        loadTopic(topicId, postNumber = postNumber)
    }

    fun loadReplies(topicId: Long, postId: Long) {
        val current = _details.value[topicId] ?: return
        if (postId in current.loadingReplies || current.reactingPosts.isNotEmpty()) return
        val tree = threadRows(current.posts)
        val matchedNode = tree.firstOrNull { it.post.id == postId }
        val rootPostId = matchedNode?.rootPostId ?: postId
        val rootNode = tree.firstOrNull { it.post.id == rootPostId }
        val pendingSubtreeIds = rootNode?.unloadedCandidateIds?.filterNot { it in current.completedReplies }.orEmpty()
        if (postId in current.completedReplies && pendingSubtreeIds.isEmpty()) return

        val activeLoadingIds = mutableSetOf(postId, rootPostId)
        pageJobs["replies:$topicId:$rootPostId"] = viewModelScope.launch {
            _details.update { states ->
                states[topicId]?.let { states + (topicId to it.copy(loadingReplies = it.loadingReplies + activeLoadingIds, error = null)) } ?: states
            }
            try {
                var iterations = 0
                val maxIterations = 30
                while (isActive && iterations < maxIterations) {
                    iterations++
                    val snapshot = _details.value[topicId] ?: break
                    val currentTree = threadRows(snapshot.posts)
                    val currentRootRow = currentTree.firstOrNull { it.post.id == rootPostId }
                    val nextCandidateId = if (postId !in snapshot.completedReplies) {
                        postId
                    } else {
                        currentRootRow?.unloadedCandidateIds?.firstOrNull { it !in snapshot.completedReplies }
                    } ?: break

                    if (nextCandidateId !in activeLoadingIds) {
                        activeLoadingIds.add(nextCandidateId)
                        _details.update { states ->
                            states[topicId]?.let { states + (topicId to it.copy(loadingReplies = it.loadingReplies + nextCandidateId)) } ?: states
                        }
                    }

                    val after = snapshot.replyCursors[nextCandidateId] ?: 1
                    val replies = repository.fetchReplies(nextCandidateId, after)
                    val cursor = replies.maxOfOrNull { it.postNumber }

                    _details.update { states ->
                        val latest = states[topicId] ?: return@update states
                        val parent = latest.posts.firstOrNull { it.id == nextCandidateId }
                        val parentNumber = parent?.postNumber
                        val normalizedReplies = replies.map { reply ->
                            if (parentNumber != null && reply.postNumber > parentNumber) {
                                reply.copy(replyToPostNumber = parentNumber)
                            } else {
                                reply
                            }
                        }
                        val replyById = normalizedReplies.associateBy { it.id }
                        val patchedExisting = latest.posts.map { existing ->
                            val fetched = replyById[existing.id] ?: return@map existing
                            val targetParent = fetched.effectiveReplyToPostNumber() ?: parentNumber
                            val existingParent = existing.effectiveReplyToPostNumber()
                            val hasLoadedParent = existingParent != null && latest.posts.any { it.postNumber == existingParent && it.postNumber < existing.postNumber }
                            if (!hasLoadedParent && targetParent != null && existing.postNumber > targetParent) {
                                existing.copy(replyToPostNumber = targetParent)
                            } else {
                                existing
                            }
                        }
                        val existingIds = patchedExisting.map { it.id }.toSet()
                        val mergedPosts = (patchedExisting + normalizedReplies.filterNot { it.id in existingIds }).sortedBy { it.postNumber }
                        val childCount = if (parentNumber != null) {
                            mergedPosts.count { it.postNumber > parentNumber && it.effectiveReplyToPostNumber() == parentNumber }
                        } else 0
                        val progressed = cursor != null && cursor > after
                        val isCandidateDone = replies.isEmpty() || replies.size < 20 || !progressed || (parent != null && childCount >= parent.replyCount)
                        states + (topicId to latest.copy(
                            posts = mergedPosts,
                            consumedIds = latest.consumedIds + replies.map { post -> post.id },
                            replyCursors = if (cursor == null) latest.replyCursors else latest.replyCursors + (nextCandidateId to cursor),
                            completedReplies = if (isCandidateDone) latest.completedReplies + nextCandidateId else latest.completedReplies,
                        ))
                    }

                    if (replies.isEmpty()) {
                        _details.update { states ->
                            states[topicId]?.let { states + (topicId to it.copy(completedReplies = it.completedReplies + nextCandidateId)) } ?: states
                        }
                    }
                }
                updateCachedTopicDetail(topicId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(error = error.message ?: "回复加载失败")) } ?: states }
                handlePageFailure(error) { loadReplies(topicId, postId) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(loadingReplies = it.loadingReplies - activeLoadingIds)) } ?: states }
            }
        }
    }

    fun saveCategoryOrder(ids: List<Long>) {
        store.saveCategoryOrder(ids)
        _categories.update { current ->
            current.copy(order = store.loadCategoryOrder())
        }
    }

    fun resetCategoryOrder() {
        store.resetCategoryOrder()
        _categories.update { it.copy(order = emptyList()) }
    }

    fun saveCustomSection(section: CustomSection) {
        val oldSection = _customSection.value
        val oldServerLinkIds = oldSection?.links?.mapNotNull { it.serverId }.orEmpty()
        store.saveCustomSection(section)
        _customSection.value = section

        // 异步向 Discourse 服务端同步（打通 PC 端）
        viewModelScope.launch {
            try {
                val updated = repository.saveCustomSidebarSection(section, oldServerLinkIds)
                store.saveCustomSection(updated)
                _customSection.value = updated
            } catch (e: Exception) {
                diagnostics.log("同步自定义版块至服务端失败: ${e.message}")
            }
        }
    }

    fun deleteCustomSection() {
        val oldSection = _customSection.value
        val serverId = oldSection?.serverId
        store.saveCustomSection(null)
        _customSection.value = null

        // 异步从 Discourse 服务端删除（打通 PC 端）
        if (serverId != null && serverId > 0L) {
            viewModelScope.launch {
                try {
                    repository.deleteCustomSidebarSection(serverId)
                } catch (e: Exception) {
                    diagnostics.log("服务端删除自定义版块失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 与服务端同步自定义版块（与 PC 端打通）：
     * 若 PC 端有配置自定义版块，自动拉取下来；
     * 若 PC 端已删除，且本地版块之前绑定过 serverId，则同步清理本地。
     */
    fun syncCustomSectionFromServer() {
        viewModelScope.launch {
            try {
                val remoteSection = repository.fetchCustomSidebarSection()
                if (remoteSection != null) {
                    store.saveCustomSection(remoteSection)
                    _customSection.value = remoteSection
                } else {
                    val current = _customSection.value
                    if (current?.serverId != null && current.serverId > 0L) {
                        store.saveCustomSection(null)
                        _customSection.value = null
                    }
                }
            } catch (e: Exception) {
                diagnostics.log("同步远端自定义版块失败: ${e.message}")
            }
        }
    }

    fun loadCategories(retry: Boolean = false) {
        syncCustomSectionFromServer()
        val current = _categories.value
        if (current.loading || (current.loaded && !retry)) return
        pageJobs["categories"] = viewModelScope.launch {
            _categories.value = current.copy(loading = true, error = null)
            try {
                _categories.value = CategoriesUiState(items = repository.fetchCategories(), loaded = true,
                    order = store.loadCategoryOrder())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _categories.value = current.copy(error = error.message ?: "加载失败")
                handlePageFailure(error) { loadCategories(retry = true) }
            } finally {
                _categories.update { it.copy(loading = false) }
            }
        }
    }

    private fun mapTopicLists(transform: (TopicListUiState) -> TopicListUiState) {
        _feedStates.update { lists -> lists.mapValues { transform(it.value) } }
        _categoryTopics.update { lists -> lists.mapValues { transform(it.value) } }
        _tagTopics.update { lists -> lists.mapValues { transform(it.value) } }
        _userTopicStates.update { lists -> lists.mapValues { transform(it.value) } }
        _state.update { it.copy(latest = transform(it.latest)) }
        _search.update { it.copy(results = transform(it.results)) }
    }

    private fun updateListCounters(fresh: List<TopicListItem>) {
        val byId = fresh.associate { it.topic.id to it.topic }
        syncSavedTopicTotals(byId.mapValues { it.value.postsCount })
        mapTopicLists { list ->
            list.copy(items = mergeTopicCounters(list.items, byId),
                pendingPage = list.pendingPage?.let { it.copy(items = mergeTopicCounters(it.items, byId)) })
        }
    }

    private fun updateListPostCount(topicId: Long, count: Int, highestPostNumber: Int? = null) {
        syncSavedTopicTotals(mapOf(topicId to count))
        fun update(items: List<TopicListItem>) = items.map { item ->
            if (item.topic.id != topicId) item else item.copy(topic = item.topic.copy(postsCount = count,
                highestPostNumber = highestPostNumber ?: item.topic.highestPostNumber.takeIf { count <= item.topic.postsCount }))
        }
        mapTopicLists { list -> list.copy(items = update(list.items),
            pendingPage = list.pendingPage?.let { it.copy(items = update(it.items)) }) }
    }

    private suspend fun probeIncomingTopics(feed: HomeFeedType, categoryId: Int? = null, allowInDetail: Boolean = false) {
        val accountGeneration = accountScopeGeneration
        fun canProbe(): Boolean = accountGeneration == accountScopeGeneration && if (allowInDetail) {
            !store.sessionChanging && !_accounts.value.switching
        } else {
            canProbeIncomingTopics()
        }
        if (!canProbe() || !store.isAutoRefreshIncomingEnabled() || repository.isRateLimitCoolingDown()) return
        if (repository.isSilentPrefetchBackingOff() || store.shouldPauseBackgroundForChallengedClearance()) return
        val probeGen = ++incomingProbeGeneration
        if (!canProbe() || probeGen != incomingProbeGeneration) return
        if (repository.isRateLimitCoolingDown()) return
        scrollBusySignal.awaitIdle()
        if (!canProbe() || probeGen != incomingProbeGeneration) return
        if (repository.isSilentPrefetchBackingOff() || store.shouldPauseBackgroundForChallengedClearance()) return
        // 等滚动停下后再申请准入，前台让行窗口才能看到用户刚触发的请求
        if (!ApiQuotaPool.INCOMING_PROBE.tryAcquire()) return
        fun checkBeforeDispatch() {
            check(canProbe() && probeGen == incomingProbeGeneration && store.isAutoRefreshIncomingEnabled() &&
                !repository.isRateLimitCoolingDown() && !repository.isSilentPrefetchBackingOff() &&
                !store.shouldPauseBackgroundForChallengedClearance()) { "后台新话题探测已暂停" }
        }
        try {
            if (categoryId != null) {
                val currentCategoryState = _categoryTopics.value[categoryId] ?: return
                if (currentCategoryState.items.isEmpty()) return
                val category = _categories.value.items.firstOrNull { it.id == categoryId }
                val slug = category?.slug ?: activeCategory?.takeIf { it.first == categoryId }?.second ?: "c"
                val sortOrder = _topicSortOrder.value.apiValue
                val sortAscending = _topicSortAscending.value.takeIf { sortOrder != null }
                val freshPage = repository.fetchCategoryTopics(
                    slug, categoryId, 0, present = false, order = sortOrder, ascending = sortAscending,
                    beforeDispatch = ::checkBeforeDispatch,
                )
                ApiQuotaPool.INCOMING_PROBE.onSuccess()
                if (!canProbe() || probeGen != incomingProbeGeneration) return
                updateListCounters(freshPage.items)
                val latestCategoryState = _categoryTopics.value[categoryId] ?: return
                val latestIds = latestCategoryState.items.map { it.topic.id }.toSet()
                // 旧列表首个非置顶话题在新鲜列表中的位置；排在它之前的旧帖视为被新回复顶上来的
                val firstOldNonPinnedId = latestCategoryState.items.firstOrNull { !it.topic.pinned }?.topic?.id
                val firstOldIdx = if (firstOldNonPinnedId != null) freshPage.items.indexOfFirst { it.topic.id == firstOldNonPinnedId } else -1
                val incoming = freshPage.items.filterIndexed { idx, item ->
                    item.topic.id !in latestIds || (firstOldIdx > 0 && idx < firstOldIdx && item.topic.id in latestIds)
                }
                if (incoming.isNotEmpty()) {
                    val incomingIds = incoming.map { it.topic.id }.toSet()
                    // 1. 先从本地持久库快速载入已有缓存
                    ExcerptCache.loadBatchFromStore(freshPage.items.map { it.topic.id })
                    _categoryTopics.update { current ->
                        val existing = current[categoryId] ?: return@update current
                        current + (categoryId to existing.copy(
                            pendingPage = freshPage,
                            incomingTopicIds = incomingIds,
                        ))
                    }
                }
            } else {
                val currentFeedState = _feedStates.value[feed] ?: return
                if (currentFeedState.items.isEmpty()) return
                val sortOrder = _topicSortOrder.value.apiValue
                val sortAscending = _topicSortAscending.value.takeIf { sortOrder != null }
                val freshPage = repository.fetchFeed(
                    feed, 0, awaitCategories = false, present = false, order = sortOrder, ascending = sortAscending,
                    beforeDispatch = ::checkBeforeDispatch,
                )
                ApiQuotaPool.INCOMING_PROBE.onSuccess()
                if (!canProbe() || probeGen != incomingProbeGeneration) return
                updateListCounters(freshPage.items)
                val latestFeedState = _feedStates.value[feed] ?: return
                val latestIds = latestFeedState.items.map { it.topic.id }.toSet()
                // 旧列表首个非置顶话题在新鲜列表中的位置；排在它之前的旧帖视为被新回复顶上来的
                val firstOldNonPinnedId = latestFeedState.items.firstOrNull { !it.topic.pinned }?.topic?.id
                val firstOldIdx = if (firstOldNonPinnedId != null) freshPage.items.indexOfFirst { it.topic.id == firstOldNonPinnedId } else -1
                val incoming = freshPage.items.filterIndexed { idx, item ->
                    item.topic.id !in latestIds || (firstOldIdx > 0 && idx < firstOldIdx && item.topic.id in latestIds)
                }
                if (incoming.isNotEmpty()) {
                    val incomingIds = incoming.map { it.topic.id }.toSet()
                    // 1. 先从本地持久库快速载入已有缓存
                    ExcerptCache.loadBatchFromStore(freshPage.items.map { it.topic.id })
                    _feedStates.update { current ->
                        val existing = current[feed] ?: return@update current
                        current + (feed to existing.copy(
                            pendingPage = freshPage,
                            incomingTopicIds = incomingIds,
                        ))
                    }
                    if (_state.value.homeFeed == feed) {
                        _state.update { currentUi ->
                            currentUi.copy(
                                latest = currentUi.latest.copy(
                                    pendingPage = freshPage,
                                    incomingTopicIds = incomingIds,
                                )
                            )
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 不弹窗，但撞盾要进入退避，否则下一轮探测照样去撞
            repository.enterSilentPrefetchBackoff(error)
        }
    }

    fun applyIncomingTopics(feed: HomeFeedType, categoryId: Int? = null) {
        incomingProbeGeneration++
        if (categoryId != null) {
            val current = _categoryTopics.value[categoryId] ?: return
            val pending = current.pendingPage
            if (pending == null) {
                if (current.incomingTopicIds.isNotEmpty()) {
                    _categoryTopics.update { map ->
                        map + (categoryId to current.copy(incomingTopicIds = emptySet()))
                    }
                }
                return
            }
            ExcerptCache.loadBatchFromStore(pending.items.map { it.topic.id })
            val newItems = (pending.items + current.items).distinctBy { it.topic.id }
            val refreshedIds = pending.items.map { it.topic.id }.toSet()
            val highlightJobKey = "incoming-highlight:category:$categoryId"
            pageJobs[highlightJobKey]?.cancel()
            _categoryTopics.update { map ->
                map + (categoryId to current.copy(
                    items = newItems,
                    pendingPage = null,
                    incomingTopicIds = emptySet(),
                    highlightedTopicIds = refreshedIds,
                ))
            }
            pageJobs[highlightJobKey] = viewModelScope.launch {
                delay(2500)
                _categoryTopics.update { map ->
                    val c = map[categoryId] ?: return@update map
                    map + (categoryId to c.copy(highlightedTopicIds = emptySet()))
                }
            }
        } else {
            val current = _feedStates.value[feed] ?: _state.value.latest
            val pending = current.pendingPage
            if (pending == null) {
                if (current.incomingTopicIds.isNotEmpty()) {
                    val updated = current.copy(incomingTopicIds = emptySet())
                    _feedStates.update { it + (feed to updated) }
                    if (_state.value.homeFeed == feed) {
                        _state.update { it.copy(latest = updated) }
                    }
                }
                return
            }
            ExcerptCache.loadBatchFromStore(pending.items.map { it.topic.id })
            val newItems = (pending.items + current.items).distinctBy { it.topic.id }
            // 高亮表示本轮刷新覆盖的整批话题，不能复用仅用于提示计数的“新动态”子集。
            val refreshedIds = pending.items.map { it.topic.id }.toSet()
            val highlightJobKey = "incoming-highlight:feed:${feed.name}"
            pageJobs[highlightJobKey]?.cancel()
            val updatedState = current.copy(
                items = newItems,
                pendingPage = null,
                incomingTopicIds = emptySet(),
                highlightedTopicIds = refreshedIds,
            )
            _feedStates.update { it + (feed to updatedState) }
            if (_state.value.homeFeed == feed) {
                _state.update { it.copy(latest = updatedState) }
            }
            pageJobs[highlightJobKey] = viewModelScope.launch {
                delay(2500)
                _feedStates.update { currentMap ->
                    val f = currentMap[feed] ?: return@update currentMap
                    currentMap + (feed to f.copy(highlightedTopicIds = emptySet()))
                }
                if (_state.value.homeFeed == feed) {
                    _state.update { currentUi ->
                        currentUi.copy(latest = currentUi.latest.copy(highlightedTopicIds = emptySet()))
                    }
                }
            }
        }
    }

    fun setTopicSort(order: TopicSortOrder, currentCategoryId: Int? = null, currentCategorySlug: String? = null) {
        val currentOrder = _topicSortOrder.value
        val currentAscending = _topicSortAscending.value
        val (newOrder, newAscending) = if (order != TopicSortOrder.Default && order == currentOrder) {
            order to !currentAscending
        } else {
            order to false
        }
        if (newOrder == currentOrder && newAscending == currentAscending) return
        _topicSortOrder.value = newOrder
        _topicSortAscending.value = newAscending
        store.setTopicSortOrder(newOrder)
        store.setTopicSortAscending(newAscending)

        pageJobs.filter { it.key.startsWith("category:") || it.key.startsWith("feed:") || it.key.startsWith("bookmark:") || it.key.startsWith("created:") }.values.forEach { it.cancel() }
        _feedStates.value = emptyMap()
        _categoryTopics.value = emptyMap()
        _userTopicStates.value = emptyMap()
        if (currentCategoryId != null && !currentCategorySlug.isNullOrBlank()) {
            loadCategory(currentCategoryId, currentCategorySlug, refresh = true)
        }
        refreshLatest()
    }

    fun setFloatingBallEnabled(enabled: Boolean) {
        _floatingBallEnabled.value = enabled
        store.setFloatingBallEnabled(enabled)
    }

    fun setAutoReadClipboardEnabled(enabled: Boolean) {
        _autoReadClipboardEnabled.value = enabled
        store.setAutoReadClipboardEnabled(enabled)
    }

    fun dismissClipboardTarget(context: Context, expectedUrl: String) {
        consumeClipboardTarget(context, expectedUrl)
    }

    private fun clearClipboardTarget() {
        _detectedClipboardTarget.value = null
        detectedClipboardText = null
    }

    fun consumeClipboardTarget(context: Context, expectedUrl: String): org.linuxdo.android.data.InternalTopicLink? {
        val current = _detectedClipboardTarget.value?.takeIf { it.first == expectedUrl } ?: return null
        val snapshot = detectedClipboardText
        clearClipboardTarget()
        if (snapshot != null) markClipboardConsumed(context, snapshot)
        checkClipboard(context)
        return current.second
    }

    private fun focusedClipboard(context: Context): ClipboardManager? {
        var current = context
        while (current is ContextWrapper && current !is Activity) {
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        val activity = current as? Activity ?: return null
        if (!activity.hasWindowFocus() || activity.isFinishing || activity.isDestroyed) return null
        return activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private fun markClipboardConsumed(context: Context, snapshot: String) {
        val original = ClipboardConsumption.originalText(snapshot)
        val consumedHash = ClipboardConsumption.textHash(original)
        val markedText = original + ClipboardConsumption.MARKER
        // 先建立兜底回执；写入回调或读回失败时，也不会立即重复显示同一份内容。
        store.setClipboardConsumedText(consumedHash, markerVerified = false)
        try {
            val clipboard = focusedClipboard(context) ?: run {
                diagnostics.log("剪贴板消费标记未写入：窗口无焦点，使用本地抑制")
                return
            }
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount != 1) {
                diagnostics.log("剪贴板含多项内容，保留原内容并使用本地抑制")
                return
            }
            val item = clip.getItemAt(0)
            if (item.text == null || item.text is Spanned || item.htmlText != null ||
                item.uri != null || item.intent != null
            ) {
                diagnostics.log("剪贴板不是单项纯文本，保留原格式并使用本地抑制")
                return
            }
            val currentText = item.text.toString()
            if (currentText != snapshot && currentText != markedText) {
                store.setClipboardConsumedText("", markerVerified = false)
                diagnostics.log("剪贴板已换成其他内容，跳过旧卡片的消费标记")
                return
            }
            if (currentText != markedText) {
                // 保留原描述（含敏感内容标志），仅在用户处理卡片时追加尾缀。
                val description = ClipDescription(clip.description)
                clip.description.extras?.let { description.extras = PersistableBundle(it) }
                clipboard.setPrimaryClip(ClipData(description, ClipData.Item(markedText)))
            }
            val written = clipboard.primaryClip
            val verified = written != null && written.itemCount == 1 &&
                written.getItemAt(0).text?.toString() == markedText
            if (verified) store.setClipboardConsumedText(consumedHash, markerVerified = true)
            diagnostics.log(if (verified) "剪贴板消费标记已写入并读回确认" else "剪贴板消费标记未读回，使用本地抑制")
        } catch (_: Exception) {
            diagnostics.log("剪贴板消费标记写入或读回失败，使用本地抑制")
        }
    }

    fun checkClipboard(context: Context) {
        if (!_autoReadClipboardEnabled.value) return
        try {
            val clipboard = focusedClipboard(context) ?: return
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            val consumedHash = store.getClipboardConsumedTextHash()
            val markerVerified = store.isClipboardConsumptionMarkerVerified()
            if (ClipboardConsumption.shouldSuppress(text, consumedHash, markerVerified)) {
                if (!markerVerified && text.endsWith(ClipboardConsumption.MARKER)) {
                    store.setClipboardConsumedText(consumedHash, markerVerified = true)
                }
                clearClipboardTarget()
                return
            }
            if (!markerVerified && consumedHash.isNotEmpty() && text.isNotBlank()) {
                store.setClipboardConsumedText("", markerVerified = false)
            }
            val found = org.linuxdo.android.data.parseTopicLinkFromText(text)
            if (found == null) {
                clearClipboardTarget()
                return
            }
            // 没有已确认的消费尾缀，属于新内容；相同 URL 的干净原文也能再次提醒。
            detectedClipboardText = text
            _detectedClipboardTarget.value = found
        } catch (_: Exception) {
        }
    }

    fun getLastHandledClipboardUrl(): String = store.getLastHandledClipboardUrl()

    fun setLastHandledClipboardUrl(url: String) {
        store.setLastHandledClipboardUrl(url)
    }

    fun setFloatingBallActions(actions: List<FloatingAction>) {
        val valid = actions.distinct().take(5)
        _floatingBallActions.value = valid
        store.setFloatingBallActions(valid)
    }

    fun updateFloatingBallPosition(xRatio: Float, yRatio: Float) {
        _floatingBallXRatio.value = xRatio
        _floatingBallYRatio.value = yRatio
        _floatingBallOnRight.value = xRatio >= 0.5f
        store.setFloatingBallXRatio(xRatio)
        store.setFloatingBallYRatio(yRatio)
        store.setFloatingBallOnRight(xRatio >= 0.5f)
    }

    fun updateFloatingBallPosition(yRatio: Float, onRight: Boolean) {
        _floatingBallYRatio.value = yRatio
        _floatingBallOnRight.value = onRight
        val xRatio = if (onRight) 0.95f else 0.05f
        _floatingBallXRatio.value = xRatio
        store.setFloatingBallXRatio(xRatio)
        store.setFloatingBallYRatio(yRatio)
        store.setFloatingBallOnRight(onRight)
    }

    fun loadCategory(id: Int, slug: String, refresh: Boolean = false, more: Boolean = false) {
        if (refresh && !more) incomingProbeGeneration++
        val current = _categoryTopics.value[id]
        if (pageJobs["category:$id"]?.isActive == true) return
        if (!refresh && !more && current != null && !current.needsRefresh) return
        val previous = current ?: TopicListUiState()
        if (more && !previous.hasMore) return
        val page = if (more) previous.page + 1 else 0
        pageJobs["category:$id"] = viewModelScope.launch {
            _categoryTopics.update { it + (id to previous.copy(
                refreshing = refresh, loadingMore = more, initialLoading = previous.items.isEmpty(), error = null,
            )) }
            try {
                val sortOrder = _topicSortOrder.value.apiValue
                val sortAscending = _topicSortAscending.value.takeIf { sortOrder != null }
                val result = repository.fetchCategoryTopics(slug, id, page, order = sortOrder, ascending = sortAscending)
                ExcerptCache.loadBatchFromStore(result.items.map { it.topic.id })
                val items = if (more) (previous.items + result.items).distinctBy { it.topic.id } else result.items
                // 对齐通知分页：防死循环检测页码推进，而非条目增加。高频重叠由页码单调递增穿透。
                val hasMore = result.hasMore && result.items.isNotEmpty() && (!more || page > previous.page)
                _categoryTopics.update { it + (id to TopicListUiState(
                    items = items,
                    page = page,
                    hasMore = hasMore,
                    initialLoading = false,
                    pendingPage = if (page == 0) null else previous.pendingPage,
                    incomingTopicIds = if (page == 0) emptySet() else previous.incomingTopicIds,
                )) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _categoryTopics.update { it + (id to previous.copy(initialLoading = false, error = error.message ?: "加载失败")) }
                handlePageFailure(error) { loadCategory(id, slug, refresh = !more, more = more) }
            } finally {
                _categoryTopics.update { states -> states[id]?.let {
                    states + (id to it.copy(initialLoading = false, refreshing = false, loadingMore = false))
                } ?: states }
            }
        }
    }

    fun loadUserTopics(username: String, isBookmark: Boolean, refresh: Boolean = false, more: Boolean = false) {
        val key = "${if (isBookmark) "bookmark" else "created"}:$username"
        val current = _userTopicStates.value[key]
        if (pageJobs[key]?.isActive == true) return
        if (!refresh && !more && current != null && !current.needsRefresh) return
        val previous = current ?: TopicListUiState()
        if (more && !previous.hasMore) return
        val page = if (more) previous.page + 1 else 0
        pageJobs[key] = viewModelScope.launch {
            _userTopicStates.update { it + (key to previous.copy(
                refreshing = refresh, loadingMore = more, initialLoading = previous.items.isEmpty(), error = null,
            )) }
            try {
                val result = if (isBookmark) repository.fetchUserBookmarks(username, page) else repository.fetchUserCreatedTopics(username, page)
                val items = if (more) (previous.items + result.items).distinctBy { it.listKey } else result.items
                val hasMore = result.hasMore && result.items.isNotEmpty() && (!more || page > previous.page)
                _userTopicStates.update { it + (key to TopicListUiState(items, page,
                    hasMore, initialLoading = false)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _userTopicStates.update { it + (key to previous.copy(initialLoading = false, error = error.message ?: "加载失败")) }
                handlePageFailure(error) { loadUserTopics(username, isBookmark, refresh = !more, more = more) }
            } finally {
                _userTopicStates.update { states -> states[key]?.let {
                    states + (key to it.copy(initialLoading = false, refreshing = false, loadingMore = false))
                } ?: states }
            }
        }
    }

    fun loadUserReplies(username: String, refresh: Boolean = false, more: Boolean = false, filter: String = "5") {
        val stateKey = if (filter == "5") username else "$username:$filter"
        val current = _userRepliesStates.value[stateKey] ?: UserRepliesUiState()
        val key = "replies:$stateKey"
        if (pageJobs[key]?.isActive == true) return
        if (!refresh && !more && current.items.isNotEmpty()) return
        if (more && !current.hasMore) return
        val cursor = if (more) current.cursor else 0
        pageJobs[key] = viewModelScope.launch {
            _userRepliesStates.update {
                it + (stateKey to current.copy(
                    loading = true,
                    initialLoading = !more && (refresh || current.items.isEmpty()),
                    error = null,
                ))
            }
            try {
                val (newItems, hasMore) = repository.fetchUserActionItems(username, filter, cursor)
                val combined = if (more) (current.items + newItems).distinctBy { it.id } else newItems
                _userRepliesStates.update {
                    it + (stateKey to UserRepliesUiState(
                        items = combined,
                        loading = false,
                        initialLoading = false,
                        hasMore = hasMore,
                        cursor = cursor + 1,
                        error = null,
                    ))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _userRepliesStates.update {
                    it + (stateKey to current.copy(
                        loading = false,
                        initialLoading = false,
                        error = error.message ?: "加载列表失败",
                    ))
                }
            } finally {
                pageJobs.remove(key)
            }
        }
    }

fun openTopicSearch(topicId: Long, title: String) {
        topicSearchJob?.cancel()
        topicSearchSequence++
        _topicSearch.value = if (_topicSearch.value.topicId == topicId) _topicSearch.value.copy(open = true, title = title)
            else TopicSearchUiState(open = true, topicId = topicId, title = title, order = _search.value.order)
    }

    fun closeTopicSearch() {
        topicSearchJob?.cancel()
        topicSearchSequence++
        _topicSearch.update { it.copy(open = false, loading = false, loadingMore = false) }
    }

    fun openTopicSearchHit(hit: TopicSearchHit) {
        val search = _topicSearch.value
        if (!search.open || search.hits.none { it.postId == hit.postId }) return
        closeTopicSearch()
        loadTopic(search.topicId, postNumber = hit.postNumber,
            restoredPosition = TopicReadingPosition(postNumber = hit.postNumber, atTop = false,
                descending = _details.value[search.topicId]?.descending ?: false))
    }

    fun updateTopicSearch(query: String, order: SearchOrder = _topicSearch.value.order) {
        topicSearchJob?.cancel()
        val sequence = ++topicSearchSequence
        val current = _topicSearch.value
        if (!current.open) return
        _topicSearch.value = current.copy(query = query, order = order, hits = emptyList(), page = 0,
            hasMore = false, loading = query.isNotBlank(), loadingMore = false, error = null)
        if (query.isBlank()) return
        topicSearchJob = viewModelScope.launch {
            delay(500L)
            performTopicSearch(sequence, more = false)
        }
    }

    fun loadMoreTopicSearch() {
        if (topicSearchJob?.isActive == true) return
        val current = _topicSearch.value
        if (!current.open || !current.hasMore) return
        topicSearchJob = viewModelScope.launch { performTopicSearch(topicSearchSequence, more = true) }
    }

    private suspend fun performTopicSearch(sequence: Long, more: Boolean) {
        val current = _topicSearch.value
        val generation = accountScopeGeneration
        val page = if (more) current.page + 1 else 1
        _topicSearch.update { it.copy(loading = !more, loadingMore = more, error = null) }
        try {
            val result = repository.searchInTopic(current.topicId, current.query, page, current.order)
            if (sequence != topicSearchSequence || generation != accountScopeGeneration) return
            _topicSearch.update { it.copy(hits = (if (more) current.hits + result.hits else result.hits).distinctBy { hit -> hit.postId },
                page = page, hasMore = result.hasMore, loading = false, loadingMore = false) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (sequence != topicSearchSequence || generation != accountScopeGeneration) return
            _topicSearch.update { it.copy(loading = false, loadingMore = false, error = error.message ?: "帖内搜索失败") }
            handlePageFailure(error) { updateTopicSearch(current.query, current.order) }
        }
    }

    fun openTopicMetadataEditor(topicId: Long, preserveInput: Boolean = false) {
        if (_metadataEditor.value.saving || store.currentUserId() <= 0L) return
        metadataJob?.cancel()
        metadataTagJob?.cancel()
        val previous = _metadataEditor.value
        val pendingPatch = if (preserveInput && previous.topicId == topicId) previous.patch else null
        val sequence = ++metadataSequence
        val userId = store.currentUserId()
        val generation = store.sessionGeneration
        _metadataEditor.value = if (pendingPatch != null) previous.copy(loading = true, error = null, generation = generation)
            else TopicMetadataEditorUiState(open = true, topicId = topicId, userId = userId, generation = generation, loading = true)
        metadataJob = viewModelScope.launch {
            try {
                val original = repository.fetchTopicMetadata(topicId)
                if (sequence != metadataSequence) return@launch
                if (userId != store.currentUserId() || generation != store.sessionGeneration) {
                    _metadataEditor.update { it.copy(loading = false, original = it.original?.copy(canEdit = false),
                        error = "账号状态已变化，请重新核对编辑权限") }
                    return@launch
                }
                if (!original.canEdit) {
                    _metadataEditor.update { it.copy(original = original, loading = false, error = "当前账号无权编辑此话题") }
                    return@launch
                }
                val rules = repository.fetchTopicEditRules()
                if (sequence != metadataSequence) return@launch
                if (userId != store.currentUserId() || generation != store.sessionGeneration) {
                    _metadataEditor.update { it.copy(loading = false, original = it.original?.copy(canEdit = false),
                        error = "账号状态已变化，请重新核对编辑权限") }
                    return@launch
                }
                val pending = pendingPatch?.let { if (rules.canTagTopics) it else it.copy(tags = null) }?.applyTo(original) ?: original
                _metadataEditor.value = TopicMetadataEditorUiState(open = true, topicId = topicId, userId = userId,
                    generation = generation, original = original, rules = rules, title = pending.title,
                    categoryId = pending.categoryId, tags = pending.tags)
                if (!original.privateMessage && rules.canTagTopics) searchMetadataTags("")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (sequence != metadataSequence) return@launch
                _metadataEditor.update { it.copy(loading = false, error = error.message ?: "编辑信息加载失败") }
                handlePageFailure(error) { if (userId == store.currentUserId()) openTopicMetadataEditor(topicId, preserveInput = true) }
            }
        }
    }

    fun closeTopicMetadataEditor() {
        if (_metadataEditor.value.saving) return
        metadataSequence++
        metadataJob?.cancel()
        metadataTagJob?.cancel()
        _metadataEditor.value = TopicMetadataEditorUiState()
    }

    fun updateMetadataTitle(title: String) {
        _metadataEditor.update { if (it.saving || it.original?.canEdit != true) it else it.copy(title = title, error = null) }
    }

    fun updateMetadataCategory(id: Int) {
        val state = _metadataEditor.value
        if (state.saving || state.original?.canEdit != true || state.rules?.selectableCategories?.none { it.id == id } != false) return
        _metadataEditor.update { it.copy(categoryId = id, error = null) }
        if (state.rules?.canTagTopics == true) searchMetadataTags("")
    }

    fun updateMetadataTags(tags: List<String>) {
        val state = _metadataEditor.value
        if (state.saving || state.original?.canEdit != true || state.rules?.canTagTopics != true) return
        if (tags.any { it !in state.tags && state.tagOptions.none { option -> option.name == it } }) {
            _metadataEditor.update { it.copy(tagError = "请选择查询结果中的标签") }
            return
        }
        if (tags.size > 5) {
            _metadataEditor.update { it.copy(tagError = "最多选择 5 个标签") }
            return
        }
        _metadataEditor.update { it.copy(tags = tags.distinct(), error = null) }
        searchMetadataTags(state.tagQuery)
    }

    fun searchMetadataTags(query: String) {
        metadataTagJob?.cancel()
        val tagSequence = ++metadataTagSequence
        val sequence = metadataSequence
        val current = _metadataEditor.value
        if (!current.open || current.rules?.canTagTopics != true || current.original?.privateMessage == true) return
        _metadataEditor.update { it.copy(tagQuery = query, loadingTags = true, tagError = null) }
        metadataTagJob = viewModelScope.launch {
            delay(300L)
            try {
                val result = repository.searchTopicEditTags(current.categoryId, query, current.tags)
                if (sequence != metadataSequence || tagSequence != metadataTagSequence || current.userId != store.currentUserId()) return@launch
                _metadataEditor.update { it.copy(loadingTags = false, tagOptions = result.results,
                    requiredTagName = result.requiredTagName, requiredTagMin = result.requiredTagMinCount) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (sequence == metadataSequence && tagSequence == metadataTagSequence) _metadataEditor.update {
                    it.copy(loadingTags = false, tagOptions = emptyList(), tagError = error.message ?: "标签加载失败，请重试")
                }
            }
        }
    }

    fun submitTopicMetadata() {
        val current = _metadataEditor.value
        val original = current.original ?: return
        val rules = current.rules ?: return
        val patch = current.patch ?: return
        if (!current.canSave) return
        if (current.userId != store.currentUserId() || current.generation != store.sessionGeneration) {
            _metadataEditor.update { it.copy(error = "账号状态已变化，请重新打开编辑") }
            return
        }
        val sequence = metadataSequence
        val accountGeneration = accountScopeGeneration
        _metadataEditor.update { it.copy(saving = true, error = null) }
        metadataJob = viewModelScope.launch {
            try {
                val updated = repository.updateTopicMetadata(original, rules, patch, current.userId, current.generation)
                if (sequence != metadataSequence || accountGeneration != accountScopeGeneration) return@launch
                applyTopicMetadata(updated, rules, patch.categoryId != null || patch.tags != null, current.userId)
                _metadataEditor.value = TopicMetadataEditorUiState()
                Toast.makeText(getApplication(), "话题信息已保存", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (sequence != metadataSequence || accountGeneration != accountScopeGeneration) return@launch
                if (error is org.linuxdo.android.net.NetworkTimeoutException || (error is org.linuxdo.android.net.HttpStatusException && error.status >= 500)) {
                    val confirmed = try { repository.fetchTopicMetadata(current.topicId) }
                        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                    val expected = patch.applyTo(original)
                    val matches = confirmed != null && (patch.title == null || confirmed.title == expected.title) &&
                        (patch.categoryId == null || confirmed.categoryId == expected.categoryId) &&
                        (patch.tags == null || confirmed.tags.sorted() == expected.tags.sorted())
                    if (matches && sequence == metadataSequence && accountGeneration == accountScopeGeneration) {
                        applyTopicMetadata(confirmed!!, rules, patch.categoryId != null || patch.tags != null, current.userId)
                        _metadataEditor.value = TopicMetadataEditorUiState()
                        Toast.makeText(getApplication(), "已确认话题信息保存成功", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                if (sequence != metadataSequence || accountGeneration != accountScopeGeneration) return@launch
                if (error is org.linuxdo.android.net.HttpStatusException && error.status == 403) {
                    _details.update { states -> states[current.topicId]?.let { detail ->
                        states + (current.topicId to detail.copy(topic = detail.topic?.copy(details = detail.topic.details.copy(canEdit = false))))
                    } ?: states }
                    updateCachedTopicDetail(current.topicId, refreshTtl = false)
                }
                _metadataEditor.update { it.copy(saving = false,
                    original = if (error is org.linuxdo.android.net.HttpStatusException && error.status == 403) original.copy(canEdit = false) else original,
                    error = error.message ?: "保存失败，请核对后重试") }
                handlePageFailure(error) { if (current.userId == store.currentUserId()) openTopicMetadataEditor(current.topicId, preserveInput = true) }
            } finally {
                if (sequence == metadataSequence) _metadataEditor.update { it.copy(saving = false) }
            }
        }
    }

    private fun applyTopicMetadata(value: TopicMetadata, rules: TopicEditRules, membershipChanged: Boolean, userId: Long) {
        val category = rules.categories.firstOrNull { it.id == value.categoryId }
        fun patchList(list: TopicListUiState): TopicListUiState = list.copy(
            items = list.items.map { item -> if (item.topic.id != value.topicId) item else item.copy(
                topic = item.topic.copy(title = value.title, categoryId = value.categoryId, tags = value.tags), category = category) },
            needsRefresh = list.needsRefresh || membershipChanged,
        )
        _details.update { states -> states[value.topicId]?.let { detail ->
            states + (value.topicId to detail.copy(topic = detail.topic?.copy(title = value.title, categoryId = value.categoryId,
                tags = value.tags, details = detail.topic.details.copy(canEdit = value.canEdit)), category = category))
        } ?: states }
        updateCachedTopicDetail(value.topicId, refreshTtl = false)
        _state.update { it.copy(latest = patchList(it.latest)) }
        _feedStates.update { states -> states.mapValues { patchList(it.value) } }
        _categoryTopics.update { states -> states.mapValues { patchList(it.value) } }
        _tagTopics.update { states -> states.mapValues { patchList(it.value) } }
        _userTopicStates.update { states -> states.mapValues { patchList(it.value) } }
        _search.update { it.copy(results = patchList(it.results).copy(needsRefresh = true)) }
        _metadataUpdated.value = value
        val generation = accountScopeGeneration
        viewModelScope.launch {
            try {
                val saved = savedTopicWriteGate.withLock { withContext(Dispatchers.IO) {
                    container.historyStore.updateMetadata(userId, value.topicId, value.title, category?.name, category?.color)
                    savedTopicStore.updateMetadata(userId, value.topicId, value.title, category?.name, category?.color)
                } }
                if (generation == accountScopeGeneration && userId == store.currentUserId()) {
                    publishSavedTopics(saved)
                    _history.update { it.copy(entries = container.historyStore.entries(userId)) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { diagnostics.log("话题信息本地同步失败: ${error.message}") }
        }
    }

    fun removeBookmarkFromList(item: TopicListItem, onResult: (String) -> Unit) {
        val bookmarkId = item.bookmarkId ?: return
        val key = "remove-bookmark:$bookmarkId"
        if (pageJobs[key]?.isActive == true) return
        val generation = accountScopeGeneration
        val userId = store.currentUserId()
        val sessionGeneration = store.sessionGeneration
        pageJobs[key] = viewModelScope.launch {
            try {
                repository.unbookmarkPost(bookmarkId, userId, sessionGeneration)
                if (generation != accountScopeGeneration) return@launch
                _userTopicStates.update { states -> states.mapValues { (key, state) ->
                    if (key.startsWith("bookmark:")) state.copy(items = state.items.filterNot { it.bookmarkId == bookmarkId }) else state
                } }
                _details.update { states -> states[item.topic.id]?.let { detail ->
                    val posts = detail.posts.map { post -> if (post.bookmarkId == bookmarkId) post.copy(bookmarked = false, bookmarkId = null) else post }
                    states + (item.topic.id to detail.copy(posts = posts, topic = detail.topic?.copy(postStream = detail.topic.postStream.copy(posts = posts))))
                } ?: states }
                updateCachedTopicDetail(item.topic.id, refreshTtl = false)
                onResult("已取消这条书签")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == accountScopeGeneration) {
                    onResult(error.message ?: "取消书签失败")
                    handlePageFailure(error) { if (generation == accountScopeGeneration) removeBookmarkFromList(item, onResult) }
                }
            } finally { pageJobs.remove(key) }
        }
    }

    fun updateSearch(query: String) {
        searchJob?.cancel()
        searchSequence++
        val trimmed = query.trim()
        _search.value = SearchUiState(query, TopicListUiState(initialLoading = trimmed.isNotEmpty(), hasMore = false), order = _search.value.order)
        if (trimmed.isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(500)
            performSearch(trimmed, 1)
        }
    }

    fun setSearchOrder(order: SearchOrder) {
        if (_search.value.order == order) return
        _search.update { it.copy(order = order) }
        updateSearch(_search.value.query)
    }

    fun loadMoreSearch() {
        val current = _search.value
        if (searchJob?.isActive == true || !current.results.hasMore) return
        searchJob = viewModelScope.launch { performSearch(current.query.trim(), current.results.page + 1) }
    }

    private suspend fun performSearch(query: String, page: Int) {
        val previous = _search.value
        val sequence = searchSequence
        val accountGeneration = accountScopeGeneration
        _search.update { it.copy(results = it.results.copy(initialLoading = page == 1, loadingMore = page > 1, error = null)) }
        try {
            val result = repository.search(query, page, previous.order)
            if (sequence != searchSequence || accountGeneration != accountScopeGeneration) return
            val items = if (page == 1) result.items.distinctBy { it.listKey } else (previous.results.items + result.items).distinctBy { it.listKey }
            val hasMore = result.hasMore && result.items.isNotEmpty() && (page == 1 || page > previous.results.page)
            _search.update { it.copy(
                results = TopicListUiState(items, page, hasMore, initialLoading = false),
                excerpts = if (page == 1) result.excerpts else previous.excerpts + result.excerpts,
            ) }
            if (page == 1 && query.isNotBlank()) {
                addSearchHistory(query)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (sequence != searchSequence || accountGeneration != accountScopeGeneration) return
            _search.update { it.copy(results = it.results.copy(initialLoading = false, loadingMore = false, error = error.message ?: "搜索失败")) }
            handlePageFailure(error) { updateSearch(_search.value.query) }
        }
    }

    fun addSearchHistory(keyword: String) {
        val updated = store.addSearchHistory(keyword)
        _searchHistory.value = updated
    }

    fun clearSearchHistory() {
        store.clearSearchHistory()
        _searchHistory.value = emptyList()
    }

    private suspend fun handlePageFailure(error: Exception, retry: () -> Unit) {
        diagnostics.log("浏览页面加载失败: ${error.message}")
        if (error is NeedsInteractiveVerification) {
            resumeAfterVerification = retry
            enterWebScreen(Screen.Verify, error.message.orEmpty(), error.readProbePath)
        }
    }

    init {
        val app = getApplication<Application>()
        ExcerptCache.initialize(TopicOpStore(app))
        repository.onAutoHealRequested = {
            selfHealNetwork(context = null, silent = true)
        }
        repository.onTimingsVerificationRequired = { probePath ->
            showReadSyncVerifyBanner(probePath)
        }
        repository.onTimingsRecovered = {
            if (_readSyncVerifyBanner.value) diagnostics.log("阅读进度已恢复同步，收起风控提示")
            _readSyncVerifyBanner.value = false
            readSyncVerificationProbePath = null
        }
        NotificationHelper.ensureNotificationChannels(app)
        if (store.isBackgroundNotificationEnabled()) {
            NotificationWorker.enqueue(app)
        }
        syncKeepAliveService()
        container.dohCoordinator.onLatenciesUpdated = { latencies: Map<String, Long?>, summaries: Map<String, String>, selectedUrl: String ->
            _dohLatencies.value = latencies
            _dohSummaries.value = summaries
            _selectedDohServerUrl.value = selectedUrl
        }
        container.dohCoordinator.onSmartRouteUpdated = { mode, directMs, dohMs, summary ->
            _smartRouteMode.value = mode
            _smartDirectLatency.value = directMs.takeIf { it >= 0 }
            _smartDohLatency.value = dohMs.takeIf { it >= 0 }
            _smartRouteSummary.value = summary
        }
        boot()
        restartAutoProbeLoop()
        checkForUpdates(manual = false)
    }

    fun testPushNotification() {
        val app = getApplication<Application>()
        NotificationHelper.showNotification(
            context = app,
            notificationId = 999999,
            title = "LINUX DO 通知测试",
            content = "这是一条本地测试通知，您的通知推送功能工作正常！",
            badgeCount = _state.value.unreadCount.takeIf { it > 0 },
        )
    }

    fun boot() {
        if (repository.cachedCategoryList() == null) {
            container.preferences.getString("cached_categories_json", null)?.let { rawJson ->
                repository.seedCategoriesFromJson(rawJson)
                repository.cachedCategoryList()?.let { list ->
                    _categories.value = CategoriesUiState(items = list, loaded = true, order = store.loadCategoryOrder())
                    backfillCategories(repository.cachedCategoryMap())
                }
            }
        }
        if (repository.cachedTagList() == null) {
            container.preferences.getString("cached_tags_json", null)?.let { rawJson ->
                repository.seedTagsFromJson(rawJson)
                repository.cachedTagList()?.let { list ->
                    _tags.value = TagsUiState(tags = list, loaded = true)
                }
            }
        }
        bootJob?.cancel()
        bootJob = viewModelScope.launch {
            verifying = false
            _state.update { it.copy(screen = Screen.Boot, latest = it.latest.copy(error = null)) }
            try {
                if (store.recoverInterruptedAccountSwitch()) {
                    container.preferences.edit { remove("cached_username") }
                    _state.update { it.copy(username = null) }
                }
                store.restoreMissingToWebView()
                refreshCookieInfo()
                sessionStorageNeedsRetry = false
                if (!store.hasSessionCookie()) {
                    enterWebScreen(Screen.Login)
                    return@launch
                }
                val cachedUser = container.preferences.getString("cached_username", null)?.takeIf {
                    it.isNotBlank() && store.currentUserId() > 0L &&
                        container.preferences.getLong("cached_username_user_id", 0L) == store.currentUserId()
                }
                if (cachedUser != null) {
                    diagnostics.log("命中本地会话缓存($cachedUser),直拉首屏列表并后台复核")
                    val currentId = store.currentUserId()
                    if (currentId > 0L) {
                        container.reading.switchUser(currentId)
                        container.historyStore.switchUser(currentId)
                        restoreAccountScopedLocalState(currentId)
                    }
                    _state.update { it.copy(username = cachedUser, screen = Screen.List) }
                    browser.prewarmCfClearanceIfNeeded("会话缓存启动")
                    resumeAfterVerification?.also { resumeAfterVerification = null }?.invoke()
                    loadLatest(page = 0, pullRefresh = false)
                    viewModelScope.launch {
                        delay(1200L)
                        refreshUnread(force = true)
                    }
                    scheduleIdleConnectWarmup()
                    warmFollowedUsernames(cachedUser)
                    viewModelScope.launch {
                        delay(2000L)
                        reconcileSessionAndCategories(cachedUser)
                    }
                    scheduleIdleConnectWarmup()
                    // 添加账号/退出登录会经 clearAccountScopedState 停掉话题感知,登录回来要重新拉起。
                    restartAutoProbeLoop()
                    return@launch
                }
                val currentGen = store.sessionGeneration
                var currentUser = repository.fetchCurrentUserDetails()
                if (currentUser == null) {
                    diagnostics.log("启动检查初次未获取到用户，从 WebView 规范化对账后二次复核")
                    withContext(Dispatchers.Main) { store.syncFromWebView() }
                    if (store.sessionGeneration == currentGen) {
                        currentUser = repository.fetchCurrentUserDetails()
                    }
                }
                if (store.sessionGeneration != currentGen) return@launch
                if (currentUser == null) {
                    if (store.sessionGeneration == currentGen) {
                        diagnostics.log("会话 Cookie 规范化二次复核后仍判定未登录,转入登录")
                        store.clearSessionCookie()
                        enterWebScreen(Screen.Login)
                    }
                } else {
                    val username = currentUser.username
                    diagnostics.log("登录态有效: $username")
                    store.saveCurrentUserId(currentUser.id)
                    container.reading.switchUser(currentUser.id)
                    container.historyStore.switchUser(currentUser.id)
                    restoreAccountScopedLocalState(currentUser.id)
                    rememberCurrentAccount(currentUser)
                    container.preferences.edit {
                        putString("cached_username", username)
                        putLong("cached_username_user_id", currentUser.id)
                    }
                    _state.update { it.copy(username = username, screen = Screen.List) }
                    browser.prewarmCfClearanceIfNeeded("登录完成启动")
                    resumeAfterVerification?.also { resumeAfterVerification = null }?.invoke()
                    loadLatest(page = 0, pullRefresh = false)
                    viewModelScope.launch {
                        delay(1200L)
                        refreshUnread(force = true)
                    }
                    scheduleIdleConnectWarmup()
                    restartAutoProbeLoop()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: NeedsInteractiveVerification) {
                enterWebScreen(Screen.Verify, error.message.orEmpty(), error.readProbePath)
            } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                sessionStorageNeedsRetry = true
                _state.update { it.copy(screen = Screen.List, latest = it.latest.copy(initialLoading = false, error = error.message)) }
            } catch (error: Exception) {
                diagnostics.log("启动检查失败: ${error.message}")
                _state.update {
                    it.copy(
                        screen = Screen.List,
                        latest = it.latest.copy(initialLoading = false, error = error.message),
                    )
                }
            }
        }
    }

    /**
     * 首屏列表上屏后，后台静默探测分类树更新并复核会话有效性（Stale-While-Revalidate）。
     * 既保证冷启动首屏 0 阻塞秒开，又确保服务端分类变动或会话失效能够及时同步。
     */
    private fun reconcileSessionAndCategories(cachedUser: String) {
        val expectedUserId = store.currentUserId()
        syncCategoriesInBackground()
        syncTagsInBackground()
        reconcileSessionJob?.cancel()
        reconcileSessionJob = viewModelScope.launch {
            try {
                val currentGen = store.sessionGeneration
                var realUser = repository.fetchCurrentUserDetails()
                if (store.currentUserId() != expectedUserId || store.sessionGeneration != currentGen) return@launch
                if (realUser == null) {
                    diagnostics.log("后台复核初次未获取到用户，从 WebView 规范化对账后二次复核")
                    withContext(Dispatchers.Main) { store.syncFromWebView() }
                    if (store.currentUserId() == expectedUserId && store.sessionGeneration == currentGen) {
                        realUser = repository.fetchCurrentUserDetails()
                    }
                }
                if (store.currentUserId() != expectedUserId || store.sessionGeneration != currentGen) return@launch
                when {
                    realUser == null -> {
                        // 参照 Fluxdo 规范：后台静默复核绝不能因为偶发异常或单次响应直接强删本地凭据并弹登录页
                        diagnostics.log("后台复核确认无返回，保守保留本地凭据避免误杀")
                    }
                    realUser.username != cachedUser -> {
                        diagnostics.log("后台复核用户名更新: $cachedUser -> ${realUser.username}")
                        container.preferences.edit {
                            putString("cached_username", realUser.username)
                            putLong("cached_username_user_id", realUser.id)
                        }
                        _state.update { it.copy(username = realUser.username) }
                    }
                }
                if (realUser != null) {
                    store.saveCurrentUserId(realUser.id)
                    container.reading.switchUser(realUser.id)
                    container.historyStore.switchUser(realUser.id)
                    restoreAccountScopedLocalState(realUser.id)
                    rememberCurrentAccount(realUser)
                }
                prewarmCurrentUserAvatar(realUser?.username ?: cachedUser)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NeedsInteractiveVerification) {
                enterWebScreen(Screen.Verify, error.message.orEmpty(), error.readProbePath)
            } catch (error: Exception) {
                diagnostics.log("后台会话复核跳过: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun backfillCategories(known: Map<Int, Category>) {
        if (known.isEmpty()) return
        _feedStates.update { currentMap ->
            currentMap.mapValues { (_, feedState) ->
                val patched = feedState.items.map { item ->
                    val cat = item.topic.categoryId?.let { known[it] }
                    if (cat != item.category) item.copy(category = cat) else item
                }
                if (patched != feedState.items) feedState.copy(items = patched) else feedState
            }
        }
        _state.update { state ->
            val patched = state.latest.items.map { item ->
                val cat = item.topic.categoryId?.let { known[it] }
                if (cat != item.category) item.copy(category = cat) else item
            }
            if (patched != state.latest.items) state.copy(latest = state.latest.copy(items = patched)) else state
        }
        _categoryTopics.update { map ->
            map.mapValues { (_, listState) ->
                val patched = listState.items.map { item ->
                    val cat = item.topic.categoryId?.let { known[it] }
                    if (cat != item.category) item.copy(category = cat) else item
                }
                if (patched != listState.items) listState.copy(items = patched) else listState
            }
        }
    }

    private fun syncCategoriesInBackground() {
        val requestScopeGeneration = accountScopeGeneration
        pageJobs["sync:categories"] = viewModelScope.launch {
            try {
                val updated = repository.refreshCategoriesFromServer()
                if (requestScopeGeneration != accountScopeGeneration) return@launch
                val known = repository.cachedCategoryMap()
                if (updated != null) {
                    val (freshList, rawJson) = updated
                    container.preferences.edit { putString("cached_categories_json", rawJson) }
                    _categories.value = CategoriesUiState(items = freshList, loaded = true, order = store.loadCategoryOrder())
                    diagnostics.log("分类表已从服务端同步更新(${known.size} 项)")
                }
                backfillCategories(known)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("后台分类探测跳过: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun syncTagsInBackground() {
        val requestScopeGeneration = accountScopeGeneration
        pageJobs["sync:tags"] = viewModelScope.launch {
            try {
                val updated = repository.refreshTagsFromServer()
                if (requestScopeGeneration != accountScopeGeneration) return@launch
                if (updated != null) {
                    val (freshList, rawJson) = updated
                    container.preferences.edit { putString("cached_tags_json", rawJson) }
                    _tags.update { it.copy(tags = freshList, loaded = true, loading = false, error = null) }
                    diagnostics.log("标签表已从服务端同步更新(${freshList.size} 项)")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("后台标签探测跳过: ${error.javaClass.simpleName}")
            }
        }
    }

    fun setCategorySubTab(tab: CategorySectionTab) {
        _categorySubTab.value = tab
        if (tab == CategorySectionTab.TAGS) {
            if (!_tags.value.loaded && !_tags.value.loading) {
                loadTags()
            } else {
                syncTagsInBackground()
            }
        }
    }

    fun updateTagSearchQuery(query: String) {
        _tagSearchQuery.value = query
        pageJobs["tags:search"]?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _tags.update { if (it.searchedTags.isEmpty()) it else it.copy(searchedTags = emptyList()) }
            return
        }
        pageJobs["tags:search"] = viewModelScope.launch {
            delay(250L)
            try {
                val result = repository.searchTags(
                    categoryId = null,
                    query = trimmed,
                    limit = 50,
                    filterForInput = false,
                )
                val remoteTags = result.results.map { info ->
                    org.linuxdo.android.data.DiscourseTag(
                        id = info.name,
                        name = info.name,
                        text = info.text.ifBlank { info.name },
                        count = info.count,
                    )
                }
                if (_tagSearchQuery.value.trim() == trimmed) {
                    _tags.update { it.copy(searchedTags = remoteTags) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 搜索远程补充失败时静默降级为本地过滤结果
            }
        }
    }

    fun loadTags(retry: Boolean = false) {
        val current = _tags.value
        if (current.loading || (current.loaded && !retry)) return
        pageJobs["tags"] = viewModelScope.launch {
            _tags.value = current.copy(loading = true, error = null)
            try {
                val updated = repository.refreshTagsFromServer()
                if (updated != null) {
                    val (freshList, rawJson) = updated
                    container.preferences.edit { putString("cached_tags_json", rawJson) }
                    _tags.update { it.copy(tags = freshList, loaded = true, loading = false, error = null) }
                } else {
                    val list = repository.fetchTags()
                    _tags.update { it.copy(tags = list, loaded = true, loading = false, error = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _tags.update { it.copy(loading = false, error = error.message ?: "加载失败") }
                handlePageFailure(error) { loadTags(retry = true) }
            }
        }
    }

    fun loadTagTopics(tag: String, refresh: Boolean = false, more: Boolean = false) {
        val current = _tagTopics.value[tag]
        if (pageJobs["tag:$tag"]?.isActive == true) return
        if (!refresh && !more && current != null && !current.needsRefresh) return
        val previous = current ?: TopicListUiState()
        if (more && !previous.hasMore) return
        val page = if (more) previous.page + 1 else 0
        pageJobs["tag:$tag"] = viewModelScope.launch {
            _tagTopics.update { it + (tag to previous.copy(
                refreshing = refresh, loadingMore = more, initialLoading = previous.items.isEmpty(), error = null,
            )) }
            try {
                val sortOrder = _topicSortOrder.value.apiValue
                val sortAscending = _topicSortAscending.value.takeIf { sortOrder != null }
                val result = repository.fetchTagTopics(tag, page, order = sortOrder, ascending = sortAscending)
                ExcerptCache.loadBatchFromStore(result.items.map { it.topic.id })
                val items = if (more) (previous.items + result.items).distinctBy { it.topic.id } else result.items
                val hasMore = result.hasMore && result.items.isNotEmpty() && (!more || page > previous.page)
                _tagTopics.update { it + (tag to TopicListUiState(
                    items = items,
                    page = page,
                    hasMore = hasMore,
                    initialLoading = false,
                )) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _tagTopics.update { it + (tag to previous.copy(initialLoading = false, error = error.message ?: "加载失败")) }
                handlePageFailure(error) { loadTagTopics(tag, refresh = !more, more = more) }
            } finally {
                _tagTopics.update { states -> states[tag]?.let {
                    states + (tag to it.copy(initialLoading = false, refreshing = false, loadingMore = false))
                } ?: states }
            }
        }
    }

    fun switchHomeFeed(feed: HomeFeedType) {
        if (_state.value.homeFeed == feed) return
        val cached = _feedStates.value[feed]
        _state.update {
            it.copy(
                homeFeed = feed,
                latest = cached ?: TopicListUiState(initialLoading = true),
            )
        }
        store.saveHomeFeed(feed.name)
        if (cached == null || (cached.items.isEmpty() && !cached.loadingMore && !cached.refreshing)) {
            viewModelScope.launch { loadFeed(feed, page = 0, pullRefresh = false) }
        }
    }

    fun preloadHomeFeed(feed: HomeFeedType) {
        val cached = _feedStates.value[feed]
        if (cached == null || (cached.items.isEmpty() && !cached.loadingMore && !cached.refreshing && !cached.initialLoading)) {
            viewModelScope.launch { loadFeed(feed, page = 0, pullRefresh = false) }
        }
    }

    fun refreshLatest() {
        incomingProbeGeneration++
        val feed = _state.value.homeFeed
        viewModelScope.launch { loadFeed(feed, page = 0, pullRefresh = true) }
    }

    fun retryLatest() {
        val feed = _state.value.homeFeed
        viewModelScope.launch { loadFeed(feed, page = 0, pullRefresh = false) }
    }

    fun clearDiagnostics() {
        diagnostics.clear()
    }

    fun loadMoreLatest() {
        val feed = _state.value.homeFeed
        val current = _feedStates.value[feed] ?: _state.value.latest
        if (!current.hasMore || current.loadingMore || current.refreshing) return
        val currentPage = current.page
        viewModelScope.launch { loadFeed(feed, page = currentPage + 1, pullRefresh = false) }
    }

    fun setPath(path: NetworkPath) {
        if (path == _state.value.path) return
        _state.update { it.copy(path = path) }
        diagnostics.log("切换网络路径 -> ${path.label}")
        retryLatest()
    }

    fun toggleAutoRefresh() {
        val enable = !_state.value.autoRefresh
        _state.update { it.copy(autoRefresh = enable) }
        autoJob?.cancel()
        if (!enable) {
            diagnostics.log("自动刷新已关闭")
            return
        }
        diagnostics.log("自动刷新已开启(每 ${Config.AUTO_REFRESH_INTERVAL_MS / 60_000} 分钟)")
        autoJob = viewModelScope.launch {
            while (isActive) {
                delay(Config.AUTO_REFRESH_INTERVAL_MS)
                if (_state.value.screen == Screen.List) loadLatest(page = 0, pullRefresh = false)
            }
        }
    }

    /**
     * 由界面在验证页每秒轮询一次;完成条件满足后回到启动流程重新校验会话。
     *
     * 这里**必须**以"拿到会话 Cookie"为完成条件。早先 `Screen.Verify -> true` 是无条件成立,
     * 只要 WebView 打开的页面不是 CF 挑战页就立刻判定验证完成 —— 而邮件登录的确认页
     * 域名和标题都正常,于是刚打开就被当成验证通过,随即 boot() 发现没有 _t 又弹回登录页。
     */
    suspend fun pollVerification() {
        if (verifying) return
        if (_state.value.screen == Screen.Verify) {
            if (store.currentUserId() != verificationUserId || store.sessionGeneration != verificationSessionGeneration) {
                cancelVerification()
                return
            }
            if (restartVerificationIfNetworkChanged()) return
            if (verificationMismatch) return
            val attempt = verificationAttempt
            val probePath = verificationProbePath
            if (browser.interactiveVerificationComplete()) {
                val now = SystemClock.elapsedRealtime()
                val rateCooldownMs = TopicRepository.rateLimitRemainingMs()
                if (rateCooldownMs > 0L) {
                    verifyCompletionProbeNotBefore = maxOf(verifyCompletionProbeNotBefore, now + rateCooldownMs)
                    return
                }
                if (now < verifyCompletionProbeNotBefore) return
                val candidateFp = runCatching { store.currentCfClearanceFp() }.getOrDefault("-")
                if (verificationConfirmedCandidateFp != candidateFp) {
                    verificationConfirmedCandidateFp = null
                    if (verificationProbeAttempts >= MAX_VERIFICATION_CONFIRMATION_PROBES) {
                        scheduleVerificationConfirmation("本轮复核预算已用完")
                        return
                    }
                    verifying = true
                    val outcome = try {
                        repository.probeClearanceAfterVerification(probePath)
                    } catch (cancelled: CancellationException) {
                        if (attempt != verificationAttempt || store.sessionGeneration != verificationSessionGeneration ||
                            store.currentUserId() != verificationUserId) return
                        throw cancelled
                    } finally {
                        if (attempt == verificationAttempt) verifying = false
                    }
                    if (attempt != verificationAttempt || _state.value.screen != Screen.Verify) return
                    if (restartVerificationIfNetworkChanged()) return
                    if (outcome != TopicRepository.VerificationProbeOutcome.DEFERRED) verificationProbeAttempts++
                    val currentCandidateFp = runCatching { store.currentCfClearanceFp() }.getOrDefault("-")
                    if (candidateFp != currentCandidateFp) {
                        scheduleVerificationConfirmation("候选凭据已更新，忽略旧请求结果")
                        return
                    }
                    when (outcome) {
                        TopicRepository.VerificationProbeOutcome.CLEARED -> {
                            verificationConfirmedCandidateFp = candidateFp
                            val postProbeCooldownMs = TopicRepository.rateLimitRemainingMs()
                            if (postProbeCooldownMs > 0L) {
                                verifyCompletionProbeNotBefore = SystemClock.elapsedRealtime() + postProbeCooldownMs
                                return
                            }
                        }
                        TopicRepository.VerificationProbeOutcome.CHALLENGED -> {
                            scheduleVerificationConfirmation("App 请求仍受挑战")
                            return
                        }
                        TopicRepository.VerificationProbeOutcome.INCONCLUSIVE -> {
                            scheduleVerificationConfirmation("App 请求暂未确认恢复")
                            return
                        }
                        TopicRepository.VerificationProbeOutcome.DEFERRED -> {
                            scheduleVerificationConfirmation("等待已有冷却结束")
                            return
                        }
                    }
                }
            } else {
                // 验证页尚未收口：用户可能已开代理/换了网络，App 通道不再被挑战，页面却停在 404 或加载失败上。
                // 正在人工交互挑战组件或处于 429 冷却时不探测，避免对 CF 额外发请求。
                val now = SystemClock.elapsedRealtime()
                val rateCooldownMs = TopicRepository.rateLimitRemainingMs()
                if (rateCooldownMs > 0L) {
                    appPathProbeNotBefore = maxOf(appPathProbeNotBefore, now + rateCooldownMs)
                    return
                }
                if (now < appPathProbeNotBefore) return
                if (browser.isVerifyChallengeActive()) {
                    diagnostics.logThrottled("verify-probe-active-challenge", "验证页仍有活跃挑战，暂缓 App 通道探测")
                    return
                }
                appPathProbeNotBefore = now + APP_PATH_PROBE_INTERVAL_MS
                verifying = true
                val outcome = try {
                    repository.probeAppPathDuringVerification(probePath)
                } finally {
                    if (attempt == verificationAttempt) verifying = false
                }
                if (attempt != verificationAttempt || _state.value.screen != Screen.Verify) return
                if (restartVerificationIfNetworkChanged()) return
                // 从响应结束时退避，慢请求或超时后也不紧接着重探。
                appPathProbeNotBefore = SystemClock.elapsedRealtime() + APP_PATH_PROBE_INTERVAL_MS
                val postProbeCooldownMs = TopicRepository.rateLimitRemainingMs()
                if (postProbeCooldownMs > 0L) {
                    appPathProbeNotBefore = maxOf(
                        appPathProbeNotBefore,
                        SystemClock.elapsedRealtime() + postProbeCooldownMs,
                    )
                    return
                }
                // 探测在途期间若页面刚刚刷出挑战组件且用户可能正在操作，放弃旁路收口，交由验证页继续处理
                if (browser.isVerifyChallengeActive()) return
                if (outcome != TopicRepository.VerificationProbeOutcome.CLEARED) return
                // Gecko 若已换入新凭据并进入 DONE，留给下一轮完成后复核，不能沿用旧请求的结论。
                if (!browser.finishVerifyForAppPath()) return
                diagnostics.log("验证页未收口，但 App 请求通道已不被 Cloudflare 挑战，直接结束验证并恢复操作")
            }
            if (TopicRepository.isRateLimitCoolingDown()) return
            store.completeVerification()
            verificationProbePath = null
            verifyCompletionProbeNotBefore = 0L
            verificationProbeAttempts = 0
            verificationConfirmedCandidateFp = null
            repository.clearCachedCsrfToken()
            unreadChallengeBackoffUntil = 0L
            if (verificationFromReadSync || _readSyncVerifyBanner.value) {
                verificationFromReadSync = false
                _readSyncVerifyBanner.value = false
                readSyncVerificationProbePath = null
                repository.onManualVerificationCompleted()
                diagnostics.log("阅读同步手工验证完成，优先尝试可用的 WebView 通道，否则继续使用 App 通道")
            }
            val resume = resumeAfterVerification
            resumeAfterVerification = null
            _state.update { it.copy(screen = verificationReturnScreen) }
            verificationDismissed = false
            if (resume != null) resume() else if (verificationReturnScreen == Screen.Boot) boot()
            return
        }
        if (!browser.isPageCleared()) return
        val cookies = try {
            store.syncFromWebView()
        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
            _loginUiState.update { it.copy(error = error.message) }
            return
        }
        val hasSession = cookies.containsKey(Config.SESSION_COOKIE)
        when (_state.value.screen) {
            Screen.Verify -> when {
                resumeAfterVerification != null -> {
                    verifying = true
                    val resume = resumeAfterVerification
                    resumeAfterVerification = null
                    diagnostics.log("安全验证完成,恢复中断的操作")
                    _state.update { it.copy(screen = Screen.List) }
                    verifying = false
                    resume?.invoke()
                }
                hasSession -> {
                    verifying = true
                    diagnostics.log("安全验证完成,重新校验会话")
                    boot()
                }
                // 页面不再被挑战但仍无会话:本来就没登录,回登录页收场,不要在这里干等。
                else -> {
                    diagnostics.log("安全验证通过但无会话,回到登录页")
                    enterWebScreen(Screen.Login)
                }
            }
            Screen.Login -> if (hasSession && !verifying) {
                if (store.isSessionRecoveryPending()) {
                    boot()
                    return
                }
                verifying = true
                diagnostics.log("检测到登录 Cookie _t，正在校验服务端真实会话...")
                val verifiedUser = runCatching { repository.fetchCurrentUser() }.getOrNull()
                if (!verifiedUser.isNullOrBlank()) {
                    diagnostics.log("服务端会话确认有效 (用户: $verifiedUser)，关闭弹层进入论坛")
                    _loginUiState.update { LoginUiState() }
                    boot()
                } else {
                    diagnostics.log("Cookie 存在但服务端尚未建立有效会话，继续等待页面完成登录")
                    verifying = false
                }
            }
            else -> Unit
        }
    }

    fun refreshAccounts() {
        if (_accounts.value.switching || store.sessionChanging) return
        try {
            _state.value.username?.let { username ->
                val userId = store.currentUserId()
                if (userId > 0L) container.accountManager.remember(userId, username, store.syncFromWebView())
            }
            publishAccountState()
        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
            _accounts.update { it.copy(error = error.message) }
        }
    }

    private var preAddAccountUserId: Long? = null
    private var preAddAccountState: UiState? = null
    private var addAccountPrepared = false

    private fun prepareAddAccountLogin(proceed: () -> Unit): Boolean {
        if (preAddAccountState == null || addAccountPrepared) return false
        if (_accounts.value.switching) return true
        _accounts.update { it.copy(switching = true) }
        loginJob = viewModelScope.launch {
            try {
                val previousId = store.currentUserId()
                val previousName = _state.value.username
                if (previousId > 0L && !previousName.isNullOrBlank()) {
                    container.accountManager.remember(previousId, previousName, store.syncFromWebView())
                }
                clearAccountScopedState()
                browser.stopSessionRequests()
                addAccountPrepared = true
                store.replaceBusinessCookies(null, null, userId = 0L)
                repository.clearCachedCsrfToken()
                container.preferences.edit { remove("cached_username") }
                _state.update { it.copy(username = null, screen = Screen.Login) }
                _accounts.update { it.copy(switching = false) }
                loginJob = null
                proceed()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _accounts.update { it.copy(switching = false) }
                _loginUiState.update { it.copy(error = "准备登录失败，请返回原账号后重试") }
            }
        }
        return true
    }

    fun switchAccount(userId: Long, force: Boolean = false) {
        switchAccountInternal(userId, force)
    }

    private fun switchAccountInternal(
        userId: Long,
        force: Boolean,
        onSuccess: (() -> Unit)? = null,
        failureMessage: String? = null,
    ) {
        if (_accounts.value.switching || userId <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastAccountSwitchAt?.let { now - it < 1_500L } == true) return
        val target = try {
            container.accountManager.account(userId)
        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
            _accounts.update { it.copy(error = error.message) }
            return
        } ?: return
        if (!force && userId == store.currentUserId()) {
            _accounts.update { it.copy(error = null) }
            return
        }
        lastAccountSwitchAt = now
        _accounts.update { it.copy(switching = true, error = null) }
        viewModelScope.launch {
            val oldState = _state.value
            val snapshot = try {
                val cookies = store.syncFromWebView()
                val oldId = store.currentUserId()
                val oldName = _state.value.username
                if (oldId > 0L && !oldName.isNullOrBlank()) {
                    container.accountManager.remember(oldId, oldName, cookies)
                }
                Triple(cookies, oldId, container.accountManager.activeUserId())
            } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                _accounts.update { it.copy(switching = false, error = error.message) }
                return@launch
            }
            val (oldCookies, oldUserId, oldActiveUserId) = snapshot
            try {
                // 先结算并清空旧账号的内存态，再替换网络凭据，避免在新会话下处理旧页面。
                clearAccountScopedState()
                browser.stopSessionRequests()
                store.replaceBusinessCookies(target.token, target.forumSession, userId = target.userId)
                repository.clearCachedCsrfToken()
                val currentUser = repository.fetchCurrentUserDetails()
                    ?: error("切换后的会话未登录")
                check(currentUser.id == target.userId) {
                    "服务端返回的账号与所选账号不匹配"
                }
                store.saveCurrentUserId(currentUser.id)
                container.accountManager.setActive(currentUser.id)
                container.reading.switchUser(currentUser.id)
                container.historyStore.switchUser(currentUser.id)
                restoreAccountScopedLocalState(currentUser.id)
                rememberCurrentAccount(currentUser)
                container.preferences.edit {
                    putString("cached_username", currentUser.username)
                    putLong("cached_username_user_id", currentUser.id)
                }
                _state.update {
                    it.copy(
                        username = currentUser.username,
                        screen = Screen.List,
                        latest = TopicListUiState(),
                        unreadCount = 0,
                    )
                }
                publishAccountState()
                loadLatest(page = 0, pullRefresh = false)
                refreshUnread(force = true)
                syncCategoriesInBackground()
                syncTagsInBackground()
                syncKeepAliveService()
                restartAutoProbeLoop()
                onSuccess?.invoke()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val rollbackError = runCatching {
                    store.advanceSessionGeneration()
                    store.replaceBusinessCookies(
                        oldCookies[Config.SESSION_COOKIE],
                        oldCookies["_forum_session"],
                        userId = oldUserId,
                    )
                    if (oldUserId > 0L) {
                        store.saveCurrentUserId(oldUserId)
                        container.reading.switchUser(oldUserId)
                        container.historyStore.switchUser(oldUserId)
                        restoreAccountScopedLocalState(oldUserId)
                    } else {
                        store.clearCurrentUserId()
                        container.reading.switchUser(0L)
                        container.historyStore.switchUser(0L)
                        restoreAccountScopedLocalState(0L)
                    }
                    if (oldActiveUserId > 0L && container.accountManager.account(oldActiveUserId) != null) {
                        container.accountManager.setActive(oldActiveUserId)
                    }
                    repository.clearCachedCsrfToken()
                    container.preferences.edit {
                        if (oldState.username == null) remove("cached_username")
                        else putString("cached_username", oldState.username)
                        putLong("cached_username_user_id", oldUserId)
                    }
                    _state.value = oldState
                    publishAccountState()
                }.exceptionOrNull()
                if (rollbackError == null) {
                    restartAutoProbeLoop()
                    refreshUnread(force = true)
                }
                if (rollbackError != null) {
                    clearAccountScopedState()
                    container.preferences.edit { remove("cached_username") }
                    runCatching { store.requireSessionRecovery() }
                        .onFailure { diagnostics.log("账号恢复标记暂未落盘，保持当前请求暂停") }
                    sessionStorageNeedsRetry = true
                    _state.update { it.copy(username = null, screen = Screen.List, unreadCount = 0,
                        latest = it.latest.copy(initialLoading = false, error = "账号恢复尚未完成，请稍后重试")) }
                }
                _accounts.update {
                    it.copy(
                        switching = false,
                        error = if (rollbackError == null) {
                            failureMessage ?: error.message ?: "账号切换失败"
                        } else {
                            "账号恢复尚未完成，请稍后重试"
                        },
                    )
                }
                return@launch
            }
            _accounts.update { it.copy(switching = false) }
        }
    }

    fun addAccount() {
        if (_accounts.value.switching) return
        refreshAccounts()
        val snapshot = container.accountManager.snapshot()
        if (!snapshot.storageAvailable) {
            _accounts.update { it.copy(error = snapshot.error) }
            return
        }
        if (_accounts.value.accounts.size >= AccountManager.MAX_ACCOUNTS) {
            Toast.makeText(
                getApplication(),
                "最多支持保存 ${AccountManager.MAX_ACCOUNTS} 个账号，请先移除未使用的账号",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        preAddAccountState = _state.value
        addAccountPrepared = false
        preAddAccountUserId = store.currentUserId().takeIf { it > 0L }
            ?: snapshot.activeUserId.takeIf { it > 0L }
        _loginUiState.value = LoginUiState()
        _state.update { it.copy(screen = Screen.Login) }
    }

    fun canCancelAddAccount(): Boolean =
        preAddAccountState != null || _accounts.value.accounts.isNotEmpty() || _state.value.username != null

    fun cancelAddAccount() {
        if (_accounts.value.switching) return
        val previousState = preAddAccountState
        if (previousState != null) {
            loginJob?.cancel()
            cancelWebAuth()
            browser.cancelCaptchaLogin()
            _loginUiState.value = LoginUiState()
            if (!addAccountPrepared) {
                preAddAccountState = null
                preAddAccountUserId = null
                _state.value = previousState
                return
            }
            val previous = try {
                preAddAccountUserId?.let(container.accountManager::account)
            } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                _loginUiState.update { it.copy(error = error.message) }
                return
            }
            if (previous == null) {
                _loginUiState.update { it.copy(error = "原账号凭据不可用，请重新登录") }
                return
            }
            _accounts.update { it.copy(switching = true) }
            viewModelScope.launch {
                try {
                    browser.stopSessionRequests()
                    store.replaceBusinessCookies(previous.token, previous.forumSession, userId = previous.userId)
                    store.saveCurrentUserId(previous.userId)
                    container.accountManager.setActive(previous.userId)
                    container.reading.switchUser(previous.userId)
                    container.historyStore.switchUser(previous.userId)
                    restoreAccountScopedLocalState(previous.userId)
                    container.preferences.edit {
                        putString("cached_username", previous.username)
                        putLong("cached_username_user_id", previous.userId)
                    }
                    repository.clearCachedCsrfToken()
                    preAddAccountState = null
                    preAddAccountUserId = null
                    addAccountPrepared = false
                    _state.value = previousState
                    publishAccountState()
                    // 本地恢复完成即可返回，网络复核不应决定取消能否成功。
                    reconcileSessionAndCategories(previous.username)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _loginUiState.update { it.copy(error = "恢复原账号失败，请重试返回") }
                } finally {
                    _accounts.update { it.copy(switching = false) }
                }
            }
            return
        }
        val snapshot = container.accountManager.snapshot()
        if (!snapshot.storageAvailable) {
            _loginUiState.update { it.copy(error = snapshot.error) }
            return
        }
        val targetUserId = preAddAccountUserId?.takeIf { expected -> snapshot.accounts.any { it.userId == expected } }
            ?: snapshot.activeUserId.takeIf { expected -> snapshot.accounts.any { it.userId == expected } }
            ?: snapshot.accounts.firstOrNull()?.userId
        preAddAccountUserId = null
        if (targetUserId != null && targetUserId > 0L) {
            switchAccountInternal(targetUserId, force = true)
        }
    }

    fun removeAccount(userId: Long) {
        if (userId <= 0L || _accounts.value.switching) return
        val snapshot = container.accountManager.snapshot()
        if (!snapshot.storageAvailable) {
            _accounts.update { it.copy(error = snapshot.error) }
            return
        }
        val activeUserId = snapshot.activeUserId
        val isActive = userId == activeUserId
        if (isActive) {
            val nextAccount = snapshot.accounts.firstOrNull { it.userId != userId }
            if (nextAccount != null) {
                switchAccountInternal(
                    userId = nextAccount.userId,
                    force = true,
                    onSuccess = {
                        try {
                            container.accountManager.remove(userId)
                            publishAccountState()
                        } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                            _accounts.update { it.copy(error = error.message) }
                        }
                    },
                    failureMessage = "切换至其他账号失败，未移除当前账号",
                )
            } else {
                logout()
            }
        } else {
            try {
                container.accountManager.remove(userId)
                publishAccountState()
            } catch (error: org.linuxdo.android.data.EncryptedStorageUnavailableException) {
                _accounts.update { it.copy(error = error.message) }
            }
        }
    }

    private fun rememberCurrentAccount(user: org.linuxdo.android.data.DiscourseUser) {
        preAddAccountState = null
        preAddAccountUserId = null
        addAccountPrepared = false
        if (!user.animatedAvatar.isNullOrBlank() && isAnimatedAvatarPath(user.animatedAvatar)) {
            AvatarCache.record(user.username, user.animatedAvatar)
            container.accountManager.updateAnimatedAvatar(user.username, user.animatedAvatar)
        }
        val account = container.accountManager.remember(
            userId = user.id,
            username = user.username,
            cookies = store.syncFromWebView(),
            user = user,
        )
        if (account != null) publishAccountState()
        prewarmCurrentUserAvatar(user.username)
        warmFollowedUsernames(user.username)
    }

    private fun warmFollowedUsernames(username: String?) {
        val cleanUser = username?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() } ?: return
        val cached = repository.getCachedFollowing(cleanUser)
        if (cached != null && isCurrentAccountUsername(cleanUser)) {
            followedUsernames.value = cached.map { it.username.lowercase() }.toSet()
        }
        viewModelScope.launch {
            runCatching {
                val fresh = repository.fetchFollowing(cleanUser)
                if (isCurrentAccountUsername(cleanUser)) {
                    followedUsernames.value = fresh.map { it.username.lowercase() }.toSet()
                }
            }
        }
    }

    private fun isCurrentAccountUsername(username: String): Boolean =
        _state.value.username?.trim()?.removePrefix("@")?.equals(username, ignoreCase = true) == true

    private fun prewarmCurrentUserAvatar(username: String?) {
        val cleanUser = username?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() } ?: return
        val currentAccount = _accounts.value.accounts.firstOrNull { it.username.equals(cleanUser, ignoreCase = true) }
        val hasAnimInAccount = isAnimatedAvatarPath(currentAccount?.animatedAvatar)
        val hasAnimInCache = isAnimatedAvatarPath(AvatarCache.get(cleanUser))
        // 已有正向缓存（账号记录 + 内存缓存均已知为动图），直接短路
        if (hasAnimInAccount && hasAnimInCache) return
        // 已确认该用户无动图（负缓存命中），跳过探测避免高频重复请求
        if (AvatarCache.isNonAnimated(cleanUser)) return

        viewModelScope.launch {
            try {
                val anim = repository.resolveAnimatedAvatar(cleanUser)
                if (!anim.isNullOrBlank() && isAnimatedAvatarPath(anim)) {
                    AvatarCache.record(cleanUser, anim)
                    container.accountManager.updateAnimatedAvatar(cleanUser, anim)
                    publishAccountState()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private fun publishAccountState() {
        val snapshot = container.accountManager.snapshot()
        _accounts.update {
            it.copy(
                accounts = snapshot.accounts,
                activeUserId = snapshot.activeUserId,
                error = snapshot.error ?: it.error,
            )
        }
    }

    private fun restoreAccountScopedLocalState(userId: Long) {
        _savedTopicsUserId.value = userId
        publishSavedTopics(runCatching { savedTopicStore.entries(userId) }.getOrElse {
            diagnostics.log("暂存列表读取失败: ${it.message}")
            _savedTopicsNotice.value = "暂存列表读取失败，请重新打开应用后重试"
            emptyList()
        })
        _history.value = HistoryUiState(entries = container.historyStore.entries())
        val blocked = repository.getBlockedUsers(userId)
        _blockedUsers.value = blocked
        blockedUsernames.value = blocked.map { it.username.lowercase() }.toSet()
    }

    private fun clearAccountScopedState() {
        accountScopeGeneration += 1
        verificationAttempt++
        verificationProbePath = null
        readSyncVerificationProbePath = null
        verificationFromReadSync = false
        _readSyncVerifyBanner.value = false
        searchSequence++
        closeTopicSearch()
        metadataSequence++
        metadataJob?.cancel()
        metadataTagJob?.cancel()
        _metadataEditor.value = TopicMetadataEditorUiState()
        _metadataUpdated.value = null
        _savedTopicsUserId.value = 0L
        _savedTopics.value = emptyList()
        _savedTopicsBusy.value = false
        clearSavedTopicsNotice()
        topicReadingPositions.clear()
        topicLiveJob?.cancel()
        topicLiveId = null
        topicLiveSessions.clear()
        topicLiveAtEnd.clear()
        topicLiveScrolling.clear()
        searchJob?.cancel()
        unreadJob?.cancel()
        reconcileSessionJob?.cancel()
        highestVisiblePostByTopic.forEach { (topicId, postNumber) ->
            container.reading.recordLastSeenPost(topicId, postNumber)
        }
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        loadingFeeds.clear()
        clearExcerptPrefetchState()
        ExcerptCache.clear()
        // Coil 内存缓存不感知账号 Cookie；切号时清空，防止受限附件跨账号短暂复用。
        container.imageLoader.memoryCache?.clear()
        readReportJobs.values.forEach { it.cancel() }
        readReportJobs.clear()
        TopicRepository.clearTimingsCache()
        readSendJobs.values.forEach { it.cancel() }
        readSendJobs.clear()
        readTrackers.clear()
        activeTopicIds.clear()
        currentVisiblePostsByTopic.clear()
        highestVisiblePostByTopic.clear()
        topicDetailCache.clear()
        scrollBusySignal.clear()
        knownPostsCountOnOpen.clear()
        _details.value = emptyMap()
        _categories.value = CategoriesUiState()
        _categoryTopics.value = emptyMap()
        _userTopicStates.value = emptyMap()
        _userRepliesStates.value = emptyMap()
        _search.value = SearchUiState()
        _profile.value = emptyMap()
        _userBadges.value = null
        _isUserBadgesLoading.value = false
        notificationCache.clear()
        _notificationFilter.value = NotificationFilter.All
        _pendingNotificationTarget.value = null
        synchronized(pendingNotificationRefreshTopics) {
            pendingNotificationRefreshTopics.clear()
        }
        _composer.value = ComposerUiState()
        _topicCreator.value = TopicCreatorUiState()
        _drafts.value = DraftsUiState()
        _feedStates.value = emptyMap()
        _chat.value = ChatUiState()
        _followList.value = FollowUiState()
        _blockedUsers.value = emptyList()
        blockedUsernames.value = emptySet()
        followedUsernames.value = emptySet()
        _trustReport.value = TrustReportUiState()
        _ldcState.value = LdcUiState()
        connectManualRefreshTimestamps.clear()
        connectSilentBackoffUntil = 0L
        ldcBackoffUntil = 0L
        ldcLastAttempt = null
        cdkScoreJob?.cancel()
        cdkScoreJob = null
        cdkScoreBackoffUntil = 0L
        _cdkScore.value = CdkScoreUiState()
        idleWarmupJob?.cancel()
        idleWarmupJob = null
        autoProbeJob?.cancel()
        autoProbeJob = null
        _history.value = HistoryUiState(entries = emptyList())
        AvatarCache.clear()
        repository.clearUserCardCache()
        _userCard.value = UserCardUiState()
    }

    fun logout() {
        if (_accounts.value.switching) return
        preAddAccountState = null
        preAddAccountUserId = null
        addAccountPrepared = false
        loginJob?.cancel()
        bootJob?.cancel()
        cancelWebAuth()
        setBackgroundNotification(false)
        autoJob?.cancel()
        searchJob?.cancel()
        unreadJob?.cancel()
        resumeAfterVerification = null
        _accounts.update { it.copy(switching = true, error = null) }
        viewModelScope.launch {
            try {
                val currentUserId = store.currentUserId()
                val token = store.storedCookies()[Config.SESSION_COOKIE]
                // 先确认账号库可读，避免退出时误删其它账号或把不可读当成空库。
                if (currentUserId > 0L) container.accountManager.account(currentUserId)
                store.noteRejectedAuthToken(SessionStore.authTokenHash(token), currentUserId, store.sessionGeneration)
                browser.stopSessionRequests()
                store.replaceBusinessCookies(null, null, userId = 0L)
                if (currentUserId > 0L) container.accountManager.remove(currentUserId)
                publishAccountState()
                clearAccountScopedState()
                container.reading.switchUser(0L)
                container.historyStore.switchUser(0L)
                container.preferences.edit { remove("cached_username") }
                repository.clearCachedCsrfToken()
                browser.reset()
                diagnostics.log("已登出并清除业务 Cookie（保留 Cloudflare 凭证）")
                _state.update { UiState(path = it.path, userAgent = it.userAgent, homeFeed = initialHomeFeed) }
                _loginUiState.update {
                    LoginUiState(
                        savedLogin = store.savedLogin().orEmpty(),
                        savedPassword = store.savedPassword().orEmpty(),
                        rememberPassword = store.isRememberPassword(),
                    )
                }
                enterWebScreen(Screen.Login)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _accounts.update { it.copy(error = "退出未完成：${error.message}") }
                runCatching { store.requireSessionRecovery() }
                    .onFailure { diagnostics.log("退出恢复标记暂未落盘，保持当前请求暂停") }
                sessionStorageNeedsRetry = true
                _state.update { it.copy(username = null, latest = it.latest.copy(error = "退出未完成，请稍后重试")) }
            } finally {
                _accounts.update { it.copy(switching = false) }
            }
        }
    }

    private suspend fun enterWebScreen(screen: Screen, reason: String = "", probePath: String? = null) {
        if (screen == Screen.Verify) {
            val accountGeneration = store.sessionGeneration
            verificationCleanupJob?.join()
            if (accountGeneration != store.sessionGeneration) return
        }
        if (screen == Screen.Verify && _state.value.screen == Screen.Verify) return
        if (screen == Screen.Verify) {
            verificationReturnScreen = _state.value.screen
            verificationDismissed = false
            verificationMismatch = false
            verificationProbePath = repository.resolveVerificationProbePath(probePath)
            verificationNetworkGeneration = container.dohCoordinator.currentEpoch.get().generation
            verifyCompletionProbeNotBefore = 0L
            verificationProbeAttempts = 0
            verificationConfirmedCandidateFp = null
            verificationUserId = store.currentUserId()
            verificationSessionGeneration = store.sessionGeneration
            verifying = false
            verificationAttempt++
            armAppPathProbe()
            store.requireVerification()
        }
        if (screen == Screen.Verify) diagnostics.recordInteractiveRequired(reason.ifBlank { "未知" })
        _state.update { it.copy(screen = screen) }
        // Login 现在是原生 Compose 页面，不需要在 WebView 里打开登录 URL。
        // Verify 时打开根 URL 触发 Cloudflare 人机验证。
        if (screen == Screen.Verify) {
            browser.beginInteractiveVerification()
        } else if (screen == Screen.Login) {
            browser.prewarmCfClearanceIfNeeded("进入登录页")
        }
    }

    private var verificationReturnScreen = Screen.List
    /** 触发当前轮交互验证的只读原请求路径；为空时复核回退至默认会话接口。 */
    private var verificationProbePath: String? = null
    private var verificationNetworkGeneration = 0L
    /** 验证完成后复核若遇 0 / 429 / 5xx 暂时无法判断，退避到此时间后再重探。 */
    private var verifyCompletionProbeNotBefore = 0L
    private var verificationProbeAttempts = 0
    private var verificationConfirmedCandidateFp: String? = null
    private var verificationUserId = 0L
    private var verificationSessionGeneration = 0L
    private var verificationCleanupJob: Job? = null

    private fun scheduleVerificationConfirmation(reason: String) {
        if (verificationProbeAttempts >= MAX_VERIFICATION_CONFIRMATION_PROBES) {
            verificationMismatch = true
            diagnostics.log("App 连接复核 ${verificationProbeAttempts} 次仍未确认，停止自动复核，保留手动重试")
            return
        }
        val waitMs = TopicRepository.rateLimitRemainingMs().coerceAtLeast(APP_PATH_PROBE_INTERVAL_MS)
        verifyCompletionProbeNotBefore = SystemClock.elapsedRealtime() + waitMs
        diagnostics.log("$reason (${verificationProbeAttempts}/$MAX_VERIFICATION_CONFIRMATION_PROBES)，保留候选凭据，${(waitMs + 999L) / 1_000L}s 后复核")
    }

    /**
     * 阅读同步被 CF 风控时的非打断式横幅，表示「阅读同步当前处于受阻状态」，而不是一次性事件：
     * 进入验证页时只是被验证页遮住，未通过就返回会继续显示；只有同步恢复成功或用户点「忽略」才收起。
     * 「忽略」只管到下一次挑战（熔断冷却 3 分钟后的下一轮上报），不会长时间吞掉提示。
     */
    private val _readSyncVerifyBanner = MutableStateFlow(false)
    val readSyncVerifyBanner: StateFlow<Boolean> = _readSyncVerifyBanner.asStateFlow()
    private var verificationFromReadSync = false
    @Volatile private var readSyncVerificationProbePath: String? = null

    private fun showReadSyncVerifyBanner(probePath: String?) {
        if (!_syncReadProgress.value) return
        readSyncVerificationProbePath = repository.resolveVerificationProbePath(probePath)
        if (_readSyncVerifyBanner.value) return
        diagnostics.log("阅读同步触发 CF 风控，提示用户手工验证")
        _readSyncVerifyBanner.value = true
    }

    fun dismissReadSyncVerifyBanner() {
        _readSyncVerifyBanner.value = false
        readSyncVerificationProbePath = null
    }

    fun openReadSyncVerification() {
        verificationFromReadSync = true
        val probePath = readSyncVerificationProbePath
        viewModelScope.launch { enterWebScreen(Screen.Verify, "阅读同步 CF 风控", probePath) }
    }
    private var verificationDismissed = false

    /** 有限次数的 App 复核仍未确认恢复；停止自动请求，等待用户重试或返回。 */
    @Volatile private var verificationMismatch = false

    /** 每次进入或重试验证递增，丢弃上一轮迟到的复核结果。 */
    private var verificationAttempt = 0

    /** 验证页打开期间旁路探测 App 请求通道的最早时间（elapsedRealtime），给 Gecko 页面留出首轮收口时间。 */
    private var appPathProbeNotBefore = 0L

    private fun armAppPathProbe() {
        appPathProbeNotBefore = SystemClock.elapsedRealtime() + APP_PATH_PROBE_FIRST_DELAY_MS
    }

    fun cancelVerification() {
        // 未通过验证就返回：横幅保持显示，用户可随时再次进入
        verificationFromReadSync = false
        verificationDismissed = true
        verificationMismatch = false
        verificationProbePath = null
        verifyCompletionProbeNotBefore = 0L
        verificationProbeAttempts = 0
        verificationConfirmedCandidateFp = null
        verifying = false
        verificationAttempt++
        resumeAfterVerification = null
        _state.update { it.copy(screen = if (verificationReturnScreen == Screen.Boot) Screen.Login else verificationReturnScreen) }
        verificationCleanupJob = viewModelScope.launch {
            browser.stopSessionRequests()
            browser.restoreClearanceIfVerificationAbandoned()
        }
    }

    /** 验证页加载异常（chrome-error 或数秒内无页面完成），用于提示检查网络或代理。 */
    fun isVerificationPageStalled(): Boolean = browser.isVerificationPageStalled()

    fun verifyPageState(): org.linuxdo.android.net.BrowserSession.VerifyPageState =
        if (verificationMismatch) org.linuxdo.android.net.BrowserSession.VerifyPageState.MISMATCH
        else browser.verifyPageState()

    fun retryVerification() {
        verificationMismatch = false
        verifyCompletionProbeNotBefore = 0L
        verificationProbeAttempts = 0
        verificationConfirmedCandidateFp = null
        verificationUserId = store.currentUserId()
        verificationSessionGeneration = store.sessionGeneration
        verifying = false
        store.requireVerification()
        verificationNetworkGeneration = container.dohCoordinator.currentEpoch.get().generation
        verificationAttempt++
        armAppPathProbe()
        val attempt = verificationAttempt
        viewModelScope.launch {
            verificationCleanupJob?.join()
            if (attempt != verificationAttempt || _state.value.screen != Screen.Verify ||
                store.sessionGeneration != verificationSessionGeneration || store.currentUserId() != verificationUserId) return@launch
            store.requireVerification()
            browser.beginInteractiveVerification()
        }
    }

    private fun restartVerificationIfNetworkChanged(): Boolean {
        val generation = container.dohCoordinator.currentEpoch.get().generation
        if (generation == verificationNetworkGeneration) return false
        if (TopicRepository.isRateLimitCoolingDown()) return true
        diagnostics.log("验证期间网络已切换(gen=$verificationNetworkGeneration->$generation)，保留原请求并重新加载验证页")
        retryVerification()
        return true
    }

    private suspend fun loadLatest(page: Int, pullRefresh: Boolean) {
        if (sessionStorageNeedsRetry) {
            boot()
            return
        }
        loadFeed(_state.value.homeFeed, page, pullRefresh)
    }

    private suspend fun loadFeed(feed: HomeFeedType, page: Int, pullRefresh: Boolean) {
        val requestScopeGeneration = accountScopeGeneration
        val current = _feedStates.value[feed] ?: if (_state.value.homeFeed == feed) _state.value.latest else TopicListUiState()
        val existingFeedGeneration = loadingFeeds.putIfAbsent(feed, requestScopeGeneration)
        if (existingFeedGeneration != null || current.refreshing || current.loadingMore) {
            if (existingFeedGeneration == null) {
                loadingFeeds.remove(feed, requestScopeGeneration)
            }
            // 已有加载在途，不重复发请求。但下拉刷新不能静默吞掉：下拉指示器要等
            // refreshing 由 true 回落才收起，直接 return 会让它一直转。
            // 让这次下拉跟随在途加载走完一次完整跳变（带超时兜底，避免无限等待）。
            if (pullRefresh && page == 0 && !current.refreshing) {
                setFeedRefreshing(feed, true)
                var waitedMs = 0L
                while (loadingFeeds.containsKey(feed) && waitedMs < FEED_REFRESH_JOIN_TIMEOUT_MS) {
                    delay(50)
                    waitedMs += 50
                }
                setFeedRefreshing(feed, false)
            }
            return
        }

        val loadingState = current.copy(
            refreshing = pullRefresh && page == 0,
            loadingMore = page > 0,
            initialLoading = page == 0 && current.items.isEmpty() && !pullRefresh,
            error = null,
        )
        _feedStates.update { it + (feed to loadingState) }
        if (_state.value.homeFeed == feed) {
            _state.update { it.copy(latest = loadingState) }
        }

        try {
            val sortOrder = _topicSortOrder.value.apiValue
            val sortAscending = _topicSortAscending.value.takeIf { sortOrder != null }
            val result = repository.fetchFeed(feed, page, awaitCategories = false, order = sortOrder, ascending = sortAscending)
            if (requestScopeGeneration != accountScopeGeneration) return
            if (page == 0 && pageJobs["sync:categories"]?.isActive != true && repository.cachedCategoryList() == null) {
                syncCategoriesInBackground()
            }
            val knownCategories = repository.cachedCategoryMap()
            val freshItems = if (knownCategories.isNotEmpty()) {
                result.items.map { item ->
                    val cat = item.category ?: item.topic.categoryId?.let { knownCategories[it] }
                    if (cat != item.category) item.copy(category = cat) else item
                }
            } else result.items
            val existing = _feedStates.value[feed]?.items ?: emptyList()
            ExcerptCache.loadBatchFromStore(freshItems.map { it.topic.id })
            val merged = if (page == 0) {
                freshItems.distinctBy { it.topic.id }
            } else {
                (existing + freshItems).distinctBy { it.topic.id }
            }
            // 对齐通知分页：防死循环检测页码推进，而非条目增加。高频重叠由页码单调递增穿透。
            val hasMore = result.hasMore && result.items.isNotEmpty() && (page == 0 || page > current.page)
            val newState = current.copy(
                items = merged,
                page = page,
                hasMore = hasMore,
                refreshing = false,
                loadingMore = false,
                initialLoading = false,
                error = null,
                pendingPage = if (page == 0) null else current.pendingPage,
                incomingTopicIds = if (page == 0) emptySet() else current.incomingTopicIds,
            )
            _feedStates.update { it + (feed to newState) }
            if (_state.value.homeFeed == feed) {
                _state.update { it.copy(latest = newState) }
            }
            refreshCookieInfo()
        } catch (error: CancellationException) {
            throw error
        } catch (error: NeedsInteractiveVerification) {
            if (requestScopeGeneration != accountScopeGeneration) return
            settleFeedLoading(feed)
            enterWebScreen(Screen.Verify, error.message.orEmpty(), error.readProbePath)
        } catch (error: HttpStatusException) {
            if (requestScopeGeneration != accountScopeGeneration) return
            val msg = if (error.status == 403 && (_state.value.username == null || (feed == HomeFeedType.Unread || feed == HomeFeedType.Posted))) {
                "请先登录账号后查看${feed.label}"
            } else {
                error.message ?: "加载失败 (HTTP ${error.status})"
            }
            diagnostics.log("加载 ${feed.label} 失败: $msg")
            settleFeedLoading(feed, msg)
        } catch (error: Exception) {
            if (requestScopeGeneration != accountScopeGeneration) return
            diagnostics.log("加载 ${feed.label} 异常: ${error.message}")
            settleFeedLoading(feed, error.message ?: error.javaClass.simpleName)
        } finally {
            loadingFeeds.remove(feed, requestScopeGeneration)
        }
    }

    /** 只翻转下拉刷新标志，不动已有数据：用于让被合并的下拉请求也有一次完整的状态跳变。 */
    private fun setFeedRefreshing(feed: HomeFeedType, refreshing: Boolean) {
        val current = _feedStates.value[feed] ?: if (_state.value.homeFeed == feed) _state.value.latest else TopicListUiState()
        val next = current.copy(refreshing = refreshing)
        _feedStates.update { it + (feed to next) }
        if (_state.value.homeFeed == feed) {
            _state.update { it.copy(latest = next) }
        }
    }

    private fun settleFeedLoading(feed: HomeFeedType, message: String? = null) {
        val current = _feedStates.value[feed] ?: if (_state.value.homeFeed == feed) _state.value.latest else TopicListUiState()
        val settled = current.copy(
            refreshing = false,
            loadingMore = false,
            initialLoading = false,
            error = message,
        )
        _feedStates.update { it + (feed to settled) }
        if (_state.value.homeFeed == feed) {
            _state.update { it.copy(latest = settled) }
        }
    }

    private fun settleLoading(message: String? = null) =
        settleFeedLoading(_state.value.homeFeed, message)

    private fun refreshCookieInfo() {
        val cookies = store.syncFromWebView()
        _state.update {
            it.copy(cookieNames = cookies.keys.sorted(), cookieSavedAt = store.savedAtMillis())
        }
    }

    private companion object {
        private const val MAX_VERIFICATION_CONFIRMATION_PROBES = 3
        /** 长帖切倒序时一次拉取的尾部楼层数,与 fetchPosts 的单批上限一致。 */
        const val TAIL_WINDOW = 20

        /** 单次阅读进度上报最多携带多少个楼层号。 */
        const val READ_REPORT_POST_CAP = 60

        /** 下拉刷新跟随在途加载的最长等待，超时就收起指示器，避免卡在转圈。 */
        const val FEED_REFRESH_JOIN_TIMEOUT_MS = 15_000L

        private const val EXCERPT_SESSION_BUDGET = 15
        private val excerptAttemptedIds = mutableSetOf<Long>()
        private var excerptNextAllowedAt = 0L
    }
}

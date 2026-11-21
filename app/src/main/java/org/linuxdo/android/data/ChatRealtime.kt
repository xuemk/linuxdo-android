package org.linuxdo.android.data

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

data class ChatLiveState(
    val generation: Long = -1,
    val channels: List<ChatChannelSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class ChatLiveEvent(
    val generation: Long,
    val channelId: Long = 0,
    val message: ChatMessageSummary? = null,
    val deletedIds: Set<Long> = emptySet(),
    val resync: Boolean = false,
)

internal fun mergeChatMessages(old: List<ChatMessageSummary>, incoming: List<ChatMessageSummary>): List<ChatMessageSummary> =
    (old + incoming).associateBy { it.id }.values.sortedBy { it.id }

/** 由前台界面和现有保活服务共享连接，避免两个连接重复计数和提醒。所有状态在主线程处理。 */
class ChatRealtime(
    private val repository: TopicRepository,
    private val store: SessionStore,
    private val onDirectMessage: (ChatChannelSummary, ChatMessageSummary) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val _state = MutableStateFlow(ChatLiveState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChatLiveEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    private var foreground = false
    private var background = false
    private var job: Job? = null
    private var refreshJob: Job? = null
    private val refreshGate = Mutex()
    private var lastRefresh = 0L
    private var revision = 0L
    private val channelRevisions = mutableMapOf<Long, Long>()
    private val readJobs = mutableMapOf<Long, Job>()
    private val pendingReads = mutableMapOf<Long, Long>()
    var visibleChannelId: Long? = null

    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) visibleChannelId = null
        reconcile()
    }

    fun setBackground(active: Boolean) {
        scope.launch {
            background = active
            reconcile()
        }
    }

    fun reset() {
        job?.cancel(); job = null
        refreshJob?.cancel(); refreshJob = null
        readJobs.values.forEach { it.cancel() }; readJobs.clear()
        pendingReads.clear()
        visibleChannelId = null
        lastRefresh = 0L
        channelRevisions.clear()
        _state.value = ChatLiveState(generation = store.sessionGeneration)
    }

    private fun current(generation: Long) = generation == store.sessionGeneration && !store.sessionChanging && store.hasSession()

    private fun reconcile() {
        if (_state.value.generation != store.sessionGeneration) reset()
        val enabled = foreground || (background && store.isBackgroundNotificationEnabled())
        if (!enabled || !store.hasSession() || store.currentUserId() <= 0) {
            job?.cancel(); job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch { monitor() }
    }

    fun refresh() {
        reconcile()
        if (refreshJob?.isActive == true || !current(store.sessionGeneration)) return
        refreshJob = scope.launch {
            try { refreshSnapshot(store.sessionGeneration) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(error = error.message ?: "聊天列表刷新失败") } }
        }
    }

    /** 息屏后沿用已有系统唤醒检查，不另起高频后台计时器。 */
    suspend fun pollWhenDisconnected() = withContext(Dispatchers.Main.immediate) {
        if (job?.isActive == true || !store.isBackgroundNotificationEnabled() || !store.hasSession()) return@withContext
        if (_state.value.generation != store.sessionGeneration) reset()
        if (lastRefresh > 0L && SystemClock.elapsedRealtime() - lastRefresh < 30_000) return@withContext
        try { refreshSnapshot(store.sessionGeneration) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 由现有后台轮询和网络退避安排下一次检查。 */ }
    }

    private suspend fun refreshSnapshot(generation: Long) = refreshGate.withLock {
        if (!current(generation)) return@withLock
        val startedRevision = revision
        _state.update { it.copy(loading = true) }
        try {
            val channels = repository.fetchChatChannels(silent = true)
            if (!current(generation)) return@withLock
            val existing = _state.value.channels.associateBy { it.id }
            // 请求期间新到的消息/已读确认优先，旧快照不能覆盖刚刚更新的角标。
            _state.value = ChatLiveState(generation, channels.map {
                if ((channelRevisions[it.id] ?: 0) > startedRevision) existing[it.id] ?: it else it
            })
            val baseline = store.hasChatNotificationBaseline()
            _state.value.channels.filter { it.isDirectMessage }.forEach { channel ->
                channel.lastMessage?.let { message ->
                    if (baseline && channel.unreadCount > 0 && message.id > store.chatLastSeenMessageId(channel.id) &&
                        message.user?.id != store.currentUserId() && channel.membership?.muted != true &&
                        visibleChannelId != channel.id && store.isBackgroundNotificationEnabled()) {
                        onDirectMessage(channel, message)
                    }
                    store.saveChatLastSeenMessageId(channel.id, message.id)
                }
            }
            store.markChatNotificationBaseline()
            lastRefresh = SystemClock.elapsedRealtime()
        } finally {
            if (current(generation)) _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun monitor() {
        var generation = store.sessionGeneration
        var clientId = UUID.randomUUID().toString().replace("-", "")
        val cursors = mutableMapOf<String, Long>()
        var sequence = 0L
        var retryMs = 5_000L
        var subscribed = false
        while (currentCoroutineContext().isActive && store.hasSession()) {
            try {
                if (!current(generation)) {
                    generation = store.sessionGeneration
                    _state.value = ChatLiveState(generation)
                    channelRevisions.clear()
                    pendingReads.clear()
                    visibleChannelId = null
                    cursors.clear()
                    clientId = UUID.randomUUID().toString().replace("-", "")
                    lastRefresh = 0L
                    subscribed = false
                    delay(1_000)
                    continue
                }
                if (SystemClock.elapsedRealtime() - lastRefresh >= 30_000 || lastRefresh == 0L) {
                    refreshSnapshot(generation)
                    _events.emit(ChatLiveEvent(generation, resync = true))
                }
                val names = buildSet {
                    add("/chat/new-channel")
                    add("/chat/user-tracking-state/${store.currentUserId()}")
                    add("/chat/bulk-user-tracking-state/${store.currentUserId()}")
                    _state.value.channels.filter { it.isDirectMessage || it.membership?.following == true || it.id == visibleChannelId }
                        .forEach { add("/chat/${it.id}") }
                }
                cursors.keys.retainAll(names)
                names.forEach { cursors.putIfAbsent(it, -1L) }
                val batch = withTimeoutOrNull(35_000) {
                    repository.pollChatMessages(clientId, cursors.toMap(), ++sequence)
                }
                if (!current(generation)) continue
                batch.orEmpty().forEach { event ->
                    if (event.channel == "/__status") {
                        (event.data as? JsonObject)?.forEach { (name, value) ->
                            if (name in cursors) (value as? JsonPrimitive)?.longOrNull?.let { cursors[name] = it }
                        }
                    } else if (event.channel in cursors && event.messageId > (cursors[event.channel] ?: -1L)) {
                        cursors[event.channel] = event.messageId
                        applyEvent(generation, event)
                    }
                }
                // 首次订阅建立游标后再读一次快照，补上初始化期间的消息。
                if (!subscribed && batch != null) {
                    subscribed = true
                    refreshSnapshot(generation)
                    _events.emit(ChatLiveEvent(generation, resync = true))
                }
                retryMs = 5_000L
                delay(250)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                delay(1_000) // 会话代次变化由下一轮重新建立订阅。
            } catch (error: Exception) {
                if (current(generation)) _state.update { it.copy(error = "聊天连接暂时中断，正在重连") }
                lastRefresh = 0L
                delay(maxOf(retryMs, TopicRepository.mainSiteGateWaitMs()))
                retryMs = (retryMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun applyEvent(generation: Long, event: TopicBusMessage) {
        val data = event.data as? JsonObject ?: return
        val channelId = event.channel.removePrefix("/chat/").toLongOrNull()
        if (channelId == null) {
            // 新私聊、跨设备已读等事件需要服务端的权威计数。
            lastRefresh = 0L
            return
        }
        val message = (data["chat_message"] as? JsonObject)?.let {
            runCatching { json.decodeFromJsonElement<ChatMessageSummary>(it).copy(channelId = channelId) }.getOrNull()
        }
        val type = (data["type"] as? JsonPrimitive)?.contentOrNull
        if (message != null) {
            val channel = _state.value.channels.firstOrNull { it.id == channelId }
            val newMessage = type == "sent" && message.id > (channel?.lastMessage?.id ?: 0L)
            if (channel != null) {
                val incoming = message.user?.id != store.currentUserId()
                val updated = channel.copy(
                    lastMessage = if (message.id >= (channel.lastMessage?.id ?: 0L)) message else channel.lastMessage,
                    unreadCount = channel.unreadCount + if (newMessage && incoming) 1 else 0,
                )
                updateChannel(updated)
                if (newMessage && incoming && channel.isDirectMessage && channel.membership?.muted != true &&
                    visibleChannelId != channelId && store.isBackgroundNotificationEnabled()) {
                    onDirectMessage(updated, message)
                }
                if (channel.isDirectMessage) store.saveChatLastSeenMessageId(channelId, message.id)
            }
            _events.emit(ChatLiveEvent(generation, channelId, message))
        }
        val deleted = when (type) {
            "delete" -> setOfNotNull((data["deleted_id"] as? JsonPrimitive)?.longOrNull)
            "bulk_delete" -> (data["deleted_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }.toSet()
            else -> emptySet()
        }
        if (deleted.isNotEmpty()) {
            _events.emit(ChatLiveEvent(generation, channelId, deletedIds = deleted))
            lastRefresh = 0L
        }
    }

    private fun updateChannel(channel: ChatChannelSummary) {
        channelRevisions[channel.id] = ++revision
        _state.update { it.copy(channels = it.channels.map { old -> if (old.id == channel.id) channel else old }
            .sortedByDescending { it.lastMessage?.createdAt.orEmpty() }) }
    }

    fun markRead(channelId: Long, messageId: Long) {
        if (!foreground || visibleChannelId != channelId) return
        pendingReads[channelId] = maxOf(messageId, pendingReads[channelId] ?: 0L)
        if (readJobs[channelId]?.isActive == true) return
        val channel = _state.value.channels.firstOrNull { it.id == channelId } ?: return
        if (messageId <= (channel.membership?.lastReadMessageId ?: 0L)) return
        val generation = store.sessionGeneration
        readJobs[channelId] = scope.launch {
            try {
                while (foreground && visibleChannelId == channelId && current(generation)) {
                    val target = pendingReads.remove(channelId) ?: break
                    repository.markChatChannelRead(channelId, target)
                    if (!current(generation)) return@launch
                    val fresh = _state.value.channels.firstOrNull { it.id == channelId } ?: return@launch
                    updateChannel(fresh.copy(
                        membership = (fresh.membership ?: ChatMembership()).copy(lastReadMessageId = target),
                        unreadCount = if (target >= (fresh.lastMessage?.id ?: 0L)) 0 else fresh.unreadCount,
                    ))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 保留未读，下一次可见状态变化时重试。 */ }
        }
    }
}

package org.linuxdo.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.linuxdo.android.data.ChatChannelSummary
import org.linuxdo.android.data.NotificationFilter
import org.linuxdo.android.ui.design.*
import org.linuxdo.android.ui.screen.ChatList
import org.linuxdo.android.ui.screen.EmptyNotice
import org.linuxdo.android.ui.screen.ErrorNotice

internal enum class MessageSection(val label: String) { Notifications("通知"), Chat("聊天") }

@Composable
internal fun MessagesScreen(
    feedDisplayMode: String,
    section: MessageSection,
    onSection: (MessageSection) -> Unit,
    directMessages: Boolean,
    onDirectMessages: (Boolean) -> Unit,
    notifications: ProfileUiState,
    notificationPages: Map<NotificationFilter, ProfileUiState>,
    chat: ChatUiState,
    filter: NotificationFilter,
    unreadFilters: Set<NotificationFilter>,
    unreadNotifications: Int,
    active: Boolean,
    scrollToTopRequest: Int,
    onFilter: (NotificationFilter) -> Unit,
    onRefreshNotifications: () -> Unit,
    onMore: () -> Unit,
    onMarkAllRead: () -> Unit,
    onRead: (Long) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenUser: (String) -> Unit,
    onRefreshChat: () -> Unit,
    onOpenChannel: (ChatChannelSummary) -> Unit,
) {
    var confirmRead by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val chatOpen = section == MessageSection.Chat
    val unreadChannels = remember(chat.channels) { chat.channels.filter { it.unreadCount > 0 && it.membership?.muted != true } }
    LaunchedEffect(active, section) {
        if (active) {
            if (chatOpen) onRefreshChat() else onRefreshNotifications()
        }
    }
    val filters = NotificationFilter.entries
    MessagePages(
        labels = remember { MessageSection.entries.map { it.label } },
        selected = section.ordinal,
        onSelect = { onSection(MessageSection.entries[it]) },
        active = active,
        swipeContent = false,
        unread = { if (it == MessageSection.Chat.ordinal) unreadChannels.isNotEmpty() else unreadNotifications > 0 },
        actionWidth = if (chatOpen) 76.dp else 48.dp,
        actions = {
            Row {
                if (chatOpen) {
                    IconButton(onClick = { showSearch = !showSearch }, modifier = Modifier.width(38.dp)) {
                        Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search,
                            if (showSearch) "关闭搜索" else "搜索聊天", tint = IosTheme.colors.label)
                    }
                    IconButton(onClick = onRefreshChat, enabled = !chat.loadingChannels, modifier = Modifier.width(38.dp)) {
                        Icon(Icons.Default.Refresh, "刷新聊天", tint = IosTheme.colors.label)
                    }
                } else {
                    IconButton(onClick = { confirmRead = true },
                        modifier = Modifier.semantics { contentDescription = "将通知全部标为已读" }) {
                        BroomGlyph(IosTheme.colors.label)
                    }
                }
            }
        },
    ) { group ->
        if (group == MessageSection.Chat.ordinal) {
            MessagePages(
                labels = listOf("频道", "直接消息"),
                selected = if (directMessages) 1 else 0,
                onSelect = { onDirectMessages(it == 1) },
                active = active && chatOpen,
                compactHeader = true,
                unread = { index -> unreadChannels.any { it.isDirectMessage == (index == 1) } },
            ) { page ->
                val direct = page == 1
                ChatList(chat, direct, showSearch, feedDisplayMode, if (chatOpen && direct == directMessages) scrollToTopRequest else 0,
                    onRefreshChat, onOpenChannel, active = active && chatOpen && direct == directMessages)
            }
        } else {
            MessagePages(
                labels = remember { filters.map { it.label } },
                selected = filter.ordinal,
                onSelect = { onFilter(filters[it]) },
                active = active && !chatOpen,
                compactHeader = true,
                unread = { index ->
                    val tab = filters[index]
                    tab != NotificationFilter.All && (tab in unreadFilters ||
                        notifications.content.entries.any { it.unread && tab.includes(it.notificationType) })
                },
            ) { page ->
                val pageFilter = filters[page]
                val current = pageFilter == filter
                val cached = notificationPages[pageFilter]
                val all = notificationPages[NotificationFilter.All]
                val pageState = if (current) notifications else remember(pageFilter, cached, all) {
                    when {
                        cached != null && (all == null || cached.loadedAt >= all.loadedAt) -> cached
                        all != null -> all.copy(content = all.content.copy(
                            entries = all.content.entries.filter { pageFilter.includes(it.notificationType) }))
                        else -> ProfileUiState(loading = true)
                    }
                }
                NotificationList(pageState, feedDisplayMode, active && !chatOpen && current,
                    if (!chatOpen && current) scrollToTopRequest else 0,
                    onRefreshNotifications, onMore, onRead, onOpenTopic, onOpenUser)
            }
        }
    }
    if (confirmRead) ConfirmReadNotifications(onDismiss = { confirmRead = false }, onConfirm = onMarkAllRead)
}

/** Both header and list drive the same pager. Publish the new filter only after settling. */
@Composable
private fun MessagePages(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    active: Boolean,
    swipeContent: Boolean = true,
    compactHeader: Boolean = false,
    unread: (Int) -> Boolean,
    actionWidth: Dp = 0.dp,
    actions: @Composable () -> Unit = {},
    content: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = selected) { labels.size }
    val scope = rememberCoroutineScope()
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    var animation by remember { mutableStateOf<Job?>(null) }
    var anchor by remember { mutableIntStateOf(selected) }
    val boundary = rememberTabBoundaryFeedback()

    // 外部入口（如系统通知）可以更改当前板块，恢复时以入口指定的页为准。
    LaunchedEffect(selected) {
        animation?.cancel()
        if (pager.currentPage != selected || pager.currentPageOffsetFraction != 0f) {
            pager.scrollToPage(selected)
        }
    }

    fun settle(target: Int, tapped: Boolean = false) {
        animation?.cancel()
        animation = scope.launch {
            val distance = abs(target - pager.currentPage - pager.currentPageOffsetFraction)
            val duration = (220 * distance).toInt().coerceIn(85, 220)
            pager.animateScrollToPage(target, animationSpec = tween(duration, easing = FastOutSlowInEasing))
            if (target != latestSelected || tapped) latestOnSelect(target)
        }
    }

    val gesture = Modifier.tabSwipeGesture(
        enabled = active,
        onStart = {
            animation?.cancel()
            anchor = pager.currentPage
        },
        onDrag = { delta ->
            val width = pager.layoutInfo.pageSize
            if (width > 0) {
                val position = pager.currentPage + pager.currentPageOffsetFraction
                val target = (position - delta / width).coerceIn(
                    (anchor - 1).coerceAtLeast(0).toFloat(),
                    (anchor + 1).coerceAtMost(labels.lastIndex).toFloat(),
                )
                pager.dispatchRawDelta((target - position) * width)
            }
        },
        onEnd = { step ->
            val target = anchor + step
            if (target !in labels.indices) boundary()
            settle(target.coerceIn(labels.indices))
        },
    )
    Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)
        .then(if (swipeContent) gesture else Modifier)) {
        ListNavigationHeader {
            SectionHeader(
                labels = labels,
                selected = selected,
                onSelect = { settle(it, tapped = true) },
                modifier = if (swipeContent) Modifier else gesture,
                indicatorPosition = { pager.currentPage + pager.currentPageOffsetFraction },
                unread = unread,
                compact = compactHeader,
                actionWidth = actionWidth,
                actions = actions,
            )
        }

        // 内容横滑由上面的单一手势驱动，嵌套分类到边界时不会连带切换父板块。
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = false, verticalAlignment = Alignment.Top) { page -> content(page) }
    }
}

@Composable
private fun NotificationList(
    state: ProfileUiState,
    feedDisplayMode: String,
    active: Boolean,
    scrollToTopRequest: Int,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onRead: (Long) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val list = rememberLazyListState()
    ScrollToTopOnRequest(list, scrollToTopRequest)
    val entries = state.content.entries
    val nearEnd by remember { derivedStateOf {
        val layout = list.layoutInfo
        layout.visibleItemsInfo.any { it.index >= layout.totalItemsCount - 3 }
    } }
    val canLoadMore = state.loaded && !state.loading && state.error == null && state.content.hasMore
    LaunchedEffect(active, nearEnd, canLoadMore, entries.size) {
        if (active && nearEnd && canLoadMore && entries.size >= 20) onMore()
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().scrollListBars(list, active), contentPadding = PaddingValues(bottom = 72.dp)) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            NotificationRow(entry, onOpenTopic, onRead, onOpenUser)
            if (index < entries.lastIndex) FeedItemDivider(feedDisplayMode)
        }
        state.error?.let { message -> item { ErrorNotice(message, onRefresh) } }
        if (state.loading) item { IosLoadingBox() }
        if (canLoadMore && entries.size < 20) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onMore) { Text("加载更早的记录") }
            }
        }
        if (state.loaded && entries.isEmpty() && !state.loading && state.error == null && !state.content.hasMore) {
            item { EmptyNotice() }
        }
    }
}

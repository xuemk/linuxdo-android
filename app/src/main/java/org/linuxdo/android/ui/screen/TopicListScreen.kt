package org.linuxdo.android.ui.screen

import org.linuxdo.android.ui.html.CookedParser

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import org.linuxdo.android.data.avatarUrlOf

import org.linuxdo.android.data.TopicPage
import org.linuxdo.android.data.TopicNotificationLevel
import org.linuxdo.android.data.PostFlagReason
import org.linuxdo.android.data.FloatingAction
import org.linuxdo.android.data.ExcerptCache
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import org.jsoup.Jsoup
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.Topic
import org.linuxdo.android.data.originalImageUrl
import org.linuxdo.android.data.thumbnailUrlForList
import org.linuxdo.android.net.IMAGE_AUTO_RETRY_LIMIT
import org.linuxdo.android.net.awaitImageRetrySlot
import org.linuxdo.android.net.isTransientImageError
import org.linuxdo.android.data.TopicSortOrder
import org.linuxdo.android.data.HomeFeedType
import org.linuxdo.android.data.TopicListItem
import org.linuxdo.android.ui.TopicUnreadDotPosition
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosLargeTitleScaffold
import org.linuxdo.android.ui.design.IosLoadingBox
import org.linuxdo.android.ui.design.IosMetrics
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.design.UserAvatar
import org.linuxdo.android.ui.design.avatarShape
import org.linuxdo.android.ui.format.RelativeTime

data class TopicListUiState(
    val items: List<TopicListItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val pendingPage: TopicPage? = null,
    val incomingTopicIds: Set<Long> = emptySet(),
    val highlightedTopicIds: Set<Long> = emptySet(),
    val needsRefresh: Boolean = false,
)

/** "最新"与"某分类下的帖子"用同一个列表页,只是标题与数据源不同。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicListScreen(
    title: String,
    state: TopicListUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenTopic: (Topic) -> Unit,
    onOpenItem: ((TopicListItem) -> Unit)? = null,
    onRemoveBookmark: ((TopicListItem, (String) -> Unit) -> Unit)? = null,
    onOpenUser: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showLargeTitle: Boolean = true,
    active: Boolean = true,
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
    currentFeed: HomeFeedType? = null,
    feedStates: Map<HomeFeedType, TopicListUiState> = emptyMap(),
    onFeedSelect: ((HomeFeedType) -> Unit)? = null,
    onPreloadFeed: ((HomeFeedType) -> Unit)? = null,
    hasUnread: Boolean = false,
    categoryVariants: List<Category> = emptyList(),
    currentCategoryId: Int? = null,
    onSelectCategoryVariant: ((Category) -> Unit)? = null,
    onApplyIncoming: (() -> Unit)? = null,
    onSetNotificationLevel: ((Topic, TopicNotificationLevel) -> Unit)? = null,
    onCreateTopic: (() -> Unit)? = null,
    onBookmarkTopic: ((Topic, (String) -> Unit) -> Unit)? = null,
    onFlagTopic: ((Topic, Int, String?, () -> Unit, (String) -> Unit) -> Unit)? = null,
    onSaveTopic: ((Topic) -> Unit)? = null,
    sortOrder: TopicSortOrder = TopicSortOrder.Default,
    sortAscending: Boolean = false,
    onSortChange: (TopicSortOrder) -> Unit = {},
    floatingBallEnabled: Boolean = true,
    floatingBallActions: List<FloatingAction> = FloatingAction.DEFAULT_ACTIONS,
    floatingBallXRatio: Float = 0.95f,
    floatingBallYRatio: Float = 1.0f,
    floatingBallOnRight: Boolean = true,
    onFloatingBallPositionChange: (Float, Float) -> Unit = { _, _ -> },
    onNavigateToDrafts: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToCategories: (() -> Unit)? = null,
    onNavigateToHistory: (() -> Unit)? = null,
    onNavigateToConnect: (() -> Unit)? = null,
    onNavigateToChat: (() -> Unit)? = null,
    onNavigateToApps: (() -> Unit)? = null,
    onNavigateToBookmarks: (() -> Unit)? = null,
    onNavigateToFollowing: (() -> Unit)? = null,
    onNavigateToLikes: (() -> Unit)? = null,
    onNavigateToReplies: (() -> Unit)? = null,
    feedGraphicMode: Boolean = true,
    feedDisplayMode: String = "no_excerpt",
    showTopicUnreadDot: Boolean = true,
    topicUnreadDotPosition: TopicUnreadDotPosition = TopicUnreadDotPosition.Avatar,
    onPrefetchExcerpt: ((Any, List<Long>, () -> Set<Long>) -> Unit)? = null,
    onPrefetchExcerpts: ((List<Topic>) -> Unit)? = null,
    onScrollBusyChanged: ((Any, Boolean) -> Unit)? = null,
    onBarsVisibilityChange: ((Boolean) -> Unit)? = null,
    barsVisible: Boolean = true,
    blockedUsernames: Set<String> = emptySet(),
) {
    // 每个 feed 的滚动位置用 rememberLazyListState（内部走 rememberSaveable），
    // 确保在 recomposition 或临时移出组合树后仍能还原 firstVisibleItemIndex + scrollOffset。
    // 旧方案 remember { mutableMapOf() } + LazyListState() 不走 saveable，回复后
    // refreshTopic silent 触发大量 state 更新时极易丢失位置。
    // HomeFeedType 枚举顺序：Latest(0)、Unread(1)、Hot(2)、Posted(3)
    val feedStateLatest = rememberLazyListState()
    val feedStateUnread = rememberLazyListState()
    val feedStateHot = rememberLazyListState()
    val feedStatePosted = rememberLazyListState()
    val categoryListStates = remember { mutableMapOf<Int, LazyListState>() }
    val defaultListState = rememberLazyListState()
    var pendingSettledFeed by remember { mutableStateOf<HomeFeedType?>(null) }
    LaunchedEffect(currentFeed) {
        if (currentFeed != null && currentFeed == pendingSettledFeed) {
            pendingSettledFeed = null
        }
    }
    val effectiveFeed = pendingSettledFeed ?: currentFeed
    val effectiveState = if (effectiveFeed != null && effectiveFeed != currentFeed) {
        feedStates[effectiveFeed] ?: state
    } else {
        state
    }
    // 将各 feed 的 rememberLazyListState 对象放入一个普通 Map，供统一访问。
    // 不用 getOrPut 动态创建，每个已知 feed 提前声明好对应的 saveable state。
    val feedListStates: Map<HomeFeedType, LazyListState> = remember(
        feedStateLatest, feedStateUnread, feedStateHot, feedStatePosted
    ) {
        mapOf(
            HomeFeedType.Latest to feedStateLatest,
            HomeFeedType.Unread to feedStateUnread,
            HomeFeedType.Hot to feedStateHot,
            HomeFeedType.Posted to feedStatePosted,
        )
    }
    val listState = when {
        effectiveFeed != null -> feedListStates[effectiveFeed] ?: feedStateLatest
        currentCategoryId != null -> categoryListStates.getOrPut(currentCategoryId) { LazyListState() }
        else -> defaultListState
    }
    val scrollBusySource = remember { Any() }
    LaunchedEffect(active, listState, listState.isScrollInProgress) {
        onScrollBusyChanged?.invoke(scrollBusySource, active && listState.isScrollInProgress)
    }
    DisposableEffect(scrollBusySource) {
        onDispose { onScrollBusyChanged?.invoke(scrollBusySource, false) }
    }

    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!active) return Offset.Zero
                val deltaY = available.y
                if (deltaY < -15f) {
                    onBarsVisibilityChange?.invoke(false)
                } else if (deltaY > 15f) {
                    onBarsVisibilityChange?.invoke(true)
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(active, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (!active) return@LaunchedEffect
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 20) {
            onBarsVisibilityChange?.invoke(true)
        }
    }
    DisposableEffect(active) {
        onDispose {
            if (!active || onBack != null) {
                onBarsVisibilityChange?.invoke(true)
            }
        }
    }
    val pullState = rememberPullToRefreshState()
    val latestOnOpenMiniApps by rememberUpdatedState(onNavigateToApps)
    val miniAppsHeaderThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val miniAppsHeaderGesture = if (active && currentFeed != null && onNavigateToApps != null) {
        Modifier.pointerInput(listState, miniAppsHeaderThreshold) {
            var pullDistance = 0f
            detectVerticalDragGestures(
                onDragStart = { pullDistance = 0f },
                onDragCancel = { pullDistance = 0f },
                onDragEnd = {
                    if (pullDistance >= miniAppsHeaderThreshold && !listState.canScrollBackward) latestOnOpenMiniApps?.invoke()
                },
                onVerticalDrag = { change, amount ->
                    if (!listState.canScrollBackward) { pullDistance = (pullDistance + amount).coerceAtLeast(0f); change.consume() }
                },
            )
        }
    } else Modifier
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var noticeToast by remember { mutableStateOf<String?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    var flagTargetTopic by remember { mutableStateOf<Topic?>(null) }
    var flagSubmitting by remember { mutableStateOf(false) }
    var flagError by remember { mutableStateOf<String?>(null) }

    fun showNotice(message: String) {
        noticeToast = message
        scope.launch {
            delay(2500)
            if (noticeToast == message) noticeToast = null
        }
    }

    val copyTopicLink: (Topic) -> Unit = { topic ->
        clipboardManager.setText(AnnotatedString("https://linux.do/t/topic/${topic.id}"))
        showNotice("已复制话题链接")
    }
    val bookmarkTopicAction: ((Topic) -> Unit)? = if (onBookmarkTopic != null) {
        { topic -> onBookmarkTopic(topic) { msg -> showNotice(msg) } }
    } else null
    val flagTopicAction: ((Topic) -> Unit)? = if (onFlagTopic != null) {
        { topic ->
            flagError = null
            flagSubmitting = false
            flagTargetTopic = topic
        }
    } else null

    var revealedTopicId by remember(listState) { mutableStateOf<Long?>(null) }
    LaunchedEffect(active, listState.isScrollInProgress, state.refreshing) {
        if (!active || listState.isScrollInProgress || state.refreshing) revealedTopicId = null
    }
    val hasCategoryDropdown = categoryVariants.size > 1 && onSelectCategoryVariant != null

    // 距底部还有 4 项时就开始取下一页,滚到底时数据通常已经就位。
    // 必须要求列表已有真实数据:空列表时占位项也会让 lastVisible 命中阈值,
    // 配合"失败不更新 page"就会变成每秒重打同一页的死循环。
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(active, nearEnd, state.hasMore, state.loadingMore, state.items.size, state.error) {
        val canLoadMore = active && state.hasMore && !state.refreshing && !state.initialLoading &&
            !state.loadingMore &&
            state.items.isNotEmpty() &&
            state.error == null
        if (nearEnd && canLoadMore) onLoadMore()
    }


    val filteredItems = remember(effectiveState.items, blockedUsernames) {
        if (blockedUsernames.isEmpty()) effectiveState.items
        else effectiveState.items.filterNot { it.authorUsername?.lowercase() in blockedUsernames }
    }

    val (pinnedGroups, plainItems) = remember(filteredItems) { pinnedPrefixGroups(filteredItems) }
    val previewKeyPrefix = effectiveFeed?.name ?: currentCategoryId ?: "topic"
    val previewTopicsByKey = remember(plainItems, previewKeyPrefix) {
        plainItems.associate { "${previewKeyPrefix}_${it.listKey}" to it.topic }
    }
    val visiblePreviewTopics by remember(listState, previewTopicsByKey) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo
                .filter { it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
                .sortedBy { kotlin.math.abs(it.offset + it.size / 2 - center) }
                .mapNotNull { item -> (item.key as? String)?.let(previewTopicsByKey::get) }
        }
    }
    val currentVisibleTopics by rememberUpdatedState(visiblePreviewTopics)
    val currentPrefetchExcerpt by rememberUpdatedState(onPrefetchExcerpt)
    LaunchedEffect(active, listState, listState.isScrollInProgress, feedDisplayMode, state.refreshing, state.initialLoading, filteredItems) {
        try {
            currentPrefetchExcerpt?.invoke(scrollBusySource, emptyList()) { emptySet() }
            if (!active || listState.isScrollInProgress || feedDisplayMode == "no_excerpt" ||
                state.refreshing || state.initialLoading
            ) return@LaunchedEffect
            delay(400L)
            // 一次停顿只拍下这一屏的候选，正文回填引起的布局变化不会追加更多卡片。
            val candidates = currentVisibleTopics.filter { getTopicRealExcerpt(it) == null }.take(6).map { it.id }
            currentPrefetchExcerpt?.invoke(scrollBusySource, candidates) {
                currentVisibleTopics.map { it.id }.toSet()
            }
            awaitCancellation()
        } finally {
            currentPrefetchExcerpt?.invoke(scrollBusySource, emptyList()) { emptySet() }
        }
    }

    LaunchedEffect(active, filteredItems, feedDisplayMode) {
        if (active && feedDisplayMode != "no_excerpt" && filteredItems.isNotEmpty()) {
            onPrefetchExcerpts?.invoke(filteredItems.map { it.topic })
        }
    }

    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val feedDragOffsetPx = remember { Animatable(0f) }
    var manualTargetFeed by remember { mutableStateOf<HomeFeedType?>(null) }

    val latestEffectiveFeed by rememberUpdatedState(effectiveFeed)
    val latestOnFeedSelect by rememberUpdatedState(onFeedSelect)
    val latestOnPreloadFeed by rememberUpdatedState(onPreloadFeed)
    val latestContainerWidthPx by rememberUpdatedState(containerWidthPx)
    val feedSwipeEdgeMarginPx = with(LocalDensity.current) { 32.dp.toPx() }
    val feedSwipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    val incomingFeed: HomeFeedType? = if (effectiveFeed != null && feedDragOffsetPx.value != 0f) {
        if (manualTargetFeed != null && manualTargetFeed != effectiveFeed) {
            manualTargetFeed
        } else {
            val feeds = HomeFeedType.entries
            if (feedDragOffsetPx.value < 0f) {
                feeds[(effectiveFeed.ordinal + 1) % feeds.size]
            } else {
                feeds[(effectiveFeed.ordinal - 1 + feeds.size) % feeds.size]
            }
        }
    } else null

    LaunchedEffect(incomingFeed) {
        if (incomingFeed != null) {
            onPreloadFeed?.invoke(incomingFeed)
        }
    }

    val transitionProgress = if (containerWidthPx > 0f && incomingFeed != null) {
        (abs(feedDragOffsetPx.value) / containerWidthPx).coerceIn(0f, 1f)
    } else 0f

    val secondaryListState = incomingFeed?.let { feedListStates[it] ?: feedStateLatest }
    val incomingFeedState = incomingFeed?.let { feedStates[it] ?: TopicListUiState(initialLoading = true) }
    val incomingFilteredItems = remember(incomingFeedState?.items, blockedUsernames) {
        val raw = incomingFeedState?.items.orEmpty()
        if (blockedUsernames.isEmpty()) raw
        else raw.filterNot { it.authorUsername?.lowercase() in blockedUsernames }
    }
    val (incomingPinnedGroups, incomingPlainItems) = remember(incomingFilteredItems) {
        pinnedPrefixGroups(incomingFilteredItems)
    }

    val animateSelectFeed: (HomeFeedType) -> Unit = { targetFeed ->
        val cur = latestEffectiveFeed
        val width = latestContainerWidthPx
        val select = latestOnFeedSelect
        if (cur == null || targetFeed == cur || width <= 0f || select == null) {
            select?.invoke(targetFeed)
        } else {
            latestOnPreloadFeed?.invoke(targetFeed)
            val dir = if (targetFeed.ordinal > cur.ordinal) -1f else 1f
            scope.launch {
                manualTargetFeed = targetFeed
                feedDragOffsetPx.snapTo(0f)
                feedDragOffsetPx.animateTo(
                    targetValue = dir * width,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                )
                pendingSettledFeed = targetFeed
                manualTargetFeed = null
                select(targetFeed)
                feedDragOffsetPx.snapTo(0f)
            }
        }
    }

    val homeFeedSwipeModifier = if (currentFeed != null && onFeedSelect != null) {
        Modifier.pointerInput(feedSwipeEdgeMarginPx, feedSwipeThresholdPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x < feedSwipeEdgeMarginPx ||
                    (size.width > 0 && down.position.x > size.width - feedSwipeEdgeMarginPx)
                ) {
                    return@awaitEachGesture
                }
                var dragMode = 0 // 0: 未定, 1: 水平切标签, 2: 垂直滚动或其他手势
                var totalPan = Offset.Zero
                var horizontalDrag = 0f
                val touchSlop = viewConfiguration.touchSlop

                do {
                    val event = awaitPointerEvent()
                    val activePointers = event.changes.filter { it.pressed }
                    if (activePointers.isEmpty()) break
                    if (activePointers.size > 1) {
                        dragMode = 2
                        break
                    }
                    val change = activePointers.first()
                    if (change.isConsumed && dragMode == 0) {
                        dragMode = 2
                        break
                    }
                    val delta = change.positionChange()
                    when (dragMode) {
                        1 -> {
                            horizontalDrag += delta.x
                            val maxW = latestContainerWidthPx.takeIf { it > 0f } ?: size.width.toFloat()
                            val clamped = horizontalDrag.coerceIn(-maxW, maxW)
                            scope.launch { feedDragOffsetPx.snapTo(clamped) }
                            change.consume()
                        }
                        0 -> {
                            totalPan += delta
                            if (totalPan.getDistance() > touchSlop) {
                                if (abs(totalPan.x) > abs(totalPan.y) * 1.35f) {
                                    dragMode = 1
                                    manualTargetFeed = null
                                    horizontalDrag = totalPan.x
                                    val maxW = latestContainerWidthPx.takeIf { it > 0f } ?: size.width.toFloat()
                                    val clamped = horizontalDrag.coerceIn(-maxW, maxW)
                                    scope.launch { feedDragOffsetPx.snapTo(clamped) }
                                    change.consume()
                                } else {
                                    dragMode = 2
                                    break
                                }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                if (dragMode == 1) {
                    val maxW = latestContainerWidthPx.takeIf { it > 0f } ?: size.width.toFloat()
                    val currentOffset = feedDragOffsetPx.value
                    val threshold = if (maxW > 0f) maxOf(feedSwipeThresholdPx, maxW * 0.16f) else feedSwipeThresholdPx
                    val feed = latestEffectiveFeed
                    val select = latestOnFeedSelect
                    if (feed != null && select != null && maxW > 0f && abs(currentOffset) >= threshold) {
                        val feeds = HomeFeedType.entries
                        val nextIndex = if (currentOffset < 0f) {
                            (feed.ordinal + 1) % feeds.size
                        } else {
                            (feed.ordinal - 1 + feeds.size) % feeds.size
                        }
                        val targetFeed = feeds[nextIndex]
                        val targetX = if (currentOffset < 0f) -maxW else maxW
                        val remaining = (1f - abs(currentOffset) / maxW).coerceIn(0.15f, 1f)
                        val durationMs = (220f * remaining).toInt().coerceIn(85, 220)
                        scope.launch {
                            feedDragOffsetPx.animateTo(
                                targetValue = targetX,
                                animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                            )
                            pendingSettledFeed = targetFeed
                            manualTargetFeed = null
                            select(targetFeed)
                            feedDragOffsetPx.snapTo(0f)
                        }
                    } else {
                        scope.launch {
                            feedDragOffsetPx.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            manualTargetFeed = null
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    fun LazyListScope.renderFeedItems(
        pageState: TopicListUiState,
        pagePinnedGroups: List<List<TopicListItem>>,
        pagePlainItems: List<TopicListItem>,
        keyPrefix: String,
    ) {
        pageState.error?.let { message ->
            item(key = "${keyPrefix}_error") { ErrorNotice(message, onRefresh) }
        }
        if (pageState.initialLoading && pageState.items.isEmpty()) {
            item(key = "${keyPrefix}_loading") { IosLoadingBox() }
        }
        if (!pageState.initialLoading && pageState.items.isEmpty() && pageState.error == null) {
            item(key = "${keyPrefix}_empty") { EmptyNotice() }
        }

        itemsIndexed(
            items = pagePinnedGroups,
            key = { _, group -> "${keyPrefix}_pinned_${group.first().topic.id}" },
        ) { _, group ->
            val isGraphic = feedDisplayMode == "graphic"
            val isCardStyle = isGraphic || feedDisplayMode == "no_excerpt"
            Column(modifier = Modifier.fillMaxWidth()) {
                PinnedGroupCard(
                    items = group,
                    isHighlighted = group.any { it.topic.id in pageState.highlightedTopicIds },
                    onOpenTopic = onOpenTopic,
                    onSetNotificationLevel = onSetNotificationLevel,
                    onCopyLink = copyTopicLink,
                    onBookmark = bookmarkTopicAction,
                    onFlag = flagTopicAction,
                    onSave = onSaveTopic,
                    showTopicUnreadDot = showTopicUnreadDot,
                    topicUnreadDotPosition = topicUnreadDotPosition,
                )
                if (isCardStyle) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(IosTheme.colors.groupedBackground)
                    )
                } else {
                    IosDivider(startIndent = IosMetrics.screenPadding)
                }
            }
        }

        itemsIndexed(
            items = pagePlainItems,
            key = { _, item -> "${keyPrefix}_${item.listKey}" },
            contentType = { _, item ->
                when {
                    feedDisplayMode == "graphic" -> "topic-graphic"
                    feedDisplayMode == "no_excerpt" -> "topic-no-excerpt"
                    item.topic.thumbnailUrlForList() != null -> "topic-with-thumbnail"
                    else -> "topic-text"
                }
            },
        ) { index, item ->
            val isHighlighted = item.topic.id in pageState.highlightedTopicIds
            val isGraphic = feedDisplayMode == "graphic"
            val isCardStyle = isGraphic || feedDisplayMode == "no_excerpt"
            Column(modifier = Modifier.fillMaxWidth()) {
                if (item.bookmarkId != null) {
                    var removing by remember(item.bookmarkId) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.targetPostNumber?.let { "收藏于第 $it 楼" } ?: "话题书签",
                            style = IosTheme.type.caption, color = IosTheme.colors.accent)
                        Spacer(Modifier.weight(1f))
                        if (onRemoveBookmark != null) androidx.compose.material3.TextButton(
                            enabled = !removing,
                            onClick = {
                                removing = true
                                onRemoveBookmark(item) { message -> removing = false; showNotice(message) }
                            },
                        ) { Text(if (removing) "处理中…" else "取消收藏", style = IosTheme.type.caption) }
                    }
                }
                TopicRow(
                    item = item,
                    isHighlighted = isHighlighted,
                    revealed = revealedTopicId == item.topic.id,
                    onReveal = { opened -> revealedTopicId = item.topic.id.takeIf { opened } },
                    onSetNotificationLevel = if (onSetNotificationLevel != null) { level ->
                        onSetNotificationLevel(item.topic, level)
                    } else null,
                    onCopyLink = { copyTopicLink(item.topic) },
                    onBookmark = bookmarkTopicAction?.let { fn -> { fn(item.topic) } },
                    onFlag = flagTopicAction?.let { fn -> { fn(item.topic) } },
                    onSave = onSaveTopic?.let { fn -> { fn(item.topic) } },
                    onOpenUser = onOpenUser,
                    onClick = { if (onOpenItem != null) onOpenItem(item) else onOpenTopic(item.topic) },
                    feedGraphicMode = isGraphic,
                    feedDisplayMode = feedDisplayMode,
                    showTopicUnreadDot = showTopicUnreadDot,
                    topicUnreadDotPosition = topicUnreadDotPosition,
                )
                if (index < pagePlainItems.lastIndex) {
                    if (isCardStyle) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(IosTheme.colors.groupedBackground)
                        )
                    } else {
                        IosDivider(startIndent = IosMetrics.screenPadding)
                    }
                }
            }
        }

        if (pageState.loadingMore) {
            item(key = "${keyPrefix}_load_more") { IosLoadingBox() }
        }
    }

    PullToRefreshBox(
        isRefreshing = effectiveState.refreshing,
        onRefresh = {
            if (active && currentFeed != null && onNavigateToApps != null && pullState.distanceFraction >= 1.55f) {
                onNavigateToApps()
            } else {
                onRefresh()
            }
        },
        state = pullState,
        modifier = modifier
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .then(homeFeedSwipeModifier)
            .nestedScroll(nestedScrollConnection),
        indicator = {
            val fraction = pullState.distanceFraction
            // 初始加载时列表中央已有加载指示，这里再叠一个下拉转圈就成了两个圈。
            if (!effectiveState.initialLoading && (effectiveState.refreshing || fraction > 0f)) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = IosMetrics.navBarHeight + 10.dp)
                        .graphicsLayer {
                            alpha = if (effectiveState.refreshing) 1f else min(1f, fraction * 1.4f)
                        },
                ) {
                    if (currentFeed != null && onNavigateToApps != null && fraction >= 1.55f && !effectiveState.refreshing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WeChatThreeDotsLoader()
                            Text("松开进入小程序", color = IosTheme.colors.secondaryLabel, fontSize = 12.sp)
                        }
                    } else {
                        IosActivityIndicator(diameter = 22.dp)
                    }
                }
            }
        },
    ) {
        IosLargeTitleScaffold(
            title = title,
            listState = listState,
            onBack = onBack,
            compactTop = onBack == null,
            showLargeTitle = showLargeTitle,
            barsVisible = barsVisible,
            scrollToTopRequest = scrollToTopRequest,
            trailing = trailing,
            pageOffsetXProvider = if (effectiveFeed != null) ({ feedDragOffsetPx.value }) else null,
            secondaryListState = secondaryListState,
            secondaryPageOffsetXProvider = if (incomingFeed != null) {
                {
                    val offset = feedDragOffsetPx.value
                    if (offset < 0f) containerWidthPx + offset else -containerWidthPx + offset
                }
            } else null,
            secondaryContent = if (incomingFeed != null && incomingFeedState != null) {
                {
                    renderFeedItems(
                        pageState = incomingFeedState,
                        pagePinnedGroups = incomingPinnedGroups,
                        pagePlainItems = incomingPlainItems,
                        keyPrefix = incomingFeed.name,
                    )
                }
            } else null,
            customLargeTitle = when {
                effectiveFeed != null && onFeedSelect != null -> {
                    {
                        Box(miniAppsHeaderGesture) { HomeFeedTitleBar(
                            currentFeed = effectiveFeed,
                            targetFeed = incomingFeed,
                            transitionProgress = transitionProgress,
                            onFeedSelect = animateSelectFeed,
                            trailing = trailing,
                        ) }
                    }
                }
                hasCategoryDropdown -> {
                    {
                        CategoryVariantSelector(
                            title = title,
                            variants = categoryVariants,
                            currentCategoryId = currentCategoryId,
                            collapsed = false,
                            onSelect = { onSelectCategoryVariant?.invoke(it) },
                        )
                    }
                }
                else -> null
            },
            customCollapsedTitle = when {
                effectiveFeed != null && onFeedSelect != null -> {
                    {
                        Box(miniAppsHeaderGesture) { HomeFeedCollapsedBar(
                            currentFeed = effectiveFeed,
                            targetFeed = incomingFeed,
                            transitionProgress = transitionProgress,
                            onFeedSelect = animateSelectFeed,
                        ) }
                    }
                }
                hasCategoryDropdown -> {
                    {
                        CategoryVariantSelector(
                            title = title,
                            variants = categoryVariants,
                            currentCategoryId = currentCategoryId,
                            collapsed = true,
                            onSelect = { onSelectCategoryVariant?.invoke(it) },
                        )
                    }
                }
                else -> null
            },
            floatingOverlay = {
                var bannerAlphaTarget by remember { mutableFloatStateOf(1f) }
                LaunchedEffect(listState.isScrollInProgress, effectiveState.incomingTopicIds.isEmpty()) {
                    if (listState.isScrollInProgress || effectiveState.incomingTopicIds.isEmpty()) {
                        bannerAlphaTarget = 1f
                    } else {
                        delay(2000L)
                        bannerAlphaTarget = FLOATING_BALL_IDLE_ALPHA
                    }
                }
                val bannerAlpha by animateFloatAsState(
                    targetValue = bannerAlphaTarget,
                    animationSpec = tween(durationMillis = 300),
                    label = "incomingBannerAlpha",
                )
                var lastApplyIncomingClickTime by remember { mutableLongStateOf(0L) }

                AnimatedVisibility(
                    visible = effectiveState.incomingTopicIds.isNotEmpty(),
                    enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it },
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (onBack == null) 58.dp else IosMetrics.navBarHeight + 8.dp),
                ) {
                    IncomingTopicsBanner(
                        count = effectiveState.incomingTopicIds.size,
                        onClick = {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastApplyIncomingClickTime > 400L) {
                                lastApplyIncomingClickTime = now
                                onApplyIncoming?.invoke()
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        },
                        modifier = Modifier.graphicsLayer { alpha = bannerAlpha },
                    )
                }
                if (floatingBallEnabled && floatingBallActions.isNotEmpty()) {
                    FloatingAssistiveBall(
                        actions = floatingBallActions,
                        sortOrder = sortOrder,
                        sortAscending = sortAscending,
                        initialXRatio = floatingBallXRatio,
                        initialYRatio = floatingBallYRatio,
                        onPositionChange = onFloatingBallPositionChange,
                        onActionClick = { action ->
                            when (action) {
                                FloatingAction.CreateTopic -> onCreateTopic?.invoke()
                                FloatingAction.Bookmarks -> onNavigateToBookmarks?.invoke()
                                FloatingAction.Following -> onNavigateToFollowing?.invoke()
                                FloatingAction.Likes -> onNavigateToLikes?.invoke()
                                FloatingAction.Replies -> onNavigateToReplies?.invoke()
                                FloatingAction.Sort -> showSortDialog = true
                                FloatingAction.Drafts -> onNavigateToDrafts?.invoke()
                                FloatingAction.Search -> onNavigateToSearch?.invoke()
                                FloatingAction.Categories -> onNavigateToCategories?.invoke()
                                FloatingAction.History -> onNavigateToHistory?.invoke()
                                FloatingAction.Connect -> onNavigateToConnect?.invoke()
                                FloatingAction.Chat -> onNavigateToChat?.invoke()
                                FloatingAction.Apps -> onNavigateToApps?.invoke()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 68.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TopicSortFab(
                            sortOrder = sortOrder,
                            ascending = sortAscending,
                            onClick = { showSortDialog = true },
                        )
                        if (onCreateTopic != null) {
                            CreateTopicFab(onClick = onCreateTopic)
                        }
                    }
                }
                if (showSortDialog) {
                    TopicSortDialog(
                        currentOrder = sortOrder,
                        ascending = sortAscending,
                        onDismiss = { showSortDialog = false },
                        onSelectOrder = { order ->
                            onSortChange(order)
                            if (order == TopicSortOrder.Default || order != sortOrder) {
                                showSortDialog = false
                            }
                        },
                    )
                }
                noticeToast?.let { notice ->
                    Text(
                        notice,
                        style = IosTheme.type.footnote,
                        color = Color(0xFF30B85B),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 68.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosTheme.colors.card)
                            .border(1.dp, Color(0xFF34C759).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            },
        ) {
            val keyPrefix = effectiveFeed?.name ?: currentCategoryId?.toString() ?: "topic"
            renderFeedItems(
                pageState = effectiveState,
                pagePinnedGroups = pinnedGroups,
                pagePlainItems = plainItems,
                keyPrefix = keyPrefix,
            )
        }
    }

    flagTargetTopic?.let { topic ->
        TopicFlagDialog(
            topic = topic,
            submitting = flagSubmitting,
            error = flagError,
            onDismiss = {
                if (!flagSubmitting) {
                    flagTargetTopic = null
                    flagError = null
                }
            },
            onSubmit = { reasonId, message ->
                val handler = onFlagTopic ?: return@TopicFlagDialog
                flagSubmitting = true
                flagError = null
                handler(
                    topic,
                    reasonId,
                    message,
                    {
                        flagSubmitting = false
                        flagTargetTopic = null
                        showNotice("举报已提交，感谢您的反馈")
                    },
                    { err ->
                        flagSubmitting = false
                        flagError = err
                    },
                )
            },
        )
    }
}

/** 列表右下角创建新话题悬浮按钮（笔状图标，与详情页回复按钮风格一致）。 */
@Composable
private fun CreateTopicFab(onClick: () -> Unit) {
    val tintColor = IosTheme.colors.accent
    val bgBase = IosTheme.colors.card.copy(alpha = 0.95f)
    val bgTint = tintColor.copy(alpha = 0.12f)
    val borderColor = tintColor.copy(alpha = 0.28f)
    val fabShape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(48.dp)
            .shadow(elevation = 3.dp, shape = fabShape, clip = false)
            .clip(fabShape)
            .background(bgBase)
            .background(bgTint)
            .border(1.dp, borderColor, fabShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "创建新话题" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val pen = Path().apply {
                    moveTo(4f, 20f)
                    lineTo(8.5f, 19f)
                    lineTo(19.5f, 8f)
                    lineTo(16f, 4.5f)
                    lineTo(5f, 15.5f)
                    close()
                }
                drawPath(pen, tintColor, style = stroke)
                drawLine(tintColor, Offset(13.5f, 7f), Offset(17f, 10.5f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                drawLine(tintColor, Offset(12.5f, 20f), Offset(20f, 20f), strokeWidth = 1.9f, cap = StrokeCap.Round)
            }
        }
    }
}

/** 列表右下角悬浮排序按钮（垂直双向箭头，位于发帖按钮上方）。 */
@Composable
private fun TopicSortFab(
    sortOrder: TopicSortOrder,
    ascending: Boolean,
    onClick: () -> Unit,
) {
    val isDefault = sortOrder == TopicSortOrder.Default
    val activeColor = IosTheme.colors.accent
    val tintColor = if (isDefault) IosTheme.colors.secondaryLabel else activeColor
    val bgBase = IosTheme.colors.card.copy(alpha = 0.94f)
    val borderColor = if (isDefault) IosTheme.colors.separator.copy(alpha = 0.4f) else activeColor.copy(alpha = 0.45f)

    Box(
        Modifier
            .size(44.dp)
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(bgBase)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "列表排序：${sortOrder.label}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(19.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                // 左侧向上箭头 (x=8)
                drawLine(tintColor, Offset(8f, 18.5f), Offset(8f, 5.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                val leftArrow = Path().apply {
                    moveTo(4.5f, 9.5f)
                    lineTo(8f, 5.5f)
                    lineTo(11.5f, 9.5f)
                }
                drawPath(leftArrow, tintColor, style = stroke)

                // 右侧向下箭头 (x=16)
                drawLine(tintColor, Offset(16f, 5.5f), Offset(16f, 18.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                val rightArrow = Path().apply {
                    moveTo(12.5f, 14.5f)
                    lineTo(16f, 18.5f)
                    lineTo(19.5f, 14.5f)
                }
                drawPath(rightArrow, tintColor, style = stroke)
            }
        }
    }
}

/** 排序选项图标绘制 */
@Composable
private fun TopicSortIcon(
    order: TopicSortOrder,
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp),
) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (order) {
                TopicSortOrder.Default -> {
                    // 三条横线 ≡
                    drawLine(tint, Offset(4f, 6.5f), Offset(20f, 6.5f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(4f, 12f), Offset(16f, 12f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(4f, 17.5f), Offset(12f, 17.5f), strokeWidth = 2f, cap = StrokeCap.Round)
                }
                TopicSortOrder.Activity -> {
                    // 逆时针时钟刷新 ↺
                    drawArc(
                        color = tint,
                        startAngle = -210f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = Offset(4.5f, 4.5f),
                        size = androidx.compose.ui.geometry.Size(15f, 15f),
                        style = stroke,
                    )
                    val arrow = Path().apply {
                        moveTo(1.5f, 9.5f)
                        lineTo(4.5f, 6.5f)
                        lineTo(8f, 9.5f)
                    }
                    drawPath(arrow, tint, style = stroke)
                    drawLine(tint, Offset(12f, 12f), Offset(12f, 8.5f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(12f, 12f), Offset(14.5f, 12f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                }
                TopicSortOrder.Created -> {
                    // 日历 📅
                    val cal = Path().apply {
                        moveTo(4f, 7f)
                        lineTo(20f, 7f)
                        lineTo(20f, 20f)
                        lineTo(4f, 20f)
                        close()
                    }
                    drawPath(cal, tint, style = stroke)
                    drawLine(tint, Offset(4f, 11f), Offset(20f, 11f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(8f, 3.5f), Offset(8f, 7f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(16f, 3.5f), Offset(16f, 7f), strokeWidth = 2f, cap = StrokeCap.Round)
                }
                TopicSortOrder.Likes -> {
                    // 爱心 ♡
                    val heart = Path().apply {
                        moveTo(12f, 20.5f)
                        cubicTo(10.5f, 19f, 4f, 13.5f, 4f, 8.5f)
                        cubicTo(4f, 5.2f, 6.8f, 3.8f, 9.5f, 4.8f)
                        cubicTo(10.8f, 5.3f, 11.6f, 6.2f, 12f, 7.2f)
                        cubicTo(12.4f, 6.2f, 13.2f, 5.3f, 14.5f, 4.8f)
                        cubicTo(17.2f, 3.8f, 20f, 5.2f, 20f, 8.5f)
                        cubicTo(20f, 13.5f, 13.5f, 19f, 12f, 20.5f)
                        close()
                    }
                    drawPath(heart, tint, style = stroke)
                }
                TopicSortOrder.Views -> {
                    // 眼睛 👁
                    val eye = Path().apply {
                        moveTo(3f, 12f)
                        cubicTo(6.5f, 6f, 17.5f, 6f, 21f, 12f)
                        cubicTo(17.5f, 18f, 6.5f, 18f, 3f, 12f)
                        close()
                    }
                    drawPath(eye, tint, style = stroke)
                    drawCircle(tint, 2.8f, Offset(12f, 12f))
                }
                TopicSortOrder.Posts -> {
                    // 回复数 💬
                    val bubble = Path().apply {
                        moveTo(4f, 6f)
                        lineTo(20f, 6f)
                        lineTo(20f, 16f)
                        lineTo(10f, 16f)
                        lineTo(6f, 19.5f)
                        lineTo(6f, 16f)
                        lineTo(4f, 16f)
                        close()
                    }
                    drawPath(bubble, tint, style = stroke)
                }
                TopicSortOrder.Posters -> {
                    // 参与者 👥
                    drawCircle(tint, 3f, Offset(9f, 7.5f), style = stroke)
                    val body1 = Path().apply {
                        moveTo(4.5f, 18f)
                        cubicTo(4.5f, 14f, 13.5f, 14f, 13.5f, 18f)
                    }
                    drawPath(body1, tint, style = stroke)

                    drawCircle(tint, 2.4f, Offset(16.5f, 8.5f), style = stroke)
                    val body2 = Path().apply {
                        moveTo(13.8f, 18f)
                        cubicTo(13.8f, 15f, 19.5f, 15f, 19.5f, 18f)
                    }
                    drawPath(body2, tint, style = stroke)
                }
            }
        }
    }
}

/**
 * 列表排序选择弹出框（复刻参考图 1 风格：带标题Header、7个图标选项、选中对勾与升降序切换）。
 */
@Composable
private fun TopicSortDialog(
    currentOrder: TopicSortOrder,
    ascending: Boolean,
    onDismiss: () -> Unit,
    onSelectOrder: (TopicSortOrder) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(IosTheme.colors.card)
                .border(0.6.dp, IosTheme.colors.separator.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(vertical = 6.dp),
        ) {
            // 顶部 Header: ≡ 排序：默认   ^
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopicSortIcon(
                    order = currentOrder,
                    tint = IosTheme.colors.label,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(12.dp))
                val sortSuffix = if (currentOrder != TopicSortOrder.Default) {
                    if (ascending) " (升序)" else " (降序)"
                } else ""
                Text(
                    text = "排序：${currentOrder.label}$sortSuffix",
                    style = IosTheme.type.headline.copy(fontWeight = FontWeight.SemiBold),
                    color = IosTheme.colors.label,
                    modifier = Modifier.weight(1f),
                )
                // 向上折叠指示符 ^
                Canvas(Modifier.size(14.dp)) {
                    val chevron = Path().apply {
                        moveTo(2f, 9.5f)
                        lineTo(7f, 4.5f)
                        lineTo(12f, 9.5f)
                    }
                    drawPath(chevron, Color(0xFF8E8E93), style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            IosDivider(startIndent = 14.dp, modifier = Modifier.padding(end = 14.dp))

            // 7 个排序条目
            TopicSortOrder.entries.forEach { order ->
                val isSelected = order == currentOrder
                val itemColor = if (isSelected) IosTheme.colors.accent else IosTheme.colors.label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectOrder(order) }
                        .background(if (isSelected) IosTheme.colors.accent.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopicSortIcon(
                        order = order,
                        tint = itemColor,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = order.label,
                        style = IosTheme.type.body.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = itemColor,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        if (order != TopicSortOrder.Default) {
                            Text(
                                text = if (ascending) "↑ 升序" else "↓ 降序",
                                style = IosTheme.type.caption.copy(fontWeight = FontWeight.Medium),
                                color = itemColor,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Canvas(Modifier.size(16.dp)) {
                            val check = Path().apply {
                                moveTo(2.5f, 8.5f)
                                lineTo(6f, 12f)
                                lineTo(13.5f, 4.5f)
                            }
                            drawPath(check, itemColor, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 话题长按操作菜单（提醒四档 + 复制链接、添加书签、举报）。[TopicRow] 与 [PinnedGroupCard] 共用。
 */
@Composable
private fun NotificationLevelMenu(
    expanded: Boolean,
    currentLevel: TopicNotificationLevel?,
    updating: Boolean,
    onDismiss: () -> Unit,
    onPick: ((TopicNotificationLevel) -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    onBookmark: (() -> Unit)? = null,
    onFlag: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    previewTopic: Topic? = null,
    previewCategory: Category? = null,
    onOpenTopic: (() -> Unit)? = null,
) {
    if (!expanded) return
    val previewLoader = org.linuxdo.android.ui.LocalTopicPreviewLoader.current
    if (previewTopic != null && onOpenTopic != null && previewLoader != null) {
        TopicPreviewDialog(previewTopic, previewCategory, previewLoader, onDismiss, onOpenTopic,
            currentLevel, updating, onPick, onCopyLink, onBookmark, onFlag, onSave)
        return
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(IosTheme.colors.card),
    ) {
        if (onPick != null) {
            val current = currentLevel ?: TopicNotificationLevel.Regular
            TopicNotificationLevel.entries.forEach { level ->
                val isSelected = level == current
                DropdownMenuItem(
                    enabled = !updating,
                    modifier = if (isSelected) Modifier.background(IosTheme.colors.accent.copy(alpha = 0.12f)) else Modifier,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TopicNotificationIcon(level, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = level.title,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) IosTheme.colors.accent else IosTheme.colors.label,
                            )
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    onClick = {
                        onDismiss()
                        onPick(level)
                    },
                )
            }
        }
        val hasExtraActions = onSave != null || onCopyLink != null || onBookmark != null || onFlag != null
        if (onPick != null && hasExtraActions) {
            IosDivider()
        }
        if (onSave != null) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
                            Text("▱", color = IosTheme.colors.secondaryLabel, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("添加暂存", color = IosTheme.colors.label)
                    }
                },
                onClick = {
                    onDismiss()
                    onSave()
                },
            )
        }
        if (onCopyLink != null) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PostActionIcon(PostAction.CopyLink, false, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("复制链接", color = IosTheme.colors.label)
                    }
                },
                onClick = {
                    onDismiss()
                    onCopyLink()
                },
            )
        }
        if (onBookmark != null) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PostActionIcon(PostAction.Bookmark, false, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加书签", color = IosTheme.colors.label)
                    }
                },
                onClick = {
                    onDismiss()
                    onBookmark()
                },
            )
        }
        if (onFlag != null) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PostActionIcon(PostAction.Flag, false, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("举报", color = IosTheme.colors.label)
                    }
                },
                onClick = {
                    onDismiss()
                    onFlag()
                },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TopicPreviewDialog(
    topic: Topic,
    category: Category?,
    loader: suspend (Long) -> org.linuxdo.android.data.TopicDetail,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    notificationLevel: TopicNotificationLevel?,
    updating: Boolean,
    onPick: ((TopicNotificationLevel) -> Unit)?,
    onCopy: (() -> Unit)?,
    onBookmark: (() -> Unit)?,
    onFlag: (() -> Unit)?,
    onSave: (() -> Unit)?,
) {
    var detail by remember(topic.id) { mutableStateOf<org.linuxdo.android.data.TopicDetail?>(null) }
    var loading by remember(topic.id) { mutableStateOf(true) }
    var failed by remember(topic.id) { mutableStateOf(false) }
    var retry by remember(topic.id) { mutableIntStateOf(0) }
    var sharePost by remember { mutableStateOf<org.linuxdo.android.data.Post?>(null) }

    LaunchedEffect(topic.id, retry) {
        loading = true
        failed = false
        try { detail = loader(topic.id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val first = detail?.postStream?.posts?.firstOrNull { it.postNumber == 1 }
    val cookedBlocks = remember(first?.cooked) {
        val cookedHtml = first?.cooked
        if (!cookedHtml.isNullOrBlank()) {
            CookedParser.parseCached(cookedHtml)
        } else emptyList()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(24.dp))
                .background(IosTheme.colors.card)
        ) {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 大标题 (单击也可跳转) 与 右上角“暂存话题”胶囊按钮
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = detail?.title ?: topic.title,
                            style = IosTheme.type.title3.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp),
                            color = IosTheme.colors.label,
                            lineHeight = 28.sp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                                .clickable { onDismiss(); onOpen() }
                        )
                        if (onSave != null) {
                            var localSaved by remember(topic.id) { mutableStateOf(false) }
                            val saveBg = if (localSaved) IosTheme.colors.accent.copy(alpha = 0.22f)
                                else IosTheme.colors.accent.copy(alpha = if (IosTheme.colors.isDark) 0.16f else 0.08f)
                            Row(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(saveBg)
                                    .clickable {
                                        localSaved = !localSaved
                                        onSave()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PostActionIcon(
                                    action = PostAction.Bookmark,
                                    selected = localSaved,
                                    tint = IosTheme.colors.accent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (localSaved) "已暂存" else "暂存话题",
                                    style = IosTheme.type.caption,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IosTheme.colors.accent
                                )
                            }
                        }
                    }
                }

                // 2. 作者信息行 (头像 · 名字 · 分类色块 · 分类名 · 时间)
                item {
                    val firstPosterUser = topic.posters.firstOrNull()?.user
                    val posterName = first?.displayName?.takeIf { it.isNotBlank() }
                        ?: firstPosterUser?.name?.takeIf { it.isNotBlank() }
                        ?: firstPosterUser?.username?.takeIf { it.isNotBlank() }
                        ?: "社区用户"
                    val posterAvatar = first?.avatarTemplate ?: firstPosterUser?.avatarTemplate
                    val effectiveCategory = category
                    val catColor = effectiveCategory?.color?.let { parseCategoryColor(it) } ?: IosTheme.colors.accent

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!posterAvatar.isNullOrBlank()) {
                            AsyncImage(
                                avatarUrlOf(posterAvatar),
                                posterName,
                                modifier = Modifier.size(24.dp).clip(CircleShape)
                            )
                        }
                        Text(
                            text = posterName,
                            style = IosTheme.type.footnote,
                            fontWeight = FontWeight.Medium,
                            color = IosTheme.colors.label
                        )
                        Text("·", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                        if (effectiveCategory != null) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(catColor)
                            )
                            Text(
                                text = effectiveCategory.name,
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.secondaryLabel
                            )
                            Text("·", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                        }
                        Text(
                            text = RelativeTime.format(first?.createdAt ?: topic.createdAt),
                            style = IosTheme.type.caption,
                            color = IosTheme.colors.secondaryLabel
                        )
                    }
                }

                // 3. 标签列表 (#快问快答 #人工智能)
                val tags = detail?.tags ?: topic.tags
                if (tags.isNotEmpty()) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            tags.forEach { tag ->
                                Text(
                                    text = "#$tag",
                                    style = IosTheme.type.caption,
                                    color = IosTheme.colors.secondaryLabel
                                )
                            }
                        }
                    }
                }

                // 4. 正文富文本与图片渲染
                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            IosActivityIndicator(diameter = 24.dp)
                        }
                    }
                } else if (cookedBlocks.isNotEmpty()) {
                    item {
                        CookedContent(
                            blocks = cookedBlocks,
                            onPicture = { _, _ -> },
                            compact = true,
                        )
                    }
                } else if (!topic.excerpt.isNullOrBlank()) {
                    item {
                        Text(
                            text = topic.excerpt!!,
                            style = IosTheme.type.body,
                            color = IosTheme.colors.label,
                            lineHeight = 24.sp
                        )
                    }
                }

                // 5. 失败重试提示
                if (failed && cookedBlocks.isEmpty()) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(IosTheme.colors.groupedBackground)
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("详情暂未加载完整，可直接打开话题查看", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.material3.TextButton(onClick = { retry++ }) {
                                Text("重试加载", color = IosTheme.colors.accent)
                            }
                        }
                    }
                }
            }

            // 6. 话题提醒设置 (按图设计分段选择器，靠下独立展示，不贴着正文)
            if (onPick != null) {
                var currentSelectedLevel by remember(notificationLevel, topic.notificationLevel) {
                    mutableStateOf(notificationLevel ?: TopicNotificationLevel.entries.firstOrNull { it.code == topic.notificationLevel } ?: TopicNotificationLevel.Regular)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "话题提醒设置",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(IosTheme.colors.groupedBackground)
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val levels = listOf(
                            TopicNotificationLevel.Watching to "关注",
                            TopicNotificationLevel.Tracking to "跟踪",
                            TopicNotificationLevel.Regular to "常规",
                            TopicNotificationLevel.Muted to "免打扰"
                        )
                        levels.forEach { (level, title) ->
                            val isSelected = level == currentSelectedLevel
                            val itemBg = if (isSelected) IosTheme.colors.accent else Color.Transparent
                            val itemTextColor = if (isSelected) Color.White else IosTheme.colors.label

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(itemBg)
                                    .clickable(enabled = !updating) {
                                        currentSelectedLevel = level
                                        onPick(level)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (isSelected) {
                                        Text("✓ ", style = IosTheme.type.caption.copy(fontWeight = FontWeight.Bold), color = itemTextColor)
                                    } else {
                                        TopicNotificationIcon(level, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = title,
                                        style = IosTheme.type.caption.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                        color = itemTextColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. 优雅卡片底栏 (左侧统计数据 💬🤍👁 + 右侧 分享按钮 & 查看详情胶囊)
            IosDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 回复数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💬", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("${topic.totalReplies}", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                }
                Spacer(Modifier.width(14.dp))
                // 点赞数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤍", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("${topic.likeCount}", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                }
                Spacer(Modifier.width(14.dp))
                // 浏览数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("👁", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("${topic.views}", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                }

                Spacer(Modifier.weight(1f))

                // 分享卡片按钮（即使详情正文尚在加载，也可先用话题基础信息秒开链接分享）
                val fallbackSharePost = remember(topic.id, topic.title, topic.createdAt, topic.excerpt) {
                    val firstPosterUser = topic.posters.firstOrNull()?.user
                    org.linuxdo.android.data.Post(
                        id = -topic.id,
                        username = firstPosterUser?.username.orEmpty(),
                        name = firstPosterUser?.name?.takeIf { it.isNotBlank() } ?: firstPosterUser?.username ?: "社区用户",
                        avatarTemplate = firstPosterUser?.avatarTemplate,
                        postNumber = 1,
                        createdAt = topic.createdAt,
                        cooked = getTopicRealExcerpt(topic).orEmpty(),
                        topicId = topic.id,
                        blurb = topic.excerpt.orEmpty(),
                    )
                }
                val shareTarget = first ?: fallbackSharePost
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(IosTheme.colors.groupedBackground)
                        .clickable {
                            sharePost = shareTarget
                        },
                    contentAlignment = Alignment.Center
                ) {
                    PostActionIcon(
                        PostAction.Share,
                        false,
                        tint = IosTheme.colors.label,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                // “查看详情”胶囊按钮
                androidx.compose.material3.Button(
                    onClick = { onDismiss(); onOpen() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = IosTheme.colors.accent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PostActionIcon(PostAction.Reply, false, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("查看详情", style = IosTheme.type.footnote, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // 预览弹窗内部可直接弹出长图分享 Sheet
    sharePost?.let { post ->
        PostShareSheet(post, detail?.title ?: topic.title, topic.id) {
            sharePost = null
        }
    }
}

/**
 * 把列表**头部连续**的置顶项按作者切成若干组,返回 (置顶分组, 其余话题)。
 *
 * 只吃头部前缀而不是全列表 filter:站方的置顶本来就排在最前,取前缀足以解决"置顶吃光首屏";
 * 全列表 filter 会把翻页后零散冒出来的置顶帖也抽到顶上,列表会在加载更多时自己跳一下。
 */
private fun pinnedPrefixGroups(
    items: List<TopicListItem>,
): Pair<List<List<TopicListItem>>, List<TopicListItem>> {
    val prefix = items.takeWhile { it.topic.pinned }
    if (prefix.isEmpty()) return emptyList<List<TopicListItem>>() to items
    // LinkedHashMap 保持作者首次出现的顺序,不打乱站方的置顶排序。
    val grouped = LinkedHashMap<String, MutableList<TopicListItem>>()
    prefix.forEach { entry ->
        grouped.getOrPut(entry.authorUsername ?: "") { mutableListOf() }.add(entry)
    }
    return grouped.values.map { it.toList() } to items.drop(prefix.size)
}

@Composable
private fun topicRowBackground(isHighlighted: Boolean): Color {
    val background by animateColorAsState(
        targetValue = if (isHighlighted) IosTheme.colors.accent.copy(alpha = 0.12f) else IosTheme.colors.card,
        animationSpec = tween(if (isHighlighted) 100 else 450), label = "topic-highlight",
    )
    return background
}

/**
 * 置顶帖合并卡。
 *
 * 站方同时挂多条全局置顶时,每条各铺一张完整卡片会把首屏吃光 —— 下拉刷新后想看的新帖只剩一条。
 * 这里把同一作者的连续置顶折成一张卡:只保留作者身份(头像 + 用户名 + Lv 徽章)和标题行,
 * 摘要、缩略图、回复/浏览/时间与标签全部省掉。标题仍走 [titleWithBadges],
 * 所以未读蓝点、"置顶""已关闭"标记不必重写一套。
 *
 * 不做左滑四档抽屉:一张卡对应多个话题,左滑该落到哪一条没有明确语义;
 * 要单独设免打扰就长按对应那一行标题。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedGroupCard(
    items: List<TopicListItem>,
    onOpenTopic: (Topic) -> Unit,
    onSetNotificationLevel: ((Topic, TopicNotificationLevel) -> Unit)? = null,
    onCopyLink: ((Topic) -> Unit)? = null,
    onBookmark: ((Topic) -> Unit)? = null,
    onFlag: ((Topic) -> Unit)? = null,
    onSave: ((Topic) -> Unit)? = null,
    isHighlighted: Boolean = false,
    showTopicUnreadDot: Boolean = true,
    topicUnreadDotPosition: TopicUnreadDotPosition = TopicUnreadDotPosition.Avatar,
) {
    val head = items.firstOrNull() ?: return
    val rowBackground = topicRowBackground(isHighlighted)
    var menuTopicId by remember { mutableStateOf<Long?>(null) }
    val hasAnyLongPress = org.linuxdo.android.ui.LocalTopicPreviewLoader.current != null ||
        onSetNotificationLevel != null || onCopyLink != null || onBookmark != null || onFlag != null || onSave != null
    Column(
        Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(horizontal = IosMetrics.screenPadding, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    UserAvatar(
                        avatarUrl = head.avatarUrl,
                        fallbackUrl = head.staticAvatarUrl,
                        username = head.authorUsername,
                        modifier = Modifier.size(24.dp),
                        cornerRadius = 3.5.dp,
                    )
                    if (showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.Avatar && items.any { it.topic.unseen }) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 1.dp, y = (-1).dp)
                                .clip(CircleShape)
                                .background(IosTheme.colors.card)
                                .padding(1.dp)
                                .clip(CircleShape)
                                .background(IosTheme.colors.accent)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = head.displayName.ifBlank { "匿名" },
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                head.trustLevel?.takeIf { it > 0 }?.let { level ->
                    Spacer(Modifier.width(4.dp))
                    LevelBadge(level)
                }
            }
            Text(
                text = "置顶 ${items.size}",
                style = IosTheme.type.footnote,
                color = IosTheme.colors.secondaryLabel,
            )
        }
        items.forEach { entry ->
            Spacer(Modifier.height(6.dp))
            Box {
                Text(
                    text = titleWithBadges(
                        entry.topic,
                        IosTheme.colors.accent,
                        includeUnseenDot = showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.TitleLeft,
                    ),
                    style = IosTheme.type.body,
                    color = IosTheme.colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    inlineContent = unseenDotContent(IosTheme.colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpenTopic(entry.topic) },
                            onLongClick = if (hasAnyLongPress && !entry.notificationLevelUpdating) {
                                { menuTopicId = entry.topic.id }
                            } else null,
                        ),
                )
                NotificationLevelMenu(
                    expanded = menuTopicId == entry.topic.id && hasAnyLongPress,
                    currentLevel = entry.notificationLevel,
                    updating = entry.notificationLevelUpdating,
                    previewTopic = entry.topic,
                    previewCategory = entry.category,
                    onOpenTopic = { onOpenTopic(entry.topic) },
                    onDismiss = { menuTopicId = null },
                    onPick = onSetNotificationLevel?.let { fn -> { level -> fn(entry.topic, level) } },
                    onCopyLink = onCopyLink?.let { fn -> { fn(entry.topic) } },
                    onBookmark = onBookmark?.let { fn -> { fn(entry.topic) } },
                    onFlag = onFlag?.let { fn -> { fn(entry.topic) } },
                    onSave = onSave?.let { fn -> { fn(entry.topic) } },
                )
            }
        }
    }
}

/**
 * 清洗话题正文摘录：
 * 彻底过滤掉开头自动生成的图片属性、文件名、分辨率与体积信息（如 "image1540×734 54.4 KB"、"截图... 120KB" 等），
 * 直接将真实正文字符串呈现给用户。
 */
internal val EMOJI_SHORTCODE_MAP = mapOf(
    "laughing" to "😆", "satisfied" to "😆", "smile" to "😄", "smiley" to "😃", "grinning" to "😀",
    "grin" to "😁", "joy" to "😂", "rofl" to "🤣", "sweat_smile" to "😅", "wink" to "😉",
    "blush" to "😊", "innocent" to "😇", "slight_smile" to "🙂", "upside_down_face" to "🙃",
    "yum" to "😋", "relieved" to "😌", "heart_eyes" to "😍", "kissing_heart" to "😘",
    "kissing" to "😗", "stuck_out_tongue_winking_eye" to "😜", "stuck_out_tongue_closed_eyes" to "😝",
    "stuck_out_tongue" to "😛", "money_mouth_face" to "🤑", "hugs" to "🤗", "hug" to "🤗",
    "shush" to "🤫", "thinking" to "🤔", "zipper_mouth_face" to "🤐", "face_with_raised_eyebrow" to "🤨",
    "neutral_face" to "😐", "expressionless" to "😑", "no_mouth" to "😶", "smirk" to "😏",
    "unamused" to "😒", "rolling_eyes" to "🙄", "grimacing" to "😬", "lying_face" to "🤥",
    "sleepy" to "😪", "drooling_face" to "🤤", "sleeping" to "😴", "mask" to "😷",
    "face_with_thermometer" to "🤒", "head_bandage" to "🤕", "nauseated_face" to "🤢",
    "vomiting_face" to "🤮", "sneezing_face" to "🤧", "hot_face" to "🥵", "cold_face" to "🥶",
    "woozy_face" to "🥴", "dizzy_face" to "😵", "exploding_head" to "🤯", "sunglasses" to "😎",
    "nerd_face" to "🤓", "monocle_face" to "🧐", "confused" to "😕", "worried" to "😟",
    "slight_frown" to "🙁", "frowning_face" to "☹️", "open_mouth" to "😮", "hushed" to "😯",
    "astonished" to "😲", "flushed" to "😳", "pleading_face" to "🥺", "frowning" to "😦",
    "anguished" to "😧", "fearful" to "😨", "cold_sweat" to "😰", "disappointed_relieved" to "😥",
    "cry" to "😢", "sob" to "😭", "scream" to "😱", "confounded" to "😖", "persevere" to "😣",
    "disappointed" to "😞", "sweat" to "😓", "weary" to "😩", "tired_face" to "😫",
    "yawning_face" to "🥱", "triumph" to "😤", "rage" to "😡", "pout" to "😡", "angry" to "😠",
    "cursing_face" to "🤬", "smiling_imp" to "😈", "imp" to "👿", "skull" to "💀", "poop" to "💩",
    "clown_face" to "🤡", "ghost" to "👻", "alien" to "👽", "wave" to "👋", "ok_hand" to "👌",
    "victory" to "✌️", "v" to "✌️", "+1" to "👍", "thumbsup" to "👍", "-1" to "👎", "thumbsdown" to "👎",
    "clap" to "👏", "pray" to "🙏", "muscle" to "💪", "eyes" to "👀", "heart" to "❤️", "broken_heart" to "💔",
    "fire" to "🔥", "100" to "💯", "tada" to "🎉", "sparkles" to "✨", "star" to "⭐", "rocket" to "🚀",
    "salute" to "🫡", "saluting_face" to "🫡", "party" to "🥳", "partying_face" to "🥳",
    "melting_face" to "🫠", "face_holding_back_tears" to "🥹", "handshake" to "🤝",
    "dog" to "🐶", "cat" to "🐱", "beer" to "🍺", "beers" to "🍻", "coffee" to "☕", "tea" to "🍵",
    "check" to "✅", "white_check_mark" to "✅", "x" to "❌", "cross_mark" to "❌",
    "sun" to "☀️", "sunny" to "☀️", "moon" to "🌙", "rose" to "🌹", "warning" to "⚠️",
    "pin" to "📌", "pushpin" to "📌", "red_heart" to "❤️", "sparkling_heart" to "💖",
    "two_hearts" to "💕", "gift" to "🎁", "bulb" to "💡", "memo" to "📝", "pencil" to "📝"
)

internal val CLOSED_EMOJI_PATTERN = Regex("""(?<![a-zA-Z0-9]):([a-zA-Z0-9_\-+]+):""")
internal val UNCLOSED_EMOJI_PATTERN = Regex("""(?<![a-zA-Z0-9]):([a-zA-Z0-9_\-+]{2,30})(?=[,\s，。！!？?；;]|\Z)""")

/**
 * 智能渲染或过滤 Discourse 表情代码（如 :laughing:, :joy:, :heart:, :+1: 以及截断的 :laughing 等）：
 * - [keepUnknown] 为 true 时（如聊天场景），无法识别的代码保留原貌；为 false 时（如摘要场景）剔除，避免显示生硬难看的冒号字符。
 */
fun renderEmojiShortcodes(text: String?, keepUnknown: Boolean = false): String? {
    if (text.isNullOrBlank()) return text
    var res = CLOSED_EMOJI_PATTERN.replace(text) { matchResult ->
        val code = matchResult.groupValues[1].lowercase()
        EMOJI_SHORTCODE_MAP[code] ?: if (keepUnknown) matchResult.value else ""
    }
    res = UNCLOSED_EMOJI_PATTERN.replace(res) { matchResult ->
        val code = matchResult.groupValues[1].lowercase()
        EMOJI_SHORTCODE_MAP[code] ?: if (keepUnknown) matchResult.value else ""
    }
    return res
}

/** 兼容旧接口：默认过滤无法识别的代码 */
fun renderOrFilterEmojiShortcodes(text: String?): String? = renderEmojiShortcodes(text, keepUnknown = false)

/**
 * 清洗摘要文本。
 * 深度过滤掉各种图片占位符、相机相册自动生成的文件名、十六进制哈希、分辨率大小以及无意义的元信息；
 * 并将 :laughing: 等表情代码自动转为真实 Emoji（未识别的表情代码自动过滤消除），保留真实有意义正文。
 */
internal fun cleanExcerpt(rawHtmlOrText: String?): String? {
    if (rawHtmlOrText.isNullOrBlank()) return null
    val parsed = try {
        val doc = Jsoup.parseBodyFragment(rawHtmlOrText)
        doc.select(".lightbox-wrapper, .lightbox, .meta, .informations, .filename, img:not(.emoji), svg, video, audio").remove()
        doc.select("img.emoji").forEach { img ->
            val alt = img.attr("alt")
            val converted = renderOrFilterEmojiShortcodes(alt).orEmpty()
            img.replaceWith(org.jsoup.nodes.TextNode(converted))
        }
        doc.text().trim()
    } catch (_: Exception) {
        rawHtmlOrText.trim()
    }

    if (parsed.isEmpty()) return null

    var cleaned = parsed

    // 1. 先进行 Emoji Shortcode 渲染与过滤（如 :laughing: -> 😆，未知代码过滤）
    cleaned = renderOrFilterEmojiShortcodes(cleaned).orEmpty()

    // 2. 清理占位符：[image], [图片], [图], [photo], [attachment], [video], [audio] 等（包括首部、中间、尾部）
    cleaned = cleaned.replace(
        Regex("""\[(?:image|img|图片|贴图|图|photo|attachment|video|audio)\]""", RegexOption.IGNORE_CASE),
        " "
    )

    // 3. 清理常见相机、相册、截图默认命名前缀及完整文件名：
    // 如 IMG_5509, PXL_20230501, Screenshot_2023..., 微信截图_..., mmexport1234..., wx_camera_...
    cleaned = cleaned.replace(
        Regex("""\b(?:IMG_\d+|PXL_\d+|Screenshot[^\s]*|截屏[^\s]*|微信截图[^\s]*|QQ截图[^\s]*|mmexport\d+|wx_camera_\d+|Pasted\s+image[^\s]*)\b""", RegexOption.IGNORE_CASE),
        " "
    )

    // 4. 清理 16~64 位的纯十六进制哈希字符串（如 MD5: E577F8EF97E51E7BAD93488ED6435DCA）与标准 UUID
    cleaned = cleaned.replace(Regex("""\b[0-9a-fA-F]{16,64}\b"""), " ")
    cleaned = cleaned.replace(Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""), " ")

    // 5. 清理图片扩展名文件：xxx.png, xxx.jpg, xxx.jpeg, xxx.webp 等
    cleaned = cleaned.replace(
        Regex("""\b[\w\-_.]+\.(?:png|jpe?g|gif|webp|bmp|svg)\b""", RegexOption.IGNORE_CASE),
        " "
    )

    // 6. 清理分辨率与体积元信息：image1540×734, 1920x1080, 54.4 KB, 1.2MB 等
    cleaned = cleaned.replace(
        Regex("""\b(?:image|img|photo|贴图)?\s*\d{2,5}\s*[×xX*]\s*\d{2,5}\b""", RegexOption.IGNORE_CASE),
        " "
    )
    cleaned = cleaned.replace(
        Regex("""\b[\d.]+\s*(?:KB|MB|GB|B|千字节|字节)\b""", RegexOption.IGNORE_CASE),
        " "
    )
    cleaned = cleaned.replace(
        Regex("""\b(?:image|img|photo|贴图)\s*\d*\b""", RegexOption.IGNORE_CASE),
        " "
    )

    // 7. 规整空白与清理多余残留标点
    cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
    cleaned = cleaned.trim(',', '，', ':', '：', '|', '丨', '/', '\\', '、', '-', '—', ' ')

    // 8. 必须包含字符、数字或大于 127 的符号（如中文、Emoji 等），否则认定无有效正文
    if (cleaned.isBlank() || cleaned.none { it.isLetterOrDigit() || it.code > 127 }) {
        return null
    }

    return cleaned
}

/**
 * 获取话题真实正文摘录:
 * 1. 优先使用服务端下发的真实 excerpt (置顶帖、搜索结果)，并剥离首部图片元属性
 * 2. 检查用户浏览过或后台轻量预取到的真实首楼正文
 * 3. 严格遵循真实内容原则，绝不拼凑合成假话
 */
private fun getTopicRealExcerpt(topic: Topic): String? {
    val fromTopic = cleanExcerpt(topic.excerpt)
    if (fromTopic != null) return fromTopic

    return cleanExcerpt(ExcerptCache.get(topic.id))
}

@Composable
private fun rememberTopicThumbnailRequest(
    url: String?,
    widthPx: Int,
    heightPx: Int,
    retry: Int,
): ImageRequest? {
    val context = LocalContext.current
    return remember(context, url, widthPx, heightPx, retry) {
        url?.takeIf { it.isNotBlank() }?.let { thumbnailUrl ->
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(widthPx, heightPx)
                // 同一资源在大图、小图模式下各自保持合适的内存解码尺寸。
                .memoryCacheKey("topic-thumbnail:$thumbnailUrl:${widthPx}x${heightPx}")
                // 磁盘缓存按完整 URL 存（Coil 默认键），与详情页、大图查看器共用：
                // 任一处拉成功，另一处直接命中本地，不再额外打一次站内请求。
                .setParameter("retry", retry, memoryCacheKey = null)
                .crossfade(false)
                .build()
        }
    }
}

@Composable
private fun TopicThumbnailImage(
    thumbnailUrl: String,
    fallbackUrl: String?,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier,
) {
    var useFallback by remember(thumbnailUrl, fallbackUrl) { mutableStateOf(false) }
    var retry by remember(thumbnailUrl, fallbackUrl) { mutableIntStateOf(0) }
    var pendingRetry by remember(thumbnailUrl, fallbackUrl) { mutableStateOf(false) }
    val thumbnailRequest = rememberTopicThumbnailRequest(thumbnailUrl, widthPx, heightPx, retry)
    val fallbackRequest = rememberTopicThumbnailRequest(fallbackUrl, widthPx, heightPx, retry)
    val canFallback = !fallbackUrl.isNullOrBlank() && fallbackUrl != thumbnailUrl

    // 列表在详情页下方始终保持组合，失败态不会因"返回列表"自然刷新；
    // 瞬时失败（门禁冷却、429、网络抖动）等冷却结束后自己补拉，次数封顶避免风控。
    if (pendingRetry) {
        LaunchedEffect(retry) {
            awaitImageRetrySlot(retry)
            pendingRetry = false
            retry++
        }
    }
    AsyncImage(
        model = if (useFallback) fallbackRequest else thumbnailRequest,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        onError = { state ->
            when {
                isTransientImageError(state.result.throwable) && retry < IMAGE_AUTO_RETRY_LIMIT -> pendingRetry = true
                !useFallback && canFallback -> useFallback = true
            }
        },
    )
}

/**
 * 现代知乎风列表卡片。
 * 严格按照【大标题置顶 -> 作者行 -> 2行正文摘要 -> 底部操作行】分层排版。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopicRow(
    item: TopicListItem,
    query: String? = null,
    isHighlighted: Boolean = false,
    revealed: Boolean = false,
    onReveal: (Boolean) -> Unit = {},
    onSetNotificationLevel: ((TopicNotificationLevel) -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    onBookmark: (() -> Unit)? = null,
    onFlag: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onOpenUser: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> },
    onClick: () -> Unit,
    feedGraphicMode: Boolean = true,
    feedDisplayMode: String = "no_excerpt",
    showTopicUnreadDot: Boolean = true,
    topicUnreadDotPosition: TopicUnreadDotPosition = TopicUnreadDotPosition.Avatar,
) {
    var showLevelMenu by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    val hasAnyLongPress = org.linuxdo.android.ui.LocalTopicPreviewLoader.current != null ||
        onSetNotificationLevel != null || onCopyLink != null || onBookmark != null || onFlag != null || onSave != null
    val topic = item.topic
    // 首次组合查一次本地库；之后改读内存态，详情页或预取记下封面时这一行会随之刷新。
    val storedCoverUrl = remember(topic.id) { ExcerptCache.getCoverImage(topic.id) }
    val coverImageUrl = ExcerptCache.peekCoverImage(topic.id) ?: storedCoverUrl
    val largeThumbnailUrl = remember(topic.id, topic.imageUrl, topic.thumbnails, coverImageUrl) {
        topic.thumbnailUrlForList(targetWidth = 960) ?: coverImageUrl
    }
    val smallThumbnailUrl = remember(topic.id, topic.imageUrl, topic.thumbnails, coverImageUrl) {
        topic.thumbnailUrlForList(targetWidth = 320) ?: coverImageUrl
    }
    val originalImageUrl = remember(topic.id, topic.imageUrl) { topic.originalImageUrl() }
    val rowBackground = topicRowBackground(isHighlighted)
    val needle = query?.trim().orEmpty()
    val accent = IosTheme.colors.accent

    // 观察响应式真实正文（一旦预取成功或点开详情，立即自动重组刷新）
    val realExcerpt = when {
        feedDisplayMode == "no_excerpt" -> null
        query != null || item.bookmarkId != null -> topic.excerpt
        else -> getTopicRealExcerpt(topic)
    }

    val excerpt = remember(realExcerpt, needle, accent) {
        val raw = realExcerpt
        if (raw.isNullOrBlank()) null
        else if (needle.isEmpty()) buildAnnotatedString { append(raw) }
        else buildAnnotatedString {
            var start = raw.indexOf(needle, ignoreCase = true)
            var cursor = 0
            while (start >= 0) {
                append(raw.substring(cursor, start))
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append(raw.substring(start, start + needle.length))
                }
                cursor = start + needle.length
                start = raw.indexOf(needle, cursor, ignoreCase = true)
            }
            append(raw.substring(cursor))
        }
    }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        val isGraphic = feedDisplayMode == "graphic"
        val isNoExcerpt = feedDisplayMode == "no_excerpt"
        val isCardStyle = isGraphic || isNoExcerpt
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (isHighlighted) IosTheme.colors.card.copy(alpha = 0.85f) else if (isCardStyle) IosTheme.colors.card else rowBackground)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasAnyLongPress && !item.notificationLevelUpdating) {
                        { showLevelMenu = true }
                    } else null,
                )
                .padding(
                    horizontal = if (isCardStyle) 16.dp else IosMetrics.screenPadding,
                    vertical = if (isCardStyle) 12.dp else 10.dp,
                ),
        ) {
            if (isNoExcerpt) {
                // ── 无正文模式（微博样式）：从上到下：用户图标 -> 标题 -> 缩略图 ──
                // 1. 用户图标与作者信息行（微博风格双行头部）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        UserAvatar(
                            avatarUrl = item.avatarUrl,
                            fallbackUrl = item.staticAvatarUrl,
                            username = item.authorUsername,
                            modifier = Modifier
                                .size(32.dp)
                                .clickable(enabled = !item.authorUsername.isNullOrBlank()) {
                                    item.authorUsername?.let { u ->
                                        onOpenUser(u, item.avatarUrl, item.staticAvatarUrl, item.authorName ?: item.displayName)
                                    }
                                },
                            cornerRadius = 16.dp,
                        )
                        if (showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.Avatar && topic.unseen) {
                            Box(
                                Modifier
                                    .size(7.5.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 1.dp, y = (-1).dp)
                                    .clip(CircleShape)
                                    .background(IosTheme.colors.card)
                                    .padding(1.dp)
                                    .clip(CircleShape)
                                    .background(IosTheme.colors.accent)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.displayName.ifBlank { "匿名" },
                            style = IosTheme.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                            color = IosTheme.colors.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = !item.authorUsername.isNullOrBlank()) {
                                item.authorUsername?.let { u ->
                                    onOpenUser(u, item.avatarUrl, item.staticAvatarUrl, item.authorName ?: item.displayName)
                                }
                            },
                        )
                        item.trustLevel?.takeIf { it > 0 }?.let { level ->
                            Spacer(Modifier.width(5.dp))
                            LevelBadge(level)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.notificationLevel?.takeIf { it != TopicNotificationLevel.Regular }?.let { level ->
                            TopicNotificationLevelTag(level)
                            Spacer(Modifier.width(6.dp))
                        }
                        item.category?.let { CategoryTag(it) }
                    }
                }

                NotificationLevelMenu(
                    expanded = showLevelMenu && hasAnyLongPress,
                    currentLevel = item.notificationLevel,
                    updating = item.notificationLevelUpdating,
                    previewTopic = topic,
                    previewCategory = item.category,
                    onOpenTopic = onClick,
                    onDismiss = { showLevelMenu = false },
                    onPick = onSetNotificationLevel,
                    onCopyLink = onCopyLink,
                    onBookmark = onBookmark,
                    onFlag = onFlag,
                    onSave = onSave,
                )

                // 2. 标题（紧随作者行，精致克制主标题，契合主流App信息流）
                Spacer(Modifier.height(7.5.dp))
                Text(
                    text = titleWithBadges(
                        topic = topic,
                        accent = IosTheme.colors.accent,
                        query = query,
                        includeUnseenDot = showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.TitleLeft,
                    ),
                    style = IosTheme.type.body.copy(
                        fontSize = 14.6.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.8.sp,
                        letterSpacing = 0.1.sp,
                    ),
                    color = IosTheme.colors.label,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    inlineContent = unseenDotContent(IosTheme.colors.accent),
                )

                // 3. 缩略图（如果有配图，上下微留白舒展，不团在一起）
                val coverUrl = largeThumbnailUrl ?: smallThumbnailUrl
                if (coverUrl != null) {
                    Spacer(Modifier.height(11.dp))
                    TopicThumbnailImage(
                        thumbnailUrl = coverUrl,
                        fallbackUrl = originalImageUrl,
                        widthPx = 960,
                        heightPx = 540,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .background(IosTheme.colors.fieldBackground),
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Spacer(Modifier.height(11.dp))
                }

                // 4. 底部互动栏（左侧：评论 / 浏览 / 活跃时间；右侧：快捷分享）
                TopicCardMetaBar(
                    topic = topic,
                    onShare = { showShareSheet = true },
                    compact = false,
                )
            } else if (isGraphic) {
                // ── 图文模式：标题置顶（知乎级粗体大标题，首字平齐无毛刺） ──
                Text(
                    text = titleWithBadges(
                        topic = topic,
                        accent = IosTheme.colors.accent,
                        query = query,
                        includeUnseenDot = showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.TitleLeft,
                    ),
                    style = IosTheme.type.body.copy(
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                        letterSpacing = 0.15.sp,
                    ),
                    color = IosTheme.colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    inlineContent = unseenDotContent(IosTheme.colors.accent),
                )

                Spacer(Modifier.height(8.dp))

                // ── 标题下方作者与分类信息行 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            UserAvatar(
                                avatarUrl = item.avatarUrl,
                                fallbackUrl = item.staticAvatarUrl,
                                username = item.authorUsername,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clickable(enabled = !item.authorUsername.isNullOrBlank()) {
                                        item.authorUsername?.let { u ->
                                            onOpenUser(u, item.avatarUrl, item.staticAvatarUrl, item.authorName ?: item.displayName)
                                        }
                                    },
                                cornerRadius = 13.dp,
                            )
                            if (showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.Avatar && topic.unseen) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 1.dp, y = (-1).dp)
                                        .clip(CircleShape)
                                        .background(IosTheme.colors.card)
                                        .padding(1.dp)
                                        .clip(CircleShape)
                                        .background(IosTheme.colors.accent)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.displayName.ifBlank { "匿名" },
                            style = IosTheme.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                            color = IosTheme.colors.label.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = !item.authorUsername.isNullOrBlank()) {
                                item.authorUsername?.let { u ->
                                    onOpenUser(u, item.avatarUrl, item.staticAvatarUrl, item.authorName ?: item.displayName)
                                }
                            },
                        )
                        item.trustLevel?.takeIf { it > 0 }?.let { level ->
                            Spacer(Modifier.width(5.dp))
                            LevelBadge(level)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.notificationLevel?.takeIf { it != TopicNotificationLevel.Regular }?.let { level ->
                            TopicNotificationLevelTag(level)
                            Spacer(Modifier.width(6.dp))
                        }
                        item.category?.let { CategoryTag(it) }
                    }
                }

                NotificationLevelMenu(
                    expanded = showLevelMenu && hasAnyLongPress,
                    currentLevel = item.notificationLevel,
                    updating = item.notificationLevelUpdating,
                    previewTopic = topic,
                    previewCategory = item.category,
                    onOpenTopic = onClick,
                    onDismiss = { showLevelMenu = false },
                    onPick = onSetNotificationLevel,
                    onCopyLink = onCopyLink,
                    onBookmark = onBookmark,
                    onFlag = onFlag,
                    onSave = onSave,
                )

                // 3. 正文两行摘录 (严格保证两行知乎排版结构，固定最小高度防抖)
                Spacer(Modifier.height(7.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = excerpt,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(220)) togetherWith
                                androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
                        },
                        label = "excerpt-fade",
                    ) { targetExcerpt ->
                        if (targetExcerpt != null) {
                            Text(
                                text = targetExcerpt,
                                style = IosTheme.type.footnote.copy(fontSize = 14.sp, lineHeight = 20.5.sp, fontWeight = FontWeight.Normal),
                                color = IosTheme.colors.secondaryLabel,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(IosTheme.colors.separator.copy(alpha = 0.22f))
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.58f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(IosTheme.colors.separator.copy(alpha = 0.16f))
                                )
                            }
                        }
                    }
                }

                // 4. 封面大图预览 (16:9 高清全宽展示)
                largeThumbnailUrl?.let { url ->
                    Spacer(Modifier.height(9.dp))
                    TopicThumbnailImage(
                        thumbnailUrl = url,
                        fallbackUrl = originalImageUrl,
                        widthPx = 960,
                        heightPx = 540,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .background(IosTheme.colors.fieldBackground),
                    )
                }

                // 5. 底部互动栏（左侧：评论 / 浏览 / 发布时间；右侧：快捷分享）
                Spacer(Modifier.height(10.dp))
                TopicCardMetaBar(
                    topic = topic,
                    onShare = { showShareSheet = true },
                    compact = false,
                )
            } else {
                // ── 小图与纯文模式: 统一知乎结构 (大标题置顶 -> 作者行 -> 2行正文摘要 -> 底部操作行) ──
                // 1. 标题置顶 (大一号半粗)
                Text(
                    text = titleWithBadges(
                        topic = topic,
                        accent = IosTheme.colors.accent,
                        query = query,
                        includeUnseenDot = showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.TitleLeft,
                    ),
                    style = IosTheme.type.body.copy(
                        fontSize = 15.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 21.5.sp,
                        letterSpacing = 0.15.sp,
                    ),
                    color = IosTheme.colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    inlineContent = unseenDotContent(IosTheme.colors.accent),
                )

                Spacer(Modifier.height(6.dp))

                // 2. 标题下方作者与分类行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            UserAvatar(
                                avatarUrl = item.avatarUrl,
                                fallbackUrl = item.staticAvatarUrl,
                                username = item.authorUsername,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable(enabled = !item.authorUsername.isNullOrBlank()) {
                                        item.authorUsername?.let { u ->
                                            onOpenUser(u, item.avatarUrl, item.staticAvatarUrl, item.authorName ?: item.displayName)
                                        }
                                    },
                                cornerRadius = 4.dp,
                            )
                            if (showTopicUnreadDot && topicUnreadDotPosition == TopicUnreadDotPosition.Avatar && topic.unseen) {
                                Box(
                                    Modifier
                                        .size(6.5.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 1.dp, y = (-1).dp)
                                        .clip(CircleShape)
                                        .background(IosTheme.colors.card)
                                        .padding(1.dp)
                                        .clip(CircleShape)
                                        .background(IosTheme.colors.accent)
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.displayName.ifBlank { "匿名" },
                            style = IosTheme.type.footnote.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = IosTheme.colors.secondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = !item.authorUsername.isNullOrBlank()) {
                                item.authorUsername?.let { u ->
                                    onOpenUser(u, item.avatarUrl, item.staticAvatarUrl, item.authorName ?: item.displayName)
                                }
                            },
                        )
                        item.trustLevel?.takeIf { it > 0 }?.let { level ->
                            Spacer(Modifier.width(4.dp))
                            LevelBadge(level)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.notificationLevel?.takeIf { it != TopicNotificationLevel.Regular }?.let { level ->
                            TopicNotificationLevelTag(level)
                            Spacer(Modifier.width(6.dp))
                        }
                        item.category?.let { CategoryTag(it) }
                    }
                }

                NotificationLevelMenu(
                    expanded = showLevelMenu && hasAnyLongPress,
                    currentLevel = item.notificationLevel,
                    updating = item.notificationLevelUpdating,
                    previewTopic = topic,
                    previewCategory = item.category,
                    onOpenTopic = onClick,
                    onDismiss = { showLevelMenu = false },
                    onPick = onSetNotificationLevel,
                    onCopyLink = onCopyLink,
                    onBookmark = onBookmark,
                    onFlag = onFlag,
                    onSave = onSave,
                )

                Spacer(Modifier.height(6.dp))

                // 3. 两行正文摘要 (有图时小图右置混排，无图整宽，永远保持两行正文)
                val hasSmallCover = feedDisplayMode == "small_image" && smallThumbnailUrl != null
                if (hasSmallCover) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (excerpt != null) {
                                Text(
                                    text = excerpt,
                                    style = IosTheme.type.footnote.copy(fontSize = 13.sp, lineHeight = 18.5.sp, fontWeight = FontWeight.Normal),
                                    color = IosTheme.colors.secondaryLabel,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.9f)
                                            .height(12.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(IosTheme.colors.separator.copy(alpha = 0.22f))
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.55f)
                                            .height(12.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(IosTheme.colors.separator.copy(alpha = 0.16f))
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        TopicThumbnailImage(
                            thumbnailUrl = smallThumbnailUrl!!,
                            fallbackUrl = originalImageUrl,
                            widthPx = 240,
                            heightPx = 168,
                            modifier = Modifier
                                .size(width = 74.dp, height = 52.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(IosTheme.colors.fieldBackground),
                        )
                    }
                } else {
                    if (excerpt != null) {
                        Text(
                            text = excerpt,
                            style = IosTheme.type.footnote.copy(fontSize = 13.sp, lineHeight = 18.5.sp, fontWeight = FontWeight.Normal),
                            color = IosTheme.colors.secondaryLabel,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(IosTheme.colors.separator.copy(alpha = 0.22f))
                            )
                            Spacer(Modifier.height(5.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.58f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(IosTheme.colors.separator.copy(alpha = 0.16f))
                            )
                        }
                    }
                }

                Spacer(Modifier.height(7.dp))

                // 4. 底部紧凑互动行（左侧：评论 / 浏览 / 时间；右侧：标签与快捷分享）
                TopicCardMetaBar(
                    topic = topic,
                    onShare = { showShareSheet = true },
                    compact = true,
                    trailingTags = topic.tags.take(2),
                )
            }
        }
    }

    if (showShareSheet) {
        val sharePost = remember(topic.id, item.authorUsername, item.authorName, item.displayName, item.avatarUrl, item.staticAvatarUrl, realExcerpt) {
            org.linuxdo.android.data.Post(
                id = -topic.id,
                username = item.authorUsername.orEmpty(),
                name = item.authorName?.takeIf { it.isNotBlank() } ?: item.displayName,
                avatarTemplate = item.avatarUrl ?: item.staticAvatarUrl,
                postNumber = item.targetPostNumber ?: 1,
                createdAt = topic.createdAt,
                cooked = (realExcerpt ?: getTopicRealExcerpt(topic)).orEmpty(),
                topicId = topic.id,
                blurb = topic.excerpt.orEmpty(),
            )
        }
        PostShareSheet(
            post = sharePost,
            title = topic.title,
            topicId = topic.id,
            onDismiss = { showShareSheet = false },
        )
    }
}

/** 一档信任等级的配色。描边与文字都留 Brush 口子,好让高等级上金属渐变和彩虹。 */
internal data class LevelPalette(
    val background: Brush,
    val border: Brush,
    val content: Color,
    val contentBrush: Brush? = null,
)

internal val RAINBOW = listOf(
    Color(0xFFFF4D6D), Color(0xFFFF9F1C), Color(0xFFFFD60A),
    Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE),
)

/**
 * 作者信任等级徽章。颜色本身就是身份:Lv1 蓝、Lv2 琥珀橙、Lv3 鎏金、Lv4 彩虹加金冠。
 *
 * Lv1/Lv2 的色值与「分类」页的 Lv 勋章同源,免得同一个 Lv 在两个页面是两种颜色。
 * Lv3 起刻意改成**深底亮字**:Lv2 和 Lv3 都在黄色系里,只调深浅在 10sp 的徽章上根本分不开,
 * 明暗反转才一眼可辨 —— 顺带让等级越高的牌子越有金属分量。
 * 这里一律用静态渐变、不挂流光动画:列表每行都要画一遍,动画那点光泽不值这个开销。
 */
@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier) {
    val isDark = IosTheme.colors.isDark
    val shape = RoundedCornerShape(6.dp)
    val palette = when {
        level >= 4 -> LevelPalette(
            background = Brush.horizontalGradient(
                listOf(Color(0xFF241233), Color(0xFF32124A), Color(0xFF1A1030)),
            ),
            border = Brush.horizontalGradient(RAINBOW),
            content = Color(0xFFFFF1C2),
            contentBrush = Brush.horizontalGradient(RAINBOW),
        )
        level == 3 -> LevelPalette(
            background = Brush.horizontalGradient(
                listOf(Color(0xFF3E2B08), Color(0xFF6B4A12), Color(0xFF4A3410)),
            ),
            border = Brush.horizontalGradient(
                listOf(Color(0xFFFFE9A8), Color(0xFFD4AF37), Color(0xFFFFD76A)),
            ),
            content = Color(0xFFFFD873),
        )
        level == 2 -> LevelPalette(
            background = Brush.horizontalGradient(
                if (isDark) listOf(Color(0xFF33230A), Color(0xFF47310E))
                else listOf(Color(0xFFFFF6DE), Color(0xFFFFE7B0)),
            ),
            border = SolidColor(if (isDark) Color(0xFF8F651A) else Color(0xFFEFC163)),
            content = if (isDark) Color(0xFFFFD166) else Color(0xFF824B05),
        )
        else -> LevelPalette(
            background = Brush.horizontalGradient(
                if (isDark) listOf(Color(0xFF13263E), Color(0xFF193354))
                else listOf(Color(0xFFEAF3FF), Color(0xFFDCEBFF)),
            ),
            border = SolidColor(if (isDark) Color(0xFF2B548C) else Color(0xFFBBD7FF)),
            content = if (isDark) Color(0xFF6EAEFF) else Color(0xFF155FD0),
        )
    }
    val weight = if (level >= 3) FontWeight.ExtraBold else FontWeight.SemiBold
    val textStyle = if (palette.contentBrush != null) {
        TextStyle(brush = palette.contentBrush, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = weight)
    } else {
        TextStyle(color = palette.content, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = weight)
    }
    Row(
        modifier
            .clip(shape)
            .background(palette.background)
            .border(0.8.dp, palette.border, shape)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (level >= 4) {
            CrownGlyph(Color(0xFFFFD60A))
            Spacer(Modifier.width(2.dp))
        }
        Text(text = "Lv$level", style = textStyle)
    }
}

/** Lv4 专属小金冠。只有最高等级才画,所以列表里的数量天然很少。 */
@Composable
internal fun CrownGlyph(color: Color) {
    Canvas(Modifier.size(8.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val crown = Path().apply {
                moveTo(3f, 19f)
                lineTo(1.5f, 7f)
                lineTo(7.5f, 12f)
                lineTo(12f, 4f)
                lineTo(16.5f, 12f)
                lineTo(22.5f, 7f)
                lineTo(21f, 19f)
                close()
            }
            drawPath(crown, color)
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    Text(
        text = tag,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        color = IosTheme.colors.secondaryLabel,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(IosTheme.colors.fieldBackground)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 列表卡片统一底部互动栏：
 * - 左侧定宽槽位显示：评论数、浏览数、活跃时间（每个指标使用定宽容器 + `tnum` 等宽数字，
 *   确保不管数字是 1 位还是 4 位，上下相邻卡片的三个图标永远严格垂直对齐在同一直线上，且间距舒展不拥挤）；
 * - 右侧居右显示：空心圆润转发分享按钮（带防误触热区，右边缘与卡片右上角分类胶囊平齐）。
 */
@Composable
private fun TopicCardMetaBar(
    topic: Topic,
    onShare: () -> Unit,
    compact: Boolean = false,
    trailingTags: List<String> = emptyList(),
) {
    val iconColor = IosTheme.colors.secondaryLabel.copy(alpha = 0.78f)
    val textColor = IosTheme.colors.secondaryLabel
    val iconSize = if (compact) 12.5.dp else 14.dp
    val fontSize = if (compact) 11.5.sp else 12.2.sp
    val textStyle = IosTheme.type.caption.copy(
        fontSize = fontSize,
        lineHeight = fontSize,
        fontFeatureSettings = "tnum",
    )
    val replySlotWidth = if (compact) 58.dp else 68.dp
    val viewSlotWidth = if (compact) 66.dp else 76.dp
    val iconTextGap = if (compact) 4.5.dp else 5.5.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 评论数（固定槽位宽度，确保下一列“浏览图标”在所有卡片中严格上下对齐）
        Row(
            modifier = Modifier.width(replySlotWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReplyGlyph(iconColor, iconSize = iconSize)
            Spacer(Modifier.width(iconTextGap))
            Text(
                text = formatCount(topic.totalReplies),
                style = textStyle,
                color = textColor,
                maxLines = 1,
            )
        }

        // 2. 浏览数（固定槽位宽度，确保下一列“时钟图标”在所有卡片中严格上下对齐）
        Row(
            modifier = Modifier.width(viewSlotWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewGlyph(iconColor, iconSize = iconSize)
            Spacer(Modifier.width(iconTextGap))
            Text(
                text = formatCount(topic.views),
                style = textStyle,
                color = textColor,
                maxLines = 1,
            )
        }

        // 3. 活跃时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClockGlyph(iconColor, iconSize = iconSize)
            Spacer(Modifier.width(iconTextGap))
            Text(
                text = RelativeTime.format(topic.bumpedAt ?: topic.lastPostedAt ?: topic.createdAt),
                style = textStyle,
                color = textColor,
                maxLines = 1,
            )
        }

        Spacer(Modifier.weight(1f))

        if (trailingTags.isNotEmpty()) {
            trailingTags.forEach { tag ->
                TagChip(tag)
                Spacer(Modifier.width(6.dp))
            }
        }

        // 4. 居右快捷分享按钮
        Box(
            modifier = Modifier
                .offset(x = 5.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onShare)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            TopicShareGlyph(
                color = iconColor,
                iconSize = if (compact) 15.dp else 16.5.dp,
            )
        }
    }
}

/** 回复气泡（一体化空心圆角对话框 + 左下角微折角，垂直重心校准在 Y=12f）。 */
@Composable
private fun ReplyGlyph(color: Color, iconSize: Dp = 13.5.dp) {
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path = Path().apply {
                moveTo(6.2f, 4.8f)
                lineTo(17.8f, 4.8f)
                quadraticTo(21f, 4.8f, 21f, 8.0f)
                lineTo(21f, 14.6f)
                quadraticTo(21f, 17.8f, 17.8f, 17.8f)
                lineTo(11.2f, 17.8f)
                lineTo(7.2f, 21.0f)
                lineTo(7.2f, 17.8f)
                lineTo(6.2f, 17.8f)
                quadraticTo(3f, 17.8f, 3f, 14.6f)
                lineTo(3f, 8.0f)
                quadraticTo(3f, 4.8f, 6.2f, 4.8f)
                close()
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** 浏览眼睛图标（杏仁形轮廓 + 空心瞳孔）。 */
@Composable
private fun ViewGlyph(color: Color, iconSize: Dp = 13.5.dp) {
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val eye = Path().apply {
                moveTo(2.2f, 12f)
                cubicTo(6f, 5.5f, 18f, 5.5f, 21.8f, 12f)
                cubicTo(18f, 18.5f, 6f, 18.5f, 2.2f, 12f)
                close()
            }
            drawPath(
                path = eye,
                color = color,
                style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(
                color = color,
                radius = 2.7f,
                center = Offset(12f, 12f),
                style = Stroke(width = 2.0f),
            )
        }
    }
}

/** 活跃时间小钟表图标。 */
@Composable
private fun ClockGlyph(color: Color, iconSize: Dp = 13.5.dp) {
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawCircle(color, radius = 9f, center = Offset(12f, 12f), style = Stroke(2.0f))
            drawLine(color, Offset(12f, 7.5f), Offset(12f, 12f), strokeWidth = 2.0f, cap = StrokeCap.Round)
            drawLine(color, Offset(12f, 12f), Offset(15.5f, 12f), strokeWidth = 2.0f, cap = StrokeCap.Round)
        }
    }
}

/** 列表居右分享图标（空心圆润右弯转发箭头，契合主流社区 App 卡片底栏风格）。 */
@Composable
private fun TopicShareGlyph(color: Color, iconSize: Dp = 16.dp) {
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path = Path().apply {
                moveTo(3.5f, 19.5f)
                cubicTo(4.2f, 11.5f, 7.8f, 8.2f, 13.5f, 8.2f)
                lineTo(13.5f, 4.2f)
                lineTo(20.8f, 11.2f)
                lineTo(13.5f, 18.2f)
                lineTo(13.5f, 14.0f)
                cubicTo(9.2f, 14.0f, 6.2f, 15.6f, 3.5f, 19.5f)
                close()
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * 未读蓝点与置顶/关闭标记都内联进标题文本流,和标题共占 2 行配额,
 * 不在标题上方额外占一行 —— 紧凑卡片经不起多一行的开销。
 */
private fun titleWithBadges(
    topic: Topic,
    accent: Color,
    query: String? = null,
    includeUnseenDot: Boolean = true,
) = buildAnnotatedString {
    if (includeUnseenDot && topic.unseen) {
        appendInlineContent(UNSEEN_DOT, "•")
        append(" ")
    }
    if (topic.pinned) {
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)) {
            append("置顶 ")
        }
    }
    if (topic.closed) {
        withStyle(SpanStyle(color = accent.copy(alpha = 0.7f), fontSize = 14.sp)) { append("已关闭 ") }
    }
    val title = topic.title
    val needle = query?.trim().orEmpty()
    if (needle.isNotEmpty()) {
        var start = title.indexOf(needle, ignoreCase = true)
        var cursor = 0
        while (start >= 0) {
            append(title.substring(cursor, start))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(title.substring(start, start + needle.length))
            }
            cursor = start + needle.length
            start = title.indexOf(needle, cursor, ignoreCase = true)
        }
        append(title.substring(cursor))
    } else {
        append(title)
    }
}

private const val UNSEEN_DOT = "unseen-dot"

@Composable
private fun unseenDotContent(accent: Color): Map<String, InlineTextContent> = remember(accent) {
    mapOf(
        UNSEEN_DOT to InlineTextContent(
            Placeholder(9.sp, 9.sp, PlaceholderVerticalAlign.TextCenter),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            }
        },
    )
}

/** 浏览量这类大数按千位缩写,免得把这一行挤到换行。 */
internal fun formatCount(value: Int): String {
    if (value < 1_000) return value.coerceAtLeast(0).toString()
    val thousands = value / 1_000.0
    val text = if (thousands >= 100) {
        thousands.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", thousands).removeSuffix(".0")
    }
    return "${text}k"
}

@Composable
internal fun CategoryTag(category: Category, prominent: Boolean = false) {
    val catColor = parseCategoryColor(category.color) ?: IosTheme.colors.accent
    val isDark = IosTheme.colors.isDark
    val chipShape = RoundedCornerShape(8.dp)
    val borderColor = catColor.copy(alpha = if (isDark) 0.35f else 0.22f)
    val pillBg = catColor.copy(alpha = if (isDark) 0.16f else 0.08f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(chipShape)
            .border(width = 0.75.dp, color = borderColor, shape = chipShape)
            .background(pillBg)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
    ) {
        Box(
            Modifier
                .size(if (prominent) 6.dp else 5.dp)
                .clip(CircleShape)
                .background(catColor),
        )
        Spacer(Modifier.width(4.5.dp))
        Text(
            text = category.name,
            style = if (prominent) IosTheme.type.body.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium) else IosTheme.type.footnote.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
            color = catColor,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ErrorNotice(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = IosMetrics.screenPadding, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            style = IosTheme.type.subheadline,
            color = IosTheme.colors.secondaryLabel,
        )
        Text(
            text = "重试",
            style = IosTheme.type.body,
            color = IosTheme.colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun EmptyNotice() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "这里还没有内容",
            style = IosTheme.type.subheadline,
            color = IosTheme.colors.tertiaryLabel,
        )
    }
}

/** Discourse 下发的分类色是不带 # 的六位十六进制。 */
fun parseCategoryColor(hex: String?): Color? {
    val cleaned = hex?.removePrefix("#")?.trim() ?: return null
    if (cleaned.length != 6) return null
    return runCatching { Color(cleaned.toLong(16) or 0xFF000000L) }.getOrNull()
}

@Composable
private fun HomeFeedTitleBar(
    currentFeed: HomeFeedType,
    targetFeed: HomeFeedType?,
    transitionProgress: Float,
    onFeedSelect: (HomeFeedType) -> Unit,
    trailing: @Composable () -> Unit,
) {
    val activeColor = IosTheme.colors.label
    val inactiveColor = IosTheme.colors.secondaryLabel
    val indicatorColor = IosTheme.colors.accent
    val feeds = HomeFeedType.entries
    val tabCenters = remember { FloatArray(feeds.size) }
    val p = transitionProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IosMetrics.largeTitleHeight)
            .padding(bottom = 4.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.drawWithContent {
                drawContent()
                val curIdx = currentFeed.ordinal
                val curCenter = tabCenters[curIdx]
                if (curCenter > 0f) {
                    val dstIdx = (targetFeed ?: currentFeed).ordinal
                    val dstCenter = tabCenters[dstIdx].takeIf { it > 0f } ?: curCenter
                    val center = curCenter + (dstCenter - curCenter) * p
                    val baseWidth = 18.dp.toPx()
                    val stretch = min(abs(dstCenter - curCenter) * 0.35f, 16.dp.toPx()) *
                        sin(p * Math.PI.toFloat())
                    val indicatorWidth = baseWidth + stretch
                    val indicatorHeight = 3.dp.toPx()
                    val topY = size.height - 2.dp.toPx() - indicatorHeight
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(center - indicatorWidth / 2f, topY),
                        size = Size(indicatorWidth, indicatorHeight),
                        cornerRadius = CornerRadius(indicatorHeight / 2f, indicatorHeight / 2f),
                    )
                }
            },
        ) {
            feeds.forEachIndexed { index, feed ->
                val weight = when {
                    targetFeed != null && targetFeed != currentFeed && feed == currentFeed -> 1f - p
                    targetFeed != null && targetFeed != currentFeed && feed == targetFeed -> p
                    feed == currentFeed -> 1f
                    else -> 0f
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            tabCenters[index] = coords.positionInParent().x + coords.size.width / 2f
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onFeedSelect(feed) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = feed.label,
                        fontSize = (15f + 2.5f * weight).sp,
                        fontWeight = if (weight >= 0.5f) FontWeight.Bold else FontWeight.Normal,
                        color = lerp(inactiveColor, activeColor, weight),
                    )
                    Spacer(Modifier.height(7.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeFeedCollapsedBar(
    currentFeed: HomeFeedType,
    targetFeed: HomeFeedType?,
    transitionProgress: Float,
    onFeedSelect: (HomeFeedType) -> Unit,
) {
    val activeColor = IosTheme.colors.label
    val inactiveColor = IosTheme.colors.secondaryLabel
    val indicatorColor = IosTheme.colors.accent
    val feeds = HomeFeedType.entries
    val tabCenters = remember { FloatArray(feeds.size) }
    val p = transitionProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.drawWithContent {
                drawContent()
                val curIdx = currentFeed.ordinal
                val curCenter = tabCenters[curIdx]
                if (curCenter > 0f) {
                    val dstIdx = (targetFeed ?: currentFeed).ordinal
                    val dstCenter = tabCenters[dstIdx].takeIf { it > 0f } ?: curCenter
                    val center = curCenter + (dstCenter - curCenter) * p
                    val baseWidth = 16.dp.toPx()
                    val stretch = min(abs(dstCenter - curCenter) * 0.35f, 14.dp.toPx()) *
                        sin(p * Math.PI.toFloat())
                    val indicatorWidth = baseWidth + stretch
                    val indicatorHeight = 2.5.dp.toPx()
                    val topY = size.height - 2.dp.toPx() - indicatorHeight
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(center - indicatorWidth / 2f, topY),
                        size = Size(indicatorWidth, indicatorHeight),
                        cornerRadius = CornerRadius(indicatorHeight / 2f, indicatorHeight / 2f),
                    )
                }
            },
        ) {
            feeds.forEachIndexed { index, feed ->
                val weight = when {
                    targetFeed != null && targetFeed != currentFeed && feed == currentFeed -> 1f - p
                    targetFeed != null && targetFeed != currentFeed && feed == targetFeed -> p
                    feed == currentFeed -> 1f
                    else -> 0f
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            tabCenters[index] = coords.positionInParent().x + coords.size.width / 2f
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onFeedSelect(feed) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = feed.label,
                        fontSize = (14f + 1.5f * weight).sp,
                        fontWeight = if (weight >= 0.5f) FontWeight.Bold else FontWeight.Normal,
                        color = lerp(inactiveColor, activeColor, weight),
                    )
                    Spacer(Modifier.height(4.5.dp))
                }
            }
        }
    }
}

private val CATEGORY_LEVEL_REGEX = Regex("[,，\\s]*Lv\\s*(\\d+)\\s*$", RegexOption.IGNORE_CASE)

internal fun categoryLevelOf(category: Category): Int =
    CATEGORY_LEVEL_REGEX.find(category.name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 3) ?: 0

internal fun categoryBaseNameOf(name: String): String =
    name.replace(CATEGORY_LEVEL_REGEX, "").trim().ifBlank { name }

@Composable
private fun CategoryVariantSelector(
    title: String,
    variants: List<Category>,
    currentCategoryId: Int?,
    collapsed: Boolean,
    onSelect: (Category) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentCategory = variants.firstOrNull { it.id == currentCategoryId }
    val currentLevel = currentCategory?.let(::categoryLevelOf)
        ?: CATEGORY_LEVEL_REGEX.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 3)
        ?: 0
    val baseTitle = categoryBaseNameOf(currentCategory?.name ?: title)
    val isDark = IosTheme.colors.isDark

    val (pillBg, pillBorder, pillText) = levelPillColors(currentLevel, isDark)

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(
                    bottom = if (collapsed) 0.dp else 6.dp,
                    end = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = baseTitle,
                style = if (collapsed) {
                    IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    IosTheme.type.largeTitle
                },
                color = IosTheme.colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(pillBg)
                    .border(0.8.dp, pillBorder, RoundedCornerShape(6.dp))
                    .padding(
                        horizontal = if (collapsed) 6.dp else 8.dp,
                        vertical = if (collapsed) 2.dp else 3.5.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Lv.$currentLevel",
                    style = IosTheme.type.caption.copy(
                        fontSize = if (collapsed) 11.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = pillText,
                )
                DropdownChevronGlyph(
                    color = pillText,
                    expanded = expanded,
                    size = if (collapsed) 9.dp else 10.dp,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = IosTheme.colors.card,
            shape = RoundedCornerShape(14.dp),
        ) {
            variants.forEachIndexed { idx, variant ->
                val level = categoryLevelOf(variant)
                val selected = variant.id == currentCategoryId
                val (itemBg, itemBorder, itemText) = levelPillColors(level, isDark)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = false
                            onSelect(variant)
                        }
                        .background(
                            if (selected) IosTheme.colors.accent.copy(alpha = 0.08f) else Color.Transparent,
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(parseCategoryColor(variant.color) ?: IosTheme.colors.tertiaryLabel),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = categoryBaseNameOf(variant.name),
                        style = IosTheme.type.subheadline,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) IosTheme.colors.accent else IosTheme.colors.label,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(itemBg)
                            .border(0.7.dp, itemBorder, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                    ) {
                        Text(
                            text = "Lv.$level",
                            style = IosTheme.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = itemText,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    if (selected) {
                        Text(
                            text = "✓",
                            style = IosTheme.type.subheadline,
                            fontWeight = FontWeight.Bold,
                            color = IosTheme.colors.accent,
                        )
                    } else if (variant.topicCount > 0) {
                        Text(
                            text = "${variant.topicCount}",
                            style = IosTheme.type.caption,
                            color = IosTheme.colors.tertiaryLabel,
                        )
                    }
                }
                if (idx < variants.lastIndex) {
                    IosDivider(startIndent = 14.dp)
                }
            }
        }
    }
}

internal fun levelPillColors(level: Int, isDark: Boolean): Triple<Color, Color, Color> = when (level) {
    0 -> if (isDark) {
        Triple(Color(0xFF25282E), Color(0xFF3E434D), Color(0xFFD5D8E0))
    } else {
        Triple(Color(0xFFF0F2F6), Color(0xFFD5DAE3), Color(0xFF4A5160))
    }
    1 -> if (isDark) {
        Triple(Color(0xFF152942), Color(0xFF2B548C), Color(0xFF6EAEFF))
    } else {
        Triple(Color(0xFFEAF3FF), Color(0xFFBBD7FF), Color(0xFF155FD0))
    }
    2 -> if (isDark) {
        Triple(Color(0xFF36250B), Color(0xFF8F651A), Color(0xFFFFD166))
    } else {
        Triple(Color(0xFFFFF4D9), Color(0xFFEFC163), Color(0xFF824B05))
    }
    else -> Triple(Color(0xFF4A3410), Color(0xFFD4AF37), Color(0xFFFFE28A))
}

@Composable
private fun DropdownChevronGlyph(
    color: Color,
    expanded: Boolean,
    size: Dp,
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            if (expanded) {
                moveTo(w * 0.18f, h * 0.68f)
                lineTo(w * 0.5f, h * 0.32f)
                lineTo(w * 0.82f, h * 0.68f)
            } else {
                moveTo(w * 0.18f, h * 0.34f)
                lineTo(w * 0.5f, h * 0.70f)
                lineTo(w * 0.82f, h * 0.34f)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w * 0.2f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun IncomingTopicsBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(22.dp),
                spotColor = Color(0x33007AFF),
                ambientColor = Color(0x22007AFF),
            )
            .clip(RoundedCornerShape(22.dp))
            .background(IosTheme.colors.card)
            .border(1.dp, IosTheme.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(color = Color(0xFF007AFF))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "有 $count 条新话题动态，点击查看 ↑",
                style = IosTheme.type.footnote,
                color = IosTheme.colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun TopicNotificationLevelTag(
    level: TopicNotificationLevel,
    modifier: Modifier = Modifier,
) {
    if (level == TopicNotificationLevel.Regular) return
    val color = notificationLevelColor(level)
    val isDark = IosTheme.colors.isDark
    val chipShape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(chipShape)
            .border(0.75.dp, color.copy(alpha = if (isDark) 0.35f else 0.22f), chipShape)
            .background(color.copy(alpha = if (isDark) 0.16f else 0.08f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.5.dp))
        Text(
            text = level.badgeLabel,
            style = IosTheme.type.footnote.copy(fontSize = 11.5.sp),
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun notificationLevelColor(level: TopicNotificationLevel): Color = when (level) {
    TopicNotificationLevel.Watching -> Color(0xFF007AFF)
    TopicNotificationLevel.Tracking -> Color(0xFF34C759)
    TopicNotificationLevel.Regular -> Color(0xFF8E8E93)
    TopicNotificationLevel.Muted -> Color(0xFFFF3B30)
}

@Composable
internal fun TopicNotificationIcon(level: TopicNotificationLevel, modifier: Modifier = Modifier) {
    val color = notificationLevelColor(level)
    Canvas(modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val bell = Path().apply {
                moveTo(5f, 17f)
                lineTo(7f, 14f)
                lineTo(7f, 9f)
                cubicTo(7f, 2.5f, 17f, 2.5f, 17f, 9f)
                lineTo(17f, 14f)
                lineTo(19f, 17f)
                close()
            }
            drawPath(bell, color, style = Stroke(1.7f, cap = StrokeCap.Round))
            drawLine(color, Offset(10f, 20f), Offset(14f, 20f), 1.7f, StrokeCap.Round)
            if (level == TopicNotificationLevel.Muted) {
                drawLine(color, Offset(3f, 3f), Offset(21f, 21f), 1.7f, StrokeCap.Round)
            } else if (level == TopicNotificationLevel.Watching) {
                drawCircle(color, 2f, Offset(20f, 4f))
            }
        }
    }
}

@Composable
private fun TopicFlagDialog(
    topic: Topic,
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (reasonId: Int, message: String?) -> Unit,
) {
    var selectedReason by remember(topic.id) { mutableStateOf(PostFlagReason.Spam) }
    var customMessage by rememberSaveable(topic.id) { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(IosTheme.colors.card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "举报话题",
                style = IosTheme.type.headline,
                fontWeight = FontWeight.SemiBold,
                color = IosTheme.colors.label,
            )
            Text(
                topic.title,
                style = IosTheme.type.footnote,
                color = IosTheme.colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PostFlagReason.entries.forEach { reason ->
                    val isSelected = selectedReason == reason
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) IosTheme.colors.fieldBackground else Color.Transparent)
                            .clickable(enabled = !submitting) { selectedReason = reason }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .border(
                                    1.5.dp,
                                    if (isSelected) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(IosTheme.colors.accent, CircleShape),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(reason.title, style = IosTheme.type.subheadline, fontWeight = FontWeight.Medium)
                            Text(reason.description, style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                        }
                    }
                }
            }

            if (selectedReason.requiresMessage) {
                BasicTextField(
                    value = customMessage,
                    enabled = !submitting,
                    onValueChange = { customMessage = it },
                    textStyle = IosTheme.type.subheadline.copy(color = IosTheme.colors.label),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(IosTheme.colors.fieldBackground)
                        .padding(8.dp),
                    decorationBox = { innerTextField ->
                        if (customMessage.isEmpty()) {
                            Text("请详细说明需要版主处理的问题...", style = IosTheme.type.subheadline, color = IosTheme.colors.tertiaryLabel)
                        }
                        innerTextField()
                    },
                )
            }
            error?.let { Text(it, style = IosTheme.type.footnote, color = IosTheme.colors.destructive) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(IosTheme.colors.fieldBackground)
                        .clickable(enabled = !submitting, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", style = IosTheme.type.subheadline, color = IosTheme.colors.secondaryLabel)
                }
                val canSubmit = !submitting && (!selectedReason.requiresMessage || customMessage.isNotBlank())
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (canSubmit) IosTheme.colors.accent else IosTheme.colors.accent.copy(alpha = 0.4f))
                        .clickable(enabled = canSubmit) {
                            onSubmit(selectedReason.id, customMessage.trim().takeIf { selectedReason.requiresMessage })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (submitting) "提交中…" else "提交",
                        style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private const val FLOATING_BALL_IDLE_ALPHA = 0.18f

/**
 * 辅助悬浮按钮设计方案（单功能直达 / 多功能伞型自适应展开，支持自由拖拽与边缘吸附记忆）。
 */
@Composable
private fun FloatingAssistiveBall(
    actions: List<FloatingAction>,
    sortOrder: TopicSortOrder,
    sortAscending: Boolean,
    initialXRatio: Float,
    initialYRatio: Float,
    onPositionChange: (Float, Float) -> Unit,
    onActionClick: (FloatingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    val expandProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expandProgress",
    )

    val isSingle = actions.size == 1

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        val mainBallSize = 52.dp
        val mainBallSizePx = with(density) { mainBallSize.toPx() }

        // 自由拖拽安全边界：避开最顶部状态栏（32dp）与底栏（72dp），四周留 6dp 防切边
        val minX = with(density) { 6.dp.toPx() }
        val maxX = (screenWidthPx - mainBallSizePx - with(density) { 6.dp.toPx() }).coerceAtLeast(minX)
        val minY = with(density) { 32.dp.toPx() }
        val maxY = (screenHeightPx - mainBallSizePx - with(density) { 72.dp.toPx() }).coerceAtLeast(minY)

        // 采用真实绝对物理像素坐标：手指拖到哪停在哪，完全消除相对基准点重置与强行吸附造成的乱跳
        var currentXPx by remember {
            mutableFloatStateOf(
                (minX + initialXRatio.coerceIn(0f, 1f) * (maxX - minX)).coerceIn(minX, maxX)
            )
        }
        var currentYPx by remember {
            mutableFloatStateOf(
                (minY + initialYRatio.coerceIn(0f, 1f) * (maxY - minY)).coerceIn(minY, maxY)
            )
        }

        val mainCenter = Offset(currentXPx + mainBallSizePx / 2f, currentYPx + mainBallSizePx / 2f)

        // 四象限智能空间判定：
        // 1. 水平方向：右半区朝左展开，左半区朝右展开
        val isRightSide = mainCenter.x >= screenWidthPx / 2f
        // 2. 垂直方向：上半区（上方不足 142dp+56dp）朝下展开，下半区朝上展开
        val needExpandDownward = mainCenter.y < (with(density) { 142.dp.toPx() } + with(density) { 56.dp.toPx() })

        // 静止淡出透明度：未交互时近乎透明（0.18f），触碰、拖拽或展开时平滑升为 1.0f，绝不遮挡阅读
        val idleAlpha by animateFloatAsState(
            targetValue = if (isExpanded || isDragging) 1f else FLOATING_BALL_IDLE_ALPHA,
            animationSpec = tween(220),
            label = "fabIdleAlpha",
        )

        // 1. 全屏轻柔半透明蒙层（展开时淡出）
        if (expandProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.26f * expandProgress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isExpanded = false },
                    ),
            )
        }

        // 2. 触控涟漪光环（展开时从主球周围扩散出的淡蓝水纹圆环，参照设计图右侧）
        if (expandProgress > 0.01f) {
            val outerRadiusPx = with(density) { 38.dp.toPx() }
            val innerRadiusPx = with(density) { 32.dp.toPx() }

            // 外层水纹圆环
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (mainCenter.x - outerRadiusPx).roundToInt(),
                            (mainCenter.y - outerRadiusPx).roundToInt(),
                        )
                    }
                    .size(76.dp)
                    .border(
                        width = 1.2.dp,
                        color = Color(0xFF2196F3).copy(alpha = 0.22f * expandProgress),
                        shape = CircleShape,
                    ),
            )
            // 内层水纹圆环
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (mainCenter.x - innerRadiusPx).roundToInt(),
                            (mainCenter.y - innerRadiusPx).roundToInt(),
                        )
                    }
                    .size(64.dp)
                    .border(
                        width = 1.4.dp,
                        color = Color(0xFF2196F3).copy(alpha = 0.38f * expandProgress),
                        shape = CircleShape,
                    ),
            )
        }

        // 3. 静止底晕（静止时主球周围有极浅的微蓝晕圈，柔和贴边，不突兀）
        if (expandProgress < 0.05f) {
            val haloRadiusPx = with(density) { 29.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (mainCenter.x - haloRadiusPx).roundToInt(),
                            (mainCenter.y - haloRadiusPx).roundToInt(),
                        )
                    }
                    .size(58.dp)
                    .graphicsLayer { alpha = idleAlpha }
                    .background(Color(0xFF2196F3).copy(alpha = 0.12f), CircleShape),
            )
        }

        // 4. 展开的子按钮群（四象限智能朝向：右上向左下，右下向左上，左上向右下，左下向右上）
        if (!isSingle && expandProgress > 0.01f) {
            val count = actions.size
            val subRadiusDp = when {
                count >= 8 -> 168.dp
                count >= 6 -> 154.dp
                else -> 142.dp
            }
            val subRadiusPx = with(density) { subRadiusDp.toPx() } * expandProgress
            val subBallSize = 42.dp
            val subBallSizePx = with(density) { subBallSize.toPx() }

            val startAngle = if (isRightSide) {
                if (needExpandDownward) 184.0 else 84.0
            } else {
                if (needExpandDownward) 356.0 else 96.0
            }
            val endAngle = if (isRightSide) {
                if (needExpandDownward) 266.0 else 176.0
            } else {
                if (needExpandDownward) 274.0 else 4.0
            }

            actions.forEachIndexed { i, action ->
                val angle = if (count <= 1) 0.0 else startAngle + i.toDouble() * (endAngle - startAngle) / (count - 1).toDouble()
                val rad = Math.toRadians(angle)
                val subX = mainCenter.x + (subRadiusPx * cos(rad)).toFloat()
                val subY = mainCenter.y - (subRadiusPx * sin(rad)).toFloat()

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (subX - subBallSizePx / 2f).roundToInt(),
                                (subY - subBallSizePx / 2f).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            alpha = expandProgress.coerceIn(0f, 1f)
                            scaleX = 0.35f + 0.65f * expandProgress
                            scaleY = 0.35f + 0.65f * expandProgress
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 子按钮圆球
                    Box(
                        modifier = Modifier
                            .size(subBallSize)
                            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(floatingActionColor(action))
                            .border(1.2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                isExpanded = false
                                onActionClick(action)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        FloatingActionIcon(action = action, tint = Color.White, modifier = Modifier.size(19.dp))
                    }

                    // 精致白底药丸标签（文字小巧上移，避免相互压盖与遮挡）
                    Box(
                        modifier = Modifier
                            .offset(y = 21.dp)
                            .shadow(elevation = 1.5.dp, shape = RoundedCornerShape(8.dp), clip = false)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IosTheme.colors.card.copy(alpha = 0.96f))
                            .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 5.5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = action.shortLabel,
                            color = IosTheme.colors.label,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 5. 主悬浮球（采用绝对像素平移，松手直接驻留目标坐标，支持全屏幕自由拖放）
        val ballRotation = expandProgress * 45f
        val isDark = IosTheme.colors.isDark
        val fabDarkBg = if (isDark) Color(0xFF383A3E) else Color(0xFF242629)

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(currentXPx.roundToInt(), currentYPx.roundToInt())
                }
                .size(mainBallSize)
                .graphicsLayer {
                    alpha = idleAlpha
                }
                .shadow(elevation = if (isExpanded) 5.dp else 2.5.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .then(
                    if (isSingle) {
                        Modifier
                            .background(IosTheme.colors.card.copy(alpha = 0.92f))
                            .border(1.dp, IosTheme.colors.separator, CircleShape)
                    } else if (isExpanded) {
                        Modifier
                            .background(if (isDark) Color(0xFF222426) else Color(0xFF141517))
                            .border(1.2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    } else {
                        Modifier
                            .background(fabDarkBg)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    }
                )
                .pointerInput(isSingle, actions) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        var totalDragDistance = 0f
                        val pointerId = down.id
                        var hasMoved = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (change.changedToUp()) {
                                change.consume()
                                isDragging = false
                                if (!hasMoved) {
                                    if (isSingle) {
                                        onActionClick(actions.first())
                                    } else {
                                        isExpanded = !isExpanded
                                    }
                                } else {
                                    val finalXRatio = if (maxX > minX) ((currentXPx - minX) / (maxX - minX)).coerceIn(0f, 1f) else 0.5f
                                    val finalYRatio = if (maxY > minY) ((currentYPx - minY) / (maxY - minY)).coerceIn(0f, 1f) else 0.5f
                                    onPositionChange(finalXRatio, finalYRatio)
                                }
                                break
                            }

                            val drag = change.positionChange()
                            if (drag != Offset.Zero) {
                                totalDragDistance += drag.getDistance()
                                if (totalDragDistance > 8f) {
                                    hasMoved = true
                                    change.consume()
                                    currentXPx = (currentXPx + drag.x).coerceIn(minX, maxX)
                                    currentYPx = (currentYPx + drag.y).coerceIn(minY, maxY)
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isSingle) {
                // 单功能展示：图标 + 下方小字
                val singleAction = actions.first()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    FloatingActionIcon(
                        action = singleAction,
                        tint = if (isDark) Color.White else Color(0xFF181818),
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = singleAction.shortLabel,
                        style = IosTheme.type.caption.copy(fontWeight = FontWeight.Medium, fontSize = 9.sp),
                        color = IosTheme.colors.label,
                    )
                }
            } else {
                // 多功能主球：旋转动画，+ 号平滑过渡为 ×
                Box(
                    modifier = Modifier.graphicsLayer {
                        rotationZ = ballRotation
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(20.dp)) {
                        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                            val stroke = 2.4f
                            // 水平横线
                            drawLine(Color.White, Offset(5.5f, 12f), Offset(18.5f, 12f), strokeWidth = stroke, cap = StrokeCap.Round)
                            // 垂直竖线
                            drawLine(Color.White, Offset(12f, 5.5f), Offset(12f, 18.5f), strokeWidth = stroke, cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

/** 辅助悬浮按钮各功能的柔和雅致专属配色（低饱和度，对齐系统级设计图）。 */
private fun floatingActionColor(action: FloatingAction): Color = when (action) {
    FloatingAction.CreateTopic -> Color(0xFFF06A55) // 暖珊瑚西柚橙
    FloatingAction.Bookmarks -> Color(0xFFFAAD14)   // 典雅琥珀金
    FloatingAction.Following -> Color(0xFF2979FF)   // 皇家明亮蓝
    FloatingAction.Likes -> Color(0xFFFF4D4F)       // 活力点赞红
    FloatingAction.Replies -> Color(0xFF00BFA5)     // 翠湖薄荷青
    FloatingAction.Sort -> Color(0xFF2196F3)        // 纯净主题天蓝
    FloatingAction.Drafts -> Color(0xFF8C9BB0)      // 雅致灰紫蓝
    FloatingAction.Search -> Color(0xFF68A398)      // 柔和灰海青
    FloatingAction.Categories -> Color(0xFFEAA63B)  // 暖金琥珀
    FloatingAction.History -> Color(0xFF7E57C2)     // 典雅紫罗兰
    FloatingAction.Connect -> Color(0xFF1E88E5)     // LinuxDo 极客蓝
    FloatingAction.Chat -> Color(0xFFE91E63)        // 活力珊瑚粉
    FloatingAction.Apps -> Color(0xFF07C160)        // 微信小程序绿
}

/** 辅助悬浮按钮各功能的纯矢量精致手绘图标。 */
@Composable
private fun FloatingActionIcon(
    action: FloatingAction,
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp),
) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (action) {
                FloatingAction.CreateTopic -> {
                    // 铅笔 ✏️
                    val pen = Path().apply {
                        moveTo(4f, 20f)
                        lineTo(8.5f, 19f)
                        lineTo(19.5f, 8f)
                        lineTo(16f, 4.5f)
                        lineTo(5f, 15.5f)
                        close()
                    }
                    drawPath(pen, tint, style = stroke)
                    drawLine(tint, Offset(13.5f, 7f), Offset(17f, 10.5f), strokeWidth = 1.9f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(12.5f, 20f), Offset(20f, 20f), strokeWidth = 1.9f, cap = StrokeCap.Round)
                }
                FloatingAction.Bookmarks -> {
                    // 燕尾书签 🔖
                    val bookmark = Path().apply {
                        moveTo(6.5f, 4.5f)
                        lineTo(17.5f, 4.5f)
                        lineTo(17.5f, 19.5f)
                        lineTo(12f, 15.5f)
                        lineTo(6.5f, 19.5f)
                        close()
                    }
                    drawPath(bookmark, tint, style = stroke)
                    drawLine(tint, Offset(9.5f, 9.5f), Offset(14.5f, 9.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                }
                FloatingAction.Following -> {
                    // 关注用户群组人像 👤⁺
                    drawCircle(tint, 3.2f, Offset(9f, 7.5f), style = stroke)
                    val shoulder = Path().apply {
                        moveTo(3.5f, 19.5f)
                        cubicTo(3.5f, 15f, 6.5f, 13.5f, 9f, 13.5f)
                        cubicTo(11.5f, 13.5f, 14.5f, 15f, 14.5f, 19.5f)
                    }
                    drawPath(shoulder, tint, style = stroke)
                    drawLine(tint, Offset(18.5f, 8f), Offset(18.5f, 14f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(15.5f, 11f), Offset(21.5f, 11f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                }
                FloatingAction.Likes -> {
                    // 竖大拇指点赞 👍
                    val thumb = Path().apply {
                        moveTo(4.5f, 12f)
                        lineTo(4.5f, 19.5f)
                        lineTo(7.5f, 19.5f)
                        lineTo(7.5f, 12f)
                        close()
                    }
                    drawPath(thumb, tint, style = stroke)
                    val palm = Path().apply {
                        moveTo(7.5f, 13f)
                        lineTo(10.5f, 11f)
                        lineTo(12.5f, 4.5f)
                        cubicTo(13.2f, 3.5f, 14.5f, 3.5f, 15f, 4.5f)
                        cubicTo(15.2f, 5.5f, 14.2f, 8f, 13.5f, 10.5f)
                        lineTo(17.5f, 10.5f)
                        cubicTo(19f, 10.5f, 19.5f, 11.5f, 19.5f, 12.8f)
                        lineTo(19f, 17f)
                        cubicTo(18.8f, 18.2f, 17.8f, 19.5f, 16.5f, 19.5f)
                        lineTo(7.5f, 19.5f)
                    }
                    drawPath(palm, tint, style = stroke)
                }
                FloatingAction.Replies -> {
                    // 弯曲回折回复 ↩️
                    val reply = Path().apply {
                        moveTo(18.5f, 18f)
                        cubicTo(18.5f, 11.5f, 14.5f, 8.5f, 9.5f, 8.5f)
                    }
                    drawPath(reply, tint, style = stroke)
                    val arrowHead = Path().apply {
                        moveTo(13.5f, 5f)
                        lineTo(9f, 8.5f)
                        lineTo(13.5f, 12f)
                    }
                    drawPath(arrowHead, tint, style = stroke)
                    drawLine(tint, Offset(5f, 19.5f), Offset(19f, 19.5f), strokeWidth = 1.9f, cap = StrokeCap.Round)
                }
                FloatingAction.Sort -> {
                    // 垂直双向箭头 ⇅
                    drawLine(tint, Offset(8f, 18.5f), Offset(8f, 5.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    val leftArrow = Path().apply {
                        moveTo(4.5f, 9.5f)
                        lineTo(8f, 5.5f)
                        lineTo(11.5f, 9.5f)
                    }
                    drawPath(leftArrow, tint, style = stroke)

                    drawLine(tint, Offset(16f, 5.5f), Offset(16f, 18.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    val rightArrow = Path().apply {
                        moveTo(12.5f, 14.5f)
                        lineTo(16f, 18.5f)
                        lineTo(19.5f, 14.5f)
                    }
                    drawPath(rightArrow, tint, style = stroke)
                }
                FloatingAction.Drafts -> {
                    // 文件夹 📁
                    val folder = Path().apply {
                        moveTo(3.5f, 7f)
                        lineTo(8.5f, 7f)
                        lineTo(10.5f, 9.5f)
                        lineTo(20.5f, 9.5f)
                        lineTo(20.5f, 18.5f)
                        lineTo(3.5f, 18.5f)
                        close()
                    }
                    drawPath(folder, tint, style = stroke)
                    drawLine(tint, Offset(3.5f, 10.5f), Offset(20.5f, 10.5f), strokeWidth = 1.6f, cap = StrokeCap.Round)
                }
                FloatingAction.Search -> {
                    // 放大镜 🔍
                    drawCircle(tint, 5.5f, Offset(10.5f, 10.5f), style = stroke)
                    drawLine(tint, Offset(14.8f, 14.8f), Offset(20f, 20f), strokeWidth = 2.3f, cap = StrokeCap.Round)
                }
                FloatingAction.Categories -> {
                    // 分类板块 🗂（4 个圆角小卡片）
                    val r = 1.5f
                    drawRoundRect(tint, Offset(4.5f, 4.5f), Size(6.5f, 6.5f), CornerRadius(r, r), style = stroke)
                    drawRoundRect(tint, Offset(13f, 4.5f), Size(6.5f, 6.5f), CornerRadius(r, r), style = stroke)
                    drawRoundRect(tint, Offset(4.5f, 13f), Size(6.5f, 6.5f), CornerRadius(r, r), style = stroke)
                    drawRoundRect(tint, Offset(13f, 13f), Size(6.5f, 6.5f), CornerRadius(r, r), style = stroke)
                }
                FloatingAction.History -> {
                    // 历史时钟回旋箭头 🕒 ⟲
                    drawArc(
                        color = tint,
                        startAngle = 40f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = Offset(4f, 4f),
                        size = Size(16f, 16f),
                        style = stroke,
                    )
                    val arrow = Path().apply {
                        moveTo(9.5f, 2.5f)
                        lineTo(4f, 4.5f)
                        lineTo(5.5f, 10f)
                    }
                    drawPath(arrow, tint, style = stroke)
                    drawLine(tint, Offset(12f, 12f), Offset(12f, 7.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(12f, 12f), Offset(15.5f, 12f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                }
                FloatingAction.Connect -> {
                    // 极客互联双链 🔗
                    val l1 = Path().apply {
                        moveTo(10f, 13f)
                        lineTo(8f, 15f)
                        cubicTo(6.5f, 16.5f, 6.5f, 19f, 8f, 20.5f)
                        cubicTo(9.5f, 22f, 12f, 22f, 13.5f, 20.5f)
                        lineTo(15.5f, 18.5f)
                    }
                    val l2 = Path().apply {
                        moveTo(14f, 11f)
                        lineTo(16f, 9f)
                        cubicTo(17.5f, 7.5f, 17.5f, 5f, 16f, 3.5f)
                        cubicTo(14.5f, 2f, 12f, 2f, 10.5f, 3.5f)
                        lineTo(8.5f, 5.5f)
                    }
                    drawPath(l1, tint, style = stroke)
                    drawPath(l2, tint, style = stroke)
                    drawLine(tint, Offset(9.5f, 14.5f), Offset(14.5f, 9.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                }
                FloatingAction.Chat -> {
                    // 实时聊天对话气泡 💬
                    val bubble = Path().apply {
                        moveTo(4.5f, 6f)
                        cubicTo(4.5f, 4.5f, 5.5f, 4f, 7f, 4f)
                        lineTo(17f, 4f)
                        cubicTo(18.5f, 4f, 19.5f, 4.5f, 19.5f, 6f)
                        lineTo(19.5f, 14f)
                        cubicTo(19.5f, 15.5f, 18.5f, 16f, 17f, 16f)
                        lineTo(9.5f, 16f)
                        lineTo(5.5f, 19.5f)
                        lineTo(5.5f, 16f)
                        lineTo(4.5f, 16f)
                        close()
                    }
                    drawPath(bubble, tint, style = stroke)
                    drawCircle(tint, 1.1f, Offset(8.5f, 10f))
                    drawCircle(tint, 1.1f, Offset(12f, 10f))
                    drawCircle(tint, 1.1f, Offset(15.5f, 10f))
                }
                FloatingAction.Apps -> {
                    // 小程序双环徽章
                    drawArc(
                        color = tint,
                        startAngle = 120f,
                        sweepAngle = 250f,
                        useCenter = false,
                        topLeft = Offset(3.5f, 3.5f),
                        size = Size(11f, 11f),
                        style = stroke,
                    )
                    drawArc(
                        color = tint,
                        startAngle = -60f,
                        sweepAngle = 250f,
                        useCenter = false,
                        topLeft = Offset(9.5f, 9.5f),
                        size = Size(11f, 11f),
                        style = stroke,
                    )
                }
            }
        }
    }
}



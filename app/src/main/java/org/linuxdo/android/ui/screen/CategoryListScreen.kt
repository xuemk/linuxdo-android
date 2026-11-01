package org.linuxdo.android.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.linuxdo.android.Config
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.CustomSection
import org.linuxdo.android.data.CustomSectionLink
import org.linuxdo.android.data.flattenCategories
import org.linuxdo.android.data.orderedCategoryGroups
import org.linuxdo.android.ui.CategoriesUiState
import org.linuxdo.android.ui.design.*

private val LEVEL_NUMBER_REGEX = Regex("Lv\\s*(\\d+)", RegexOption.IGNORE_CASE)

private fun extractLevelNumber(name: String): Int =
    LEVEL_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

/**
 * 具有半透明磨砂亚克力/毛玻璃质感的分类卡片容器 (Glassmorphism)，
 * 搭配 1px 细微高光玻璃边框与悬浮微投影，不改动全屏底色，模块自身通透出众。
 */
@Composable
private fun GlassCategoryCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    val cardShape = RoundedCornerShape(14.dp)
    val glassBg = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF232630).copy(alpha = 0.68f),
                Color(0xFF171920).copy(alpha = 0.52f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.88f),
                Color.White.copy(alpha = 0.70f),
            )
        )
    }
    val glassBorder = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.06f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.95f),
                Color(0xFFE2E4E8).copy(alpha = 0.65f),
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IosMetrics.screenPadding)
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(cardShape)
            .background(glassBg)
            .border(1.dp, glassBorder, cardShape),
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryListScreen(
    state: CategoriesUiState,
    onRetry: () -> Unit,
    onOpen: (Category) -> Unit,
    customSection: CustomSection? = null,
    onOpenTopic: (Long, String, Int?) -> Unit = { _, _, _ -> },
    onOpenWebUrl: (String) -> Unit = {},
    onSaveCustomSection: (CustomSection) -> Unit = {},
    onDeleteCustomSection: () -> Unit = {},
    scrollToTopRequest: Int = 0,
    onSaveOrder: (List<Long>) -> Unit = {},
    onResetOrder: () -> Unit = {},
    currentTab: org.linuxdo.android.ui.CategorySectionTab = org.linuxdo.android.ui.CategorySectionTab.CATEGORIES,
    onTabChange: (org.linuxdo.android.ui.CategorySectionTab) -> Unit = {},
    tagsState: org.linuxdo.android.ui.TagsUiState = org.linuxdo.android.ui.TagsUiState(),
    tagSearchQuery: String = "",
    onTagSearchQueryChange: (String) -> Unit = {},
    onRetryTags: () -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val topScrollZone = with(LocalDensity.current) { 130.dp.toPx() }
    val bottomScrollZone = with(LocalDensity.current) { 140.dp.toPx() }
    val maxScrollSpeed = with(LocalDensity.current) { 14.dp.toPx() }
    val baseGroups = remember(state.items, state.order) {
        orderedCategoryGroups(state.items, state.order).map { (parent, levels) ->
            parent to levels.sortedBy { extractLevelNumber(it.name) }
        }
    }
    val groups = remember { mutableStateListOf<Pair<Category, List<Category>>>() }
    var draggedId by remember { mutableStateOf<Int?>(null) }
    var dragOrigin by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragPointerId by remember { mutableStateOf<PointerId?>(null) }
    var showCustomSectionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(baseGroups) {
        draggedId = null
        dragPointerId = null
        groups.clear()
        groups.addAll(baseGroups)
    }

    var showMenu by remember { mutableStateOf(false) }
    val dotColor = IosTheme.colors.label.copy(alpha = 0.7f)

    val combinedTrailing: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            trailing()
            Spacer(Modifier.width(6.dp))
            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { showMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val r = 2.dp.toPx()
                        drawCircle(dotColor, r, Offset(size.width * 0.2f, size.height * 0.5f))
                        drawCircle(dotColor, r, Offset(size.width * 0.5f, size.height * 0.5f))
                        drawCircle(dotColor, r, Offset(size.width * 0.8f, size.height * 0.5f))
                    }
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (customSection == null) "添加自定义版块" else "编辑自定义版块") },
                        onClick = {
                            showMenu = false
                            showCustomSectionDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("恢复默认分类顺序") },
                        onClick = {
                            showMenu = false
                            onResetOrder()
                        },
                    )
                }
            }
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int, persist: Boolean = true) {
        if (fromIndex == toIndex || fromIndex !in groups.indices || toIndex !in groups.indices) return
        groups.add(toIndex, groups.removeAt(fromIndex))
        if (persist) onSaveOrder(groups.map { it.first.id.toLong() })
    }

    fun moveDraggedItem() {
        val activeId = draggedId ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val activeItem = visible.firstOrNull { it.key == activeId } ?: return
        val fromIndex = groups.indexOfFirst { it.first.id == activeId }
        if (fromIndex < 0) return
        val center = dragOrigin + dragDistance + activeItem.size / 2f
        val target = visible.firstOrNull { item ->
            val targetIndex = groups.indexOfFirst { it.first.id == item.key }
            targetIndex >= 0 && item.key != activeId &&
                center in item.offset.toFloat()..(item.offset + item.size).toFloat() &&
                ((targetIndex > fromIndex && center > item.offset + item.size / 2f) ||
                    (targetIndex < fromIndex && center < item.offset + item.size / 2f))
        }
        val targetIndex = if (target != null) {
            groups.indexOfFirst { it.first.id == target.key }
        } else {
            // 首尾越界兜底：拖拽中心若已越过首项顶部或末项底部，直接吸附到首位或末位
            val categoryItems = visible.filter { v -> groups.any { it.first.id == v.key } }
            val firstCat = categoryItems.firstOrNull()
            val lastCat = categoryItems.lastOrNull()
            when {
                firstCat != null && center < firstCat.offset && fromIndex > 0 -> 0
                lastCat != null && center > lastCat.offset + lastCat.size && fromIndex < groups.lastIndex -> groups.lastIndex
                else -> -1
            }
        }
        if (targetIndex in groups.indices && targetIndex != fromIndex) {
            moveItem(fromIndex, targetIndex, persist = false)
        }
    }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == draggedId } ?: continue
            val cardTop = dragOrigin + dragDistance
            val cardBottom = cardTop + item.size
            val viewportStart = layout.viewportStartOffset.toFloat()
            val viewportEnd = layout.viewportEndOffset.toFloat()

            val autoScroll = when {
                cardTop < viewportStart + topScrollZone -> {
                    val dist = (viewportStart + topScrollZone - cardTop).coerceAtLeast(0f)
                    val factor = (dist / topScrollZone).coerceIn(0.15f, 1f)
                    -maxScrollSpeed * factor
                }
                cardBottom > viewportEnd - bottomScrollZone -> {
                    val dist = (cardBottom - (viewportEnd - bottomScrollZone)).coerceAtLeast(0f)
                    val factor = (dist / bottomScrollZone).coerceIn(0.15f, 1f)
                    maxScrollSpeed * factor
                }
                else -> 0f
            }
            if (autoScroll != 0f) {
                listState.scrollBy(autoScroll)
                moveDraggedItem()
            }
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == org.linuxdo.android.ui.CategorySectionTab.TAGS && !tagsState.loaded && !tagsState.loading) {
            onRetryTags()
        }
    }

    val filteredTags = remember(tagsState.tags, tagsState.searchedTags, tagSearchQuery) {
        if (tagSearchQuery.isBlank()) {
            tagsState.tags.sortedWith(
                compareByDescending<org.linuxdo.android.data.DiscourseTag> { it.effectiveCount }
                    .thenBy { it.displayName.lowercase() }
            )
        } else {
            val q = tagSearchQuery.trim().lowercase()
            val merged = linkedMapOf<String, org.linuxdo.android.data.DiscourseTag>()
            for (tag in tagsState.tags) {
                if (tag.displayName.lowercase().contains(q) ||
                    tag.name.lowercase().contains(q) ||
                    tag.text.lowercase().contains(q) ||
                    tag.id.lowercase().contains(q)
                ) {
                    val key = tag.displayName.trim()
                    if (key.isNotEmpty()) {
                        merged[key] = tag.copy(count = tag.effectiveCount)
                    }
                }
            }
            for (tag in tagsState.searchedTags) {
                if (tag.displayName.lowercase().contains(q) ||
                    tag.name.lowercase().contains(q) ||
                    tag.text.lowercase().contains(q) ||
                    tag.id.lowercase().contains(q)
                ) {
                    val key = tag.displayName.trim()
                    if (key.isNotEmpty()) {
                        val existing = merged[key]
                        if (existing == null) {
                            merged[key] = tag.copy(count = tag.effectiveCount)
                        } else if (tag.effectiveCount > existing.count) {
                            merged[key] = existing.copy(count = tag.effectiveCount)
                        }
                    }
                }
            }
            merged.values.sortedWith(
                compareByDescending<org.linuxdo.android.data.DiscourseTag> { it.effectiveCount }
                    .thenBy { it.displayName.lowercase() }
            )
        }
    }

    val latestCurrentTab by rememberUpdatedState(currentTab)
    val latestOnTabChange by rememberUpdatedState(onTabChange)
    val latestDraggedId by rememberUpdatedState(draggedId)
    val tabSwipeEdgeMarginPx = with(LocalDensity.current) { 32.dp.toPx() }
    val tabSwipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    val segmentTitle: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosTheme.colors.fieldBackground)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isDark = IosTheme.colors.isDark
                val activeCardBg = if (isDark) Color(0xFF32353E) else Color.White
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (currentTab == org.linuxdo.android.ui.CategorySectionTab.CATEGORIES) activeCardBg else Color.Transparent)
                        .clickable { onTabChange(org.linuxdo.android.ui.CategorySectionTab.CATEGORIES) }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "分类",
                        style = IosTheme.type.footnote,
                        fontWeight = if (currentTab == org.linuxdo.android.ui.CategorySectionTab.CATEGORIES) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (currentTab == org.linuxdo.android.ui.CategorySectionTab.CATEGORIES) IosTheme.colors.label else IosTheme.colors.secondaryLabel,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (currentTab == org.linuxdo.android.ui.CategorySectionTab.TAGS) activeCardBg else Color.Transparent)
                        .clickable { onTabChange(org.linuxdo.android.ui.CategorySectionTab.TAGS) }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "标签",
                        style = IosTheme.type.footnote,
                        fontWeight = if (currentTab == org.linuxdo.android.ui.CategorySectionTab.TAGS) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (currentTab == org.linuxdo.android.ui.CategorySectionTab.TAGS) IosTheme.colors.label else IosTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(tabSwipeEdgeMarginPx, tabSwipeThresholdPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (latestDraggedId != null ||
                        down.position.x < tabSwipeEdgeMarginPx ||
                        (size.width > 0 && down.position.x > size.width - tabSwipeEdgeMarginPx)
                    ) {
                        return@awaitEachGesture
                    }
                    var dragMode = 0 // 0: 未定, 1: 水平切标签, 2: 垂直滚动或其他手势
                    var totalPan = Offset.Zero
                    var horizontalDrag = 0f
                    val touchSlop = viewConfiguration.touchSlop

                    do {
                        val event = awaitPointerEvent()
                        if (latestDraggedId != null) {
                            dragMode = 2
                            break
                        }
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
                                change.consume()
                            }
                            0 -> {
                                totalPan += delta
                                if (totalPan.getDistance() > touchSlop) {
                                    if (abs(totalPan.x) > abs(totalPan.y) * 1.35f) {
                                        dragMode = 1
                                        horizontalDrag = totalPan.x
                                        change.consume()
                                    } else {
                                        dragMode = 2
                                        break
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (dragMode == 1 && abs(horizontalDrag) >= tabSwipeThresholdPx) {
                        val tabs = org.linuxdo.android.ui.CategorySectionTab.entries
                        val nextIndex = if (horizontalDrag < 0f) {
                            (latestCurrentTab.ordinal + 1) % tabs.size
                        } else {
                            (latestCurrentTab.ordinal - 1 + tabs.size) % tabs.size
                        }
                        latestOnTabChange(tabs[nextIndex])
                    }
                }
            }
    ) {
    IosLargeTitleScaffold(
        title = if (currentTab == org.linuxdo.android.ui.CategorySectionTab.TAGS) "标签" else "分类",
        showLargeTitle = false,
        listState = listState,
        compactTop = false,
        scrollToTopRequest = scrollToTopRequest,
        trailing = if (currentTab == org.linuxdo.android.ui.CategorySectionTab.CATEGORIES) combinedTrailing else trailing,
        customCollapsedTitle = segmentTitle,
    ) {
        if (currentTab == org.linuxdo.android.ui.CategorySectionTab.CATEGORIES) {
            if (state.loading) item("loading") { IosLoadingBox() }
            state.error?.let { message -> item("error") { ErrorNotice(message, onRetry) } }
            if (state.loaded && state.items.isEmpty()) item("empty") { EmptyNotice() }

        // ── 自定义版块（固定在分类第一位，仅在用户配置后展示） ──
        if (customSection != null) {
            item("custom-section-card") {
                CustomSectionCard(
                    customSection = customSection,
                    categories = state.items,
                    onOpenCategory = onOpen,
                    onOpenTopic = onOpenTopic,
                    onOpenWebUrl = onOpenWebUrl,
                    onEdit = { showCustomSectionDialog = true },
                )
            }
        }

        itemsIndexed(groups, key = { _, pair -> pair.first.id }) { index, (parent, levels) ->
            var showCardMenu by remember { mutableStateOf(false) }
            val dragging = draggedId == parent.id
            GlassCategoryCard(Modifier
                .animateItem(placementSpec = if (dragging) null else tween(180))
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer {
                    val currentOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == parent.id }?.offset
                    translationY = if (dragging && currentOffset != null) dragOrigin + dragDistance - currentOffset else 0f
                    scaleX = if (dragging) 1.02f else 1f
                    scaleY = if (dragging) 1.02f else 1f
                    shadowElevation = if (dragging) 8.dp.toPx() else 0f
                }
                .pointerInput(parent.id, baseGroups) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == parent.id }
                            if (item != null) {
                                showCardMenu = false
                                dragOrigin = item.offset.toFloat()
                                dragDistance = 0f
                                draggedId = parent.id
                            }
                        },
                        onDrag = { change, amount ->
                            if (draggedId == parent.id) {
                                dragPointerId = change.id
                                change.consume()
                                dragDistance += amount.y
                                moveDraggedItem()
                            }
                        },
                        onDragEnd = {
                            if (draggedId != null) {
                                val ids = groups.map { it.first.id.toLong() }
                                draggedId = null
                                dragPointerId = null
                                if (ids != baseGroups.map { it.first.id.toLong() }) onSaveOrder(ids)
                            }
                        },
                        onDragCancel = {
                            draggedId = null
                            dragPointerId = null
                            groups.clear()
                            groups.addAll(baseGroups)
                        },
                    )
                }
                .padding(vertical = 8.dp)) {
                Box {
                    IosRow(
                        onClick = { if (draggedId == null) onOpen(parent) },
                    ) {
                        Column(Modifier.weight(1f).padding(top = 5.dp, bottom = 5.dp, end = 8.dp)) {
                            CategoryTag(parent, prominent = true)
                            Text(
                                text = "${parent.topicCount} 个话题",
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        if (levels.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                levels.forEach { category ->
                                    val levelNum = extractLevelNumber(category.name)
                                    LevelBadgeChip(
                                        level = levelNum,
                                        onClick = { onOpen(category) },
                                    )
                                }
                            }
                        }
                        Box(Modifier.size(32.dp).semantics { contentDescription = "调整分类顺序" }
                            .clickable { showCardMenu = true }, contentAlignment = Alignment.Center) {
                            Text("≡", style = IosTheme.type.title3, color = IosTheme.colors.secondaryLabel)
                        }
                    }

                    if (showCardMenu) {
                        DropdownMenu(
                            expanded = showCardMenu,
                            onDismissRequest = { showCardMenu = false },
                        ) {
                            if (index > 0) {
                                DropdownMenuItem(
                                    text = { Text("置顶") },
                                    onClick = {
                                        showCardMenu = false
                                        moveItem(index, 0)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("上移") },
                                    onClick = {
                                        showCardMenu = false
                                        moveItem(index, index - 1)
                                    },
                                )
                            }
                            if (index < groups.lastIndex) {
                                DropdownMenuItem(
                                    text = { Text("下移") },
                                    onClick = {
                                        showCardMenu = false
                                        moveItem(index, index + 1)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        } else {
            // ── 标签页面（仅在标签页面展示搜索筛选框） ──
            item("tags-search-bar") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = IosMetrics.screenPadding, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosTheme.colors.fieldBackground)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchLoupeIcon(
                            tint = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = tagSearchQuery,
                            onValueChange = onTagSearchQueryChange,
                            singleLine = true,
                            textStyle = IosTheme.type.body.copy(
                                color = IosTheme.colors.label,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(IosTheme.colors.accent),
                            decorationBox = { field ->
                                Box {
                                    if (tagSearchQuery.isEmpty()) {
                                        Text(
                                            text = if (tagsState.tags.isNotEmpty()) "搜索标签 (共 ${tagsState.tags.size} 个)..." else "搜索标签...",
                                            style = IosTheme.type.body.copy(fontSize = 14.sp),
                                            color = IosTheme.colors.secondaryLabel,
                                        )
                                    }
                                    field()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (tagSearchQuery.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(IosTheme.colors.secondaryLabel.copy(alpha = 0.2f))
                                    .clickable { onTagSearchQueryChange("") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", fontSize = 10.sp, color = IosTheme.colors.secondaryLabel)
                            }
                        }
                    }
                }
            }

            if (tagsState.loading && tagsState.tags.isEmpty()) {
                item("tags-loading") { IosLoadingBox() }
            }
            tagsState.error?.takeIf { tagsState.tags.isEmpty() }?.let { message ->
                item("tags-error") { ErrorNotice(message, onRetryTags) }
            }

            if (tagsState.loaded && filteredTags.isEmpty()) {
                item("tags-empty") {
                    EmptyNotice()
                }
            } else {
                items(
                    items = filteredTags.chunked(2),
                    key = { chunk -> "tags-chunk:" + chunk.joinToString(",") { it.displayName } }
                ) { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = IosMetrics.screenPadding, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { tag ->
                            Box(modifier = Modifier.weight(1f)) {
                                TagCard(tag = tag, onClick = { onOpenTag(tag.name.ifBlank { tag.displayName }) })
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    }

    if (showCustomSectionDialog) {
        CustomSectionDialog(
            initialSection = customSection,
            categories = state.items,
            onDismiss = { showCustomSectionDialog = false },
            onSave = {
                onSaveCustomSection(it)
                showCustomSectionDialog = false
            },
            onDelete = {
                onDeleteCustomSection()
                showCustomSectionDialog = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomSectionCard(
    customSection: CustomSection,
    categories: List<Category>,
    onOpenCategory: (Category) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenWebUrl: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable { mutableStateOf(true) }

    GlassCategoryCard(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEdit()
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    text = "⭐",
                    fontSize = 17.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = customSection.title.ifBlank { "自定义版块" },
                    style = IosTheme.type.headline,
                    fontWeight = FontWeight.Bold,
                    color = IosTheme.colors.label,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起 ▾" else "展开 ▸",
                    style = IosTheme.type.caption.copy(fontSize = 11.sp),
                    color = IosTheme.colors.accent,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(IosTheme.colors.fieldBackground)
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "长按编辑",
                        style = IosTheme.type.caption.copy(fontSize = 11.sp),
                        color = IosTheme.colors.secondaryLabel,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (expanded) {
                if (customSection.links.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "暂无快捷链接，长按卡片或点击右上角编辑添加",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    customSection.links.forEachIndexed { index, link ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = IosTheme.colors.separator.copy(alpha = 0.35f),
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        CustomSectionLinkRow(
                            link = link,
                            onClick = {
                                handleCustomLinkClick(
                                    url = link.url,
                                    name = link.name,
                                    categories = categories,
                                    onOpenCategory = onOpenCategory,
                                    onOpenTopic = onOpenTopic,
                                    onOpenWebUrl = onOpenWebUrl,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomSectionLinkRow(
    link: CustomSectionLink,
    onClick: () -> Unit,
) {
    val isTopic = remember(link.url) {
        val u = link.url.trim()
        u.contains("/t/") || u.contains("/topics/")
    }
    val isCategory = remember(link.url, isTopic) {
        val u = link.url.trim()
        !isTopic && (u.contains("/c/") || u.contains("/categories/"))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = link.icon.ifBlank { "🔗" },
            fontSize = 17.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = link.name.ifBlank { link.url },
            style = IosTheme.type.body,
            fontWeight = FontWeight.Normal,
            color = IosTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        val (tagBg, tagText, tagLabel) = when {
            isTopic -> Triple(
                Color(0xFFFF9500).copy(alpha = 0.12f),
                Color(0xFFFF9500),
                "帖子",
            )
            isCategory -> Triple(
                Color(0xFF007AFF).copy(alpha = 0.12f),
                Color(0xFF007AFF),
                "板块",
            )
            else -> Triple(
                IosTheme.colors.fieldBackground,
                IosTheme.colors.secondaryLabel,
                "链接",
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(tagBg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = tagLabel,
                style = IosTheme.type.caption.copy(fontSize = 10.sp),
                color = tagText,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "›",
            style = IosTheme.type.body,
            color = IosTheme.colors.tertiaryLabel,
            fontSize = 17.sp,
        )
    }
}

private fun handleCustomLinkClick(
    url: String,
    name: String,
    categories: List<Category>,
    onOpenCategory: (Category) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenWebUrl: (String) -> Unit,
) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return

    // 1. 尝试匹配 Topic 链接: /t/12345, /t/topic-slug/12345, /t/12345/last, https://linux.do/t/...
    val fullTopicRegex = Regex("""(?:https?://[^/]+)?/t/(?:[^/]+/)?(\d+)(?:/(\d+|last))?""")
    val topicMatch = fullTopicRegex.find(trimmed)
    if (topicMatch != null) {
        val topicId = topicMatch.groupValues[1].toLongOrNull()
        if (topicId != null && topicId > 0L) {
            val postStr = topicMatch.groupValues.getOrNull(2)
            val postNumber = when {
                postStr.equals("last", ignoreCase = true) -> Int.MAX_VALUE
                else -> postStr?.toIntOrNull()
            }
            onOpenTopic(topicId, name.ifBlank { "话题详情" }, postNumber)
            return
        }
    }

    // 2. 尝试匹配 Category ID 链接: /c/dev/5, /c/5, https://linux.do/c/dev/5
    val flattened = flattenCategories(categories)
    val catIdRegex = Regex("""(?:https?://[^/]+)?/c/(?:[^/]+/)?(\d+)""")
    val catIdMatch = catIdRegex.find(trimmed)
    if (catIdMatch != null) {
        val catId = catIdMatch.groupValues[1].toIntOrNull()
        val matchedCat = flattened.firstOrNull { it.id == catId }
        if (matchedCat != null) {
            onOpenCategory(matchedCat)
            return
        } else if (catId != null) {
            onOpenCategory(Category(id = catId, name = name.ifBlank { "分类" }, slug = ""))
            return
        }
    }

    // 3. 尝试匹配 Category slug 链接: /c/dev, /c/gqns
    val slugRegex = Regex("""(?:https?://[^/]+)?/c/([a-zA-Z0-9_-]+)""")
    val slugMatch = slugRegex.find(trimmed)
    if (slugMatch != null) {
        val slug = slugMatch.groupValues[1]
        val matchedCat = flattened.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
        if (matchedCat != null) {
            onOpenCategory(matchedCat)
            return
        }
    }

    // 4. 外部/相对路径通用 URL
    val fullUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "${Config.BASE_URL.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    } else {
        trimmed
    }
    onOpenWebUrl(fullUrl)
}

@Composable
private fun CustomSectionDialog(
    initialSection: CustomSection?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (CustomSection) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var title by remember { mutableStateOf(initialSection?.title ?: "自定义版块") }
    val links = remember {
        mutableStateListOf<CustomSectionLink>().apply {
            if (initialSection != null && initialSection.links.isNotEmpty()) {
                addAll(initialSection.links)
            } else {
                add(CustomSectionLink(icon = "🔗", name = "", url = ""))
            }
        }
    }
    var pickingCategoryIndex by remember { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(IosTheme.colors.card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .padding(16.dp),
            ) {
                // 顶部栏: 标题 + 关闭按钮 ✕
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (initialSection == null) "添加自定义版块" else "编辑自定义版块",
                        style = IosTheme.type.title3,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(IosTheme.colors.fieldBackground)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 中间可滚动区域
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 版块标题
                    Text(
                        text = "版块标题",
                        style = IosTheme.type.subheadline,
                        fontWeight = FontWeight.SemiBold,
                        color = IosTheme.colors.secondaryLabel,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosTheme.colors.fieldBackground)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (title.isEmpty()) {
                            Text("输入版块标题，如：我的常逛", style = IosTheme.type.body, color = IosTheme.colors.tertiaryLabel)
                        }
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                            cursorBrush = SolidColor(IosTheme.colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 版块链接
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "版块链接",
                            style = IosTheme.type.subheadline,
                            fontWeight = FontWeight.SemiBold,
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${links.size} 个链接",
                            style = IosTheme.type.caption,
                            color = IosTheme.colors.tertiaryLabel,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // 链接列表
                    links.forEachIndexed { index, link ->
                        CustomSectionLinkEditorItem(
                            index = index,
                            totalCount = links.size,
                            link = link,
                            onUpdate = { updated -> links[index] = updated },
                            onMoveUp = if (index > 0) {
                                {
                                    val item = links.removeAt(index)
                                    links.add(index - 1, item)
                                }
                            } else null,
                            onMoveDown = if (index < links.lastIndex) {
                                {
                                    val item = links.removeAt(index)
                                    links.add(index + 1, item)
                                }
                            } else null,
                            onDelete = { links.removeAt(index) },
                            onSelectCategory = { pickingCategoryIndex = index },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // + 添加另一个链接
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, IosTheme.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .clickable {
                                links.add(CustomSectionLink(icon = "🔗", name = "", url = ""))
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "＋ 添加另一个链接",
                            style = IosTheme.type.footnote,
                            fontWeight = FontWeight.SemiBold,
                            color = IosTheme.colors.accent,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.5.dp, color = IosTheme.colors.separator.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (initialSection != null && onDelete != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(IosTheme.colors.destructive.copy(alpha = 0.12f))
                                .clickable(onClick = onDelete)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "删除版块",
                                style = IosTheme.type.footnote,
                                fontWeight = FontWeight.SemiBold,
                                color = IosTheme.colors.destructive,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosTheme.colors.accent)
                            .clickable {
                                val validLinks = links.filter { it.name.isNotBlank() || it.url.isNotBlank() }
                                onSave(
                                    CustomSection(
                                        id = initialSection?.id ?: "custom_section_default",
                                        title = title.ifBlank { "自定义版块" },
                                        links = validLinks,
                                    ),
                                )
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "保 存",
                            style = IosTheme.type.body,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }

    // 分类选择子弹窗
    pickingCategoryIndex?.let { targetIdx ->
        CategoryPickerSubDialog(
            categories = categories,
            onSelect = { cat ->
                val current = links.getOrNull(targetIdx)
                if (current != null) {
                    val url = if (cat.slug.isNotBlank()) "/c/${cat.slug}/${cat.id}" else "/c/${cat.id}"
                    links[targetIdx] = current.copy(
                        name = if (current.name.isBlank()) cat.name else current.name,
                        url = url,
                    )
                }
                pickingCategoryIndex = null
            },
            onDismiss = { pickingCategoryIndex = null },
        )
    }
}

@Composable
private fun CustomSectionLinkEditorItem(
    index: Int,
    totalCount: Int,
    link: CustomSectionLink,
    onUpdate: (CustomSectionLink) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
    onSelectCategory: () -> Unit,
) {
    var showIconPicker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(IosTheme.colors.fieldBackground.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        // 第一行：[序号与移位] + [图标] + [名称输入框] + [删除]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${index + 1}",
                    style = IosTheme.type.caption.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold,
                    color = IosTheme.colors.tertiaryLabel,
                    modifier = Modifier.padding(end = 4.dp),
                )
                if (onMoveUp != null) {
                    Text(
                        text = "▲",
                        fontSize = 10.sp,
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable(onClick = onMoveUp)
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                    )
                }
                if (onMoveDown != null) {
                    Text(
                        text = "▼",
                        fontSize = 10.sp,
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable(onClick = onMoveDown)
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // 图标选择器
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(IosTheme.colors.card)
                        .clickable { showIconPicker = true }
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = link.icon.ifBlank { "🔗" },
                        fontSize = 16.sp,
                    )
                    Text(
                        text = " ▾",
                        style = IosTheme.type.caption.copy(fontSize = 11.sp),
                        color = IosTheme.colors.secondaryLabel,
                    )
                }

                if (showIconPicker) {
                    IconPickerPopup(
                        currentIcon = link.icon,
                        onSelect = {
                            onUpdate(link.copy(icon = it))
                            showIconPicker = false
                        },
                        onDismiss = { showIconPicker = false },
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // 名称输入框
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosTheme.colors.card)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (link.name.isEmpty()) {
                    Text("名称，如：开发调优", style = IosTheme.type.footnote, color = IosTheme.colors.tertiaryLabel)
                }
                BasicTextField(
                    value = link.name,
                    onValueChange = { onUpdate(link.copy(name = it)) },
                    textStyle = IosTheme.type.footnote.copy(color = IosTheme.colors.label),
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Spacer(Modifier.width(6.dp))

            // 删除按钮 🗑️
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text("🗑️", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 第二行：[链接输入框] + [选分类] + [粘贴]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosTheme.colors.card)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (link.url.isEmpty()) {
                    Text("链接，如：/c/dev 或 /t/123", style = IosTheme.type.caption.copy(fontSize = 12.sp), color = IosTheme.colors.tertiaryLabel)
                }
                BasicTextField(
                    value = link.url,
                    onValueChange = { onUpdate(link.copy(url = it)) },
                    textStyle = IosTheme.type.caption.copy(fontSize = 12.sp, color = IosTheme.colors.label),
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Spacer(Modifier.width(6.dp))

            // 从分类选取
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(IosTheme.colors.accent.copy(alpha = 0.12f))
                    .clickable(onClick = onSelectCategory)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                Text("选分类", style = IosTheme.type.caption.copy(fontSize = 11.sp), fontWeight = FontWeight.SemiBold, color = IosTheme.colors.accent)
            }

            Spacer(Modifier.width(4.dp))

            // 粘贴剪贴板
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(IosTheme.colors.card)
                    .clickable {
                        val clip = clipboard.getText()?.text.orEmpty().trim()
                        if (clip.isNotBlank()) {
                            onUpdate(link.copy(url = clip))
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                Text("粘贴", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.secondaryLabel)
            }
        }
    }
}

private val PRESET_CUSTOM_ICONS = listOf(
    "🔗", "💻", "💬", "🎁", "📌", "⭐", "📖", "🔥",
    "💡", "🚀", "🛡️", "🌐", "📢", "🛠️", "📝", "☕",
    "🎮", "🤖", "⚡", "❤️", "🎯", "💎", "🏆", "🌟",
)

@Composable
private fun IconPickerPopup(
    currentIcon: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customInput by remember { mutableStateOf("") }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(230.dp)
                .padding(8.dp),
        ) {
            Text(
                text = "选择图标",
                style = IosTheme.type.footnote,
                fontWeight = FontWeight.SemiBold,
                color = IosTheme.colors.label,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val rows = PRESET_CUSTOM_ICONS.chunked(6)
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    row.forEach { icon ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (icon == currentIcon) IosTheme.colors.accent.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { onSelect(icon) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(icon, fontSize = 16.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(IosTheme.colors.fieldBackground)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    if (customInput.isEmpty()) {
                        Text("自定义Emoji", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.tertiaryLabel)
                    }
                    BasicTextField(
                        value = customInput,
                        onValueChange = { customInput = it.take(2) },
                        textStyle = IosTheme.type.caption.copy(color = IosTheme.colors.label),
                        cursorBrush = SolidColor(IosTheme.colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (customInput.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "确定",
                        style = IosTheme.type.caption.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.accent,
                        modifier = Modifier
                            .clickable { onSelect(customInput.trim()) }
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerSubDialog(
    categories: List<Category>,
    onSelect: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    val flattened = remember(categories) { flattenCategories(categories).distinctBy { it.id } }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(IosTheme.colors.card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选择要添加的分类",
                        style = IosTheme.type.headline,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        style = IosTheme.type.body,
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    flattened.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(cat) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val color = runCatching {
                                cat.color?.takeIf { it.isNotBlank() }?.let { Color(android.graphics.Color.parseColor("#$it")) }
                            }.getOrNull() ?: IosTheme.colors.accent
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = cat.name,
                                style = IosTheme.type.body,
                                color = IosTheme.colors.label,
                                modifier = Modifier.weight(1f),
                            )
                            if (cat.topicCount > 0) {
                                Text(
                                    text = "${cat.topicCount} 帖",
                                    style = IosTheme.type.caption,
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 勋章徽章流 (Badge Chip with Arrow)：
 * - Lv.1：浅灰蓝底 + 深蓝字（活力、基础）
 * - Lv.2：浅黄/琥珀底 + 深褐金字（进阶）
 * - Lv.3：淡紫/淡金流光渐变底 + 紫金鎏金字 + 皇家金冠与星芒 + 动态流光扫光（高阶/核心大佬专属）
 */
@Composable
private fun LevelBadgeChip(
    level: Int,
    onClick: () -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    val shape = RoundedCornerShape(8.dp)
    val label = "Lv.$level"

    when {
        level <= 0 -> {
            val bgBrush = if (isDark) {
                Brush.horizontalGradient(listOf(Color(0xFF242528), Color(0xFF2C2D32)))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFFF2F3F5), Color(0xFFE9EBEF)))
            }
            val borderColor = if (isDark) Color(0xFF3E4046) else Color(0xFFD6D9E0)
            val contentColor = if (isDark) Color(0xFFA0A3A8) else Color(0xFF6B7280)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bgBrush)
                    .border(0.8.dp, borderColor, shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = IosTheme.type.caption.copy(fontSize = 11.5.sp),
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                )
                BadgeArrowIcon(color = contentColor)
            }
        }

        level == 1 -> {
            // ── Lv.1 清新天蓝 ──
            val bgBrush = if (isDark) {
                Brush.horizontalGradient(listOf(Color(0xFF0D2538), Color(0xFF13334C)))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFFEBF5FF), Color(0xFFDCEEFE)))
            }
            val borderColor = if (isDark) Color(0xFF1A5276) else Color(0xFF90CDF4)
            val contentColor = if (isDark) Color(0xFF63B3ED) else Color(0xFF096DD9)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bgBrush)
                    .border(0.8.dp, borderColor, shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = IosTheme.type.caption.copy(fontSize = 11.5.sp),
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                )
                BadgeArrowIcon(color = contentColor)
            }
        }

        level == 2 -> {
            // ── Lv.2 浅黄/温润琥珀底 + 铜金字（进阶，告别绿色） ──
            val bgBrush = if (isDark) {
                Brush.horizontalGradient(listOf(Color(0xFF33230A), Color(0xFF47310E)))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFFFFF6DE), Color(0xFFFFE7B0)))
            }
            val borderColor = if (isDark) Color(0xFF8F651A) else Color(0xFFEFC163)
            val contentColor = if (isDark) Color(0xFFFFD166) else Color(0xFF824B05)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bgBrush)
                    .border(0.8.dp, borderColor, shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = IosTheme.type.caption.copy(fontSize = 11.5.sp),
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                )
                BadgeArrowIcon(color = contentColor)
            }
        }

        else -> {
            // ── Lv.3 大佬专属：深鎏金金属底 + 亮金流光描边 + 纯金皇冠星芒 + 动态流光扫光 ──
            // 刻意走"深底亮字"：Lv.2 和 Lv.3 同在黄色系，只差深浅的话这么小的徽章上根本分不开，
            // 明暗反转才一眼可辨，顺带让最高等级有块金牌的分量。
            val transition = rememberInfiniteTransition(label = "lv3-shimmer")
            val shimmerProgress by transition.animateFloat(
                initialValue = -0.6f,
                targetValue = 1.6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "lv3-sweep",
            )
            // 浅色模式同样用深底：白底卡片上这块深金牌才是全屏最扎眼的那一个。
            val bgBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF3E2B08),
                    Color(0xFF6B4A12),
                    Color(0xFF4A3410),
                ),
            )
            val borderBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFE9A8),
                    Color(0xFFD4AF37),
                    Color(0xFFFFD76A),
                ),
            )
            val textColor = Color(0xFFFFD873)
            val crownGold = Color(0xFFFFE9A8)
            val crownShade = Color(0xFFC9971F)
            val shimmerColor = Color(0x66FFF3C4)

            Row(
                modifier = Modifier
                    .shadow(
                        elevation = if (isDark) 4.dp else 2.dp,
                        shape = shape,
                        ambientColor = Color(0xFF6B4A12),
                        spotColor = Color(0xFFD4AF37),
                    )
                    .clip(shape)
                    .background(bgBrush)
                    .border(1.25.dp, borderBrush, shape)
                    .drawWithContent {
                        drawContent()
                        val bandWidth = size.width * 0.48f
                        val startX = shimmerProgress * size.width
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    shimmerColor,
                                    Color.Transparent,
                                ),
                                start = Offset(startX, 0f),
                                end = Offset(startX + bandWidth, size.height),
                            ),
                        )
                    }
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.5.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            ) {
                Lv3CrownEmblem(gold = crownGold, shade = crownShade)
                Text(
                    text = label,
                    style = IosTheme.type.caption.copy(fontSize = 12.sp, letterSpacing = 0.2.sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = textColor,
                )
                BadgeArrowIcon(color = textColor, bold = true)
            }
        }
    }
}

/** Lv.3 专属纯金三尖皇冠 + 星芒徽记。 */
@Composable
private fun Lv3CrownEmblem(gold: Color, shade: Color) {
    Canvas(Modifier.size(13.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            // 皇冠主体
            val crown = Path().apply {
                moveTo(3f, 18f)
                lineTo(2f, 8f)
                lineTo(7.5f, 12.5f)
                lineTo(12f, 5f)
                lineTo(16.5f, 12.5f)
                lineTo(22f, 8f)
                lineTo(21f, 18f)
                close()
            }
            drawPath(
                path = crown,
                brush = Brush.verticalGradient(listOf(gold, shade)),
            )
            // 皇冠底座金边
            drawLine(
                color = gold,
                start = Offset(3f, 20.5f),
                end = Offset(21f, 20.5f),
                strokeWidth = 2.2f,
                cap = StrokeCap.Round,
            )
            // 皇冠三颗尖顶明珠
            drawCircle(color = gold, radius = 1.6f, center = Offset(2f, 6.2f))
            drawCircle(color = gold, radius = 2.0f, center = Offset(12f, 3.2f))
            drawCircle(color = gold, radius = 1.6f, center = Offset(22f, 6.2f))
        }
    }
}

/** 勋章右侧紧凑箭头 `→`。 */
@Composable
private fun BadgeArrowIcon(color: Color, bold: Boolean = false) {
    val strokeWidth = if (bold) 2.5f else 2.1f
    Canvas(Modifier.size(11.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawLine(
                color = color,
                start = Offset(4f, 12f),
                end = Offset(19f, 12f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            val head = Path().apply {
                moveTo(13f, 6.5f)
                lineTo(19.5f, 12f)
                lineTo(13f, 17.5f)
            }
            drawPath(
                path = head,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** 搜索放大镜图标 */
@Composable
private fun SearchLoupeIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawCircle(tint, radius = 6.5f, center = Offset(10.5f, 10.5f), style = stroke)
            drawLine(tint, Offset(15.5f, 15.5f), Offset(21f, 21f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TagCard(
    tag: org.linuxdo.android.data.DiscourseTag,
    onClick: () -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IosTheme.colors.card)
            .border(
                0.5.dp,
                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#",
            style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            color = IosTheme.colors.accent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = tag.displayName,
            style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
            color = IosTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val displayCount = tag.effectiveCount
        Spacer(Modifier.width(6.dp))
        val countStr = when {
            displayCount >= 10000 -> String.format(java.util.Locale.US, "%.1fw", displayCount / 10000.0)
            displayCount >= 1000 -> String.format(java.util.Locale.US, "%.1fk", displayCount / 1000.0)
            else -> "$displayCount"
        }
        Text(
            text = countStr,
            style = IosTheme.type.caption.copy(fontSize = 11.5.sp),
            color = IosTheme.colors.secondaryLabel,
        )
    }
}


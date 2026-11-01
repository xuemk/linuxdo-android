package org.linuxdo.android.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.linuxdo.android.data.Topic
import org.linuxdo.android.data.TopicListItem
import org.linuxdo.android.data.SearchOrder
import org.linuxdo.android.ui.SearchUiState
import org.linuxdo.android.ui.design.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenTopic: (Topic) -> Unit,
    onOrder: (SearchOrder) -> Unit,
    active: Boolean = true,
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
    searchHistory: List<String> = emptyList(),
    onSelectHistory: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    val list = rememberLazyListState()
    ScrollToTopOnRequest(list, scrollToTopRequest)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val nearEnd by remember { derivedStateOf {
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(active, nearEnd, state.results) {
        if (active && nearEnd && state.results.items.isNotEmpty() && state.results.hasMore &&
            !state.results.loadingMore && !state.results.initialLoading && state.results.error == null) onLoadMore()
    }
    var previousQuery by rememberSaveable { mutableStateOf(state.query) }
    var previousOrder by rememberSaveable { mutableStateOf(state.order) }
    LaunchedEffect(state.query, state.order) {
        if (previousQuery != state.query || previousOrder != state.order) {
            previousQuery = state.query
            previousOrder = state.order
            list.scrollToItem(0)
        }
    }

    val isDark = IosTheme.colors.isDark
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

    Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
        // 顶部大标题栏
        IosNavBar(title = "搜索", trailing = trailing)

        // 搜索输入栏 (透明玻璃胶囊 + 放大镜 Icon + 右侧取消)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .shadow(
                        elevation = 2.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.04f),
                        spotColor = Color.Black.copy(alpha = 0.08f),
                    )
                    .clip(CircleShape)
                    .background(glassBg)
                    .border(1.dp, glassBorder, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchLoupeIcon(
                    tint = IosTheme.colors.secondaryLabel,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label, fontSize = 14.5.sp),
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        if (state.query.isNotBlank()) onSelectHistory(state.query.trim())
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { field ->
                        Box {
                            if (state.query.isEmpty()) {
                                Text(
                                    "输入关键词查找话题",
                                    style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }
                            field()
                        }
                    },
                )
                if (state.query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(IosTheme.colors.secondaryLabel.copy(alpha = 0.2f))
                            .clickable { onQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", fontSize = 10.sp, color = IosTheme.colors.secondaryLabel)
                    }
                }
            }
            val hasQuery = state.query.isNotBlank()
            Text(
                text = if (hasQuery) "搜索" else "取消",
                style = IosTheme.type.body.copy(fontSize = 14.5.sp, fontWeight = if (hasQuery) FontWeight.Medium else FontWeight.Normal),
                color = if (hasQuery) IosTheme.colors.label else IosTheme.colors.secondaryLabel,
                modifier = Modifier
                    .clickable {
                        if (hasQuery) {
                            onSelectHistory(state.query)
                            focusManager.clearFocus()
                        } else {
                            onQuery("")
                            focusManager.clearFocus()
                        }
                    }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
        }

        // 筛选标签栏 (排序依据 + 透明玻璃胶囊)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "排序依据",
                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                color = IosTheme.colors.secondaryLabel,
            )
            // 当前排序胶囊 (透明玻璃质感)
            Row(
                modifier = Modifier
                    .shadow(
                        elevation = 1.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.03f),
                        spotColor = Color.Black.copy(alpha = 0.06f),
                    )
                    .clip(CircleShape)
                    .background(glassBg)
                    .border(1.dp, glassBorder, CircleShape)
                    .clickable {
                        focusManager.clearFocus()
                        showSort = true
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.order.label,
                    style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Medium,
                    color = IosTheme.colors.label,
                )
                Text(
                    text = "⌄",
                    style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Medium,
                    color = IosTheme.colors.secondaryLabel,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        val showHistory = state.query.isBlank() && state.results.items.isEmpty() && !state.results.initialLoading

        if (showHistory) {
            // 搜索历史模块 (参考图 3：标题 + 垃圾桶图标 + 流式玻璃药丸标签)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (searchHistory.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "历史搜索",
                                style = IosTheme.type.body.copy(fontSize = 15.sp),
                                fontWeight = FontWeight.Bold,
                                color = IosTheme.colors.label,
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = onClearHistory),
                                contentAlignment = Alignment.Center,
                            ) {
                                SearchTrashIcon(tint = IosTheme.colors.secondaryLabel)
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            searchHistory.forEach { keyword ->
                                Box(
                                    modifier = Modifier
                                        .shadow(
                                            elevation = 1.dp,
                                            shape = CircleShape,
                                            ambientColor = Color.Black.copy(alpha = 0.03f),
                                            spotColor = Color.Black.copy(alpha = 0.06f),
                                        )
                                        .clip(CircleShape)
                                        .background(glassBg)
                                        .border(1.dp, glassBorder, CircleShape)
                                        .clickable {
                                            onQuery(keyword)
                                            onSelectHistory(keyword)
                                            focusManager.clearFocus()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = keyword,
                                        style = IosTheme.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
                                        color = IosTheme.colors.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 空状态
                    SearchHistoryEmptyView()
                }
            }
        } else {
            // 搜索结果卡片列表 (全宽扁平纯平流式排版：直抵屏幕边缘，呼吸留白 16dp，移除悬浮外边距与硬描边)
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 64.dp),
            ) {
                if (state.results.initialLoading) item("loading") { IosLoadingBox() }
                state.results.error?.let { message ->
                    item("error") { ErrorNotice(message) { onQuery(state.query) } }
                }
                if (state.query.isNotBlank() && !state.results.initialLoading && state.results.error == null && state.results.items.isEmpty()) {
                    item("empty") { EmptyNotice() }
                }
                itemsIndexed(state.results.items, key = { index, item -> "${item.topic.id}_$index" }) { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(IosTheme.colors.card),
                    ) {
                        val topicWithExcerpt = remember(item, state.excerpts[item.topic.id]) {
                            if (state.excerpts[item.topic.id] != null) {
                                item.copy(topic = item.topic.copy(excerpt = state.excerpts[item.topic.id]))
                            } else item
                        }
                        TopicRow(
                            item = topicWithExcerpt,
                            query = state.query,
                            onClick = {
                                focusManager.clearFocus()
                                onOpenTopic(item.topic)
                            },
                        )
                        if (index < state.results.items.lastIndex) {
                            IosDivider(startIndent = 16.dp)
                        }
                    }
                }
                if (state.results.loadingMore) item("more") { IosLoadingBox() }
            }
        }
    }

    // 底部“搜索排序”抽屉 (BottomSheet 样式)
    if (showSort) {
        Dialog(
            onDismissRequest = { showSort = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // 半透明背景遮罩
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showSort = false },
                        ),
                )

                // 抽屉面板
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(IosTheme.colors.card)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 顶部灰色横条把手
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(IosTheme.colors.separator),
                    )

                    Spacer(Modifier.height(12.dp))

                    // 抽屉头部标题与关闭按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "搜索排序",
                            style = IosTheme.type.title3,
                            fontWeight = FontWeight.Bold,
                            color = IosTheme.colors.label,
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(IosTheme.colors.fieldBackground)
                                .clickable { showSort = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", fontSize = 12.sp, color = IosTheme.colors.secondaryLabel)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 排序选项列表
                    SearchOrder.entries.forEach { order ->
                        val isSelected = state.order == order
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) IosTheme.colors.accent.copy(alpha = 0.12f)
                                    else Color.Transparent,
                                )
                                .clickable {
                                    onOrder(order)
                                    showSort = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchOrderIcon(order, isSelected)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = order.label,
                                style = IosTheme.type.body,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = IosTheme.colors.label,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    color = IosTheme.colors.accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                )
                            }
                        }
                        if (order != SearchOrder.entries.last()) {
                            IosDivider(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 底部取消按钮
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSort = false }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "取消",
                            style = IosTheme.type.body,
                            color = IosTheme.colors.label,
                        )
                    }
                }
            }
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

/** 排序抽屉中各选项的专属贴吧/社区风格图标 */
@Composable
private fun SearchOrderIcon(order: SearchOrder, isSelected: Boolean) {
    val tint = if (isSelected) IosTheme.colors.accent else IosTheme.colors.secondaryLabel
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                when (order) {
                    SearchOrder.Relevance -> {
                        // 爱心图标 (选中时实心蓝底微心，或心形)
                        if (isSelected) {
                            drawCircle(tint, radius = 10f, center = Offset(12f, 12f))
                            val heart = Path().apply {
                                moveTo(12f, 16.5f)
                                cubicTo(10.5f, 15f, 6f, 11f, 6f, 8f)
                                cubicTo(6f, 5.5f, 10f, 4.5f, 12f, 8f)
                                cubicTo(14f, 4.5f, 18f, 5.5f, 18f, 8f)
                                cubicTo(18f, 11f, 13.5f, 15f, 12f, 16.5f)
                                close()
                            }
                            drawPath(heart, Color.White)
                        } else {
                            val heart = Path().apply {
                                moveTo(12f, 19f)
                                cubicTo(10f, 17f, 4f, 12f, 4f, 7.5f)
                                cubicTo(4f, 4.5f, 9.5f, 3.5f, 12f, 8f)
                                cubicTo(14.5f, 3.5f, 20f, 4.5f, 20f, 7.5f)
                                cubicTo(20f, 12f, 14f, 17f, 12f, 19f)
                                close()
                            }
                            drawPath(heart, tint, style = stroke)
                        }
                    }
                    SearchOrder.Latest -> {
                        // 时钟图标 🕒
                        drawCircle(tint, radius = 9f, center = Offset(12f, 12f), style = stroke)
                        drawLine(tint, Offset(12f, 12f), Offset(12f, 6.5f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                        drawLine(tint, Offset(12f, 12f), Offset(16.5f, 12f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                    }
                    SearchOrder.Likes -> {
                        // 点赞大拇指 👍
                        val thumb = Path().apply {
                            moveTo(7f, 19f); lineTo(3f, 19f); lineTo(3f, 10f); lineTo(7f, 10f)
                            moveTo(7f, 11f); lineTo(10.5f, 4f); cubicTo(12f, 4f, 13f, 5.5f, 12.5f, 7.5f)
                            lineTo(11.5f, 10f); lineTo(18.5f, 10f); cubicTo(20f, 10f, 21f, 11.5f, 20f, 13f)
                            lineTo(18.5f, 18f); cubicTo(18f, 19f, 17f, 19f, 16f, 19f); lineTo(7f, 19f)
                        }
                        drawPath(thumb, tint, style = stroke)
                    }
                    SearchOrder.Views -> {
                        // 眼睛浏览 👁
                        val eye = Path().apply {
                            moveTo(3f, 12f)
                            cubicTo(6.5f, 6.5f, 17.5f, 6.5f, 21f, 12f)
                            cubicTo(17.5f, 17.5f, 6.5f, 17.5f, 3f, 12f)
                            close()
                        }
                        drawPath(eye, tint, style = stroke)
                        drawCircle(tint, radius = 2.8f, center = Offset(12f, 12f))
                    }
                    SearchOrder.LatestTopic -> {
                        // 对话气泡 💬
                        val bubble = Path().apply {
                            moveTo(4f, 6f)
                            lineTo(20f, 6f)
                            cubicTo(21f, 6f, 21.5f, 7f, 21.5f, 8f)
                            lineTo(21.5f, 15f)
                            cubicTo(21.5f, 16f, 21f, 17f, 20f, 17f)
                            lineTo(9f, 17f)
                            lineTo(5f, 20.5f)
                            lineTo(5f, 17f)
                            lineTo(4f, 17f)
                            cubicTo(3f, 17f, 2.5f, 16f, 2.5f, 15f)
                            lineTo(2.5f, 8f)
                            cubicTo(2.5f, 7f, 3f, 6f, 4f, 6f)
                            close()
                        }
                        drawPath(bubble, tint, style = stroke)
                    }
                    SearchOrder.Read -> {
                        // 打开的书本 📖
                        val book = Path().apply {
                            moveTo(12f, 6f)
                            cubicTo(9f, 4.5f, 4f, 5f, 3f, 6f); lineTo(3f, 18f)
                            cubicTo(4f, 17f, 9f, 16.5f, 12f, 18f)
                            cubicTo(15f, 16.5f, 20f, 17f, 21f, 18f); lineTo(21f, 6f)
                            cubicTo(20f, 5f, 15f, 4.5f, 12f, 6f)
                        }
                        drawPath(book, tint, style = stroke)
                        drawLine(tint, Offset(12f, 6f), Offset(12f, 18f), strokeWidth = 1.8f)
                    }
                }
            }
        }
    }
}

/** 搜索历史空状态优雅插图与提示文案 */
@Composable
private fun SearchHistoryEmptyView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val loupeColor = if (IosTheme.colors.isDark) Color(0xFFA0A5B0) else Color(0xFF6B7280)
        Canvas(Modifier.size(68.dp)) {
            val stroke = 2.dp.toPx()
            val tint = Color(0xFFC4C9D3)
            // 绘制圆角文档
            val docWidth = size.width * 0.52f
            val docHeight = size.height * 0.68f
            val docLeft = size.width * 0.14f
            val docTop = size.height * 0.14f
            drawRoundRect(
                color = tint.copy(alpha = 0.35f),
                topLeft = Offset(docLeft, docTop),
                size = androidx.compose.ui.geometry.Size(docWidth, docHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(stroke),
            )
            // 文档内容线
            drawLine(tint.copy(alpha = 0.5f), Offset(docLeft + 8.dp.toPx(), docTop + 14.dp.toPx()), Offset(docLeft + docWidth - 8.dp.toPx(), docTop + 14.dp.toPx()), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawLine(tint.copy(alpha = 0.5f), Offset(docLeft + 8.dp.toPx(), docTop + 24.dp.toPx()), Offset(docLeft + docWidth - 14.dp.toPx(), docTop + 24.dp.toPx()), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            // 放大镜
            val loupeCenter = Offset(size.width * 0.72f, size.height * 0.68f)
            val loupeRadius = size.width * 0.19f
            drawCircle(
                color = loupeColor,
                radius = loupeRadius,
                center = loupeCenter,
                style = Stroke(stroke * 1.2f),
            )
            drawLine(
                color = loupeColor,
                start = Offset(loupeCenter.x + loupeRadius * 0.7f, loupeCenter.y + loupeRadius * 0.7f),
                end = Offset(size.width * 0.94f, size.height * 0.94f),
                strokeWidth = stroke * 1.4f,
                cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "暂无搜索历史",
            style = IosTheme.type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = IosTheme.colors.label,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "输入关键词搜索感兴趣的话题、技术或用户",
            style = IosTheme.type.footnote,
            color = IosTheme.colors.secondaryLabel,
        )
    }
}

/** 历史搜索区域轻盈精细的垃圾桶图标 (对齐参考图 3) */
@Composable
private fun SearchTrashIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 桶盖横线
        drawLine(tint, Offset(2.5f, 4f), Offset(12.5f, 4f), strokeWidth = 1.4f, cap = StrokeCap.Round)
        // 桶盖手柄
        val handle = Path().apply {
            moveTo(5.5f, 4f)
            lineTo(5.5f, 2.2f)
            lineTo(9.5f, 2.2f)
            lineTo(9.5f, 4f)
        }
        drawPath(handle, tint, style = stroke)
        // 桶身
        val body = Path().apply {
            moveTo(3.8f, 4.2f)
            lineTo(4.4f, 12.8f)
            cubicTo(4.4f, 13.6f, 5f, 14.2f, 5.8f, 14.2f)
            lineTo(9.2f, 14.2f)
            cubicTo(10f, 14.2f, 10.6f, 13.6f, 10.6f, 12.8f)
            lineTo(11.2f, 4.2f)
        }
        drawPath(body, tint, style = stroke)
        // 桶身竖条纹
        drawLine(tint, Offset(6.2f, 6.5f), Offset(6.2f, 11.5f), strokeWidth = 1.2f, cap = StrokeCap.Round)
        drawLine(tint, Offset(8.8f, 6.5f), Offset(8.8f, 11.5f), strokeWidth = 1.2f, cap = StrokeCap.Round)
    }
}


package org.linuxdo.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.min
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.Topic
import org.linuxdo.android.data.TopicListItem
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosLargeTitleScaffold
import org.linuxdo.android.ui.design.IosLoadingBox
import org.linuxdo.android.ui.design.IosMetrics
import org.linuxdo.android.ui.design.IosRow
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.format.RelativeTime

data class TopicListUiState(
    val items: List<TopicListItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
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
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    active: Boolean = true,
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // 距底部还有 4 项时就开始取下一页,滚到底时数据通常已经就位。
    // 必须要求列表已有真实数据:空列表时占位项也会让 lastVisible 命中阈值,
    // 配合"失败不更新 page"就会变成每秒重打同一页的死循环。
    val nearEnd by remember {
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

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
        indicator = {
            val fraction = pullState.distanceFraction
            if (state.refreshing || fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = IosMetrics.navBarHeight + 10.dp)
                        .graphicsLayer {
                            alpha = if (state.refreshing) 1f else min(1f, fraction * 1.4f)
                        },
                ) {
                    IosActivityIndicator(diameter = 22.dp)
                }
            }
        },
    ) {
        IosLargeTitleScaffold(
            title = title,
            listState = listState,
            onBack = onBack,
            compactTop = onBack == null,
            scrollToTopRequest = scrollToTopRequest,
            trailing = trailing,
        ) {
            state.error?.let { message ->
                item(key = "error") { ErrorNotice(message, onRefresh) }
            }
            if (state.initialLoading && state.items.isEmpty()) {
                item(key = "loading") { IosLoadingBox() }
            }
            if (!state.initialLoading && state.items.isEmpty() && state.error == null) {
                item(key = "empty") { EmptyNotice() }
            }

            itemsIndexed(state.items, key = { _, item -> item.topic.id }) { index, item ->
                Column(Modifier.background(IosTheme.colors.card)) {
                    TopicRow(item) { onOpenTopic(item.topic) }
                    if (index < state.items.lastIndex) {
                        IosDivider(
                            startIndent = IosMetrics.screenPadding + IosMetrics.avatarSize + 12.dp,
                        )
                    }
                }
            }

            if (state.loadingMore) {
                item(key = "load-more") { IosLoadingBox() }
            }
        }
    }
}

@Composable
internal fun TopicRow(item: TopicListItem, onClick: () -> Unit) {
    val topic = item.topic
    IosRow(onClick = onClick) {
        AsyncImage(
            model = item.avatarUrl,
            contentDescription = item.authorUsername,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(IosMetrics.avatarSize)
                .clip(CircleShape)
                .background(IosTheme.colors.fieldBackground),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = titleWithBadges(topic, IosTheme.colors.accent),
                style = IosTheme.type.body,
                color = IosTheme.colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.category?.let { CategoryTag(it) }
                Text(
                    text = buildMeta(item),
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 置顶标记内联进标题,而不是做成固定宽度的徽章 —— 360dp 宽的屏上,
 * 徽章会把标题挤到换行,内联则随文本流排版。
 */
private fun titleWithBadges(topic: Topic, accent: Color) = buildAnnotatedString {
    if (topic.pinned) {
        withStyle(
            SpanStyle(color = accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        ) {
            append("置顶")
        }
        append(" ")
    }
    append(topic.title)
}

/**
 * 只保留回复数与时间。作者名由头像表达,浏览数在列表里的信息价值低于保证这一行不被截断
 * —— 13sp 在 360dp 宽的行里放不下四段信息。
 */
private fun buildMeta(item: TopicListItem): String {
    val topic = item.topic
    return buildList {
        add("${topic.replyCount} 回复")
        add(RelativeTime.format(topic.bumpedAt ?: topic.lastPostedAt ?: topic.createdAt))
    }.joinToString(" · ")
}

@Composable
internal fun CategoryTag(category: Category, prominent: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(parseCategoryColor(category.color) ?: IosTheme.colors.tertiaryLabel),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = category.name,
            style = if (prominent) IosTheme.type.body else IosTheme.type.footnote,
            color = if (prominent) IosTheme.colors.label else IosTheme.colors.secondaryLabel,
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

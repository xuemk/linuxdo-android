package org.linuxdo.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.foundation.Canvas

/** 回顶请求由各 Tab 独立递增;保存消费位置,避免切回页面时再次回顶。 */
@Composable
internal fun ScrollToTopOnRequest(listState: LazyListState, request: Int) {
    var handled by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(request) {
        if (request > 0 && request != handled) {
            listState.scrollToItem(0)
            handled = request
        }
    }
}

/**
 * iOS 大标题导航栏。
 *
 * 行为照 UIKit:大标题是列表内容的一部分,随滚动一起上移;导航栏固定浮在顶部,
 * 大标题滚出后小标题淡入、底部分隔线显出。所以大标题由本组件作为首个 item 插入列表,
 * 调用方只提供其余内容。
 */
@Composable
fun IosLargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onBack: (() -> Unit)? = null,
    compactTop: Boolean = false,
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    ScrollToTopOnRequest(listState, scrollToTopRequest)
    val density = LocalDensity.current
    val largeTitlePx = remember(density) { with(density) { IosMetrics.largeTitleHeight.toPx() } }
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / largeTitlePx).coerceIn(0f, 1f)
        }
    }

    Box(modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (compactTop) 0.dp else IosMetrics.navBarHeight),
        ) {
            item(key = "ios-large-title") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(IosMetrics.largeTitleHeight)
                        .padding(horizontal = IosMetrics.screenPadding),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = IosTheme.type.largeTitle,
                        color = IosTheme.colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(bottom = 6.dp),
                    )
                    if (compactTop) trailing()
                    }
                }
            }
            content()
        }

        if (!compactTop || collapse > 0.01f) IosNavBar(
            title = title,
            titleAlpha = collapse,
            showDivider = collapse > 0.01f,
            // 顶部时与大标题区同色、看起来融为一体,折叠后过渡成实色条。
            // 不能用半透明近似毛玻璃:Compose 没有模糊,透出的是清晰可读的文字,很脏。
            background = lerp(
                IosTheme.colors.groupedBackground,
                IosTheme.colors.card,
                collapse,
            ),
            onBack = onBack,
            trailing = trailing,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/**
 * iOS 导航栏。44dp 高、标题居中、返回键是"箭头 + 上一页标题"的形态。
 * 背景必须不透明:Compose 没有原生毛玻璃,半透明会把滚动内容清晰地透出来。
 */
@Composable
fun IosNavBar(
    title: String,
    modifier: Modifier = Modifier,
    titleAlpha: Float = 1f,
    showDivider: Boolean = true,
    backLabel: String = "返回",
    background: Color = IosTheme.colors.card,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = IosMetrics.navBarHeight)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = IosTheme.colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(titleAlpha)
                    .padding(start = if (onBack != null) 72.dp else 56.dp, end = 56.dp),
            )
            if (onBack != null) {
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .clickable(onClick = onBack)
                        .heightIn(min = IosMetrics.navBarHeight)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IosBackChevron()
                    Text(
                        text = backLabel,
                        style = IosTheme.type.subheadline,
                        color = IosTheme.colors.accent,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
        if (showDivider) IosFullDivider()
    }
}

/** 导航栏返回箭头:比行尾 chevron 更粗、朝左。 */
@Composable
private fun IosBackChevron() {
    val color = IosTheme.colors.accent
    Canvas(Modifier.size(width = 11.dp, height = 19.dp)) {
        val stroke = size.width / 4.5f
        val midY = size.height / 2f
        drawLine(color, Offset(size.width, 0f), Offset(0f, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(0f, midY), Offset(size.width, size.height), stroke, StrokeCap.Round)
    }
}

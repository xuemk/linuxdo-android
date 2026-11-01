package org.linuxdo.android.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
    showLargeTitle: Boolean = true,
    barsVisible: Boolean = true,
    bottomPadding: Dp = 64.dp,
    backLabel: String = "返回",
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
    customLargeTitle: (@Composable () -> Unit)? = null,
    customCollapsedTitle: (@Composable () -> Unit)? = null,
    floatingOverlay: (@Composable BoxScope.() -> Unit)? = null,
    pageOffsetXProvider: (() -> Float)? = null,
    secondaryListState: LazyListState? = null,
    secondaryPageOffsetXProvider: (() -> Float)? = null,
    secondaryContent: (LazyListScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    ScrollToTopOnRequest(listState, scrollToTopRequest)
    val density = LocalDensity.current
    val titleBoxHeight = if (compactTop) IosMetrics.largeTitleHeight else 34.dp
    val largeTitlePx = remember(density, titleBoxHeight) { with(density) { titleBoxHeight.toPx() } }
    val collapse by remember(listState, largeTitlePx, showLargeTitle) {
        derivedStateOf {
            if (!showLargeTitle) 1f
            else if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / largeTitlePx).coerceIn(0f, 1f)
        }
    }

    val topPadding = if (showLargeTitle) {
        if (compactTop) 0.dp else IosMetrics.navBarHeight
    } else {
        IosMetrics.navBarHeight
    }

    Box(modifier.fillMaxSize().clipToBounds().background(IosTheme.colors.groupedBackground)) {
        if (secondaryContent != null && secondaryListState != null && secondaryPageOffsetXProvider != null) {
            LazyColumn(
                state = secondaryListState,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = secondaryPageOffsetXProvider()
                    },
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            ) {
                if (showLargeTitle) {
                    item(key = "ios-large-title-secondary-spacer") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(titleBoxHeight),
                        )
                    }
                }
                secondaryContent()
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (pageOffsetXProvider != null) {
                        Modifier.graphicsLayer {
                            translationX = pageOffsetXProvider()
                        }
                    } else {
                        Modifier
                    }
                ),
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        ) {
            if (showLargeTitle) {
                item(key = "ios-large-title") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(titleBoxHeight)
                            .then(
                                if (pageOffsetXProvider != null) {
                                    Modifier.graphicsLayer {
                                        translationX = -pageOffsetXProvider()
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = IosMetrics.screenPadding),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (customLargeTitle != null) {
                            customLargeTitle()
                        } else {
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
                }
            }
            content()
        }

        val showNavBar = (!compactTop || collapse > 0.01f || !showLargeTitle)
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 20
        AnimatedVisibility(
            visible = showNavBar && (barsVisible || atTop),
            enter = slideInVertically(animationSpec = tween(220)) { -it } + fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(animationSpec = tween(220)) { -it } + fadeOut(animationSpec = tween(180)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            IosNavBar(
                title = title,
                titleAlpha = if (!showLargeTitle) 1f else collapse,
                showDivider = if (!showLargeTitle) true else collapse > 0.01f,
                backLabel = backLabel,
                // 顶部时与大标题区同色、看起来融为一体,折叠后过渡成实色条。
                background = if (!showLargeTitle) IosTheme.colors.card else lerp(
                    IosTheme.colors.groupedBackground,
                    IosTheme.colors.card,
                    collapse,
                ),
                onBack = onBack,
                trailing = trailing,
                customTitle = customCollapsedTitle,
            )
        }

        floatingOverlay?.invoke(this)
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
    customTitle: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().background(background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = IosMetrics.navBarHeight)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (customTitle != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .alpha(titleAlpha)
                        .padding(
                            start = if (onBack != null) (if (backLabel.isBlank()) 56.dp else 72.dp) else 48.dp,
                            end = if (onBack != null) 56.dp else 48.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    customTitle()
                }
            } else {
                Text(
                    text = title,
                    style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = IosTheme.colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(titleAlpha)
                        .padding(start = if (onBack != null) (if (backLabel.isBlank()) 56.dp else 72.dp) else 56.dp, end = 56.dp),
                )
            }
            if (onBack != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .heightIn(min = IosMetrics.navBarHeight)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IosBackChevron()
                        if (backLabel.isNotBlank()) {
                            Text(
                                text = backLabel,
                                style = IosTheme.type.subheadline,
                                color = IosTheme.colors.accent,
                                maxLines = 1,
                            )
                        }
                    }
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

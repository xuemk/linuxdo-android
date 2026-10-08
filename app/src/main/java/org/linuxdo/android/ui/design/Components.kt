package org.linuxdo.android.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) Color(0xFF34C759) else IosTheme.colors.fieldBackground, label = "switch-color")
    val thumbOffset by animateDpAsState(if (checked) 22.dp else 2.dp, label = "switch-position")
    Box(modifier.size(width = 51.dp, height = 44.dp).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track)) {
            Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(thumbOffset.roundToPx(), 0) }.size(27.dp)
                .shadow(1.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

/**
 * iOS 列表分隔线,默认左内缩不通栏。
 *
 * 厚度按 1 物理像素算而不是写死 0.33dp:后者在部分密度下会被舍入成 0,线直接消失。
 */
@Composable
fun IosDivider(startIndent: Dp = IosMetrics.screenPadding, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(hairlineThickness())
            .padding(start = startIndent)
            .background(IosTheme.colors.separator),
    )
}

/** 通栏分隔线,用于导航栏与 TabBar 的边界。 */
@Composable
fun IosFullDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(hairlineThickness())
            .background(IosTheme.colors.separator),
    )
}

@Composable
private fun hairlineThickness(): Dp = with(LocalDensity.current) { 1f.toDp() }

/**
 * iOS 分组卡片:圆角 10dp、左右留 16dp 边距、卡片底色与分组背景区分。
 * 行之间的分隔线由调用方用 [IosDivider] 插入,最后一行之后不画线。
 */
@Composable
fun IosGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = IosMetrics.screenPadding)
            .clip(RoundedCornerShape(IosMetrics.cardCorner))
            .background(IosTheme.colors.card),
        content = content,
    )
}

/**
 * iOS 列表行。自身不画背景 —— 按压高亮由主题的 indication 绘制在内容之下,
 * 底色由父层卡片或页面背景提供,这样按下时整行变灰而文字不被染色。
 */
@Composable
fun IosRow(
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = IosMetrics.rowMinHeight)
            .padding(horizontal = IosMetrics.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** iOS 的行尾箭头。自己画而不用 Material 图标,因为 SF Symbols 的箭头更细更尖。 */
@Composable
fun IosChevron(modifier: Modifier = Modifier, color: Color = IosTheme.colors.tertiaryLabel) {
    Canvas(modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = size.width / 4f
        val midY = size.height / 2f
        drawLine(color, Offset(0f, 0f), Offset(size.width, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, midY), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}

/**
 * iOS 的转圈指示器:8 条辐射线段按相位渐暗旋转。
 * 不用 Material 的 CircularProgressIndicator —— 那是一条绕圈的弧线,形态完全不同。
 */
@Composable
fun IosActivityIndicator(
    modifier: Modifier = Modifier,
    diameter: Dp = 20.dp,
    color: Color = IosTheme.colors.tertiaryLabel,
) {
    val segments = 8
    val phase by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = segments.toFloat(),
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier.size(diameter)) {
        val stroke = size.minDimension / 9f
        val outer = size.minDimension / 2f - stroke / 2f
        val inner = outer * 0.5f
        val center = Offset(size.width / 2f, size.height / 2f)
        val head = floor(phase).toInt()
        repeat(segments) { index ->
            val radians = (index * 360.0 / segments - 90.0) * Math.PI / 180.0
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            // 距离当前头部越远越淡,形成 iOS 那种拖尾。
            val distance = (index - head + segments) % segments
            val alpha = 0.15f + 0.85f * (1f - distance.toFloat() / segments)
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 居中的加载占位,用于整页首次加载。 */
@Composable
fun IosLoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        IosActivityIndicator(diameter = 24.dp)
    }
}

package org.linuxdo.android.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.linuxdo.android.ui.design.IosFullDivider
import org.linuxdo.android.ui.design.IosTheme

enum class MainTab(val label: String, val root: Route) {
    Latest("首页", Route.TopicList),
    Categories("分类", Route.CategoryList),
    Search("发现", Route.Search),
    Messages("消息", Route.Notifications),
    Profile("我的", Route.Profile),
}

@Composable
fun TabBar(
    selected: MainTab,
    homeFeedLabel: String = "首页",
    categoriesTabLabel: String = "分类",
    unreadCount: Int = 0,
    onSelect: (MainTab) -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    // 严格按照微博：亮色下激活态纯黑，未激活中灰；暗色下激活态纯白，未激活深灰；绝不使用橙色
    val activeTint = if (isDark) Color(0xFFFFFFFF) else Color(0xFF181818)
    val inactiveTint = if (isDark) Color(0xFF8A8A8A) else Color(0xFF767676)

    // 微博/iOS 风格半透明毛玻璃底栏：82%~85% 半透明主题卡片色 + 顶部极细微透描边，通透轻盈
    val barBackground = IosTheme.colors.card.copy(alpha = if (isDark) 0.82f else 0.85f)

    Column(
        Modifier
            .fillMaxWidth()
            .background(barBackground)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(IosTheme.colors.separator.copy(alpha = 0.32f))
        )
        Row(Modifier.height(52.dp)) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val color = if (isSelected) activeTint else inactiveTint
                val label = if (tab == MainTab.Latest) homeFeedLabel else if (tab == MainTab.Categories) categoriesTabLabel else tab.label
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(tab) }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Canvas(Modifier.size(24.dp)) {
                            val stroke = if (isSelected) 2.2.dp.toPx() else 1.6.dp.toPx()
                            when (tab) {
                                MainTab.Latest -> {
                                    // 微博式小房子图标：激活态实心，未激活态细线条
                                    val roofPath = Path().apply {
                                        moveTo(size.width * 0.5f, size.height * 0.12f)
                                        lineTo(size.width * 0.90f, size.height * 0.46f)
                                        lineTo(size.width * 0.80f, size.height * 0.46f)
                                        lineTo(size.width * 0.80f, size.height * 0.88f)
                                        lineTo(size.width * 0.20f, size.height * 0.88f)
                                        lineTo(size.width * 0.20f, size.height * 0.46f)
                                        lineTo(size.width * 0.10f, size.height * 0.46f)
                                        close()
                                    }
                                    if (isSelected) {
                                        drawPath(roofPath, color)
                                        // 门洞反白
                                        val doorLeft = size.width * 0.39f
                                        val doorTop = size.height * 0.55f
                                        val doorWidth = size.width * 0.22f
                                        val doorHeight = size.height * 0.33f
                                        drawRect(
                                            if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
                                            Offset(doorLeft, doorTop),
                                            Size(doorWidth, doorHeight)
                                        )
                                    } else {
                                        drawPath(roofPath, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                        // 线条小门
                                        drawLine(color, Offset(size.width * 0.38f, size.height * 0.88f), Offset(size.width * 0.38f, size.height * 0.60f), stroke, StrokeCap.Round)
                                        drawLine(color, Offset(size.width * 0.38f, size.height * 0.60f), Offset(size.width * 0.62f, size.height * 0.60f), stroke, StrokeCap.Round)
                                        drawLine(color, Offset(size.width * 0.62f, size.height * 0.60f), Offset(size.width * 0.62f, size.height * 0.88f), stroke, StrokeCap.Round)
                                    }
                                }
                                MainTab.Categories -> {
                                    if (categoriesTabLabel == "标签") {
                                        val tagPath = Path().apply {
                                            moveTo(size.width * 0.18f, size.height * 0.22f)
                                            lineTo(size.width * 0.58f, size.height * 0.22f)
                                            lineTo(size.width * 0.88f, size.height * 0.52f)
                                            lineTo(size.width * 0.58f, size.height * 0.82f)
                                            lineTo(size.width * 0.18f, size.height * 0.82f)
                                            close()
                                        }
                                        if (isSelected) {
                                            drawPath(tagPath, color)
                                            val contrastColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
                                            drawCircle(contrastColor, size.width * 0.08f, Offset(size.width * 0.36f, size.height * 0.52f))
                                        } else {
                                            drawPath(tagPath, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                            drawCircle(color, size.width * 0.07f, Offset(size.width * 0.36f, size.height * 0.52f), style = Stroke(stroke))
                                        }
                                    } else {
                                        repeat(4) { index ->
                                            val x = size.width * (0.10f + index % 2 * 0.50f)
                                            val y = size.height * (0.10f + index / 2 * 0.50f)
                                            val rectSize = Size(size.width * 0.30f, size.height * 0.30f)
                                            if (isSelected) {
                                                drawRect(color, Offset(x, y), rectSize)
                                            } else {
                                                drawRect(color, Offset(x, y), rectSize, style = Stroke(stroke))
                                            }
                                        }
                                    }
                                }
                                MainTab.Search -> {
                                    val radius = size.width * 0.28f
                                    val center = Offset(size.width * 0.40f, size.height * 0.40f)
                                    drawCircle(color, radius, center, style = Stroke(if (isSelected) stroke * 1.25f else stroke))
                                    drawLine(
                                        color,
                                        Offset(size.width * 0.61f, size.height * 0.61f),
                                        Offset(size.width * 0.89f, size.height * 0.89f),
                                        if (isSelected) stroke * 1.35f else stroke,
                                        StrokeCap.Round
                                    )
                                }
                                MainTab.Messages -> {
                                    val envLeft = size.width * 0.08f
                                    val envRight = size.width * 0.92f
                                    val envTop = size.height * 0.22f
                                    val envBottom = size.height * 0.78f
                                    val envMidY = size.height * 0.52f
                                    val envPath = Path().apply {
                                        moveTo(envLeft, envTop)
                                        lineTo(envRight, envTop)
                                        lineTo(envRight, envBottom)
                                        lineTo(envLeft, envBottom)
                                        close()
                                    }
                                    if (isSelected) {
                                        drawPath(envPath, color)
                                        // 反白折线
                                        val contrastColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
                                        drawLine(contrastColor, Offset(envLeft + 2.dp.toPx(), envTop + 2.dp.toPx()), Offset(size.width / 2, envMidY), stroke, StrokeCap.Round)
                                        drawLine(contrastColor, Offset(envRight - 2.dp.toPx(), envTop + 2.dp.toPx()), Offset(size.width / 2, envMidY), stroke, StrokeCap.Round)
                                    } else {
                                        drawPath(envPath, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
                                        drawLine(color, Offset(envLeft, envTop), Offset(size.width / 2, envMidY), stroke, StrokeCap.Round)
                                        drawLine(color, Offset(envRight, envTop), Offset(size.width / 2, envMidY), stroke, StrokeCap.Round)
                                    }
                                }
                                MainTab.Profile -> {
                                    val headCenter = Offset(size.width / 2, size.height * 0.28f)
                                    val headRadius = size.width * 0.20f
                                    if (isSelected) {
                                        drawCircle(color, headRadius, headCenter)
                                        val bodyPath = Path().apply {
                                            moveTo(size.width * 0.12f, size.height * 0.88f)
                                            cubicTo(
                                                size.width * 0.12f, size.height * 0.56f,
                                                size.width * 0.88f, size.height * 0.56f,
                                                size.width * 0.88f, size.height * 0.88f
                                            )
                                            close()
                                        }
                                        drawPath(bodyPath, color)
                                    } else {
                                        drawCircle(color, headRadius, headCenter, style = Stroke(stroke))
                                        drawArc(
                                            color,
                                            180f,
                                            180f,
                                            false,
                                            Offset(size.width * 0.12f, size.height * 0.54f),
                                            Size(size.width * 0.76f, size.height * 0.68f),
                                            style = Stroke(stroke)
                                        )
                                    }
                                }
                            }
                        }
                        // 消息未读红点/数字提示
                        if (tab == MainTab.Messages && unreadCount > 0) {
                            val badgeText = if (unreadCount > 99) "99+" else unreadCount.toString()
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-4).dp)
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFF3B30))
                                    .padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = badgeText,
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 11.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = label,
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = 12.sp,
                    )
                }
            }
        }
    }
}

package org.linuxdo.android.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.compactCategoryGroups
import org.linuxdo.android.ui.CategoriesUiState
import org.linuxdo.android.ui.design.*

private val LEVEL_NUMBER_REGEX = Regex("Lv\\s*(\\d+)", RegexOption.IGNORE_CASE)

private fun extractLevelNumber(name: String): Int =
    LEVEL_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

@Composable
fun CategoryListScreen(
    state: CategoriesUiState,
    onRetry: () -> Unit,
    onOpen: (Category) -> Unit,
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
) {
    val groups = remember(state.items) {
        compactCategoryGroups(state.items).map { (parent, levels) ->
            parent to levels.sortedBy { extractLevelNumber(it.name) }
        }
    }
    IosLargeTitleScaffold(title = "分类", compactTop = true, scrollToTopRequest = scrollToTopRequest, trailing = trailing) {
        if (state.loading) item("loading") { IosLoadingBox() }
        state.error?.let { message -> item("error") { ErrorNotice(message, onRetry) } }
        if (state.loaded && state.items.isEmpty()) item("empty") { EmptyNotice() }
        items(groups, key = { it.first.id }) { (parent, levels) ->
            IosGroupCard(Modifier.padding(vertical = 8.dp)) {
                IosRow(onClick = { onOpen(parent) }) {
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
        level <= 1 -> {
            val bgBrush = if (isDark) {
                Brush.horizontalGradient(listOf(Color(0xFF13263E), Color(0xFF193354)))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFFEAF3FF), Color(0xFFDCEBFF)))
            }
            val borderColor = if (isDark) Color(0xFF2B548C) else Color(0xFFBBD7FF)
            val contentColor = if (isDark) Color(0xFF6EAEFF) else Color(0xFF155FD0)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bgBrush)
                    .border(0.8.dp, borderColor, shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = IosTheme.type.caption.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                BadgeArrowIcon(color = contentColor)
            }
        }

        level == 2 -> {
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
                    .border(0.9.dp, borderColor, shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = IosTheme.type.caption.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
                BadgeArrowIcon(color = contentColor)
            }
        }

        else -> {
            // ── Lv.3 大佬专属：淡紫/淡金交融底 + 紫金流光金属描边 + 鎏金皇冠星芒 + 动态流光扫光 ──
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
            val bgBrush = if (isDark) {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2B124C),
                        Color(0xFF431C6B),
                        Color(0xFF4A3214),
                    ),
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF3E6FF),
                        Color(0xFFE9D4FF),
                        Color(0xFFFDF0CA),
                    ),
                )
            }
            val borderBrush = if (isDark) {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFB56BFF),
                        Color(0xFFFFD76A),
                        Color(0xFF9D4EDD),
                    ),
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF8A2BE2),
                        Color(0xFFD99B16),
                        Color(0xFF6A1B9A),
                    ),
                )
            }
            val textColor = if (isDark) Color(0xFFFFE082) else Color(0xFF56108E)
            val crownGold = if (isDark) Color(0xFFFFD54F) else Color(0xFFD48806)
            val crownPurple = if (isDark) Color(0xFFD09CFF) else Color(0xFF7B1FA2)
            val shimmerColor = if (isDark) Color(0x55FFE57F) else Color(0x88FFFDF5)

            Row(
                modifier = Modifier
                    .shadow(
                        elevation = if (isDark) 4.dp else 2.dp,
                        shape = shape,
                        ambientColor = Color(0xFF8A2BE2),
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
                Lv3CrownEmblem(gold = crownGold, purple = crownPurple)
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

/** Lv.3 专属鎏金三尖皇冠 + 星芒徽记。 */
@Composable
private fun Lv3CrownEmblem(gold: Color, purple: Color) {
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
                brush = Brush.verticalGradient(listOf(gold, purple)),
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


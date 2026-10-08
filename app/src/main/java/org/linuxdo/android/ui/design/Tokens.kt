package org.linuxdo.android.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * iOS 系统色。取自 Apple HIG 的 System Colors 与 Semantic Colors,
 * 刻意不复用 MaterialTheme.colorScheme —— 两套语义不对齐,混用会让观感飘回安卓。
 */
data class IosColors(
    val accent: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val groupedBackground: Color,
    val card: Color,
    val pressedHighlight: Color,
    val fieldBackground: Color,
    val codeBackground: Color,
    val destructive: Color,
    val isDark: Boolean,
)

/** systemBlue,iOS 的默认强调色与链接色。 */
private val SystemBlue = Color(0xFF007AFF)
private val SystemRedLight = Color(0xFFFF3B30)
private val SystemRedDark = Color(0xFFFF453A)

val LightIosColors = IosColors(
    accent = SystemBlue,
    label = Color(0xFF000000),
    // iOS 的 secondaryLabel/tertiaryLabel 是带透明度的黑,不是实心灰。
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    groupedBackground = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    // systemGray5,行按压时的整行底色。
    pressedHighlight = Color(0xFFE5E5EA),
    fieldBackground = Color(0x1F767680),
    codeBackground = Color(0xFFF6F6F8),
    destructive = SystemRedLight,
    isDark = false,
)

val DarkIosColors = IosColors(
    accent = Color(0xFF0A84FF),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    // iOS 深色的分组背景是纯黑,卡片才是 #1C1C1E。
    groupedBackground = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    pressedHighlight = Color(0xFF2C2C2E),
    fieldBackground = Color(0x3D767680),
    codeBackground = Color(0xFF1C1C1E),
    destructive = SystemRedDark,
    isDark = true,
)

/** iOS 的标准间距与尺寸。分隔线厚度不在这里定义 —— 它按 1 物理像素算,见 IosDivider。 */
object IosMetrics {
    val screenPadding: Dp = 16.dp
    val rowMinHeight: Dp = 44.dp
    val cardCorner: Dp = 10.dp
    val tabBarHeight: Dp = 49.dp
    val navBarHeight: Dp = 44.dp
    val largeTitleHeight: Dp = 52.dp
    val avatarSize: Dp = 40.dp
}

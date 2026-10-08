package org.linuxdo.android.ui.design

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.SideEffect

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

private val LocalIosColors = staticCompositionLocalOf { LightIosColors }
private val LocalIosTypography = staticCompositionLocalOf { DefaultIosTypography }

object IosTheme {
    val colors: IosColors
        @Composable @ReadOnlyComposable get() = LocalIosColors.current

    val type: IosTypography
        @Composable @ReadOnlyComposable get() = LocalIosTypography.current
}

@Composable
fun IosTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkIosColors else LightIosColors
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.groupedBackground.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.card.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalIosColors provides colors,
        LocalIosTypography provides DefaultIosTypography,
        // 全局换掉 Material 水波纹。这是"像不像安卓"最直接的破绽,必须在主题层一次性解决,
        // 而不是指望每个可点击组件都记得传 indication = null。
        LocalIndication provides PressHighlight(colors.pressedHighlight),
        LocalContentColor provides colors.label,
        LocalTextStyle provides DefaultIosTypography.body,
        content = content,
    )
}

/**
 * iOS 的按压反馈:整行瞬时变色,无扩散动画,松手即恢复。
 * 高亮绘制在内容之下,所以文字不会被染色;行自身不画背景,由父层卡片提供底色。
 */
private data class PressHighlight(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressHighlightNode(interactionSource, color)
}

private class PressHighlightNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            var depth = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> depth++
                    is PressInteraction.Release, is PressInteraction.Cancel -> depth--
                }
                val nowPressed = depth > 0
                if (pressed != nowPressed) {
                    pressed = nowPressed
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (pressed) drawRect(color)
        drawContent()
    }
}

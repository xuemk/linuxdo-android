package org.linuxdo.android.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** iOS 转场曲线:起步快、收尾慢,和 UIKit 的 push/pop 手感接近。 */
private val IosEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private val PushSpec = tween<Float>(durationMillis = 350, easing = IosEasing)
private val PopSpec = tween<Float>(durationMillis = 320, easing = IosEasing)

/** 上一页在转场中最多左移屏宽的这个比例,iOS 约 30%。 */
private const val BelowShift = 0.3f

/** 左边缘可起手返回的宽度。 */
private val EdgeWidth = 24.dp

/** 当前页左侧的投影宽度。 */
private val ShadowWidth = 8.dp

/**
 * iOS 风格导航容器。
 *
 * progress 语义:0 = 当前页完全占据屏幕,1 = 当前页完全移出、上一页回到原位。
 * push 走 1→0,pop 与滑动返回走 0→1,两种转场共用这一个量,所以手势可以无缝打断动画。
 *
 * 注意:全局单例 WebView 不能放进这里 —— 它必须挂在导航之外的最外层,否则出栈会把它卸载,
 * Cloudflare 挑战就再也没法渲染。
 */
@Composable
fun IosNavHost(
    stack: NavStack,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    content: @Composable (Route) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val progress = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }

    // 实际参与渲染的栈。push 立刻跟上真实栈;pop 要等退出动画播完才裁剪,
    // 否则被移除的页面会在动画开始前就消失。
    var rendered by remember { mutableStateOf(stack.entries) }

    LaunchedEffect(stack.entries) {
        val target = stack.entries
        when {
            target.size > rendered.size -> {
                rendered = target
                progress.snapTo(1f)
                progress.animateTo(0f, PushSpec)
            }
            target.size < rendered.size -> {
                val dropped = rendered.drop(target.size)
                progress.animateTo(1f, PopSpec)
                rendered = target
                progress.snapTo(0f)
                dropped.forEach { holder.removeState(it.id) }
            }
            else -> rendered = target
        }
    }

    BackHandler(enabled = interactive && stack.canPop) { stack.pop() }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { if (it.width > 0) width = it.width.toFloat() }
            .pointerInput(stack.canPop, interactive) {
                if (!interactive || !stack.canPop) return@pointerInput
                val edgePx = EdgeWidth.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > edgePx) return@awaitEachGesture

                    val tracker = VelocityTracker()
                    var travelled = 0f
                    // 先等横向 slop 判定,避免抢走页面内的垂直滚动。
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        travelled += over
                        change.consume()
                    } ?: return@awaitEachGesture
                    tracker.addPointerInputChange(start)

                    horizontalDrag(start.id) { change ->
                        travelled += change.positionChange().x
                        tracker.addPointerInputChange(change)
                        change.consume()
                        val value = (travelled / width).coerceIn(0f, 1f)
                        scope.launch { progress.snapTo(value) }
                    }

                    val velocity = tracker.calculateVelocity().x
                    val shouldPop = progress.value > 0.5f || velocity > 400f
                    scope.launch {
                        // 出栈交给 stack,由上面的 LaunchedEffect 接着把动画播完,
                        // 这样手势返回和代码返回走同一条路径。
                        if (shouldPop) stack.pop() else progress.animateTo(0f, PopSpec)
                    }
                }
            },
    ) {
        // 只渲染栈顶两层。关键是用 key(entry.id) 包住每一层:
        // 没有它,一个页面从"下层槽位"变成"上层槽位"时 Compose 会当成新节点,
        // 卸载重组一次,列表滚动位置就丢了。节点结构也必须恒定(投影/遮罩靠 alpha 开关,
        // 不靠条件插入),否则内容的组合位置照样会偏移。
        val lastIndex = rendered.lastIndex
        rendered.forEachIndexed { index, entry ->
            if (index < lastIndex - 1) return@forEachIndexed
            val isTop = index == lastIndex

            key(entry.id) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = if (isTop) {
                                progress.value * width
                            } else {
                                -BelowShift * width * (1f - progress.value)
                            }
                        },
                ) {
                    // 当前页左侧的投影,画在页面之外;progress 为 0 时正好在屏幕外。
                    Box(
                        Modifier
                            .offset(x = -ShadowWidth)
                            .width(ShadowWidth)
                            .fillMaxHeight()
                            .graphicsLayer { alpha = if (isTop && lastIndex > 0) 1f else 0f }
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f)),
                                ),
                            ),
                    )

                    holder.SaveableStateProvider(entry.id) { content(entry.route) }

                    // 下层页面在转场中被压暗。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (isTop) 0f else 0.12f * (1f - progress.value)
                            }
                            .background(Color.Black),
                    )
                }
            }
        }
    }
}

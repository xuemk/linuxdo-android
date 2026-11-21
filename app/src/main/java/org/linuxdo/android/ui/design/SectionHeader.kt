package org.linuxdo.android.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
internal fun SectionHeader(
    labels: List<String>,
    selected: Int = 0,
    onSelect: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    indicatorPosition: (() -> Float)? = null,
    unread: (Int) -> Boolean = { false },
    actionWidth: Dp = 0.dp,
    actions: @Composable () -> Unit = {},
) {
    val accent = IosTheme.colors.accent
    val animatedIndex = animateFloatAsState(selected.toFloat(), tween(170), label = "section indicator")
    Box(modifier.fillMaxWidth().height(IosMetrics.largeTitleHeight)
        .background(IosTheme.colors.groupedBackground), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = maxOf(IosMetrics.screenPadding, actionWidth)),
            contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max = (80 * labels.size).dp).fillMaxWidth()
                .then(if (onSelect != null) Modifier.selectableGroup() else Modifier)
                .drawWithContent {
                    drawContent()
                    val index = indicatorPosition?.invoke() ?: animatedIndex.value
                    val center = size.width * (index + 0.5f) / labels.size
                    val w = 18.dp.toPx()
                    val h = 3.dp.toPx()
                    drawRoundRect(accent, Offset(center - w / 2, size.height - h - 3.dp.toPx()),
                        Size(w, h), CornerRadius(h / 2))
                }) {
                labels.forEachIndexed { i, label ->
                    val isSelected = i == selected
                    val hasUnread = unread(i)
                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                        .then(if (onSelect != null) Modifier.selectable(
                            selected = isSelected, interactionSource = remember { MutableInteractionSource() },
                            indication = null, role = Role.Tab, onClick = { onSelect(i) },
                        ) else Modifier.semantics { heading() })
                        .then(if (hasUnread) Modifier.semantics { stateDescription = "有未读消息" } else Modifier),
                        contentAlignment = Alignment.Center) {
                        Box {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = if (isSelected) 17.5.sp else 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) IosTheme.colors.label else IosTheme.colors.secondaryLabel)
                            if (hasUnread) Spacer(Modifier.align(Alignment.TopEnd).offset(x = 6.dp)
                                .size(5.dp).background(IosTheme.colors.destructive, CircleShape))
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd), contentAlignment = Alignment.CenterEnd) { actions() }
    }
}

@Composable
internal fun rememberTabBoundaryFeedback(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

@Composable
internal fun Modifier.boundedTabSwipe(
    selected: Int,
    count: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
): Modifier {
    val boundary = rememberTabBoundaryFeedback()
    return tabSwipeGesture(enabled = enabled, onEnd = { step ->
        if (step != 0) {
            val target = selected + step
            if (target in 0 until count) onSelect(target) else boundary()
        }
    })
}

/** Only claim a single, clearly horizontal drag. Descendant controls and system back edges have priority. */
@Composable
internal fun Modifier.tabSwipeGesture(
    enabled: Boolean,
    onStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onEnd: (Int) -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    if (!enabled) return this
    return pointerInput(Unit) {
        val edge = 24.dp.toPx()
        awaitEachGesture {
            val threshold = maxOf(48.dp.toPx(), size.width * 0.16f)
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            var pan = Offset.Zero
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.singleOrNull()
                if (change == null || change.id != down.id) {
                    if (dragging) end(0)
                    break
                }
                if (!change.pressed) {
                    if (dragging) {
                        // 快速松手时最后一段位移可能只在 UP 事件中送达。
                        val distance = change.position.x - down.position.x
                        end(when {
                            change.isConsumed -> 0
                            distance > threshold -> -1
                            distance < -threshold -> 1
                            else -> 0
                        })
                    }
                    break
                }
                if (change.isConsumed) {
                    if (dragging) end(0)
                    break
                }
                val delta = change.positionChange()
                pan += delta
                if (!dragging) {
                    if (pan.getDistance() <= viewConfiguration.touchSlop) continue
                    if (abs(pan.x) <= abs(pan.y) * 1.35f) break
                    dragging = true
                    start()
                    drag(pan.x)
                } else {
                    drag(delta.x)
                }
                change.consume()
            }
        }
    }
}

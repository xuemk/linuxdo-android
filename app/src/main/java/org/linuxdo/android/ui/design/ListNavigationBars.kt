package org.linuxdo.android.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity

// 只有当前导航条目中的列表能控制上下栏；详情、浮层和输入法各自处理导航。
internal val LocalListBars = compositionLocalOf<MutableState<Boolean>?> { null }

@Composable
internal fun Modifier.scrollListBars(list: ScrollableState, enabled: Boolean = true): Modifier {
    val bars = LocalListBars.current ?: return this
    if (!enabled) return this
    val threshold = LocalViewConfiguration.current.touchSlop
    LaunchedEffect(bars, list) {
        try {
            snapshotFlow { list.canScrollBackward }.collect { if (!it) bars.value = true }
        } finally {
            // 从长列表切到空页面时，仍可使用顶栏和底部导航。
            bars.value = true
        }
    }
    val connection = remember(bars, list, threshold) {
        object : NestedScrollConnection {
            private var distance = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val dy = consumed.y
                if (dy == 0f) {
                    if (available.y > 0f && !list.canScrollBackward) bars.value = true
                    return Offset.Zero
                }
                if (dy * distance < 0f) distance = 0f
                distance = (distance + dy).coerceIn(-threshold, threshold)
                if (!list.canScrollBackward || distance >= threshold) bars.value = true
                else if (distance <= -threshold) bars.value = false
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                distance = 0f
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection)
}

@Composable
internal fun ListNavigationHeader(content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = LocalListBars.current?.value != false,
        enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(220), shrinkTowards = Alignment.Top) + fadeOut(tween(180)),
    ) {
        Column(content = content)
    }
}

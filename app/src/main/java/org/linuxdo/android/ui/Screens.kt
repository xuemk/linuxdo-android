package org.linuxdo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.linuxdo.android.Config
import org.linuxdo.android.BuildConfig
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosGroupCard
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosLargeTitleScaffold
import org.linuxdo.android.ui.design.IosMetrics
import org.linuxdo.android.ui.design.IosRow
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.design.IosLoadingBox
import org.linuxdo.android.ui.design.IosSwitch
import org.linuxdo.android.data.ProfileSection
import org.linuxdo.android.data.NotificationFilter
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import org.linuxdo.android.ui.screen.ErrorNotice
import org.linuxdo.android.ui.screen.EmptyNotice

/** 列表攒够这么多条才允许继续自动翻页;不足则改成手动按钮。 */
private const val AUTO_LOAD_THRESHOLD = 20

@Composable
fun ProfileScreen(
    username: String?,
    states: Map<ProfileSection, ProfileUiState>,
    onLoad: (ProfileSection, Boolean, Boolean) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit = {},
    active: Boolean,
    notificationOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
    onReadNotification: (Long) -> Unit = {},
    notificationFilter: NotificationFilter = NotificationFilter.All,
    onNotificationFilter: (NotificationFilter) -> Unit = {},
    unreadFilters: Set<NotificationFilter> = emptySet(),

    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(if (notificationOnly) ProfileSection.Notifications else ProfileSection.Summary) }
    var previousSection by rememberSaveable { mutableStateOf(section) }
    val list = rememberLazyListState()
    val state = states[section] ?: ProfileUiState()
    // 通知已经在服务端按分类取回,这里不再二次过滤 —— 否则会把"分类下有但本页未命中"
    // 的情况显示成空列表。
    val visibleEntries = state.content.entries
    val nearEnd by remember { derivedStateOf {
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(section, active) {
        if (active) onLoad(section, false, false)
        if (previousSection != section) {
            previousSection = section
            list.scrollToItem(0)
        }
    }
    // 自动翻页只在已经攒够一屏时继续。条目不足一页却还有更多时改成手动按钮:
    // 低频分类(私信)每翻一页可能只命中一两条,自动翻页会变成一个永远转不完的 spinner。
    LaunchedEffect(active, nearEnd, state, notificationFilter) {
        if (active && nearEnd && state.loaded && !state.loading && state.error == null &&
            state.content.hasMore && visibleEntries.size >= AUTO_LOAD_THRESHOLD) {
            onLoad(section, false, true)
        }
    }
    var showLogoutDialog by remember { mutableStateOf(false) }

    IosLargeTitleScaffold(title = if (notificationOnly) "通知" else "我的", listState = list, onBack = onBack,
        scrollToTopRequest = scrollToTopRequest,
        trailing = { if (!notificationOnly) Row(verticalAlignment = Alignment.CenterVertically) {
            trailing(); IosTextAction("设置", onClick = onSettings)
        } }) {
      if (!notificationOnly) {
        item("identity") {
            IosGroupCard {
                IosRow {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(username ?: "尚未确认登录状态", style = IosTheme.type.title3)
                        Text("你的社区足迹", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                    }
                    // 退出登录按钮
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(IosTheme.colors.destructive.copy(alpha = 0.1f))
                            .clickable { showLogoutDialog = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "退出登录",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.destructive,
                        )
                    }
                }
            }
        }

        item("sections") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileSection.entries.forEach { option ->
                    Text(option.label, style = IosTheme.type.subheadline,
                        color = if (section == option) Color.White else IosTheme.colors.label,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (section == option) IosTheme.colors.accent else IosTheme.colors.card)
                            .clickable { section = option }.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        }
      }
        if (section == ProfileSection.Notifications) item("notification-filters") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotificationFilter.entries.forEach { filter ->
                    val selected = notificationFilter == filter
                    val color = if (selected) IosTheme.colors.accent else IosTheme.colors.secondaryLabel
                    val hasUnread = filter != NotificationFilter.All && (
                        filter in unreadFilters || visibleEntries.any { it.unread && filter.includes(it.notificationType) }
                    )
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) IosTheme.colors.accent.copy(alpha = 0.12f) else IosTheme.colors.card)
                        .clickable { onNotificationFilter(filter) }.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (hasUnread) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF3B30))
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        NotificationTabIcon(filter, color)
                        Text(filter.label, style = IosTheme.type.subheadline, color = color, modifier = Modifier.padding(start = 6.dp))
                    }
                }

            }
        }
        if (state.loading && !state.loaded) item("loading") { IosLoadingBox() }
        state.error?.let { message -> item("error") { ErrorNotice(message) { onLoad(section, true, false) } } }
        if (section == ProfileSection.Summary) {
            items(state.content.fields.chunked(2)) { fields ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    fields.forEach { (label, value) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(IosTheme.colors.card).padding(16.dp)) {
                            Text(value, style = IosTheme.type.title3, color = IosTheme.colors.accent)
                            Text(label, style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        } else if (section == ProfileSection.Account && state.content.fields.isNotEmpty()) {
            item("account") {
                IosGroupCard {
                    state.content.fields.forEachIndexed { index, (label, value) ->
                        if (index > 0) IosDivider()
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(label, style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                            SelectionContainer { Text(value, style = IosTheme.type.body, modifier = Modifier.padding(top = 5.dp)) }
                        }
                    }
                }
            }
        }
        items(visibleEntries, key = { it.id }) { entry ->
            val notification = section == ProfileSection.Notifications
            IosGroupCard(Modifier.padding(bottom = if (notification) 4.dp else 8.dp)) {
                IosRow(onClick = {
                    if (section == ProfileSection.Notifications && entry.unread) entry.id.toLongOrNull()?.let(onReadNotification)
                    entry.topicId?.let { onOpenTopic(it, entry.title, entry.postNumber) }
                }) {
                    if (entry.unread) Text("•", color = IosTheme.colors.accent, modifier = Modifier.padding(end = 6.dp))
                    if (notification) {
                        NotificationAvatarWithBadge(
                            avatarUrl = entry.avatarUrl,
                            actor = entry.actor,
                            notificationType = entry.notificationType,
                            unread = entry.unread,
                        )
                        Spacer(Modifier.width(10.dp))
                    } else {
                        entry.avatarUrl?.let { url ->
                            AsyncImage(url, entry.actor, modifier = Modifier.size(32.dp).clip(CircleShape))
                            Spacer(Modifier.width(10.dp))
                        }
                    }
                    Column(Modifier.weight(1f).padding(vertical = if (notification) 0.dp else 4.dp)) {
                        if (notification) {
                            val typeTag = notificationTypeTag(entry.notificationType)
                            val dateStr = entry.subtitle.substringAfterLast(" · ").takeLast(5)
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (!entry.actor.isNullOrBlank()) {
                                        Text(
                                            text = entry.actor,
                                            style = IosTheme.type.footnote,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(notificationTypeTint(entry.notificationType).copy(alpha = 0.13f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = typeTag,
                                            style = IosTheme.type.caption.copy(fontSize = 11.sp),
                                            fontWeight = FontWeight.Medium,
                                            color = notificationTypeTint(entry.notificationType),
                                        )
                                    }
                                }
                                if (dateStr.isNotBlank()) {
                                    Text(
                                        text = dateStr,
                                        style = IosTheme.type.caption,
                                        color = IosTheme.colors.secondaryLabel,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        } else {
                            entry.actor?.let { actor ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(actor, style = IosTheme.type.footnote, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Text(
                            text = entry.title,
                            style = if (notification) IosTheme.type.subheadline else IosTheme.type.body,
                            maxLines = if (notification) 2 else 3,
                            modifier = Modifier.padding(top = if (notification) 2.dp else 0.dp),
                        )
                        if (!notification && entry.subtitle.isNotBlank()) Text(entry.subtitle, style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        // 还有更多但不足一屏:给个明确的手动入口,而不是让列表自己转圈翻页。
        if (state.loaded && !state.loading && state.error == null && state.content.hasMore &&
            visibleEntries.size < AUTO_LOAD_THRESHOLD && state.content.fields.isEmpty()) {
            item("load-more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    IosTextAction(if (visibleEntries.isEmpty()) "这一页没有更多，继续往前找" else "加载更早的记录") {
                        onLoad(section, false, true)
                    }
                }
            }
        }
        if (state.loaded && visibleEntries.isEmpty() && state.content.fields.isEmpty() &&
            state.error == null && !state.content.hasMore) {
            item("empty") { EmptyNotice() }
        }
        if (state.loading && state.loaded) item("more") { IosLoadingBox() }
        item("actions") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IosTextAction("刷新") { onLoad(section, true, false) }
                if (BuildConfig.DEBUG) {
                    IosTextAction("开发者选项", color = IosTheme.colors.secondaryLabel, onClick = onDiagnostics)
                }
            }
        }
    }

    // 退出登录确认对话框（防止误触）
    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确认要退出登录吗？退出后需要重新登录才能访问社区。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showLogoutDialog = false; onLogout() }
                ) {
                    Text("退出", color = IosTheme.colors.destructive)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showLogoutDialog = false }
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun notificationTypeTint(type: Int): Color = when (type) {
    5, 19 -> Color(0xFFFF3B30)
    25 -> Color(0xFFFF9500)
    14 -> Color(0xFF30B85B)
    12 -> Color(0xFFAF52DE)
    43 -> Color(0xFF5856D6)
    else -> IosTheme.colors.accent
}

private fun notificationTypeTag(type: Int): String = when (type) {
    1, 15 -> "提及"
    2 -> "回复"
    3 -> "引用"
    4 -> "编辑"
    5, 19 -> "赞了你"
    6, 7, 16 -> "私信"
    8 -> "接受邀请"
    9, 17 -> "新帖"
    10 -> "移动"
    11, 38 -> "链接"
    12 -> "徽章"
    13 -> "话题邀请"
    14 -> "解决方案"
    25 -> "表情回应"
    27 -> "活动提醒"
    28 -> "活动邀请"
    29, 32 -> "聊天提及"
    30 -> "聊天消息"
    31 -> "聊天邀请"
    33 -> "聊天引用"
    34 -> "指派"
    43 -> "简评"
    800, 801, 802 -> "关注动态"
    else -> "通知"
}

/**
 * 参照 Discourse Web 通知列表样式：在圆形头像右上角叠加带白/底色描边的类型角标图标，
 * 一眼区分回复(弯箭头)、点赞(爱心)、表情回应(笑脸点赞)、采纳解答(勾选框)、徽章(星章)、简评(小火箭)、私信(信封)。
 */
@Composable
private fun NotificationAvatarWithBadge(
    avatarUrl: String?,
    actor: String?,
    notificationType: Int,
    unread: Boolean,
) {
    val cardColor = IosTheme.colors.card
    val badgeIconColor = when {
        unread -> IosTheme.colors.accent
        IosTheme.colors.isDark -> Color(0xFFD1D1D6)
        else -> Color(0xFF3A3A3C)
    }
    Box(Modifier.size(38.dp)) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = actor,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(34.dp)
                    .clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(IosTheme.colors.fieldBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actor?.firstOrNull()?.uppercase() ?: "★",
                    style = IosTheme.type.footnote,
                    fontWeight = FontWeight.Bold,
                    color = IosTheme.colors.secondaryLabel,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(cardColor),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) {
                scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                    val shape = Path()
                    when (notificationType) {
                        // 回复 / 提及 / 引用 / 聊天引用 / 关注回复：左弯箭头 (↩)
                        1, 2, 3, 9, 15, 33, 801, 802 -> {
                            shape.moveTo(10f, 5f); shape.lineTo(3f, 11f); shape.lineTo(10f, 17f)
                            shape.lineTo(10f, 13f); shape.cubicTo(17f, 12f, 19f, 15f, 21f, 20f)
                            shape.cubicTo(21f, 10f, 17f, 8f, 10f, 9f); shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 点赞 / 合并点赞：实心爱心 (❤)
                        5, 19 -> {
                            shape.moveTo(12f, 21f); shape.cubicTo(10f, 19f, 2.5f, 13f, 2.5f, 8f)
                            shape.cubicTo(2.5f, 2.5f, 9.5f, 1.5f, 12f, 6.2f)
                            shape.cubicTo(14.5f, 1.5f, 21.5f, 2.5f, 21.5f, 8f)
                            shape.cubicTo(21.5f, 13f, 14f, 19f, 12f, 21f); shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 表情回应：圆脸 + 微笑弧线 + 右侧小拇指
                        25 -> {
                            drawCircle(badgeIconColor, radius = 7.5f, center = Offset(9.5f, 12f), style = Stroke(2.2f))
                            drawCircle(badgeIconColor, radius = 1.2f, center = Offset(7f, 10.2f))
                            drawCircle(badgeIconColor, radius = 1.2f, center = Offset(12f, 10.2f))
                            drawArc(
                                color = badgeIconColor,
                                startAngle = 25f,
                                sweepAngle = 130f,
                                useCenter = false,
                                topLeft = Offset(6.5f, 11f),
                                size = androidx.compose.ui.geometry.Size(6f, 4.5f),
                                style = Stroke(2f),
                            )
                            shape.moveTo(18f, 11f); shape.lineTo(19.5f, 7f); shape.lineTo(21.5f, 7.8f)
                            shape.lineTo(20.5f, 11f); shape.lineTo(23f, 11f); shape.lineTo(22.2f, 16.5f)
                            shape.lineTo(18f, 16.5f); shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 采纳解决方案：圆角方框 + 对勾 (☑)
                        14 -> {
                            if (unread) {
                                drawRoundRect(
                                    color = badgeIconColor,
                                    topLeft = Offset(3f, 3f),
                                    size = androidx.compose.ui.geometry.Size(18f, 18f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                                    style = Stroke(2.4f),
                                )
                                shape.moveTo(7.5f, 12.2f); shape.lineTo(10.6f, 15.3f); shape.lineTo(16.8f, 9f)
                                drawPath(shape, badgeIconColor, style = Stroke(2.4f))
                            } else {
                                drawRoundRect(
                                    color = badgeIconColor,
                                    topLeft = Offset(3f, 3f),
                                    size = androidx.compose.ui.geometry.Size(18f, 18f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                                )
                                shape.moveTo(7.5f, 12.2f); shape.lineTo(10.6f, 15.3f); shape.lineTo(16.8f, 9f)
                                drawPath(shape, cardColor, style = Stroke(2.4f))
                            }
                        }
                        // 获赠徽章：多角星章 (☀)
                        12 -> {
                            val points = 12
                            val outerR = 9.5f
                            val innerR = 7.2f
                            for (i in 0 until points * 2) {
                                val r = if (i % 2 == 0) outerR else innerR
                                val angle = i * Math.PI / points
                                val x = 12f + (r * kotlin.math.cos(angle)).toFloat()
                                val y = 12f + (r * kotlin.math.sin(angle)).toFloat()
                                if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                            }
                            shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 简评 (Boost)：小火箭 (🚀)
                        43 -> {
                            shape.moveTo(20f, 4f)
                            shape.cubicTo(15f, 4f, 10.5f, 7.5f, 8.5f, 12f)
                            shape.lineTo(5f, 13f); shape.lineTo(7.5f, 15.5f)
                            shape.lineTo(8.5f, 15.5f); shape.lineTo(11f, 19f)
                            shape.lineTo(12f, 15.5f)
                            shape.cubicTo(16.5f, 13.5f, 20f, 9f, 20f, 4f)
                            shape.close()
                            drawPath(shape, badgeIconColor)
                            drawCircle(cardColor, radius = 1.6f, center = Offset(14.5f, 9.5f))
                        }
                        // 私信：信封 (✉)
                        6, 7, 16 -> {
                            shape.moveTo(3f, 6f); shape.lineTo(21f, 6f); shape.lineTo(21f, 18f)
                            shape.lineTo(3f, 18f); shape.close()
                            shape.moveTo(3f, 7f); shape.lineTo(12f, 13.5f); shape.lineTo(21f, 7f)
                            drawPath(shape, badgeIconColor, style = Stroke(2.2f))
                        }
                        // 其他通知：小铃铛
                        else -> {
                            shape.moveTo(5f, 17f); shape.lineTo(7f, 14f); shape.lineTo(7f, 9f)
                            shape.cubicTo(7f, 2.5f, 17f, 2.5f, 17f, 9f)
                            shape.lineTo(17f, 14f); shape.lineTo(19f, 17f); shape.close()
                            drawPath(shape, badgeIconColor)
                            drawCircle(badgeIconColor, 1.8f, Offset(12f, 20f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationTabIcon(filter: NotificationFilter, color: Color) {
    Canvas(Modifier.size(17.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val shape = Path()
            when (filter) {
                NotificationFilter.All -> {
                    shape.moveTo(5f, 17f); shape.lineTo(7f, 14f); shape.lineTo(7f, 9f)
                    shape.cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                    shape.lineTo(17f, 14f); shape.lineTo(19f, 17f); shape.close()
                    drawPath(shape, color, style = Stroke(1.7f))
                    drawCircle(color, 1.5f, Offset(12f, 20f))
                }
                NotificationFilter.Replies -> {
                    shape.moveTo(10f, 5f); shape.lineTo(3f, 11f); shape.lineTo(10f, 17f)
                    shape.lineTo(10f, 13f); shape.cubicTo(17f, 12f, 19f, 15f, 21f, 20f)
                    shape.cubicTo(21f, 10f, 17f, 8f, 10f, 9f); shape.close()
                    drawPath(shape, color)
                }
                NotificationFilter.Messages -> {
                    shape.moveTo(3f, 5f); shape.lineTo(21f, 5f); shape.lineTo(21f, 19f)
                    shape.lineTo(3f, 19f); shape.close()
                    shape.moveTo(3f, 6f); shape.lineTo(12f, 13f); shape.lineTo(21f, 6f)
                    drawPath(shape, color, style = Stroke(1.7f))
                }
                NotificationFilter.Likes -> {
                    shape.moveTo(12f, 21f); shape.cubicTo(10f, 19f, 3f, 13f, 3f, 8f)
                    shape.cubicTo(3f, 2f, 10f, 1f, 12f, 6f); shape.cubicTo(14f, 1f, 21f, 2f, 21f, 8f)
                    shape.cubicTo(21f, 13f, 14f, 19f, 12f, 21f); shape.close(); drawPath(shape, color)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    treeView: Boolean,
    onTreeView: (Boolean) -> Unit,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    updateState: UpdateUiState = UpdateUiState(),
    onCheckUpdate: () -> Unit = {},
) {
    IosLargeTitleScaffold(title = "设置", onBack = onBack) {
        item("appearance") {
            SectionTitle("外观")
            IosGroupCard {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) IosDivider()
                    IosRow(onClick = { onThemeMode(mode) }) {
                        Text(mode.label, modifier = Modifier.weight(1f))
                        if (themeMode == mode) Text("✓", color = IosTheme.colors.accent)
                    }
                }
            }
        }
        item("reading") {
            SectionTitle("阅读")
            IosGroupCard {
                IosRow(onClick = { onTreeView(!treeView) }) {
                    Column(Modifier.weight(1f)) {
                        Text("树形查看话题", style = IosTheme.type.body)
                        Text(
                            "按回复关系排列，关闭后按楼层顺序显示",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    IosSwitch(checked = treeView, onCheckedChange = onTreeView)
                }
            }
        }
        item("about") {
            SectionTitle("版本与更新")
            IosGroupCard {
                IosRow {
                    Text("当前版本", style = IosTheme.type.body, modifier = Modifier.weight(1f))
                    Text(
                        "v${updateState.currentVersion}",
                        style = IosTheme.type.subheadline,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
                IosDivider()
                IosRow(onClick = onCheckUpdate) {
                    Column(Modifier.weight(1f)) {
                        Text("检查更新", style = IosTheme.type.body)
                        updateState.statusMessage?.let { msg ->
                            Text(
                                msg,
                                style = IosTheme.type.footnote,
                                color = if (updateState.updateInfo != null) IosTheme.colors.accent else IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    when {
                        updateState.checking -> IosActivityIndicator(diameter = 18.dp)
                        updateState.updateInfo != null -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFF3B30))
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        "NEW v${updateState.updateInfo.latestVersion}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        lineHeight = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text("›", color = IosTheme.colors.tertiaryLabel, style = IosTheme.type.title3)
                            }
                        }
                        else -> Text("›", color = IosTheme.colors.tertiaryLabel, style = IosTheme.type.title3)
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(
    currentVersion: String,
    info: UpdateInfo,
    onDismiss: (ignoreThisVersion: Boolean) -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = { if (!info.mandatory) onDismiss(false) },
        properties = DialogProperties(
            dismissOnBackPress = !info.mandatory,
            dismissOnClickOutside = !info.mandatory,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(IosTheme.colors.card)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (info.mandatory) {
                                IosTheme.colors.destructive.copy(alpha = 0.14f)
                            } else {
                                IosTheme.colors.accent.copy(alpha = 0.14f)
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = if (info.mandatory) "必升版本" else "发现新版本",
                        style = IosTheme.type.footnote,
                        color = if (info.mandatory) IosTheme.colors.destructive else IosTheme.colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "v$currentVersion → v${info.latestVersion}",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = info.title,
                style = IosTheme.type.title3,
                color = IosTheme.colors.label,
            )

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp, max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(IosTheme.colors.fieldBackground)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = info.changelog,
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.label,
                    lineHeight = 19.sp,
                )
            }

            if (info.mandatory) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "当前版本包含关键协议或安全更新，请升级至最新版本后继续使用。",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.destructive,
                )
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(IosTheme.colors.accent)
                    .clickable {
                        openInDefaultBrowser(context, info.downloadUrl)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "立即升级 (v${info.latestVersion})",
                    style = IosTheme.type.body,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (!info.mandatory) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss(true) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "忽略此版本",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss(false) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "稍后提醒",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.accent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 优先调用系统设置中的“默认浏览器”打开升级链接，避免被装有特定下载接管过滤器的第三方应用（如某些下载器）误拦截。
 */
fun openInDefaultBrowser(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    // 1. 构造一个纯网页意图，向系统 PackageManager 查询系统设置中指定的真正“默认浏览器”
    val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://linux.do")).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val defaultBrowserPkg = runCatching {
        context.packageManager
            .resolveActivity(probeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != "com.google.android.packageinstaller" }
    }.getOrNull()

    val targetIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!defaultBrowserPkg.isNullOrBlank()) {
            setPackage(defaultBrowserPkg)
        }
    }
    val launched = runCatching {
        context.startActivity(targetIntent)
        true
    }.getOrDefault(false)

    if (!launched) {
        // 兜底：若指定包名启动失败，移除包名并使用通用选择器唤起
        runCatching {
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

@Composable
fun UnreadBell(count: Int, onClick: () -> Unit) {
    val iconColor = IosTheme.colors.secondaryLabel
    Box(Modifier.size(44.dp).semantics { contentDescription = "通知，${count}条回复或私信未读" }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val bell = Path().apply {
                    moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f)
                    cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                    lineTo(17f, 14f); lineTo(19f, 17f); close()
                }
                drawPath(bell, iconColor, style = Stroke(1.7f))
                drawArc(iconColor, 0f, 180f, false, Offset(10f, 18f), androidx.compose.ui.geometry.Size(4f, 3f), style = Stroke(1.7f))
            }
        }
        if (count > 0) Text(if (count > 99) "99+" else count.toString(), color = Color.White,
            fontSize = 10.sp, lineHeight = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 2.dp)
                .background(Color(0xFFFF3B30), RoundedCornerShape(20.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/** 登录/验证页顶部的说明条;WebView 本体由 AppShell 抬到它下方显示。 */
@Composable
fun WebTopBar(screen: Screen) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(IosMetrics.navBarHeight)
            .background(IosTheme.colors.card),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (screen == Screen.Login) "登录 linux.do" else "需要安全验证(通过后自动返回)",
            style = IosTheme.type.navTitle,
            color = IosTheme.colors.label,
        )
    }
}

/**
 * 开发者选项。承接原型界面的全部调试能力:双路径切换、自动刷新、会话诊断与日志。
 * 这些是排查 Cloudflare 与会话保持问题的主要手段,所以界面改版时只换位置、不削功能。
 */
@Composable
fun DiagnosticsScreen(state: UiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val stats by viewModel.diagnostics.stats.collectAsStateWithLifecycle()
    val lines by viewModel.diagnostics.lines.collectAsStateWithLifecycle()
    val savedAt = if (state.cookieSavedAt == 0L) {
        "-"
    } else {
        DateFormat.getDateTimeInstance().format(Date(state.cookieSavedAt))
    }

    IosLargeTitleScaffold(title = "开发者选项", onBack = onBack) {
        item(key = "paths") {
            SectionTitle("网络路径")
            IosGroupCard {
                NetworkPath.entries.forEachIndexed { index, path ->
                    IosRow(onClick = { viewModel.setPath(path) }) {
                        Text(
                            text = path.label,
                            style = IosTheme.type.body,
                            color = IosTheme.colors.label,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.path == path) {
                            Text("使用中", style = IosTheme.type.subheadline, color = IosTheme.colors.accent)
                        }
                    }
                    if (index < NetworkPath.entries.lastIndex) IosDivider()
                }
            }
        }

        item(key = "actions") {
            SectionTitle("操作")
            IosGroupCard {
                IosRow {
                    Text(
                        text = "自动刷新(${Config.AUTO_REFRESH_INTERVAL_MS / 60_000} 分钟)",
                        style = IosTheme.type.body,
                        color = IosTheme.colors.label,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.autoRefresh,
                        onCheckedChange = { viewModel.toggleAutoRefresh() },
                        colors = SwitchDefaults.colors(
                            // iOS 的开关轨道是 systemGreen。
                            checkedTrackColor = Color(0xFF34C759),
                            checkedThumbColor = Color.White,
                        ),
                    )
                }
                IosDivider()
                IosRow(onClick = viewModel::retryLatest) {
                    Text("立即刷新列表", style = IosTheme.type.body, color = IosTheme.colors.accent)
                }
                IosDivider()
                IosRow(onClick = viewModel::logout) {
                    Text("登出并清除 Cookie", style = IosTheme.type.body, color = IosTheme.colors.destructive)
                }
            }
        }

        item(key = "session") {
            SectionTitle("会话诊断")
            IosGroupCard {
                InfoRow("当前用户", state.username ?: "未确认")
                IosDivider()
                InfoRow("最近路径", stats.lastPath)
                IosDivider()
                InfoRow("最近状态码", stats.lastStatus?.toString() ?: "-")
                IosDivider()
                InfoRow("静默重验次数", stats.silentRevalidations.toString())
                IosDivider()
                InfoRow("需交互验证次数", stats.interactiveRequired.toString())
                IosDivider()
                InfoRow("Cookie", state.cookieNames.joinToString(", ").ifEmpty { "无" })
                IosDivider()
                InfoRow("Cookie 更新于", savedAt)
                IosDivider()
                InfoRow("User-Agent", state.userAgent)
            }
        }

        item(key = "log-title") { SectionTitle("日志") }
        // 日志文本可能重复,不能拿内容当 key。
        items(lines.asReversed()) { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = IosTheme.colors.secondaryLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IosMetrics.screenPadding, vertical = 2.dp),
            )
        }
        item(key = "log-tail") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = IosTheme.type.footnote,
        color = IosTheme.colors.secondaryLabel,
        modifier = Modifier.padding(
            start = IosMetrics.screenPadding + 16.dp,
            end = IosMetrics.screenPadding,
            top = 24.dp,
            bottom = 6.dp,
        ),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    IosRow {
        Text(
            text = label,
            style = IosTheme.type.body,
            color = IosTheme.colors.label,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = IosTheme.type.subheadline,
            color = IosTheme.colors.secondaryLabel,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 文字按钮,iOS 里强调操作就是一段蓝色文字。 */
@Composable
fun IosTextAction(text: String, color: Color = IosTheme.colors.accent,
    textStyle: androidx.compose.ui.text.TextStyle = IosTheme.type.body, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = text, style = textStyle, color = color)
    }
}

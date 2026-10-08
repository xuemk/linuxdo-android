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
    scrollToTopRequest: Int = 0,
    trailing: @Composable () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(if (notificationOnly) ProfileSection.Notifications else ProfileSection.Summary) }
    var previousSection by rememberSaveable { mutableStateOf(section) }
    val list = rememberLazyListState()
    val state = states[section] ?: ProfileUiState()
    var notificationFilter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    val visibleEntries = if (section == ProfileSection.Notifications) state.content.entries.filter {
        notificationFilter.includes(it.notificationType)
    } else state.content.entries
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
    LaunchedEffect(active, nearEnd, state, notificationFilter) {
        if (active && nearEnd && state.loaded && !state.loading && state.error == null && state.content.hasMore && visibleEntries.isNotEmpty()) {
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
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) IosTheme.colors.accent.copy(alpha = 0.12f) else IosTheme.colors.card)
                        .clickable { notificationFilter = filter }.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
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
                    if (entry.unread) Text("•", color = IosTheme.colors.accent, modifier = Modifier.padding(end = 8.dp))
                    entry.avatarUrl?.let { url ->
                        AsyncImage(url, entry.actor, modifier = Modifier.size(32.dp).clip(CircleShape))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f).padding(vertical = if (notification) 0.dp else 4.dp)) {
                        entry.actor?.let { actor -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(actor, style = IosTheme.type.footnote, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (notification) Text(entry.subtitle.substringAfterLast(" · ").takeLast(5),
                                style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                        } }
                        Text(entry.title, style = if (notification) IosTheme.type.subheadline else IosTheme.type.body,
                            maxLines = if (notification) 2 else 3)
                        if (!notification && entry.subtitle.isNotBlank()) Text(entry.subtitle, style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (state.loaded && visibleEntries.isEmpty() && state.content.fields.isEmpty() && state.error == null) {
            item("empty") {
                if (section == ProfileSection.Notifications && state.content.hasMore) {
                    IosTextAction("加载更早通知") { onLoad(section, false, true) }
                } else EmptyNotice()
            }
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
fun SettingsScreen(treeView: Boolean, onTreeView: (Boolean) -> Unit, onBack: () -> Unit,
    themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
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
                        Text("按回复关系排列，关闭后按楼层顺序显示", style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
                    }
                    IosSwitch(checked = treeView, onCheckedChange = onTreeView)
                }
            }
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

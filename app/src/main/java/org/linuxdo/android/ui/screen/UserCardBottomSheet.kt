package org.linuxdo.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.linuxdo.android.data.UserCardData
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.data.AvatarCache
import org.linuxdo.android.ui.UserCardUiState
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.design.sheetScrollable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserCardBottomSheet(
    state: UserCardUiState,
    onDismiss: () -> Unit,
    onFollow: () -> Unit,
    onMessage: (String) -> Unit,
    onChat: (String) -> Unit,
    onOpenTopics: (String) -> Unit,
    onToggleBlock: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = IosTheme.colors.card,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            val user = state.user
            val backgroundUrl = user?.backgroundUrl
            if (backgroundUrl != null) {
                val bgRequest = org.linuxdo.android.ui.design.rememberAnimatedImageRequest(backgroundUrl)
                AsyncImage(
                    model = bgRequest ?: backgroundUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    IosTheme.colors.card.copy(alpha = 0.45f),
                                    IosTheme.colors.card.copy(alpha = 0.92f),
                                ),
                            ),
                        ),
                )
            }
            when {
                state.loading && user == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IosActivityIndicator(diameter = 24.dp, color = IosTheme.colors.accent)
                }
                state.error != null && user == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        state.error,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
                user == null -> Unit
                else -> UserCardContent(user, state, onFollow, onMessage, onChat, onOpenTopics, onToggleBlock)
            }
        }
    }
}

@Composable
private fun UserCardContent(
    user: UserCardData,
    state: UserCardUiState,
    onFollow: () -> Unit,
    onMessage: (String) -> Unit,
    onChat: (String) -> Unit,
    onOpenTopics: (String) -> Unit,
    onToggleBlock: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val isDark = IosTheme.colors.isDark

    val primaryBtnBg = if (isDark) Color(0xFF2A6A99) else Color(0xFF245F8A)
    val secondaryBtnBg = if (isDark) Color(0xFF1E2E42) else Color(0xFFE1EDF7)
    val secondaryBtnText = if (isDark) Color(0xFF93C5FD) else Color(0xFF1D5A8A)

    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .sheetScrollable()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CompactUserIdentityHeader(
            user = user,
            modifier = Modifier.padding(top = 4.dp),
        )

        // 个性签名 / 简介 (固定占位，杜绝异步数据插入导致弹窗与图像上下跳动)
        val rawBio = user.bioExcerpt?.let(::bioPlainText)?.trim()
        val bioText = if (!rawBio.isNullOrBlank()) rawBio else "这个人很懒，没有写签名！"
        val isBioEmpty = rawBio.isNullOrBlank()

        Text(
            text = bioText,
            style = IosTheme.type.body.copy(fontSize = 14.sp),
            color = if (isBioEmpty) IosTheme.colors.secondaryLabel.copy(alpha = 0.65f) else IosTheme.colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        )

        // 灰度属性信息行: 加入时间 / 注册时间
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "加入时间 ${formatJoinDate(user.createdAt)}",
                style = IosTheme.type.caption.copy(fontSize = 12.5.sp),
                color = IosTheme.colors.secondaryLabel,
            )
            Text(
                text = "最后活跃 刚刚",
                style = IosTheme.type.caption.copy(fontSize = 12.5.sp),
                color = IosTheme.colors.secondaryLabel,
            )
        }

        // 核心数据指标 (高质感横向圆角边框卡片: [ 话题 X | 帖子 Y | 获赞 Z ])
        val showMetrics = !state.loading || user.topicCount > 0 || user.postCount > 0 || user.likesReceived > 0
        val topicDisplay = if (showMetrics) formatCount(user.topicCount) else "—"
        val postDisplay = if (showMetrics) formatCount(user.postCount) else "—"
        val likesDisplay = if (showMetrics) formatCount(user.likesReceived) else "—"

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDark) Color(0xFF1E2128) else Color(0xFFF3F5F7))
                .border(1.dp, if (isDark) Color(0xFF2C323D) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                // 话题
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        text = topicDisplay,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("话题", fontSize = 11.5.sp, color = IosTheme.colors.secondaryLabel)
                }
                // 细竖分割线
                Box(modifier = Modifier.width(1.dp).height(20.dp).background(if (isDark) Color(0xFF2E3542) else Color(0xFFCBD5E1)))
                // 帖子
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        text = postDisplay,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("帖子", fontSize = 11.5.sp, color = IosTheme.colors.secondaryLabel)
                }
                // 细竖分割线
                Box(modifier = Modifier.width(1.dp).height(20.dp).background(if (isDark) Color(0xFF2E3542) else Color(0xFFCBD5E1)))
                // 获赞
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        text = likesDisplay,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("获赞", fontSize = 11.5.sp, color = IosTheme.colors.secondaryLabel)
                }
            }
        }

        // 用户勋章展示 (固定占位显示，杜绝异步数据插入导致弹窗上下跳动)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "用户勋章",
                style = IosTheme.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = IosTheme.colors.secondaryLabel,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (user.badges.isNotEmpty()) {
                    user.badges.take(3).forEach { badge ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDark) Color(0xFF252932) else Color(0xFFEFF2F6))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text("🏅", fontSize = 13.sp)
                            Text(
                                text = badge.name,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = IosTheme.colors.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    // 默认占位胶囊：保持与勋章完全一致的高度与布局，防止上下跳动
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF252932).copy(alpha = 0.5f) else Color(0xFFEFF2F6).copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("🏅", fontSize = 13.sp)
                        Text(
                            text = if (state.loading) "正在获取勋章..." else "暂无勋章",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = IosTheme.colors.secondaryLabel.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // ── 按钮组第一行（最上面）：全宽关注按钮 [✓ 已关注] / [+ 关注] ──
        if (!state.isSelf) {
            val isFollowed = state.following
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isFollowed) {
                            if (isDark) Color(0xFF1E2E42) else Color(0xFFE1EDF7)
                        } else {
                            if (isDark) Color(0xFF0284C7) else Color(0xFF0284C7)
                        }
                    )
                    .clickable(enabled = !state.followUpdating, onClick = onFollow),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (isFollowed) "✓" else "+",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFollowed) secondaryBtnText else Color.White,
                    )
                    Text(
                        text = if (state.followUpdating) "请稍候..." else if (isFollowed) "已关注" else "关注",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFollowed) secondaryBtnText else Color.White,
                    )
                }
            }
        }

        // ── 按钮组第二行：[💬 私聊] 与 [✉️ 私信] ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 私聊主按钮
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primaryBtnBg)
                    .clickable { onChat(user.username) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("💬", fontSize = 14.sp)
                    Text(
                        text = "私聊",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }

            // 私信辅助按钮
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(secondaryBtnBg)
                    .clickable { onMessage(user.username) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("✉️", fontSize = 14.sp)
                    Text(
                        text = "私信",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = secondaryBtnText,
                    )
                }
            }
        }

        // ── 按钮组第三行：[🪪 查看资料] 与 [🚫 加入黑名单 / 🔄 移除黑名单] ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 查看资料按钮
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, IosTheme.colors.separator, RoundedCornerShape(12.dp))
                    .clickable { onOpenTopics(user.username) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("🪪", fontSize = 14.sp)
                    Text(
                        text = "查看资料",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = IosTheme.colors.label,
                    )
                }
            }

            // 黑名单按钮
            val isBlocked = state.isBlocked
            val isFollowed = state.following
            val blockBg = when {
                isFollowed -> (if (isDark) Color(0xFF25262B) else Color(0xFFF1F3F5)).copy(alpha = 0.5f)
                isBlocked -> (if (isDark) Color(0xFF381B1B) else Color(0xFFFFECEB))
                else -> (if (isDark) Color(0xFF25262B) else Color(0xFFF1F3F5))
            }
            val blockText = when {
                isFollowed -> "加入黑名单"
                isBlocked -> "移除黑名单"
                else -> "加入黑名单"
            }
            val blockTextColor = when {
                isFollowed -> IosTheme.colors.secondaryLabel.copy(alpha = 0.5f)
                isBlocked -> Color(0xFFFF3B30)
                else -> IosTheme.colors.secondaryLabel
            }
            val blockBorderColor = if (isBlocked) Color(0xFFFF3B30).copy(alpha = 0.5f) else IosTheme.colors.separator

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, blockBorderColor, RoundedCornerShape(12.dp))
                    .background(blockBg)
                    .clickable {
                        if (isFollowed) {
                            Toast.makeText(context, "已关注用户不能拉黑，请先取消关注", Toast.LENGTH_SHORT).show()
                        } else {
                            onToggleBlock()
                            if (isBlocked) {
                                Toast.makeText(context, "已将 @${user.username} 移出黑名单", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "已将 @${user.username} 加入本地黑名单", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(if (isBlocked) "🔄" else "🚫", fontSize = 13.sp)
                    Text(
                        text = blockText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = blockTextColor,
                    )
                }
            }
        }

        state.error?.let { Text(it, style = IosTheme.type.caption, color = Color(0xFFFF3B30)) }
        Spacer(Modifier.height(10.dp))
    }
}

/** 简评操作与个人资料栏共用的身份头部，避免同一用户在不同弹层中呈现不同风格。 */
@Composable
internal fun CompactUserIdentityHeader(
    user: UserCardData,
    modifier: Modifier = Modifier,
) {
    val isDark = IosTheme.colors.isDark
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.size(66.dp)) {
            val avatar = avatarUrlOf(
                template = user.avatarTemplate,
                animatedAvatar = user.animatedAvatar ?: AvatarCache.get(user.username),
                size = 120,
                username = user.username,
            )
            val staticAvatar = avatarUrlOf(template = user.avatarTemplate, size = 64)
                ?: avatarUrlOf(template = user.avatarTemplate, size = 120)
            val cardShape = org.linuxdo.android.ui.design.avatarShape(user.username, avatar, 12.dp)
            org.linuxdo.android.ui.design.UserAvatar(
                avatarUrl = avatar,
                fallbackUrl = staticAvatar,
                username = user.username,
                modifier = Modifier
                    .size(64.dp)
                    .border(2.5.dp, if (isDark) Color(0xFF48484A) else Color.White, cardShape),
                cornerRadius = 12.dp,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.cleanDisplayName,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text("🔥", fontSize = 16.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "@${user.username}",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                    maxLines = 1,
                )
                user.trustLevel?.let { level ->
                    LevelBadge(level)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(IosTheme.colors.fieldBackground)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            value,
            style = IosTheme.type.subheadline.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = IosTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = IosTheme.type.caption.copy(fontSize = 11.sp),
            color = IosTheme.colors.secondaryLabel,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatJoinDate(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    val datePart = raw.take(10)
    return if (datePart.length == 10 && datePart[4] == '-' && datePart[7] == '-') {
        datePart.substring(2)
    } else {
        datePart
    }
}

/**
 * 资料卡接口返回的 `bio_excerpt` 是 HTML 片段（含 `<br>`、`<img class="emoji">`），
 * 直接喂给 Text 会把标签原样显示。这里只做降级为纯文本：emoji 用 alt 还原成 `:cat:` 短码，
 * 其余标签丢弃、块级换行保留。
 */
private fun bioPlainText(html: String): String {
    val document = Jsoup.parse(html)
    document.select("br").forEach { it.replaceWith(TextNode(NEWLINE_MARKER)) }
    document.select("p").forEach { it.append(NEWLINE_MARKER) }
    document.select("img").forEach { image ->
        image.replaceWith(TextNode(image.attr("alt")))
    }
    return document.text()
        .replace(NEWLINE_MARKER, "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** `<br>`/`<p>` 的换行占位符。Document.text() 会把空白折叠掉，必须用可见字符占位再还原。 */
private const val NEWLINE_MARKER = "\u0001"

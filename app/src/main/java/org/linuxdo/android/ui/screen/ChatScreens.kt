package org.linuxdo.android.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.linuxdo.android.data.AttachedMedia
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.linuxdo.android.ui.RichMarkdownEditorView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import org.linuxdo.android.Config
import org.linuxdo.android.data.ChatUpload
import kotlinx.coroutines.delay
import org.linuxdo.android.data.ChatChannelSummary
import org.linuxdo.android.data.ChatMessageSummary
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.ui.ChatUiState
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.LocalListBars
import org.linuxdo.android.ui.design.scrollListBars
import org.linuxdo.android.ui.design.FeedItemDivider
import org.linuxdo.android.ui.design.MessageFeedRow
import org.linuxdo.android.ui.design.UserAvatar
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosNavBar
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.design.FullscreenDialog
import org.linuxdo.android.ui.format.RelativeTime

@Composable
internal fun ChatList(
    state: ChatUiState,
    directMessages: Boolean,
    showSearch: Boolean,
    feedDisplayMode: String,
    scrollToTopRequest: Int,
    onRefresh: () -> Unit,
    onOpenChannel: (ChatChannelSummary) -> Unit,
    active: Boolean,
) {
    var queryText by rememberSaveable { mutableStateOf("") }
    val searchQuery = if (showSearch) queryText else ""
    LaunchedEffect(showSearch) { if (!showSearch) queryText = "" }
    val currentList = remember(directMessages, state.channels, searchQuery) {
        val base = state.channels.filter { it.isDirectMessage == directMessages }
        val query = searchQuery.trim()
        if (query.isEmpty()) base
        else base.filter { channel ->
            channel.displayTitle.contains(query, ignoreCase = true) ||
                channel.categoryName?.contains(query, ignoreCase = true) == true ||
                channel.lastMessage?.message?.contains(query, ignoreCase = true) == true
        }
    }

    Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
        // 搜索栏展开
        AnimatedVisibility(visible = showSearch && LocalListBars.current?.value != false) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IosTheme.colors.card)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { queryText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(IosTheme.colors.fieldBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = IosTheme.colors.label),
                    singleLine = true,
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                if (!directMessages) "搜索频道..." else "搜索直接消息...",
                                style = TextStyle(fontSize = 14.sp, color = IosTheme.colors.tertiaryLabel),
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }

        // 频道/消息列表内容
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadingChannels && state.channels.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        IosActivityIndicator(diameter = 24.dp, color = IosTheme.colors.accent)
                    }
                }
                state.channelsError != null && state.channels.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(state.channelsError, style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onRefresh) { Text("重试") }
                    }
                }
                currentList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "未搜索到匹配项"
                            else if (!directMessages) "暂无公共频道" else "暂无直接消息",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                }
                else -> {
                    val list = rememberLazyListState()
                    org.linuxdo.android.ui.design.ScrollToTopOnRequest(list, scrollToTopRequest)
                    LazyColumn(
                        state = list,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 72.dp),
                        modifier = Modifier.fillMaxSize().scrollListBars(list, active),
                    ) {
                        itemsIndexed(currentList, key = { _, channel -> channel.id }) { index, channel ->
                            ChatChannelRow(channel, onClick = { onOpenChannel(channel) })
                            if (index < currentList.lastIndex) FeedItemDivider(feedDisplayMode)
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun ChatChannelRow(channel: ChatChannelSummary, onClick: () -> Unit) {
    val direct = channel.isDirectMessage
    val last = channel.lastMessage
    val preview = remember(last, direct, channel.categoryName) {
        if (last == null) {
            if (direct) "暂无对话" else channel.categoryName ?: "暂无新消息"
        } else {
            val raw = last.message.ifBlank { org.jsoup.Jsoup.parseBodyFragment(last.cooked).text() }
            val message = renderEmojiShortcodes(raw, keepUnknown = true).orEmpty()
            val sender = last.user?.name?.takeIf { it.isNotBlank() } ?: last.user?.username
            listOfNotNull(sender, message).filter { it.isNotBlank() }.joinToString("：")
        }
    }
    MessageFeedRow(
        name = channel.displayTitle,
        preview = preview,
        date = RelativeTime.format(last?.createdAt),
        onClick = onClick,
        avatar = {
            if (direct) {
                val user = channel.dmUsers.firstOrNull() ?: last?.user
                UserAvatar(avatarUrl = avatarUrlOf(user?.avatarTemplate, 80),
                    username = user?.username ?: channel.displayTitle, modifier = Modifier.size(32.dp))
            } else {
                Box(Modifier.size(32.dp).background(IosTheme.colors.fieldBackground, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("#", style = IosTheme.type.body, color = IosTheme.colors.secondaryLabel)
                }
            }
        },
        badge = {
            if (channel.unreadCount > 0) {
                val count = channel.unreadCount
                Text(if (count > 99) "99+" else count.toString(), style = IosTheme.type.caption.copy(fontSize = 11.sp),
                    color = Color.White, modifier = Modifier.background(IosTheme.colors.accent, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .semantics { contentDescription = "${count}条未读消息" })
            }
        },
    )
}

@Composable
fun ChatRoomScreen(
    channel: ChatChannelSummary,
    state: ChatUiState,
    active: Boolean,
    currentUsername: String? = null,
    onLoad: (Long, Boolean) -> Unit,
    onLoadPast: (Long) -> Unit = {},
    onMessagesVisible: (Long, Long) -> Unit = { _, _ -> },
    onSend: (Long, String, List<Long>, () -> Unit) -> Unit,
    onUploadAttachment: (Long, Uri, (AttachedMedia) -> Unit, (String) -> Unit) -> Unit = { _, _, _, _ -> },
    onOpenUser: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val channelId = channel.id
    val messages = state.messagesByChannel[channelId].orEmpty()
    val listState = rememberLazyListState()
    var draft by rememberSaveable(channelId) { mutableStateOf("") }
    var showEmojiPicker by remember(channelId) { mutableStateOf(false) }
    var showComposerSheet by remember(channelId) { mutableStateOf(false) }
    var pendingAttachments by remember(channelId) { mutableStateOf<List<AttachedMedia>>(emptyList()) }
    var isUploading by remember(channelId) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val send: (String, List<Long>) -> Unit = { text, uploads ->
        onSend(channelId, text, uploads) {
            draft = ""
            pendingAttachments = emptyList()
            showComposerSheet = false
            showEmojiPicker = false
            keyboard?.hide()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            isUploading = true
            onUploadAttachment(channelId, uri, { media ->
                isUploading = false
                pendingAttachments = pendingAttachments + media
            }, {
                isUploading = false
            })
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isUploading = true
            onUploadAttachment(channelId, uri, { media ->
                isUploading = false
                pendingAttachments = pendingAttachments + media
            }, {
                isUploading = false
            })
        }
    }

    LaunchedEffect(active, channelId) {
        if (!active) return@LaunchedEffect
        onLoad(channelId, false)
    }

    LaunchedEffect(active, channelId, messages, channel.membership?.lastReadMessageId) {
        if (!active) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.maxOrNull() }
            .distinctUntilChanged().collectLatest { lastVisible ->
                if (lastVisible != null) {
                    delay(400)
                    onMessagesVisible(channelId, lastVisible)
                }
            }
    }

    var initialScrollDone by remember(channelId) { mutableStateOf(false) }
    var prevFirstMessageId by remember(channelId) { mutableStateOf<Long?>(null) }
    var prevLastMessageId by remember(channelId) { mutableStateOf<Long?>(null) }
    val canLoadMorePast = state.canLoadMorePastByChannel[channelId] ?: true
    val isLoadingPast = state.loadingPastChannelId == channelId

    LaunchedEffect(messages) {
        if (messages.isEmpty()) return@LaunchedEffect
        val currentFirstId = messages.first().id
        val currentLastId = messages.last().id
        if (!initialScrollDone) {
            initialScrollDone = true
            listState.scrollToItem(messages.lastIndex)
        } else if (prevFirstMessageId != null && currentFirstId != prevFirstMessageId) {
            val addedCount = messages.indexOfFirst { it.id == prevFirstMessageId }.takeIf { it > 0 } ?: 0
            if (addedCount > 0) {
                val newIndex = listState.firstVisibleItemIndex + addedCount
                val offset = listState.firstVisibleItemScrollOffset
                listState.scrollToItem(newIndex, offset)
            }
        } else if (prevLastMessageId != null && currentLastId != prevLastMessageId) {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (totalItems - lastVisibleIndex <= 3) {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
        prevFirstMessageId = currentFirstId
        prevLastMessageId = currentLastId
    }

    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex, canLoadMorePast, isLoadingPast, initialScrollDone) {
        if (firstVisibleIndex <= 2 && canLoadMorePast && !isLoadingPast && messages.isNotEmpty() && initialScrollDone) {
            onLoadPast(channelId)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
            // 顶部导航
            IosNavBar(
                title = channel.displayTitle,
                onBack = onBack,
            )

        // 错误提示
        if (state.error != null) {
            Text(state.error, modifier = Modifier.padding(16.dp), color = IosTheme.colors.secondaryLabel)
        }

        // 消息气泡列表
        if (state.loadingChannelId == channelId && messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                IosActivityIndicator(diameter = 24.dp, color = IosTheme.colors.accent)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            ) {
                if (isLoadingPast) {
                    item(key = "loading_past_indicator") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            IosActivityIndicator(diameter = 18.dp, color = IosTheme.colors.accent)
                        }
                    }
                } else if (!canLoadMorePast && messages.size >= 50) {
                    item(key = "all_history_loaded") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "已加载全部历史消息",
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }

                items(messages, key = { it.id }) { message ->
                    val isMe = currentUsername != null && message.user?.username == currentUsername
                    ChatMessageBubble(
                        message = message,
                        isMe = isMe,
                        onOpenUser = onOpenUser,
                    )
                }
            }
        }

        // 底部现代输入栏
        Surface(
            color = IosTheme.colors.card,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Column {
                IosDivider()

                // 待发送附件预览栏
                if (pendingAttachments.isNotEmpty() || isUploading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (media in pendingAttachments) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(IosTheme.colors.fieldBackground)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (media.isImage) {
                                        AsyncImage(
                                            model = media.previewUrl,
                                            contentDescription = media.name,
                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Text("📄", fontSize = 14.sp)
                                    }
                                    Text(
                                        text = media.name.take(12),
                                        style = IosTheme.type.caption,
                                        color = IosTheme.colors.label,
                                        maxLines = 1,
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除",
                                        tint = IosTheme.colors.secondaryLabel,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                pendingAttachments = pendingAttachments.filterNot { it.id == media.id }
                                            },
                                    )
                                }
                            }
                        }
                        if (isUploading) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(IosTheme.colors.fieldBackground)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                IosActivityIndicator(diameter = 14.dp, color = IosTheme.colors.accent)
                                Text("正在上传...", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 图片按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isUploading && state.sendingChannelId == null) {
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerImageIcon(tint = if (isUploading) IosTheme.colors.tertiaryLabel else IosTheme.colors.label)
                    }

                    // 附件按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isUploading && state.sendingChannelId == null) {
                                pickFile.launch(arrayOf("*/*"))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerAttachmentIcon(tint = if (isUploading) IosTheme.colors.tertiaryLabel else IosTheme.colors.label)
                    }

                    // 表情按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (showEmojiPicker) IosTheme.colors.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { showEmojiPicker = !showEmojiPicker },
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerEmojiIcon(tint = if (showEmojiPicker) IosTheme.colors.accent else IosTheme.colors.label)
                    }

                    // 胶囊文本输入框 (点击弹出评论样式全屏/半屏输入框)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(IosTheme.colors.fieldBackground)
                            .clickable(enabled = state.sendingChannelId == null) { showComposerSheet = true }
                            .semantics { contentDescription = "输入聊天消息" }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        if (draft.isEmpty()) {
                            Text("发送消息...", style = TextStyle(fontSize = 15.sp, color = IosTheme.colors.tertiaryLabel))
                        } else {
                            Text(
                                text = draft,
                                style = TextStyle(fontSize = 15.sp, color = IosTheme.colors.label),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // 发送药丸按钮
                    val canSend = (draft.isNotBlank() || pendingAttachments.isNotEmpty()) && !isUploading && state.sendingChannelId == null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (canSend) IosTheme.colors.accent else IosTheme.colors.accent.copy(alpha = 0.35f))
                            .clickable(enabled = canSend) {
                                val text = draft.trim()
                                val attachText = pendingAttachments.joinToString("\n") { it.toMarkdown() }
                                val fullMessage = when {
                                    text.isBlank() -> attachText
                                    attachText.isBlank() -> text
                                    else -> "$text\n\n$attachText"
                                }
                                if (fullMessage.isNotBlank()) {
                                    val uploadIds = pendingAttachments.mapNotNull { it.uploadId }
                                    send(fullMessage, uploadIds)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.sendingChannelId == channelId) {
                            IosActivityIndicator(diameter = 16.dp, color = Color.White)
                        } else {
                            Text(
                                text = "发送",
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                            )
                        }
                    }
                }

                // 常用表情快捷行
                if (showEmojiPicker) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(IosTheme.colors.fieldBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        listOf("👍", "😀", "😂", "🤣", "😍", "🎉", "🔥", "🙏", "❤️", "🤔", "👏", "🫡", "👀", "🚀", "🍻", "💯").forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier.clickable { draft += emoji },
                            )
                        }
                    }
                }
            }
        }
    }

    // 点击弹出的富文本发表评论/全屏输入面板
    if (showComposerSheet) {
        ChatComposerBottomSheet(
            channelTitle = channel.displayTitle.ifBlank { "实时聊天" },
            draft = draft,
            onDraftChange = { newDraft: String -> draft = newDraft },
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = { removeId: String -> pendingAttachments = pendingAttachments.filterNot { it.id == removeId } },
            isUploading = isUploading,
            isSending = state.sendingChannelId == channelId,
            error = state.error,
            onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickFile = { pickFile.launch(arrayOf("*/*")) },
            onSend = { fullMessage, uploadIds ->
                if (fullMessage.isNotBlank()) {
                    send(fullMessage, uploadIds)
                }
            },
            onDismiss = { keyboard?.hide(); showComposerSheet = false },
        )
    }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessageSummary,
    isMe: Boolean,
    onOpenUser: (String) -> Unit,
) {
    val user = message.user
    val textContent = remember(message.message, message.cooked) {
        val raw = if (message.message.isNotBlank()) message.message
        else android.text.Html.fromHtml(message.cooked, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        renderEmojiShortcodes(raw, keepUnknown = true).orEmpty()
    }
    val timeStr = remember(message.createdAt) { RelativeTime.format(message.createdAt) }
    // 图片附件与文件附件分类
    val imageUploads = remember(message.uploads) { message.uploads.filter { it.isImage } }
    val fileUploads = remember(message.uploads) { message.uploads.filter { !it.isImage } }
    // 全屏预览状态
    var fullscreenImage by remember { mutableStateOf<ChatUpload?>(null) }

    // 全屏图片预览 Dialog
    fullscreenImage?.let { upload ->
        FullscreenDialog(
            onDismissRequest = { fullscreenImage = null },
            darkSystemBars = true,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .safeDrawingPadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { fullscreenImage = null },
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = upload.absoluteUrl(Config.BASE_URL),
                    contentDescription = upload.originalFilename,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    if (isMe) {
        // 我方发送的消息靠右展示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = timeStr,
                style = TextStyle(fontSize = 11.sp, color = IosTheme.colors.tertiaryLabel),
                modifier = Modifier.padding(end = 6.dp, bottom = 2.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                    .background(IosTheme.colors.accent)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (textContent.isNotBlank()) {
                        Text(
                            text = textContent,
                            style = TextStyle(fontSize = 15.sp, color = Color.White, lineHeight = 21.sp),
                        )
                    }
                    // 渲染图片附件（缩略图点击可全屏查看）
                    imageUploads.forEach { upload ->
                        AsyncImage(
                            model = upload.absoluteThumbnailUrl(Config.BASE_URL),
                            contentDescription = upload.originalFilename,
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { fullscreenImage = upload },
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                    // 渲染非图片文件附件
                    fileUploads.forEach { upload ->
                        Text(
                            text = "📎 ${upload.originalFilename}",
                            style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }
    } else {
        // 对方发送的消息靠左展示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 48.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            val avatar = avatarUrlOf(user?.avatarTemplate, 72)
            AsyncImage(
                model = avatar,
                contentDescription = user?.username,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosTheme.colors.fieldBackground)
                    .clickable(enabled = !user?.username.isNullOrBlank()) {
                        user?.username?.let(onOpenUser)
                    },
            )

            Spacer(Modifier.width(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = user?.name?.takeIf { it.isNotBlank() } ?: user?.username.orEmpty(),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = IosTheme.colors.secondaryLabel),
                        modifier = Modifier.clickable(enabled = !user?.username.isNullOrBlank()) {
                            user?.username?.let(onOpenUser)
                        },
                    )
                    Text(
                        text = timeStr,
                        style = TextStyle(fontSize = 11.sp, color = IosTheme.colors.tertiaryLabel),
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(IosTheme.colors.card)
                        .border(
                            0.5.dp,
                            IosTheme.colors.separator.copy(alpha = 0.5f),
                            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (textContent.isNotBlank()) {
                            Text(
                                text = textContent,
                                style = TextStyle(fontSize = 15.sp, color = IosTheme.colors.label, lineHeight = 21.sp),
                            )
                        }
                        // 渲染图片附件（缩略图点击可全屏查看）
                        imageUploads.forEach { upload ->
                            AsyncImage(
                                model = upload.absoluteThumbnailUrl(Config.BASE_URL),
                                contentDescription = upload.originalFilename,
                                modifier = Modifier
                                    .widthIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { fullscreenImage = upload },
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                        // 渲染非图片文件附件
                        fileUploads.forEach { upload ->
                            Text(
                                text = "📎 ${upload.originalFilename}",
                                style = TextStyle(fontSize = 13.sp, color = IosTheme.colors.secondaryLabel),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 实时聊天富文本评论样式输入框 (支持半屏/全屏切换、附件、表情、Markdown 加粗) */
@Composable
private fun ChatComposerBottomSheet(
    channelTitle: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    pendingAttachments: List<AttachedMedia>,
    onRemoveAttachment: (String) -> Unit,
    isUploading: Boolean,
    isSending: Boolean,
    error: String?,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onSend: (String, List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val completionSource = org.linuxdo.android.ui.LocalEditorSuggestionSource.current
    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    var showSheetEmojiPicker by rememberSaveable { mutableStateOf(false) }
    var editorView by remember { mutableStateOf<RichMarkdownEditorView?>(null) }
    val textColorInt = IosTheme.colors.label.toArgb()
    val hintColorInt = IosTheme.colors.tertiaryLabel.toArgb()

    LaunchedEffect(pendingAttachments) {
        editorView?.syncAttachments(pendingAttachments)
    }

    BackHandler(enabled = true) {
        if (isFullScreen) {
            isFullScreen = false
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 半透明暗色背景遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // 底部/全屏抽屉面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        isFullScreen -> Modifier.fillMaxHeight(0.92f)
                        showSheetEmojiPicker -> Modifier.height(420.dp)
                        pendingAttachments.isNotEmpty() || isUploading -> Modifier.height(380.dp)
                        else -> Modifier.height(280.dp)
                    }
                )
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(IosTheme.colors.card)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶部栏: 关闭按钮 ✕ + 标题 + 全屏切换按钮 ⛶
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左侧轻量关闭叉号
                val closeColor = IosTheme.colors.secondaryLabel
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        drawLine(closeColor, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 1.8f, cap = StrokeCap.Round)
                        drawLine(closeColor, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 1.8f, cap = StrokeCap.Round)
                    }
                }

                Text(
                    text = "发送给 $channelTitle",
                    style = IosTheme.type.headline,
                    fontWeight = FontWeight.SemiBold,
                    color = IosTheme.colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )

                // 右上角全屏切换图标
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { isFullScreen = !isFullScreen },
                    contentAlignment = Alignment.Center,
                ) {
                    ChatFullscreenToggleIcon(isFullScreen = isFullScreen, tint = IosTheme.colors.label)
                }
            }

            val editorTextSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.sp.toPx() }
            // 中间输入区域 (圆角灰色底，内嵌原生富文本图文流编辑器)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IosTheme.colors.fieldBackground)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                RichMarkdownEditorView(ctx).apply {
                                    hint = "发送消息…"
                                    updateThemeColors(textColorInt, hintColorInt)
                                    setCompletionSource(completionSource)
                                    setInitialMarkdown(draft, pendingAttachments)
                                    setOnMarkdownChangeListener { newRaw -> onDraftChange(newRaw) }
                                    setOnRemoveAttachmentListener { mediaId -> onRemoveAttachment(mediaId) }
                                    editorView = this
                                    post { requestInputFocus() }
                                }
                            },
                            update = { view ->
                                view.updateThemeColors(textColorInt, hintColorInt)
                                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, editorTextSizePx)
                                view.setCompletionSource(completionSource)
                                view.isEnabled = !isSending
                            },
                        )
                    }

                    // 待发附件列表横向滑动
                    if (pendingAttachments.isNotEmpty() || isUploading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (media in pendingAttachments) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(IosTheme.colors.card)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (media.isImage) {
                                            AsyncImage(
                                                model = media.previewUrl,
                                                contentDescription = media.name,
                                                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                                                contentScale = ContentScale.Crop,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                tint = IosTheme.colors.accent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        Text(
                                            text = media.name,
                                            style = IosTheme.type.caption,
                                            color = IosTheme.colors.label,
                                            maxLines = 1,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除",
                                            tint = IosTheme.colors.secondaryLabel,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clickable { onRemoveAttachment(media.id) },
                                        )
                                    }
                                }
                            }
                            if (isUploading) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(IosTheme.colors.card)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    IosActivityIndicator(diameter = 14.dp, color = IosTheme.colors.accent)
                                    Text("正在上传...", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                                }
                            }
                        }
                    }
                }
            }

            error?.let { Text(it, style = IosTheme.type.footnote, color = IosTheme.colors.destructive, maxLines = 2) }

            // 底部操作栏: 图片, 文件, 表情, B, 以及蓝底发送按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 图片按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isUploading && !isSending, onClick = onPickImage),
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerImageIcon(tint = if (isUploading) IosTheme.colors.tertiaryLabel else IosTheme.colors.label)
                    }

                    // 附件按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isUploading && !isSending, onClick = onPickFile),
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerAttachmentIcon(tint = if (isUploading) IosTheme.colors.tertiaryLabel else IosTheme.colors.label)
                    }

                    // 表情按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (showSheetEmojiPicker) IosTheme.colors.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { showSheetEmojiPicker = !showSheetEmojiPicker },
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerEmojiIcon(tint = if (showSheetEmojiPicker) IosTheme.colors.accent else IosTheme.colors.label)
                    }

                    // 粗体 B
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                editorView?.insertToken("****") ?: run {
                                    onDraftChange(if (draft.endsWith(" ") || draft.isEmpty()) "${draft}****" else "$draft ****")
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "B",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = IosTheme.colors.label,
                            ),
                        )
                    }
                }

                // 蓝底发表/发送按钮
                val canSend = (draft.isNotBlank() || pendingAttachments.isNotEmpty()) && !isUploading && !isSending
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (canSend) IosTheme.colors.accent else IosTheme.colors.accent.copy(alpha = 0.4f))
                        .clickable(enabled = canSend) {
                            val fullText = editorView?.exportToMarkdown()?.trim() ?: draft.trim()
                            val uploadIds = pendingAttachments.mapNotNull { it.uploadId }
                            onSend(fullText, uploadIds)
                        }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSending) {
                        IosActivityIndicator(diameter = 16.dp, color = Color.White)
                    } else {
                        Text(
                            text = "发送",
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        )
                    }
                }
            }

            // 表情快速面板
            if (showSheetEmojiPicker) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(IosTheme.colors.fieldBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    listOf("👍", "😀", "😂", "🤣", "😍", "🎉", "🔥", "🙏", "❤️", "🤔", "👏", "🫡", "👀", "🚀", "🍻", "💯").forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            modifier = Modifier.clickable {
                                editorView?.insertToken(emoji) ?: run { onDraftChange(draft + emoji) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatFullscreenToggleIcon(isFullScreen: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (!isFullScreen) {
                // 四个直角朝外：全屏展开模式
                val p1 = Path().apply { moveTo(3f, 8f); lineTo(3f, 3f); lineTo(8f, 3f) }
                val p2 = Path().apply { moveTo(16f, 3f); lineTo(21f, 3f); lineTo(21f, 8f) }
                val p3 = Path().apply { moveTo(21f, 16f); lineTo(21f, 21f); lineTo(16f, 21f) }
                val p4 = Path().apply { moveTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f) }
                drawPath(p1, tint, style = stroke)
                drawPath(p2, tint, style = stroke)
                drawPath(p3, tint, style = stroke)
                drawPath(p4, tint, style = stroke)
            } else {
                // 四个折角朝内：恢复半屏模式
                val p1 = Path().apply { moveTo(8f, 3f); lineTo(8f, 8f); lineTo(3f, 8f) }
                val p2 = Path().apply { moveTo(16f, 3f); lineTo(16f, 8f); lineTo(21f, 8f) }
                val p3 = Path().apply { moveTo(16f, 21f); lineTo(16f, 16f); lineTo(21f, 16f) }
                val p4 = Path().apply { moveTo(8f, 21f); lineTo(8f, 16f); lineTo(3f, 16f) }
                drawPath(p1, tint, style = stroke)
                drawPath(p2, tint, style = stroke)
                drawPath(p3, tint, style = stroke)
                drawPath(p4, tint, style = stroke)
            }
        }
    }
}

package org.linuxdo.android.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.linuxdo.android.ui.RichMarkdownEditorView
import org.linuxdo.android.ui.LocalEditorSuggestionSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.linuxdo.android.data.AttachedMedia
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosTheme

@Composable
fun PrivateMessageComposeSheet(
    initialRecipient: String = "",
    onDismiss: () -> Unit,
    onSend: suspend (List<String>, String, String) -> Long,
    onUploadAttachment: (suspend (Uri) -> AttachedMedia)? = null,
    onCreated: (Long, String) -> Unit,
) {
    var recipients by remember(initialRecipient) { mutableStateOf(initialRecipient) }
    var title by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<AttachedMedia>>(emptyList()) }
    var isUploading by remember { mutableStateOf(false) }
    var showEmojiPicker by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val completionSource = LocalEditorSuggestionSource.current
    var editorView by remember { mutableStateOf<RichMarkdownEditorView?>(null) }
    val textColor = IosTheme.colors.label.toArgb()
    val hintColor = IosTheme.colors.tertiaryLabel.toArgb()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && onUploadAttachment != null) {
            scope.launch {
                isUploading = true
                try {
                    val media = onUploadAttachment(uri)
                    pendingAttachments = pendingAttachments + media
                } catch (e: Exception) {
                    error = e.message ?: "图片上传失败"
                } finally {
                    isUploading = false
                }
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && onUploadAttachment != null) {
            scope.launch {
                isUploading = true
                try {
                    val media = onUploadAttachment(uri)
                    pendingAttachments = pendingAttachments + media
                } catch (e: Exception) {
                    error = e.message ?: "文件上传失败"
                } finally {
                    isUploading = false
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!sending && !isUploading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(enabled = true) {
            if (!sending && !isUploading) onDismiss()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(IosTheme.colors.card)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !sending && !isUploading,
                ) {
                    Text(
                        "取消",
                        style = TextStyle(fontSize = 16.sp, color = IosTheme.colors.secondaryLabel),
                    )
                }

                Text(
                    text = "新建私信",
                    style = IosTheme.type.headline,
                    fontWeight = FontWeight.SemiBold,
                    color = IosTheme.colors.label,
                )

                val canSend = recipients.isNotBlank() && title.isNotBlank() && (raw.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending && !isUploading
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (canSend) IosTheme.colors.accent else IosTheme.colors.accent.copy(alpha = 0.35f))
                        .clickable(enabled = canSend) {
                            val targets = recipients.split(',', ' ', '\n')
                                .map { it.trim().removePrefix("@") }
                                .filter { it.matches(Regex("[A-Za-z0-9_.-]+")) }
                                .distinct()
                            if (targets.isEmpty() || title.isBlank() || (raw.isBlank() && pendingAttachments.isEmpty())) {
                                error = "请填写收件人、标题和内容"
                                return@clickable
                            }
                            scope.launch {
                                sending = true
                                error = null
                                try {
                                    val unincluded = pendingAttachments.filter { !raw.contains(it.uploaded.shortUrl) }
                                    val gap = if (raw.isBlank() || raw.endsWith("\n")) "" else "\n\n"
                                    val finalContent = if (unincluded.isEmpty()) raw.trim() else "${raw.trim()}$gap${unincluded.joinToString("\n") { it.toMarkdown() }}"
                                    val topicId = onSend(targets, title.trim(), finalContent)
                                    onCreated(topicId, title.trim())
                                    onDismiss()
                                } catch (exception: Exception) {
                                    error = exception.message ?: "私信发送失败"
                                } finally {
                                    sending = false
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) {
                        IosActivityIndicator(diameter = 16.dp, color = Color.White)
                    } else {
                        Text(
                            text = "发送",
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        )
                    }
                }
            }

            IosDivider()

            // 收件人行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "收件人:",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IosTheme.colors.secondaryLabel),
                    modifier = Modifier.width(60.dp),
                )
                BasicTextField(
                    value = recipients,
                    onValueChange = { recipients = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = IosTheme.colors.label),
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (recipients.isEmpty()) {
                                Text("@username (多个用空格隔开)", style = TextStyle(fontSize = 15.sp, color = IosTheme.colors.tertiaryLabel))
                            }
                            innerTextField()
                        }
                    },
                )
            }

            IosDivider(startIndent = 16.dp)

            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "主题:",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IosTheme.colors.secondaryLabel),
                    modifier = Modifier.width(60.dp),
                )
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = IosTheme.colors.label),
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (title.isEmpty()) {
                                Text("私信标题", style = TextStyle(fontSize = 15.sp, color = IosTheme.colors.tertiaryLabel))
                            }
                            innerTextField()
                        }
                    },
                )
            }

            IosDivider()

            // 错误提示
            error?.let {
                Text(
                    text = it,
                    style = IosTheme.type.footnote,
                    color = Color(0xFFFF3B30),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            // 正文内容输入区 (填满剩余空间)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        RichMarkdownEditorView(context).apply {
                            updateThemeColors(textColor, hintColor)
                            setCompletionSource(completionSource)
                            setInitialMarkdown(raw, emptyList())
                            setOnMarkdownChangeListener { raw = it }
                            hint = "在此撰写私信正文，支持 Markdown"
                            editorView = this
                        }
                    },
                    update = {
                        it.updateThemeColors(textColor, hintColor)
                        it.setCompletionSource(completionSource)
                        it.isEnabled = !sending
                    },
                )
            }

            // 待发送附件缩略展示行
            if (pendingAttachments.isNotEmpty() || isUploading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
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

            IosDivider()

            // 底部富文本工具栏: 图片、文件、表情、加粗 B、字数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IosTheme.colors.groupedBackground.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 图片按钮
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isUploading && !sending) {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposerImageIcon(
                        tint = if (isUploading || sending) IosTheme.colors.tertiaryLabel else IosTheme.colors.secondaryLabel,
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 文件按钮
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isUploading && !sending) {
                            pickFile.launch(arrayOf("*/*"))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposerAttachmentIcon(
                        tint = if (isUploading || sending) IosTheme.colors.tertiaryLabel else IosTheme.colors.secondaryLabel,
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 表情按钮
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (showEmojiPicker) IosTheme.colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                        )
                        .clickable(enabled = !isUploading && !sending) {
                            if (!showEmojiPicker) keyboard?.hide()
                            showEmojiPicker = !showEmojiPicker
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposerEmojiIcon(
                        tint = if (showEmojiPicker) IosTheme.colors.accent
                        else if (isUploading || sending) IosTheme.colors.tertiaryLabel
                        else IosTheme.colors.secondaryLabel,
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 粗体 B
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isUploading && !sending) {
                            editorView?.insertToken("**粗体文字**")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposerBoldIcon(
                        tint = if (isUploading || sending) IosTheme.colors.tertiaryLabel else IosTheme.colors.secondaryLabel,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (isUploading) {
                    IosActivityIndicator(diameter = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "上传中…",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                } else {
                    Text(
                        text = "${raw.length} 字",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.tertiaryLabel,
                    )
                }
            }

            // 表情选择面板
            if (showEmojiPicker) {
                IosDivider()
                QuickEmojiPicker(
                    enabled = !isUploading && !sending,
                    onSelect = { emoji ->
                        editorView?.insertToken(emoji)
                    },
                )
            }
        }
    }
}

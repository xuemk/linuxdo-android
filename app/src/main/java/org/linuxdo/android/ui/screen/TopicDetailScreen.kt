package org.linuxdo.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.linuxdo.android.data.Post
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.data.TreePost
import org.linuxdo.android.data.threadRows
import org.linuxdo.android.data.postAncestorIds
import org.linuxdo.android.ui.IosTextAction
import org.linuxdo.android.ui.DetailUiState
import org.linuxdo.android.ui.DetailLoad
import org.linuxdo.android.ui.design.*
import org.linuxdo.android.ui.format.RelativeTime
import org.linuxdo.android.ui.html.CookedBlock
import org.linuxdo.android.ui.html.CookedParser
import org.linuxdo.android.ui.html.InlineText

@Composable
fun TopicDetailScreen(
    title: String,
    state: DetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    onUnknownTag: (String) -> Unit,
    treeView: Boolean,
    onLoadReplies: (Long) -> Unit,
    onReact: (Long, String) -> Unit,
    onBoost: (Long, String) -> Unit,
    onDeleteBoost: (Long, Long) -> Unit,
    onRefreshPost: (Long) -> Unit,
    onSolution: (Long) -> Unit,
    active: Boolean = true,
    initialPostNumber: Int? = null,
    trailing: @Composable () -> Unit = {},
) {
    val list = rememberLazyListState()
    var picture by remember { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var located by rememberSaveable(initialPostNumber) { mutableStateOf(initialPostNumber == null) }
    var highlightedPost by remember { mutableStateOf<Long?>(null) }
    val rows = remember(state.posts, treeView, collapsed) {
        if (treeView) threadRows(state.posts, collapsed.toSet())
        else state.posts.sortedBy { it.postNumber }.map { TreePost(it, 0, 0, false) }
    }
    LaunchedEffect(active, initialPostNumber, state.posts, treeView, located) {
        if (active && !located && treeView && initialPostNumber != null) {
            val ancestors = postAncestorIds(state.posts, initialPostNumber)
            collapsed = collapsed.filterNot { it in ancestors }
        }
    }
    LaunchedEffect(active, initialPostNumber, rows, state.loading, state.error, located, state.previousIds.isNotEmpty()) {
        if (active && !located && !state.loading && state.error == null && initialPostNumber != null) {
            val index = rows.indexOfFirst { it.post.postNumber == initialPostNumber }
            if (index >= 0) {
                list.scrollToItem(index + 1 + if (state.previousIds.isNotEmpty()) 1 else 0)
                highlightedPost = rows[index].post.id
                located = true
            }
        }
    }
    LaunchedEffect(highlightedPost) {
        if (highlightedPost != null) {
            delay(2_000)
            highlightedPost = null
        }
    }
    val nearEnd by remember { derivedStateOf {
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(active, nearEnd, state.loading, state.consumedIds.size, state.error, located) {
        if (active && located && nearEnd && !state.loading && state.error == null &&
            state.posts.isNotEmpty() && state.remaining.isNotEmpty()) onLoadMore()
    }
    val showTopError = !located || state.failedLoad == DetailLoad.Previous
    Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
        IosNavBar(title = state.topic?.title ?: title, onBack = onBack, trailing = trailing)
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            item("title") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.topic?.title ?: title, style = IosTheme.type.title3)
                    state.category?.let { CategoryTag(it) }
                }
            }
            if (showTopError) state.error?.let { message ->
                item("top-error") { ErrorNotice(message, onRetry) }
            }
            if (state.previousIds.isNotEmpty()) item("load-previous") {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    IosTextAction(if (state.loading) "加载中…" else "加载之前的楼层",
                        color = if (state.loading) IosTheme.colors.secondaryLabel else IosTheme.colors.accent,
                        textStyle = IosTheme.type.subheadline) { if (!state.loading) onLoadPrevious() }
                }
            }
            items(rows, key = { it.post.id }) { row ->
                val post = row.post
                Column(Modifier.padding(start = (row.depth.coerceAtMost(3) * 12).dp)) {
                    PostRow(post, onPicture = { picture = it }, onUnknownTag = onUnknownTag,
                        reactions = state.topic?.validReactions.orEmpty(),
                        emojiUrls = state.emojiUrls,
                        busy = state.reactingPosts.isNotEmpty(), error = state.reactionErrors[post.id],
                        onReact = { onReact(post.id, it) }, onBoost = { onBoost(post.id, it) },
                        onDeleteBoost = { onDeleteBoost(post.id, it) }, onRefresh = { onRefreshPost(post.id) },
                        onSolution = { onSolution(post.id) }, highlighted = highlightedPost == post.id)
                    if (treeView) Row(Modifier.fillMaxWidth().background(IosTheme.colors.card)) {
                        if (row.childCount > 0) IosTextAction(if (post.id in collapsed) "展开 ${row.childCount} 条回复" else "收起回复",
                            textStyle = IosTheme.type.subheadline) {
                            collapsed = if (post.id in collapsed) collapsed - post.id else collapsed + post.id
                        }
                        if (post.replyCount > row.childCount && post.id !in state.completedReplies) {
                            IosTextAction(if (state.loading) "加载中…" else "更多回复", textStyle = IosTheme.type.subheadline) {
                                collapsed = collapsed - post.id
                                onLoadReplies(post.id)
                            }
                        }
                    }
                    IosDivider()
                }
            }
            if (state.loading) item("loading") { IosLoadingBox() }
            if (!showTopError) state.error?.let { message -> item("error") { ErrorNotice(message, onRetry) } }
            if (!state.loading && state.topic != null && state.posts.isEmpty() && state.error == null) {
                item("empty") { EmptyNotice() }
            }
            if (state.posts.isNotEmpty() && state.remaining.isEmpty() && !state.loading && state.error == null) {
                item("end") { Text(if (state.previousIds.isEmpty()) "已显示全部可见楼层" else "已到话题末尾",
                    color = IosTheme.colors.secondaryLabel,
                    style = IosTheme.type.footnote, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
    picture?.let { ImageViewer(it) { picture = null } }
}

@Composable
private fun PostRow(post: Post, onPicture: (String) -> Unit, onUnknownTag: (String) -> Unit,
    reactions: List<String>, emojiUrls: Map<String, String>, busy: Boolean, error: String?, onReact: (String) -> Unit,
    onBoost: (String) -> Unit, onDeleteBoost: (Long) -> Unit, onRefresh: () -> Unit, onSolution: () -> Unit,
    highlighted: Boolean = false) {
    val blocks by produceState<List<CookedBlock>?>(null, post.cooked) {
        value = withContext(Dispatchers.Default) { CookedParser(onUnknownTag).parse(post.cooked) }
    }
    val background = if (highlighted) IosTheme.colors.accent.copy(alpha = 0.12f) else IosTheme.colors.card
    Column(Modifier.fillMaxWidth().background(background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(avatarUrlOf(post.avatarTemplate), post.username,
                modifier = Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(post.username, style = IosTheme.type.subheadline, fontWeight = FontWeight.SemiBold)
                Text(RelativeTime.format(post.createdAt), style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
            }
            Text("#${post.postNumber}", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
        }
        post.replyToPostNumber?.takeIf { it > 0 }?.let { parent ->
            Text("回复 #$parent", style = IosTheme.type.caption, color = IosTheme.colors.accent)
        }
        if (post.acceptedAnswer) Text("✓ 解决方案", style = IosTheme.type.footnote, color = Color(0xFF30B85B))
        if (blocks == null) IosLoadingBox() else CookedContent(blocks.orEmpty(), onPicture)
        PostActions(post, reactions, emojiUrls, busy, error, onReact, onBoost, onDeleteBoost, onRefresh, onSolution)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
private fun PostActions(post: Post, reactions: List<String>, emojiUrls: Map<String, String>, busy: Boolean, error: String?,
    onReact: (String) -> Unit, onBoost: (String) -> Unit, onDeleteBoost: (Long) -> Unit, onRefresh: () -> Unit, onSolution: () -> Unit) {
    var chooseReaction by remember { mutableStateOf(false) }
    var composeBoost by remember { mutableStateOf(false) }
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    var visibleBoosts by rememberSaveable(post.id) { mutableIntStateOf(8) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(post.canBoost) { if (!post.canBoost) { composeBoost = false; draft = "" } }
    if (post.reactions.any { it.count > 0 }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            post.reactions.filter { it.count > 0 }.forEach { reaction ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReactionIcon(reaction.id, emojiUrls)
                    Text(" ${reaction.count}", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (busy) IosActivityIndicator()
        if (post.canAcceptAnswer || post.canUnacceptAnswer || post.acceptedAnswer) {
            Box(Modifier.size(44.dp).semantics { contentDescription = if (post.acceptedAnswer) "取消解决方案" else "设为解决方案" }
                .clickable(enabled = !busy && (post.canAcceptAnswer || post.canUnacceptAnswer), onClick = onSolution),
                contentAlignment = Alignment.Center) {
                PostActionIcon(rocket = false, selected = post.acceptedAnswer, solution = true)
            }
        }
        val selected = post.currentUserReaction?.id
        val canReact = !busy && !post.yours && (selected == null || post.currentUserReaction?.canUndo == true)
        Box(Modifier.size(44.dp).semantics { contentDescription = "点赞，长按选择表情" }
            .combinedClickable(enabled = canReact, onClick = { onReact("heart") }, onLongClick = { chooseReaction = true }),
            contentAlignment = Alignment.Center) {
            PostActionIcon(rocket = false, selected = selected != null)
        }
        if (post.canBoost) Box(Modifier.size(44.dp).semantics { contentDescription = "写简评" }
            .clickable(enabled = !busy) { composeBoost = !composeBoost }, contentAlignment = Alignment.Center) {
            PostActionIcon(rocket = true, selected = composeBoost)
        }
    }
    error?.let {
        Text(it, style = IosTheme.type.footnote, color = Color(0xFFFF453A))
        IosTextAction("刷新状态", textStyle = IosTheme.type.footnote, onClick = onRefresh)
    }
    if (composeBoost) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(IosTheme.colors.fieldBackground).padding(12.dp)) {
        BasicTextField(draft, onValueChange = { if (it.length <= 1000) draft = it },
            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label), modifier = Modifier.fillMaxWidth(),
            decorationBox = { field -> Box {
                if (draft.isEmpty()) Text("写一句简评…", color = IosTheme.colors.secondaryLabel)
                field()
            } })
        Text("最多 16 个可见字符、5 个表情", style = IosTheme.type.caption,
            color = IosTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 40.dp).clickable { composeBoost = false }, contentAlignment = Alignment.Center) {
                Text("取消", style = IosTheme.type.subheadline, color = IosTheme.colors.accent)
            }
            Box(Modifier.size(width = 64.dp, height = 40.dp).clickable(enabled = draft.isNotBlank() && !busy) { onBoost(draft) },
                contentAlignment = Alignment.Center) {
                Text("发送", style = IosTheme.type.subheadline,
                    color = if (draft.isNotBlank() && !busy) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel)
            }
        }
    }
    if (post.boosts.isNotEmpty()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        post.boosts.take(visibleBoosts).forEach { boost ->
            key(boost.id) {
                val content by produceState<List<CookedBlock>>(emptyList(), boost.cooked) {
                    value = withContext(Dispatchers.Default) { CookedParser().parse(boost.cooked) }
                }
                Row(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(50))
                    .background(IosTheme.colors.fieldBackground)
                    .combinedClickable(enabled = boost.canDelete && !busy, onClick = {}, onLongClick = { deleteId = boost.id })
                    .padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(avatarUrlOf(boost.user?.avatarTemplate), boost.user?.username,
                        modifier = Modifier.size(18.dp).clip(CircleShape))
                    Box(Modifier.padding(start = 4.dp, end = 3.dp)) {
                        RichParagraph(content.filterIsInstance<CookedBlock.Paragraph>().flatMap { it.runs }, compact = true)
                    }
                }
            }
        }
      }
        if (visibleBoosts < post.boosts.size) IosTextAction("更多简评 (${post.boosts.size - visibleBoosts})", textStyle = IosTheme.type.caption) { visibleBoosts += 20 }
        else if (visibleBoosts > 8) IosTextAction("收起简评", textStyle = IosTheme.type.caption) { visibleBoosts = 8 }
    }
    if (chooseReaction) Dialog(onDismissRequest = { chooseReaction = false }) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(IosTheme.colors.card).padding(16.dp)) {
            Text("选择回应", style = IosTheme.type.title3, modifier = Modifier.padding(bottom = 12.dp))
            (listOf("heart") + reactions).distinct().chunked(5).forEach { group ->
                Row {
                    group.forEach { reaction ->
                        Box(Modifier.clickable(enabled = !busy) { chooseReaction = false; onReact(reaction) }.padding(10.dp)) {
                            ReactionIcon(reaction, emojiUrls)
                        }
                    }
                }
            }
            IosTextAction("取消") { chooseReaction = false }
        }
    }
    deleteId?.let { id -> Dialog(onDismissRequest = { deleteId = null }) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(IosTheme.colors.card).padding(20.dp)) {
            Text("删除这条简评？", style = IosTheme.type.title3)
            Row {
                IosTextAction("取消") { deleteId = null }
                IosTextAction("删除", Color(0xFFFF453A)) { deleteId = null; onDeleteBoost(id) }
            }
        }
    } }
}

@Composable
private fun ReactionIcon(name: String, urls: Map<String, String>) {
    AsyncImage(urls[name] ?: "https://linux.do/images/emoji/twemoji/$name.png?v=15", name, modifier = Modifier.size(20.dp))
}

@Composable
private fun PostActionIcon(rocket: Boolean, selected: Boolean, solution: Boolean = false) {
    val color = if (selected && solution) Color(0xFF30B85B) else if (selected) IosTheme.colors.accent else IosTheme.colors.secondaryLabel
    Canvas(Modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val shape = Path()
            if (solution) {
                drawCircle(color, 4.5f, Offset(7.5f, 7.5f), style = Stroke(1.7f))
                drawLine(color, Offset(11f, 11f), Offset(21f, 21f), 1.7f)
                drawLine(color, Offset(15f, 15f), Offset(18f, 12f), 1.7f)
                drawLine(color, Offset(18f, 18f), Offset(21f, 15f), 1.7f)
            } else if (!rocket) {
                shape.moveTo(12f, 21f)
                shape.cubicTo(10f, 19f, 3f, 13f, 3f, 8f)
                shape.cubicTo(3f, 2f, 10f, 1f, 12f, 6f)
                shape.cubicTo(14f, 1f, 21f, 2f, 21f, 8f)
                shape.cubicTo(21f, 13f, 14f, 19f, 12f, 21f)
                shape.close()
                if (selected) drawPath(shape, color) else drawPath(shape, color, style = Stroke(1.6f))
            } else {
                shape.moveTo(9f, 15f); shape.lineTo(9f, 10f)
                shape.cubicTo(12f, 5f, 16f, 3f, 21f, 3f)
                shape.cubicTo(21f, 8f, 19f, 12f, 14f, 15f)
                shape.close()
                shape.moveTo(9f, 10f); shape.lineTo(5f, 10f); shape.lineTo(3f, 15f); shape.lineTo(9f, 15f)
                shape.moveTo(14f, 15f); shape.lineTo(14f, 19f); shape.lineTo(9f, 21f); shape.lineTo(9f, 15f)
                drawPath(shape, color, style = Stroke(1.6f))
                drawCircle(color, 1.8f, Offset(16f, 8f), style = Stroke(1.4f))
                drawLine(color, Offset(6f, 18f), Offset(3f, 21f), 1.6f)
            }
        }
    }
}

@Composable
private fun CookedContent(blocks: List<CookedBlock>, onPicture: (String) -> Unit, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is CookedBlock.Paragraph -> RichParagraph(block.runs, compact)
                is CookedBlock.Code -> SelectionContainer {
                    Text(block.text, fontFamily = FontFamily.Monospace, style = IosTheme.type.footnote,
                        modifier = Modifier.fillMaxWidth().background(IosTheme.colors.fieldBackground)
                            .horizontalScroll(rememberScrollState()).padding(12.dp))
                }
                is CookedBlock.Quote -> Row(Modifier.fillMaxWidth().background(IosTheme.colors.fieldBackground)) {
                    Box(Modifier.width(3.dp).height(28.dp).background(IosTheme.colors.separator))
                    Box(Modifier.weight(1f).padding(10.dp)) { CookedContent(block.blocks, onPicture) }
                }
                is CookedBlock.Picture -> Column {
                    AsyncImage(block.url, block.description, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 360.dp)
                            .clickable { onPicture(block.url) })
                    Text("点按查看图片", style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier.clickable { onPicture(block.url) }.padding(vertical = 4.dp))
                }
                is CookedBlock.ListBlock -> block.entries.forEachIndexed { index, entry ->
                    Row {
                        Text(block.start?.let { "${it + index}. " } ?: "• ", modifier = Modifier.widthIn(min = 22.dp))
                        Box(Modifier.weight(1f)) { CookedContent(entry, onPicture) }
                    }
                }
                CookedBlock.Divider -> IosFullDivider()
            }
        }
    }
}

@Composable
private fun RichParagraph(runs: List<InlineText>, compact: Boolean = false) {
    val colors = IosTheme.colors
    val uriHandler = LocalUriHandler.current
    val annotated = remember(runs, colors, uriHandler) {
        buildAnnotatedString {
            runs.forEach { run ->
                val emoji = run.emojiUrl
                if (emoji != null) {
                    appendInlineContent(emoji, run.text.ifBlank { "表情" })
                    return@forEach
                }
                withStyle(SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) colors.fieldBackground else Color.Unspecified,
                )) {
                    val link = run.href
                    if (link == null) append(run.text) else withLink(LinkAnnotation.Url(
                        link, TextLinkStyles(style = SpanStyle(color = colors.accent)),
                        linkInteractionListener = { runCatching { uriHandler.openUri(link) } },
                    )) { append(run.text) }
                }
            }
        }
    }
    val inline = runs.mapNotNull { it.emojiUrl }.distinct().associateWith { url ->
        val size = if (compact) 14.sp else 20.sp
        InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.TextCenter)) {
            AsyncImage(url, "表情", modifier = Modifier.fillMaxSize())
        }
    }
    if (compact) Text(annotated, style = IosTheme.type.caption, inlineContent = inline)
    else SelectionContainer { Text(annotated, style = IosTheme.type.body, inlineContent = inline) }
}

@Composable
private fun ImageViewer(url: String, onDismiss: () -> Unit) {
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(url) {
            detectTransformGestures { _, pan, scale, _ ->
                zoom = (zoom * scale).coerceIn(1f, 5f)
                offset = if (zoom > 1f) offset + pan else Offset(0f, (offset.y + pan.y).coerceAtLeast(0f))
                if (zoom == 1f && offset.y > 100.dp.toPx()) onDismiss()
            }
        }) {
            AsyncImage(url, "图片预览", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                })
            Text("关闭", color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().clickable(onClick = onDismiss).padding(20.dp))
        }
    }
}

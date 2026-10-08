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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
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
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.linuxdo.android.App
import org.linuxdo.android.data.Post
import org.linuxdo.android.data.BoostLength
import org.linuxdo.android.data.measureBoost
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.data.TreePost
import org.linuxdo.android.data.threadRows
import org.linuxdo.android.data.postAncestorIds
import org.linuxdo.android.ui.IosTextAction
import org.linuxdo.android.ui.ComposerTarget
import org.linuxdo.android.ui.ComposerUiState
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
    composer: ComposerUiState,
    onOpenComposer: (ComposerTarget) -> Unit,
    onDraftChange: (String) -> Unit,
    onSubmitComposer: () -> Unit,
    onCloseComposer: () -> Unit,
    onDeletePost: (Long) -> Unit,
    onReloadFromStart: () -> Unit,
    onCreatedPostShown: () -> Unit,
    onAttach: (Uri) -> Unit,
    topicId: Long,
    active: Boolean = true,
    initialPostNumber: Int? = null,
    trailing: @Composable () -> Unit = {},
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var picture by remember { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var located by rememberSaveable(initialPostNumber) { mutableStateOf(initialPostNumber == null) }
    var highlightedPost by remember { mutableStateOf<Long?>(null) }
    var manageTarget by remember { mutableStateOf<Post?>(null) }
    var deleteTarget by remember { mutableStateOf<Post?>(null) }
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
    // 楼层进度取"最靠下的可见楼层",和网页端的阅读进度一致。
    // 用 item key(post.id) 反查,而不是用列表下标 —— 标题、错误条、"加载之前的楼层"
    // 都会挤占下标,算偏移量很容易错。
    val currentPostNumber by remember(rows) { derivedStateOf {
        val visible = list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet()
        rows.lastOrNull { it.post.id in visible }?.post?.postNumber
    } }
    val totalPosts = state.topic?.postStream?.stream?.size ?: 0
    val firstPostLoaded = state.posts.any { it.postNumber == 1 }
    val showTopError = !located || state.failedLoad == DetailLoad.Previous
    val canReply = state.topic?.canReply == true
    // 写操作全局互斥(ViewModel 侧也是),所以其他楼层的按钮要禁用;
    // 但转圈只画在真正被操作的那一条上,否则点一下"解决方案"整页都在转。
    val writeBusy = state.reactingPosts.isNotEmpty() || composer.submitting
    // 树形视图下,刚发的回复会挂在父楼层之下 —— 父楼层若是折叠的,用户会以为回复失败。
    var lastTarget by remember { mutableStateOf<ComposerTarget?>(null) }
    LaunchedEffect(composer.target) {
        val previous = lastTarget
        lastTarget = composer.target
        if (composer.target == null && previous is ComposerTarget.ReplyTo) {
            state.posts.firstOrNull { it.postNumber == previous.postNumber }
                ?.let { parent -> collapsed = collapsed - parent.id }
        }
    }
    val hasPinnedRoot = state.previousIds.isNotEmpty() &&
        rows.firstOrNull()?.post?.postNumber == 1 &&
        rows.size > 1
    val headRows = if (hasPinnedRoot) rows.take(1) else emptyList()
    val bodyRows = if (hasPinnedRoot) rows.drop(1) else rows

    fun lazyIndexOf(rowIndex: Int): Int {
        val topErrorCount = if (showTopError && state.error != null) 1 else 0
        val gapBefore = if (state.previousIds.isNotEmpty() && (!hasPinnedRoot || rowIndex >= 1)) 1 else 0
        return 1 + topErrorCount + gapBefore + rowIndex
    }

    // 向上回填前序切片时锁定当前首个可见楼层锚点，新楼层前置插入后在同一帧恢复视口位置，防止跳屏。
    var upwardAnchor by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var upwardPrevRowCount by remember { mutableIntStateOf(0) }
    val triggerLoadPrevious = {
        if (!state.loading && state.previousIds.isNotEmpty()) {
            val firstPostId = rows.firstOrNull { it.post.postNumber == 1 }?.post?.id
            val anchorItem = list.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                val key = info.key as? Long ?: return@firstOrNull false
                key != firstPostId
            }
            if (anchorItem != null) {
                upwardAnchor = (anchorItem.key as Long) to anchorItem.offset
                upwardPrevRowCount = rows.size
            }
            onLoadPrevious()
        }
    }
    LaunchedEffect(rows) {
        val anchor = upwardAnchor ?: return@LaunchedEffect
        if (rows.size > upwardPrevRowCount) {
            val idx = rows.indexOfFirst { it.post.id == anchor.first }
            if (idx >= 0) {
                list.scrollToItem(lazyIndexOf(idx), (-anchor.second).coerceAtLeast(0))
            }
            upwardAnchor = null
        }
    }
    val nearGapFromBelow by remember(rows, state.previousIds.size) {
        derivedStateOf {
            val info = list.layoutInfo.visibleItemsInfo
            val hasGap = info.any { it.key == "load-previous" }
            val firstPostId = rows.firstOrNull { it.post.postNumber == 1 }?.post?.id
            val firstPostAtTop = firstPostId != null && info.firstOrNull()?.key == firstPostId
            hasGap && !firstPostAtTop && list.isScrollInProgress
        }
    }
    LaunchedEffect(active, nearGapFromBelow, state.loading, state.previousIds.size, state.error) {
        if (active && nearGapFromBelow && !state.loading && state.error == null && state.previousIds.isNotEmpty()) {
            triggerLoadPrevious()
        }
    }

    // 发完回复一步到位直接定格在新楼层并高亮（与 Web 端渲染完直达底部体验一致，不做长距离逐层滚动动画）。
    LaunchedEffect(state.createdPostId, rows) {
        val created = state.createdPostId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.post.id == created }
        if (index < 0) return@LaunchedEffect
        highlightedPost = created
        try {
            list.scrollToItem(lazyIndexOf(index))
        } finally {
            onCreatedPostShown()
        }
    }

    @Composable
    fun RenderPostEntry(row: TreePost) {
        val post = row.post
        Column(Modifier.padding(start = (row.depth.coerceAtMost(3) * 12).dp)) {
            PostRow(post, onPicture = { picture = it }, onUnknownTag = onUnknownTag,
                reactions = state.topic?.validReactions.orEmpty(),
                emojiUrls = state.emojiUrls,
                busy = writeBusy, pending = post.id in state.reactingPosts,
                error = state.reactionErrors[post.id],
                onReact = { onReact(post.id, it) }, onBoost = { onBoost(post.id, it) },
                onDeleteBoost = { onDeleteBoost(post.id, it) }, onRefresh = { onRefreshPost(post.id) },
                onSolution = { onSolution(post.id) }, highlighted = highlightedPost == post.id,
                canReply = canReply,
                onReply = { onOpenComposer(ComposerTarget.ReplyTo(topicId, post.postNumber, post.username)) },
                onManage = { manageTarget = post })
            if (treeView) Row(Modifier.fillMaxWidth().background(IosTheme.colors.card)) {
                if (row.childCount > 0) IosTextAction(if (post.id in collapsed) "展开 ${row.childCount} 条回复" else "收起回复",
                    textStyle = IosTheme.type.subheadline) {
                    collapsed = if (post.id in collapsed) collapsed - post.id else collapsed + post.id
                }
                if (post.replyCount > row.childCount && post.id !in state.completedReplies) {
                    val replyLoading = post.id in state.loadingReplies
                    IosTextAction(if (replyLoading) "加载中…" else "更多回复", textStyle = IosTheme.type.subheadline) {
                        if (!replyLoading) {
                            collapsed = collapsed - post.id
                            onLoadReplies(post.id)
                        }
                    }
                }
            }
            IosDivider()
        }
    }

    Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
        IosNavBar(title = state.topic?.title ?: title, onBack = onBack, trailing = trailing)
        Box(Modifier.weight(1f)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item("title") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.topic?.title ?: title, style = IosTheme.type.title3)
                    state.category?.let { CategoryTag(it) }
                }
            }
            if (showTopError) state.error?.let { message ->
                item("top-error") { ErrorNotice(message, onRetry) }
            }
            items(headRows, key = { it.post.id }) { row -> RenderPostEntry(row) }
            if (state.previousIds.isNotEmpty()) item("load-previous") {
                PreviousSliceGap(
                    hiddenCount = state.previousIds.size,
                    loading = state.loading,
                    onClick = triggerLoadPrevious,
                )
            }
            items(bodyRows, key = { it.post.id }) { row -> RenderPostEntry(row) }
            if (state.loading && (state.posts.isEmpty() || state.remaining.isNotEmpty())) {
                item("loading") { IosLoadingBox() }
            }
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
            // 右下角纵向排列:回复按钮在上、阅读进度在下。用 Column 堆叠而不是各自
            // align(BottomEnd),这样回复按钮永远压不到进度数字上。
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (canReply) ReplyFab(enabled = !writeBusy) {
                    onOpenComposer(ComposerTarget.NewReply(topicId))
                }
                if (currentPostNumber != null && totalPosts > 1) {
                    ReadingProgressPill(current = currentPostNumber ?: 1, total = totalPosts) {
                        // 1 楼还没取回来时(从通知直接跳进中间楼层),先从头重拉话题。
                        if (firstPostLoaded) scope.launch { list.animateScrollToItem(0) } else onReloadFromStart()
                    }
                }
            }
        }
    }
    picture?.let { ImageViewer(it) { picture = null } }
    if (composer.open) ComposerDialog(composer, onDraftChange, onSubmitComposer, onCloseComposer, onAttach)
    manageTarget?.let { post ->
        PostManageDialog(post, onDismiss = { manageTarget = null },
            onEdit = {
                manageTarget = null
                onOpenComposer(ComposerTarget.Edit(topicId, post.id, post.postNumber))
            },
            onDelete = { manageTarget = null; deleteTarget = post })
    }
    deleteTarget?.let { post ->
        DeleteConfirmDialog(post, onDismiss = { deleteTarget = null }) {
            deleteTarget = null
            onDeletePost(post.id)
        }
    }
}

/**
 * Discourse 风格的前序未加载楼层折叠条 + 骨架屏（Skeleton Placeholder）。
 * 1 楼与尾部切片之间的未加载楼层收拢在此，向上滑入或点击时展示骨架屏并按 20 楼一批向上回填。
 */
@Composable
private fun PreviousSliceGap(hiddenCount: Int, loading: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (loading) {
            repeat(2) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(IosTheme.colors.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(IosTheme.colors.separator),
                        )
                        Column(
                            Modifier.padding(start = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(width = 96.dp, height = 12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(IosTheme.colors.separator),
                            )
                            Box(
                                Modifier
                                    .size(width = 64.dp, height = 10.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(IosTheme.colors.fieldBackground),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(0.92f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(IosTheme.colors.separator),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.68f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(IosTheme.colors.fieldBackground),
                    )
                }
                IosDivider()
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(IosTheme.colors.groupedBackground)
                .clickable(enabled = !loading, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                IosActivityIndicator(diameter = 16.dp)
                Text(
                    "正在加载前序楼层（剩余 $hiddenCount 层）…",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(
                    "↑ 中间省略 $hiddenCount 层 · 上滑或点击加载",
                    style = IosTheme.type.subheadline,
                    color = IosTheme.colors.accent,
                )
            }
        }
        IosDivider()
    }
}

/** 长按楼层头部弹出的操作面板:左编辑、右删除。删除还要再过一道确认。 */
@Composable
private fun PostManageDialog(post: Post, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(IosTheme.colors.card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("#${post.postNumber} · ${post.username}", style = IosTheme.type.subheadline,
                color = IosTheme.colors.secondaryLabel, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (post.canEdit) ManageAction("编辑", PostAction.Edit, IosTheme.colors.accent, onEdit)
                if (post.canDelete) ManageAction("删除", PostAction.Delete, IosTheme.colors.destructive, onDelete)
            }
            IosTextAction("取消", color = IosTheme.colors.secondaryLabel,
                textStyle = IosTheme.type.subheadline, onClick = onDismiss)
        }
    }
}

@Composable
private fun ManageAction(label: String, action: PostAction, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PostActionIcon(action, selected = false, tint = color)
        Text(label, style = IosTheme.type.subheadline, color = color)
    }
}

/** 回复整个话题的入口。做成圆形纯图标,和进度胶囊上下错开,互不遮挡。 */
@Composable
private fun ReplyFab(enabled: Boolean, onClick: () -> Unit) {
    val background = if (enabled) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "回复这个话题" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val arrow = Path().apply {
                    moveTo(10f, 5f); lineTo(3f, 11f); lineTo(10f, 17f)
                    lineTo(10f, 13f); cubicTo(17f, 12f, 19f, 15f, 21f, 20f)
                    cubicTo(21f, 10f, 17f, 8f, 10f, 9f); close()
                }
                drawPath(arrow, Color.White)
            }
        }
    }
}

/** 右下角的阅读进度。兼作"回到 1 楼"的按钮,省掉一个额外悬浮控件。 */
@Composable
private fun ReadingProgressPill(current: Int, total: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val arrowColor = IosTheme.colors.accent
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(IosTheme.colors.card.copy(alpha = 0.95f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(13.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                drawLine(arrowColor, Offset(12f, 21f), Offset(12f, 5f), 2f)
                drawLine(arrowColor, Offset(12f, 4f), Offset(5f, 11f), 2f)
                drawLine(arrowColor, Offset(12f, 4f), Offset(19f, 11f), 2f)
            }
        }
        Text("$current / $total", style = IosTheme.type.caption,
            color = IosTheme.colors.secondaryLabel, modifier = Modifier.padding(start = 5.dp))
    }
}

/** 回复 / 编辑共用的输入弹层。 */
@Composable
private fun ComposerDialog(
    composer: ComposerUiState,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onAttach: (Uri) -> Unit,
) {
    val target = composer.target ?: return
    val heading = when (target) {
        is ComposerTarget.NewReply -> "回复话题"
        is ComposerTarget.ReplyTo -> "回复 @${target.username} 的 #${target.postNumber}"
        is ComposerTarget.Edit -> "编辑 #${target.postNumber}"
    }
    val focus = remember { FocusRequester() }
    // 系统相册选择器和 SAF 文件选择器都不需要声明存储权限。
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onAttach)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onAttach)
    }
    val busy = composer.submitting || composer.uploading
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(IosTheme.colors.card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(heading, style = IosTheme.type.subheadline, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
                    .clip(RoundedCornerShape(10.dp)).background(IosTheme.colors.fieldBackground)
                    // 输入框高度有上限,长文必须能在容器内滚动,否则后面输入的内容会被裁掉看不见。
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                if (composer.loadingDraft) IosLoadingBox() else {
                    BasicTextField(
                        value = composer.draft,
                        onValueChange = onDraftChange,
                        enabled = !composer.submitting,
                        textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                        cursorBrush = SolidColor(IosTheme.colors.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { field ->
                            Box {
                                if (composer.draft.isEmpty()) {
                                    Text("支持 Markdown，输入内容…", style = IosTheme.type.body,
                                        color = IosTheme.colors.tertiaryLabel)
                                }
                                field()
                            }
                        },
                    )
                }
            }
            composer.error?.let { Text(it, style = IosTheme.type.footnote, color = IosTheme.colors.destructive) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically) {
                IosTextAction("图片", textStyle = IosTheme.type.subheadline,
                    color = if (busy) IosTheme.colors.tertiaryLabel else IosTheme.colors.accent) {
                    if (!busy) pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
                IosTextAction("附件", textStyle = IosTheme.type.subheadline,
                    color = if (busy) IosTheme.colors.tertiaryLabel else IosTheme.colors.accent) {
                    // 放开到 */* 再由 UploadLimits 按扩展名拦:站方白名单里 zip/7z/md 这些
                    // 在各设备上的 MIME 映射并不一致,用 MIME 过滤反而会漏掉合法文件。
                    if (!busy) pickFile.launch(arrayOf("*/*"))
                }
                Spacer(Modifier.weight(1f))
                if (composer.uploading) {
                    IosActivityIndicator(diameter = 18.dp)
                    Text("上传中…", style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (composer.submitting) IosActivityIndicator(diameter = 18.dp)
                IosTextAction("取消", color = IosTheme.colors.secondaryLabel,
                    textStyle = IosTheme.type.subheadline) { if (!composer.submitting) onClose() }
                IosTextAction(
                    if (target is ComposerTarget.Edit) "保存" else "发送",
                    color = if (composer.canSubmit) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel,
                    textStyle = IosTheme.type.subheadline,
                ) { if (composer.canSubmit) onSubmit() }
            }
        }
    }
    LaunchedEffect(target) {
        if (target !is ComposerTarget.Edit) runCatching { focus.requestFocus() }
    }
}

@Composable
private fun DeleteConfirmDialog(post: Post, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    // 删掉 1 楼等于删掉整个话题,这个后果必须写在确认框里。
    val isTopicRoot = post.postNumber <= 1
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(IosTheme.colors.card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (isTopicRoot) "删除整个话题？" else "删除 #${post.postNumber}？", style = IosTheme.type.title3)
            Text(
                if (isTopicRoot) "这是 1 楼，删除后整个话题及其下所有回复都会被移除。"
                else "删除后这条回复将不再显示。",
                style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IosTextAction("取消", color = IosTheme.colors.secondaryLabel, onClick = onDismiss)
                IosTextAction("删除", IosTheme.colors.destructive, onClick = onConfirm)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PostRow(post: Post, onPicture: (String) -> Unit, onUnknownTag: (String) -> Unit,
    reactions: List<String>, emojiUrls: Map<String, String>, busy: Boolean, pending: Boolean,
    error: String?, onReact: (String) -> Unit,
    onBoost: (String) -> Unit, onDeleteBoost: (Long) -> Unit, onRefresh: () -> Unit, onSolution: () -> Unit,
    canReply: Boolean, onReply: () -> Unit, onManage: () -> Unit,
    highlighted: Boolean = false) {
    val blocks by produceState<List<CookedBlock>?>(null, post.cooked) {
        value = withContext(Dispatchers.Default) { CookedParser(onUnknownTag).parse(post.cooked) }
    }
    val background = if (highlighted) IosTheme.colors.accent.copy(alpha = 0.12f) else IosTheme.colors.card
    val canManage = post.canEdit || post.canDelete
    Column(Modifier.fillMaxWidth().background(background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 长按放在头部这一行,而不是整条楼层:正文裹在 SelectionContainer 里,
        // 长按那里是"选择文本",两个手势会抢。
        Row(
            Modifier.fillMaxWidth().then(
                if (canManage) Modifier
                    .semantics { contentDescription = "长按 #${post.postNumber} 可编辑或删除" }
                    .combinedClickable(enabled = !busy, onClick = {}, onLongClick = onManage)
                else Modifier,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        PostActions(post, reactions, emojiUrls, busy, pending, error, onReact, onBoost, onDeleteBoost,
            onRefresh, onSolution, canReply, onReply)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
private fun PostActions(post: Post, reactions: List<String>, emojiUrls: Map<String, String>,
    busy: Boolean, pending: Boolean, error: String?,
    onReact: (String) -> Unit, onBoost: (String) -> Unit, onDeleteBoost: (Long) -> Unit,
    onRefresh: () -> Unit, onSolution: () -> Unit,
    canReply: Boolean, onReply: () -> Unit) {
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
        // 只有这一条楼层在提交时才转圈。
        if (pending) IosActivityIndicator()
        if (post.canAcceptAnswer || post.canUnacceptAnswer || post.acceptedAnswer) {
            Box(Modifier.size(44.dp).semantics { contentDescription = if (post.acceptedAnswer) "取消解决方案" else "设为解决方案" }
                .clickable(enabled = !busy && (post.canAcceptAnswer || post.canUnacceptAnswer), onClick = onSolution),
                contentAlignment = Alignment.Center) {
                PostActionIcon(PostAction.Solution, selected = post.acceptedAnswer)
            }
        }
        val selected = post.currentUserReaction?.id
        val canReact = !busy && !post.yours && (selected == null || post.currentUserReaction?.canUndo == true)
        Box(Modifier.size(44.dp).semantics { contentDescription = "点赞，长按选择表情" }
            .combinedClickable(enabled = canReact, onClick = { onReact("heart") }, onLongClick = { chooseReaction = true }),
            contentAlignment = Alignment.Center) {
            PostActionIcon(PostAction.Like, selected = selected != null)
        }
        if (post.canBoost) Box(Modifier.size(44.dp).semantics { contentDescription = "写简评" }
            .clickable(enabled = !busy) { composeBoost = !composeBoost }, contentAlignment = Alignment.Center) {
            PostActionIcon(PostAction.Boost, selected = composeBoost)
        }
        if (canReply) Box(Modifier.size(44.dp).semantics { contentDescription = "回复这条内容" }
            .clickable(enabled = !busy, onClick = onReply), contentAlignment = Alignment.Center) {
            PostActionIcon(PostAction.Reply, selected = false)
        }
    }
    error?.let {
        Text(it, style = IosTheme.type.footnote, color = Color(0xFFFF453A))
        IosTextAction("刷新状态", textStyle = IosTheme.type.footnote, onClick = onRefresh)
    }
    if (composeBoost) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(IosTheme.colors.fieldBackground).padding(12.dp)) {
        val length = remember(draft) { measureBoost(draft) }
        val over = length.overVisible || length.overEmoji
        BasicTextField(draft, onValueChange = { if (it.length <= BoostLength.RAW_INPUT_CAP) draft = it },
            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label), modifier = Modifier.fillMaxWidth(),
            decorationBox = { field -> Box {
                if (draft.isEmpty()) Text("写一句简评…", color = IosTheme.colors.secondaryLabel)
                field()
            } })
        // 实时计数:服务端对两项分别设限,写超了要让用户当场看见,而不是提交后才报错。
        Text("可见 ${length.visible}/${BoostLength.MAX_VISIBLE} · 表情 ${length.emoji}/${BoostLength.MAX_EMOJI}",
            style = IosTheme.type.caption,
            color = if (over) IosTheme.colors.destructive else IosTheme.colors.secondaryLabel,
            modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 40.dp).clickable { composeBoost = false }, contentAlignment = Alignment.Center) {
                Text("取消", style = IosTheme.type.subheadline, color = IosTheme.colors.accent)
            }
            val canSend = length.submittable && !busy
            Box(Modifier.size(width = 64.dp, height = 40.dp).clickable(enabled = canSend) { onBoost(draft) },
                contentAlignment = Alignment.Center) {
                Text("发送", style = IosTheme.type.subheadline,
                    color = if (canSend) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel)
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

private enum class PostAction { Solution, Like, Boost, Reply, Edit, Delete }

@Composable
private fun PostActionIcon(action: PostAction, selected: Boolean, tint: Color? = null) {
    val color = tint ?: when {
        action == PostAction.Solution && selected -> Color(0xFF30B85B)
        action == PostAction.Delete -> IosTheme.colors.destructive
        selected -> IosTheme.colors.accent
        else -> IosTheme.colors.secondaryLabel
    }
    Canvas(Modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val shape = Path()
            when (action) {
                PostAction.Solution -> {
                    drawCircle(color, 4.5f, Offset(7.5f, 7.5f), style = Stroke(1.7f))
                    drawLine(color, Offset(11f, 11f), Offset(21f, 21f), 1.7f)
                    drawLine(color, Offset(15f, 15f), Offset(18f, 12f), 1.7f)
                    drawLine(color, Offset(18f, 18f), Offset(21f, 15f), 1.7f)
                }
                PostAction.Like -> {
                    shape.moveTo(12f, 21f)
                    shape.cubicTo(10f, 19f, 3f, 13f, 3f, 8f)
                    shape.cubicTo(3f, 2f, 10f, 1f, 12f, 6f)
                    shape.cubicTo(14f, 1f, 21f, 2f, 21f, 8f)
                    shape.cubicTo(21f, 13f, 14f, 19f, 12f, 21f)
                    shape.close()
                    if (selected) drawPath(shape, color) else drawPath(shape, color, style = Stroke(1.6f))
                }
                PostAction.Boost -> {
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
                // 左弯的回复箭头,和网页端那颗"回复"图标同形。
                PostAction.Reply -> {
                    shape.moveTo(10f, 5f); shape.lineTo(3f, 11f); shape.lineTo(10f, 17f)
                    shape.lineTo(10f, 13f); shape.cubicTo(17f, 12f, 19f, 15f, 21f, 20f)
                    shape.cubicTo(21f, 10f, 17f, 8f, 10f, 9f); shape.close()
                    drawPath(shape, color)
                }
                // 铅笔
                PostAction.Edit -> {
                    shape.moveTo(4f, 20f); shape.lineTo(8f, 19f); shape.lineTo(20f, 7f)
                    shape.lineTo(17f, 4f); shape.lineTo(5f, 16f); shape.close()
                    drawPath(shape, color, style = Stroke(1.7f))
                    drawLine(color, Offset(15f, 6f), Offset(18f, 9f), 1.7f)
                }
                // 垃圾桶
                PostAction.Delete -> {
                    shape.moveTo(6f, 7f); shape.lineTo(18f, 7f); shape.lineTo(17f, 21f)
                    shape.lineTo(7f, 21f); shape.close()
                    drawPath(shape, color, style = Stroke(1.7f))
                    drawLine(color, Offset(4f, 7f), Offset(20f, 7f), 1.7f)
                    drawLine(color, Offset(10f, 4f), Offset(14f, 4f), 1.7f)
                    drawLine(color, Offset(10f, 4f), Offset(10f, 7f), 1.7f)
                    drawLine(color, Offset(14f, 4f), Offset(14f, 7f), 1.7f)
                }
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
                is CookedBlock.Picture -> AsyncImage(
                    model = block.url,
                    contentDescription = block.description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 100.dp, max = 360.dp)
                        .clickable { onPicture(block.originalUrl) },
                )
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
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val annotated = remember(runs, colors, uriHandler, context) {
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
                        linkInteractionListener = {
                            if (run.isAttachment) {
                                Toast.makeText(context, "正在下载文件…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val result = saveMediaToPublicStorage(
                                        context = context,
                                        url = link,
                                        suggestedName = run.text.trim(),
                                        asImage = false,
                                    )
                                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                runCatching { uriHandler.openUri(link) }
                            }
                        },
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var saving by remember(url) { mutableStateOf(false) }
    val originalRequest = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .crossfade(true)
            .build()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .pointerInput(url) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (zoom > 1.05f) {
                                zoom = 1f
                                offset = Offset.Zero
                            } else {
                                zoom = 2.5f
                            }
                        },
                    )
                }
                .pointerInput(url) {
                    detectTransformGestures { _, pan, scale, _ ->
                        val nextZoom = (zoom * scale).coerceIn(1f, 6f)
                        zoom = nextZoom
                        offset = if (nextZoom > 1f) {
                            offset + pan
                        } else {
                            Offset(0f, (offset.y + pan.y).coerceAtLeast(0f))
                        }
                        if (nextZoom == 1f && offset.y > 100.dp.toPx()) onDismiss()
                    }
                },
        ) {
            AsyncImage(
                model = originalRequest,
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            Row(
                Modifier.align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (saving) "保存中…" else "保存",
                    color = Color.White,
                    style = IosTheme.type.subheadline,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = !saving) {
                            saving = true
                            scope.launch {
                                val msg = saveMediaToPublicStorage(
                                    context = context,
                                    url = url,
                                    suggestedName = null,
                                    asImage = true,
                                )
                                saving = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = "关闭",
                    color = Color.White,
                    style = IosTheme.type.subheadline,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private suspend fun saveMediaToPublicStorage(
    context: Context,
    url: String,
    suggestedName: String?,
    asImage: Boolean,
): String {
    val app = context.applicationContext as App
    val container = app.container
    withContext(Dispatchers.Main) { container.sessionStore.syncFromWebView() }
    return withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            var response = container.httpClient.newCall(request).execute()
            if (response.code == 403 && response.header("cf-mitigated") != null) {
                response.close()
                val cleared = container.browser.clearChallenge("download")
                if (cleared) {
                    response = container.httpClient.newCall(request).execute()
                } else {
                    return@withContext "下载被安全校验拦截，请重试"
                }
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "下载失败 (HTTP ${resp.code})"
                }
                val body = resp.body ?: return@withContext "下载失败：响应内容为空"
                val headerMime = resp.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                val fileName = resolveDownloadFileName(
                    disposition = resp.header("Content-Disposition"),
                    finalUrl = resp.request.url.toString(),
                    suggestedName = suggestedName,
                    mimeType = headerMime,
                    asImage = asImage,
                )
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val resolvedMime = headerMime
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: if (asImage) "image/jpeg" else "application/octet-stream"

                val saveAsPicture = asImage && resolvedMime.startsWith("image/")
                val resolver = context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = if (saveAsPicture) {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    }
                    val relativeDir = if (saveAsPicture) {
                        Environment.DIRECTORY_PICTURES + "/LinuxDo"
                    } else {
                        Environment.DIRECTORY_DOWNLOADS + "/LinuxDo"
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val itemUri = resolver.insert(collection, values)
                        ?: return@withContext "无法创建本地文件"
                    try {
                        resolver.openOutputStream(itemUri)?.use { out ->
                            body.byteStream().use { input -> input.copyTo(out) }
                        } ?: return@withContext "无法写入本地文件"
                        val doneValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        resolver.update(itemUri, doneValues, null, null)
                    } catch (e: Throwable) {
                        resolver.delete(itemUri, null, null)
                        throw e
                    }
                    if (saveAsPicture) "已保存到系统相册 (Pictures/LinuxDo)" else "已保存到系统「下载/LinuxDo」：$fileName"
                } else {
                    val baseDir = context.getExternalFilesDir(
                        if (saveAsPicture) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS,
                    ) ?: context.filesDir
                    if (!baseDir.exists()) baseDir.mkdirs()
                    val outFile = File(baseDir, fileName)
                    outFile.outputStream().use { out ->
                        body.byteStream().use { input -> input.copyTo(out) }
                    }
                    "已保存到：${outFile.absolutePath}"
                }
            }
        }.getOrElse { err ->
            "保存失败：${err.message ?: "网络异常"}"
        }
    }
}

private fun resolveDownloadFileName(
    disposition: String?,
    finalUrl: String,
    suggestedName: String?,
    mimeType: String?,
    asImage: Boolean,
): String {
    val fromDisposition = disposition?.let { header ->
        val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)
        val stdMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        val raw = utf8Match?.groupValues?.getOrNull(1) ?: stdMatch?.groupValues?.getOrNull(1)
        raw?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrDefault(it.trim()) }
    }?.takeIf { it.isNotBlank() }

    val fromUrl = runCatching {
        Uri.parse(finalUrl).lastPathSegment?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    val cleanSuggested = suggestedName
        ?.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        ?.trim()
        ?.takeIf { it.isNotBlank() && '.' in it && it.length <= 120 }

    var base = (fromDisposition ?: cleanSuggested ?: fromUrl ?: "linuxdo_${System.currentTimeMillis()}")
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        .trim()

    if ('.' !in base) {
        val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> if (asImage) "jpg" else "bin"
            }
        base = "$base.$ext"
    }
    return base
}

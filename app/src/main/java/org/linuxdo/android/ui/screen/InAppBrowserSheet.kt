package org.linuxdo.android.ui.screen

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.linuxdo.android.App
import org.linuxdo.android.Config
import org.linuxdo.android.data.MiniAppDirectoryPage
import org.linuxdo.android.data.MiniAppEntry
import org.linuxdo.android.data.MiniAppLibraryState
import org.linuxdo.android.data.MiniAppSort
import org.linuxdo.android.net.gecko.GeckoAuthBridge
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.gecko.GeckoManager
import org.linuxdo.android.ui.design.IosTheme
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val IN_APP_BOOTSTRAP_PATH = "/robots.txt"
private const val IN_APP_BOOTSTRAP_URL = "${Config.BASE_URL}$IN_APP_BOOTSTRAP_PATH"
const val LINUXDO_APPS_PLAZA_URL = "${Config.BASE_URL}/apps"

private fun isAppsPlazaUrl(value: String): Boolean = isTrustedForumUrl(value) &&
    value.toUri().path?.trimEnd('/') == "/apps"

private class MiniAppLibrary(
    private val preferences: SharedPreferences,
    private val accountId: Long,
    private val currentAccountId: () -> Long,
) {
    private val key = "mini_apps_library_$accountId"
    private val json = Json { ignoreUnknownKeys = true }
    var state by mutableStateOf(read())
        private set
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
        if (changed == key || changed == null) state = read()
    }

    private fun read(): MiniAppLibraryState = runCatching {
        json.decodeFromString<MiniAppLibraryState>(preferences.getString(key, null) ?: "{}")
    }.getOrDefault(MiniAppLibraryState())

    fun update(change: (MiniAppLibraryState) -> MiniAppLibraryState) {
        if (currentAccountId() != accountId) return
        state = change(read())
        preferences.edit().putString(key, json.encodeToString(state)).apply()
    }
}

@Composable
private fun rememberMiniAppLibrary(): MiniAppLibrary {
    val app = LocalContext.current.applicationContext as App
    val preferences = app.container.preferences
    val store = app.container.sessionStore
    val accountId = store.currentUserId()
    val library = remember(preferences, accountId) { MiniAppLibrary(preferences, accountId, store::currentUserId) }
    DisposableEffect(library) {
        preferences.registerOnSharedPreferenceChangeListener(library.listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(library.listener) }
    }
    return library
}

@Composable
internal fun rememberMiniAppName(url: String): String? {
    if (isAppsPlazaUrl(url)) return "应用广场"
    val id = remember(url) { MiniAppEntry.idFromUrl(url) } ?: return null
    val app = LocalContext.current.applicationContext as App
    val store = app.container.sessionStore
    val accountId = store.currentUserId()
    val generation = store.sessionGeneration
    val library = rememberMiniAppLibrary()
    val cachedName = (library.state.recent + library.state.favorites)
        .firstOrNull { it.id == id && it.name.isNotBlank() && it.name != "小程序" }?.name
    var fetchedName by remember(id, accountId, generation) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, accountId, generation) {
        val repository = app.container.topicRepository
        if (cachedName != null || repository.isRateLimitCoolingDown() || repository.isSilentPrefetchBackingOff()) {
            return@LaunchedEffect
        }
        try {
            val info = withContext(Dispatchers.IO) { repository.fetchMiniAppInfo(id, present = false) }
            if (store.currentUserId() == accountId && store.sessionGeneration == generation) {
                fetchedName = info?.name
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 名称仅用于提示，查询失败时保留链接，不打断用户或启动小程序。
        }
    }
    return fetchedName ?: cachedName
}

private enum class MiniAppSection(val title: String) {
    Home("应用广场"), Recent("最近使用的小程序"), Favorites("我的小程序"), Directory("应用广场");
}

private enum class MiniAppDragSource {
    Recent, Favorites
}

private enum class MiniAppDropZone {
    None, AddToFavorites, Delete
}

private class MiniAppDragSession(
    val source: MiniAppDragSource,
    val app: MiniAppEntry,
    initialFingerInRoot: Offset,
) {
    var fingerInRoot by mutableStateOf(initialFingerInRoot)
    var hoverZone by mutableStateOf(MiniAppDropZone.None)
}

@Composable
private fun MiniAppLauncher(onDismiss: () -> Unit, onOpen: (MiniAppEntry) -> Unit, onVerify: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val accountId = app.container.sessionStore.currentUserId()
    val library = rememberMiniAppLibrary()
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    var section by rememberSaveable { mutableStateOf(MiniAppSection.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(MiniAppSort.Latest) }
    var plazaGridMode by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var dragSession by remember { mutableStateOf<MiniAppDragSession?>(null) }

    val directoryVisible = section == MiniAppSection.Directory
    var directoryApps by remember(accountId, query, sort) { mutableStateOf<List<MiniAppEntry>>(emptyList()) }
    var page by remember(accountId, query, sort) { mutableStateOf(0) }
    var hasMore by remember(accountId, query, sort) { mutableStateOf(false) }
    var loading by remember(accountId, query, sort) { mutableStateOf(true) }
    var failure by remember(accountId, query, sort) { mutableStateOf<String?>(null) }
    var needsVerification by remember(accountId, query, sort) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    var rootOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootSizePx by remember { mutableStateOf(Size.Zero) }
    val recentSlotRects = remember { mutableMapOf<Int, Rect>() }
    val favoriteSlotRects = remember { mutableMapOf<Int, Rect>() }
    val dropBarHeightPx = with(density) { 60.dp.toPx() }
    val screenHeightPx = remember(context) {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        metrics.heightPixels
    }
    var bottomOverflowPx by remember { mutableStateOf(0) }

    fun back() {
        when {
            showFilterSheet -> showFilterSheet = false
            dragSession != null -> dragSession = null
            section == MiniAppSection.Directory -> {
                query = ""
                focusManager.clearFocus()
                section = MiniAppSection.Home
            }
            section != MiniAppSection.Home -> section = MiniAppSection.Home
            else -> onDismiss()
        }
    }

    fun open(appEntry: MiniAppEntry) {
        library.update { it.visit(appEntry) }
        onOpen(appEntry)
    }

    fun handleDragMove(session: MiniAppDragSession, newFingerInRoot: Offset) {
        session.fingerInRoot = newFingerInRoot
        val fingerLocal = newFingerInRoot - rootOriginInWindow
        val visibleBottom = rootSizePx.height - bottomOverflowPx
        val inBottomDrop = rootSizePx.height > 0f && fingerLocal.y >= (visibleBottom - dropBarHeightPx)
        val newZone = when {
            !inBottomDrop -> MiniAppDropZone.None
            session.source == MiniAppDragSource.Recent -> {
                if (fingerLocal.x < rootSizePx.width / 2f) MiniAppDropZone.AddToFavorites
                else MiniAppDropZone.Delete
            }
            else -> MiniAppDropZone.Delete
        }
        if (newZone != session.hoverZone) {
            session.hoverZone = newZone
            if (newZone != MiniAppDropZone.None) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        if (!inBottomDrop) {
            val slots = if (session.source == MiniAppDragSource.Recent) recentSlotRects else favoriteSlotRects
            val currentList = if (session.source == MiniAppDragSource.Recent) library.state.recent else library.state.favorites
            val fromIndex = currentList.indexOfFirst { it.id == session.app.id }
            val targetEntry = slots.entries.firstOrNull { (_, rect) -> rect.contains(newFingerInRoot) }
            val toIndex = targetEntry?.key ?: -1
            if (fromIndex >= 0 && toIndex in currentList.indices && toIndex != fromIndex) {
                library.update { state ->
                    if (session.source == MiniAppDragSource.Recent) state.reorderRecent(fromIndex, toIndex)
                    else state.reorderFavorite(fromIndex, toIndex)
                }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun handleDragEnd(session: MiniAppDragSession) {
        when (session.hoverZone) {
            MiniAppDropZone.AddToFavorites -> {
                val alreadyExists = library.state.favorites.any { it.id == session.app.id }
                library.update { it.addFavorite(session.app) }
                Toast.makeText(
                    context,
                    if (alreadyExists) "已在我的小程序中" else "已添加到我的小程序",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            MiniAppDropZone.Delete -> {
                library.update { state ->
                    if (session.source == MiniAppDragSource.Recent) state.removeRecent(session.app.id)
                    else state.removeFavorite(session.app.id)
                }
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
            }
            MiniAppDropZone.None -> Unit
        }
        dragSession = null
    }

    LaunchedEffect(section, query, sort) { listState.scrollToItem(0) }
    LaunchedEffect(directoryVisible, accountId, query, sort, page, retry) {
        if (!directoryVisible) return@LaunchedEffect
        loading = true
        failure = null
        needsVerification = false
        try {
            if (query.isNotBlank() && page == 0) delay(280)
            val result: MiniAppDirectoryPage = withContext(Dispatchers.IO) {
                app.container.topicRepository.fetchMiniApps(query, page, sort)
            }
            if (app.container.sessionStore.currentUserId() != accountId) return@LaunchedEffect
            directoryApps = (if (page == 0) result.apps else directoryApps + result.apps).distinctBy { it.id }
            hasMore = result.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            needsVerification = error is NeedsInteractiveVerification
            failure = if (needsVerification) "完成安全验证" else "小程序加载失败，请重试"
        } finally {
            loading = false
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = ::back)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1E29), Color(0xFF252433), Color(0xFF2E2C3E))))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .onGloballyPositioned { coords ->
                    rootOriginInWindow = coords.positionInRoot()
                    rootSizePx = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                },
        ) {
            // 部分机型上 Dialog 窗口整体下移了状态栏高度、底部被挤出屏幕，这里实测溢出量并补到底部，
            // 保证底部的收起条 / 拖拽操作栏完整可见；没有溢出时补偿为 0。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        val bottomOnScreen = coords.positionOnScreen().y + coords.size.height
                        bottomOverflowPx = (bottomOnScreen - screenHeightPx).roundToInt().coerceAtLeast(0)
                    },
            ) {
            Column(Modifier.fillMaxSize().padding(bottom = with(density) { bottomOverflowPx.toDp() })) {
                if (directoryVisible) {
                    MiniAppPlazaTopBar(
                        query = query,
                        onBack = ::back,
                        onOpenFilter = {
                            focusManager.clearFocus()
                            showFilterSheet = true
                        },
                        onQueryChange = { query = it },
                    )
                } else {
                    // 微信风格顶部标题栏：主界面居中显示「应用广场」，二级列表左上角带返回箭头
                    Box(Modifier.fillMaxWidth().height(52.dp)) {
                        if (section != MiniAppSection.Home) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 8.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { back() }
                                    .semantics { contentDescription = "返回" },
                                contentAlignment = Alignment.Center,
                            ) {
                                WeChatBackChevron(Color.White.copy(alpha = 0.85f))
                            }
                        }
                        Text(
                            text = section.title,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    // 点击搜索：进入「所有小程序」列表并直接聚焦搜索框
                    MiniAppSearchEntry(
                        onClick = {
                            query = ""
                            section = MiniAppSection.Directory
                        },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    userScrollEnabled = dragSession == null,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 6.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    if (directoryVisible) {
                        val favoriteIds = library.state.favorites.map { it.id }.toSet()
                        if (plazaGridMode) {
                            items(directoryApps.chunked(3), key = { row -> "plaza_grid_${row.first().id}" }) { row ->
                                MiniAppPlazaGridRow(
                                    apps = row,
                                    favorites = favoriteIds,
                                    onOpen = ::open,
                                    onToggleFavorite = { entry ->
                                        val added = entry.id !in favoriteIds
                                        library.update { it.toggleFavorite(entry) }
                                        Toast.makeText(
                                            context,
                                            if (added) "已添加到我的小程序" else "已从我的小程序移除",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        } else {
                            items(directoryApps, key = { item -> "plaza_list_${item.id}" }) { item ->
                                MiniAppPlazaListRow(
                                    app = item,
                                    isFavorite = item.id in favoriteIds,
                                    onOpen = { open(item) },
                                    onToggleFavorite = {
                                        val added = item.id !in favoriteIds
                                        library.update { it.toggleFavorite(item) }
                                        Toast.makeText(
                                            context,
                                            if (added) "已添加到我的小程序" else "已从我的小程序移除",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        }
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                when {
                                    loading -> CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color.White.copy(alpha = 0.85f),
                                        strokeWidth = 2.dp,
                                    )
                                    failure != null -> TextButton(onClick = { if (needsVerification) onVerify() else retry++ }) {
                                        Text(failure.orEmpty(), color = Color.White)
                                    }
                                    hasMore -> TextButton(onClick = { page++ }) {
                                        Text("加载更多小程序", color = Color.White.copy(alpha = 0.8f))
                                    }
                                    directoryApps.isEmpty() -> Text(
                                        text = if (sort == MiniAppSort.Favorites) "暂无已收藏的小程序" else "没有找到匹配的小程序",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                    } else {
                        // 主界面：最近使用 / 我的小程序各展示前 8 个；点「更多」进入对应的完整列表页
                        val showHeaders = section == MiniAppSection.Home
                        if (section != MiniAppSection.Favorites) {
                            if (showHeaders) {
                                item {
                                    MiniAppSectionHeader(
                                        title = "最近使用的小程序",
                                        onMore = { section = MiniAppSection.Recent },
                                    )
                                }
                            }
                            val recent = if (showHeaders) library.state.recent.take(8) else library.state.recent
                            if (recent.isEmpty()) {
                                item {
                                    MiniAppEmptyRow(
                                        message = "暂未使用过小程序，点击上方搜索浏览全部小程序",
                                        onClick = { section = MiniAppSection.Directory },
                                    )
                                }
                            } else {
                                val rows = recent.withIndex().chunked(4)
                                items(rows.size, key = { rowIndex -> "recent_row_$rowIndex" }) { rowIndex ->
                                    val row = rows[rowIndex]
                                    MiniAppDraggableGridRow(
                                        items = row,
                                        source = MiniAppDragSource.Recent,
                                        draggedAppId = dragSession?.app?.id,
                                        rootOrigin = rootOriginInWindow,
                                        slotRects = recentSlotRects,
                                        onOpen = ::open,
                                        onDragStart = { entry, fingerInRoot ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragSession = MiniAppDragSession(MiniAppDragSource.Recent, entry, fingerInRoot)
                                        },
                                        onDragMove = { delta ->
                                            dragSession?.let { session ->
                                                handleDragMove(session, session.fingerInRoot + delta)
                                            }
                                        },
                                        onDragEnd = {
                                            dragSession?.let(::handleDragEnd)
                                        },
                                        onDragCancel = { dragSession = null },
                                    )
                                }
                            }
                        }
                        if (section != MiniAppSection.Recent) {
                            if (showHeaders) {
                                item {
                                    MiniAppSectionHeader(
                                        title = "我的小程序",
                                        onMore = { section = MiniAppSection.Favorites },
                                    )
                                }
                            }
                            val favorites = if (showHeaders) library.state.favorites.take(8) else library.state.favorites
                            if (favorites.isEmpty()) {
                                item {
                                    MiniAppEmptyRow(
                                        message = "长按拖动最近使用的小程序到底部，或在搜索里添加常用小程序",
                                        onClick = { section = MiniAppSection.Directory },
                                    )
                                }
                            } else {
                                val rows = favorites.withIndex().chunked(4)
                                items(rows.size, key = { rowIndex -> "favorite_row_$rowIndex" }) { rowIndex ->
                                    val row = rows[rowIndex]
                                    MiniAppDraggableGridRow(
                                        items = row,
                                        source = MiniAppDragSource.Favorites,
                                        draggedAppId = dragSession?.app?.id,
                                        rootOrigin = rootOriginInWindow,
                                        slotRects = favoriteSlotRects,
                                        onOpen = ::open,
                                        onDragStart = { entry, fingerInRoot ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragSession = MiniAppDragSession(MiniAppDragSource.Favorites, entry, fingerInRoot)
                                        },
                                        onDragMove = { delta ->
                                            dragSession?.let { session ->
                                                handleDragMove(session, session.fingerInRoot + delta)
                                            }
                                        },
                                        onDragEnd = {
                                            dragSession?.let(::handleDragEnd)
                                        },
                                        onDragCancel = { dragSession = null },
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部区域：拖拽时显示微信同款底栏（添加为我的小程序 / 删除），常态显示微信式圆角收起条
                val activeDrag = dragSession
                if (activeDrag != null) {
                    WeChatDragBottomBar(
                        source = activeDrag.source,
                        hoverZone = activeDrag.hoverZone,
                        alreadyFavorited = library.state.favorites.any { it.id == activeDrag.app.id },
                    )
                } else if (section == MiniAppSection.Home) {
                    var upwardDrag by remember { mutableFloatStateOf(0f) }
                    val closeThreshold = with(density) { 36.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .background(Color(0xFF3A3949).copy(alpha = 0.92f))
                            .pointerInput(closeThreshold) {
                                detectVerticalDragGestures(
                                    onDragStart = { upwardDrag = 0f },
                                    onDragEnd = { if (upwardDrag < -closeThreshold) onDismiss() },
                                    onVerticalDrag = { change, amount ->
                                        upwardDrag += amount
                                        change.consume()
                                    },
                                )
                            }
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "LINUX DO",
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            }

            // 自由拖拽时跟随手指悬浮放大的小程序图标（微信同款）
            dragSession?.let { active ->
                val iconSizeDp = 62.dp
                val halfSizePx = with(density) { (iconSizeDp / 2).toPx() }
                val localFinger = active.fingerInRoot - rootOriginInWindow
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (localFinger.x - halfSizePx).roundToInt(),
                                y = (localFinger.y - halfSizePx).roundToInt(),
                            )
                        }
                        .size(iconSizeDp)
                        .graphicsLayer {
                            scaleX = 1.12f
                            scaleY = 1.12f
                            alpha = 0.95f
                        }
                        .shadow(16.dp, CircleShape),
                ) {
                    MiniAppIcon(active.app, Modifier.fillMaxSize())
                }
            }

            // 应用广场底部「筛选应用」面板（对齐截图：最热、最新上线、评分最高、使用最多、已收藏）
            if (showFilterSheet) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { showFilterSheet = false },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(IosTheme.colors.card)
                        .clickable(enabled = false) {}
                        .padding(top = 10.dp, bottom = 20.dp + with(density) { bottomOverflowPx.toDp() }),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(IosTheme.colors.secondaryLabel.copy(alpha = 0.35f)),
                    )
                    Text(
                        text = "筛选应用",
                        color = IosTheme.colors.label,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 14.dp, bottom = 10.dp),
                    )
                    MiniAppSort.entries.forEach { option ->
                        val selected = sort == option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sort = option
                                    page = 0
                                    showFilterSheet = false
                                }
                                .padding(horizontal = 24.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.label,
                                color = IosTheme.colors.label,
                                fontSize = 16.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Canvas(Modifier.size(18.dp)) {
                                    val path = Path().apply {
                                        moveTo(size.width * 0.16f, size.height * 0.52f)
                                        lineTo(size.width * 0.42f, size.height * 0.78f)
                                        lineTo(size.width * 0.85f, size.height * 0.24f)
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(0xFFF59E0B),
                                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .height(0.5.dp)
                            .background(IosTheme.colors.separator),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                plazaGridMode = !plazaGridMode
                                showFilterSheet = false
                            }
                            .padding(horizontal = 24.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (plazaGridMode) "切换为列表视图" else "切换为宫格视图",
                            color = IosTheme.colors.label,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppSectionHeader(title: String, onMore: (() -> Unit)?, moreLabel: String = "更多 ›") {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onMore != null) Text(moreLabel, color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onMore).padding(horizontal = 4.dp, vertical = 8.dp))
    }
}

@Composable
private fun MiniAppEmptyRow(message: String, onClick: (() -> Unit)? = null) {
    Text(message, color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 22.dp, horizontal = 8.dp), textAlign = TextAlign.Center)
}

/**
 * 微信风格 4 列图标行：长按后图标脱离原位并跟随手指，移动时在同一分区内实时换位，
 * 拖到底部操作栏松手则添加到「我的小程序」或删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniAppDraggableGridRow(
    items: List<IndexedValue<MiniAppEntry>>,
    source: MiniAppDragSource,
    draggedAppId: String?,
    rootOrigin: Offset,
    slotRects: MutableMap<Int, Rect>,
    onOpen: (MiniAppEntry) -> Unit,
    onDragStart: (MiniAppEntry, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (index, entry) ->
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val currentEntry by androidx.compose.runtime.rememberUpdatedState(entry)
            androidx.compose.runtime.DisposableEffect(source, index) {
                onDispose { slotRects.remove(index) }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        val rect = coords.boundsInRoot()
                        bounds = rect
                        slotRects[index] = rect
                    }
                    .graphicsLayer { alpha = if (entry.id == draggedAppId) 0f else 1f }
                    .pointerInput(source) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { localOffset ->
                                onDragStart(currentEntry, bounds.topLeft + localOffset)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragMove(dragAmount)
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                        )
                    }
                    .clickable { onOpen(entry) }
                    .semantics { contentDescription = entry.name },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MiniAppIcon(
                    app = entry,
                    modifier = Modifier.size(54.dp).border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entry.name,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        repeat(4 - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun MiniAppPlazaCircleButton(description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** 「所有小程序」顶栏：圆形返回 + 展开的搜索框（进入即聚焦）+ 筛选按钮。 */
@Composable
private fun MiniAppPlazaTopBar(
    query: String,
    onBack: () -> Unit,
    onOpenFilter: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    val tint = Color.White.copy(alpha = 0.92f)
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiniAppPlazaCircleButton("返回", onBack) { WeChatBackChevron(tint) }
        MiniAppSearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f),
            focusRequester = focusRequester,
        )
        MiniAppPlazaCircleButton("筛选应用", onOpenFilter) {
            Canvas(Modifier.size(20.dp)) {
                drawLine(tint, Offset(size.width * 0.15f, size.height * 0.28f), Offset(size.width * 0.85f, size.height * 0.28f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.28f, size.height * 0.52f), Offset(size.width * 0.72f, size.height * 0.52f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.42f, size.height * 0.76f), Offset(size.width * 0.58f, size.height * 0.76f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** 主界面的搜索入口：只负责点击跳转，占位文字与放大镜整体居中（微信样式）。 */
@Composable
private fun MiniAppSearchEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hint = Color.White.copy(alpha = 0.45f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "搜索小程序" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            drawCircle(hint, size.width * 0.34f, Offset(size.width * 0.4f, size.height * 0.4f), style = Stroke(1.4.dp.toPx()))
            drawLine(hint, Offset(size.width * 0.66f, size.height * 0.66f), Offset(size.width, size.height), strokeWidth = 1.4.dp.toPx())
        }
        Spacer(Modifier.width(6.dp))
        Text("搜索小程序", color = hint, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniAppPlazaGridRow(
    apps: List<MiniAppEntry>,
    favorites: Set<String>,
    onOpen: (MiniAppEntry) -> Unit,
    onToggleFavorite: (MiniAppEntry) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.forEach { entry ->
            val saved = entry.id in favorites
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onOpen(entry) }, onLongClick = { onToggleFavorite(entry) },
                        onLongClickLabel = if (saved) "从我的小程序移除" else "添加到我的小程序")
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    MiniAppIcon(entry, Modifier.size(68.dp), RoundedCornerShape(16.dp))
                    if (saved) {
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(20.dp)
                                .background(Color(0xFF07C160), CircleShape).border(1.5.dp, Color(0xFF252433), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(10.dp)) {
                                val path = Path().apply {
                                    moveTo(size.width * 0.1f, size.height * 0.52f)
                                    lineTo(size.width * 0.4f, size.height * 0.82f)
                                    lineTo(size.width * 0.92f, size.height * 0.2f)
                                }
                                drawPath(path, Color.White, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(entry.name, color = Color.White.copy(alpha = 0.92f), fontSize = 12.5.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 16.sp)
            }
        }
        repeat(3 - apps.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun MiniAppPlazaListRow(
    app: MiniAppEntry,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniAppIcon(app, Modifier.size(52.dp), RoundedCornerShape(13.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = Color.White.copy(alpha = 0.95f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stats = buildList {
                app.ratingAverage?.takeIf { (app.ratingCount ?: 0L) > 0L }?.let { add("★ ${"%.1f".format(it)}") }
                app.usageCount?.let { add("${it} 次使用") }
            }.joinToString("  ·  ")
            if (stats.isNotEmpty()) Text(stats, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1)
        }
        Text(
            text = if (isFavorite) "已添加" else "添加",
            color = if (isFavorite) Color.White.copy(alpha = 0.5f) else Color(0xFF07C160),
            fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onToggleFavorite)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** 微信同款拖拽底栏：最近分区左侧「添加为我的小程序」灰块 + 右侧红色「删除」，我的分区整条红色删除。 */
@Composable
private fun WeChatDragBottomBar(
    source: MiniAppDragSource,
    hoverZone: MiniAppDropZone,
    alreadyFavorited: Boolean,
) {
    val deleteColor = if (hoverZone == MiniAppDropZone.Delete) Color(0xFFFA5151) else Color(0xFFE64340)
    Row(Modifier.fillMaxWidth().height(60.dp)) {
        if (source == MiniAppDragSource.Recent) {
            val addColor = if (hoverZone == MiniAppDropZone.AddToFavorites) Color(0xFF5E5C72) else Color(0xFF454456)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().background(addColor),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val dot = 3.dp.toPx()
                    drawCircle(Color.White, dot, Offset(size.width * 0.28f, size.height * 0.28f))
                    drawCircle(Color.White, dot, Offset(size.width * 0.72f, size.height * 0.28f))
                    drawCircle(Color.White, dot, Offset(size.width * 0.28f, size.height * 0.72f))
                    drawLine(Color.White, Offset(size.width * 0.9f, size.height * 0.9f), Offset(size.width * 0.62f, size.height * 0.62f),
                        strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (alreadyFavorited) "已在我的小程序中" else "拖动到此处添加为我的小程序",
                    color = Color.White, fontSize = 11.5.sp, maxLines = 1,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().background(deleteColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 1.7.dp.toPx()
                drawLine(Color.White, Offset(size.width * 0.12f, size.height * 0.24f), Offset(size.width * 0.88f, size.height * 0.24f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(size.width * 0.38f, size.height * 0.1f), Offset(size.width * 0.62f, size.height * 0.1f), strokeWidth = stroke, cap = StrokeCap.Round)
                val body = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.24f)
                    lineTo(size.width * 0.28f, size.height * 0.9f)
                    lineTo(size.width * 0.72f, size.height * 0.9f)
                    lineTo(size.width * 0.78f, size.height * 0.24f)
                }
                drawPath(body, Color.White, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(Color.White, Offset(size.width * 0.42f, size.height * 0.42f), Offset(size.width * 0.42f, size.height * 0.72f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(size.width * 0.58f, size.height * 0.42f), Offset(size.width * 0.58f, size.height * 0.72f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            Spacer(Modifier.height(4.dp))
            Text("删除", color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MiniAppIcon(
    app: MiniAppEntry,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    val colors = listOf(Color(0xFF487CE0), Color(0xFF16AC82), Color(0xFFE5794F), Color(0xFF9471CF))
    val color = colors[(app.id.hashCode() and Int.MAX_VALUE) % colors.size]
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
            Text(app.name.take(1), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
    coil.compose.SubcomposeAsyncImage(model = app.iconUrl, contentDescription = null, modifier = modifier.clip(shape),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop, loading = { placeholder() }, error = { placeholder() })
}

/** 微信式搜索框：未聚焦且无内容时占位文字与放大镜整体居中，聚焦或输入后左对齐并出现清空按钮。 */
@Composable
private fun MiniAppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = focusRequester ?: remember { androidx.compose.ui.focus.FocusRequester() }
    val idle = query.isEmpty() && !focused
    val hint = Color.White.copy(alpha = 0.45f)
    BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        modifier = modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))
            .focusRequester(requester).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "搜索小程序" },
        decorationBox = { field ->
            Row(
                modifier = Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { requester.requestFocus() }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (idle) Arrangement.Center else Arrangement.Start,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    drawCircle(hint, size.width * 0.34f, Offset(size.width * 0.4f, size.height * 0.4f), style = Stroke(1.4.dp.toPx()))
                    drawLine(hint, Offset(size.width * 0.66f, size.height * 0.66f), Offset(size.width, size.height), strokeWidth = 1.4.dp.toPx())
                }
                Spacer(Modifier.width(if (idle) 6.dp else 10.dp))
                if (idle) {
                    Text("搜索小程序", color = hint, fontSize = 15.sp)
                    Box(Modifier.width(1.dp)) { field() }
                } else {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("搜索小程序", color = hint, fontSize = 15.sp)
                        field()
                    }
                    if (query.isNotEmpty()) Text("×", color = Color.White.copy(alpha = 0.65f), fontSize = 21.sp,
                        modifier = Modifier.clickable { onQueryChange("") }.padding(start = 12.dp).semantics { contentDescription = "清空搜索" })
                }
            }
        })
}

fun isLinuxDoMiniAppUrl(rawUrl: String?): Boolean {
    if (!isTrustedForumUrl(rawUrl)) return false
    val path = rawUrl?.toUri()?.path?.lowercase().orEmpty()
    return path == "/apps" || path.startsWith("/apps/")
}

private fun defaultMiniAppTitle(url: String, initialTitle: String?): String {
    if (!initialTitle.isNullOrBlank()) return initialTitle
    val uri = runCatching { url.toUri() }.getOrNull()
    val path = uri?.path?.lowercase().orEmpty()
    return when {
        path == "/apps" || path == "/apps/" -> "小程序"
        path.startsWith("/apps/") -> "小程序"
        else -> uri?.host.orEmpty().ifBlank { "L 站页面" }
    }
}

private fun normalizeMiniAppKey(url: String): String {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return url
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path?.trimEnd('/').orEmpty()
    return "$host$path"
}

internal fun miniAppLaunchUrl(value: String): String = if (MiniAppEntry.idFromUrl(value) == null) value else
    value.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("embed_mode", "true").build().toString()

private fun miniAppPublicUrl(value: String): String = if (MiniAppEntry.idFromUrl(value) == null) value else
    value.toHttpUrlOrNull()!!.newBuilder().removeAllQueryParameters("embed_mode").build().toString()

/**
 * L 站小程序/轻应用单实例热保活池与全局浮窗状态管理。
 *
 * - 关闭小程序时默认保留 GeckoSession 5 分钟（KEEP_ALIVE_MS），5 分钟内再次打开同一应用可 0 秒热启动，
 *   无需重新请求 /robots.txt，也避免重复触发 CF 校验；
 * - 开启「浮窗」后不自动过期，在宿主 App 任意页面展示侧边悬浮胶囊，点击即可 0 秒回到原对局/应用现场。
 */
object MiniAppSessionPool {
    private const val KEEP_ALIVE_MS = 5 * 60 * 1000L
    private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var expireJob: Job? = null

    class HeldSession(
        val key: String,
        val entryUrl: String,
        val session: GeckoSession,
        val accountId: Long,
        val accountGeneration: Long,
    ) {
        var bridge: GeckoAuthBridge? = null
        var initializationJob: Job? = null
        var title by mutableStateOf(defaultMiniAppTitle(entryUrl, null))
        var currentUrl by mutableStateOf(entryUrl)
        var progress by mutableFloatStateOf(0.05f)
        var canGoBack by mutableStateOf(false)
        var bootstrapping by mutableStateOf(true)
        var bootstrapDone by mutableStateOf(false)
        var hasRenderedFirstPage by mutableStateOf(false)
        var contentReady = false
        var targetPainted = false
        var showChallenge by mutableStateOf(false)
        var loadEpoch by mutableStateOf(0)
        var documentUrl = ""
        var documentId: String? = null
        var bootstrapPreparing = false
        var appInfo by mutableStateOf<MiniAppEntry?>(null)
        var mainFrameError by mutableStateOf<String?>(null)
        var customNavMode by mutableStateOf(false)
        var desktopMode by mutableStateOf(false)
            private set
        var injectCss: String? = null

        fun toggleDesktopMode() {
            val useDesktop = !desktopMode
            session.settings.userAgentMode = if (useDesktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            session.settings.viewportMode = if (useDesktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            desktopMode = useDesktop
            // 初始化尚未完成时，目标页面会直接采用新模式，避免重载打断 Cookie 准备。
            if (bootstrapDone) {
                mainFrameError = null
                progress = 0.08f
                session.reload()
            }
        }
    }

    var activeHolder by mutableStateOf<HeldSession?>(null)
        private set

    var isFloating by mutableStateOf(false)
        private set

    var isContainerVisible by mutableStateOf(false)
        private set

    var requestedMiniAppUrl by mutableStateOf<String?>(null)
        private set

    var floatXOnRight by mutableStateOf(true)
    var floatYRatio by mutableFloatStateOf(0.72f)

    fun requestOpen(url: String) {
        if (url.isNotBlank()) {
            requestedMiniAppUrl = url
        }
    }

    fun clearRequest() {
        requestedMiniAppUrl = null
    }

    fun canReuse(url: String): Boolean {
        val current = activeHolder ?: return false
        return current.key == normalizeMiniAppKey(url)
    }

    fun acquireOrCreate(
        context: Context,
        url: String,
        initialTitle: String?,
        injectCss: String?,
    ): Pair<HeldSession, Boolean> {
        val appContext = context.applicationContext ?: context
        expireJob?.cancel()
        expireJob = null
        val targetKey = normalizeMiniAppKey(url)
        val existing = activeHolder
        val store = (appContext as? App)?.container?.sessionStore
        if (existing != null && existing.key == targetKey && existing.accountId == store?.currentUserId() &&
            existing.accountGeneration == store.sessionGeneration) {
            if (!initialTitle.isNullOrBlank() && existing.title == defaultMiniAppTitle(existing.entryUrl, null)) {
                existing.title = initialTitle
            }
            existing.injectCss = injectCss
            existing.session.setActive(true)
            return existing to true
        }
        // 若切换到不同小程序，先释放旧实例
        destroyCurrentInternal()
        val holder = createNewSession(appContext, targetKey, url, initialTitle, injectCss)
        activeHolder = holder
        isFloating = false
        holder.initializationJob?.start()
        return holder to false
    }

    fun markContainerEntered() {
        expireJob?.cancel()
        expireJob = null
        isContainerVisible = true
    }

    fun markContainerExited(expectedHolder: HeldSession, keepFloating: Boolean) {
        if (activeHolder !== expectedHolder) return
        isContainerVisible = false
        val holder = activeHolder ?: return
        holder.session.setActive(false)
        if (keepFloating) {
            isFloating = true
            expireJob?.cancel()
            expireJob = null
        } else {
            scheduleExpiration()
        }
    }

    fun toggleFloatingState(enable: Boolean) {
        isFloating = enable
        if (enable) {
            expireJob?.cancel()
            expireJob = null
        } else if (!isContainerVisible) {
            scheduleExpiration()
        }
    }

    fun relaunchCurrent(targetUrl: String? = null) {
        val holder = activeHolder ?: return
        val loadTarget = targetUrl ?: holder.entryUrl
        holder.mainFrameError = null
        holder.progress = 0.08f
        holder.session.loadUri(miniAppLaunchUrl(loadTarget))
    }

    fun destroyCurrent() {
        expireJob?.cancel()
        expireJob = null
        isFloating = false
        isContainerVisible = false
        destroyCurrentInternal()
    }

    private fun scheduleExpiration() {
        expireJob?.cancel()
        expireJob = poolScope.launch {
            delay(KEEP_ALIVE_MS)
            if (!isContainerVisible && !isFloating) {
                destroyCurrentInternal()
            }
        }
    }

    private fun destroyCurrentInternal() {
        val old = activeHolder ?: return
        activeHolder = null
        old.initializationJob?.cancel()
        runCatching { old.bridge?.close() }
        old.bridge = null
        runCatching { old.session.setActive(false) }
        runCatching { old.session.stop() }
        runCatching { old.session.close() }
    }

    private fun createNewSession(
        appContext: Context,
        key: String,
        url: String,
        initialTitle: String?,
        injectCss: String?,
    ): HeldSession {
        val container = (appContext as? App)?.container
        val sessionStore = container?.sessionStore
        val browserSession = container?.browser

        lateinit var holder: HeldSession

        fun isBootstrapLocation(raw: String?): Boolean {
            val parsed = raw?.toUri() ?: return false
            return parsed.host?.equals(Config.HOST, ignoreCase = true) == true &&
                parsed.path == IN_APP_BOOTSTRAP_PATH
        }

        fun isCurrentHolder(): Boolean = activeHolder === holder &&
            (sessionStore?.currentUserId() ?: 0L) == holder.accountId &&
            (sessionStore?.sessionGeneration ?: 0L) == holder.accountGeneration

        fun syncClearanceBack(pageUrl: String) {
            if (!isCurrentHolder() || !isTrustedForumUrl(pageUrl)) return
            poolScope.launch {
                val clearances = runCatching { holder.bridge?.readCfClearances() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: withContext(Dispatchers.IO) { GeckoManager.cfClearancesFromDb(appContext).toList() }
                if (isCurrentHolder()) {
                    browserSession?.syncFreshClearanceFromInAppGecko(clearances, "小程序容器")
                    val pageHost = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull().orEmpty()
                    org.linuxdo.android.net.NetDiagnostics.log(
                        "小程序容器页面完成(Gecko) host=$pageHost title=${holder.title.take(30)} Cookie清单: " +
                            (sessionStore?.let { runCatching { it.cookieInventory() }.getOrNull() } ?: "-"),
                    )
                }
            }
        }

        fun continueAfterBootstrap() {
            if (!isCurrentHolder() || holder.bootstrapDone || holder.bootstrapPreparing || holder.showChallenge) return
            holder.bootstrapPreparing = true
            val epoch = holder.loadEpoch
            poolScope.launch {
                try {
                    val cookies = sessionStore?.syncFromWebView().orEmpty()
                    val challenged = sessionStore?.cfClearancesForVerification()
                        ?.filterTo(mutableSetOf()) { sessionStore.isClearanceChallenged(it) }.orEmpty()
                    if (isCurrentHolder() && holder.loadEpoch == epoch) {
                        holder.bridge?.prepareInAppSessionCookies(cookies, challenged)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The destination can still use cookies already held by Gecko.
                } finally {
                    holder.bootstrapPreparing = false
                }
                if (isCurrentHolder() && holder.loadEpoch == epoch) {
                    syncClearanceBack(IN_APP_BOOTSTRAP_URL)
                    holder.bootstrapDone = true
                    holder.bootstrapping = false
                    holder.session.load(GeckoSession.Loader().uri(miniAppLaunchUrl(url)).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
                }
            }
        }

        fun applyDocumentState(state: JSONObject, bindDocument: Boolean = false) {
            if (!isCurrentHolder()) return
            val documentUrl = state.optString("url")
            if (documentUrl != holder.documentUrl) return
            val documentId = state.optString("documentId").takeIf { it.isNotBlank() } ?: return
            if (bindDocument) holder.documentId = documentId
            if (holder.documentId != documentId) return
            val phase = state.optString("phase")
            holder.showChallenge = phase == "challenge"
            if (isBootstrapLocation(documentUrl)) {
                holder.contentReady = false
                holder.hasRenderedFirstPage = false
                if (phase == "bootstrap") continueAfterBootstrap()
                return
            }
            holder.contentReady = phase == "ready" || phase == "permission"
            holder.hasRenderedFirstPage = holder.contentReady && holder.targetPainted
            if (phase == "error" || phase == "unavailable") {
                holder.mainFrameError = state.optString("detail").ifBlank { "小程序暂时无法启动，请稍后重试" }
            }
        }

        val session = GeckoManager.createChallengeSession(
            context = appContext,
            onLocationChanged = { navUrl ->
                holder.documentUrl = navUrl
                if (!isBootstrapLocation(navUrl)) {
                    holder.currentUrl = navUrl
                }
            },
            onPageStarted = { startUrl ->
                holder.loadEpoch++
                holder.documentId = null
                holder.documentUrl = startUrl
                holder.hasRenderedFirstPage = false
                holder.contentReady = false
                holder.targetPainted = false
                holder.showChallenge = false
                if (!isBootstrapLocation(startUrl)) {
                    holder.currentUrl = startUrl
                }
                holder.progress = 0.08f
                holder.mainFrameError = null
            },
            onProgressChanged = { newProgress ->
                if (!isBootstrapLocation(holder.documentUrl)) {
                    holder.progress = (newProgress / 100f).coerceIn(0.05f, 1f)
                }
            },
            onTitleChanged = { newTitle ->
                if (!isBootstrapLocation(holder.documentUrl) && MiniAppEntry.idFromUrl(holder.entryUrl) == null && newTitle.isNotBlank()) {
                    val cleaned = newTitle
                        .removeSuffix(" - LINUX DO")
                        .removeSuffix(" - Linux Do")
                        .trim()
                    holder.title = cleaned.ifBlank { newTitle }
                }
            },
            onCanGoBackChanged = { back ->
                holder.canGoBack = back && !holder.bootstrapping
            },
            onPageFinished = { finishedUrl ->
                if (isBootstrapLocation(finishedUrl)) {
                    return@createChallengeSession
                }
                holder.bootstrapping = false
                holder.currentUrl = finishedUrl
                holder.progress = 1f
                if (MiniAppEntry.idFromUrl(finishedUrl) == null) {
                    holder.contentReady = true
                    holder.hasRenderedFirstPage = holder.targetPainted
                } else if (holder.bridge == null) {
                    holder.mainFrameError = "小程序加载组件未就绪，请重试"
                }
                syncClearanceBack(finishedUrl)
                holder.injectCss?.let { css ->
                    val encodedCss = JSONObject.quote(css)
                    holder.bridge?.evaluateJavascript(
                        "(function(){var s=document.getElementById('linuxdo-native-websheet-style');if(!s){s=document.createElement('style');s.id='linuxdo-native-websheet-style';document.head.appendChild(s);}s.textContent=$encodedCss;})();",
                    )
                }
            },
            onPageError = { failedUrl, error ->
                holder.bootstrapping = false
                if (isBootstrapLocation(failedUrl)) {
                    holder.bootstrapDone = true
                    if (isCurrentHolder()) holder.session.load(GeckoSession.Loader().uri(miniAppLaunchUrl(url)).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
                } else {
                    holder.currentUrl = failedUrl.ifBlank { holder.currentUrl }
                    holder.mainFrameError = describeGeckoLoadError(error)
                }
            },
            onExternalRequest = { targetUri ->
                val target = targetUri.toUri()
                val scheme = target.scheme?.lowercase().orEmpty()
                val targetAppId = MiniAppEntry.idFromUrl(targetUri)
                if (isAppsPlazaUrl(targetUri) || (targetAppId != null && targetAppId != MiniAppEntry.idFromUrl(holder.entryUrl))) {
                    requestOpen(miniAppPublicUrl(targetUri))
                    true
                } else if (targetAppId != null && target.getQueryParameter("embed_mode") != "true") {
                    holder.session.loadUri(miniAppLaunchUrl(targetUri))
                    true
                } else if (scheme !in setOf("http", "https")) {
                    openExternal(appContext, targetUri)
                    true
                } else {
                    false
                }
            },
            returnErrorPageHtml = false,
            onContentPainted = { paintedUrl ->
                if (!isBootstrapLocation(paintedUrl)) {
                    holder.targetPainted = true
                    if (MiniAppEntry.idFromUrl(paintedUrl) == null) holder.contentReady = true
                    holder.hasRenderedFirstPage = holder.contentReady
                }
            },
        )

        holder = HeldSession(
            key = key,
            entryUrl = url,
            session = session,
            accountId = sessionStore?.currentUserId() ?: 0L,
            accountGeneration = sessionStore?.sessionGeneration ?: 0L,
        ).apply {
            this.title = defaultMiniAppTitle(url, initialTitle)
            this.injectCss = injectCss
        }

        // Publish the holder before a cached bridge can complete synchronously.
        holder.initializationJob = poolScope.launch(start = CoroutineStart.LAZY) {
            val bridge = runCatching {
                GeckoAuthBridge.create(
                    context = appContext,
                    session = session,
                    onReady = { readyUrl ->
                        if (activeHolder !== holder) return@create
                        val epoch = holder.loadEpoch
                        poolScope.launch ready@ {
                            val state = runCatching { holder.bridge?.observeInAppPage() }.getOrNull()
                            if (!isCurrentHolder() || holder.loadEpoch != epoch) return@ready
                            state?.let { applyDocumentState(it, bindDocument = true) }
                            if (isBootstrapLocation(readyUrl)) continueAfterBootstrap() else syncClearanceBack(readyUrl)
                        }
                    },
                    onCallback = { name, payload ->
                        if (name == "onInAppPageState") runCatching { JSONObject(payload) }.getOrNull()?.let { applyDocumentState(it) }
                    },
                )
            }.getOrNull()
            if (activeHolder !== holder) {
                bridge?.close()
                return@launch
            }
            holder.bridge = bridge
            if (bridge != null) {
                session.loadUri(IN_APP_BOOTSTRAP_URL)
                delay(12_000)
                if (isCurrentHolder() && !holder.bootstrapDone && !holder.bootstrapPreparing &&
                    !holder.showChallenge && holder.mainFrameError == null) {
                    holder.bootstrapDone = true
                    holder.bootstrapping = false
                    session.load(GeckoSession.Loader().uri(miniAppLaunchUrl(url)).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
                }
            } else {
                holder.bootstrapDone = true
                holder.bootstrapping = false
                session.loadUri(miniAppLaunchUrl(url))
            }
        }
        return holder
    }
}

/**
 * 1:1 复刻微信小程序风格的 L 站轻应用与同域网页容器。
 *
 * 核心视觉与交互特性：
 * 1. 右上角恒定置顶悬浮胶囊按钮 `[ ··· | (O) ]`（宽 87dp，高 32dp，圆角 16dp，距右边缘 7dp，0.5dp 描边与 0.5dp×18dp 中间分割线）；
 * 2. 原生 44dp 导航栏（支持 `default` 标准导航栏与 `custom` 沉浸全屏模式切换，沉浸模式下胶囊依然置顶悬浮）；
 * 3. 原生居中加载页，目标页面就绪后显示内容，内部 Cookie 同步页不参与展示；
 * 4. 底部双排横向滚动图标菜单 ActionSheet（支持浮窗挂起、重新进入、直达应用广场、沉浸切换、复制链接、分享等）；
 * 5. 5 分钟热保活缓存池与全局侧边吸附悬浮窗（五子棋对局或工具应用切出后 0 秒秒回，不丢失状态且零额外 CF 请求）。
 */
@Composable
fun InAppBrowserSheet(
    url: String,
    onDismiss: () -> Unit,
    initialTitle: String? = null,
    injectCss: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var activeTargetUrl by remember(url) { mutableStateOf(url) }
    var targetTitle by remember(url) { mutableStateOf(initialTitle) }
    var returnToLauncher by remember(url) { mutableStateOf(isAppsPlazaUrl(url)) }
    var actionSheetVisible by remember(activeTargetUrl) { mutableStateOf(false) }
    var keepFloatingOnExit by remember(activeTargetUrl) { mutableStateOf(false) }
    val launcherState = rememberSaveableStateHolder()

    if (isAppsPlazaUrl(activeTargetUrl)) {
        launcherState.SaveableStateProvider("mini_app_launcher") {
            MiniAppLauncher(onDismiss = onDismiss, onOpen = { entry ->
                returnToLauncher = true
                targetTitle = entry.name
                activeTargetUrl = entry.url
            }, onVerify = {
                returnToLauncher = true
                targetTitle = "安全验证"
                activeTargetUrl = "${Config.BASE_URL}/challenge"
            })
        }
        return
    }

    val closeApp: () -> Unit = {
        actionSheetVisible = false
        if (returnToLauncher) activeTargetUrl = LINUXDO_APPS_PLAZA_URL else onDismiss()
    }

    val uri = remember(activeTargetUrl) { runCatching { activeTargetUrl.toUri() }.getOrNull() }
    val validTrustedUrl = remember(activeTargetUrl) {
        uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && isTrustedForumUrl(activeTargetUrl)
    }

    LaunchedEffect(activeTargetUrl, validTrustedUrl) {
        if (!validTrustedUrl) {
            if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show()
            } else {
                openExternal(context, activeTargetUrl)
            }
            onDismiss()
        }
    }

    if (!validTrustedUrl) return

    val (holder, _) = remember(activeTargetUrl) {
        MiniAppSessionPool.acquireOrCreate(
            context = context,
            url = activeTargetUrl,
            initialTitle = targetTitle,
            injectCss = injectCss,
        )
    }

    val geckoView = remember(holder) {
        GeckoManager.createGeckoView(context, allowTouchInterceptOverride = true)
    }
    val library = rememberMiniAppLibrary()
    LaunchedEffect(holder) {
        val id = MiniAppEntry.idFromUrl(holder.entryUrl) ?: return@LaunchedEffect
        val cached = (library.state.recent + library.state.favorites).firstOrNull { it.id == id }
        val entry = cached ?: MiniAppEntry(id, targetTitle ?: "小程序")
        holder.appInfo = entry
        holder.title = entry.name
        library.update { it.visit(entry) }
        try {
            val info = withContext(Dispatchers.IO) {
                (context.applicationContext as App).container.topicRepository.fetchMiniAppInfo(id)
            }
            if (info != null && MiniAppSessionPool.activeHolder === holder) {
                holder.appInfo = info
                holder.title = info.name
                library.update { it.updateEntry(info) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cached identity remains usable when the information endpoint is unavailable.
        }
    }

    var isTimeout by remember(holder) { mutableStateOf(false) }
    LaunchedEffect(holder, holder.loadEpoch, holder.hasRenderedFirstPage, holder.showChallenge, holder.mainFrameError) {
        isTimeout = false
        if (!holder.hasRenderedFirstPage && !holder.showChallenge && holder.mainFrameError == null) {
            delay(15_000)
            isTimeout = true
        }
    }

    DisposableEffect(holder, geckoView) {
        MiniAppSessionPool.markContainerEntered()
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        geckoView.alpha = if (holder.hasRenderedFirstPage || holder.showChallenge) 1f else 0f
        geckoView.setSession(holder.session)
        holder.session.setActive(true)

        onDispose {
            runCatching { geckoView.releaseSession() }
            MiniAppSessionPool.markContainerExited(
                expectedHolder = holder,
                keepFloating = keepFloatingOnExit || MiniAppSessionPool.isFloating,
            )
        }
    }

    val showColdStartOverlay = holder.mainFrameError == null && !holder.showChallenge && !holder.hasRenderedFirstPage

    Dialog(
        onDismissRequest = closeApp,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler {
            when {
                actionSheetVisible -> actionSheetVisible = false
                holder.canGoBack -> holder.session.goBack()
                else -> closeApp()
            }
        }

        val isDark = IosTheme.colors.isDark
        val navBarBg = if (isDark) Color(0xFF191919) else Color(0xFFFFFFFF)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(navBarBg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 1. 原生导航栏 (44dp) —— default 模式下显示完整顶栏，custom 模式下隐藏顶栏让视图置顶
                if (!holder.customNavMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(navBarBg),
                    ) {
                        // 左侧返回箭头与小程序标题
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 6.dp, end = 102.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (holder.canGoBack) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable { holder.session.goBack() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    WeChatBackChevron(tint = if (isDark) Color(0xFFF0F0F0) else Color(0xFF191919))
                                }
                                Spacer(Modifier.width(2.dp))
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }

                            // 小程序微光标识点
                            val info = holder.appInfo
                            if (info != null && info.name != "小程序") MiniAppIcon(info, Modifier.size(22.dp))
                            else MiniAppGridGlyph(if (isDark) Color.White else Color(0xFF191919), Modifier.size(19.dp))
                            Spacer(Modifier.width(6.dp))

                            Text(
                                text = holder.title.ifBlank { defaultMiniAppTitle(holder.currentUrl, initialTitle) },
                                color = if (isDark) Color(0xFFF2F2F2) else Color(0xFF191919),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // 底部分割细线
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f),
                                ),
                        )
                    }
                }

                // 顶部细微加载进度条 (仅在非冷启动遮罩期显示)
                if (holder.progress < 1f && !showColdStartOverlay) {
                    LinearProgressIndicator(
                        progress = { holder.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(0xFF07C160), // 微信绿进度指示
                        trackColor = Color.Transparent,
                    )
                }

                // 加载慢提示横幅
                if (isTimeout && holder.mainFrameError == null && !showColdStartOverlay) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isDark) Color(0xFF33260D) else Color(0xFFFEF3C7))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "应用响应较慢，可尝试重新进入或切换网络",
                            color = if (isDark) Color(0xFFFCD34D) else Color(0xFF92400E),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { MiniAppSessionPool.relaunchCurrent(holder.currentUrl) }) {
                            Text("重进", fontSize = 12.sp, color = IosTheme.colors.accent)
                        }
                        TextButton(onClick = { openExternal(context, holder.currentUrl) }) {
                            Text("外部打开", fontSize = 12.sp, color = IosTheme.colors.accent)
                        }
                    }
                }

                // 2. 小程序渲染视图区域 (GeckoView)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(IosTheme.colors.groupedBackground),
                ) {
                    if (holder.mainFrameError != null) {
                        MiniAppErrorView(
                            errorMessage = holder.mainFrameError.orEmpty(),
                            onRetry = {
                                MiniAppSessionPool.relaunchCurrent()
                            },
                            onOpenExternal = { openExternal(context, holder.currentUrl) },
                        )
                    } else {
                        AndroidView(
                            factory = { geckoView },
                            modifier = Modifier.fillMaxSize(),
                            update = { view ->
                                view.alpha = if (holder.hasRenderedFirstPage || holder.showChallenge) 1f else 0f
                            },
                        )
                    }

                    if (showColdStartOverlay) {
                        MiniAppColdStartOverlay(
                            slow = isTimeout,
                            onRetry = { MiniAppSessionPool.relaunchCurrent() },
                        )
                    }
                }
            }

            // Custom 沉浸模式下，若可后退，在左上角提供半透明胶囊返回键
            if (holder.customNavMode && holder.canGoBack) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 6.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0x66000000) else Color(0xCCFFFFFF))
                        .border(
                            0.5.dp,
                            if (isDark) Color(0x26FFFFFF) else Color(0x1A000000),
                            CircleShape,
                        )
                        .clickable { holder.session.goBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    WeChatBackChevron(tint = if (isDark) Color(0xFFF2F2F2) else Color(0xFF191919))
                }
            }

            // 3. 右上角恒定置顶悬浮的微信同款胶囊按钮 [ ··· | (O) ]
            // 严格对齐规范：宽 87dp，高 32dp，距右边缘 7dp，垂直居中于 44dp 导航栏内（上下边距各 6dp）
            WeChatCapsuleButton(
                isDark = isDark,
                onMoreClick = { actionSheetVisible = true },
                onCloseClick = closeApp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 7.dp),
            )

            // 4. 微信同款底部双排横向滚动菜单 (ActionSheet)
            AnimatedVisibility(
                visible = actionSheetVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { actionSheetVisible = false },
                )
            }

            AnimatedVisibility(
                visible = actionSheetVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160)),
            ) {
                MiniAppActionSheet(
                    title = holder.title.ifBlank { defaultMiniAppTitle(holder.currentUrl, initialTitle) },
                    currentUrl = holder.currentUrl,
                    isFloating = MiniAppSessionPool.isFloating,
                    customNavMode = holder.customNavMode,
                    desktopMode = holder.desktopMode,
                    canGoBack = holder.canGoBack,
                    isFavorite = library.state.favorites.any { it.id == holder.appInfo?.id },
                    onToggleFavorite = holder.appInfo?.let { info ->
                        { library.update { it.toggleFavorite(info) }; actionSheetVisible = false }
                    },
                    onToggleFloat = {
                        actionSheetVisible = false
                        if (MiniAppSessionPool.isFloating) {
                            MiniAppSessionPool.toggleFloatingState(false)
                            Toast.makeText(context, "已取消浮窗", Toast.LENGTH_SHORT).show()
                        } else {
                            keepFloatingOnExit = true
                            MiniAppSessionPool.toggleFloatingState(true)
                            Toast.makeText(context, "已最小化为浮窗，点击侧边气泡可秒回", Toast.LENGTH_SHORT).show()
                            closeApp()
                        }
                    },
                    onRelaunch = {
                        actionSheetVisible = false
                        MiniAppSessionPool.relaunchCurrent(holder.currentUrl)
                    },
                    onToggleCustomNav = {
                        actionSheetVisible = false
                        holder.customNavMode = !holder.customNavMode
                    },
                    onToggleDesktopMode = {
                        actionSheetVisible = false
                        holder.toggleDesktopMode()
                    },
                    onGoBack = {
                        actionSheetVisible = false
                        if (holder.canGoBack) holder.session.goBack()
                    },
                    onCopyLink = {
                        actionSheetVisible = false
                        clipboard.setText(AnnotatedString(miniAppPublicUrl(holder.currentUrl)))
                        Toast.makeText(context, "小程序链接已复制", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        actionSheetVisible = false
                        shareMiniAppLink(context, holder.title, miniAppPublicUrl(holder.currentUrl))
                    },
                    onOpenExternal = {
                        actionSheetVisible = false
                        openExternal(context, holder.currentUrl)
                    },
                    onDestroyAndExit = {
                        actionSheetVisible = false
                        MiniAppSessionPool.destroyCurrent()
                        closeApp()
                    },
                    onDismiss = { actionSheetVisible = false },
                )
            }
        }
    }
}

/**
 * 1:1 复刻微信小程序右上角胶囊按钮 `[ ··· | (O) ]`
 *
 * 尺寸参数：
 * - 宽 `87dp`，高 `32dp`，圆角半径 `16dp`，边框 `0.5dp`
 * - 浅色模式：背景 `#CCFFFFFF`，边框 `#1A000000`，分割线 `#26000000`
 * - 深色模式：背景 `#40000000`，边框 `#26FFFFFF`，分割线 `#33FFFFFF`
 * - 中间分割线：宽 `0.5dp`，高 `18dp`
 */
@Composable
fun WeChatCapsuleButton(
    isDark: Boolean,
    onMoreClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capsuleShape = RoundedCornerShape(16.dp)
    val bgColor = if (isDark) Color(0x52000000) else Color(0xCCFFFFFF)
    val borderColor = if (isDark) Color(0x26FFFFFF) else Color(0x1A000000)
    val dividerColor = if (isDark) Color(0x33FFFFFF) else Color(0x26000000)
    val iconColor = if (isDark) Color(0xFFF5F5F5) else Color(0xFF191919)

    Row(
        modifier = modifier
            .width(87.dp)
            .height(32.dp)
            .shadow(
                elevation = 1.dp,
                shape = capsuleShape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(capsuleShape)
            .background(bgColor)
            .border(0.5.dp, borderColor, capsuleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左半区：··· 更多菜单按钮 (43.25dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onMoreClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val cy = size.height / 2f
                val sideRadius = 1.65.dp.toPx()
                val centerRadius = 2.25.dp.toPx() // 微信规范：中间圆点略大于两侧圆点
                drawCircle(iconColor, sideRadius, Offset(size.width * 0.20f, cy))
                drawCircle(iconColor, centerRadius, Offset(size.width * 0.50f, cy))
                drawCircle(iconColor, sideRadius, Offset(size.width * 0.80f, cy))
            }
        }

        // 中间分割线：宽 0.5dp，高 18dp
        Box(
            modifier = Modifier
                .width(0.5.dp)
                .height(18.dp)
                .background(dividerColor),
        )

        // 右半区：(O) 靶心关闭按钮 (43.25dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onCloseClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = 7.4.dp.toPx()
                val innerRadius = 2.4.dp.toPx()
                drawCircle(
                    color = iconColor,
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 1.65.dp.toPx()),
                )
                drawCircle(
                    color = iconColor,
                    radius = innerRadius,
                    center = center,
                )
            }
        }
    }
}

@Composable
private fun MiniAppColdStartOverlay(
    slow: Boolean,
    onRetry: () -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    val bgColor = if (isDark) Color(0xFF191919) else Color(0xFFFFFFFF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = (-22).dp),
        ) {
            CircularProgressIndicator(Modifier.size(26.dp), color = if (isDark) Color.White else Color(0xFF333333),
                trackColor = if (isDark) Color(0xFF444444) else Color(0xFFEAEAEA), strokeWidth = 2.dp)
            Spacer(Modifier.height(16.dp))
            Text(if (slow) "加载时间较长" else "加载中…", color = IosTheme.colors.secondaryLabel, fontSize = 14.sp)
            if (slow) TextButton(onClick = onRetry) { Text("重新加载", color = IosTheme.colors.accent) }
        }
    }
}

@Composable
private fun MiniAppGridGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.width / 5f
        repeat(3) { row -> repeat(3) { column ->
            drawRect(tint, Offset(column * unit * 1.7f, row * unit * 1.7f), Size(unit, unit))
        } }
    }
}

/**
 * 微信同款三圆点水平排列交替缩放呼吸加载动画。
 */
@Composable
fun WeChatThreeDotsLoader(
    modifier: Modifier = Modifier,
    dotColor: Color = Color(0xFF07C160),
) {
    val transition = rememberInfiniteTransition(label = "wechat-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 960, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots-phase",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val distance = kotlin.math.abs(phase - index - 0.5f)
            val activeFactor = (1f - (distance / 1.2f).coerceIn(0f, 1f))
            val scale = 0.65f + 0.45f * activeFactor
            val alpha = 0.32f + 0.68f * activeFactor
            Box(
                modifier = Modifier
                    .size(7.5.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

/**
 * 点击胶囊左侧 `···` 弹出的微信风格双排横向滚动抽屉菜单 (`ActionSheet`)。
 */
@Composable
private fun MiniAppActionSheet(
    title: String,
    currentUrl: String,
    isFloating: Boolean,
    customNavMode: Boolean,
    desktopMode: Boolean,
    canGoBack: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onToggleFloat: () -> Unit,
    onRelaunch: () -> Unit,
    onToggleCustomNav: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onGoBack: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onDestroyAndExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    val sheetBg = if (isDark) Color(0xFF1F1F1F) else Color(0xFFF7F7F7)
    val cardBg = if (isDark) Color(0xFF2C2C2E) else Color(0xFFFFFFFF)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(sheetBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        // 顶部小程序身份头栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(cardBg)
                    .border(1.dp, Color(0xFF07C160).copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MiniAppTinyEmblem(isMiniApp = true, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title,
                        color = IosTheme.colors.label,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF07C160).copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "轻应用",
                            color = Color(0xFF07C160),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = currentUrl,
                    color = IosTheme.colors.secondaryLabel,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(dividerColor))

        // 第一排：核心容器操作（浮窗、沉浸全屏、电脑手机模式切换、重新进入、移出我的）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ActionSheetItem(
                label = if (isFloating) "取消浮窗" else "浮窗",
                iconBg = cardBg,
                badgeColor = if (isFloating) Color(0xFF07C160) else null,
                onClick = onToggleFloat,
            ) {
                MiniActionIconFloat(tint = if (isFloating) Color(0xFF07C160) else IosTheme.colors.label)
            }

            ActionSheetItem(
                label = if (customNavMode) "标准顶栏" else "沉浸全屏",
                iconBg = cardBg,
                badgeColor = if (customNavMode) Color(0xFF0284C7) else null,
                onClick = onToggleCustomNav,
            ) {
                MiniActionIconFullscreen(tint = if (customNavMode) Color(0xFF0284C7) else IosTheme.colors.label)
            }

            ActionSheetItem(
                label = if (desktopMode) "访问手机版" else "访问电脑版",
                iconBg = cardBg,
                badgeColor = if (desktopMode) Color(0xFF0284C7) else null,
                onClick = onToggleDesktopMode,
                itemWidth = 80.dp,
            ) {
                MiniActionIconDevice(desktop = !desktopMode, tint = IosTheme.colors.label)
            }

            ActionSheetItem(
                label = "重新进入",
                iconBg = cardBg,
                onClick = onRelaunch,
            ) {
                MiniActionIconRefresh(tint = IosTheme.colors.label)
            }

            if (onToggleFavorite != null) {
                ActionSheetItem(
                    label = if (isFavorite) "移出我的" else "添加到我的",
                    iconBg = cardBg,
                    badgeColor = if (isFavorite) Color(0xFF07C160) else null,
                    onClick = onToggleFavorite,
                ) { Text(if (isFavorite) "✓" else "+", color = Color(0xFF07C160), fontSize = 28.sp) }
            }

            if (canGoBack) {
                ActionSheetItem(
                    label = "返回上页",
                    iconBg = cardBg,
                    onClick = onGoBack,
                ) {
                    WeChatBackChevron(tint = IosTheme.colors.label, modifier = Modifier.size(22.dp))
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(dividerColor))

        // 第二排：分享与系统级操作（转发分享、复制链接、外部浏览器、彻底关闭）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ActionSheetItem(
                label = "转发分享",
                iconBg = cardBg,
                onClick = onShare,
            ) {
                MiniActionIconShare(tint = Color(0xFF07C160))
            }

            ActionSheetItem(
                label = "复制链接",
                iconBg = cardBg,
                onClick = onCopyLink,
            ) {
                MiniActionIconCopy(tint = IosTheme.colors.label)
            }

            ActionSheetItem(
                label = "浏览器打开",
                iconBg = cardBg,
                onClick = onOpenExternal,
            ) {
                MiniActionIconBrowser(tint = Color(0xFF0284C7))
            }

            ActionSheetItem(
                label = "关闭小程序",
                iconBg = cardBg,
                onClick = onDestroyAndExit,
            ) {
                MiniActionIconPowerOff(tint = Color(0xFFFF4D4F))
            }
        }

        Spacer(Modifier.height(6.dp))

        // 底部取消按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg)
                .clickable(onClick = onDismiss)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "取消",
                color = IosTheme.colors.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ActionSheetItem(
    label: String,
    iconBg: Color,
    badgeColor: Color? = null,
    onClick: () -> Unit,
    itemWidth: androidx.compose.ui.unit.Dp = 64.dp,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(itemWidth)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconBg)
                .then(
                    if (badgeColor != null) {
                        Modifier.border(1.2.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            color = IosTheme.colors.secondaryLabel,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 全局侧边吸附的微信小程序浮窗气泡 (`MiniAppFloatingPill`)。
 *
 * 当用户在小程序内选择「浮窗」后，悬浮在宿主 App 侧边，支持上下拖动与左右贴边吸附，
 * 点击即可 0 秒热启动回到刚才正在运行的五子棋或轻应用现场。
 */
@Composable
fun MiniAppFloatingPill(
    onRestore: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = MiniAppSessionPool.activeHolder
    val visible = holder != null && MiniAppSessionPool.isFloating && !MiniAppSessionPool.isContainerVisible
    if (!visible || holder == null) return

    val density = LocalDensity.current
    val isDark = IosTheme.colors.isDark

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val pillWidthPx = with(density) { 148.dp.toPx() }
        val pillHeightPx = with(density) { 44.dp.toPx() }
        val edgeMarginPx = with(density) { 10.dp.toPx() }
        val minYPx = with(density) { 88.dp.toPx() }
        val maxYPx = (maxHeightPx - pillHeightPx - with(density) { 110.dp.toPx() }).coerceAtLeast(minYPx)

        var currentX by remember(maxWidthPx) {
            mutableFloatStateOf(
                if (MiniAppSessionPool.floatXOnRight) {
                    (maxWidthPx - pillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                } else {
                    edgeMarginPx
                },
            )
        }
        var currentY by remember(maxHeightPx) {
            mutableFloatStateOf(
                (minYPx + (maxYPx - minYPx) * MiniAppSessionPool.floatYRatio).coerceIn(minYPx, maxYPx),
            )
        }

        val pillShape = RoundedCornerShape(22.dp)
        val bgBrush = if (isDark) {
            Brush.horizontalGradient(listOf(Color(0xEE222631), Color(0xEE1A1D26)))
        } else {
            Brush.horizontalGradient(listOf(Color(0xF5FFFFFF), Color(0xF5F0FDF4)))
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                .height(44.dp)
                .widthIn(min = 124.dp, max = 168.dp)
                .shadow(10.dp, pillShape, ambientColor = Color(0xFF07C160).copy(alpha = 0.25f))
                .clip(pillShape)
                .background(bgBrush)
                .border(
                    1.dp,
                    if (isDark) Color(0x5507C160) else Color(0x6607C160),
                    pillShape,
                )
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures(
                        onDragEnd = {
                            val centerX = currentX + pillWidthPx / 2f
                            val snapRight = centerX >= maxWidthPx / 2f
                            MiniAppSessionPool.floatXOnRight = snapRight
                            currentX = if (snapRight) {
                                (maxWidthPx - pillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            } else {
                                edgeMarginPx
                            }
                            if (maxYPx > minYPx) {
                                MiniAppSessionPool.floatYRatio = ((currentY - minYPx) / (maxYPx - minYPx)).coerceIn(0f, 1f)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentX = (currentX + dragAmount.x).coerceIn(
                                edgeMarginPx,
                                (maxWidthPx - pillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx),
                            )
                            currentY = (currentY + dragAmount.y).coerceIn(minYPx, maxYPx)
                        },
                    )
                }
                .clickable { onRestore(holder.currentUrl) }
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF07C160).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                MiniAppTinyEmblem(isMiniApp = true, modifier = Modifier.size(17.dp))
            }

            Spacer(Modifier.width(7.dp))

            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = holder.title.ifBlank { "L 站轻应用" },
                    color = IosTheme.colors.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "点击回到小程序",
                    color = Color(0xFF07C160),
                    fontSize = 9.5.sp,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { MiniAppSessionPool.destroyCurrent() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = IosTheme.colors.secondaryLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MiniAppErrorView(
    errorMessage: String,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IosTheme.colors.groupedBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniAppTinyEmblem(isMiniApp = true, modifier = Modifier.size(44.dp))
            Text(
                text = "小程序页面加载未完成",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = IosTheme.colors.label,
            )
            Text(
                text = errorMessage,
                fontSize = 13.5.sp,
                color = IosTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF07C160))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                ) {
                    Text("重新加载", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(IosTheme.colors.card)
                        .border(0.5.dp, IosTheme.colors.separator, RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenExternal)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("外部浏览器", color = IosTheme.colors.label, fontSize = 13.5.sp)
                }
            }
        }
    }
}

/**
 * 小程序 S 形双环/四叶魔方微光徽章图标。
 */
@Composable
fun MiniAppTinyEmblem(
    isMiniApp: Boolean = true,
    modifier: Modifier = Modifier.size(20.dp),
) {
    val primary = if (isMiniApp) Color(0xFF07C160) else Color(0xFF0284C7)
    val secondary = if (isMiniApp) Color(0xFF0284C7) else Color(0xFF38BDF8)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = (w * 0.11f).coerceAtLeast(2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 左上与右下交织的双胶囊环（致敬微信小程序 S 环与 L 站极客积木）
        drawArc(
            color = primary,
            startAngle = 120f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.14f),
            size = Size(w * 0.48f, h * 0.48f),
            style = stroke,
        )
        drawArc(
            color = secondary,
            startAngle = -60f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(w * 0.38f, h * 0.38f),
            size = Size(w * 0.48f, h * 0.48f),
            style = stroke,
        )
    }
}

@Composable
private fun WeChatBackChevron(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp),
) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.65f, size.height * 0.20f)
            lineTo(size.width * 0.32f, size.height * 0.50f)
            lineTo(size.width * 0.65f, size.height * 0.80f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun MiniActionIconFloat(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
            size = Size(size.width * 0.68f, size.height * 0.68f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.46f, size.height * 0.46f),
            size = Size(size.width * 0.30f, size.height * 0.30f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
    }
}

@Composable
private fun MiniActionIconRefresh(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(
            color = tint,
            startAngle = 35f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
            size = Size(size.width * 0.64f, size.height * 0.64f),
            style = stroke,
        )
        val arrow = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.12f)
            lineTo(size.width * 0.82f, size.height * 0.24f)
            lineTo(size.width * 0.66f, size.height * 0.38f)
        }
        drawPath(arrow, tint, style = stroke)
    }
}

@Composable
private fun MiniActionIconDevice(desktop: Boolean, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * if (desktop) 0.08f else 0.28f, size.height * 0.12f),
            size = Size(size.width * if (desktop) 0.84f else 0.44f, size.height * if (desktop) 0.58f else 0.76f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
        if (desktop) {
            drawLine(tint, Offset(size.width * 0.5f, size.height * 0.70f), Offset(size.width * 0.5f, size.height * 0.88f), stroke.width)
            drawLine(tint, Offset(size.width * 0.32f, size.height * 0.88f), Offset(size.width * 0.68f, size.height * 0.88f), stroke.width)
        } else {
            drawCircle(tint, 1.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.77f))
        }
    }
}

@Composable
private fun MiniActionIconFullscreen(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val l = size.width * 0.20f
        val r = size.width * 0.80f
        val t = size.height * 0.20f
        val b = size.height * 0.80f
        val d = size.width * 0.18f
        val p = Path().apply {
            moveTo(l, t + d); lineTo(l, t); lineTo(l + d, t)
            moveTo(r - d, t); lineTo(r, t); lineTo(r, t + d)
            moveTo(l, b - d); lineTo(l, b); lineTo(l + d, b)
            moveTo(r - d, b); lineTo(r, b); lineTo(r, b - d)
        }
        drawPath(p, tint, style = stroke)
    }
}

@Composable
private fun MiniActionIconShare(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.16f)
            lineTo(size.width * 0.50f, size.height * 0.62f)
            moveTo(size.width * 0.34f, size.height * 0.32f)
            lineTo(size.width * 0.50f, size.height * 0.16f)
            lineTo(size.width * 0.66f, size.height * 0.32f)
            moveTo(size.width * 0.22f, size.height * 0.48f)
            lineTo(size.width * 0.22f, size.height * 0.80f)
            lineTo(size.width * 0.78f, size.height * 0.80f)
            lineTo(size.width * 0.78f, size.height * 0.48f)
        }
        drawPath(p, tint, style = stroke)
    }
}

@Composable
private fun MiniActionIconCopy(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.32f, size.height * 0.32f),
            size = Size(size.width * 0.50f, size.height * 0.50f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        val back = Path().apply {
            moveTo(size.width * 0.26f, size.height * 0.64f)
            lineTo(size.width * 0.18f, size.height * 0.64f)
            lineTo(size.width * 0.18f, size.height * 0.18f)
            lineTo(size.width * 0.64f, size.height * 0.18f)
            lineTo(size.width * 0.64f, size.height * 0.26f)
        }
        drawPath(back, tint, style = stroke)
    }
}

@Composable
private fun MiniActionIconBrowser(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width * 0.34f
        drawCircle(tint, radius, center, style = stroke)
        drawLine(tint, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 1.5.dp.toPx())
        drawOval(
            color = tint,
            topLeft = Offset(center.x - radius * 0.5f, center.y - radius),
            size = Size(radius, radius * 2f),
            style = stroke,
        )
    }
}

@Composable
private fun MiniActionIconPowerOff(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.50f, size.height * 0.16f),
            end = Offset(size.width * 0.50f, size.height * 0.50f),
            strokeWidth = 1.9.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(size.width * 0.20f, size.height * 0.22f),
            size = Size(size.width * 0.60f, size.height * 0.60f),
            style = stroke,
        )
    }
}

private fun shareMiniAppLink(context: Context, title: String, url: String) {
    runCatching {
        val shareText = if (title.isNotBlank()) "【$title】 $url" else url
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "L 站轻应用" })
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(sendIntent, "分享小程序").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "暂时无法调起系统分享", Toast.LENGTH_SHORT).show()
    }
}

internal fun describeGeckoLoadError(error: WebRequestError): String = when (error.code) {
    WebRequestError.ERROR_UNKNOWN_HOST -> "域名解析失败，请检查网络连接 (${error.code})"
    WebRequestError.ERROR_NET_TIMEOUT -> "网络连接超时，请刷新重试 (${error.code})"
    WebRequestError.ERROR_OFFLINE -> "当前网络已断开 (${error.code})"
    WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "连接被意外中断，请刷新重试 (${error.code})"
    WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_SECURITY_BAD_CERT,
    WebRequestError.ERROR_BAD_HSTS_CERT -> "安全连接握手失败 (${error.code})"
    else -> "网络连接异常 (${error.category}/${error.code})"
}

fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show() }
}

val TRUSTED_FORUM_HOSTS = setOf(Config.HOST, "www.${Config.HOST}")

fun isTrustedForumHost(rawHost: String?): Boolean {
    val host = rawHost?.lowercase()?.trimEnd('.') ?: return false
    return host == Config.HOST || host.endsWith(".${Config.HOST}")
}

fun isTrustedForumUrl(rawUrl: String?): Boolean {
    val host = rawUrl?.toUri()?.host ?: return false
    return isTrustedForumHost(host)
}

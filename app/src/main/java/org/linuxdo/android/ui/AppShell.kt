package org.linuxdo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.linuxdo.android.net.BrowserSession
import org.linuxdo.android.Config
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosMetrics
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.nav.IosNavHost
import org.linuxdo.android.ui.nav.NavStack
import org.linuxdo.android.ui.nav.Route
import org.linuxdo.android.ui.screen.TopicListScreen
import org.linuxdo.android.ui.screen.TopicDetailScreen
import org.linuxdo.android.ui.screen.CategoryListScreen
import org.linuxdo.android.ui.screen.SearchScreen
import org.linuxdo.android.ui.screen.TopicListUiState
import org.linuxdo.android.ui.nav.MainTab
import org.linuxdo.android.ui.nav.TabBar

/**
 * 应用外壳。
 *
 * 分层顺序是硬约束:全局单例 WebView 必须留在导航之外的最外层,靠 zIndex 切换显隐。
 * 它隐藏时仍要处在可见窗口内,否则 Cloudflare 的挑战页渲染不出来,静默重验证就永远失败。
 */
@Composable
fun AppShell(viewModel: MainViewModel, browser: BrowserSession) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showWeb = state.screen == Screen.Login || state.screen == Screen.Verify
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle, state.screen) {
        if (state.screen == Screen.List) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshUnread(force = true)
            while (true) { delay(Config.UNREAD_POLL_INTERVAL_MS); viewModel.refreshUnread() }
        }
    }

    LaunchedEffect(state.screen) {
        if (showWeb) {
            while (true) {
                delay(1_000)
                viewModel.pollVerification()
            }
        }
    }

    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    IosTheme(dark = dark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(IosTheme.colors.groupedBackground)
                .systemBarsPadding()
                .imePadding()
                .clipToBounds(),
        ) {
            // 验证期间保留导航的组合身份,返回后恢复原页面与滚动位置。
            if (state.username != null || state.screen == Screen.List) {
                key(state.username) { MainNavigation(state, viewModel) }
            }
            if (state.screen == Screen.Boot) BootScreen()
            if (showWeb) WebTopBar(state.screen)

            val context = LocalContext.current
            AndroidView(
                factory = { browser.attach(context) },
                onRelease = { browser.detach() },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (showWeb) 1f else -1f)
                    .padding(top = if (showWeb) IosMetrics.navBarHeight else 0.dp),
            )
        }
    }
}

@Composable
private fun MainNavigation(state: UiState, viewModel: MainViewModel) {
    val stacks = MainTab.entries.associateWith { tab ->
        key(tab) { rememberSaveable(saver = NavStack.Saver) { NavStack(tab.root) } }
    }
    val scrollRequests = MainTab.entries.associateWith { tab ->
        key(tab) { rememberSaveable { mutableIntStateOf(0) } }
    }
    var selected by rememberSaveable { mutableStateOf(MainTab.Latest) }
    val holder = rememberSaveableStateHolder()
    val stack = stacks.getValue(selected)
    val scrollToTopRequest = scrollRequests.getValue(selected).intValue
    val details by viewModel.details.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoryTopics by viewModel.categoryTopics.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val treeView by viewModel.treeView.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val browsing = state.screen == Screen.List
    val openNotifications = {
        if (stack.current != Route.Notifications) stack.push(Route.Notifications)
        viewModel.loadProfile(org.linuxdo.android.data.ProfileSection.Notifications, refresh = true)
        viewModel.refreshUnread(force = true)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            holder.SaveableStateProvider(selected.name) {
                key(selected) {
                    IosNavHost(stack, interactive = browsing) { route ->
                        val active = browsing && stack.current == route
                        LaunchedEffect(route, active) {
                            if (active) when (route) {
                                is Route.TopicDetail -> viewModel.loadTopic(route.topicId, postNumber = route.postNumber)
                                Route.CategoryList -> viewModel.loadCategories()
                                is Route.CategoryTopics -> viewModel.loadCategory(route.categoryId, route.slug)
                                else -> Unit
                            }
                        }
                        when (route) {
                            Route.TopicList -> TopicListScreen(
                                title = "最新",
                                state = state.latest,
                                onRefresh = viewModel::refreshLatest,
                                onLoadMore = viewModel::loadMoreLatest,
                                onOpenTopic = { topic -> stack.push(Route.TopicDetail(topic.id, topic.title)) },
                                active = active,
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = { UnreadBell(state.unreadCount, openNotifications) },
                            )

                            is Route.TopicDetail -> TopicDetailScreen(
                                title = route.title,
                                state = details[route.topicId] ?: DetailUiState(loading = true),
                                onBack = { stack.pop() },
                                onRetry = { viewModel.retryTopic(route.topicId, route.postNumber) },
                                onLoadMore = { viewModel.loadTopic(route.topicId, more = true) },
                                onLoadPrevious = { viewModel.loadTopic(route.topicId, previous = true) },
                                initialPostNumber = route.postNumber,
                                onUnknownTag = { viewModel.diagnostics.log("未知 cooked 标签: $it") },
                                treeView = treeView,
                                onLoadReplies = { viewModel.loadReplies(route.topicId, it) },
                                onReact = { postId, reaction -> viewModel.react(route.topicId, postId, reaction) },
                                onBoost = { postId, raw -> viewModel.createBoost(route.topicId, postId, raw) },
                                onDeleteBoost = { postId, boostId -> viewModel.deleteBoost(route.topicId, postId, boostId) },
                                onRefreshPost = { viewModel.refreshPost(route.topicId, it) },
                                onSolution = { viewModel.setSolution(route.topicId, it) },
                                active = active,
                                trailing = { UnreadBell(state.unreadCount, openNotifications) },
                            )

                            Route.CategoryList -> CategoryListScreen(categories,
                                onRetry = { viewModel.loadCategories(retry = true) },
                                onOpen = { stack.push(Route.CategoryTopics(it.id, it.slug, it.name)) },
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = { UnreadBell(state.unreadCount, openNotifications) })

                            is Route.CategoryTopics -> TopicListScreen(route.name,
                                state = categoryTopics[route.categoryId] ?: TopicListUiState(),
                                onRefresh = { viewModel.loadCategory(route.categoryId, route.slug, refresh = true) },
                                onLoadMore = { viewModel.loadCategory(route.categoryId, route.slug, more = true) },
                                onOpenTopic = { stack.push(Route.TopicDetail(it.id, it.title)) },
                                onBack = { stack.pop() }, active = active,
                                trailing = { UnreadBell(state.unreadCount, openNotifications) })

                            Route.Search -> SearchScreen(search, viewModel::updateSearch, viewModel::loadMoreSearch,
                                onOpenTopic = { stack.push(Route.TopicDetail(it.id, it.title)) },
                                onOrder = viewModel::setSearchOrder, active = active,
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = { UnreadBell(state.unreadCount, openNotifications) })

                            Route.Profile -> ProfileScreen(state.username, profile, viewModel::loadProfile,
                                onOpenTopic = { id, title, postNumber -> stack.push(Route.TopicDetail(id, title, postNumber)) },
                                onDiagnostics = { stack.push(Route.Diagnostics) },
                                onSettings = { stack.push(Route.Settings) }, active = active,
                                onReadNotification = viewModel::readNotification,
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = { UnreadBell(state.unreadCount, openNotifications) })

                            Route.Settings -> SettingsScreen(treeView, viewModel::setTreeView, onBack = { stack.pop() },
                                themeMode = themeMode, onThemeMode = viewModel::setThemeMode)

                            Route.Notifications -> ProfileScreen(state.username, profile, viewModel::loadProfile,
                                onOpenTopic = { id, title, postNumber -> stack.push(Route.TopicDetail(id, title, postNumber)) },
                                onDiagnostics = { stack.push(Route.Diagnostics) }, onSettings = { stack.push(Route.Settings) },
                                active = active, notificationOnly = true, onBack = { stack.pop() },
                                onReadNotification = viewModel::readNotification)

                            Route.Diagnostics -> DiagnosticsScreen(
                                state = state,
                                viewModel = viewModel,
                                onBack = { stack.pop() },
                            )

                        }
                    }
                }
            }
        }
        TabBar(selected) { tab ->
            if (browsing) {
                if (tab == selected) {
                    stacks.getValue(tab).popToRoot()
                    scrollRequests.getValue(tab).intValue++
                } else selected = tab
            }
        }
    }
}

@Composable
private fun BootScreen() {
    Box(
        Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground),
        contentAlignment = Alignment.Center,
    ) {
        IosActivityIndicator(diameter = 28.dp)
    }
}

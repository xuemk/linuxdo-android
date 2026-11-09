package org.linuxdo.android.ui

import android.app.Dialog
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import org.linuxdo.android.data.InternalTopicLink
import org.linuxdo.android.data.parseTopicLinkFromText
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView as AndroidTextView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Text
import org.linuxdo.android.ui.screen.PostAction
import org.linuxdo.android.ui.screen.PostActionIcon
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.linuxdo.android.ui.screen.openExternal
import org.linuxdo.android.net.BrowserSession
import org.linuxdo.android.Config
import org.linuxdo.android.data.HomeFeedType
import org.linuxdo.android.data.NotificationPollInterval
import org.linuxdo.android.data.compactCategoryGroups
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
import org.linuxdo.android.ui.screen.LoginScreen
import org.linuxdo.android.ui.screen.categoryLevelOf
import org.linuxdo.android.ui.screen.HistoryScreen
import org.linuxdo.android.ui.screen.ConnectWebSheet
import org.linuxdo.android.ui.screen.InAppBrowserSheet
import org.linuxdo.android.ui.screen.openExternal
import org.linuxdo.android.ui.screen.ChatListScreen
import org.linuxdo.android.ui.screen.ChatRoomScreen
import org.linuxdo.android.ui.screen.FollowListScreen
import org.linuxdo.android.ui.screen.BlacklistScreen
import org.linuxdo.android.ui.screen.UserCardBottomSheet
import org.linuxdo.android.ui.screen.UserProfileScreen
import org.linuxdo.android.ui.screen.PrivateMessageComposeSheet
import org.linuxdo.android.ui.nav.MainTab
import org.linuxdo.android.ui.nav.TabBar

/**
 * 应用外壳。
 *
 * 分层顺序是硬约束:全局单例 WebView 必须留在导航之外的最外层,靠 zIndex 切换显隐。
 * Login 屏使用原生 Compose，安全验证只在独立对话框中显示轻量 WebView。
 */
@Composable
fun AppShell(viewModel: MainViewModel, browser: BrowserSession) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loginUiState by viewModel.loginUiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    // 全局 WebView 在 CF 验证时显示；登录人机验证使用独立对话框。
    val showWeb = state.screen == Screen.Verify
    BackHandler(enabled = showWeb) { viewModel.cancelVerification() }
    val showWebLoginModal = state.screen == Screen.Login && loginUiState.showWebLoginDialog
    val accountsState by viewModel.accounts.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val notificationPollInterval by viewModel.notificationPollInterval.collectAsStateWithLifecycle()
    val customRealtimeSeconds by viewModel.customRealtimeSeconds.collectAsStateWithLifecycle()

    LaunchedEffect(lifecycle, state.screen, state.latest.initialLoading, notificationPollInterval, customRealtimeSeconds) {
        if (state.screen == Screen.List && !state.latest.initialLoading) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshUnread(force = true)
            // 前台未读角标与后台通知设置解耦：可见时始终按 10 秒基线轮询，
            // 只有“实时”且自定义间隔更短时才加快，下限 5 秒避免触发限频。
            val pollDelayMs = when (notificationPollInterval) {
                NotificationPollInterval.REALTIME ->
                    (customRealtimeSeconds * 1000L).coerceIn(5_000L, Config.UNREAD_POLL_INTERVAL_MS)
                else -> Config.UNREAD_POLL_INTERVAL_MS
            }
            while (true) {
                // 限流冷却期间直接睡到解封，不再每 10 秒空转一次
                delay(viewModel.nextUnreadPollDelayMs(pollDelayMs))
                viewModel.refreshUnread()
            }
        }
    }

    var verifyPageState by remember(showWeb) { mutableStateOf(BrowserSession.VerifyPageState.LOADING) }
    LaunchedEffect(showWeb) {
        if (showWeb) {
            verifyPageState = BrowserSession.VerifyPageState.LOADING
            launch {
                // 验证页未确认需要人工交互前用原生加载层遮住 WebView；主框架报错（DoH 直连下连不上）
                // 或数秒仍未加载完则换成异常提示。
                while (showWeb) {
                    verifyPageState = viewModel.verifyPageState()
                    delay(150)
                }
            }
            while (true) {
                delay(300)
                viewModel.pollVerification()
                verifyPageState = viewModel.verifyPageState()
            }
        }
    }
    val readSyncVerifyBanner by viewModel.readSyncVerifyBanner.collectAsStateWithLifecycle()

    val skinPreset by viewModel.skinPreset.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    IosTheme(dark = dark, skinPreset = skinPreset) {
        Box(
            Modifier
                .fillMaxSize()
                .background(IosTheme.colors.groupedBackground)
                .systemBarsPadding()
                .imePadding()
                .clipToBounds(),
        ) {
            // 验证期间保留导航的组合身份，返回后恢复原页面与滚动位置。
            if (state.username != null || state.screen == Screen.List) {
                key(state.username) { MainNavigation(state, viewModel, lifecycle) }
            }

            if (state.screen == Screen.Boot) BootScreen()

            if (readSyncVerifyBanner && state.screen == Screen.List) {
                ReadSyncVerifyBanner(
                    onVerify = viewModel::openReadSyncVerification,
                    onDismiss = viewModel::dismissReadSyncVerifyBanner,
                    dark = dark,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = IosMetrics.navBarHeight + 4.dp, start = 12.dp, end = 12.dp)
                        .zIndex(1f),
                )
            }

            // 原生登录页面。
            if (state.screen == Screen.Login) {
                LoginScreen(
                    state = loginUiState,
                    onPasswordLogin = viewModel::loginWithPassword,
                    onSendEmailCode = viewModel::sendLoginEmail,
                    onEmailCodeLogin = viewModel::loginWithEmailCode,
                    onDismissError = viewModel::dismissLoginError,
                    onResetSecondFactor = viewModel::resetSecondFactor,
                    onOpenWebLogin = viewModel::openWebLoginModal,
                    onStartWebAuth = viewModel::startWebAuth,
                    onDismissRiskControl = viewModel::dismissRiskControlDialog,
                    onCancelWebAuth = viewModel::cancelWebAuth,
                    onCancel = if (viewModel.canCancelAddAccount()) viewModel::cancelAddAccount else null,
                )
            }

            // CF 的全屏验证和后台 WebView 使用全局页面内 AndroidView；登录人机验证在弹框中。
            if (!showWebLoginModal) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (showWeb) 2f else -1f)
                        .then(if (showWeb) Modifier.background(IosTheme.colors.groupedBackground) else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = if (showWeb) {
                            Modifier
                                .fillMaxSize()
                                .padding(top = IosMetrics.navBarHeight)
                        } else {
                            Modifier.fillMaxSize()
                        },
                    ) {
                        val context = LocalContext.current
                        if (showWeb) {
                            WebTopBar(state.screen, viewModel::cancelVerification, viewModel::retryVerification)
                        }
                        val webReady = !showWeb || verifyPageState == BrowserSession.VerifyPageState.READY
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clipToBounds(),
                        ) {
                            AndroidView(
                                factory = { browser.attach(context) },
                                onRelease = { browser.detach() },
                                // 保持 View.VISIBLE 让 Chromium/Turnstile 正常跑帧，未就绪或已回源 404 时用 alpha=0f + 原生遮罩层挡住画面
                                update = { view ->
                                    if (view.visibility != android.view.View.VISIBLE) {
                                        view.visibility = android.view.View.VISIBLE
                                    }
                                    val targetAlpha = if (webReady) 1f else 0f
                                    if (view.alpha != targetAlpha) view.alpha = targetAlpha
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (!webReady) {
                                VerifyLoadingLayer(
                                    state = verifyPageState,
                                    onRetry = viewModel::retryVerification,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(IosTheme.colors.groupedBackground)
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent()
                                                }
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
            else {
                NativeWebLoginDialog(browser, viewModel::closeWebLoginModal, dark)
            }

            val updateInfo = updateState.updateInfo
            if (updateState.showDialog && updateInfo != null) {
                UpdateDialog(
                    currentVersion = updateState.currentVersion,
                    info = updateInfo,
                    stage = updateState.apkStage,
                    downloadedBytes = updateState.apkDownloadedBytes,
                    totalBytes = updateState.apkTotalBytes,
                    errorMessage = updateState.apkError,
                    onStartDownload = viewModel::startApkUpdate,
                    onRetryAfterPermission = viewModel::retryApkUpdateAfterPermission,
                    onCancelDownload = viewModel::cancelApkUpdate,
                    onDismiss = viewModel::dismissUpdateDialog,
                )
            }
        }
    }
}

/** 阅读同步被 CF 风控时的提示条：点击进入验证页，关闭则 10 分钟内不再提示。 */
@Composable
private fun ReadSyncVerifyBanner(
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val warnBg = if (dark) Color(0xFF3A2E12) else Color(0xFFFEF3C7)
    val warnText = if (dark) Color(0xFFFDE68A) else Color(0xFF92400E)
    val warnBtn = if (dark) Color(0xFFF59E0B) else Color(0xFFB45309)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(warnBg)
            .clickable(onClickLabel = "进行安全验证", onClick = onVerify)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "阅读进度同步触发风控，需要完成 Cloudflare 安全验证",
            color = warnText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onVerify) {
            Text("去验证", fontSize = 13.sp, color = warnBtn, fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = onDismiss) {
            Text("忽略", fontSize = 13.sp, color = warnText)
        }
    }
}

/** 验证页加载层：加载中显示转圈；验证页打不开，或验证通过但 App 请求仍被拦截时提供重试。 */
@Composable
private fun VerifyLoadingLayer(
    state: BrowserSession.VerifyPageState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        val failure = when (state) {
            BrowserSession.VerifyPageState.STALLED -> "验证未完成" to "验证页面加载失败，请检查网络后重试"
            BrowserSession.VerifyPageState.MISMATCH ->
                "App 连接尚未恢复" to "请稍后重试。"
            else -> null
        }
        if (failure != null) {
            Text(
                text = failure.first,
                style = IosTheme.type.navTitle,
                color = IosTheme.colors.label,
            )
            Text(
                text = failure.second,
                color = IosTheme.colors.secondaryLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("重试", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            IosActivityIndicator(diameter = 28.dp)
            Text(
                text = if (state == BrowserSession.VerifyPageState.CONFIRMING) {
                    "网页验证已完成，正在确认 App 连接。\n遇到限流时会等待后复核。"
                } else "正在连接安全验证服务…",
                color = IosTheme.colors.secondaryLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun NativeWebLoginDialog(
    browser: BrowserSession,
    onClose: () -> Unit,
    dark: Boolean,
) {
    val context = LocalContext.current
    DisposableEffect(browser, context, dark) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val surfaceColor = if (dark) AndroidColor.rgb(28, 28, 30) else AndroidColor.WHITE
        val labelColor = if (dark) AndroidColor.rgb(245, 245, 247) else AndroidColor.rgb(28, 28, 30)
        val secondaryColor = if (dark) AndroidColor.rgb(142, 142, 147) else AndroidColor.rgb(142, 142, 147)
        val dividerColor = if (dark) AndroidColor.rgb(56, 56, 58) else AndroidColor.rgb(229, 229, 234)
        val dialog = Dialog(context)
        var dismissedByComposition = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(surfaceColor)
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val icon = AndroidTextView(context).apply {
            text = "🛡️"
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val title = AndroidTextView(context).apply {
            text = "安全验证"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        val close = AndroidTextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(secondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (dark) AndroidColor.rgb(44, 44, 46) else AndroidColor.rgb(242, 242, 247))
            }
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(View(context).apply { setBackgroundColor(dividerColor) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val webView = browser.attachAuth(context)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (!dismissedByComposition) onClose()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.92f).toInt(),
            )
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            attributes = attributes.apply { dimAmount = 0.5f }
        }
        webView.requestFocus()

        onDispose {
            dismissedByComposition = true
            dialog.setOnDismissListener(null)
            root.removeView(webView)
            dialog.dismiss()
            browser.detachAuth(webView)
        }
    }
}

@Composable
private fun MainNavigation(state: UiState, viewModel: MainViewModel, lifecycle: Lifecycle) {
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
    val feedStates by viewModel.feedStates.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categorySubTab by viewModel.categorySubTab.collectAsStateWithLifecycle()
    val tagsState by viewModel.tags.collectAsStateWithLifecycle()
    val tagSearchQuery by viewModel.tagSearchQuery.collectAsStateWithLifecycle()
    val tagTopics by viewModel.tagTopics.collectAsStateWithLifecycle()
    val categoryTopics by viewModel.categoryTopics.collectAsStateWithLifecycle()
    val userTopicStates by viewModel.userTopicStates.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val topicSearch by viewModel.topicSearch.collectAsStateWithLifecycle()
    val metadataEditor by viewModel.metadataEditor.collectAsStateWithLifecycle()
    val metadataUpdated by viewModel.metadataUpdated.collectAsStateWithLifecycle()
    LaunchedEffect(metadataUpdated) {
        metadataUpdated?.let { value -> stacks.values.forEach { it.updateTopicTitle(value.topicId, value.title) } }
    }
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val userBadges by viewModel.userBadges.collectAsStateWithLifecycle()
    val isUserBadgesLoading by viewModel.isUserBadgesLoading.collectAsStateWithLifecycle()
    val notificationFilter by viewModel.notificationFilter.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val topicCreator by viewModel.topicCreator.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val accountState by viewModel.accounts.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val userCard by viewModel.userCard.collectAsStateWithLifecycle()
    val followList by viewModel.followList.collectAsStateWithLifecycle()
    val trustReport by viewModel.trustReport.collectAsStateWithLifecycle()
    val ldcState by viewModel.ldcState.collectAsStateWithLifecycle()
    val cdkScoreState by viewModel.cdkScore.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val skinPreset by viewModel.skinPreset.collectAsStateWithLifecycle()
    val userRepliesStates by viewModel.userRepliesStates.collectAsStateWithLifecycle()
    val topicSortOrder by viewModel.topicSortOrder.collectAsStateWithLifecycle()
    val topicSortAscending by viewModel.topicSortAscending.collectAsStateWithLifecycle()
    val floatingBallEnabled by viewModel.floatingBallEnabled.collectAsStateWithLifecycle()
    val floatingBallActions by viewModel.floatingBallActions.collectAsStateWithLifecycle()
    val floatingBallXRatio by viewModel.floatingBallXRatio.collectAsStateWithLifecycle()
    val floatingBallYRatio by viewModel.floatingBallYRatio.collectAsStateWithLifecycle()
    val floatingBallOnRight by viewModel.floatingBallOnRight.collectAsStateWithLifecycle()
    val treeView by viewModel.treeView.collectAsStateWithLifecycle()
    val syncReadProgress by viewModel.syncReadProgress.collectAsStateWithLifecycle()
    val showUnreadDot by viewModel.showUnreadDot.collectAsStateWithLifecycle()
    val unreadDotPosition by viewModel.unreadDotPosition.collectAsStateWithLifecycle()
    val showTopicUnreadDot by viewModel.showTopicUnreadDot.collectAsStateWithLifecycle()
    val topicUnreadDotPosition by viewModel.topicUnreadDotPosition.collectAsStateWithLifecycle()
    val isBottomBarVisible by viewModel.isBottomBarVisible.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val autoRefreshIncoming by viewModel.autoRefreshIncoming.collectAsStateWithLifecycle()
    val autoRefreshInterval by viewModel.autoRefreshInterval.collectAsStateWithLifecycle()
    val autoReadClipboardEnabled by viewModel.autoReadClipboardEnabled.collectAsStateWithLifecycle()
    val detectedClipboardTarget by viewModel.detectedClipboardTarget.collectAsStateWithLifecycle()
    val backgroundNotification by viewModel.backgroundNotification.collectAsStateWithLifecycle()
    val notificationPollInterval by viewModel.notificationPollInterval.collectAsStateWithLifecycle()
    val notificationScope by viewModel.notificationScope.collectAsStateWithLifecycle()
    val customRealtimeSeconds by viewModel.customRealtimeSeconds.collectAsStateWithLifecycle()
    val keepAliveService by viewModel.keepAliveService.collectAsStateWithLifecycle()
    val pendingNotificationTarget by viewModel.pendingNotificationTarget.collectAsStateWithLifecycle()
    // ── DoH 状态 ─────────────────────────────────────────────────────────────────
    val dohEnabled by viewModel.dohEnabled.collectAsStateWithLifecycle()
    val dohServers by viewModel.dohServers.collectAsStateWithLifecycle()
    val selectedDohServerUrl by viewModel.selectedDohServerUrl.collectAsStateWithLifecycle()
    val dohAutoFailover by viewModel.dohAutoFailover.collectAsStateWithLifecycle()
    val dohAutoOptimize by viewModel.dohAutoOptimize.collectAsStateWithLifecycle()
    val dohForceOverProxy by viewModel.dohForceOverProxy.collectAsStateWithLifecycle()
    val dohLatencies by viewModel.dohLatencies.collectAsStateWithLifecycle()
    val dohSummaries by viewModel.dohSummaries.collectAsStateWithLifecycle()
    val isTestingAllDoh by viewModel.isTestingAllDoh.collectAsStateWithLifecycle()
    val smartRouteMode by viewModel.smartRouteMode.collectAsStateWithLifecycle()
    val smartDirectLatency by viewModel.smartDirectLatency.collectAsStateWithLifecycle()
    val smartDohLatency by viewModel.smartDohLatency.collectAsStateWithLifecycle()
    val smartRouteSummary by viewModel.smartRouteSummary.collectAsStateWithLifecycle()
    val isSmartEvaluating by viewModel.isSmartEvaluating.collectAsStateWithLifecycle()
    val isSelfHealing by viewModel.isSelfHealing.collectAsStateWithLifecycle()
    val feedGraphicMode by viewModel.feedGraphicMode.collectAsStateWithLifecycle()
    val feedDisplayMode by viewModel.feedDisplayMode.collectAsStateWithLifecycle()
    val blockedUsers by viewModel.blockedUsers.collectAsStateWithLifecycle()
    val blockedUsernameSet = remember(blockedUsers) { blockedUsers.map { it.username.lowercase() }.toSet() }
    val savedTopics by viewModel.savedTopics.collectAsStateWithLifecycle()
    val savedTopicsUserId by viewModel.savedTopicsUserId.collectAsStateWithLifecycle()
    val savedTopicsBusy by viewModel.savedTopicsBusy.collectAsStateWithLifecycle()
    val savedTopicsNotice by viewModel.savedTopicsNotice.collectAsStateWithLifecycle()
    val canUndoSavedTopics by viewModel.canUndoSavedTopics.collectAsStateWithLifecycle()
    var showSavedTopics by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(savedTopicsNotice) {
        if (savedTopicsNotice != null) {
            delay(if (canUndoSavedTopics) 6_000L else 3_000L)
            viewModel.clearSavedTopicsNotice()
        }
    }

    LaunchedEffect(pendingNotificationTarget) {
        val target = pendingNotificationTarget ?: return@LaunchedEffect
        selected = MainTab.Latest
        val stack = stacks.getValue(MainTab.Latest)
        val currentRoute = stack.current
        if (currentRoute is Route.TopicDetail && currentRoute.topicId == target.topicId) {
            viewModel.loadTopic(
                target.topicId,
                postNumber = target.postNumber,
                resumePostNumber = viewModel.resolveResumePostNumber(target.topicId, target.postNumber),
                fromNotification = true,
            )
        } else {
            stack.push(Route.TopicDetail(target.topicId, "", target.postNumber))
        }
        viewModel.clearPendingNotificationTarget()
    }

    LaunchedEffect(stack.current) {
        val currentRoute = stack.current
        val isList = currentRoute is Route.TopicList || currentRoute is Route.CategoryTopics || currentRoute is Route.UserTopics || currentRoute is Route.TagTopics
        val isDetail = currentRoute is Route.TopicDetail || currentRoute is Route.Chat || currentRoute is Route.ChatRoom
        if (!isList && !isDetail) {
            viewModel.setBarsVisible(true)
        }
    }

    val context = LocalContext.current

    LaunchedEffect(autoReadClipboardEnabled) {
        if (autoReadClipboardEnabled) {
            viewModel.checkClipboard(context)
        }
    }

    DisposableEffect(lifecycle, autoReadClipboardEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && autoReadClipboardEnabled) {
                viewModel.checkClipboard(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
    val browsing = state.screen == Screen.List
    LaunchedEffect(browsing, savedTopicsUserId) {
        if (!browsing || savedTopicsUserId <= 0L) showSavedTopics = false
    }
    var detailOverlay by rememberSaveable { mutableStateOf(false) }
    var showConnectSheet by rememberSaveable { mutableStateOf(false) }
    var privateMessageRecipient by rememberSaveable { mutableStateOf<String?>(null) }
    var globalBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browserAccountId by remember { mutableStateOf(savedTopicsUserId) }
    LaunchedEffect(savedTopicsUserId, accountState.switching) {
        if (browserAccountId != savedTopicsUserId || accountState.switching) {
            globalBrowserUrl = null
            org.linuxdo.android.ui.screen.MiniAppSessionPool.destroyCurrent()
        }
        browserAccountId = savedTopicsUserId
    }
    val contentObscured = topicCreator.open || composer.open || detailOverlay || showSavedTopics || topicSearch.open || metadataEditor.open ||
        showConnectSheet || globalBrowserUrl != null || privateMessageRecipient != null || accountState.switching ||
        userCard.loading || userCard.user != null || userCard.error != null
    val visibleRoute = stack.current
    val navigationLifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val listForeground = navigationLifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !contentObscured
    DisposableEffect(lifecycle, visibleRoute, browsing, contentObscured, state.homeFeed) {
        fun updateProbeVisibility() {
            val category = visibleRoute as? Route.CategoryTopics
            val visible = browsing && !contentObscured &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (visibleRoute == Route.TopicList || category != null)
            viewModel.setAutoProbeVisible(visible, category?.categoryId, category?.slug)
            val previewVisible = browsing && !contentObscured &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (visibleRoute == Route.TopicList || category != null || visibleRoute is Route.TagTopics)
            viewModel.setPreviewListVisible(previewVisible)
        }
        val observer = LifecycleEventObserver { _, _ -> updateProbeVisibility() }
        lifecycle.addObserver(observer)
        updateProbeVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setAutoProbeVisible(false)
            viewModel.setPreviewListVisible(false)
        }
    }
    val openNotifications = {
        if (stack.current != Route.Notifications) stack.push(Route.Notifications)
        viewModel.loadProfile(org.linuxdo.android.data.ProfileSection.Notifications, refresh = true)
        viewModel.refreshUnread(force = true)
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            holder.SaveableStateProvider(selected.name) {
                key(selected) {
                    IosNavHost(stack, interactive = browsing && !topicCreator.open && !showSavedTopics && !topicSearch.open && !metadataEditor.open) { route ->
                        val active = browsing && stack.current == route
                        // 仅回写标题时保持加载身份，避免保存后重新按阅读游标跳楼。
                        val routeLoadIdentity = if (route is Route.TopicDetail) route.copy(title = "") else route
                        // 首次详情响应可补齐历史位置，之后固定；0 代表新话题，不能被阅读回执改写。
                        var entryResumeFloor by rememberSaveable(routeLoadIdentity) {
                            mutableIntStateOf((route as? Route.TopicDetail)?.let {
                                viewModel.resolveResumePostNumber(it.topicId, it.postNumber)
                            } ?: -1)
                        }
                        var topicEntryLoaded by rememberSaveable(routeLoadIdentity) { mutableStateOf(false) }
                        val entryDetail = (route as? Route.TopicDetail)?.let { details[it.topicId] }
                        if (entryResumeFloor == -1 && topicEntryLoaded && entryDetail?.topic != null && !entryDetail.loading) {
                            SideEffect { entryResumeFloor = entryDetail.topic.lastReadPostNumber?.takeIf { it > 1 } ?: 0 }
                        }
                        val resumePostNumber = entryResumeFloor.takeIf { it > 0 }
                        LaunchedEffect(route, active) {
                            if (active) when (route) {
                                is Route.TopicDetail -> {
                                    if (!topicEntryLoaded || viewModel.details.value[route.topicId]?.topic == null) {
                                        topicEntryLoaded = true
                                        viewModel.loadTopic(route.topicId, postNumber = route.postNumber,
                                            resumePostNumber = resumePostNumber,
                                            restoredPosition = route.savedPosition ?: route.postNumber?.let {
                                                org.linuxdo.android.data.TopicReadingPosition(postNumber = it, atTop = it == 1,
                                                    descending = details[route.topicId]?.descending ?: false)
                                            })
                                    }
                                }
                                Route.CategoryList -> viewModel.loadCategories()
                                Route.TopicList -> if (state.latest.needsRefresh) viewModel.refreshLatest()
                                Route.Search -> if (search.results.needsRefresh) viewModel.updateSearch(search.query)
                                is Route.TagTopics -> viewModel.loadTagTopics(route.tag)
                                is Route.CategoryTopics -> {
                                    if (!categories.loaded && !categories.loading) viewModel.loadCategories()
                                    viewModel.loadCategory(route.categoryId, route.slug)
                                }
                                is Route.UserTopics -> viewModel.loadUserTopics(route.username, route.isBookmark)
                                is Route.UserReplies -> viewModel.loadUserReplies(route.username, filter = route.filter)
                                Route.Drafts -> {
                                    if (!categories.loaded && !categories.loading) viewModel.loadCategories()
                                    viewModel.loadDrafts(refresh = true)
                                }
                                Route.Chat -> viewModel.loadChatChannels()
                                is Route.FollowList -> viewModel.loadFollowing(route.username)
                                else -> Unit
                            }
                        }
                        when (route) {
                            Route.TopicList -> TopicListScreen(
                                title = state.homeFeed.label,
                                state = state.latest,
                                feedGraphicMode = feedGraphicMode,
                                feedDisplayMode = feedDisplayMode,
                                showTopicUnreadDot = showTopicUnreadDot,
                                topicUnreadDotPosition = topicUnreadDotPosition,
                                blockedUsernames = blockedUsernameSet,
                                onRefresh = viewModel::refreshLatest,
                                onLoadMore = viewModel::loadMoreLatest,
                                onOpenTopic = { topic ->
                                    viewModel.recordTopicListOpen(topic.id, topic.postsCount)
                                    stack.push(Route.TopicDetail(topic.id, topic.title))
                                },
                                onOpenUser = { username, primaryAvatarUrl, staticAvatarUrl, displayName ->
                                    viewModel.openUserCard(username, primaryAvatarUrl, staticAvatarUrl, displayName)
                                },
                                onPrefetchExcerpt = viewModel::prefetchTopicExcerpt,
                                onPrefetchExcerpts = viewModel::prefetchTopicExcerpts,
                                onScrollBusyChanged = viewModel::setTopicListScrollBusy,
                                onBarsVisibilityChange = viewModel::setBottomBarVisible,
                                barsVisible = isBottomBarVisible,
                                active = active && listForeground,
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = {},
                                currentFeed = state.homeFeed,
                                feedStates = feedStates,
                                onFeedSelect = viewModel::switchHomeFeed,
                                onPreloadFeed = viewModel::preloadHomeFeed,
                                hasUnread = state.unreadCount > 0,
                                onApplyIncoming = { viewModel.applyIncomingTopics(state.homeFeed) },
                                onSetNotificationLevel = { topic, level ->
                                    viewModel.setTopicNotificationLevel(topic.id, level)
                                },
                                onCreateTopic = if (state.username != null) ({ viewModel.openTopicCreator(null) }) else null,
                                onBookmarkTopic = { topic, onResult ->
                                    viewModel.bookmarkTopicFromList(topic.id, onResult)
                                },
                                onFlagTopic = { topic, reasonId, message, onSuccess, onError ->
                                    viewModel.submitTopicFlagFromList(topic.id, reasonId, message, onSuccess, onError)
                                },
                                onSaveTopic = if (savedTopicsUserId > 0L) viewModel::saveTopicFromList else null,
                                sortOrder = topicSortOrder,
                                sortAscending = topicSortAscending,
                                onSortChange = { viewModel.setTopicSort(it) },
                                floatingBallEnabled = floatingBallEnabled,
                                floatingBallActions = floatingBallActions,
                                floatingBallXRatio = floatingBallXRatio,
                                floatingBallYRatio = floatingBallYRatio,
                                floatingBallOnRight = floatingBallOnRight,
                                onFloatingBallPositionChange = viewModel::updateFloatingBallPosition,
                                onNavigateToDrafts = { stack.push(Route.Drafts) },
                                onNavigateToSearch = { selected = MainTab.Search },
                                onNavigateToCategories = { selected = MainTab.Categories },
                                onNavigateToHistory = { stack.push(Route.BrowsingHistory) },
                                onNavigateToConnect = { showConnectSheet = true },
                                onNavigateToChat = { stack.push(Route.Chat) },
                                onNavigateToApps = { globalBrowserUrl = org.linuxdo.android.ui.screen.LINUXDO_APPS_PLAZA_URL },
                                    onNavigateToBookmarks = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserTopics(username, "我的收藏", isBookmark = true))
                                        }
                                    },
                                    onNavigateToFollowing = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.FollowList(username))
                                        }
                                    },
                                    onNavigateToLikes = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的点赞", filter = "1"))
                                        }
                                    },
                                    onNavigateToReplies = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的回复", filter = "5"))
                                        }
                                    },
                            )

                            is Route.TopicDetail -> {
                                val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
                                val isForeground = active && !contentObscured && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
                                DisposableEffect(lifecycle, route.topicId, active, contentObscured) {
                                    fun updateReadingActivity() {
                                        viewModel.setTopicActive(route.topicId, active && !contentObscured &&
                                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                    }
                                    val observer = LifecycleEventObserver { _, _ -> updateReadingActivity() }
                                    lifecycle.addObserver(observer)
                                    updateReadingActivity()
                                    onDispose {
                                        lifecycle.removeObserver(observer)
                                        viewModel.setTopicActive(route.topicId, false)
                                    }
                                }
                                // 离开这篇帖子时结算一次阅读进度(仅在用户开启同步后才会真正发请求)。
                                DisposableEffect(route.topicId) {
                                    onDispose {
                                        viewModel.setTopicActive(route.topicId, false)
                                        viewModel.leaveTopic(route.topicId)
                                    }
                                }
                                TopicDetailScreen(
                                    title = route.title,
                                    state = details[route.topicId] ?: DetailUiState(loading = true),
                                    initialPosition = route.savedPosition,
                                    onReadingPosition = { viewModel.updateSavedTopicPosition(savedTopicsUserId, route.topicId, it) },
                                    savedTopicCount = savedTopics.size,
                                    onOpenSavedTopics = { showSavedTopics = true },
                                    onBack = { stack.pop() },
                                    onRetry = { viewModel.retryTopic(route.topicId, resumePostNumber) },
                                    onRefresh = { viewModel.refreshTopic(route.topicId) },
                                    onLoadMore = { viewModel.loadTopic(route.topicId, more = true) },
                                    onLoadPrevious = { viewModel.loadTopic(route.topicId, previous = true) },
                                    initialPostNumber = resumePostNumber,
                                    onUnknownTag = { viewModel.diagnostics.log("未知 cooked 标签: $it") },
                                    treeView = treeView,
                                    showUnreadDot = showUnreadDot,
                                    unreadDotPosition = unreadDotPosition,
                                    onLoadReplies = { viewModel.loadReplies(route.topicId, it) },
                                    onReact = { postId, reaction -> viewModel.react(route.topicId, postId, reaction) },
                                    onBoost = { postId, raw -> viewModel.createBoost(route.topicId, postId, raw) },
                                    onDeleteBoost = { postId, boostId -> viewModel.deleteBoost(route.topicId, postId, boostId) },
                                    onFlagBoost = { postId, boostId, reasonId, message ->
                                        viewModel.flagBoost(route.topicId, postId, boostId, reasonId, message)
                                    },
                                    onOpenUser = viewModel::openUserCard,
                                    onRefreshPost = { viewModel.refreshPost(route.topicId, it) },
                                    onSolution = { viewModel.setSolution(route.topicId, it) },
                                    composer = composer,
                                    onOpenComposer = viewModel::openComposer,
                                    onDraftChange = viewModel::updateDraft,
                                    onSubmitComposer = viewModel::submitComposer,
                                    onCloseComposer = viewModel::closeComposer,
                                    onDeletePost = { viewModel.deletePost(route.topicId, it) },
                                    onReloadFromStart = { viewModel.reloadFromStart(route.topicId) },
                                    onCreatedPostShown = { viewModel.clearCreatedPost(route.topicId) },
                                    onAttach = viewModel::attachToComposer,
                                    onRemoveComposerAttachment = viewModel::removeComposerAttachment,
                                    topicId = route.topicId,
                                    onOpenTopic = { id, title, postNumber ->
                                        stack.push(Route.TopicDetail(id, title, postNumber))
                                    },
                                    onJumpToFloor = { viewModel.jumpToFloor(route.topicId, it) },
                                    onOpenTag = { tag -> stack.push(Route.TagTopics(tag)) },
                                    active = isForeground,
                                    trailing = {
                                        var more by remember { mutableStateOf(false) }
                                        val shareActions = org.linuxdo.android.ui.screen.LocalTopicShareActions.current
                                        Box {
                                            TextButton(onClick = { more = true }, modifier = Modifier.width(44.dp)) {
                                                Text("⋯", color = IosTheme.colors.accent, fontSize = 22.sp)
                                            }
                                            androidx.compose.material3.DropdownMenu(
                                                expanded = more, onDismissRequest = { more = false },
                                                containerColor = if (IosTheme.colors.isDark) IosTheme.colors.card else Color.White,
                                                tonalElevation = 0.dp,
                                                modifier = Modifier.background(if (IosTheme.colors.isDark) IosTheme.colors.card else Color.White),
                                            ) {
                                                val isSaved = savedTopics.any { it.topicId == route.topicId }
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            PostActionIcon(PostAction.Bookmark, isSaved, modifier = Modifier.size(18.dp))
                                                            Spacer(Modifier.width(8.dp))
                                                            Text(
                                                                text = if (isSaved) "已暂存" else "话题暂存",
                                                                color = if (isSaved) IosTheme.colors.accent else IosTheme.colors.label,
                                                            )
                                                        }
                                                    },
                                                    enabled = savedTopicsUserId > 0L && !savedTopicsBusy && details[route.topicId]?.topic != null,
                                                    onClick = {
                                                        more = false
                                                        if (isSaved) showSavedTopics = true else viewModel.saveTopic(savedTopicsUserId, route.topicId)
                                                    },
                                                )
                                                if (details[route.topicId]?.topic?.details?.canEdit == true) {
                                                    androidx.compose.material3.DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                PostActionIcon(PostAction.Edit, false, modifier = Modifier.size(18.dp))
                                                                Spacer(Modifier.width(8.dp))
                                                                Text("话题编辑", color = IosTheme.colors.label)
                                                            }
                                                        },
                                                        onClick = {
                                                            more = false
                                                            viewModel.openTopicMetadataEditor(route.topicId)
                                                        },
                                                    )
                                                }
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            PostActionIcon(PostAction.Search, false, modifier = Modifier.size(18.dp))
                                                            Spacer(Modifier.width(8.dp))
                                                            Text("帖内搜索", color = IosTheme.colors.label)
                                                        }
                                                    },
                                                    onClick = {
                                                        more = false
                                                        viewModel.openTopicSearch(route.topicId, details[route.topicId]?.topic?.title ?: route.title)
                                                    },
                                                )
                                                shareActions?.let { actions ->
                                                    androidx.compose.material3.DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                PostActionIcon(PostAction.Share, false, modifier = Modifier.size(18.dp))
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(if (actions.shareLoading) "正在生成分享图…" else "生成分享图", color = IosTheme.colors.label)
                                                            }
                                                        },
                                                        enabled = !actions.shareLoading,
                                                        onClick = {
                                                            more = false
                                                            actions.onShareImage()
                                                        },
                                                    )
                                                    androidx.compose.material3.DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                PostActionIcon(PostAction.Markdown, false, modifier = Modifier.size(18.dp))
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(if (actions.copyLoading) "正在复制…" else "复制 Markdown 原文", color = IosTheme.colors.label)
                                                            }
                                                        },
                                                        enabled = !actions.copyLoading,
                                                        onClick = {
                                                            more = false
                                                            actions.onCopyMarkdown()
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onToggleOrder = { viewModel.toggleCommentOrder(route.topicId) },
                                    onPostsVisible = { viewModel.onPostsVisible(route.topicId, it) },
                                    onLiveViewport = { atEnd, scrolling -> viewModel.onTopicViewport(route.topicId, atEnd, scrolling) },
                                    syncReadProgress = syncReadProgress,
                                    onScrolled = { viewModel.onTopicScrolled(route.topicId) },
                                    onOverlayActive = { detailOverlay = it },
                                    onSetFilterUser = { viewModel.setTopicAuthorFilter(route.topicId, it) },
                                    onVotePoll = { postId, pollName, optionIds -> viewModel.votePoll(route.topicId, postId, pollName, optionIds) },
                                    onSetNotificationLevel = { viewModel.setTopicNotificationLevel(route.topicId, it) },
                                    signedIn = state.username != null,
                                    onToggleBookmark = { viewModel.toggleBookmark(route.topicId, it) },
                                    onOpenFlagDialog = { viewModel.openFlagDialog(route.topicId, it) },
                                    onCloseFlagDialog = { viewModel.closeFlagDialog(route.topicId) },
                                    onSubmitFlag = { postId, reasonId, message ->
                                        viewModel.submitFlag(route.topicId, postId, reasonId, message)
                                    },
                                    blockedUsernames = blockedUsernameSet,
                                )
                            }
                            Route.CategoryList -> {
                                val customSection by viewModel.customSection.collectAsStateWithLifecycle()
                                val context = LocalContext.current
                                CategoryListScreen(
                                    state = categories,
                                    customSection = customSection,
                                    onRetry = { viewModel.loadCategories(retry = true) },
                                    onOpen = { stack.push(Route.CategoryTopics(it.id, it.slug, it.name)) },
                                    onOpenTopic = { topicId, title, postNumber ->
                                        stack.push(Route.TopicDetail(topicId, title, postNumber))
                                    },
                                    onOpenWebUrl = { url ->
                                        globalBrowserUrl = url
                                    },
                                    onSaveCustomSection = viewModel::saveCustomSection,
                                    onDeleteCustomSection = viewModel::deleteCustomSection,
                                    scrollToTopRequest = scrollToTopRequest,
                                    onSaveOrder = viewModel::saveCategoryOrder,
                                    onResetOrder = viewModel::resetCategoryOrder,
                                    currentTab = categorySubTab,
                                    onTabChange = viewModel::setCategorySubTab,
                                    tagsState = tagsState,
                                    tagSearchQuery = tagSearchQuery,
                                    onTagSearchQueryChange = viewModel::updateTagSearchQuery,
                                    onRetryTags = { viewModel.loadTags(retry = true) },
                                    onOpenTag = { tag -> stack.push(Route.TagTopics(tag)) },
                                    trailing = {},
                                )
                            }

                            is Route.TagTopics -> {
                                val topicState = tagTopics[route.tag] ?: TopicListUiState()
                                LaunchedEffect(route.tag) {
                                    viewModel.loadTagTopics(route.tag)
                                }
                                TopicListScreen(
                                    title = "# ${route.tag}",
                                    state = topicState,
                                    feedGraphicMode = feedGraphicMode,
                                    feedDisplayMode = feedDisplayMode,
                                    showTopicUnreadDot = showTopicUnreadDot,
                                    topicUnreadDotPosition = topicUnreadDotPosition,
                                    blockedUsernames = blockedUsernameSet,
                                    onRefresh = { viewModel.loadTagTopics(route.tag, refresh = true) },
                                    onLoadMore = { viewModel.loadTagTopics(route.tag, more = true) },
                                    onOpenTopic = {
                                        viewModel.recordTopicListOpen(it.id, it.postsCount)
                                        stack.push(Route.TopicDetail(it.id, it.title))
                                    },
                                    onOpenUser = { username, primaryAvatarUrl, staticAvatarUrl, displayName ->
                                        viewModel.openUserCard(username, primaryAvatarUrl, staticAvatarUrl, displayName)
                                    },
                                    onPrefetchExcerpt = viewModel::prefetchTopicExcerpt,
                                    onPrefetchExcerpts = viewModel::prefetchTopicExcerpts,
                                    onScrollBusyChanged = viewModel::setTopicListScrollBusy,
                                    onBarsVisibilityChange = viewModel::setBottomBarVisible,
                                    barsVisible = isBottomBarVisible,
                                    onBack = { stack.pop() },
                                    active = active && listForeground,
                                    trailing = {},
                                    onApplyIncoming = {},
                                    onSetNotificationLevel = { topic, level ->
                                        viewModel.setTopicNotificationLevel(topic.id, level)
                                    },
                                    sortOrder = topicSortOrder,
                                    sortAscending = topicSortAscending,
                                    onSortChange = { viewModel.setTopicSort(it) },
                                    floatingBallEnabled = floatingBallEnabled,
                                    floatingBallActions = floatingBallActions,
                                    floatingBallXRatio = floatingBallXRatio,
                                    floatingBallYRatio = floatingBallYRatio,
                                    floatingBallOnRight = floatingBallOnRight,
                                    onFloatingBallPositionChange = viewModel::updateFloatingBallPosition,
                                    onNavigateToDrafts = { stack.push(Route.Drafts) },
                                    onNavigateToSearch = { selected = MainTab.Search },
                                    onNavigateToCategories = { selected = MainTab.Categories },
                                    onNavigateToHistory = { stack.push(Route.BrowsingHistory) },
                                    onNavigateToConnect = { showConnectSheet = true },
                                    onNavigateToChat = { stack.push(Route.Chat) },
                                onNavigateToApps = { globalBrowserUrl = org.linuxdo.android.ui.screen.LINUXDO_APPS_PLAZA_URL },
                                    onNavigateToBookmarks = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserTopics(username, "我的收藏", isBookmark = true))
                                        }
                                    },
                                    onNavigateToFollowing = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.FollowList(username))
                                        }
                                    },
                                    onNavigateToLikes = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的点赞", filter = "1"))
                                        }
                                    },
                                )
                            }

                            is Route.CategoryTopics -> {
                                val variants = remember(categories.items, route.categoryId) {
                                    val group = compactCategoryGroups(categories.items).firstOrNull { (parent, levels) ->
                                        levels.isNotEmpty() && (parent.id == route.categoryId || levels.any { it.id == route.categoryId })
                                    }
                                    if (group != null) {
                                        (listOf(group.first) + group.second.sortedBy { categoryLevelOf(it) })
                                            .filter { categoryLevelOf(it) in 0..3 }
                                    } else {
                                        emptyList()
                                    }
                                }
                                TopicListScreen(
                                    title = route.name,
                                    state = categoryTopics[route.categoryId] ?: TopicListUiState(),
                                    feedGraphicMode = feedGraphicMode,
                                    feedDisplayMode = feedDisplayMode,
                                    showTopicUnreadDot = showTopicUnreadDot,
                                    topicUnreadDotPosition = topicUnreadDotPosition,
                                    blockedUsernames = blockedUsernameSet,
                                    onRefresh = { viewModel.loadCategory(route.categoryId, route.slug, refresh = true) },
                                    onLoadMore = { viewModel.loadCategory(route.categoryId, route.slug, more = true) },
                                    onOpenTopic = {
                                        viewModel.recordTopicListOpen(it.id, it.postsCount)
                                        stack.push(Route.TopicDetail(it.id, it.title))
                                    },
                                    onOpenUser = { username, primaryAvatarUrl, staticAvatarUrl, displayName ->
                                        viewModel.openUserCard(username, primaryAvatarUrl, staticAvatarUrl, displayName)
                                    },
                                    onPrefetchExcerpt = viewModel::prefetchTopicExcerpt,
                                    onPrefetchExcerpts = viewModel::prefetchTopicExcerpts,
                                    onScrollBusyChanged = viewModel::setTopicListScrollBusy,
                                    onBarsVisibilityChange = viewModel::setBottomBarVisible,
                                    barsVisible = isBottomBarVisible,
                                    onBack = { stack.pop() },
                                    active = active && listForeground,
                                    trailing = {},
                                    onApplyIncoming = { viewModel.applyIncomingTopics(state.homeFeed, categoryId = route.categoryId) },
                                    categoryVariants = variants,
                                    currentCategoryId = route.categoryId,
                                    onSelectCategoryVariant = { target ->
                                        if (target.id != route.categoryId) {
                                            stack.replaceCurrent(Route.CategoryTopics(target.id, target.slug, target.name))
                                            viewModel.loadCategory(target.id, target.slug)
                                        }
                                    },
                                    onSetNotificationLevel = { topic, level ->
                                        viewModel.setTopicNotificationLevel(topic.id, level)
                                    },
                                    onCreateTopic = if (state.username != null) ({ viewModel.openTopicCreator(route.categoryId.toInt()) }) else null,
                                    onBookmarkTopic = { topic, onResult ->
                                        viewModel.bookmarkTopicFromList(topic.id, onResult)
                                    },
                                    onFlagTopic = { topic, reasonId, message, onSuccess, onError ->
                                        viewModel.submitTopicFlagFromList(topic.id, reasonId, message, onSuccess, onError)
                                    },
                                    onSaveTopic = if (savedTopicsUserId > 0L) viewModel::saveTopicFromList else null,
                                    sortOrder = topicSortOrder,
                                    sortAscending = topicSortAscending,
                                    onSortChange = { viewModel.setTopicSort(it, currentCategoryId = route.categoryId, currentCategorySlug = route.slug) },
                                    floatingBallEnabled = floatingBallEnabled,
                                    floatingBallActions = floatingBallActions,
                                    floatingBallXRatio = floatingBallXRatio,
                                    floatingBallYRatio = floatingBallYRatio,
                                    floatingBallOnRight = floatingBallOnRight,
                                    onFloatingBallPositionChange = viewModel::updateFloatingBallPosition,
                                    onNavigateToDrafts = { stack.push(Route.Drafts) },
                                    onNavigateToSearch = { selected = MainTab.Search },
                                    onNavigateToCategories = { selected = MainTab.Categories },
                                    onNavigateToHistory = { stack.push(Route.BrowsingHistory) },
                                    onNavigateToConnect = { showConnectSheet = true },
                                    onNavigateToChat = { stack.push(Route.Chat) },
                                onNavigateToApps = { globalBrowserUrl = org.linuxdo.android.ui.screen.LINUXDO_APPS_PLAZA_URL },
                                    onNavigateToBookmarks = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserTopics(username, "我的收藏", isBookmark = true))
                                        }
                                    },
                                    onNavigateToFollowing = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.FollowList(username))
                                        }
                                    },
                                    onNavigateToLikes = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的点赞", filter = "1"))
                                        }
                                    },
                                    onNavigateToReplies = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的回复", filter = "5"))
                                        }
                                    },
                                )
                            }

                            Route.Search -> SearchScreen(
                                state = search,
                                onQuery = viewModel::updateSearch,
                                onLoadMore = viewModel::loadMoreSearch,
                                onOpenTopic = {
                                    viewModel.recordTopicListOpen(it.topic.id, it.topic.postsCount)
                                    stack.push(Route.TopicDetail(it.topic.id, it.topic.title, it.targetPostNumber))
                                },
                                onOrder = viewModel::setSearchOrder,
                                active = active,
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = {},
                                searchHistory = searchHistory,
                                onSelectHistory = { keyword ->
                                    viewModel.updateSearch(keyword)
                                    viewModel.addSearchHistory(keyword)
                                },
                                onClearHistory = viewModel::clearSearchHistory,
                            )

                            Route.Profile -> ProfileScreen(state.username, profile, viewModel::loadProfile,
                                onOpenTopic = { id, title, postNumber ->
                                    val currentRoute = stack.current
                                    if (currentRoute is Route.TopicDetail && currentRoute.topicId == id) {
                                        viewModel.loadTopic(
                                            id,
                                            postNumber = postNumber,
                                            resumePostNumber = viewModel.resolveResumePostNumber(id, postNumber),
                                        )
                                    } else {
                                        stack.push(Route.TopicDetail(id, title, postNumber))
                                    }
                                },
                                onOpenUserTopics = { username, title, isBookmark ->
                                    stack.push(Route.UserTopics(username, title, isBookmark))
                                },
                                onOpenUserReplies = { username ->
                                    stack.push(Route.UserReplies(username))
                                },
                                onOpenUserActions = { username, title, filter ->
                                    stack.push(Route.UserReplies(username, title, filter))
                                },
                                draftCount = drafts.items.size.toString(),
                                onOpenDrafts = { stack.push(Route.Drafts) },
                                onDiagnostics = { stack.push(Route.Diagnostics) },
                                onSettings = { stack.push(Route.Settings) },
                                accountState = accountState,
                                onRefreshAccounts = viewModel::refreshAccounts,
                                onSwitchAccount = viewModel::switchAccount,
                                onRemoveAccount = viewModel::removeAccount,
                                onAddAccount = viewModel::addAccount,
                                onOpenConnect = { showConnectSheet = true },
                                onOpenHistory = { stack.push(Route.BrowsingHistory) },
                                onOpenFollowing = { state.username?.let { stack.push(Route.FollowList(it)) } },
                                onOpenChat = { stack.push(Route.Chat) },
                                onOpenBlacklist = { stack.push(Route.Blacklist) },
                                onOpenUser = { username, primaryAvatar, staticAvatar ->
                                    stack.push(Route.UserProfile(username, primaryAvatar, staticAvatar))
                                },
                                onComposePrivateMessage = { recipient -> privateMessageRecipient = recipient },
                                onLogout = viewModel::logout,
                                userBadges = userBadges,
                                isUserBadgesLoading = isUserBadgesLoading,
                                onRefreshUserBadges = viewModel::loadUserBadges,
                                cdkScore = cdkScoreState.score.takeIf {
                                    cdkScoreState.username.equals(state.username, ignoreCase = true)
                                },
                                onLoadCdkScore = { viewModel.loadCdkScore() },
                                active = active,
                                onReadNotification = viewModel::readNotification,
                                notificationFilter = notificationFilter,
                                onNotificationFilter = viewModel::setNotificationFilter,
                                unreadFilters = state.unreadFilters,
                                scrollToTopRequest = scrollToTopRequest,
                                trailing = {})

                            Route.Drafts -> DraftsScreen(
                                state = drafts,
                                categories = categories.items,
                                onBack = { stack.pop() },
                                onRefresh = { viewModel.loadDrafts(refresh = true) },
                                onEditDraft = { draft ->
                                    if (draft.topicId != null && draft.topicId > 0L) {
                                        stack.pop()
                                        stack.push(Route.TopicDetail(draft.topicId, draft.title, draft.replyToPostNumber))
                                        viewModel.openReplyWithDraft(draft)
                                    } else {
                                        viewModel.openDraftInCreator(draft)
                                    }
                                },
                                onDeleteDraft = viewModel::deleteDraft,
                                trailing = {},
                            )

                            Route.BrowsingHistory -> HistoryScreen(
                                entries = history.entries,
                                query = history.query,
                                loading = history.loading,
                                onQueryChange = viewModel::setHistoryQuery,
                                onClear = viewModel::clearHistory,
                                onOpenTopic = { id, title, postNumber ->
                                    stack.push(Route.TopicDetail(id, title, postNumber))
                                },
                                onBack = { stack.pop() },
                            )

                            Route.Chat -> ChatListScreen(
                                state = chat,
                                active = active,
                                onLoad = { viewModel.loadChatChannels() },
                                onRefresh = { viewModel.loadChatChannels(refresh = true) },
                                onOpenChannel = { channel -> stack.push(Route.ChatRoom(channel.id, channel.displayTitle)) },
                                onBack = if (stack.canPop) ({ stack.pop() }) else null,
                            )

                            is Route.ChatRoom -> {
                                val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
                                val roomActive = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
                                val channel = chat.channels.firstOrNull { it.id == route.channelId }
                                    ?: org.linuxdo.android.data.ChatChannelSummary(id = route.channelId, title = route.title)
                                val currentUsername = accountState.accounts.firstOrNull { it.userId == accountState.activeUserId }?.username
                                ChatRoomScreen(
                                    channel = channel,
                                    state = chat,
                                    active = roomActive,
                                    currentUsername = currentUsername,
                                    onLoad = viewModel::loadChatRoom,
                                    onLoadPast = viewModel::loadMoreChatHistory,
                                    onSend = viewModel::sendChatMessage,
                                    onUploadAttachment = viewModel::uploadChatAttachment,
                                    onOpenUser = viewModel::openUserCard,
                                    onBack = { stack.pop() },
                                )
                            }

                            is Route.FollowList -> FollowListScreen(
                                state = followList,
                                onBack = { stack.pop() },
                                onRetry = { viewModel.loadFollowing(route.username, refresh = true) },
                                onOpenUser = viewModel::openUserCard,
                                onUnfollow = viewModel::unfollowUser,
                                onUnfollowBatch = viewModel::unfollowBatch,
                            )

                            Route.Blacklist -> BlacklistScreen(
                                users = blockedUsers,
                                onBack = { stack.pop() },
                                onOpenUser = viewModel::openUserCard,
                                onRemove = viewModel::removeFromBlacklist,
                                onRemoveBatch = viewModel::removeBatchFromBlacklist,
                            )

                            is Route.UserTopics -> {
                                val key = "${if (route.isBookmark) "bookmark" else "created"}:${route.username}"
                                TopicListScreen(
                                    title = route.title,
                                    state = userTopicStates[key] ?: TopicListUiState(),
                                    feedGraphicMode = feedGraphicMode,
                                    feedDisplayMode = feedDisplayMode,
                                    showTopicUnreadDot = showTopicUnreadDot,
                                    topicUnreadDotPosition = topicUnreadDotPosition,
                                    blockedUsernames = blockedUsernameSet,
                                    onRefresh = { viewModel.loadUserTopics(route.username, route.isBookmark, refresh = true) },
                                    onLoadMore = { viewModel.loadUserTopics(route.username, route.isBookmark, more = true) },
                                    onOpenTopic = {
                                        viewModel.recordTopicListOpen(it.id, it.postsCount)
                                        stack.push(Route.TopicDetail(it.id, it.title))
                                    },
                                    onOpenItem = { item ->
                                        viewModel.recordTopicListOpen(item.topic.id, item.topic.postsCount)
                                        stack.push(Route.TopicDetail(item.topic.id, item.topic.title, item.targetPostNumber))
                                    },
                                    onRemoveBookmark = if (route.isBookmark && route.username.equals(state.username, ignoreCase = true)) viewModel::removeBookmarkFromList else null,
                                    onScrollBusyChanged = viewModel::setTopicListScrollBusy,
                                    onBarsVisibilityChange = viewModel::setBottomBarVisible,
                                    barsVisible = isBottomBarVisible,
                                    onOpenUser = { username, primaryAvatarUrl, staticAvatarUrl, displayName ->
                                        viewModel.openUserCard(username, primaryAvatarUrl, staticAvatarUrl, displayName)
                                    },
                                    onBack = { stack.pop() },
                                    showLargeTitle = false,
                                    active = active,
                                    trailing = {},
                                    onSetNotificationLevel = { topic, level ->
                                        viewModel.setTopicNotificationLevel(topic.id, level)
                                    },
                                    onBookmarkTopic = if (route.isBookmark) null else { topic, onResult ->
                                        viewModel.bookmarkTopicFromList(topic.id, onResult)
                                    },
                                    onFlagTopic = { topic, reasonId, message, onSuccess, onError ->
                                        viewModel.submitTopicFlagFromList(topic.id, reasonId, message, onSuccess, onError)
                                    },
                                    onSaveTopic = if (savedTopicsUserId > 0L) viewModel::saveTopicFromList else null,
                                    sortOrder = topicSortOrder,
                                    sortAscending = topicSortAscending,
                                    onSortChange = { viewModel.setTopicSort(it) },
                                    floatingBallEnabled = floatingBallEnabled,
                                    floatingBallActions = floatingBallActions,
                                    floatingBallXRatio = floatingBallXRatio,
                                    floatingBallYRatio = floatingBallYRatio,
                                    floatingBallOnRight = floatingBallOnRight,
                                    onFloatingBallPositionChange = viewModel::updateFloatingBallPosition,
                                    onNavigateToDrafts = { stack.push(Route.Drafts) },
                                    onNavigateToSearch = { selected = MainTab.Search },
                                    onNavigateToCategories = { selected = MainTab.Categories },
                                    onNavigateToHistory = { stack.push(Route.BrowsingHistory) },
                                    onNavigateToConnect = { showConnectSheet = true },
                                    onNavigateToChat = { stack.push(Route.Chat) },
                                onNavigateToApps = { globalBrowserUrl = org.linuxdo.android.ui.screen.LINUXDO_APPS_PLAZA_URL },
                                    onNavigateToBookmarks = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserTopics(username, "我的收藏", isBookmark = true))
                                        }
                                    },
                                    onNavigateToFollowing = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.FollowList(username))
                                        }
                                    },
                                    onNavigateToLikes = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的点赞", filter = "1"))
                                        }
                                    },
                                    onNavigateToReplies = {
                                        val username = state.username
                                        if (!username.isNullOrBlank()) {
                                            stack.push(Route.UserReplies(username, "我的回复", filter = "5"))
                                        }
                                    },
                                )
                            }

                            is Route.UserReplies -> {
                                val stateKey = if (route.filter == "5") route.username else "${route.username}:${route.filter}"
                                val repliesState = userRepliesStates[stateKey] ?: org.linuxdo.android.data.UserRepliesUiState()
                                org.linuxdo.android.ui.screen.UserRepliesScreen(
                                    title = route.title,
                                    state = repliesState,
                                    onRefresh = { viewModel.loadUserReplies(route.username, refresh = true, filter = route.filter) },
                                    onLoadMore = { viewModel.loadUserReplies(route.username, more = true, filter = route.filter) },
                                    onOpenTopic = { id, title, postNumber ->
                                        stack.push(Route.TopicDetail(id, title, postNumber))
                                    },
                                    onOpenUser = viewModel::openUserCard,
                                    onBack = { stack.pop() },
                                )
                            }

                            is Route.UserProfile -> {
                                UserProfileScreen(
                                    username = route.username,
                                    initialAvatarUrl = route.initialAvatarUrl,
                                    initialStaticAvatarUrl = route.initialStaticAvatarUrl,
                                    onOpenTopic = { id, title, postNumber ->
                                        stack.push(Route.TopicDetail(id, title, postNumber))
                                    },
                                    onOpenUser = viewModel::openUserCard,
                                    onChat = { target ->
                                        viewModel.openChatWithUser(target) { stack.push(it) }
                                    },
                                    onComposePrivateMessage = { recipient -> privateMessageRecipient = recipient },
                                    onBack = { stack.pop() },
                                )
                            }


                            Route.Settings, is Route.SettingsDetail -> SettingsScreen(
                                page = (route as? Route.SettingsDetail)?.page,
                                onOpenPage = { stack.push(Route.SettingsDetail(it)) },
                                treeView = treeView,
                                onTreeView = viewModel::setTreeView,
                                showUnreadDot = showUnreadDot,
                                onShowUnreadDot = viewModel::setShowUnreadDot,
                                unreadDotPosition = unreadDotPosition,
                                onUnreadDotPosition = viewModel::setUnreadDotPosition,
                                showTopicUnreadDot = showTopicUnreadDot,
                                onShowTopicUnreadDot = viewModel::setShowTopicUnreadDot,
                                topicUnreadDotPosition = topicUnreadDotPosition,
                                onTopicUnreadDotPosition = viewModel::setTopicUnreadDotPosition,
                                onBack = { stack.pop() },
                                themeMode = themeMode,
                                onThemeMode = viewModel::setThemeMode,
                                floatingBallEnabled = floatingBallEnabled,
                                onFloatingBallEnabled = viewModel::setFloatingBallEnabled,
                                floatingBallActions = floatingBallActions,
                                onFloatingBallActions = viewModel::setFloatingBallActions,
                                updateState = updateState,
                                onCheckUpdate = viewModel::onVersionItemClick,
                                syncReadProgress = syncReadProgress,
                                onSyncReadProgress = viewModel::setSyncReadProgress,
                                onDiagnostics = { stack.push(Route.Diagnostics) },
                                onLogout = viewModel::logout,
                                autoRefreshIncoming = autoRefreshIncoming,
                                onAutoRefreshIncoming = viewModel::setAutoRefreshIncoming,
                                autoRefreshInterval = autoRefreshInterval,
                                onAutoRefreshInterval = viewModel::setAutoRefreshInterval,
                                autoReadClipboard = autoReadClipboardEnabled,
                                onAutoReadClipboard = viewModel::setAutoReadClipboardEnabled,
                                backgroundNotification = backgroundNotification,
                                onBackgroundNotification = viewModel::setBackgroundNotification,
                                notificationPollInterval = notificationPollInterval,
                                onNotificationPollInterval = viewModel::setNotificationPollInterval,
                                customRealtimeSeconds = customRealtimeSeconds,
                                onCustomRealtimeSeconds = viewModel::setCustomRealtimeSeconds,
                                notificationScope = notificationScope,
                                onNotificationScope = viewModel::setNotificationScope,
                                keepAliveService = keepAliveService,
                                onKeepAliveService = viewModel::setKeepAliveService,
                                onTestNotification = viewModel::testPushNotification,
                                // ── DoH ──────────────────────────────────────────
                                dohEnabled = dohEnabled,
                                onDohEnabled = viewModel::setDohEnabled,
                                dohServers = dohServers,
                                selectedDohServerUrl = selectedDohServerUrl,
                                onDohServerSelected = viewModel::setDohServerSelected,
                                dohAutoFailover = dohAutoFailover,
                                onDohAutoFailover = viewModel::setDohAutoFailover,
                                dohAutoOptimize = dohAutoOptimize,
                                onDohAutoOptimize = viewModel::setDohAutoOptimize,
                                dohForceOverProxy = dohForceOverProxy,
                                onDohForceOverProxy = viewModel::setDohForceOverProxy,
                                dohLatencies = dohLatencies,
                                dohSummaries = dohSummaries,
                                isTestingAllDoh = isTestingAllDoh,
                                onTestDohServer = viewModel::testDohServer,
                                onTestAllDohServers = viewModel::testAllDohServers,
                                smartRouteMode = smartRouteMode,
                                smartDirectLatency = smartDirectLatency,
                                smartDohLatency = smartDohLatency,
                                smartRouteSummary = smartRouteSummary,
                                isSmartEvaluating = isSmartEvaluating,
                                onEvaluateSmartRoute = viewModel::evaluateSmartRoute,
                                onAddCustomDohServer = viewModel::addCustomDohServer,
                                onUpdateDohServer = viewModel::updateDohServer,
                                onDeleteDohServer = viewModel::deleteDohServer,
                                onResetDohServers = viewModel::resetDohServersToDefault,
                                feedGraphicMode = feedGraphicMode,
                                onFeedGraphicMode = viewModel::setFeedGraphicMode,
                                feedDisplayMode = feedDisplayMode,
                                onFeedDisplayMode = viewModel::setFeedDisplayMode,
                                skinPreset = skinPreset,
                                onSkinPreset = viewModel::setSkinPreset,
                                isSelfHealing = isSelfHealing,
                                onSelfHealNetwork = { viewModel.selfHealNetwork(context) },
                            )

                            Route.Notifications -> ProfileScreen(state.username, profile, viewModel::loadProfile,
                                onOpenTopic = { id, title, postNumber ->
                                    viewModel.markNotificationTopicPending(id, postNumber)
                                    val currentRoute = stack.current
                                    if (currentRoute is Route.TopicDetail && currentRoute.topicId == id) {
                                        viewModel.loadTopic(
                                            id,
                                            postNumber = postNumber,
                                            resumePostNumber = viewModel.resolveResumePostNumber(id, postNumber),
                                            fromNotification = true,
                                        )
                                    } else {
                                        stack.push(Route.TopicDetail(id, title, postNumber))
                                    }
                                },
                                onOpenUser = viewModel::openUserCard,
                                onDiagnostics = { stack.push(Route.Diagnostics) }, onSettings = { stack.push(Route.Settings) },
                                active = active, notificationOnly = true, onBack = null,
                                onReadNotification = viewModel::readNotification,
                                onMarkAllNotificationsRead = viewModel::markAllNotificationsRead,
                                notificationFilter = notificationFilter,
                                onNotificationFilter = viewModel::setNotificationFilter,
                                unreadFilters = state.unreadFilters)


                            Route.Diagnostics -> DiagnosticsScreen(
                                state = state,
                                viewModel = viewModel,
                                onBack = { stack.pop() },
                            )

                        }
                    }
                }
            }

            if (topicCreator.open) {
                TopicCreatorSheet(
                    state = topicCreator,
                    categories = categories.items,
                    onTitleChange = viewModel::updateTopicCreatorTitle,
                    onRawChange = viewModel::updateTopicCreatorRaw,
                    onCategoryChange = viewModel::updateTopicCreatorCategory,
                    onTagsChange = viewModel::updateTopicCreatorTags,
                    onSearchTags = viewModel::searchTopicCreatorTags,
                    onAttach = viewModel::attachToTopicCreator,
                    onRemoveAttachment = viewModel::removeTopicCreatorAttachment,
                    onSaveDraft = viewModel::saveTopicDraft,
                    onSubmit = {
                        viewModel.submitTopicCreator { topicId, title, postNumber ->
                            stack.push(Route.TopicDetail(topicId, title, postNumber))
                        }
                    },
                    onClose = viewModel::closeTopicCreator,
                    onClearNotice = viewModel::clearTopicCreatorNotice,
                )
            }
        }
        val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val isDetailRoute = stack.current is Route.TopicDetail || stack.current is Route.Chat || stack.current is Route.ChatRoom
        val isTopicListRoute = stack.current is Route.TopicList || stack.current is Route.CategoryTopics || stack.current is Route.UserTopics || stack.current is Route.TagTopics
        val effectiveBottomBarVisible = isBottomBarVisible || !isTopicListRoute
        val shouldShowTabBar = effectiveBottomBarVisible && !isDetailRoute && !composer.open && !topicCreator.open && !detailOverlay && !isKeyboardOpen
        if (savedTopics.isNotEmpty() && !isDetailRoute && browsing && !contentObscured && !isKeyboardOpen) {
            SavedTopicChip(
                savedTopics.size, { showSavedTopics = true },
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (shouldShowTabBar) 76.dp else 16.dp),
            )
        }
        if (showSavedTopics) {
            SavedTopicsSheet(
                entries = savedTopics,
                currentTopicId = (stack.current as? Route.TopicDetail)?.topicId,
                busy = savedTopicsBusy, notice = savedTopicsNotice, canUndo = canUndoSavedTopics,
                onOpen = { entry ->
                    if ((stack.current as? Route.TopicDetail)?.topicId != entry.topicId) {
                        stack.switchSavedTopic(Route.TopicDetail(entry.topicId, entry.title, entry.position.postNumber, entry.position))
                    }
                    showSavedTopics = false
                },
                onRemove = { viewModel.removeSavedTopic(savedTopicsUserId, it) },
                onPin = { viewModel.pinSavedTopic(savedTopicsUserId, it) },
                onClear = { viewModel.clearSavedTopics(savedTopicsUserId) },
                onUndo = { viewModel.undoSavedTopics(savedTopicsUserId) },
                onDismiss = { showSavedTopics = false },
            )
        } else if (savedTopicsNotice != null && browsing && !contentObscured) {
            SavedTopicsNotice(
                savedTopicsNotice!!, canUndoSavedTopics, { viewModel.undoSavedTopics(savedTopicsUserId) },
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 100.dp),
            )
        }
        if (browsing && topicSearch.open) {
            TopicSearchSheet(topicSearch, { viewModel.updateTopicSearch(it) },
                { viewModel.updateTopicSearch(topicSearch.query, it) }, viewModel::loadMoreTopicSearch,
                viewModel::openTopicSearchHit, viewModel::closeTopicSearch)
        }
        if (browsing && metadataEditor.open) {
            TopicMetadataEditorSheet(metadataEditor, viewModel::updateMetadataTitle, viewModel::updateMetadataCategory,
                viewModel::updateMetadataTags, viewModel::searchMetadataTags, viewModel::submitTopicMetadata,
                { viewModel.openTopicMetadataEditor(metadataEditor.topicId, preserveInput = true) }, viewModel::closeTopicMetadataEditor)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = shouldShowTabBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(animationSpec = androidx.compose.animation.core.tween(220)) { it } +
                androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.slideOutVertically(animationSpec = androidx.compose.animation.core.tween(220)) { it } +
                androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(180)),
        ) {
            TabBar(
                selected,
                homeFeedLabel = state.homeFeed.label,
                categoriesTabLabel = if (categorySubTab == org.linuxdo.android.ui.CategorySectionTab.TAGS) "标签" else "分类",
                unreadCount = state.unreadCount,
            ) { tab ->
                if (browsing) {
                    if (tab == selected) {
                        stacks.getValue(tab).popToRoot()
                        scrollRequests.getValue(tab).intValue++
                    } else {
                        selected = tab
                        viewModel.setBottomBarVisible(true)
                    }
                }
            }
        }

        val requestedMiniAppUrl = org.linuxdo.android.ui.screen.MiniAppSessionPool.requestedMiniAppUrl
        LaunchedEffect(requestedMiniAppUrl, browsing) {
            if (browsing && !requestedMiniAppUrl.isNullOrBlank()) {
                org.linuxdo.android.ui.screen.MiniAppSessionPool.clearRequest()
                globalBrowserUrl = requestedMiniAppUrl
            }
        }

        // 剪贴板若为 L 站应用链接 (https://linux.do/apps/...)，直接消费并进入对应小程序
        LaunchedEffect(detectedClipboardTarget, browsing) {
            val target = detectedClipboardTarget ?: return@LaunchedEffect
            val miniAppUrl = target.second.miniAppUrl
            if (browsing && !miniAppUrl.isNullOrBlank()) {
                viewModel.consumeClipboardTarget(context, target.first)
                globalBrowserUrl = miniAppUrl
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = detectedClipboardTarget != null && detectedClipboardTarget?.second?.isMiniApp != true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (shouldShowTabBar) 64.dp else 16.dp, start = 16.dp, end = 16.dp)
                .zIndex(10f),
            enter = androidx.compose.animation.slideInVertically(animationSpec = androidx.compose.animation.core.tween(240)) { it / 2 } +
                androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.slideOutVertically(animationSpec = androidx.compose.animation.core.tween(200)) { it / 2 } +
                androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(160)),
        ) {
            val target = detectedClipboardTarget
            if (target != null) {
                ClipboardTopicDetectionCard(
                    rawUrl = target.first,
                    link = target.second,
                    onView = {
                        val consumed = viewModel.consumeClipboardTarget(context, target.first)
                        val linkToOpen = consumed ?: target.second
                        if (linkToOpen.isMiniApp) {
                            globalBrowserUrl = linkToOpen.miniAppUrl
                        } else {
                            selected = MainTab.Latest
                            val latestStack = stacks.getValue(MainTab.Latest)
                            val currentRoute = latestStack.current
                            if (currentRoute is Route.TopicDetail && currentRoute.topicId == linkToOpen.topicId) {
                                viewModel.loadTopic(
                                    linkToOpen.topicId,
                                    postNumber = linkToOpen.postNumber,
                                    resumePostNumber = viewModel.resolveResumePostNumber(linkToOpen.topicId, linkToOpen.postNumber),
                                    fromNotification = true,
                                )
                            } else {
                                latestStack.push(Route.TopicDetail(linkToOpen.topicId, "", linkToOpen.postNumber))
                            }
                        }
                    },
                    onDismiss = {
                        viewModel.dismissClipboardTarget(context, target.first)
                    },
                )
            }
        }

        org.linuxdo.android.ui.screen.MiniAppFloatingPill(
            onRestore = { url -> globalBrowserUrl = url },
            modifier = Modifier.zIndex(18f),
        )
    }
    if (showConnectSheet) {
        ConnectWebSheet(
            state = trustReport,
            ldcState = ldcState,
            onLoad = { viewModel.loadTrustReport() },
            onRefresh = { viewModel.loadTrustReport(refresh = true) },
            onLoadLdc = { viewModel.loadLdcUserInfo() },
            onRefreshLdc = { viewModel.loadLdcUserInfo(refresh = true) },
            onAuthorizeLdc = { viewModel.authorizeLdc() },
            onOpenWebUrl = { globalBrowserUrl = it },
            onDismiss = { showConnectSheet = false },
        )
    }
    globalBrowserUrl?.let { url ->
        InAppBrowserSheet(url = url, onDismiss = { globalBrowserUrl = null })
    }
    if (userCard.loading || userCard.user != null || userCard.error != null) {
        UserCardBottomSheet(
            state = userCard,
            onDismiss = viewModel::closeUserCard,
            onFollow = viewModel::toggleFollowUser,
            onMessage = { username ->
                viewModel.closeUserCard()
                privateMessageRecipient = username
            },
            onChat = { username ->
                viewModel.openChatWithUser(username) { route ->
                    stack.push(route)
                }
            },
            onOpenTopics = { username ->
                viewModel.closeUserCard()
                stack.push(Route.UserProfile(username))
            },
            onToggleBlock = viewModel::toggleBlacklistForUserCard,
        )
    }
    privateMessageRecipient?.let { recipient ->
        PrivateMessageComposeSheet(
            initialRecipient = recipient,
            onDismiss = { privateMessageRecipient = null },
            onSend = viewModel::createPrivateMessage,
            onUploadAttachment = viewModel::uploadAttachmentDirect,
            onCreated = { topicId, title ->
                viewModel.loadProfile(org.linuxdo.android.data.ProfileSection.Messages, refresh = true)
                stack.push(Route.TopicDetail(topicId, title))
            },
        )
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

@Composable
private fun ClipboardTopicDetectionCard(
    rawUrl: String,
    link: InternalTopicLink,
    onView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = IosTheme.colors.accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(IosTheme.colors.card)
            .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                        val stroke = Stroke(2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val l1 = Path().apply {
                            moveTo(10f, 13f); lineTo(8f, 15f); cubicTo(6.5f, 16.5f, 6.5f, 19f, 8f, 20.5f); cubicTo(9.5f, 22f, 12f, 22f, 13.5f, 20.5f); lineTo(15.5f, 18.5f)
                        }
                        val l2 = Path().apply {
                            moveTo(14f, 11f); lineTo(16f, 9f); cubicTo(17.5f, 7.5f, 17.5f, 5f, 16f, 3.5f); cubicTo(14.5f, 2f, 12f, 2f, 10.5f, 3.5f); lineTo(8.5f, 5.5f)
                        }
                        drawPath(l1, accentColor, style = stroke)
                        drawPath(l2, accentColor, style = stroke)
                        drawLine(accentColor, Offset(9.5f, 14.5f), Offset(14.5f, 9.5f), strokeWidth = 2.0f, cap = StrokeCap.Round)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "检测到剪贴板帖子链接",
                    style = IosTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                    color = IosTheme.colors.label,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                val floorText = if (link.postNumber != null) " · #${link.postNumber}楼" else ""
                Text(
                    text = "话题 #${link.topicId}$floorText",
                    style = IosTheme.type.caption.copy(fontSize = 12.sp),
                    color = IosTheme.colors.secondaryLabel,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosTheme.colors.accent)
                    .clickable(onClick = onView)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "打开",
                    color = Color.White,
                    style = IosTheme.type.footnote.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                )
            }

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = IosTheme.colors.secondaryLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

package org.linuxdo.android

import android.content.Intent
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.linuxdo.android.ui.AppShell
import org.linuxdo.android.ui.MainViewModel
import org.linuxdo.android.ui.LocalEditorSuggestionSource
import org.linuxdo.android.ui.LocalPostMarkdownLoader
import org.linuxdo.android.ui.LocalPostRevisionLoader
import org.linuxdo.android.ui.LocalTopicPreviewLoader
import androidx.compose.runtime.CompositionLocalProvider

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val clipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
    }
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardListenerGeneration = 0
    private var clipboardResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { uri ->
            viewModel.handleWebAuthCallback(uri)
        }
        handleNotificationIntent(intent)
        handleDeepLinkIntent(intent)
        // 在 Activity 尚未进入 RESUMED 前提前初始化 GeckoRuntime，避免首帧合成（已处于 RESUMED）时
        // GeckoRuntime.init 同步触发 LifecycleListener.onResume 与 GeckoThread 启动产生竞态。
        org.linuxdo.android.net.gecko.GeckoManager.getOrCreateRuntime(applicationContext)
        val browser = (application as App).container.browser
        setContent {
            CompositionLocalProvider(
                LocalEditorSuggestionSource provides viewModel::searchEditorSuggestions,
                LocalPostMarkdownLoader provides viewModel::loadPostMarkdown,
                LocalPostRevisionLoader provides viewModel::loadPostRevision,
                LocalTopicPreviewLoader provides viewModel::loadTopicPreview,
            ) {
                AppShell(viewModel, browser)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clipboardResumed = true
        // 先回收后台久置的旧连接，再放行下面的未读刷新等首批请求。
        (application as App).container.onAppForeground()
        val sessionStore = (application as App).container.sessionStore
        sessionStore.setAppInForeground(true)
        // 回到前台后由前台服务的协程轮询接管，撤掉最小化兜底闹钟
        org.linuxdo.android.service.NotificationKeepAliveService.cancelMinimizedPoll(applicationContext)
        if (sessionStore.isBackgroundNotificationEnabled() && sessionStore.hasSession()) {
            org.linuxdo.android.service.NotificationKeepAliveService.start(applicationContext)
            if (!org.linuxdo.android.service.NotificationHelper.isIgnoringBatteryOptimizations(this) &&
                !sessionStore.hasAutoPromptedBatteryWhitelist()
            ) {
                sessionStore.markAutoPromptedBatteryWhitelist()
                org.linuxdo.android.service.NotificationHelper.promptIgnoreBatteryOptimizationsIfNeeded(this)
            }
        }
        viewModel.refreshUnread(force = true)
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                viewModel.checkClipboard(this)
                observeClipboardChanges()
            }
        }, 300L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            viewModel.checkClipboard(this)
            observeClipboardChanges()
        } else {
            stopObservingClipboardChanges()
        }
    }

    private fun observeClipboardChanges() {
        if (clipboardListener != null) return
        val generation = ++clipboardListenerGeneration
        window.decorView.post {
            if (generation != clipboardListenerGeneration || !clipboardResumed ||
                !hasWindowFocus() || isFinishing || isDestroyed
            ) return@post
            // 获焦点后读取快照，再监听后续变化；消费状态由文本尾缀判断。
            viewModel.checkClipboard(this)
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                if (generation == clipboardListenerGeneration && clipboardResumed && hasWindowFocus()) {
                    viewModel.checkClipboard(this)
                }
            }
            clipboardManager?.addPrimaryClipChangedListener(listener)
            clipboardListener = listener
        }
    }

    private fun stopObservingClipboardChanges() {
        clipboardListenerGeneration++
        clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipboardListener = null
    }

    override fun onPause() {
        clipboardResumed = false
        stopObservingClipboardChanges()
        super.onPause()
    }

    override fun onDestroy() {
        stopObservingClipboardChanges()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        val appContext = applicationContext
        val sessionStore = (application as App).container.sessionStore
        (application as App).container.onAppBackground()
        sessionStore.setAppInForeground(false)
        // 退回桌面（最小化）时立即触发一次即时后台未读检查，不留时间空隙
        org.linuxdo.android.service.NotificationWorker.triggerImmediate(appContext)
        // 亮屏最小化：预约兜底闹钟，补上「亮屏时 Doze 闹钟被取消、只剩协程」的缺口。
        // 息屏场景由 handleScreenOff 的 Doze 阶梯接管，这里不抢。
        if (org.linuxdo.android.service.NotificationKeepAliveService.isScreenInteractive(appContext)) {
            org.linuxdo.android.service.NotificationKeepAliveService.scheduleMinimizedPoll(appContext)
        }
        // 荣耀 MagicOS / 华为桌面在用户打开过应用界面后会自动清空桌面图标角标；
        // 退回桌面 (onStop) 时必须把当前未读数重新写回桌面 Provider，并延迟补写一次确保桌面 onResume 后生效。
        val badge = sessionStore.getLastBadgeCount()
        org.linuxdo.android.service.NotificationHelper.updateBadgeCount(appContext, badge, source = "退出前台")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val latest = sessionStore.getLastBadgeCount()
            org.linuxdo.android.service.NotificationHelper.updateBadgeCount(appContext, latest, source = "退出前台400ms")
        }, 400L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()?.let { uri ->
            viewModel.handleWebAuthCallback(uri)
        }
        handleNotificationIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val url = uri.toString()
        if (uri.scheme == "discourse" && uri.host == "auth_redirect") return
        val link = org.linuxdo.android.data.parseInternalTopicUrl(url) ?: return
        intent.data = null
        viewModel.navigateToTopicFromNotification(link.topicId, link.postNumber)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val topicId = intent.getLongExtra("topic_id", -1L)
        val notificationId = intent.getLongExtra("notification_id", -1L).takeIf { it > 0L }
        val notificationType = intent.getIntExtra("notification_type", -1).takeIf { it > 0 }
        val postNumber = intent.getIntExtra("post_number", -1).takeIf { it > 0 }

        intent.removeExtra("topic_id")
        intent.removeExtra("notification_id")
        intent.removeExtra("notification_type")
        intent.removeExtra("post_number")

        if (topicId > 0L) {
            viewModel.navigateToTopicFromNotification(topicId, postNumber, notificationId, notificationType)
        } else if (notificationId != null) {
            viewModel.readNotification(notificationId, notificationType)
        }
    }
}

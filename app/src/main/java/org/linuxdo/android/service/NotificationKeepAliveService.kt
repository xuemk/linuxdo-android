package org.linuxdo.android.service

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.linuxdo.android.push.LinuxDoPushSdk
import org.linuxdo.android.push.ObfHelper
import org.linuxdo.android.push.PushSessionProvider

class NotificationKeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isReceiverRegistered = false
    private var graceWakeLock: PowerManager.WakeLock? = null
    private var pollWakeLock: PowerManager.WakeLock? = null
    private var inScreenOffGrace = false
    private var suppressNextScreenOff = false
    private var pollJob: Job? = null
    private var quickPollJob: Job? = null
    private var graceOffJob: Job? = null
    private var isStopping = false

    private fun logService(message: String) {
        NotificationHelper.logDiagnostic(this, "push.service", "[保活] $message", true)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isStopping) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureNotificationChannels(this)
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            isReceiverRegistered = true
        }.onFailure {
            logService("屏幕广播注册失败 ${it.javaClass.simpleName}")
        }
    }

    private fun unregisterScreenReceiver() {
        if (!isReceiverRegistered) return
        runCatching {
            unregisterReceiver(screenReceiver)
        }
        isReceiverRegistered = false
    }

    private fun acquireGraceWakeLock(timeoutMs: Long) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (graceWakeLock == null) {
            graceWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LinuxDo:ScreenOffGraceWakeLock",
            ).apply {
                setReferenceCounted(false)
            }
        }
        runCatching { graceWakeLock?.acquire(timeoutMs) }
            .onSuccess { logService("宽限期唤醒锁 held=${graceWakeLock?.isHeld} 超时=${timeoutMs / 1000}s") }
            .onFailure { logService("宽限期唤醒锁申请失败 ${it.javaClass.simpleName}") }
    }

    private fun releaseGraceWakeLock() {
        runCatching {
            if (graceWakeLock?.isHeld == true) {
                graceWakeLock?.release()
                logService("宽限期唤醒锁已释放")
            }
        }
    }

    private fun acquireShortWakeLock(timeoutMs: Long = 15_000L) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pollWakeLock == null) {
            pollWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LinuxDo:DozePollWakeLock",
            ).apply {
                setReferenceCounted(false)
            }
        }
        runCatching { pollWakeLock?.acquire(timeoutMs) }
            .onSuccess { logService("短时唤醒锁 held=${pollWakeLock?.isHeld} 超时=${timeoutMs / 1000}s") }
            .onFailure { logService("短时唤醒锁申请失败 ${it.javaClass.simpleName}") }
    }

    private fun releaseWakeLockSafely() {
        runCatching {
            if (pollWakeLock?.isHeld == true) {
                pollWakeLock?.release()
                logService("短时唤醒锁已释放")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionStore = LinuxDoPushSdk.getConfig().sessionProvider(this)
        logService("启动 action=${intent?.action ?: "常规/系统恢复"} 后台通知=${sessionStore.isBackgroundNotificationEnabled()} 会话存在=${sessionStore.hasSession()}")
        NotificationHelper.recordPushState(this, "服务启动")

        val config = LinuxDoPushSdk.getConfig()
        val openAppIntent = Intent(this, config.targetActivityClass).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_KEEPALIVE)
            .setSmallIcon(config.smallIconResId)
            .setContentTitle("LINUX DO 实时通知")
            .setContentText("亮屏实时推送 · 息屏低功耗智能守护")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("亮屏实时推送，息屏智能切换系统低功耗唤醒。\n长按本通知关闭「后台连接保持」通知类别即可隐藏此常驻条（不影响新消息推送）。"),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: RuntimeException) {
            // startForeground 在服务回调中执行，外层 startForegroundService 的捕获覆盖不到这里。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                stopForSystemLimit("前台服务启动受系统限制 ${error.message.orEmpty()}", startId)
                return START_NOT_STICKY
            }
            throw error
        }

        // 即使准备退出，也先处理本次前台启动要求，避免排队的启动请求触发未及时进入前台的崩溃。
        if (isStopping) {
            stopForSystemLimit("启动时服务已在停止", startId)
            return START_NOT_STICKY
        }
        if (!sessionStore.isBackgroundNotificationEnabled() || !sessionStore.hasSession()) {
            cancelDozeAlarm(this)
            cancelMinimizedPoll(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_DOZE_ALARM_POLL) {
            handleDozeAlarmWakeup()
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_SHALLOW_SLEEP) {
            quickPollJob?.cancel()
            quickPollJob = serviceScope.launch {
                acquireShortWakeLock(45_000L)
                try {
                    delay(20_000L)
                    val store = LinuxDoPushSdk.getConfig().sessionProvider(this@NotificationKeepAliveService)
                    store.setPendingTestReplayTag("浅睡20s实测")
                    NotificationWorker.pollOnceWithLagRetry(this@NotificationKeepAliveService, store)
                } finally {
                    releaseWakeLockSafely()
                }
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_DOZE_ALARM) {
            exitScreenOffGrace()
            releaseWakeLockSafely()
            suppressNextScreenOff = true
            sessionStore.setPendingTestReplayTag("Doze闹钟60s唤醒")
            scheduleCustomDozeAlarm(this, 60_000L)
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_SCREEN_ON_RUSH) {
            exitScreenOffGrace()
            releaseWakeLockSafely()
            suppressNextScreenOff = true
            sessionStore.setTestWaitScreenOnArmed(true)
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_BACKGROUND_MINIMIZE) {
            quickPollJob?.cancel()
            quickPollJob = serviceScope.launch {
                acquireShortWakeLock(30_000L)
                try {
                    delay(15_000L)
                    val store = LinuxDoPushSdk.getConfig().sessionProvider(this@NotificationKeepAliveService)
                    store.setPendingTestReplayTag("退回桌面15s实测")
                    NotificationWorker.pollOnceWithLagRetry(this@NotificationKeepAliveService, store)
                } finally {
                    releaseWakeLockSafely()
                }
            }
            return START_STICKY
        }

        if (isScreenInteractive(this)) {
            handleScreenOn()
        } else {
            handleScreenOff()
        }

        return START_STICKY
    }

    private fun exitScreenOffGrace() {
        logService("退出息屏宽限期，停止长轮询")
        inScreenOffGrace = false
        graceOffJob?.cancel()
        graceOffJob = null
        pollJob?.cancel()
        pollJob = null
        releaseGraceWakeLock()
    }

    private fun handleScreenOn() {
        logService("亮屏：恢复实时长轮询并立即检查通知")
        NotificationHelper.recordPushState(this, "亮屏", force = true)
        inScreenOffGrace = false
        suppressNextScreenOff = false
        graceOffJob?.cancel()
        graceOffJob = null
        releaseGraceWakeLock()
        resetAlarmStep()
        cancelDozeAlarm(this)

        if (!LinuxDoPushSdk.getConfig().sessionProvider(this).isAppInForeground()) {
            scheduleMinimizedPoll(this)
        }

        if (quickPollJob?.isActive == true) {
            logService("亮屏抢跑已在进行中，跳过重复触发")
        } else {
            quickPollJob = serviceScope.launch {
                val store = LinuxDoPushSdk.getConfig().sessionProvider(this@NotificationKeepAliveService)
                if (store.isTestWaitScreenOnArmed()) {
                    store.setTestWaitScreenOnArmed(false)
                    store.setPendingTestReplayTag("亮屏抢跑实测")
                }
                if (store.isBackgroundNotificationEnabled() && store.hasSession()) {
                    val completed = withTimeoutOrNull(QUICK_POLL_TIMEOUT_MS) {
                        NotificationWorker.pollOnceWithLagRetry(this@NotificationKeepAliveService, store)
                    }
                    if (completed == null) {
                        logService("亮屏抢跑超时 ${QUICK_POLL_TIMEOUT_MS / 1000}s，已放弃本轮")
                    }
                }
            }
        }

        startPollLoopIfNeeded()
    }

    private fun startPollLoopIfNeeded() {
        if (pollJob?.isActive == true) return
        logService("启动后台通知定期守护任务")
        pollJob = serviceScope.launch {
            delay(300L)
            while (isActive) {
                val currentStore = LinuxDoPushSdk.getConfig().sessionProvider(this@NotificationKeepAliveService)
                if (!currentStore.isBackgroundNotificationEnabled() || !currentStore.hasSession()) {
                    stopForSystemLimit("轮询检测到后台通知未启用或会话已退出")
                    break
                }

                if (!isScreenInteractive(this@NotificationKeepAliveService) && !inScreenOffGrace) {
                    break
                }

                acquireShortWakeLock(12_000L)
                try {
                    NotificationWorker.pollOnceWithLagRetry(this@NotificationKeepAliveService, currentStore)
                } finally {
                    releaseWakeLockSafely()
                }

                val intervalSec = currentStore.getCustomRealtimeSeconds().coerceIn(5, 60)
                delay(intervalSec * 1000L)
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                logService("后台守护任务结束：${cause?.javaClass?.simpleName ?: "正常结束"}")
            }
        }
    }

    private fun handleScreenOff() {
        NotificationHelper.recordPushState(this, "息屏", force = true)
        if (suppressNextScreenOff) {
            logService("息屏：保持当前实测场景，跳过常规宽限期")
            suppressNextScreenOff = false
            return
        }

        inScreenOffGrace = true
        cancelMinimizedPoll(this)
        resetAlarmStep()
        logService("息屏：进入${SCREEN_OFF_GRACE_MS / 1000}s宽限期，之后改为9/12/15分钟闹钟检查")
        scheduleCustomDozeAlarm(this, SCREEN_OFF_GRACE_MS)

        acquireGraceWakeLock(SCREEN_OFF_GRACE_MS + 30_000L)
        startPollLoopIfNeeded()

        graceOffJob?.cancel()
        graceOffJob = serviceScope.launch {
            delay(SCREEN_OFF_GRACE_MS)
            if (!isScreenInteractive(this@NotificationKeepAliveService)) {
                inScreenOffGrace = false
                pollJob?.cancel()
                pollJob = null
                releaseGraceWakeLock()
                logService("息屏宽限期结束：停止实时长轮询，等待闹钟检查")
                NotificationHelper.recordPushState(this@NotificationKeepAliveService, "宽限期结束", force = true)
            }
        }
    }

    private fun handleDozeAlarmWakeup() {
        logService("收到服务闹钟唤醒请求")
        NotificationHelper.recordPushState(this, "服务闹钟唤醒", force = true)
        quickPollJob?.cancel()
        quickPollJob = serviceScope.launch {
            acquireShortWakeLock(15_000L)
            try {
                val store = LinuxDoPushSdk.getConfig().sessionProvider(this@NotificationKeepAliveService)
                if (store.isBackgroundNotificationEnabled() && store.hasSession()) {
                    NotificationWorker.pollOnceWithLagRetry(this@NotificationKeepAliveService, store)
                }
            } finally {
                if (!isScreenInteractive(this@NotificationKeepAliveService)) {
                    scheduleNextDozeAlarm(this@NotificationKeepAliveService)
                }
                releaseWakeLockSafely()
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForSystemLimit("前台服务超时 startId=$startId type=$fgsType")
    }

    private fun stopForSystemLimit(reason: String, startId: Int? = null) {
        isStopping = true
        if (startId == null) {
            // 超时回调必须立即停止整个服务，不能使用可能过期的 startId。
            stopSelf()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            // 启动回调只结束已处理的 startId，让后续排队请求有机会处理各自的前台启动要求。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        serviceScope.cancel()
        releaseGraceWakeLock()
        releaseWakeLockSafely()
        logService("$reason：已停止服务，后续由闹钟检查通知")
    }

    override fun onDestroy() {
        isStopping = true
        logService("服务销毁")
        unregisterScreenReceiver()
        scheduleFallbackPolls(this)

        graceOffJob?.cancel()
        pollJob?.cancel()
        quickPollJob?.cancel()
        serviceScope.cancel()
        releaseGraceWakeLock()
        releaseWakeLockSafely()
        graceWakeLock = null
        pollWakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 2001
        private const val ALARM_REQUEST_CODE = 2002
        const val ACTION_DOZE_ALARM_POLL = "org.linuxdo.android.ACTION_DOZE_ALARM_POLL"
        const val EXTRA_ALARM_TRIGGER_AT = "extra_alarm_trigger_at"

        const val ACTION_TEST_SHALLOW_SLEEP = "org.linuxdo.android.ACTION_TEST_SHALLOW_SLEEP"
        const val ACTION_TEST_DOZE_ALARM = "org.linuxdo.android.ACTION_TEST_DOZE_ALARM"
        const val ACTION_TEST_SCREEN_ON_RUSH = "org.linuxdo.android.ACTION_TEST_SCREEN_ON_RUSH"
        const val ACTION_TEST_BACKGROUND_MINIMIZE = "org.linuxdo.android.ACTION_TEST_BACKGROUND_MINIMIZE"

        private const val SCREEN_OFF_GRACE_MS = 5 * 60 * 1000L
        private const val QUICK_POLL_TIMEOUT_MS = 45_000L

        val ACTION_MINIMIZED_POLL: String get() = ObfHelper.ACT_MINIMIZED
        private const val MINIMIZED_ALARM_REQUEST_CODE = 2003
        private const val MINIMIZED_POLL_INTERVAL_MS = 60_000L

        private val DOZE_ALARM_INTERVALS_MS = longArrayOf(
            9 * 60 * 1000L,
            12 * 60 * 1000L,
            15 * 60 * 1000L,
        )
        @Volatile
        private var alarmStepIndex = 0

        /** 已在册的最小化闹钟触发时刻（ELAPSED_REALTIME），0 表示无闹钟在册。 */
        @Volatile
        private var minimizedAlarmDueAt = 0L

        fun isScreenInteractive(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isInteractive ?: true
        }

        internal fun resetAlarmStep() {
            alarmStepIndex = 0
        }

        internal fun scheduleNextDozeAlarm(context: Context) {
            val idx = alarmStepIndex.coerceIn(0, DOZE_ALARM_INTERVALS_MS.size - 1)
            val delayMs = DOZE_ALARM_INTERVALS_MS[idx]
            if (alarmStepIndex < DOZE_ALARM_INTERVALS_MS.size - 1) {
                alarmStepIndex++
            }
            scheduleCustomDozeAlarm(context, delayMs)
        }

        private fun buildAlarmPendingIntent(context: Context, triggerAt: Long = 0L): PendingIntent {
            val intent = Intent(context, DozeAlarmReceiver::class.java).apply {
                action = ACTION_DOZE_ALARM_POLL
                putExtra(EXTRA_ALARM_TRIGGER_AT, triggerAt)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
        }

        internal fun scheduleCustomDozeAlarm(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val pendingIntent = buildAlarmPendingIntent(context, triggerAt)
            runCatching {
                val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
                if (exactAllowed) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
                exactAllowed
            }.onSuccess { exactAllowed ->
                NotificationHelper.logDiagnostic(context, "alarm.schedule", "[闹钟] 已预约 ${delayMs / 1000}s后触发 精确=$exactAllowed", true)
            }.onFailure {
                NotificationHelper.logDiagnostic(context, "alarm.schedule.error", "[闹钟] 预约失败 ${it.javaClass.simpleName}", true)
            }
        }

        internal fun cancelDozeAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching {
                alarmManager.cancel(buildAlarmPendingIntent(context))
            }.onFailure {
                NotificationHelper.logDiagnostic(context, "alarm.cancel.error", "[闹钟] 取消失败 ${it.javaClass.simpleName}", true)
            }
        }

        private fun buildMinimizedAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DozeAlarmReceiver::class.java).apply {
                action = ACTION_MINIMIZED_POLL
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, MINIMIZED_ALARM_REQUEST_CODE, intent, flags)
        }

        fun scheduleMinimizedPoll(
            context: Context,
            delayMs: Long = MINIMIZED_POLL_INTERVAL_MS,
            renew: Boolean = false,
        ) {
            val sessionStore = LinuxDoPushSdk.getConfig().sessionProvider(context)
            if (!sessionStore.isBackgroundNotificationEnabled() || !sessionStore.hasSession()) return
            val now = SystemClock.elapsedRealtime()
            // 幂等：已有未到期的闹钟在册时直接返回。亮屏在真机上会被连打数次
            // （闹钟 start + onResume().start + enqueue().start 叠加），每次都重新 set
            // 会把触发时刻一路向后推，闹钟永远等不到执行 —— 兜底通道等于不存在。
            // 闹钟自己续约时传 renew=true 跳过该判断。
            if (!renew && minimizedAlarmDueAt > now) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = now + delayMs
            val pendingIntent = buildMinimizedAlarmPendingIntent(context)
            runCatching {
                val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
                if (exactAllowed) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
                exactAllowed
            }.onSuccess { exactAllowed ->
                minimizedAlarmDueAt = triggerAt
                NotificationHelper.logDiagnostic(
                    context, "alarm.minimized.schedule",
                    "[最小化闹钟] 已预约 ${delayMs / 1000}s后触发 精确=$exactAllowed 续约=$renew", true,
                )
            }.onFailure {
                NotificationHelper.logDiagnostic(
                    context, "alarm.minimized.error",
                    "[最小化闹钟] 预约失败 ${it.javaClass.simpleName}", true,
                )
            }
        }

        fun cancelMinimizedPoll(context: Context) {
            minimizedAlarmDueAt = 0L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching {
                alarmManager.cancel(buildMinimizedAlarmPendingIntent(context))
            }.onFailure {
                NotificationHelper.logDiagnostic(
                    context, "alarm.minimized.cancel.error",
                    "[最小化闹钟] 取消失败 ${it.javaClass.simpleName}", true,
                )
            }
        }

        private fun scheduleFallbackPolls(context: Context) {
            val store = LinuxDoPushSdk.getConfig().sessionProvider(context)
            if (store.isBackgroundNotificationEnabled() && store.hasSession()) {
                scheduleNextDozeAlarm(context)
                if (isScreenInteractive(context) && !store.isAppInForeground()) {
                    scheduleMinimizedPoll(context)
                }
            } else {
                cancelDozeAlarm(context)
                cancelMinimizedPoll(context)
            }
        }

        fun start(context: Context) {
            val sessionStore = LinuxDoPushSdk.getConfig().sessionProvider(context)
            if (!sessionStore.isBackgroundNotificationEnabled() || !sessionStore.hasSession()) {
                stop(context)
                return
            }
            val intent = Intent(context, NotificationKeepAliveService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                scheduleFallbackPolls(context)
                NotificationHelper.logDiagnostic(context, "service.start.error", "[保活] 启动服务失败 ${it.javaClass.simpleName}", true)
            }
        }

        fun startTestScenario(context: Context, testAction: String) {
            val intent = Intent(context, NotificationKeepAliveService::class.java).apply {
                action = testAction
            }
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                NotificationHelper.logDiagnostic(context, "service.test.error", "[保活] 启动实测失败 action=$testAction ${it.javaClass.simpleName}", true)
            }
        }

        fun startShallowSleepTest(context: Context) = startTestScenario(context, ACTION_TEST_SHALLOW_SLEEP)

        fun startDozeAlarmTest(context: Context) = startTestScenario(context, ACTION_TEST_DOZE_ALARM)

        fun startScreenOnRushTest(context: Context) = startTestScenario(context, ACTION_TEST_SCREEN_ON_RUSH)

        fun startBackgroundMinimizeTest(context: Context) = startTestScenario(context, ACTION_TEST_BACKGROUND_MINIMIZE)

        fun stop(context: Context) {
            cancelDozeAlarm(context)
            cancelMinimizedPoll(context)
            val intent = Intent(context, NotificationKeepAliveService::class.java)
            context.stopService(intent)
        }
    }
}

class DozeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action == NotificationKeepAliveService.ACTION_MINIMIZED_POLL) {
            handleMinimizedPoll(appContext)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val expectedAt = intent?.getLongExtra(NotificationKeepAliveService.EXTRA_ALARM_TRIGGER_AT, 0L) ?: 0L
        val lateness = if (expectedAt > 0L) "${(startedAt - expectedAt) / 1000}s" else "未知"
        NotificationHelper.logDiagnostic(appContext, "alarm.fired", "[闹钟] 已触发，较预约时间偏移=$lateness", true)
        NotificationHelper.recordPushState(appContext, "闹钟触发", force = true)
        val sessionStore = LinuxDoPushSdk.getConfig().sessionProvider(appContext)
        if (!sessionStore.isBackgroundNotificationEnabled() || !sessionStore.hasSession()) {
            NotificationHelper.logDiagnostic(appContext, "alarm.skip", "[闹钟] 后台通知关闭或无会话，停止检查", true)
            NotificationKeepAliveService.cancelDozeAlarm(appContext)
            return
        }

        NotificationKeepAliveService.scheduleNextDozeAlarm(appContext)
        if (NotificationKeepAliveService.isScreenInteractive(appContext)) {
            NotificationKeepAliveService.start(appContext)
        }

        val pendingResult = goAsync()
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LinuxDo:DozeReceiverWakeLock",
        )?.apply {
            setReferenceCounted(false)
            runCatching { acquire(15_000L) }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NotificationWorker.pollOnceWithLagRetry(appContext, sessionStore)
            } finally {
                NotificationHelper.logDiagnostic(appContext, "alarm.finished", "[闹钟] 本次检查结束 耗时=${(SystemClock.elapsedRealtime() - startedAt) / 1000}s", true)
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
                runCatching { pendingResult.finish() }
            }
        }
    }

    private fun handleMinimizedPoll(appContext: Context) {
        val sessionStore = LinuxDoPushSdk.getConfig().sessionProvider(appContext)
        val screenOn = NotificationKeepAliveService.isScreenInteractive(appContext)
        val inForeground = sessionStore.isAppInForeground()
        val enabled = sessionStore.isBackgroundNotificationEnabled() && sessionStore.hasSession()
        NotificationHelper.logDiagnostic(
            appContext, "alarm.minimized.fired",
            "[最小化闹钟] 已触发 亮屏=$screenOn 前台=$inForeground 后台通知=$enabled", true,
        )
        if (!enabled || !screenOn || inForeground) {
            NotificationKeepAliveService.cancelMinimizedPoll(appContext)
            return
        }

        NotificationKeepAliveService.scheduleMinimizedPoll(appContext, renew = true)

        val pendingResult = goAsync()
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LinuxDo:MinimizedPollWakeLock",
        )?.apply {
            setReferenceCounted(false)
            runCatching { acquire(20_000L) }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NotificationWorker.pollOnceWithLagRetry(appContext, sessionStore)
            } finally {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
                runCatching { pendingResult.finish() }
            }
        }
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context?.applicationContext ?: return
        val sessionStore = LinuxDoPushSdk.getConfig().sessionProvider(appContext)
        if (!sessionStore.isBackgroundNotificationEnabled() || !sessionStore.hasSession()) return

        NotificationKeepAliveService.resetAlarmStep()
        NotificationKeepAliveService.scheduleNextDozeAlarm(appContext)
        NotificationKeepAliveService.start(appContext)
    }
}

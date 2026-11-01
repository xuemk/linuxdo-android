package org.linuxdo.android.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.linuxdo.android.push.LinuxDoPushSdk
import org.linuxdo.android.push.ObfHelper

object NotificationHelper {

    val CHANNEL_ID_NOTIFICATIONS: String get() = ObfHelper.CH_NOTIFICATIONS
    val CHANNEL_ID_KEEPALIVE: String get() = ObfHelper.CH_KEEPALIVE

    internal fun logDiagnostic(context: Context, key: String, message: String, force: Boolean = false) {
        LinuxDoPushSdk.log(context, key, message, force)
    }

    private fun failureDescription(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(200)}"

    fun recordDiagnosticSnapshot(context: Context) {
        val session = LinuxDoPushSdk.getConfig().sessionProvider(context)
        logDiagnostic(
            context, "notification.device",
            "[推送诊断] 设备=${Build.MANUFACTURER}/${Build.MODEL} Android=${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} " +
                "系统版本=${Build.DISPLAY}", true,
        )
        val count = session.getLastBadgeCount()
        recordBadgeState(context, count, "手动检查", force = true)
        recordPushState(context, "手动检查", force = true)
    }

    private fun recordBadgeState(context: Context, count: Int, source: String, force: Boolean = false) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager?.getNotificationChannel(CHANNEL_ID_NOTIFICATIONS)
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToShortString() } ?: "未解析"
            val active = manager?.activeNotifications?.filter { it.notification.channelId == CHANNEL_ID_NOTIFICATIONS }
            val saved = LinuxDoPushSdk.getConfig().sessionProvider(context).getLastBadgeCount()
            logDiagnostic(
                context, "badge.state.$source",
                "[角标] 来源=$source 目标=$count 缓存=$saved 桌面=$home 入口=${resolveLauncherClassName(context)} " +
                    "通知允许=${manager?.areNotificationsEnabled()} 渠道=$CHANNEL_ID_NOTIFICATIONS " +
                    "渠道存在=${channel != null} 重要性=${channel?.importance} 允许角标=${channel?.canShowBadge()} " +
                    "活动通知=${active?.size} 通知number=${active?.take(4)?.map { it.notification.number }}", force,
            )
        }.onFailure {
            logDiagnostic(context, "badge.state.error", "[角标] 读取系统状态失败 ${failureDescription(it)}", force)
        }
    }

    internal fun recordPushState(context: Context, source: String, force: Boolean = false) {
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.activeNetwork
            val capabilities = network?.let { connectivity?.getNetworkCapabilities(it) }
            val transports = buildList {
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("移动数据")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("以太网")
            }.joinToString("+").ifEmpty { if (network == null) "无活动网络" else "其他" }
            val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
            } else null
            val exactAlarmAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms()
            } else true
            logDiagnostic(
                context, "push.state.$source",
                "[推送状态] 来源=$source 亮屏=${power?.isInteractive} Doze=${power?.isDeviceIdleMode} " +
                    "省电=${power?.isPowerSaveMode} 电池优化豁免=${power?.isIgnoringBatteryOptimizations(context.packageName)} " +
                    "后台受限=$backgroundRestricted 精确闹钟=$exactAlarmAllowed 网络=$transports " +
                    "联网能力=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                    "网络已验证=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "省流量状态=${connectivity?.restrictBackgroundStatus}", force,
            )
        }.onFailure {
            logDiagnostic(context, "push.state.error", "[推送状态] 读取失败 ${failureDescription(it)}", force)
        }
    }

    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            for (legacyId in listOf("linuxdo_unread_notifications", "linuxdo_unread_notifications_v2")) {
                try {
                    manager.deleteNotificationChannel(legacyId)
                } catch (_: Exception) {}
            }

            val unreadChannel = NotificationChannel(
                CHANNEL_ID_NOTIFICATIONS,
                "新消息与回复通知",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "接收 LINUX DO 社区未读回复、私信与系统通知（支持横幅浮窗提醒）"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(unreadChannel)

            val keepAliveChannel = NotificationChannel(
                CHANNEL_ID_KEEPALIVE,
                "后台连接保持",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "维持后台连接，按设定频率检测新消息"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(keepAliveChannel)
        }
    }

    fun formatNotification(
        notificationType: Int,
        senderName: String,
        topicTitle: String?,
        dataExcerpt: String?,
    ): Pair<String, String> {
        val title = topicTitle?.takeIf { it.isNotBlank() } ?: when (notificationType) {
            1, 15 -> "$senderName 提及了您"
            2 -> "$senderName 回复了您"
            3 -> "$senderName 引用了您的发言"
            4 -> "$senderName 编辑了内容"
            5, 19 -> "$senderName 赞了您的帖子"
            25 -> "$senderName 回应了您的帖子"
            6, 7, 16 -> "$senderName 发送了私信"
            9 -> "$senderName 发布了新回复"
            14 -> "$senderName 采纳了解决方案"
            35 -> "$senderName 发表了评论"
            43 -> "$senderName 发送了简评"
            800 -> "关注动态"
            801, 17 -> "$senderName 关注发帖"
            802 -> "$senderName 关注回复"
            12 -> "获得新徽章"
            else -> "$senderName 社区动态"
        }

        val content = if (!dataExcerpt.isNullOrBlank()) {
            if (senderName.isNotBlank() && senderName != "社区用户") {
                "$senderName: $dataExcerpt"
            } else {
                dataExcerpt
            }
        } else {
            when (notificationType) {
                1, 15 -> "$senderName 在话题中提及了您"
                2 -> "$senderName 回复了您的发言"
                3 -> "$senderName 引用了您的发言"
                4 -> "$senderName 编辑了帖子内容"
                5, 19 -> "$senderName 赞了您的帖子"
                25 -> "$senderName 回应了您的帖子"
                6, 7, 16 -> "$senderName 给您发送了私信"
                9 -> "$senderName 在关注的话题中发布了新内容"
                14 -> "$senderName 采纳了回答作为解决方案"
                35 -> "$senderName 在问答中发表了评论"
                43 -> "$senderName 留下了一条简评"
                800 -> "$senderName 已开始关注您"
                801, 17 -> "$senderName 发布了新话题"
                802 -> "$senderName 回复了关注的话题"
                12 -> "恭喜您获得了一枚新社区徽章"
                else -> "收到一条新动态"
            }
        }

        return Pair(title, content)
    }

    private val recentlyShownKeys = LinkedHashMap<String, Long>(64, 0.75f, false)
    private const val DEDUP_EXPIRE_MS = 30 * 60 * 1000L

    fun isRecentlyShown(key: String): Boolean = synchronized(recentlyShownKeys) {
        cleanExpiredDedupLocked()
        recentlyShownKeys.containsKey(key)
    }

    fun markShown(key: String) = synchronized(recentlyShownKeys) {
        cleanExpiredDedupLocked()
        recentlyShownKeys[key] = System.currentTimeMillis()
    }

    private fun cleanExpiredDedupLocked() {
        val now = System.currentTimeMillis()
        val iterator = recentlyShownKeys.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > DEDUP_EXPIRE_MS) {
                iterator.remove()
            }
        }
    }

    fun showNotification(
        context: Context,
        notificationId: Long,
        title: String,
        content: String,
        topicId: Long? = null,
        postNumber: Int? = null,
        notificationType: Int? = null,
        badgeCount: Int? = null,
        dedupKey: String? = null,
    ): Boolean {
        ensureNotificationChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val config = LinuxDoPushSdk.getConfig()

        val notifIntId = if (topicId != null && postNumber != null && topicId > 0L) {
            ("t_${topicId}_p_${postNumber}").hashCode()
        } else {
            (notificationId % Int.MAX_VALUE).toInt()
        }
        val intent = Intent(context, config.targetActivityClass).apply {
            action = "org.linuxdo.android.NOTIFICATION_CLICK_${notificationId}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notification_id", notificationId)
            if (notificationType != null) {
                putExtra("notification_type", notificationType)
            }
            if (topicId != null) {
                putExtra("topic_id", topicId)
            }
            if (postNumber != null) {
                putExtra("post_number", postNumber)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifIntId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_NOTIFICATIONS)
            .setSmallIcon(config.smallIconResId)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val effectiveBadge = badgeCount?.coerceAtLeast(1)
        if (effectiveBadge != null) {
            builder.setNumber(effectiveBadge)
        }

        val notification = builder.build()
        if (effectiveBadge != null) {
            try {
                val field = notification.javaClass.getDeclaredField("extraNotification")
                val extraNotification = field.get(notification)
                val method = extraNotification.javaClass.getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType)
                method.invoke(extraNotification, effectiveBadge)
            } catch (_: Exception) {}
        }

        try {
            manager.notify(notifIntId, notification)
            if (!dedupKey.isNullOrBlank()) {
                markShown(dedupKey)
            }
            val channel = manager.getNotificationChannel(CHANNEL_ID_NOTIFICATIONS)
            val active = runCatching {
                manager.activeNotifications.any { it.id == notifIntId }
            }.getOrNull()
            logDiagnostic(
                context,
                "notification.post",
                "[通知] 已向系统提交 id=$notifIntId number=${notification.number} " +
                    "通知允许=${manager.areNotificationsEnabled()} 渠道重要性=${channel?.importance} " +
                    "活动通知=$active",
                true,
            )
        } catch (error: Exception) {
            logDiagnostic(context, "notification.post.error", "[通知] 提交失败 ${failureDescription(error)}", true)
            throw error
        }

        if (effectiveBadge != null) {
            val appCtx = context.applicationContext
            updateBadgeCount(appCtx, effectiveBadge, source = "通知提交")
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.postDelayed({
                val latest = config.sessionProvider(appCtx).getLastBadgeCount()
                updateBadgeCount(appCtx, latest, source = "通知后350ms")
            }, 350L)
            mainHandler.postDelayed({
                val latest = config.sessionProvider(appCtx).getLastBadgeCount()
                updateBadgeCount(appCtx, latest, source = "通知后900ms")
            }, 900L)
        }
        return true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        return true
    }

    fun promptIgnoreBatteryOptimizationsIfNeeded(context: Context) {
        if (!isIgnoringBatteryOptimizations(context)) {
            requestIgnoreBatteryOptimizations(context)
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }.onSuccess { return }
            }
        }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }.onFailure {
            openAppDetailsSettings(context)
        }
    }

    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            )
            else -> emptyList()
        }
        for (intent in intents) {
            if (runCatching {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }.isSuccess
            ) return
        }
        openAppDetailsSettings(context)
    }

    fun openPowerSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()
        if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            intents += Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"))
            intents += Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"))
        }
        intents += Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        intents += Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intents += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        for (intent in intents) {
            if (runCatching {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }.isSuccess
            ) return
        }
        openAppDetailsSettings(context)
    }

    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }.onFailure {
            openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    fun updateBadgeCount(
        context: Context,
        badgeCount: Int,
        source: String = "未指定",
        force: Boolean = false,
    ) {
        val safeCount = badgeCount.coerceAtLeast(0)
        val session = LinuxDoPushSdk.getConfig().sessionProvider(context.applicationContext)
        if (!force && session.isBadgeTestHolding()) {
            logDiagnostic(context, "badge.hold.$source", "[角标] 来源=$source 目标=$safeCount 已跳过：角标实测锁定中，桌面保持实测数字")
            return
        }
        recordBadgeState(context, safeCount, source, force)
        applyHuaweiHonorBadge(context, safeCount, source)
        applyXiaomiBadge(context, safeCount, source)
        applyVivoBadge(context, safeCount, source)
        applyOppoBadge(context, safeCount, source)
        applySamsungBadge(context, safeCount, source)
    }

    private fun resolveLauncherClassName(context: Context): String {
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.component?.className ?: LinuxDoPushSdk.getConfig().targetActivityClass.name
        }.getOrDefault(LinuxDoPushSdk.getConfig().targetActivityClass.name)
    }

    private fun applyHuaweiHonorBadge(context: Context, count: Int, source: String) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isHuaweiHonor = manufacturer.contains("huawei") || manufacturer.contains("honor")
        val authorities = listOf(
            "com.hihonor.android.launcher.settings",
            "com.huawei.android.launcher.settings",
        )
        val launcherActivity = resolveLauncherClassName(context)

        for (authority in authorities) {
            val uri = Uri.parse("content://$authority/badge/")
            val providerClient = runCatching {
                context.contentResolver.acquireUnstableContentProviderClient(uri)
            }.getOrNull()

            if (providerClient == null) {
                if (isHuaweiHonor) {
                    logDiagnostic(
                        context, "badge.call.$source.$authority",
                        "[角标接口] 来源=$source 接口=$authority 目标=$count 跳过：Provider 未解析",
                    )
                }
                continue
            }

            val outcomes = mutableListOf<String>()
            try {
                val methods = listOf("change_badge", "changeBadge", "setBadge")
                for (method in methods) {
                    val bundle = Bundle().apply {
                        putString("package", context.packageName)
                        putString("class", launcherActivity)
                        putInt("badgenumber", count)
                    }
                    val outcome = runCatching { providerClient.call(method, null, bundle) }
                    outcomes += "$method→" + outcome.fold(
                        onSuccess = { badgeResultDescription(it) },
                        onFailure = { "异常 ${failureDescription(it)}" },
                    )
                    if (outcome.getOrNull() != null) break
                }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    providerClient.close()
                } else {
                    @Suppress("DEPRECATION")
                    providerClient.release()
                }
            }

            // 返回值是桌面没画角标时唯一的判据：null 指向权限被拒或方法名不被接受，
            // 带 result/error 字段的 Bundle 则指向参数错误，两者的排查方向完全不同。
            logDiagnostic(
                context, "badge.call.$source.$authority",
                "[角标接口] 来源=$source 接口=$authority 目标=$count 包=${context.packageName} " +
                    "入口=$launcherActivity 本应用已获权限[${badgePermissionState(context, authority)}] " +
                    "调用结果=${outcomes.joinToString("; ")}；桌面显示仍需实测",
            )
        }
    }

    private fun badgePermissionState(context: Context, authority: String): String {
        val prefix = authority.removeSuffix(".settings")
        return listOf("CHANGE_BADGE", "READ_SETTINGS", "WRITE_SETTINGS").joinToString(" ") { name ->
            val granted = runCatching {
                context.checkSelfPermission("$prefix.permission.$name") == PackageManager.PERMISSION_GRANTED
            }.getOrNull()
            "$name=$granted"
        }
    }

    private fun badgeResultDescription(result: Bundle?): String {
        if (result == null) return "null（接口无返回值）"
        return runCatching {
            result.keySet().sorted().take(8).joinToString(prefix = "{", postfix = "}") { key ->
                @Suppress("DEPRECATION")
                val value = result.get(key)
                val description = when (value) {
                    null, is Number, is Boolean -> value.toString()
                    is String -> if (key in setOf("result", "success", "code", "error", "message")) {
                        value.replace('\n', ' ').replace('\r', ' ').take(100)
                    } else "String"
                    else -> value.javaClass.simpleName
                }
                "$key=$description"
            }
        }.getOrElse { "返回值无法读取：${it.javaClass.simpleName}" }
    }

    private fun applyXiaomiBadge(context: Context, count: Int, source: String) {
        runCatching {
            val intent = Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE").apply {
                putExtra(
                    "android.intent.extra.update_application_component_name",
                    "${context.packageName}/${resolveLauncherClassName(context)}",
                )
                putExtra(
                    "android.intent.extra.update_application_message_comments",
                    if (count > 0) count.toString() else "",
                )
            }
            context.sendBroadcast(intent)
        }
    }

    private fun applyVivoBadge(context: Context, count: Int, source: String) {
        runCatching {
            val intent = Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM").apply {
                putExtra("packageName", context.packageName)
                putExtra("className", resolveLauncherClassName(context))
                putExtra("notificationNum", count)
                addFlags(0x01000000)
            }
            context.sendBroadcast(intent)
        }
    }

    private fun applyOppoBadge(context: Context, count: Int, source: String) {
        runCatching {
            val bundle = Bundle().apply {
                putInt("app_badge_count", count)
            }
            context.contentResolver.call(
                Uri.parse("content://com.oppo.launcher.settings/badge"),
                "setAppBadgeCount",
                null,
                bundle,
            )
        }
    }

    private fun applySamsungBadge(context: Context, count: Int, source: String) {
        runCatching {
            val uri = Uri.parse("content://com.sec.badge/apps")
            val contentValues = ContentValues().apply {
                put("package", context.packageName)
                put("class", resolveLauncherClassName(context))
                put("badgecount", count)
            }
            val updated = context.contentResolver.update(uri, contentValues, "package=?", arrayOf(context.packageName))
            if (updated == 0) {
                context.contentResolver.insert(uri, contentValues)
            }
        }
    }
}

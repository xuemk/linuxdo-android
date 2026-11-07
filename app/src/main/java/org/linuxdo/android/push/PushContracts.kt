package org.linuxdo.android.push

import android.app.Activity
import android.content.Context
import okhttp3.OkHttpClient

/**
 * 通知作用域契约（与服务端 notification_type 对齐）。
 */
enum class PushNotificationScope(val rawValue: Int, val label: String) {
    INTERACTION_ONLY(0, "仅回复与互动"),
    ALL(1, "全部通知");

    companion object {
        fun fromRaw(raw: Int): PushNotificationScope =
            values().firstOrNull { it.rawValue == raw } ?: INTERACTION_ONLY
    }
}

/**
 * 宿主会话状态与持久化桥接。
 */
interface PushSessionProvider {
    fun hasSession(): Boolean
    fun isBackgroundNotificationEnabled(): Boolean
    /** 临时验证或限流只推迟网络请求，不改变登录或后台服务开关。 */
    fun backgroundRequestDelayMs(): Long = 0L
    fun getCookie(): String
    fun getUserAgent(): String
    fun getUserId(): Long
    fun getSessionGeneration(): Long = -1L
    fun saveUserId(id: Long)
    fun getLastSeenNotificationId(): Long
    fun setLastSeenNotificationId(id: Long)
    fun getLastBadgeCount(): Int
    fun setLastBadgeCount(count: Int, force: Boolean = false)
    fun incrementLastBadgeCount(): Int
    fun isBadgeTestHolding(): Boolean
    fun setBadgeTestHoldUntil(timestamp: Long)
    fun getPendingTestReplayTag(): String?
    fun setPendingTestReplayTag(tag: String?)
    fun isTestWaitScreenOnArmed(): Boolean
    fun setTestWaitScreenOnArmed(armed: Boolean)
    fun isAppInForeground(): Boolean
    fun setAppInForeground(inForeground: Boolean)
    fun getNotificationScope(): PushNotificationScope
    fun getCustomRealtimeSeconds(): Int
    fun setNotificationTransportHealthyAtMillis(timestamp: Long) {}
}

/**
 * 宿主诊断日志桥接。
 */
fun interface PushLogger {
    fun log(key: String, message: String, force: Boolean)
}

/**
 * 推送核心配置。
 */
data class PushConfig(
    val targetActivityClass: Class<out Activity>,
    val smallIconResId: Int,
    val baseUrl: String = "https://linux.do",
    val httpClientProvider: () -> OkHttpClient,
    val sessionProvider: (Context) -> PushSessionProvider,
    val logger: PushLogger? = null,
)

/**
 * 推送全局入口门面。
 */
object LinuxDoPushSdk {
    @Volatile
    private var config: PushConfig? = null

    fun init(pushConfig: PushConfig) {
        config = pushConfig
    }

    fun getConfig(): PushConfig =
        config ?: throw IllegalStateException("LinuxDoPushSdk 未初始化，请先在 Application.onCreate 中调用 init()")

    fun log(context: Context, key: String, message: String, force: Boolean = false) {
        config?.logger?.log(key, message, force)
    }
}

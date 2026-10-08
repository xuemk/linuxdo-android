package org.linuxdo.android.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import org.json.JSONObject
import org.linuxdo.android.Config

/**
 * 会话 Cookie 的加密持久化,同时负责与 WebView CookieManager 双向同步。
 *
 * 约定:WebView 的 CookieManager 是"现场真相";只要它非空就以它为准并回写加密存储,
 * 只有它为空(例如 WebView 数据被清)时才用加密存储恢复。
 * 涉及 CookieManager 的方法请在主线程调用。
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context)

    fun storedCookies(): Map<String, String> {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun savedAtMillis(): Long = prefs.getLong(KEY_SAVED_AT, 0L)

    fun userAgentOrNull(): String? = prefs.getString(KEY_USER_AGENT, null)

    fun saveUserAgent(userAgent: String) {
        prefs.edit().putString(KEY_USER_AGENT, userAgent).apply()
    }

    /** 以 WebView 现场 Cookie 为准同步到加密存储,返回当前有效的 Cookie。 */
    fun syncFromWebView(): Map<String, String> {
        val web = parseCookieHeader(CookieManager.getInstance().getCookie(Config.BASE_URL))
        if (web.isEmpty()) return storedCookies()
        if (web != storedCookies()) save(web)
        return web
    }

    /** WebView Cookie 为空而加密存储有值时,把存储的 Cookie 写回 WebView。 */
    fun restoreToWebViewIfEmpty() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val web = parseCookieHeader(manager.getCookie(Config.BASE_URL))
        if (web.isNotEmpty()) return
        val stored = storedCookies()
        if (stored.isEmpty()) return
        stored.forEach { (name, value) ->
            manager.setCookie(Config.BASE_URL, "$name=$value; Domain=${Config.HOST}; Path=/; Secure")
        }
        manager.flush()
    }

    /** 应用 OkHttp 响应里的 Set-Cookie,同时写入加密存储与 WebView,保证两边一致。 */
    fun applyResponseCookies(cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val manager = CookieManager.getInstance()
        val merged = syncFromWebView().toMutableMap()
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt < now) {
                merged.remove(cookie.name)
                manager.setCookie(
                    Config.BASE_URL,
                    "${cookie.name}=; Max-Age=0; Domain=${Config.HOST}; Path=/",
                )
            } else {
                merged[cookie.name] = cookie.value
                manager.setCookie(Config.BASE_URL, cookie.toString())
            }
        }
        manager.flush()
        save(merged)
    }

    fun hasSessionCookie(): Boolean = syncFromWebView().containsKey(Config.SESSION_COOKIE)

    fun clear() {
        prefs.edit().remove(KEY_COOKIES).remove(KEY_SAVED_AT).apply()
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    private fun save(cookies: Map<String, String>) {
        prefs.edit()
            .putString(KEY_COOKIES, JSONObject(cookies as Map<*, *>).toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "session_secure"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_USER_AGENT = "user_agent"

        fun parseCookieHeader(raw: String?): Map<String, String> =
            raw.orEmpty().split(";").mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()

        fun toCookieHeader(cookies: Map<String, String>): String =
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        private fun createPrefs(context: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                build()
            } catch (error: Exception) {
                // Keystore 密钥失效(如恢复备份/重装)会导致无法解密,清掉旧文件重新开始
                context.deleteSharedPreferences(PREFS_NAME)
                build()
            }
        }
    }
}

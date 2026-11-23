package org.linuxdo.android.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.linuxdo.android.Config

private val profileUsernamePattern = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.-]*")

/** 只接管论坛个人主页；设置、登录及子站链接仍由原来的网页入口处理。 */
internal fun parseInternalUserProfileUrl(url: String): String? {
    val parsed = Config.BASE_URL.toHttpUrl().resolve(url.trim()) ?: return null
    val host = parsed.host.trimEnd('.')
    if (host != Config.HOST && host != "www.${Config.HOST}") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.port != if (parsed.isHttps) 443 else 80) return null

    val segments = parsed.pathSegments.dropLastWhile { it.isEmpty() }
    if (segments.size != 2 && !(segments.size == 3 && segments[2] == "summary")) return null
    if (segments[0] != "u" && segments[0] != "users") return null
    val username = segments[1]
    if (!profileUsernamePattern.matches(username) || username.endsWith(".json", ignoreCase = true)) return null
    if (username in setOf("search", "email-login", "account-created", "activate-account", "password-reset")) return null
    return username
}

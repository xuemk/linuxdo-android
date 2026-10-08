package org.linuxdo.android.ui.format

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 把 Discourse 的 ISO 时间戳格式化成 iOS 论坛客户端惯用的相对时间。 */
object RelativeTime {

    fun format(iso: String?, now: Instant = Instant.now()): String {
        val instant = parse(iso) ?: return "-"
        val seconds = ChronoUnit.SECONDS.between(instant, now)
        if (seconds < 60) return "刚刚"
        if (seconds < 3_600) return "${seconds / 60} 分钟前"
        if (seconds < 86_400) return "${seconds / 3_600} 小时前"

        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (val days = ChronoUnit.DAYS.between(date, today)) {
            1L -> "昨天"
            in 2L..6L -> "$days 天前"
            else -> if (date.year == today.year) {
                "${date.monthValue} 月 ${date.dayOfMonth} 日"
            } else {
                "${date.year} 年 ${date.monthValue} 月"
            }
        }
    }

    /** Discourse 多数端点返回 Z 结尾,少数带 +08:00 偏移,两种都要能解析。 */
    private fun parse(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant() }
            .recoverCatching { Instant.parse(iso) }
            .getOrNull()
    }
}

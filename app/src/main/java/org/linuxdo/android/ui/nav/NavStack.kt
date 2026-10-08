package org.linuxdo.android.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import java.io.Serializable

/** 第一期的全部路由。密封类便于后续从 URL 解析(通知跳转、深链)时集中扩展。 */
sealed interface Route : Serializable {
    data object TopicList : Route
    data class TopicDetail(val topicId: Long, val title: String, val postNumber: Int? = null) : Route
    data object CategoryList : Route
    data class CategoryTopics(val categoryId: Int, val slug: String, val name: String) : Route
    data object Search : Route
    data object Profile : Route
    data object Diagnostics : Route
    data object Settings : Route
    data object Notifications : Route
}

/**
 * 栈内条目带稳定 id,而不是用下标做 key —— 同一个路由可能重复入栈(详情跳详情),
 * 且下标会随出栈变化,拿它当 key 会让页面状态(滚动位置)错乱。
 */
data class NavEntry(val id: Long, val route: Route) : Serializable

/**
 * 极简导航栈。没有用 navigation-compose:第一期无深链需求,
 * 而 iOS 边缘滑动返回要求上一页跟随手指位移,自己持有栈才好做这个联动。
 */
class NavStack(root: Route) {
    private var nextId = 1L

    var entries by mutableStateOf(listOf(NavEntry(nextId++, root)))
        private set

    val current: Route get() = entries.last().route

    val canPop: Boolean get() = entries.size > 1

    fun push(route: Route) {
        entries = entries + NavEntry(nextId++, route)
    }

    fun pop(): Boolean {
        if (!canPop) return false
        entries = entries.dropLast(1)
        return true
    }

    fun popToRoot() {
        if (canPop) entries = entries.take(1)
    }

    companion object {
        val Saver = listSaver<NavStack, NavEntry>(
            save = { it.entries },
            restore = { saved -> NavStack(saved.first().route).apply {
                entries = saved
                nextId = saved.maxOf { it.id } + 1
            } },
        )
    }
}

package org.linuxdo.android.data

/** 缺失楼层优先落到后面的有效回复；超过末尾时回到最后一条。楼层号不等于列表下标。 */
internal fun resolveTopicFloor(posts: List<Post>, requestedFloor: Int): Post? {
    val valid = posts.filter { it.postNumber > 0 }
    return valid.filter { it.postNumber >= requestedFloor }.minByOrNull { it.postNumber }
        ?: valid.maxByOrNull { it.postNumber }
}

/**
 * 只有服务端 stream 能证明两条已加载回复相邻时，才在本地跳过它们之间的楼层缺口。
 * 不能把尚未加载的回复当作已删除，也不能根据帖子 ID 推算楼层号。
 */
internal fun resolveLoadedTopicFloor(posts: List<Post>, stream: List<Long>, requestedFloor: Int): Post? {
    posts.firstOrNull { it.postNumber == requestedFloor }?.let { return it }
    if (stream.isEmpty()) return null
    val before = posts.filter { it.postNumber in 1 until requestedFloor }.maxByOrNull { it.postNumber }
    val after = posts.filter { it.postNumber > requestedFloor }.minByOrNull { it.postNumber }
    val beforeIndex = before?.let { stream.indexOf(it.id) } ?: -1
    val afterIndex = after?.let { stream.indexOf(it.id) } ?: -1
    return when {
        before == null && afterIndex == 0 -> after
        after == null && beforeIndex == stream.lastIndex -> before
        beforeIndex >= 0 && afterIndex == beforeIndex + 1 -> after
        else -> null
    }
}

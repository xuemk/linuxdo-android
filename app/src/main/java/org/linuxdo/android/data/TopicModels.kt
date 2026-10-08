package org.linuxdo.android.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.linuxdo.android.Config

/**
 * linux.do 的 tags 下发的是对象数组(`[{"id":444,"name":"人工智能"}]`),
 * 而原版 Discourse 多数端点给的是字符串数组。两种都要能解析,否则整个列表会解析失败。
 */
object TagNameSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TopicTagName", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> element.content
            is JsonObject -> element["name"]?.jsonPrimitive?.content.orEmpty()
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class Topic(
    val id: Long,
    val title: String,
    val slug: String? = null,
    @SerialName("posts_count") val postsCount: Int = 0,
    @SerialName("reply_count") val replyCount: Int = 0,
    val views: Int = 0,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_posted_at") val lastPostedAt: String? = null,
    @SerialName("bumped_at") val bumpedAt: String? = null,
    val pinned: Boolean = false,
    val closed: Boolean = false,
    val unseen: Boolean = false,
    val excerpt: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val tags: List<@Serializable(with = TagNameSerializer::class) String> = emptyList(),
    @SerialName("last_poster_username") val lastPosterUsername: String? = null,
    val posters: List<Poster> = emptyList(),
)

@Serializable
data class Poster(
    @SerialName("user_id") val userId: Long? = null,
    val description: String? = null,
)

@Serializable
data class DiscourseUser(
    val id: Long,
    val username: String,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
)

@Serializable
data class Category(
    val id: Int,
    val name: String,
    val slug: String = "",
    /** Discourse 下发的是不带 # 的十六进制,例如 "0088CC"。 */
    val color: String? = null,
    @SerialName("text_color") val textColor: String? = null,
    val description: String? = null,
    @SerialName("topic_count") val topicCount: Int = 0,
    @SerialName("parent_category_id") val parentCategoryId: Int? = null,
    /**
     * include_subcategories=true 时,子分类是嵌在父分类里的,而不是平铺进 categories 数组。
     * 不展开就查不到子分类,列表里那些帖子会没有分类标签。
     */
    @SerialName("subcategory_list") val subcategoryList: List<Category> = emptyList(),
)

/** 把嵌套的分类树摊平成一维,便于按 id 查找。 */
fun flattenCategories(categories: List<Category>): List<Category> =
    categories.flatMap { listOf(it) + flattenCategories(it.subcategoryList) }

/** 只有与父模块同名的 Lv 子分类合并,具名子模块保留独立入口。 */
fun compactCategoryGroups(categories: List<Category>): List<Pair<Category, List<Category>>> {
    val flattened = flattenCategories(categories).distinctBy { it.id }
    val parents = mutableMapOf<Int, Category>()
    flattened.forEach { parent -> parent.subcategoryList.forEach { parents[it.id] = parent } }
    val byId = flattened.associateBy { it.id }
    fun parentOf(category: Category) = parents[category.id] ?: byId[category.parentCategoryId]
    fun isLevel(category: Category): Boolean {
        val parent = parentOf(category) ?: return false
        val suffix = Regex("[,，\\s]+Lv\\s*\\d+$", RegexOption.IGNORE_CASE)
        return suffix.containsMatchIn(category.name) && category.name.replace(suffix, "").trim() == parent.name.trim()
    }
    return flattened.filterNot(::isLevel).map { parent ->
        parent to flattened.filter { parentOf(it)?.id == parent.id && isLevel(it) }
    }
}

/** 列表行要展示的全部信息:帖子本体 + 已 join 出的作者与分类。 */
data class TopicListItem(
    val topic: Topic,
    val authorUsername: String?,
    val avatarUrl: String?,
    val category: Category?,
)

data class TopicPage(
    val items: List<TopicListItem>,
    val hasMore: Boolean,
)

@Serializable
data class Post(
    val id: Long,
    val username: String = "",
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("post_number") val postNumber: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    val cooked: String = "",
    @SerialName("topic_id") val topicId: Long = 0,
    val blurb: String = "",
    @SerialName("reply_to_post_number") val replyToPostNumber: Int? = null,
    @SerialName("reply_count") val replyCount: Int = 0,
    val boosts: List<Boost> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    @SerialName("current_user_reaction") val currentUserReaction: Reaction? = null,
    val yours: Boolean = false,
    @SerialName("can_boost") val canBoost: Boolean = false,
    @SerialName("can_accept_answer") val canAcceptAnswer: Boolean = false,
    @SerialName("can_unaccept_answer") val canUnacceptAnswer: Boolean = false,
    @SerialName("accepted_answer") val acceptedAnswer: Boolean = false,
)

@Serializable
data class Boost(val id: Long, val cooked: String = "", val user: DiscourseUser? = null,
    @SerialName("can_delete") val canDelete: Boolean = false)

@Serializable
data class Reaction(val id: String, val count: Int = 0, @SerialName("can_undo") val canUndo: Boolean = false)

data class TreePost(val post: Post, val depth: Int, val childCount: Int, val parentMissing: Boolean)

/** 缺失父楼层时保留为根节点,避免分页时漏掉可见回复。 */
fun threadRows(posts: List<Post>, collapsed: Set<Long> = emptySet()): List<TreePost> {
    val ordered = posts.distinctBy { it.id }.sortedBy { it.postNumber }
    val byNumber = ordered.associateBy { it.postNumber }
    val children = ordered.groupBy { post ->
        val parent = post.replyToPostNumber?.let { byNumber[it] }
        if (parent != null && parent.postNumber < post.postNumber) parent.id else null
    }
    val result = mutableListOf<TreePost>()
    val pending = java.util.ArrayDeque<Pair<Post, Int>>()
    children[null].orEmpty().asReversed().forEach { pending.addLast(it to 0) }
    while (pending.isNotEmpty()) {
        val (post, depth) = pending.removeLast()
        val replies = children[post.id].orEmpty()
        result += TreePost(post, depth, replies.size, post.replyToPostNumber?.let { it > 1 && it !in byNumber } == true)
        if (post.id !in collapsed) replies.asReversed().forEach { pending.addLast(it to depth + 1) }
    }
    return result
}

/** 与树形排列使用同一父子约束,仅展开通知目标的祖先。 */
fun postAncestorIds(posts: List<Post>, postNumber: Int): Set<Long> {
    val byNumber = posts.associateBy { it.postNumber }
    var current = byNumber[postNumber] ?: return emptySet()
    val ancestors = mutableSetOf<Long>()
    while (true) {
        val parent = current.replyToPostNumber?.let { byNumber[it] } ?: break
        if (parent.postNumber >= current.postNumber || !ancestors.add(parent.id)) break
        current = parent
    }
    return ancestors
}

@Serializable
data class PostStream(
    val posts: List<Post> = emptyList(),
    val stream: List<Long> = emptyList(),
)

@Serializable
data class TopicDetail(
    val id: Long,
    val title: String,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("post_stream") val postStream: PostStream = PostStream(),
    @SerialName("valid_reactions") val validReactions: List<String> = emptyList(),
)

data class SearchPage(
    val items: List<TopicListItem>,
    val excerpts: Map<Long, String>,
    val hasMore: Boolean,
)

enum class SearchOrder(val label: String, val key: String) {
    Relevance("相关性", ""), Latest("最新帖子", "latest"), Likes("赞最多", "likes"),
    Views("浏览最多", "views"), LatestTopic("最新话题", "latest_topic"), Read("最近阅读", "read"),
}

enum class ProfileSection(val label: String) {
    Summary("总结"), Activity("活动"), Notifications("通知"), Messages("消息"), Account("账号与邮箱"),
}

enum class NotificationFilter(val label: String) {
    All("全部"), Replies("回复"), Likes("点赞"), Messages("私信");

    fun includes(type: Int): Boolean = when (this) {
        All -> true
        Replies -> type in setOf(1, 2, 3, 15, 43)
        Likes -> type in setOf(5, 19, 25)
        Messages -> type in setOf(6, 7, 16)
    }
}

@Serializable
internal data class NotificationCountItem(
    val id: Long,
    @SerialName("notification_type") val type: Int,
    val read: Boolean = true,
) {
    val countsAsUnread: Boolean get() = !read &&
        (NotificationFilter.Replies.includes(type) || NotificationFilter.Messages.includes(type))
}

@Serializable
internal data class UnreadNotificationPage(
    val notifications: List<NotificationCountItem> = emptyList(),
    @SerialName("total_rows_notifications") val totalRows: Long? = null,
) {
    // 服务端会在分页后剔除不可访问的话题,不能仅凭当前页条数判断已到末尾。
    fun hasMore(offset: Int, limit: Int): Boolean = totalRows?.let { offset.toLong() + limit < it }
        ?: (notifications.size >= limit)
}

data class ProfileEntry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val topicId: Long? = null,
    val unread: Boolean = false,
    val notificationType: Int = 0,
    val avatarUrl: String? = null,
    val actor: String? = null,
    val postNumber: Int? = null,
)

data class ProfilePage(
    val fields: List<Pair<String, String>> = emptyList(),
    val entries: List<ProfileEntry> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * avatar_template 形如 `/user_avatar/linux.do/foo/{size}/123_2.png`,
 * 需要替换 {size} 并补全为绝对地址。
 */
fun avatarUrlOf(template: String?, size: Int = 144): String? {
    if (template.isNullOrBlank()) return null
    val path = template.replace("{size}", size.toString())
    if (path.startsWith("//")) return "https:$path"
    return if (path.startsWith("http", ignoreCase = true)) path else Config.BASE_URL + path
}

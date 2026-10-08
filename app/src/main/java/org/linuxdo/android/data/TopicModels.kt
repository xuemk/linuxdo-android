package org.linuxdo.android.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("can_delete") val canDelete: Boolean = false,
    /** post_stream 里的楼层不带 raw,编辑前要单独取一次。 */
    val raw: String? = null,
)

@Serializable
data class Boost(val id: Long, val cooked: String = "", val user: DiscourseUser? = null,
    @SerialName("can_delete") val canDelete: Boolean = false)

/**
 * 简评的长度。discourse-boosts 对"可见字符"和"表情"分别设限,任一项超了都会被服务端拒掉,
 * 所以提交前必须先在本地算一遍 —— 否则用户要等一个来回才知道写长了。
 */
data class BoostLength(val visible: Int, val emoji: Int) {
    val overVisible: Boolean get() = visible > MAX_VISIBLE
    val overEmoji: Boolean get() = emoji > MAX_EMOJI
    val submittable: Boolean get() = !overVisible && !overEmoji && (visible > 0 || emoji > 0)

    companion object {
        const val MAX_VISIBLE = 16
        const val MAX_EMOJI = 5

        /** 输入框的原始字符上限。给 `:shortcode:` 留足空间,不是服务端规则。 */
        const val RAW_INPUT_CAP = 120
    }
}

private val EMOJI_SHORTCODE = Regex(":[a-zA-Z0-9_+-]{1,40}:")
private const val ZERO_WIDTH_JOINER = 0x200D

/**
 * 统计简评长度。
 *
 * 表情有两种写法:`:smile:` 这种 shortcode,和直接输入的 Unicode 表情。
 * Unicode 这边按"基字符 + 紧跟的修饰符"算一个:肤色、变体选择符、ZWJ 连起来的后续字符
 * 以及成对的区域指示符(国旗)都并入前一个表情,不另外计数。
 *
 * 这是对服务端规则的近似 —— 站方的确切计数方式没有公开,所以界面同时把实时计数显示出来,
 * 真被拒时也仍然会把服务端原话展示给用户。
 */
fun measureBoost(raw: String): BoostLength {
    var emoji = 0
    val stripped = EMOJI_SHORTCODE.replace(raw.trim()) { emoji++; "" }
    var visible = 0
    var index = 0
    var joinNext = false
    var pendingRegional = false
    while (index < stripped.length) {
        val code = stripped.codePointAt(index)
        index += Character.charCount(code)
        when {
            code == ZERO_WIDTH_JOINER -> joinNext = true
            isEmojiModifier(code) -> Unit
            isRegionalIndicator(code) -> {
                // 两个区域指示符拼成一面国旗,只算一个表情。
                if (pendingRegional) pendingRegional = false else { emoji++; pendingRegional = true }
                joinNext = false
            }
            isEmojiBase(code) -> {
                if (!joinNext) emoji++
                joinNext = false
                pendingRegional = false
            }
            else -> {
                visible++
                joinNext = false
                pendingRegional = false
            }
        }
    }
    return BoostLength(visible, emoji)
}

private fun isEmojiModifier(code: Int): Boolean =
    code in 0xFE00..0xFE0F || code in 0x1F3FB..0x1F3FF || code == 0x20E3

private fun isRegionalIndicator(code: Int): Boolean = code in 0x1F1E6..0x1F1FF

private fun isEmojiBase(code: Int): Boolean =
    code in 0x1F000..0x1FAFF || code in 0x2600..0x27BF || code in 0x2B00..0x2BFF

/**
 * 站方对上传的限制。都以服务端实际报错为准,客户端先拦一道,免得用户传完才被拒。
 *
 * 服务端原话:
 * - "允许的扩展名：jpg、jpeg、png、gif、heic、heif、webp、avif、svg、txt、pdf、doc、docx、csv、zip、7z、gz、xz、jxl、md"
 * - "您尝试上传的文件太大（大小上限为 4 MB）"
 */
object UploadLimits {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val TOO_LARGE_MESSAGE = "文件超过 4 MB 上限"

    val ALLOWED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "avif", "svg",
        "txt", "pdf", "doc", "docx", "csv", "zip", "7z", "gz", "xz", "jxl", "md",
    )

    /** 能用 BitmapFactory 解码重编码的位图格式。GIF 会丢动画,SVG 是矢量,AVIF/JXL 解码支持不稳,都不碰。 */
    private val COMPRESSIBLE = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    /** 解码时先降采样,限制位图占用的内存。 */
    const val MAX_IMAGE_EDGE = 2560
    const val JPEG_QUALITY = 85

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun isAllowed(fileName: String): Boolean = extensionOf(fileName) in ALLOWED_EXTENSIONS

    fun isCompressibleImage(fileName: String): Boolean = extensionOf(fileName) in COMPRESSIBLE

    fun rejectReason(fileName: String, bytes: Long?): String? = when {
        bytes != null && bytes > MAX_BYTES -> TOO_LARGE_MESSAGE
        bytes == 0L -> "文件为空，无法上传"
        !isAllowed(fileName) ->
            "不支持的文件类型 .${extensionOf(fileName).ifBlank { "未知" }}，可传：" +
                ALLOWED_EXTENSIONS.joinToString("、")
        else -> null
    }

    /** 元数据只做预检；实际最多读取上限加 1 字节,防止大小缺失、失真或文件在读取时变大。 */
    fun readBytes(fileName: String, reportedSize: Long?, openStream: () -> InputStream?): ByteArray {
        rejectReason(fileName, reportedSize)?.let { throw IllegalArgumentException(it) }
        return (openStream() ?: throw IllegalStateException("无法读取所选文件")).use { input ->
            val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val remaining = MAX_BYTES - output.size()
                var count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                if (count == 0) {
                    val next = input.read()
                    if (next == -1) break
                    buffer[0] = next.toByte()
                    count = 1
                }
                if (count == -1) break
                require(count <= remaining) { TOO_LARGE_MESSAGE }
                output.write(buffer, 0, count)
            }
            rejectReason(fileName, output.size().toLong())?.let { throw IllegalArgumentException(it) }
            output.toByteArray()
        }
    }
}

/** 一次上传的结果。short_url 是 Discourse 的内部短链,要原样写进正文 raw。 */
data class UploadedFile(
    val shortUrl: String,
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** 拼成 Discourse 正文里的 Markdown。图片带尺寸,其余走 attachment 语法。 */
    fun toMarkdown(): String =
        if (width > 0 && height > 0) "![$fileName|${width}x$height]($shortUrl)"
        else "[$fileName|attachment]($shortUrl)"
}

@Serializable
data class Reaction(val id: String, val count: Int = 0, @SerialName("can_undo") val canUndo: Boolean = false)

data class TreePost(val post: Post, val depth: Int, val childCount: Int, val parentMissing: Boolean)

private val ASIDE_TAG = Regex("""<aside\b[^>]*>""", RegexOption.IGNORE_CASE)
private val DATA_POST_ATTR = Regex("""\bdata-post\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)
private val DATA_TOPIC_ATTR = Regex("""\bdata-topic\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)

/**
 * 优先取服务端显式 reply_to_post_number；若为空（例如通过引用 `[quote="..., post:13"]` 回复），
 * 则从 cooked 首个同话题 quote 块提取 data-post 作为父楼层号，使客户端树形挂载与服务端 reply_count 口径对齐。
 */
fun Post.effectiveReplyToPostNumber(): Int? {
    replyToPostNumber?.let { return it }
    if (!cooked.contains("quote", ignoreCase = true)) return null
    val aside = ASIDE_TAG.find(cooked)?.value ?: return null
    val quotedTopic = DATA_TOPIC_ATTR.find(aside)?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (quotedTopic != null && topicId > 0L && quotedTopic != topicId) return null
    return DATA_POST_ATTR.find(aside)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 && it < postNumber }
}

/** 缺失父楼层时保留为根节点,避免分页时漏掉可见回复。 */
fun threadRows(posts: List<Post>, collapsed: Set<Long> = emptySet()): List<TreePost> {
    val ordered = posts.distinctBy { it.id }.sortedBy { it.postNumber }
    val byNumber = ordered.associateBy { it.postNumber }
    val children = ordered.groupBy { post ->
        val parent = post.effectiveReplyToPostNumber()?.let { byNumber[it] }
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
        val parent = current.effectiveReplyToPostNumber()?.let { byNumber[it] } ?: break
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

/** 话题级权限。没有 can_create_post 字段的旧响应按"可回复"处理,由服务端最终裁决。 */
@Serializable
data class TopicDetails(
    @SerialName("can_create_post") val canCreatePost: Boolean = true,
)

@Serializable
data class TopicDetail(
    val id: Long,
    val title: String,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("post_stream") val postStream: PostStream = PostStream(),
    @SerialName("valid_reactions") val validReactions: List<String> = emptyList(),
    val closed: Boolean = false,
    val archived: Boolean = false,
    val details: TopicDetails = TopicDetails(),
) {
    val canReply: Boolean get() = details.canCreatePost && !closed && !archived
}

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

    /**
     * Discourse 的 `filter_by_types` 参数值(用户菜单各 tab 用的就是它)。
     * 服务端若不认这个参数会直接忽略、返回全类型,此时由 Repository 的循环补页兜住,
     * 所以带上它只会变快、不会变错。
     */
    val serverTypes: String get() = when (this) {
        All -> ""
        Replies -> "mentioned,replied,quoted,group_mentioned,chat_quoted"
        Likes -> "liked,liked_consolidated,reaction"
        Messages -> "private_message,invited_to_private_message,group_message_summary"
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

data class UnreadSummary(
    val count: Int = 0,
    val unreadFilters: Set<NotificationFilter> = emptySet(),
    /**
     * 每个分类各自的未读条数。
     * 总 count 走的是铃铛口径 —— 只算回复类和私信类(见 NotificationCountItem.countsAsUnread),
     * 点赞根本不计入。所以判断"某个分类有没有新东西"必须看这里的分项值,用 count 会让
     * 点赞分类永远判定为没变化。
     */
    val unreadByFilter: Map<NotificationFilter, Int> = emptyMap(),
)

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
    /**
     * 下一页游标。通知分区是服务端 offset —— 按类型筛选后"已显示条数"和 offset 不再等价,
     * 用页码递推会漏掉被过滤掉的那些条目。其余分区游标就是页码。
     */
    val nextCursor: Int = 0,
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

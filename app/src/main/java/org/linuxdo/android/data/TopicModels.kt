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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.linuxdo.android.Config

/** 首页顶部支持切换的 4 个 Feed 类别。默认是 Latest。 */
enum class HomeFeedType(val label: String, val apiPath: String) {
    Latest("最新", "/latest.json"),
    Unread("未读", "/unseen.json"),
    Hot("热门", "/hot.json"),
    Posted("我的帖子", "/posted.json");

    companion object {
        fun fromName(name: String?): HomeFeedType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Latest
    }
}

/** 话题列表排序方式，与 Discourse API 参数及 FluxDO 对齐。 */
enum class TopicSortOrder(val label: String, val apiValue: String?) {
    Default("默认", null),
    Activity("活跃度", "activity"),
    Created("创建时间", "created"),
    Likes("点赞数", "likes"),
    Views("浏览量", "views"),
    Posts("回复数", "posts"),
    Posters("参与者", "posters");

    companion object {
        fun fromName(name: String?): TopicSortOrder =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Default
    }
}

/** 辅助悬浮球支持的快捷动作类型。最多配置 8 项，用户可调整顺序与开启状态。 */
enum class FloatingAction(
    val id: String,
    val title: String,
    val shortLabel: String,
    val description: String,
) {
    CreateTopic("create_topic", "发帖", "发帖", "快速发布新话题"),
    Bookmarks("bookmarks", "我的收藏", "收藏", "查看我收藏的话题"),
    Following("following", "我的关注", "关注", "查看关注的用户与话题"),
    Likes("likes", "点赞", "点赞", "查看我的点赞与获赞"),
    Replies("replies", "回帖", "回帖", "查看我发表的回复"),
    Connect("connect", "等级积分", "等级积分", "查看升级进度与我的积分"),
    History("history", "历史", "历史", "查看阅读历史记录"),
    Chat("chat", "聊天", "聊天", "进入实时聊天室"),
    Sort("sort", "排序", "排序", "切换话题列表排序"),
    Drafts("drafts", "草稿", "草稿", "查看与编辑草稿"),
    Search("search", "搜索", "搜索", "全站快捷搜索"),
    Categories("categories", "分类", "分类", "浏览全部话题分类");

    companion object {
        const val MAX_ACTIONS = 5
        val DEFAULT_ACTIONS = listOf(CreateTopic, Bookmarks, History, Following, Sort)
        private const val OLD_DEFAULT_SERIALIZED_2 = "create_topic,bookmarks,following,likes,replies"
        private const val OLD_DEFAULT_SERIALIZED = "create_topic,sort,drafts,search,categories"

        fun fromId(id: String?): FloatingAction? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }

        fun parseActions(serialized: String?): List<FloatingAction> {
            if (serialized.isNullOrBlank() || serialized == OLD_DEFAULT_SERIALIZED || serialized == OLD_DEFAULT_SERIALIZED_2) return DEFAULT_ACTIONS
            val list = serialized.split(",")
                .mapNotNull { fromId(it.trim()) }
                .distinct()
            return if (list.isEmpty()) DEFAULT_ACTIONS else list.take(MAX_ACTIONS)
        }

        fun serializeActions(actions: List<FloatingAction>): String =
            actions.distinct().take(MAX_ACTIONS).joinToString(",") { it.id }
    }
}

/** 用户在社区的回复历史数据项。 */
data class UserReplyItem(
    val id: String,
    val topicId: Long,
    val postNumber: Int?,
    val title: String,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryColor: String?,
    val createdAt: String?,
    val commentExcerpt: String,
    val username: String,
    val avatarUrl: String?,
)

data class UserRepliesUiState(
    val items: List<UserReplyItem> = emptyList(),
    val loading: Boolean = false,
    val initialLoading: Boolean = true,
    val hasMore: Boolean = false,
    val cursor: Int = 0,
    val error: String? = null,
)

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

object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString().trim('"')
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class DiscourseTag(
    @Serializable(with = FlexibleStringSerializer::class) val id: String = "",
    val name: String = "",
    val text: String = "",
    val count: Int = 0,
    @SerialName("topic_count") val topicCount: Int = 0,
    @SerialName("staff_topic_count") val staffTopicCount: Int = 0,
    @SerialName("pm_count") val pmCount: Int = 0,
    val description: String? = null,
) {
    val displayName: String
        get() = name.ifBlank { text.ifBlank { id } }

    val effectiveCount: Int
        get() = maxOf(count, topicCount, staffTopicCount)
}

@Serializable
data class DiscourseTagGroupItem(
    @Serializable(with = FlexibleStringSerializer::class) val id: String = "",
    val name: String = "",
    val tags: List<DiscourseTag> = emptyList(),
)

@Serializable
data class DiscourseCategoryTagsItem(
    @Serializable(with = FlexibleStringSerializer::class) val id: String = "",
    val tags: List<DiscourseTag> = emptyList(),
)

@Serializable
data class DiscourseTagsExtras(
    @SerialName("tag_groups") val tagGroups: List<DiscourseTagGroupItem> = emptyList(),
    val categories: List<DiscourseCategoryTagsItem> = emptyList(),
)

@Serializable
data class DiscourseTagsResponse(
    val tags: List<DiscourseTag> = emptyList(),
    val extras: DiscourseTagsExtras? = null,
) {
    /**
     * 对齐 FluxDO (getSiteTagGroups)：
     * 开启 tags_listed_by_group 的站点（如 Linux.do）大部分核心标签位于 extras.tag_groups（及 extras.categories）中，
     * 顶层 tags 仅存放未归入分组的兜底标签。汇总全部分组、分类及顶层标签，合并真实话题数并按话题数倒序排序。
     */
    fun allTagsSorted(): List<DiscourseTag> {
        val merged = linkedMapOf<String, DiscourseTag>()

        fun upsertGlobal(tag: DiscourseTag) {
            val key = tag.displayName.trim()
            if (key.isEmpty()) return
            val normalizedCount = tag.effectiveCount
            val existing = merged[key]
            if (existing == null) {
                merged[key] = tag.copy(
                    name = tag.name.ifBlank { key },
                    text = tag.text.ifBlank { key },
                    count = normalizedCount,
                )
            } else {
                merged[key] = existing.copy(
                    count = maxOf(existing.count, normalizedCount),
                    description = existing.description ?: tag.description,
                )
            }
        }

        // 1. 优先解析 extras.tag_groups（与 FluxDO 顺序一致）
        extras?.tagGroups?.forEach { group ->
            group.tags.forEach(::upsertGlobal)
        }

        // 2. 解析顶层未分组标签 tags
        tags.forEach(::upsertGlobal)

        // 3. 解析 extras.categories（分类限定标签，同一标签跨多个分类时累加分类内计数并与全局计数取最大值）
        val categorySums = linkedMapOf<String, Int>()
        extras?.categories?.forEach { categoryGroup ->
            val seenInCategory = hashSetOf<String>()
            categoryGroup.tags.forEach { tag ->
                val key = tag.displayName.trim()
                if (key.isNotEmpty() && seenInCategory.add(key)) {
                    val c = tag.effectiveCount
                    categorySums[key] = (categorySums[key] ?: 0) + c
                    if (key !in merged) {
                        merged[key] = tag.copy(
                            name = tag.name.ifBlank { key },
                            text = tag.text.ifBlank { key },
                            count = c,
                        )
                    }
                }
            }
        }
        categorySums.forEach { (key, sumCount) ->
            val existing = merged[key] ?: return@forEach
            if (sumCount > existing.count) {
                merged[key] = existing.copy(count = sumCount)
            }
        }

        return merged.values.sortedWith(
            compareByDescending<DiscourseTag> { it.count }
                .thenBy { it.displayName.lowercase() }
        )
    }
}

@Serializable
data class Topic(
    val id: Long,
    val title: String,
    val slug: String? = null,
    @SerialName("posts_count") val postsCount: Int = 0,
    @SerialName("reply_count") val replyCount: Int = 0,
    @SerialName("last_read_post_number") val lastReadPostNumber: Int? = null,
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
    val thumbnails: List<TopicThumbnail>? = null,
    val tags: List<@Serializable(with = TagNameSerializer::class) String> = emptyList(),
    @SerialName("last_poster_username") val lastPosterUsername: String? = null,
    val posters: List<Poster> = emptyList(),
    @SerialName("notification_level") val notificationLevel: Int? = null,
    @SerialName("highest_post_number") val highestPostNumber: Int? = null,
) {
    /**
     * 列表要显示的"总回复数",口径与网页端一致。
     *
     * reply_count 只统计挂在楼层下的直接回复(楼中楼),直接回复整个话题的楼层压根不计入,
     * 所以热门帖常出现"列表显示 3 回复、点进去有 80 楼"。posts_count 含 1 楼主贴,减 1 才是回复数;
     * 取两者较大值兜住个别端点不下发 posts_count 的情况。
     */
    val totalReplies: Int get() = (postsCount - 1).coerceAtLeast(replyCount).coerceAtLeast(0)
}

/** Discourse 列表接口随话题下发的服务端缩略图。 */
@Serializable
data class TopicThumbnail(
    @SerialName("max_width") val maxWidth: Int? = null,
    @SerialName("max_height") val maxHeight: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val url: String? = null,
)

/**
 * 选取列表所需的服务端派生缩略图，避免将 `/original/` 原图作为常规列表资源。
 * 只有服务端没下发派生图时，才回退到原图 URL。
 */
fun Topic.thumbnailUrlForList(targetWidth: Int = 400): String? {
    val valid = thumbnails.orEmpty().mapNotNull { thumbnail ->
        val url = normalizeTopicImageUrl(thumbnail.url) ?: return@mapNotNull null
        val width = thumbnail.maxWidth ?: thumbnail.width
        ThumbnailCandidate(
            url = url,
            width = width,
            isDerived = thumbnail.maxWidth != null,
        )
    }
    val derived = valid.filter { it.isDerived && it.width != null }
    val candidates = if (derived.isNotEmpty()) derived else valid.filter { it.width != null }
    val selected = candidates.minByOrNull { candidate ->
        kotlin.math.abs(candidate.width!! - targetWidth)
    }
    return selected?.url ?: normalizeTopicImageUrl(imageUrl)
}

/** 仅用于缩略图拉取失败时的最后回退，正常列表路径优先 [thumbnailUrlForList]。 */
fun Topic.originalImageUrl(): String? = normalizeTopicImageUrl(imageUrl)

private data class ThumbnailCandidate(
    val url: String,
    val width: Int?,
    val isDerived: Boolean,
)

private fun normalizeTopicImageUrl(rawUrl: String?): String? {
    val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> Config.BASE_URL + url
        else -> "${Config.BASE_URL}/$url"
    }
}

@Serializable
data class Poster(
    @SerialName("user_id") val userId: Long? = null,
    val description: String? = null,
    val user: DiscourseUser? = null,
)

@Serializable
data class DiscourseUser(
    val id: Long = 0L,
    val username: String = "",
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    /** 信任等级(Lv0~Lv4)。列表接口在 users[] 里下发,话题详情的楼层里没有。 */
    @SerialName("trust_level") val trustLevel: Int? = null,
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
    @SerialName("allowed_tags") val allowedTags: List<String> = emptyList(),
    @SerialName("read_restricted") val readRestricted: Boolean = false,
)

/** 获取该分类支持的固定标签列表；不同分类可能不同，不可自定义随意输入。 */
fun fixedTagsForCategory(category: Category?): List<String> {
    if (category != null && category.allowedTags.isNotEmpty()) {
        return category.allowedTags.distinct()
    }
    val name = category?.name.orEmpty()
    return when {
        name.contains("资源") || name.contains("福利") -> listOf("软件", "工具", "教程", "福利", "影视", "音乐", "游戏", "电子书", "开源")
        name.contains("开发") || name.contains("代码") || name.contains("技术") || name.contains("调优") -> listOf("前端", "后端", "Python", "Java", "Docker", "Linux", "开源", "Bug", "架构", "运维")
        name.contains("AI") || name.contains("人工智能") || name.contains("模型") -> listOf("ChatGPT", "Claude", "Gemini", "DeepSeek", "API", "提示词", "本地部署", "生图")
        name.contains("问答") || name.contains("求助") || name.contains("快问") -> listOf("求助", "咨询", "配置", "排错", "推荐", "新手")
        name.contains("运营") || name.contains("站务") || name.contains("公告") -> listOf("公告", "反馈", "建议", "活动", "规则")
        name.contains("羊毛") || name.contains("打折") -> listOf("活动", "优惠", "免费", "薅羊毛", "限免")
        else -> listOf("教程", "经验分享", "工具推荐", "求助", "日常", "讨论", "资讯", "测试")
    }
}

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

fun orderedCategoryGroups(categories: List<Category>, order: List<Long>): List<Pair<Category, List<Category>>> {
    val positions = order.distinct().withIndex().associate { it.value to it.index }
    return compactCategoryGroups(categories).sortedBy { positions[it.first.id.toLong()] ?: Int.MAX_VALUE }
}

@Serializable
data class CustomSectionLink(
    val id: String = java.util.UUID.randomUUID().toString(),
    val icon: String = "🔗",
    val name: String = "",
    val url: String = "",
    val serverId: Long? = null,
)

@Serializable
data class CustomSection(
    val id: String = "custom_section_default",
    val title: String = "自定义版块",
    val links: List<CustomSectionLink> = emptyList(),
    val serverId: Long? = null,
)

object CustomSectionIconMapper {
    private val EMOJI_TO_FA = mapOf(
        "⭐" to "star",
        "🌟" to "star",
        "🔥" to "fire",
        "🚀" to "rocket",
        "💡" to "lightbulb",
        "📌" to "thumbtack",
        "💬" to "comments",
        "💭" to "comments",
        "📚" to "book",
        "📖" to "book",
        "💻" to "code",
        "🔧" to "wrench",
        "🎯" to "bullseye",
        "☕" to "mug-hot",
        "❤️" to "heart",
        "💖" to "heart",
        "🎉" to "champagne-glasses",
        "✨" to "wand-magic-sparkles",
        "⚡" to "bolt",
        "🔒" to "lock",
        "⚙️" to "gear",
        "🔔" to "bell",
        "📢" to "bullhorn",
        "🔖" to "bookmark",
        "🏷️" to "tag",
        "🌐" to "globe",
        "🔍" to "magnifying-glass",
        "🔗" to "link",
        "📁" to "folder",
        "📂" to "folder-open",
        "👤" to "user",
        "👥" to "users",
        "🚩" to "flag",
        "✈️" to "paper-plane",
        "🎖️" to "certificate",
    )

    private val FA_TO_EMOJI = mapOf(
        "star" to "⭐",
        "fire" to "🔥",
        "rocket" to "🚀",
        "lightbulb" to "💡",
        "thumbtack" to "📌",
        "comments" to "💬",
        "comment" to "💬",
        "book" to "📚",
        "code" to "💻",
        "wrench" to "🔧",
        "bullseye" to "🎯",
        "mug-hot" to "☕",
        "heart" to "❤️",
        "champagne-glasses" to "🎉",
        "wand-magic-sparkles" to "✨",
        "bolt" to "⚡",
        "lock" to "🔒",
        "gear" to "⚙️",
        "bell" to "🔔",
        "bullhorn" to "📢",
        "bookmark" to "🔖",
        "tag" to "🏷️",
        "globe" to "🌐",
        "magnifying-glass" to "🔍",
        "search" to "🔍",
        "link" to "🔗",
        "external-link" to "🔗",
        "layer-group" to "📂",
        "folder" to "📁",
        "folder-open" to "📂",
        "user" to "👤",
        "users" to "👥",
        "user-group" to "👥",
        "inbox" to "📥",
        "flag" to "🚩",
        "paper-plane" to "✈️",
        "certificate" to "🎖️",
        "filter" to "🔎",
        "circle-info" to "ℹ️",
        "circle-question" to "❓",
    )

    /**
     * 将输入的图标（Emoji 或文本）转为 Discourse PC 端能够合法接受并正常渲染的 FontAwesome 图标名。
     * 服务端要求 validates :icon, presence: true，兜底为 "link"。
     */
    fun toFontAwesome(input: String?): String {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return "link"
        if (trimmed.matches(Regex("^[a-z0-9-]+$"))) return trimmed
        return EMOJI_TO_FA[trimmed] ?: "link"
    }

    /**
     * 将 PC 端的 FontAwesome 图标名转换为手机端美观的 Emoji。
     */
    fun toDisplayIcon(icon: String?): String {
        val trimmed = icon?.trim().orEmpty()
        if (trimmed.isEmpty()) return "🔗"
        FA_TO_EMOJI[trimmed.lowercase()]?.let { return it }
        return trimmed
    }
}

data class InternalTopicLink(
    val topicId: Long,
    val postNumber: Int? = null,
)

/**
 * ???????????????????????????? linux.do ?????
 * ?? (??? URL, ???? InternalTopicLink)?
 */
fun parseTopicLinkFromText(text: String?): Pair<String, InternalTopicLink>? {
    if (text.isNullOrBlank()) return null
    val regex = Regex("""https?://(?:[a-zA-Z0-9\-]+\.)?linux\.do/t/[^\s\r\n"'<>()\[\]\uFF08\uFF09\u3010\u3011\u300A\u300B\u201C\u201D\u2018\u2019\uFF0C\u3002\uFF01\uFF1F\uFF1B\uFF1A,!?]+""")
    val match = regex.find(text) ?: return null
    var rawUrl = match.value.trim()
    rawUrl = rawUrl.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}', '>', '\u3002', '\uff0c', '\uff01', '\uff1f', '\uff1b', '\uff1a', '\uff09', '\u3011', '\u201d', '\u2019')
    val link = parseInternalTopicUrl(rawUrl) ?: return null
    return Pair(rawUrl, link)
}

/**
 * ?????linux.do?????????
 * - `https://linux.do/t/{slug}/{topicId}`
 * - `https://linux.do/t/{slug}/{topicId}/{postNumber}`
 * - `https://linux.do/t/{topicId}`
 * - `https://linux.do/t/{topicId}/{postNumber}`
 */
fun parseInternalTopicUrl(url: String): InternalTopicLink? = runCatching {
    val uri = android.net.Uri.parse(url)
    val host = uri.host?.lowercase() ?: return null
    if (host != Config.HOST && !host.endsWith(".${Config.HOST}")) return null
    val segments = uri.pathSegments ?: return null
    if (segments.size < 2 || segments[0] != "t") return null
    val secondAsId = segments[1].toLongOrNull()
    when {
        secondAsId == null -> {
            val topicId = segments.getOrNull(2)?.toLongOrNull() ?: return null
            val postNum = segments.getOrNull(3)?.toIntOrNull()?.takeIf { it > 1 }
            InternalTopicLink(topicId = topicId, postNumber = postNum)
        }
        segments.size == 2 -> InternalTopicLink(topicId = secondAsId, postNumber = null)
        segments.size >= 4 -> {
            val topicId = segments[2].toLongOrNull() ?: return null
            val postNum = segments[3].toIntOrNull()?.takeIf { it > 1 }
            InternalTopicLink(topicId = topicId, postNumber = postNum)
        }
        else -> {
            val thirdAsLong = segments[2].toLongOrNull() ?: return null
            if (thirdAsLong > secondAsId && thirdAsLong >= 1000L) {
                InternalTopicLink(topicId = thirdAsLong, postNumber = null)
            } else {
                InternalTopicLink(topicId = secondAsId, postNumber = thirdAsLong.toInt().takeIf { it > 1 })
            }
        }
    }
}.getOrNull()

enum class TopicNotificationLevel(
    val code: Int,
    val title: String,
    val badgeLabel: String,
    val description: String,
) {
    Watching(
        code = 3,
        title = "关注",
        badgeLabel = "关注",
        description = "您将在此话题有新回复时收到通知，并且会显示新回复数量。",
    ),
    Tracking(
        code = 2,
        title = "跟踪",
        badgeLabel = "跟踪",
        description = "将显示此话题的新回复数量。您会在别人 @ 您或回复您时收到通知。",
    ),
    Regular(
        code = 1,
        title = "常规",
        badgeLabel = "常规",
        description = "您会在别人 @ 您或回复您时收到通知。",
    ),
    Muted(
        code = 0,
        title = "免打扰",
        badgeLabel = "免打扰",
        description = "您永远不会收到有关此话题的任何通知，它也不会出现在最新话题中。",
    );

    companion object {
        fun fromCode(code: Int?): TopicNotificationLevel =
            entries.firstOrNull { it.code == code } ?: Regular
    }
}

/** 列表行要展示的全部信息:帖子本体 + 已 join 出的作者与分类。 */
data class TopicListItem(
    val topic: Topic,
    val authorUsername: String?,
    val authorName: String? = null,
    val avatarUrl: String?,
    val staticAvatarUrl: String? = null,
    val category: Category?,
    val trustLevel: Int? = null,
    val notificationLevel: TopicNotificationLevel? = null,
    val notificationLevelUpdating: Boolean = false,
) {
    /** 优先展示论坛昵称（如“用户已注销”），无昵称则回退到用户名（如“NullUser”），对齐 fluxdo */
    val displayName: String
        get() = authorName?.trim()?.takeIf { it.isNotEmpty() } ?: authorUsername.orEmpty()
}

data class TopicPage(
    val items: List<TopicListItem>,
    val hasMore: Boolean,
)

/** 只回写统计快照；保留行顺序、作者、已读状态和本地操作状态。 */
internal fun mergeTopicCounters(items: List<TopicListItem>, fresh: Map<Long, Topic>): List<TopicListItem> {
    var changed = false
    val merged = items.map { item ->
        val source = fresh[item.topic.id] ?: return@map item
        val topic = item.topic.copy(postsCount = source.postsCount, replyCount = source.replyCount,
            views = source.views, lastPostedAt = source.lastPostedAt,
            highestPostNumber = source.highestPostNumber ?: item.topic.highestPostNumber.takeIf { source.postsCount <= item.topic.postsCount })
        if (topic == item.topic) item else item.copy(topic = topic).also { changed = true }
    }
    return if (changed) merged else items
}

internal fun mergeConfirmedTopicRead(topic: Topic, confirmedPosts: Set<Int>): Topic {
    val highest = confirmedPosts.maxOrNull() ?: return topic
    return topic.copy(unseen = false, lastReadPostNumber = maxOf(topic.lastReadPostNumber ?: 0, highest))
}

/** 服务端草稿条目（兼容创建新话题草稿与话题回复草稿）。 */
data class DraftItem(
    val draftKey: String,
    val sequence: Long = 0L,
    val title: String = "",
    val raw: String = "",
    val excerpt: String = "",
    val categoryId: Int? = null,
    val category: Category? = null,
    val tags: List<String> = emptyList(),
    val action: String = "createTopic",
    val topicId: Long? = null,
    val replyToPostNumber: Int? = null,
    val postId: Long? = null,
    val createdAt: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val closed: Boolean = false,
    val archetype: String = "regular",
) {
    val isNewTopic: Boolean
        get() = action == "createTopic" || draftKey.startsWith("new_topic") || topicId == null || topicId <= 0L
}

sealed class PostResult {
    data class Success(val post: Post) : PostResult()
    data object Enqueued : PostResult()

    companion object {
        fun decode(response: JsonElement, json: Json): PostResult {
            val root = response as? JsonObject
                ?: error("提交结果无法确认，请先检查回复是否已发布")
            val action = (root["action"] as? JsonPrimitive)?.contentOrNull
            val queued = (root["queued"] as? JsonPrimitive)?.booleanOrNull == true
            if (action == "enqueued" || root["pending_post"] is JsonObject || queued) return Enqueued
            return Success(json.decodeFromJsonElement(Post.serializer(), root))
        }
    }
}

@Serializable
data class Post(
    val id: Long,
    val username: String = "",
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("post_number") val postNumber: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    val cooked: String = "",
    @SerialName("topic_id") val topicId: Long = 0,
    val blurb: String = "",
    @SerialName("reply_to_post_number") val replyToPostNumber: Int? = null,
    @SerialName("reply_count") val replyCount: Int = 0,
    @SerialName("user_id") val userId: Long? = null,
    val polls: List<DiscoursePoll> = emptyList(),
    @SerialName("polls_votes") val pollsVotes: Map<String, List<String>> = emptyMap(),
    val boosts: List<Boost> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    @SerialName("current_user_reaction") val currentUserReaction: Reaction? = null,
    val yours: Boolean = false,
    @SerialName("read") val read: Boolean = false,
    @SerialName("can_boost") val canBoost: Boolean = false,
    @SerialName("can_accept_answer") val canAcceptAnswer: Boolean = false,
    @SerialName("can_unaccept_answer") val canUnacceptAnswer: Boolean = false,
    @SerialName("accepted_answer") val acceptedAnswer: Boolean = false,
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("can_delete") val canDelete: Boolean = false,
    @SerialName("bookmarked") val bookmarked: Boolean = false,
    @SerialName("bookmark_id") val bookmarkId: Long? = null,
    /** post_stream 里的楼层不带 raw,编辑前要单独取一次。 */
    val raw: String? = null,
) {
    /** 优先展示论坛昵称（如“用户已注销”），无昵称则回退到用户名（如“NullUser”），对齐 fluxdo */
    val displayName: String
        get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: username
}

@Serializable
data class DiscoursePollOption(
    val id: String,
    val html: String = "",
    val votes: Int = 0,
)

@Serializable
data class DiscoursePoll(
    val id: Long = 0L,
    val name: String,
    val type: String = "regular",
    val status: String = "open",
    val results: String = "always",
    val options: List<DiscoursePollOption> = emptyList(),
    val voters: Int = 0,
    @SerialName("chart_type") val chartType: String? = null,
    val title: String? = null,
)

/**
 * Discourse / LINUX DO 信任等级称号映射 (TL0 ~ TL4)
 */
fun trustLevelTitle(level: Int?): String = when (level) {
    0 -> "访问者"
    1 -> "基本用户"
    2 -> "活跃用户"
    3 -> "领袖"
    4 -> "领导者"
    else -> "活跃用户"
}

@Serializable
data class UserCardData(
    val id: Long = 0L,
    val username: String,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("profile_background_upload_url") val profileBackgroundUploadUrl: String? = null,
    @SerialName("card_background_upload_url") val cardBackgroundUploadUrl: String? = null,
    @SerialName("trust_level") val trustLevel: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("bio_excerpt") val bioExcerpt: String? = null,
    @SerialName("post_count") val postCount: Int = 0,
    @SerialName("likes_received") val likesReceived: Int = 0,
    @SerialName("topic_count") val topicCount: Int = 0,
    @SerialName("topic_post_count") val rawTopicPostCount: JsonElement? = null,
    @SerialName("can_follow") val canFollow: Boolean = false,
    val following: Boolean = false,
    val badges: List<SummaryBadgeItem> = emptyList(),
) {
    /** 与 FluxDO 一致：优先个人主页背景，其次资料卡背景。 */
    val backgroundUrl: String?
        get() {
            val path = profileBackgroundUploadUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: cardBackgroundUploadUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            return when {
                path.startsWith("//") -> "https:$path"
                path.startsWith("https://", ignoreCase = true) || path.startsWith("http://", ignoreCase = true) -> path
                else -> Config.BASE_URL + "/" + path.trimStart('/')
            }
        }

    val topicPostCount: Int?
        get() = when (val elem = rawTopicPostCount) {
            is JsonPrimitive -> elem.intOrNull
            is JsonObject -> elem.values.firstOrNull()?.jsonPrimitive?.intOrNull
            else -> null
        }

    val cleanDisplayName: String
        get() {
            val raw = name?.trim()
            val valid = raw?.filterNot {
                it.isWhitespace() ||
                    it in '\u2000'..'\u200F' ||
                    it in '\u2028'..'\u202F' ||
                    it in '\uFE00'..'\uFE0F' ||
                    it == '\uFEFF' ||
                    it == '\u00A0'
            }
            return if (!valid.isNullOrBlank()) raw else username
        }
}

@Serializable
data class FollowUser(
    val username: String,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("trust_level") val trustLevel: Int? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class BlockedUser(
    val username: String,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("trust_level") val trustLevel: Int? = null,
    @SerialName("blocked_at") val blockedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatChannelSummary(
    val id: Long,
    val title: String? = null,
    @SerialName("chatable_type") val chatableType: String = "Category",
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_message") val lastMessage: ChatMessageSummary? = null,
    @SerialName("current_user_membership") val membership: ChatMembership? = null,
    val dmUsers: List<ChatUserSummary> = emptyList(),
) {
    val isDirectMessage: Boolean get() = chatableType == "DirectMessage"
    val isOneOnOneDm: Boolean get() = isDirectMessage && dmUsers.size == 1
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() }
        ?: if (isDirectMessage) (dmUsers.firstOrNull()?.let { it.name ?: it.username } ?: "私聊")
        else (categoryName ?: "聊天频道")
}

@Serializable
data class ChatMembership(
    val following: Boolean = false,
    val muted: Boolean = false,
    @SerialName("last_read_message_id") val lastReadMessageId: Long? = null,
)

@Serializable
data class ChatMessageSummary(
    val id: Long,
    @SerialName("channel_id") val channelId: Long = 0L,
    val message: String = "",
    val cooked: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val user: ChatUserSummary? = null,
)

@Serializable
data class ChatUserSummary(
    val username: String = "",
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
)

@Serializable
data class ChatMessagesPage(
    val messages: List<ChatMessageSummary> = emptyList(),
    val canLoadMorePast: Boolean = false,
    val canLoadMoreFuture: Boolean = false,
)

enum class PostFlagReason(val id: Int, val title: String, val description: String) {
    Spam(8, "垃圾信息", "广告、推销或垃圾信息"),
    Inappropriate(4, "不当内容", "冒犯性、攻击性或违规内容"),
    OffTopic(3, "跑题内容", "与本话题讨论主题无关"),
    NotifyModerators(7, "其他问题", "需要版主协助处理，请补充说明");

    val requiresMessage: Boolean get() = this == NotifyModerators
}

@Serializable
data class Boost(
    val id: Long,
    val cooked: String = "",
    val user: DiscourseUser? = null,
    @SerialName("can_delete") val canDelete: Boolean = false,
    @SerialName("can_flag") val canFlag: Boolean = false,
    @SerialName("user_flag_status") val userFlagStatus: Int? = null,
    @SerialName("available_flags") val availableFlags: List<String> = emptyList(),
)

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
    val id: Long? = null,
    val shortUrl: String,
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
    val url: String? = null,
) {
    /** 拼成 Discourse 正文里的 Markdown。图片带尺寸,其余走 attachment 语法。 */
    fun toMarkdown(): String =
        if (width > 0 && height > 0) "![$fileName|${width}x$height]($shortUrl)"
        else "[$fileName|attachment]($shortUrl)"
}

/**
 * 待提交或已上传的附件媒体信息。
 * 用户在发帖/评论时先原样预览缩略图与卡片，确认无误并在提交/保存草稿时自动转化为 Markdown 编码。
 */
data class AttachedMedia(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val previewUrl: String,
    val uploaded: UploadedFile,
    val isImage: Boolean = true,
) {
    val uploadId: Long? get() = uploaded.id
    fun toMarkdown(): String = uploaded.toMarkdown()
}

@Serializable
data class Reaction(val id: String, val count: Int = 0, @SerialName("can_undo") val canUndo: Boolean = false)

const val DEFAULT_SUB_REPLY_LIMIT = 3

data class TreePost(
    val post: Post,
    val depth: Int,
    val childCount: Int,
    val parentMissing: Boolean,
    val replyToUsername: String? = null,
    val rootPostId: Long = post.id,
    val isFirstInSubtree: Boolean = false,
    val isLastInSubtree: Boolean = false,
    val rootChildCount: Int = childCount,
    val rootReplyCount: Int = post.replyCount,
    val subtreeSize: Int = childCount,
    val allLoadedCount: Int = subtreeSize,
    val totalSubtreeCount: Int = maxOf(childCount, post.replyCount),
    val unloadedCandidateIds: List<Long> = emptyList(),
)

private val ASIDE_TAG = Regex("""<aside\b[^>]*>""", RegexOption.IGNORE_CASE)
private val DATA_POST_ATTR = Regex("""\bdata-post\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)
private val DATA_TOPIC_ATTR = Regex("""\bdata-topic\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)

/**
 * 优先取服务端显式 reply_to_post_number；若为空（例如通过引用 `[quote="..., post:13"]` 回复），
 * 则从 cooked 首个同话题 quote 块提取 data-post 作为父楼层号，使客户端树形挂载与服务端 reply_count 口径对齐。
 */
fun Post.effectiveReplyToPostNumber(includeTopicPost: Boolean = false): Int? {
    val minimum = if (includeTopicPost) 1 else 2
    replyToPostNumber?.takeIf { it >= minimum && it < postNumber }?.let { return it }
    if (!cooked.contains("quote", ignoreCase = true)) return null
    val aside = ASIDE_TAG.find(cooked)?.value ?: return null
    val quotedTopic = DATA_TOPIC_ATTR.find(aside)?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (quotedTopic != null && topicId > 0L && quotedTopic != topicId) return null
    return DATA_POST_ATTR.find(aside)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it >= minimum && it < postNumber }
}

fun flatPostRows(posts: List<Post>, descending: Boolean = false): List<TreePost> {
    val sorted = posts.distinctBy { it.id }.sortedBy { it.postNumber }
    val byNumber = sorted.associateBy { it.postNumber }
    val comments = sorted.filterNot { it.postNumber == 1 }.let { if (descending) it.asReversed() else it }
    return (sorted.filter { it.postNumber == 1 } + comments).map { post ->
        val parentNumber = post.effectiveReplyToPostNumber(includeTopicPost = true)
        val parent = byNumber[parentNumber]
        TreePost(post, 0, 0, parentNumber != null && parent == null, replyToUsername = parent?.username)
    }
}

/**
 * 缺失父楼层时保留为根节点,避免分页时漏掉可见回复。1 楼为主贴,不挂载楼中楼子回复。
 *
 * [descending] 只翻转一级主评论的先后,1 楼永远在首位,楼中楼子回复仍跟着自己的主评论
 * 并保持对话的时间正序 —— 把一段对话倒过来读是没有意义的。
 */
fun threadRows(
    posts: List<Post>,
    collapsed: Set<Long> = emptySet(),
    expanded: Set<Long> = emptySet(),
    previewLimit: Int = DEFAULT_SUB_REPLY_LIMIT,
    descending: Boolean = false,
): List<TreePost> {
    val ordered = posts.distinctBy { it.id }.sortedBy { it.postNumber }
    val byNumber = ordered.associateBy { it.postNumber }
    val children = ordered.groupBy { post ->
        val parent = post.effectiveReplyToPostNumber()?.let { byNumber[it] }
        if (parent != null && parent.postNumber > 1 && parent.postNumber < post.postNumber) parent.id else null
    }
    val result = mutableListOf<TreePost>()
    data class SubNode(val post: Post, val depth: Int, val parentUsername: String?)

    val allRoots = children[null].orEmpty()
    val opRoot = allRoots.filter { it.postNumber == 1 }
    val commentRoots = allRoots.filterNot { it.postNumber == 1 }
        .let { roots -> if (descending) roots.sortedByDescending { it.postNumber } else roots }

    for (root in opRoot + commentRoots) {
        val rootDirectReplies = children[root.id].orEmpty()
        // 先无视折叠/预览状态统计整棵子树的全部已加载后代及服务端未加载差额，确保预览和收起后条数统计始终准确。
        val allDescendants = mutableListOf<SubNode>()
        if (rootDirectReplies.isNotEmpty()) {
            val fullStack = java.util.ArrayDeque<SubNode>()
            rootDirectReplies.asReversed().forEach { fullStack.addLast(SubNode(it, 1, null)) }
            while (fullStack.isNotEmpty()) {
                val current = fullStack.removeLast()
                allDescendants += current
                children[current.post.id].orEmpty().asReversed().forEach { child ->
                    fullStack.addLast(SubNode(child, current.depth + 1, current.post.username))
                }
            }
        }
        val isFirstPost = root.postNumber == 1
        val effectiveRootReplyCount = if (isFirstPost) 0 else root.replyCount
        val totalSubtreeCount = if (isFirstPost) {
            0
        } else {
            maxOf(rootDirectReplies.size, effectiveRootReplyCount) +
                allDescendants.sumOf { node ->
                    maxOf(children[node.post.id].orEmpty().size, node.post.replyCount)
                }
        }
        val unloadedCandidateIds = if (isFirstPost) {
            emptyList()
        } else {
            buildList {
                if (effectiveRootReplyCount > rootDirectReplies.size) add(root.id)
                allDescendants.forEach { node ->
                    if (node.post.replyCount > children[node.post.id].orEmpty().size) {
                        add(node.post.id)
                    }
                }
            }
        }

        val uncollapsedDescendants = mutableListOf<SubNode>()
        if (root.id !in collapsed && rootDirectReplies.isNotEmpty()) {
            val stack = java.util.ArrayDeque<SubNode>()
            rootDirectReplies.asReversed().forEach { stack.addLast(SubNode(it, 1, null)) }
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                uncollapsedDescendants += current
                if (current.post.id !in collapsed) {
                    children[current.post.id].orEmpty().asReversed().forEach { child ->
                        stack.addLast(SubNode(child, current.depth + 1, current.post.username))
                    }
                }
            }
        }
        val descendants = if (root.id !in expanded && previewLimit > 0) {
            uncollapsedDescendants.take(previewLimit)
        } else {
            uncollapsedDescendants
        }
        result += TreePost(
            post = root,
            depth = 0,
            childCount = rootDirectReplies.size,
            parentMissing = root.replyToPostNumber?.let { it > 1 && it !in byNumber } == true,
            rootPostId = root.id,
            rootChildCount = rootDirectReplies.size,
            rootReplyCount = effectiveRootReplyCount,
            subtreeSize = descendants.size,
            allLoadedCount = allDescendants.size,
            totalSubtreeCount = totalSubtreeCount,
            unloadedCandidateIds = unloadedCandidateIds,
        )
        descendants.forEachIndexed { idx, node ->
            val replies = children[node.post.id].orEmpty()
            result += TreePost(
                post = node.post,
                depth = node.depth,
                childCount = replies.size,
                parentMissing = node.post.replyToPostNumber?.let { it > 1 && it !in byNumber } == true,
                replyToUsername = node.parentUsername,
                rootPostId = root.id,
                isFirstInSubtree = idx == 0,
                isLastInSubtree = idx == descendants.lastIndex,
                rootChildCount = rootDirectReplies.size,
                rootReplyCount = root.replyCount,
                subtreeSize = descendants.size,
                allLoadedCount = allDescendants.size,
                totalSubtreeCount = totalSubtreeCount,
                unloadedCandidateIds = unloadedCandidateIds,
            )
        }
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
    @SerialName("notification_level") val notificationLevel: Int = 1,
)

@Serializable
data class TopicDetail(
    val id: Long,
    val title: String,
    @SerialName("posts_count") val postsCount: Int = 0,
    @SerialName("last_read_post_number") val lastReadPostNumber: Int? = null,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("post_stream") val postStream: PostStream = PostStream(),
    @SerialName("valid_reactions") val validReactions: List<String> = emptyList(),
    val closed: Boolean = false,
    val archived: Boolean = false,
    val details: TopicDetails = TopicDetails(),
    @SerialName("message_bus_last_id") val messageBusLastId: Long? = null,
    @SerialName("highest_post_number") val highestPostNumber: Int? = null,
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
        Replies -> type in setOf(1, 2, 3, 15, 33, 43)
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
        Replies -> "mentioned,replied,quoted,group_mentioned,chat_quoted,boost"
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
    /**
     * 是否计入铃铛与桌面角标。
     *
     * 口径跟随用户在通知设置里选的作用域(全部 / 仅回复与互动类),
     * 与推送下发共用 NotificationScope.includes —— 后台弹过的,前台必须认账。
     */
    fun countsAsUnread(scope: NotificationScope): Boolean = !read && scope.includes(type)
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
     * 总 count 走的是铃铛/角标口径 —— 跟随通知设置的作用域(见 NotificationScope.includes),
     * 「仅回复与互动类」下点赞不计入。所以判断"某个分类有没有新东西"必须看这里的分项值,
     * 用 count 会让点赞分类永远判定为没变化。
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
 * 全局用户头像与动图缓存：
 * Discourse 服务端的 /u/{username}/card.json（UserCardSerializer）不带 animated_avatar 字段，
 * 只有 basic_user 和 post 返回动图。
 * 因此客户端全局缓存所感知到的所有 animated_avatar，在打开个人资料卡与个人主页时自动回填，
 * 保证话题列表到个人信息页面的头像始终保持无限循环高刷动图播放。
 */
fun isAnimatedAvatarPath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val clean = path.substringBefore('?').substringBefore('#').trim().lowercase()
    return clean.endsWith(".gif") || clean.endsWith(".webp") || clean.contains("animated")
}

object AvatarCache {
    private const val MAX_ENTRIES = 512
    /** 标记值：已探测确认无动图头像，避免重复发起网络请求。 */
    private const val NON_ANIMATED_SENTINEL = "__non_animated__"
    private val lock = Any()
    private val cache = java.util.LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true)

    private fun keyOf(username: String?): String? = username
        ?.trim()
        ?.removePrefix("@")
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    private fun trimLocked() {
        while (cache.size > MAX_ENTRIES) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    fun record(username: String?, animatedAvatar: String?) {
        val key = keyOf(username) ?: return
        val url = animatedAvatar?.trim()?.takeIf { it.isNotEmpty() && isAnimatedAvatarPath(it) } ?: return
        synchronized(lock) {
            cache[key] = url
            trimLocked()
        }
    }

    /** 列表只提供已解析地址，主地址与静态回退地址不同时才记录为动图候选。 */
    fun recordIfDistinct(username: String?, avatarUrl: String?, staticAvatarUrl: String?) {
        val primary = avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (primary == staticAvatarUrl?.trim()) return
        record(username, primary)
    }

    /**
     * 写入"已确认无动图"负缓存，避免后续重复探测同一用户。
     * 若该用户已有正向动图记录，则忽略此调用。
     */
    fun recordNonAnimated(username: String?) {
        val key = keyOf(username) ?: return
        synchronized(lock) {
            if (cache[key]?.let { it != NON_ANIMATED_SENTINEL && isAnimatedAvatarPath(it) } == true) return
            cache[key] = NON_ANIMATED_SENTINEL
            trimLocked()
        }
    }

    /** 返回是否已确认该用户无动图（负缓存命中）。 */
    fun isNonAnimated(username: String?): Boolean {
        val key = keyOf(username) ?: return false
        return synchronized(lock) { cache[key] == NON_ANIMATED_SENTINEL }
    }

    fun get(username: String?): String? {
        val key = keyOf(username) ?: return null
        val value = synchronized(lock) { cache[key] }
        return if (value == NON_ANIMATED_SENTINEL) null else value
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }
}

object ExcerptCache {
    private const val MAX_ENTRIES = 500
    private val excerptMap = androidx.compose.runtime.mutableStateMapOf<Long, String>()
    private val coverMap = androidx.compose.runtime.mutableStateMapOf<Long, String>()
    private var opStore: TopicOpStore? = null

    fun initialize(store: TopicOpStore) {
        opStore = store
    }

    fun record(topicId: Long, plainText: String?, coverImageUrl: String? = null) {
        if (topicId <= 0L) return
        val text = plainText?.trim()?.takeIf { it.isNotEmpty() }
        val image = coverImageUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (text == null && image == null) return

        if (text != null) {
            if (excerptMap.size > MAX_ENTRIES) {
                val firstKey = excerptMap.keys.firstOrNull()
                if (firstKey != null) excerptMap.remove(firstKey)
            }
            excerptMap[topicId] = text
        }
        if (image != null) {
            if (coverMap.size > MAX_ENTRIES) {
                val firstKey = coverMap.keys.firstOrNull()
                if (firstKey != null) coverMap.remove(firstKey)
            }
            coverMap[topicId] = image
        }
        opStore?.put(topicId, text ?: excerptMap[topicId], image ?: coverMap[topicId])
    }

    fun get(topicId: Long): String? {
        if (topicId <= 0L) return null
        excerptMap[topicId]?.let { return it }
        val record = opStore?.get(topicId)
        if (record != null) {
            record.excerpt?.let { excerptMap[topicId] = it }
            record.coverImageUrl?.let { coverMap[topicId] = it }
            return record.excerpt
        }
        return null
    }

    /** 只读内存态、不碰本地库；在组合中读取可订阅后续 [record] 写入，适合每次重组都调用。 */
    fun peekCoverImage(topicId: Long): String? = coverMap[topicId]

    fun getCoverImage(topicId: Long): String? {
        if (topicId <= 0L) return null
        coverMap[topicId]?.let { return it }
        val record = opStore?.get(topicId)
        if (record != null) {
            record.excerpt?.let { excerptMap[topicId] = it }
            record.coverImageUrl?.let { coverMap[topicId] = it }
            return record.coverImageUrl
        }
        return null
    }

    /**
     * 批量从本地持久库载入内存，用于列表刷新后 0ms 瞬间把历史正文和首图直接填满内存 Compose State。
     */
    fun loadBatchFromStore(topicIds: Collection<Long>) {
        val store = opStore ?: return
        val records = store.getAll(topicIds)
        records.forEach { (id, record) ->
            if (record.excerpt != null && !excerptMap.containsKey(id)) {
                excerptMap[id] = record.excerpt
            }
            if (record.coverImageUrl != null && !coverMap.containsKey(id)) {
                coverMap[id] = record.coverImageUrl
            }
        }
    }

    fun clear() {
        excerptMap.clear()
        coverMap.clear()
    }
}

/**
 * 头像地址解析：优先使用动图 animated_avatar（针对四级用户及管理组），对齐 fluxdo；
 * 静态 avatar_template 形如 `/user_avatar/linux.do/foo/{size}/123_2.png`，
 * 替换 {size} 并补全为绝对地址。默认尺寸使用标准 120。
 */
fun avatarUrlOf(
    template: String?,
    animatedAvatar: String? = null,
    size: Int = 120,
    username: String? = null,
): String? {
    if (!username.isNullOrBlank() && !animatedAvatar.isNullOrBlank()) {
        AvatarCache.record(username, animatedAvatar)
    }
    val candidateAnim = if (!animatedAvatar.isNullOrBlank()) animatedAvatar else AvatarCache.get(username)
    val effectiveAnim = candidateAnim?.takeIf { isAnimatedAvatarPath(it) }
    if (!effectiveAnim.isNullOrBlank()) {
        val safeAnimSize = when {
            size <= 48 -> 48
            size <= 64 -> 64
            size <= 96 -> 96
            size <= 120 -> 120
            else -> 144
        }
        val replaced = effectiveAnim.replace("{size}", safeAnimSize.toString())
        val animPath = if (replaced.startsWith("//")) "https:$replaced" else replaced
        return if (animPath.startsWith("http", ignoreCase = true)) animPath
        else Config.BASE_URL + (if (animPath.startsWith("/")) animPath else "/$animPath")
    }
    if (template.isNullOrBlank()) return null
    val path = template.replace("{size}", size.toString())
    if (path.startsWith("//")) return "https:$path"
    return if (path.startsWith("http", ignoreCase = true)) path
    else Config.BASE_URL + (if (path.startsWith("/")) path else "/$path")
}

fun avatarUrlOf(template: String?, size: Int): String? = avatarUrlOf(template, null, size)
fun avatarUrlOf(template: String?, size: Int, username: String?): String? = avatarUrlOf(template, null, size, username)

data class SummaryTopicItem(
    val id: Long,
    val title: String,
    val likeCount: Int = 0,
    val createdAt: String? = null,
    val categoryId: Long? = null,
)

data class SummaryReplyItem(
    val topicId: Long,
    val postNumber: Int,
    val title: String,
    val likeCount: Int = 0,
    val createdAt: String? = null,
)

data class SummaryLinkItem(
    val url: String,
    val title: String?,
    val clicks: Int = 0,
    val postNumber: Int? = null,
    val topicId: Long? = null,
    val topicTitle: String? = null,
)

data class SummaryUserItem(
    val id: Long,
    val username: String,
    val avatarTemplate: String?,
    val count: Int = 0,
)

data class SummaryCategoryItem(
    val id: Long,
    val name: String,
    val color: String?,
    val topicCount: Int = 0,
    val postCount: Int = 0,
)

@Serializable
data class SummaryBadgeItem(
    val id: Long,
    val name: String,
    val description: String?,
    val icon: String?,
    val badgeTypeId: Int = 0,
)

@Serializable
data class UserBadgeItem(
    val id: Long,
    val badgeId: Long,
    val name: String,
    val description: String?,
    val icon: String?,
    val imageUrl: String?,
    val badgeTypeId: Int, // 1: Gold, 2: Silver, 3: Bronze
    val badgeGroupingId: Int = 0,
    val grantCount: Int = 0,
    val grantedAt: String? = null,
    val count: Int = 1,
    val isEarned: Boolean = true,
)

data class UserSummaryData(
    val daysVisited: Int = 0,
    val postsReadCount: Int = 0,
    val likesReceived: Int = 0,
    val likesGiven: Int = 0,
    val topicCount: Int = 0,
    val postCount: Int = 0,
    val timeRead: Int = 0,
    val bookmarkCount: Int = 0,
    val topicsEntered: Int = 0,
    val topTopics: List<SummaryTopicItem> = emptyList(),
    val topReplies: List<SummaryReplyItem> = emptyList(),
    val topLinks: List<SummaryLinkItem> = emptyList(),
    val mostRepliedToUsers: List<SummaryUserItem> = emptyList(),
    val mostLikedByUsers: List<SummaryUserItem> = emptyList(),
    val mostLikedUsers: List<SummaryUserItem> = emptyList(),
    val topCategories: List<SummaryCategoryItem> = emptyList(),
    val badges: List<SummaryBadgeItem> = emptyList(),
)

@Serializable
data class LdcUserInfo(
    val id: Long = 0L,
    val username: String = "",
    val nickname: String = "",
    @SerialName("trust_level") val trustLevel: Int = 0,
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("total_receive") val totalReceive: String = "0",
    @SerialName("total_payment") val totalPayment: String = "0",
    @SerialName("total_transfer") val totalTransfer: String = "0",
    @SerialName("total_community") val totalCommunity: String = "0",
    @SerialName("community_balance") val communityBalance: String = "0",
    @SerialName("available_balance") val availableBalance: String = "0",
    @SerialName("pay_score") val payScore: Int = 0,
    @SerialName("is_pay_key") val isPayKey: Boolean = false,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("remain_quota") val remainQuota: String = "0",
    @SerialName("pay_level") val payLevel: Int = 0,
    @SerialName("daily_limit") val dailyLimit: Int = 0,
)

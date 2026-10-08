package org.linuxdo.android.data

import android.os.SystemClock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import java.net.URI
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.linuxdo.android.Config
import org.linuxdo.android.net.HttpStatusException
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.Transport

@Serializable
private data class TopicListPayload(
    val topics: List<Topic> = emptyList(),
    @SerialName("more_topics_url") val moreTopicsUrl: String? = null,
)

@Serializable
private data class TopicListResponse(
    @SerialName("topic_list") val topicList: TopicListPayload,
    /** 作者信息不在 topic 里,要按 posters[].user_id 和这个数组 join。 */
    val users: List<DiscourseUser> = emptyList(),
)

@Serializable
private data class CategoryListPayload(val categories: List<Category> = emptyList())

@Serializable
private data class CategoryListResponse(
    @SerialName("category_list") val categoryList: CategoryListPayload,
)

@Serializable
private data class PostStreamResponse(
    @SerialName("post_stream") val postStream: PostStream,
)

@Serializable
private data class SearchResults(
    @SerialName("more_full_page_results") val more: Boolean = false,
)

@Serializable
private data class SearchResponse(
    val topics: List<Topic> = emptyList(),
    val posts: List<Post> = emptyList(),
    @SerialName("grouped_search_result") val grouped: SearchResults = SearchResults(),
)

class TopicRepository(
    private val transportProvider: () -> Transport,
    private val diagnostics: Diagnostics,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val gate = Mutex()
    private var lastRequestAt = 0L

    /** 分类表全站共用且很少变,整个进程只拉一次。 */
    private var categoryCache: Map<Int, Category>? = null
    private val categoryGate = Mutex()
    private var categoryListCache: List<Category>? = null
    private var emojiCache: Map<String, String>? = null

    suspend fun emojiUrls(): Map<String, String> {
        emojiCache?.let { return it }
        val result = mutableMapOf<String, String>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::visit)
                is JsonObject -> {
                    val name = element.text("name").ifBlank { element.text("id") }
                    val url = element.text("url")
                    if (name.isNotBlank() && url.isNotBlank()) {
                        runCatching { URI(Config.BASE_URL + "/").resolve(url).toASCIIString() }.getOrNull()?.let { result[name] = it }
                    }
                    element.values.forEach(::visit)
                }
                else -> Unit
            }
        }
        try { visit(json.parseToJsonElement(request("/emojis.json"))) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { diagnostics.log("表情索引加载失败: ${error.message}") }
        return result.toMap().also { emojiCache = it }
    }

    suspend fun fetchLatest(page: Int): TopicPage =
        toPage(json.decodeFromString(request("/latest.json?page=$page")))

    suspend fun fetchCategoryTopics(slug: String, categoryId: Int, page: Int): TopicPage =
        toPage(json.decodeFromString(request("/c/${encode(slug.ifBlank { "category" })}/$categoryId.json?page=$page")))

    suspend fun fetchTopic(id: Long, postNumber: Int? = null): TopicDetail {
        val position = postNumber?.takeIf { it > 1 }?.let { "/$it" }.orEmpty()
        val body = request("/t/$id$position.json?track_visit=false")
        return json.decodeFromString(body)
    }

    suspend fun fetchPosts(topicId: Long, ids: List<Long>): List<Post> {
        require(ids.size in 1..20)
        val query = ids.joinToString("&") { "post_ids%5B%5D=$it" }
        return json.decodeFromString<PostStreamResponse>(
            request("/t/$topicId/posts.json?$query&track_visit=false"),
        ).postStream.posts
    }

    suspend fun categoryById(id: Int?): Category? = id?.let { categories()[it] }

    suspend fun fetchReplies(postId: Long, after: Int): List<Post> =
        json.decodeFromString(request("/posts/$postId/replies.json?after=$after"))

    suspend fun toggleReaction(postId: Long, reaction: String): Post {
        require(reaction.matches(Regex("[a-zA-Z0-9_+:-]+")))
        return json.decodeFromString(mutate("/discourse-reactions/posts/$postId/custom-reactions/${encode(reaction)}/toggle.json", "PUT"))
    }

    suspend fun createBoost(postId: Long, raw: String): Boost {
        require(raw.isNotBlank() && raw.length <= 1000)
        val body = JsonObject(mapOf("raw" to JsonPrimitive(raw.trim()))).toString()
        return json.decodeFromString(mutate("/discourse-boosts/posts/$postId/boosts.json", "POST", body))
    }

    suspend fun deleteBoost(boostId: Long) { mutate("/discourse-boosts/boosts/$boostId.json", "DELETE") }

    suspend fun unreadCount(): Int {
        val seen = mutableSetOf<Long>()
        val limit = 60
        var offset = 0
        var count = 0
        while (true) {
            val page = json.decodeFromString<UnreadNotificationPage>(
                request("/notifications.json?filter=unread&limit=$limit&offset=$offset"),
            )
            val fresh = page.notifications.filter { seen.add(it.id) }
            check(page.notifications.isEmpty() || fresh.isNotEmpty()) { "未读通知分页未推进" }
            count += fresh.count { it.countsAsUnread }
            if (!page.hasMore(offset, limit)) return count
            offset += limit
        }
    }

    suspend fun markNotificationRead(id: Long) {
        mutate("/notifications/mark-read.json", "PUT", JsonObject(mapOf("id" to JsonPrimitive(id))).toString())
    }

    suspend fun setSolution(postId: Long, accepted: Boolean) {
        mutate("/solution/${if (accepted) "accept" else "unaccept"}.json", "POST",
            JsonObject(mapOf("id" to JsonPrimitive(postId))).toString())
    }

    private suspend fun mutate(path: String, method: String, body: String? = null): String {
        val csrf = objectRequest("/session/csrf.json").text("csrf").also { require(it.isNotBlank()) }
        return gate.withLock {
            throttle()
            val transport = transportProvider()
            val result = try { transport.write(path, method, body, csrf) } finally { lastRequestAt = SystemClock.elapsedRealtime() }
            diagnostics.recordStatus(transport.name, result.status, path)
            if (result.challenged) throw NeedsInteractiveVerification("回应需要重新验证")
            if (result.status !in 200..299) {
                val serverMessage = runCatching {
                    (json.parseToJsonElement(result.body).jsonObject["errors"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n")?.take(400)
                }.getOrNull()
                val message = if (result.status == 0 || result.status >= 500) "结果未确认，请刷新楼层后检查回应状态"
                    else serverMessage?.takeIf { it.isNotBlank() } ?: "回应未提交，请稍后重试"
                throw HttpStatusException(result.status, message)
            }
            result.body
        }
    }

    suspend fun search(query: String, page: Int, order: SearchOrder = SearchOrder.Relevance): SearchPage {
        val orderedQuery = if (order.key.isEmpty()) query else
            query.replace(Regex("(?:^|\\s)order:\\S+"), " ").trim() + " order:${order.key}"
        val response = json.decodeFromString<SearchResponse>(
            request("/search.json?q=${encode(orderedQuery)}&page=$page"),
        )
        val known = categories()
        val posts = response.posts.groupBy { it.topicId }
        return SearchPage(
            items = response.topics.map { topic ->
                val author = posts[topic.id]?.firstOrNull()
                TopicListItem(topic, author?.username, avatarUrlOf(author?.avatarTemplate), known[topic.categoryId])
            },
            excerpts = posts.mapValues { (_, matches) -> matches.first().blurb },
            hasMore = response.grouped.more && response.topics.isNotEmpty(),
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    suspend fun fetchProfile(username: String, section: ProfileSection, page: Int): ProfilePage {
        val userPath = encode(username)
        return when (section) {
            ProfileSection.Summary -> {
                val root = objectRequest("/u/$userPath/summary.json")
                val summary = root["user_summary"] as? JsonObject ?: JsonObject(emptyMap())
                val metrics = listOf("days_visited" to "访问天数", "time_read" to "阅读时长",
                    "topics_entered" to "浏览话题", "posts_read_count" to "已读帖子",
                    "likes_given" to "送出赞", "likes_received" to "收到赞",
                    "topic_count" to "发表话题", "post_count" to "回复数量")
                ProfilePage(fields = metrics.map { (key, label) ->
                    val value = summary.text(key)
                    label to if (key == "time_read" && value.toLongOrNull() != null) "${value.toLong() / 60} 分钟" else value.ifBlank { "—" }
                })
            }
            ProfileSection.Account -> {
                val user = objectRequest("/u/$userPath.json")["user"] as? JsonObject ?: error("账号信息格式不正确")
                val emails = objectRequest("/u/$userPath/emails.json")
                ProfilePage(fields = listOf(
                    "用户名" to user.text("username").ifBlank { username },
                    "显示名称" to user.text("name").ifBlank { "未设置" },
                    "信任等级" to user.text("trust_level").let { if (it.isBlank()) "—" else "Lv$it" },
                    "注册日期" to user.text("created_at").take(10).ifBlank { "—" },
                    "个人简介" to Jsoup.parseBodyFragment(user.text("bio_raw").ifBlank { user.text("bio_cooked") }).text().ifBlank { "未设置" },
                    "主邮箱" to emails.text("email").ifBlank { user.text("email") }.ifBlank { "服务端未返回" },
                    "备用邮箱" to (emails["secondary_emails"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n").ifBlank { "未设置" },
                ))
            }
            ProfileSection.Activity -> {
                val actions = objectRequest("/user_actions.json?username=$userPath&offset=${page * 30}&limit=30").objects("user_actions")
                ProfilePage(entries = actions.mapIndexed { index, action ->
                    ProfileEntry("${page * 30 + index}:${action.text("post_id")}",
                        action.text("title").ifBlank { "社区活动" },
                        listOf(activityLabel(action.text("action_type")), Jsoup.parseBodyFragment(action.text("excerpt")).text(),
                            action.text("created_at").take(10)).filter { it.isNotBlank() }.joinToString(" · "),
                        action.number("topic_id"))
                }, hasMore = actions.size >= 30)
            }
            ProfileSection.Notifications -> {
                val notices = objectRequest("/notifications.json?offset=${page * 30}&limit=30").objects("notifications")
                ProfilePage(entries = notices.map { notice ->
                    val data = notice["data"] as? JsonObject ?: JsonObject(emptyMap())
                    ProfileEntry(notice.text("id"),
                        Jsoup.parseBodyFragment(notice.text("fancy_title").ifBlank { data.text("fancy_title") }.ifBlank { data.text("topic_title") }).text()
                            .ifBlank { notificationLabel(notice.text("notification_type")) },
                        listOf(notificationLabel(notice.text("notification_type")), data.text("display_username"),
                            notice.text("created_at").take(10)).filter { it.isNotBlank() }.joinToString(" · "),
                        notice.number("topic_id"), unread = notice.text("read") == "false",
                        notificationType = notice.text("notification_type").toIntOrNull() ?: 0,
                        avatarUrl = avatarUrlOf(notice.text("acting_user_avatar_template")),
                        actor = data.text("display_username").ifBlank { notice.text("acting_user_name") }.ifBlank { null },
                        postNumber = notice.text("post_number").toIntOrNull()?.takeIf { it > 0 })
                }, hasMore = notices.size >= 30)
            }
            ProfileSection.Messages -> {
                val result = toPage(json.decodeFromString(request("/topics/private-messages/$userPath.json?page=$page")))
                ProfilePage(entries = result.items.map { item -> ProfileEntry(item.topic.id.toString(), item.topic.title,
                    "${item.topic.replyCount} 回复 · ${item.topic.lastPostedAt?.take(10).orEmpty()}", item.topic.id) }, hasMore = result.hasMore)
            }
        }
    }

    private suspend fun objectRequest(path: String): JsonObject = json.parseToJsonElement(request(path)).jsonObject
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun activityLabel(type: String): String = when (type) {
        "1" -> "点赞"; "2" -> "收到赞"; "4" -> "发表话题"; "5" -> "回复"; "6" -> "收到回复"
        "7" -> "被提及"; "9" -> "引用"; "11", "12" -> "消息"; else -> "活动"
    }

    private fun notificationLabel(type: String): String = when (type) {
        "1" -> "提及"; "2" -> "回复"; "3" -> "引用"; "5", "19" -> "收到赞"
        "6", "7", "16" -> "私信"; "9" -> "发布话题"; "10" -> "移动话题"; "12" -> "获赠徽章"; "15" -> "提及"
        "25" -> "表情回应"; "43" -> "简评"; "800" -> "关注"; "801" -> "关注的人发布话题"; "802" -> "关注的人回复"; else -> "社区通知"
    }

    suspend fun fetchCategories(): List<Category> = categoryGate.withLock {
        categoryListCache ?: json.decodeFromString<CategoryListResponse>(
            request("/categories.json?include_subcategories=true"),
        ).categoryList.categories.also { categoryListCache = it }
    }

    /** 返回当前登录用户名;未登录(Discourse 返回 404)时为 null。这才是"登录态是否有效"的真实判断。 */
    suspend fun fetchCurrentUser(): String? {
        val body = try {
            request("/session/current.json")
        } catch (error: HttpStatusException) {
            if (error.status == 404) return null
            throw error
        }
        return json.parseToJsonElement(body).jsonObject["current_user"]
            ?.jsonObject?.get("username")?.jsonPrimitive?.content
    }

    /**
     * 列表行要显示真实分类名与颜色,所以第一次取列表时顺带把分类表拉下来。
     * 分类加载失败不应让列表失败 —— 退化为不显示分类标签。
     */
    private suspend fun categories(): Map<Int, Category> {
        categoryCache?.let { return it }
        return try {
            flattenCategories(fetchCategories()).associateBy { it.id }.also { categoryCache = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: NeedsInteractiveVerification) {
            throw error
        } catch (error: Exception) {
            diagnostics.log("分类表加载失败,列表将不显示分类: ${error.message}")
            emptyMap()
        }
    }

    private suspend fun toPage(response: TopicListResponse): TopicPage {
        val known = categories()
        val usersById = response.users.associateBy { it.id }
        val items = response.topicList.topics.map { topic ->
            // Discourse 的 posters 顺序固定,第一个是原始发帖人。
            val author = topic.posters.firstOrNull()?.userId?.let { usersById[it] }
            TopicListItem(
                topic = topic,
                authorUsername = author?.username ?: topic.lastPosterUsername,
                avatarUrl = avatarUrlOf(author?.avatarTemplate),
                category = topic.categoryId?.let { known[it] },
            )
        }
        return TopicPage(items = items, hasMore = response.topicList.moreTopicsUrl != null)
    }

    private suspend fun request(path: String): String = gate.withLock {
        var attempt = 0
        while (true) {
            throttle()
            val transport = transportProvider()
            val result = try {
                transport.get(path)
            } finally {
                lastRequestAt = SystemClock.elapsedRealtime()
            }
            diagnostics.recordStatus(transport.name, result.status, path)
            when {
                result.challenged -> throw NeedsInteractiveVerification("请求 $path 仍被 Cloudflare 挑战拦截")
                result.status in 200..299 -> return result.body
                (result.status == 0 || result.status >= 500) && attempt < MAX_RETRIES -> {
                    delay(BACKOFF_BASE_MS shl attempt)
                    attempt++
                }
                else -> throw HttpStatusException(result.status, result.body.take(120))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun throttle() {
        val wait = Config.MIN_REQUEST_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastRequestAt)
        if (wait > 0) delay(wait)
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val BACKOFF_BASE_MS = 1_000L
    }
}

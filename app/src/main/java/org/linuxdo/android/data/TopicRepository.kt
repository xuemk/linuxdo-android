package org.linuxdo.android.data

import android.os.SystemClock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import org.linuxdo.android.net.LoginRejectedException
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.NetworkTimeoutException
import org.linuxdo.android.net.Transport
import org.linuxdo.android.net.TransportResult

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

    suspend fun fetchLatest(page: Int, awaitCategories: Boolean = true): TopicPage =
        toPage(json.decodeFromString(request("/latest.json?page=$page", burst = page == 0)), awaitCategories)

    suspend fun fetchCategoryTopics(slug: String, categoryId: Int, page: Int): TopicPage =
        toPage(json.decodeFromString(request("/c/${encode(slug.ifBlank { "category" })}/$categoryId.json?page=$page", burst = page == 0)), awaitCategories = false)

    suspend fun fetchTopic(id: Long, postNumber: Int? = null, burst: Boolean = false): TopicDetail {
        val position = postNumber?.takeIf { it > 1 }?.let { "/$it" }.orEmpty()
        val body = request("/t/$id$position.json?track_visit=false", burst = burst)
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
        // 和界面同一套规则再校一次,避免绕过输入框的调用路径把超长内容发出去。
        require(measureBoost(raw).submittable) { "简评最多 ${BoostLength.MAX_VISIBLE} 个可见字符、${BoostLength.MAX_EMOJI} 个表情" }
        val body = JsonObject(mapOf("raw" to JsonPrimitive(raw.trim()))).toString()
        return json.decodeFromString(mutate("/discourse-boosts/posts/$postId/boosts.json", "POST", body))
    }

    suspend fun deleteBoost(boostId: Long) { mutate("/discourse-boosts/boosts/$boostId.json", "DELETE") }

    /**
     * 发表回复。replyToPostNumber 为 null 时回复整个话题,否则回复指定楼层。
     * POST /posts.json 返回的是裸 post 对象。
     */
    suspend fun createPost(topicId: Long, raw: String, replyToPostNumber: Int? = null): Post {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "回复内容不能为空" }
        val fields = mutableMapOf<String, JsonElement>(
            "raw" to JsonPrimitive(trimmed),
            "topic_id" to JsonPrimitive(topicId),
        )
        replyToPostNumber?.takeIf { it > 0 }?.let { fields["reply_to_post_number"] = JsonPrimitive(it) }
        return json.decodeFromString(mutate("/posts.json", "POST", JsonObject(fields).toString()))
    }

    /** 编辑楼层。PUT /posts/{id}.json 返回的 post 被包在 `{"post":{...}}` 里,要解一层。 */
    suspend fun updatePost(postId: Long, raw: String): Post {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "回复内容不能为空" }
        val body = JsonObject(mapOf(
            "post" to JsonObject(mapOf("raw" to JsonPrimitive(trimmed))),
        )).toString()
        val response = json.parseToJsonElement(mutate("/posts/$postId.json", "PUT", body)).jsonObject
        val post = response["post"] ?: error("编辑响应缺少 post 字段")
        return json.decodeFromJsonElement(Post.serializer(), post)
    }

    suspend fun deletePost(postId: Long) { mutate("/posts/$postId.json", "DELETE") }

    /**
     * 上传一个文件,返回可直接写进正文 raw 的结果。
     *
     * Discourse 的发帖接口只收纯文本,附件要分两步:先 POST /uploads.json 拿 short_url
     * (形如 `upload://xxx.png`),再把 Markdown 短码拼进 raw。
     * `synchronous=true` 让服务端同步处理完再返回,省掉轮询。
     */
    suspend fun uploadFile(fileName: String, mimeType: String, bytes: ByteArray): UploadedFile {
        UploadLimits.rejectReason(fileName, bytes.size.toLong())?.let { throw LoginRejectedException(it) }
        var revalidated = false
        while (true) {
            val csrf = objectRequest("/session/csrf.json").text("csrf").also { require(it.isNotBlank()) }
            val result = gate.withLock {
                throttle()
                val transport = transportProvider()
                try {
                    transport.upload(
                        "/uploads.json", csrf, fileName, mimeType, bytes,
                        mapOf("type" to "composer", "synchronous" to "true"),
                    )
                } finally { lastRequestAt = SystemClock.elapsedRealtime() }
            }
            diagnostics.recordStatus(transportProvider().name, result.status, "/uploads")
            if (result.challenged) {
                if (!revalidated && transportProvider().revalidate("/uploads.json")) {
                    revalidated = true
                    continue
                }
                throw NeedsInteractiveVerification("上传需要重新验证")
            }
            if (result.status == 0) {
                // 路径 B 的"不支持上传"也走这里,它把原因放在 body 里。
                throw result.body.takeIf { it.isNotBlank() && !it.startsWith("java.") }
                    ?.let { LoginRejectedException(it) } ?: NetworkTimeoutException()
            }
            val payload = runCatching { json.parseToJsonElement(result.body).jsonObject }.getOrNull()
            if (result.status !in 200..299) {
                // 站方的拒绝原因(类型不允许/太大)在 errors 数组里,原样转给用户最有用。
                val serverMessage = (payload?.get("errors") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n")?.take(400)
                throw LoginRejectedException(
                    serverMessage?.takeIf { it.isNotBlank() } ?: "上传失败，请稍后重试",
                )
            }
            val root = payload ?: throw LoginRejectedException("上传响应格式不正确")
            val shortUrl = root.text("short_url").ifBlank { root.text("url") }
            if (shortUrl.isBlank()) throw LoginRejectedException("上传响应缺少文件地址")
            return UploadedFile(
                shortUrl = shortUrl,
                fileName = root.text("original_filename").ifBlank { fileName },
                width = root.text("width").toIntOrNull() ?: 0,
                height = root.text("height").toIntOrNull() ?: 0,
            )
        }
    }

    /** 取楼层原文。post_stream 下发的楼层只有 cooked,编辑前必须单独取一次 raw。 */
    suspend fun fetchPostRaw(postId: Long): String =
        objectRequest("/posts/$postId.json").text("raw")

    suspend fun fetchUnreadSummary(): UnreadSummary {
        val seen = mutableSetOf<Long>()
        val limit = 60
        var offset = 0
        var count = 0
        val unreadByType = mutableMapOf<Int, Int>()
        while (true) {
            val page = json.decodeFromString<UnreadNotificationPage>(
                request("/notifications.json?filter=unread&limit=$limit&offset=$offset"),
            )
            val fresh = page.notifications.filter { seen.add(it.id) }
            check(page.notifications.isEmpty() || fresh.isNotEmpty()) { "未读通知分页未推进" }
            count += fresh.count { it.countsAsUnread }
            fresh.filter { !it.read }.forEach { unreadByType[it.type] = (unreadByType[it.type] ?: 0) + 1 }
            if (!page.hasMore(offset, limit)) break
            offset += limit
        }
        // 按分类汇总。NotificationFilter.All.includes() 对所有类型都成立,所以 All 项
        // 拿到的是"全部类型的未读总数" —— 比铃铛口径的 count 更适合判断全部分类有没有变化。
        val byFilter = NotificationFilter.entries.associateWith { filter ->
            unreadByType.entries.filter { filter.includes(it.key) }.sumOf { it.value }
        }
        return UnreadSummary(
            count = count,
            unreadFilters = byFilter.filterValues { it > 0 }.keys,
            unreadByFilter = byFilter,
        )
    }

    suspend fun unreadCount(): Int = fetchUnreadSummary().count


    suspend fun markNotificationRead(id: Long) {
        mutate("/notifications/mark-read.json", "PUT", JsonObject(mapOf("id" to JsonPrimitive(id))).toString())
    }

    suspend fun setSolution(postId: Long, accepted: Boolean) {
        mutate("/solution/${if (accepted) "accept" else "unaccept"}.json", "POST",
            JsonObject(mapOf("id" to JsonPrimitive(postId))).toString())
    }

    /**
     * 发写请求。
     *
     * 被 CF 挑战时先静默重验证再重发一次,而不是直接把用户丢进全屏验证页 ——
     * 看了半小时帖子点个赞刚好撞上 cf_clearance 过期,是很常见的。
     *
     * 重放是安全的:CF 的挑战是在边缘拦下的(cf-mitigated: challenge),源站压根没收到这个请求,
     * 所以不存在"已经执行了又执行一次"。但**必须连 CSRF 一起重取** —— 重验证会在 WebView 里
     * 重新加载首页,Discourse 可能借机换掉 _forum_session,旧 token 就失效了。
     * 只重试一次,再挑战就交给用户出面。
     */
    private suspend fun mutate(path: String, method: String, body: String? = null): String {
        var revalidated = false
        while (true) {
            val csrf = objectRequest("/session/csrf.json").text("csrf").also { require(it.isNotBlank()) }
            val result = gate.withLock {
                throttle()
                val transport = transportProvider()
                try { transport.write(path, method, body, csrf) } finally { lastRequestAt = SystemClock.elapsedRealtime() }
            }
            diagnostics.recordStatus(transportProvider().name, result.status, path)
            if (result.challenged) {
                // clearChallenge 最长要等 30 秒,绝不能占着 gate 等 —— 那会把所有请求堵死。
                if (!revalidated && transportProvider().revalidate(path)) {
                    revalidated = true
                    continue
                }
                throw NeedsInteractiveVerification("提交需要重新验证")
            }
            if (result.status == 0) throw NetworkTimeoutException()
            if (result.status !in 200..299) {
                val serverMessage = runCatching {
                    (json.parseToJsonElement(result.body).jsonObject["errors"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n")?.take(400)
                }.getOrNull()
                val message = if (result.status >= 500) "结果未确认，请刷新楼层后检查提交状态"
                    else serverMessage?.takeIf { it.isNotBlank() } ?: "操作未提交，请稍后重试"
                throw HttpStatusException(result.status, message)
            }
            return result.body
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

    suspend fun fetchProfile(
        username: String,
        section: ProfileSection,
        cursor: Int,
        notificationFilter: NotificationFilter = NotificationFilter.All,
    ): ProfilePage {
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
                // 固定只有这两个请求,第二个走突发间隔。并发没有意义 —— gate 是全局串行锁,
                // async 出去也只会卡在同一把锁上,真正的开销是锁后的节流等待。
                val emails = objectRequest("/u/$userPath/emails.json", burst = true)
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
                val actions = objectRequest("/user_actions.json?username=$userPath&offset=${cursor * 30}&limit=30").objects("user_actions")
                ProfilePage(entries = actions.mapIndexed { index, action ->
                    ProfileEntry("${cursor * 30 + index}:${action.text("post_id")}",
                        action.text("title").ifBlank { "社区活动" },
                        listOf(activityLabel(action.text("action_type")), Jsoup.parseBodyFragment(action.text("excerpt")).text(),
                            action.text("created_at").take(10)).filter { it.isNotBlank() }.joinToString(" · "),
                        action.number("topic_id"))
                }, hasMore = actions.size >= 30, nextCursor = cursor + 1)
            }
            ProfileSection.Notifications -> fetchNotifications(cursor, notificationFilter)
            ProfileSection.Messages -> {
                val result = toPage(json.decodeFromString(request("/topics/private-messages/$userPath.json?page=$cursor")))
                ProfilePage(entries = result.items.map { item -> ProfileEntry(item.topic.id.toString(), item.topic.title,
                    "${item.topic.replyCount} 回复 · ${item.topic.lastPostedAt?.take(10).orEmpty()}", item.topic.id) },
                    hasMore = result.hasMore, nextCursor = cursor + 1)
            }
        }
    }

    /**
     * 按类型取通知。
     *
     * 关键点:服务端分页是按**全类型**通知流算 offset 的,而界面只展示其中一类。
     * 如果像普通分页那样"一页请求换一页展示",私信这种低频类型会出现一页只命中 1~2 条、
     * 界面于是不停触发加载下一页、用户只看到一个转不停的 spinner 的情况。
     *
     * 所以这里在一次加载内就把游标往前推到攒够 PAGE_TARGET 条为止:
     * 先带上 filter_by_types 让服务端尽量只回目标类型(不被支持时该参数被忽略,返回全类型),
     * 再在客户端按类型过滤 —— 两条路径都靠这个循环收敛,所以服务端支持与否都不影响正确性。
     */
    private suspend fun fetchNotifications(offset: Int, filter: NotificationFilter): ProfilePage {
        val typeParam = filter.serverTypes.takeIf { it.isNotBlank() }
            ?.let { "&filter_by_types=${encode(it)}" }.orEmpty()
        val collected = mutableListOf<ProfileEntry>()
        var scanned = offset
        var hasMore = true
        var rounds = 0
        while (hasMore && collected.size < PAGE_TARGET && rounds < MAX_SCAN_ROUNDS) {
            rounds++
            val page = objectRequest(
                "/notifications.json?offset=$scanned&limit=$NOTIFICATION_SCAN_LIMIT$typeParam",
                // 第 2 轮起走突发间隔。低频分类(私信)可能要连扫几轮才凑够一页,
                // 每轮都等满 1 秒的话用户要干等 5 秒;轮数有上限,峰值是封顶的。
                burst = rounds > 1,
            )
            val notices = page.objects("notifications")
            // 游标按请求的 limit 走,不按返回条数 —— 服务端是先分页、再剔除不可访问的话题,
            // 按返回条数累加会让游标落后、下一页重复拉同一批。
            scanned += NOTIFICATION_SCAN_LIMIT
            val total = page.number("total_rows_notifications")
            // 同理,"这一页不满"不能当作已到末尾,有总数就按总数判断。
            hasMore = total?.let { scanned < it } ?: (notices.size >= NOTIFICATION_SCAN_LIMIT)
            collected += notices.map(::toNotificationEntry).filter { filter.includes(it.notificationType) }
        }
        return ProfilePage(entries = collected, hasMore = hasMore, nextCursor = scanned)
    }

    private fun toNotificationEntry(notice: JsonObject): ProfileEntry {
        val data = notice["data"] as? JsonObject ?: JsonObject(emptyMap())
        return ProfileEntry(notice.text("id"),
            Jsoup.parseBodyFragment(notice.text("fancy_title").ifBlank { data.text("fancy_title") }.ifBlank { data.text("topic_title") }).text()
                .ifBlank { notificationLabel(notice.text("notification_type")) },
            listOf(notificationLabel(notice.text("notification_type")), data.text("display_username"),
                notice.text("created_at").take(10)).filter { it.isNotBlank() }.joinToString(" · "),
            notice.number("topic_id"), unread = notice.text("read") == "false",
            notificationType = notice.text("notification_type").toIntOrNull() ?: 0,
            avatarUrl = avatarUrlOf(notice.text("acting_user_avatar_template")),
            actor = data.text("display_username").ifBlank { notice.text("acting_user_name") }.ifBlank { null },
            postNumber = notice.text("post_number").toIntOrNull()?.takeIf { it > 0 })
    }

    private suspend fun objectRequest(path: String, burst: Boolean = false): JsonObject =
        json.parseToJsonElement(request(path, burst)).jsonObject
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

    /** 冷启动从本地持久化 JSON 预热分类缓存（0ms 内存就绪，避免首屏阻塞等待网络）。 */
    fun seedCategoriesFromJson(rawJson: String) {
        if (rawJson.isBlank()) return
        runCatching {
            val list = json.decodeFromString<CategoryListResponse>(rawJson).categoryList.categories
            if (list.isNotEmpty()) {
                categoryListCache = list
                categoryCache = flattenCategories(list).associateBy { it.id }
            }
        }
    }

    fun cachedCategoryList(): List<Category>? = categoryListCache

    fun cachedCategoryMap(): Map<Int, Category> = categoryCache.orEmpty()

    /**
     * 向服务端探测最新分类树（Stale-While-Revalidate）。
     * 若与本地缓存不一致（新增/改名/改色/子分类调整），更新内存缓存并返回最新数据与原始 JSON 供持久化落盘；
     * 若完全一致则返回 null，避免无意义的界面重组。
     */
    suspend fun refreshCategoriesFromServer(): Pair<List<Category>, String>? = categoryGate.withLock {
        val raw = request("/categories.json?include_subcategories=true")
        val freshList = json.decodeFromString<CategoryListResponse>(raw).categoryList.categories
        val freshMap = flattenCategories(freshList).associateBy { it.id }
        val changed = categoryListCache != freshList || categoryCache != freshMap
        categoryListCache = freshList
        categoryCache = freshMap
        if (changed) freshList to raw else null
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
     * 使用账号密码登录。
     * 流程：GET /session/csrf → POST /session。
     * 成功返回 true，凭据错误返回 false，CF 挑战抛 NeedsInteractiveVerification，
     * 连不上服务器抛 NetworkTimeoutException。
     */
    suspend fun sessionLogin(login: String, password: String): Boolean = gate.withLock {
        throttle()
        val transport = transportProvider()
        // 1. 获取 CSRF token
        val csrf = loginCsrf(transport)

        // 2. 发送登录请求
        throttle()
        val safeLogin = login.replace("\"", "\\\"")
        val safePwd = password.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = "{\"login\":\"$safeLogin\",\"password\":\"$safePwd\",\"second_factor_method\":1}"
        val loginResult = loginCall("/session.json") { transport.write("/session.json", "POST", body, csrf) }
        diagnostics.recordStatus(transport.name, loginResult.status, "/session")
        if (loginResult.challenged) throw NeedsInteractiveVerification("登录请求被 Cloudflare 拦截")
        if (loginResult.status == 0) throw NetworkTimeoutException()
        if (loginResult.status == 429) throw HttpStatusException(429, "请求过于频繁，请稍后再试")
        val responseObj = try {
            json.parseToJsonElement(loginResult.body).jsonObject
        } catch (e: Exception) {
            return@withLock loginResult.status in 200..299
        }
        val error = responseObj["error"]?.jsonPrimitive?.contentOrNull
        if (!error.isNullOrBlank()) { diagnostics.log("登录失败: $error"); return@withLock false }
        responseObj["user"] != null
    }

    /**
     * 向指定邮箱发送一次性登录链接邮件（Discourse magic link）。
     * 用户从邮件链接中复制 token，再交由 ViewModel.loginWithEmailCode 处理。
     */
    suspend fun sendEmailLogin(email: String) = gate.withLock {
        throttle()
        val transport = transportProvider()
        val csrf = loginCsrf(transport)
        throttle()
        val safeEmail = email.replace("\"", "\\\"")
        val body = "{\"login\":\"$safeEmail\"}"
        val result = loginCall("/u/email-login.json") { transport.write("/u/email-login.json", "POST", body, csrf) }
        diagnostics.recordStatus(transport.name, result.status, "/u/email-login")
        if (result.challenged) throw NeedsInteractiveVerification("发邮件请求被 Cloudflare 拦截")
        if (result.status == 0) throw NetworkTimeoutException()
        if (result.status !in 200..299) throw HttpStatusException(result.status, result.body.take(120))
    }

    private suspend fun loginCsrf(transport: Transport): String {
        val result = loginCall("/session/csrf.json") { transport.get("/session/csrf.json") }
        if (result.challenged) throw NeedsInteractiveVerification("CSRF 请求被 Cloudflare 拦截")
        if (result.status == 0) throw NetworkTimeoutException()
        if (result.status !in 200..299) throw HttpStatusException(result.status, "无法获取 CSRF token")
        return json.parseToJsonElement(result.body).jsonObject["csrf"]
            ?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("CSRF 响应缺少 token 字段")
    }

    /**
     * 用邮件里的 token 完成登录。
     *
     * 必须发 POST。Discourse 把这一步拆成了两个动作:
     *   GET  /session/email-login/:token → SessionController#email_login_info
     *        只做 token 预检,渲染一个"完成登录"确认页,**不建立会话**;
     *   POST /session/email-login/:token → SessionController#email_login
     *        才真正走 log_on_user 下发 _t。
     * 早先用 WebView 打开 GET 地址,拿到的永远是那个确认页 —— 会话建不起来,
     * 自然会被 boot() 判定为未登录并弹回登录页。
     */
    suspend fun emailLoginWithToken(token: String) = gate.withLock {
        throttle()
        val transport = transportProvider()
        val csrf = loginCsrf(transport)
        throttle()
        val path = "/session/email-login/${encode(token)}"
        val result = loginCall(path) { transport.write(path, "POST", "{}", csrf) }
        diagnostics.recordStatus(transport.name, result.status, "/session/email-login")
        if (result.challenged) throw NeedsInteractiveVerification("邮件登录请求被 Cloudflare 拦截")
        if (result.status == 0) throw NetworkTimeoutException()

        // 注意:token 失效、未通过审核、二次验证不通过这些情况,Discourse 都是
        // `render json: { error: ... }` —— HTTP 状态码仍是 200。只看状态码会把失败当成功,
        // 然后在 boot() 里莫名其妙地退回登录页,所以这里必须先看 error 字段。
        val body = runCatching { json.parseToJsonElement(result.body).jsonObject }.getOrNull()
        val serverError = body?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (serverError != null) {
            diagnostics.log("邮件登录被拒: $serverError")
            throw LoginRejectedException(serverError)
        }
        if (result.status !in 200..299) {
            throw LoginRejectedException("登录链接无效或已过期，请重新获取")
        }
    }

    /**
     * 登录链路的硬超时。
     * OkHttp 的 connectTimeout 只覆盖建连,DNS 解析卡住时不生效;而 withTimeout 抛的
     * TimeoutCancellationException 是 CancellationException 的子类,会被上层各处
     * `catch (CancellationException) { throw }` 原样抛出、绕过错误提示,所以必须就地换成
     * NetworkTimeoutException。
     */
    private suspend fun loginCall(path: String, call: suspend () -> TransportResult): TransportResult = try {
        withTimeout(Config.LOGIN_CALL_TIMEOUT_MS) { call() }
    } catch (timeout: TimeoutCancellationException) {
        diagnostics.log("登录请求超时: $path")
        throw NetworkTimeoutException()
    } finally {
        lastRequestAt = SystemClock.elapsedRealtime()
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

    private suspend fun toPage(response: TopicListResponse, awaitCategories: Boolean = true): TopicPage {
        val known = if (awaitCategories) categories() else categoryCache.orEmpty()
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

    private suspend fun request(path: String, burst: Boolean = false): String = gate.withLock {
        var attempt = 0
        while (true) {
            throttle(burst)
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
                // 连不上服务器时重试只是让用户多等十几秒,直接报错;5xx 才是值得重试的服务端抖动。
                result.status == 0 -> throw NetworkTimeoutException()
                result.status >= 500 && attempt < MAX_RETRIES -> {
                    delay(BACKOFF_BASE_MS shl attempt)
                    attempt++
                }
                else -> throw HttpStatusException(result.status, result.body.take(120))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun throttle(burst: Boolean = false) {
        val interval = if (burst) Config.BURST_REQUEST_INTERVAL_MS else Config.MIN_REQUEST_INTERVAL_MS
        val wait = interval - (SystemClock.elapsedRealtime() - lastRequestAt)
        if (wait > 0) delay(wait)
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val BACKOFF_BASE_MS = 1_000L

        /** 通知分区一次加载的目标条数。 */
        const val PAGE_TARGET = 20

        /** 单次扫描服务端通知流的条数。取大一些,低频类型才不至于每页只命中一两条。 */
        const val NOTIFICATION_SCAN_LIMIT = 60

        /** 单次加载最多往前扫几轮,避免某一类通知很久没有时无限翻页。 */
        const val MAX_SCAN_ROUNDS = 5
    }
}

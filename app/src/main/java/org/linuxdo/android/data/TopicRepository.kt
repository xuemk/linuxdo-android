package org.linuxdo.android.data

import android.os.SystemClock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.linuxdo.android.net.HttpHeaderUtils
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URI
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup
import org.linuxdo.android.Config
import org.linuxdo.android.net.ChallengeDetector
import org.linuxdo.android.net.HttpStatusException
import org.linuxdo.android.net.LoginRejectedException
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.NetDiagnostics
import org.linuxdo.android.net.NetworkTimeoutException
import org.linuxdo.android.net.RateLimitException
import org.linuxdo.android.net.RiskControlException
import org.linuxdo.android.net.SecondFactorRequiredException
import org.linuxdo.android.net.Transport
import org.linuxdo.android.net.TransportResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.linuxdo.android.net.ActiveSessionRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.CookieJar
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DiscourseTagInfo(
    val name: String,
    val text: String = name,
    val count: Int = 0,
)

data class DiscourseTagSearchResult(
    val results: List<DiscourseTagInfo> = emptyList(),
    val requiredTagName: String? = null,
    val requiredTagMinCount: Int = 1,
)

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
data class TopicBusMessage(
    val channel: String,
    @SerialName("message_id") val messageId: Long,
    val data: JsonElement = JsonNull,
)

internal class MessageBusConfiguration(
    val baseUrl: HttpUrl,
    val sharedSessionKey: String?,
    val csrfToken: String?,
) {
    val sendCookies: Boolean get() {
        val site = Config.BASE_URL.toHttpUrl()
        return sharedSessionKey.isNullOrBlank() && baseUrl.scheme == site.scheme &&
            baseUrl.host == site.host && baseUrl.port == site.port
    }

    fun pollUrl(clientId: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("message-bus").addPathSegment(clientId).addPathSegment("poll").build()

    override fun toString(): String = "MessageBusConfiguration(host=${baseUrl.host})"
}

/** 读取站点自身发布的轮询地址和凭据，兼容 Discourse 两种预加载 HTML 形式。 */
internal fun parseMessageBusConfiguration(html: String): MessageBusConfiguration {
    val document = Jsoup.parse(html)
    val element = document.getElementById("data-preloaded") ?: document.selectFirst("[data-preloaded]")
    val raw = if (element?.tagName() == "script") element.data() else element?.attr("data-preloaded")
    require(!raw.isNullOrBlank()) { "页面未包含站点预加载配置" }
    val preloaded = Json.parseToJsonElement(raw) as? JsonObject
    val settingsValue = preloaded?.get("siteSettings")
    val settings = when (settingsValue) {
        is JsonObject -> settingsValue
        is JsonPrimitive -> settingsValue.contentOrNull?.let { Json.parseToJsonElement(it) as? JsonObject }
        else -> null
    }
    require(settings != null) { "页面未包含 siteSettings" }
    val pollingValue = settings["long_polling_base_url"]
    require(pollingValue == null || pollingValue == JsonNull || (pollingValue is JsonPrimitive && pollingValue.isString)) {
        "轮询地址格式无效"
    }
    val polling = (pollingValue as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val baseUrl = Config.BASE_URL.toHttpUrl().resolve(polling.ifBlank { "/" })
    require(baseUrl != null && baseUrl.isHttps && baseUrl.username.isEmpty() && baseUrl.password.isEmpty() &&
        baseUrl.query == null && baseUrl.fragment == null) { "轮询地址必须是 HTTPS 站点地址" }
    fun meta(name: String): String? = document.selectFirst("meta[name=$name]")?.attr("content")
        ?.trim()?.takeIf { it.isNotEmpty() }?.also { value ->
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "站点凭据格式无效" }
        }
    return MessageBusConfiguration(baseUrl, meta("shared_session_key"), meta("csrf-token"))
}

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

internal data class BookmarkTarget(val topicId: Long, val bookmarkId: Long?, val postNumber: Int?)

internal fun parseTopicMetadata(root: JsonObject, expectedId: Long): TopicMetadata {
    val id = (root["id"] as? JsonPrimitive)?.longOrNull
    require(id == expectedId && expectedId > 0) { "话题信息不匹配，请重新打开编辑" }
    val details = root["details"] as? JsonObject
    val tags = (root["tags"] as? JsonArray).orEmpty().mapNotNull {
        val name = (it as? JsonPrimitive)?.contentOrNull ?: ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        name?.takeIf(String::isNotBlank)
    }.distinct()
    return TopicMetadata(expectedId, (root["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        (root["category_id"] as? JsonPrimitive)?.intOrNull, tags,
        (details?.get("can_edit") as? JsonPrimitive)?.booleanOrNull == true,
        (root["archetype"] as? JsonPrimitive)?.contentOrNull == "private_message")
}

internal fun bookmarkTarget(value: JsonObject): BookmarkTarget? {
    fun number(key: String) = (value[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
    fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val isTopic = text("bookmarkable_type").equals("Topic", ignoreCase = true)
    val explicitTopic = number("topic_id") ?: number("bookmarkable_id")?.takeIf { isTopic }
    val parts = runCatching { URI(text("bookmarkable_url")).path.orEmpty().split('/').filter(String::isNotEmpty) }.getOrDefault(emptyList())
    val topicIndex = when {
        parts.firstOrNull() != "t" -> -1
        parts.size >= 4 -> 2
        explicitTopic != null -> parts.indexOf(explicitTopic.toString()).takeIf { it > 0 } ?: -1
        parts.getOrNull(1)?.toLongOrNull() == null -> 2
        else -> 1
    }
    val topic = explicitTopic ?: parts.getOrNull(topicIndex)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    fun floor(key: String) = number(key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val post = floor("linked_post_number") ?: floor("post_number")
        ?: parts.getOrNull(topicIndex + 1)?.takeIf { topicIndex > 0 }?.toIntOrNull()?.takeIf { it > 0 }
        ?: 1.takeIf { isTopic }
    return BookmarkTarget(topic, number("id") ?: number("bookmark_id"), post)
}

class TopicRepository(
    private val transportProvider: () -> Transport,
    private val diagnostics: Diagnostics,
    private val reading: ReadingStore? = null,
    private val sessionStore: SessionStore? = null,
    private val fallbackTransportProvider: (() -> Transport)? = null,
    private val httpClientProvider: (() -> OkHttpClient)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val ldcAuthGate = Mutex()
    private val csrfGate = Mutex()
    private val pollingConfigGate = Mutex()
    private var pollingConfigGeneration: Long? = null
    private var pollingConfig: MessageBusConfiguration? = null
    private var nextPollingConfigAttemptAt = 0L
    // 双轨调度器 (策略二)：getSemaphore, postGate 及滑动窗口限流器全部位于 companion object，全进程单例共享

    init {
        sessionStore?.let { store ->
            val savedWallUntil = store.getRateLimitedUntil()
            val nowWall = System.currentTimeMillis()
            if (savedWallUntil > nowWall) {
                val remaining = (savedWallUntil - nowWall).coerceAtMost(RATE_LIMIT_COOLDOWN_MS)
                synchronized(rateLimitLock) {
                    rateLimitedUntil = maxOf(rateLimitedUntil, SystemClock.elapsedRealtime() + remaining)
                }
            }
        }
    }

    /** 429 冷却期间，后台任务应直接退让，不能把限流误当作需要过盾。 */
    fun isRateLimitCoolingDown(): Boolean = Companion.isRateLimitCoolingDown()

    /** 后台静默预取（首楼摘要、新话题探测）撞盾后的退避期内，这类请求一律不发。 */
    fun isSilentPrefetchBackingOff(): Boolean =
        SystemClock.elapsedRealtime() < silentPrefetchBackoffUntil

    /**
     * 后台静默预取撞到 CF 挑战/403 时进入退避，返回是否命中。
     *
     * 逐个话题连拉 /t/{id}/1.json 是典型的爬取特征，撞盾后若继续换下一个 ID 或回退 /raw，
     * 只会把同一枚 cf_clearance 的风险分越推越高，连带前台正常浏览也被挑战。
     */
    fun enterSilentPrefetchBackoff(error: Throwable): Boolean {
        val challenged = error is NeedsInteractiveVerification ||
            (error is HttpStatusException && error.status == 403)
        if (!challenged) return false
        val wasBackingOff = isSilentPrefetchBackingOff()
        silentPrefetchBackoffUntil = SystemClock.elapsedRealtime() + SILENT_PREFETCH_BACKOFF_MS
        if (!wasBackingOff) {
            diagnostics.log("后台预取撞盾，暂停摘要预取与新话题探测 ${SILENT_PREFETCH_BACKOFF_MS / 60_000L} 分钟")
        }
        return true
    }

    fun rateLimitRemainingMs(): Long = Companion.rateLimitRemainingMs()

    private fun throwIfRateLimitCoolingDown() {
        val remaining = rateLimitRemainingMs()
        if (remaining > 0L) throw RateLimitException(remaining)
    }

    fun clearTimingsCache() {
        Companion.clearTimingsCache(sessionStore?.currentUserId())
    }

    private fun enterRateLimitCooldown(
        path: String,
        retryAfterSeconds: Long? = null,
        cfChallenge: Boolean = false,
    ): RateLimitException {
        val remaining = Companion.enterRateLimitCooldown(path, retryAfterSeconds, sessionStore, cfChallenge)
        val kind = if (cfChallenge) "CF质询" else "429"
        diagnostics.log(
            "服务端限流($kind): $path，暂停自动网络请求 ${remaining / 1_000L}s，" +
                "后台池惩罚 ${ApiQuotaPool.UNREAD_POLL.penaltyRemainingMs() / 1_000L}s",
        )
        return RateLimitException(remaining)
    }

    /**
     * 阅读进度上报复用的 CSRF Token。
     * 写请求本来每次都要先 GET 一次 /session/csrf.json,统计这种可丢弃的请求不值得为此翻倍;
     * 失效(403/挑战)时清空重取即可。
     */
    private var cachedCsrfToken: String? = null

    fun clearCachedCsrfToken() {
        cachedCsrfToken = null
    }

    fun resetTimingsCooldown() {
        timingsCooldownUntil = 0L
    }

    suspend fun refreshCsrf(): String = obtainCsrf(forceRefresh = true)

    /** 自动网络与会话自愈回调 (由 ViewModel 注册) */
    var onAutoHealRequested: (suspend () -> Unit)? = null

    data class Http403Event(val key: String, val timestamp: Long)

    /** 记录 6 分钟滑动窗口内的 403 / 挑战拦截事件以智能触发自愈 */
    private val recent403Events = java.util.Collections.synchronizedList(mutableListOf<Http403Event>())
    private var lastAutoHealAt = 0L

    fun notify403OrChallenged(key: String, reason: String = "") {
        val now = SystemClock.elapsedRealtime()
        synchronized(recent403Events) {
            recent403Events.removeAll { now - it.timestamp > 360_000L }
            recent403Events.add(Http403Event(key, now))
            diagnostics.log("网络监控: 记录 403/挑战事件 (特征: $key, 来源: $reason)")
        }
    }

    /** 分类表全站共用且很少变,整个进程只拉一次。 */
    private var categoryCache: Map<Int, Category>? = null
    private val categoryGate = Mutex()
    private var categoryListCache: List<Category>? = null
    private var tagListCache: List<DiscourseTag>? = null
    private val tagGate = Mutex()
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

    suspend fun fetchFeed(
        feed: HomeFeedType,
        page: Int,
        awaitCategories: Boolean = true,
        present: Boolean = true,
        order: String? = null,
        ascending: Boolean? = null,
    ): TopicPage {
        val excludeRead = feed == HomeFeedType.Unread
        val query = buildList {
            add("page=$page")
            if (!order.isNullOrBlank()) add("order=$order")
            if (ascending != null) add("ascending=$ascending")
        }.joinToString("&")
        val path = "${feed.apiPath}?$query"
        val body = request(path, burst = page == 0, present = present, retryAllowed = present)
        val result = withContext(Dispatchers.Default) {
            toPage(
                response = json.decodeFromString(body),
                awaitCategories = awaitCategories,
                excludeReadUnread = excludeRead,
            )
        }
        return if (feed == HomeFeedType.Latest) result.copy(
            items = result.items.filterNot { it.notificationLevel == TopicNotificationLevel.Muted },
        ) else result
    }

    suspend fun fetchLatest(page: Int, awaitCategories: Boolean = true, present: Boolean = true): TopicPage =
        fetchFeed(HomeFeedType.Latest, page, awaitCategories, present = present)

    suspend fun fetchCategoryTopics(
        slug: String,
        categoryId: Int,
        page: Int,
        present: Boolean = true,
        order: String? = null,
        ascending: Boolean? = null,
    ): TopicPage {
        val query = buildList {
            add("page=$page")
            if (!order.isNullOrBlank()) add("order=$order")
            if (ascending != null) add("ascending=$ascending")
        }.joinToString("&")
        val body = request(
            "/c/${encode(slug.ifBlank { "category" })}/$categoryId.json?$query",
            burst = page == 0,
            present = present,
            retryAllowed = present,
        )
        return withContext(Dispatchers.Default) {
            toPage(
                json.decodeFromString(body),
                awaitCategories = false,
            )
        }
    }

    suspend fun fetchUserCreatedTopics(username: String, page: Int): TopicPage =
        toPage(json.decodeFromString(request("/topics/created-by/${encode(username)}.json?page=$page", burst = page == 0)), awaitCategories = false)

    suspend fun fetchUserBookmarks(username: String, page: Int): TopicPage {
        val userPath = encode(username)
        val pageQuery = if (page > 0) "&page=$page" else ""
        val root = objectRequest("/u/$userPath/bookmarks.json?q=$pageQuery", burst = page == 0)
        val listObj = root["user_bookmark_list"] as? JsonObject
        val bookmarks = listObj?.objects("bookmarks") ?: root.objects("bookmarks")
        val moreUrl = listObj?.text("more_bookmarks_url").orEmpty().ifBlank { root.text("more_bookmarks_url") }
        val known = categoryCache ?: categories()

        val items = bookmarks.mapNotNull { bm ->
            val target = bookmarkTarget(bm) ?: return@mapNotNull null
            val topicId = target.topicId

            val rawTitle = bm.text("title").ifBlank { bm.text("fancy_title") }.ifBlank { bm.text("name") }
            val title = Jsoup.parseBodyFragment(rawTitle).text().ifBlank { "已收藏话题" }
            val slug = bm.text("slug").ifBlank { "topic" }
            val highest = bm.text("highest_post_number").toIntOrNull()
            val linked = target.postNumber
            val postsCount = bm.text("posts_count").toIntOrNull() ?: highest ?: linked ?: 1
            val replyCount = bm.text("reply_count").toIntOrNull() ?: (postsCount - 1).coerceAtLeast(0)
            val categoryId = bm.text("category_id").toIntOrNull()

            val userObj = (bm["user"] as? JsonObject) ?: (bm["post_user"] as? JsonObject)
            val author = userObj?.text("username")?.takeIf { it.isNotBlank() }
                ?: bm.text("username").ifBlank { bm.text("post_username") }.ifBlank { null }
            val authorName = userObj?.text("name")?.takeIf { it.isNotBlank() }
                ?: bm.text("name").ifBlank { null }
            val animAvatar = userObj?.text("animated_avatar")?.takeIf { it.isNotBlank() }
                ?: bm.text("animated_avatar").ifBlank { null }
            val avatar = userObj?.text("avatar_template")?.takeIf { it.isNotBlank() }
                ?: bm.text("avatar_template").ifBlank { bm.text("post_user_avatar_template") }

            val createdAt = bm.text("created_at")
            val bumpedAt = bm.text("bumped_at").ifBlank { bm.text("updated_at").ifBlank { createdAt } }
            val excerpt = Jsoup.parseBodyFragment(bm.text("excerpt").ifBlank { bm.text("cooked") }).text()
            val tags = (bm["tags"] as? JsonArray).orEmpty().mapNotNull { elem ->
                (elem as? JsonPrimitive)?.contentOrNull
                    ?: (elem as? JsonObject)?.text("name")?.takeIf { it.isNotBlank() }
            }

            val cachedLevel = sessionStore?.getTopicNotificationLevel(topicId)
            val notificationLevel = cachedLevel?.let { TopicNotificationLevel.fromCode(it) }

            TopicListItem(
                topic = Topic(
                    id = topicId,
                    title = title,
                    slug = slug,
                    postsCount = postsCount,
                    replyCount = replyCount,
                    createdAt = createdAt,
                    lastPostedAt = bumpedAt,
                    bumpedAt = bumpedAt,
                    categoryId = categoryId,
                    excerpt = excerpt,
                    tags = tags,
                ),
                authorUsername = author,
                authorName = authorName,
                avatarUrl = avatarUrlOf(avatar, animAvatar, size = 64),
                staticAvatarUrl = avatarUrlOf(avatar, null, size = 64),
                category = categoryId?.let { known[it] },
                trustLevel = null,
                notificationLevel = notificationLevel,
                bookmarkId = target.bookmarkId,
                targetPostNumber = linked,
            )
        }.distinctBy { it.listKey }

        return TopicPage(
            items = items,
            hasMore = moreUrl.isNotBlank() && items.isNotEmpty(),
        )
    }

    suspend fun fetchTopic(
        id: Long,
        postNumber: Int? = null,
        burst: Boolean = false,
        trackVisit: Boolean = true,
        present: Boolean = true,
    ): TopicDetail {
        val position = postNumber?.takeIf { it > 1 }?.let { "/$it" }.orEmpty()
        val body = request("/t/$id$position.json?track_visit=${present && trackVisit}",
            burst = burst, present = present, retryAllowed = present)
        return withContext(Dispatchers.Default) {
            json.decodeFromString<TopicDetail>(body).also { detail ->
                detail.postStream.posts.forEach { post ->
                    AvatarCache.record(post.username, post.animatedAvatar)
                }
                detail.postStream.posts.firstOrNull { it.postNumber == 1 }?.let { firstPost ->
                    val doc = Jsoup.parseBodyFragment(firstPost.cooked)
                    val firstImg = doc.select("img").firstOrNull { img ->
                        val src = img.attr("src")
                        !img.hasClass("emoji") &&
                        !img.hasClass("avatar") &&
                        !src.contains("/user_avatar/") &&
                        !src.contains("/images/emoji/") &&
                        (src.startsWith("http") || src.startsWith("/"))
                    }
                    val coverUrl = firstImg?.attr("src")?.let { src ->
                        if (src.startsWith("//")) "https:$src"
                        else if (src.startsWith("/")) "${Config.BASE_URL}$src"
                        else src
                    }
                    doc.select(".lightbox-wrapper, .lightbox, .meta, .informations, .filename, img, svg, video, audio").remove()
                    val plainText = doc.text().trim()
                    ExcerptCache.record(detail.id, plainText, coverUrl)
                }
            }
        }
    }

    private val prefetchingIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())


    private suspend fun fetchRawOp(topicId: Long, beforeDispatch: () -> Unit): Pair<String?, String?> {
        // 不带楼层号的 /raw/{id} 会导出整篇话题；重试只由预览调度器统一计量。
        val raw = request("/raw/$topicId/1", present = false, retryAllowed = false, beforeDispatch = beforeDispatch)
        return withContext(Dispatchers.Default) {
            val plainText = rawExcerptToPlainText(raw).take(150).takeIf { it.isNotEmpty() }
            val coverUrl = extractMarkdownFirstImage(raw)
            plainText to coverUrl
        }
    }

    private fun extractMarkdownFirstImage(raw: String): String? {
        val mdMatch = Regex("""!\[[^\]]*]\((https?://[^)\s]+|/uploads/[^)\s]+|upload://[^)\s]+)""").find(raw)
        if (mdMatch != null) {
            val url = mdMatch.groupValues[1]
            if (url.startsWith("upload://")) return "${Config.BASE_URL}/uploads/short-url/${url.removePrefix("upload://")}"
            return if (url.startsWith("/")) "${Config.BASE_URL}$url" else url
        }
        val htmlMatch = Regex("""<img\b[^>]*src=["'](https?://[^"'\s]+|/uploads/[^"'\s]+)["']""", RegexOption.IGNORE_CASE).find(raw)
        if (htmlMatch != null) {
            val url = htmlMatch.groupValues[1]
            return if (url.startsWith("/")) "${Config.BASE_URL}$url" else url
        }
        return null
    }

    /**
     * 单次首楼请求返回明确结果；是否重试交给持有可见性和总预算的调用方。
     */
    internal suspend fun prefetchExcerpt(topicId: Long, beforeDispatch: () -> Unit = {}): ExcerptFetchResult {
        if (topicId <= 0L) return ExcerptFetchResult(ExcerptFetchOutcome.PERMANENT_FAILURE)
        if (ExcerptCache.get(topicId) != null) return ExcerptFetchResult(ExcerptFetchOutcome.COMPLETE)
        if (isSilentPrefetchBackingOff() || isRateLimitCoolingDown()) return ExcerptFetchResult(ExcerptFetchOutcome.BLOCKED)
        if (!ApiQuotaPool.EXCERPT_PREFETCH.tryAcquire() || !prefetchingIds.add(topicId)) {
            return ExcerptFetchResult(ExcerptFetchOutcome.DEFERRED, ApiQuotaPool.EXCERPT_PREFETCH.remainingWaitMs().coerceAtLeast(1_000L))
        }
        return try {
            val op = fetchRawOp(topicId, beforeDispatch)
            val plainText = op.first
            val coverUrl = op.second
            if (!plainText.isNullOrBlank() || !coverUrl.isNullOrBlank()) {
                ExcerptCache.record(topicId, plainText, coverUrl)
            }
            ApiQuotaPool.EXCERPT_PREFETCH.onSuccess()
            ExcerptFetchResult(if (plainText == null && coverUrl == null) ExcerptFetchOutcome.EMPTY else ExcerptFetchOutcome.COMPLETE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val challenged = enterSilentPrefetchBackoff(error)
            val status = when (error) {
                is HttpStatusException -> error.status
                is RateLimitException -> 429
                is NetworkTimeoutException -> 0
                else -> -1
            }
            val retryAfterMs = when (error) {
                is RateLimitException -> error.retryAfterMs
                is HttpStatusException -> error.retryAfterSeconds?.coerceIn(0L, Long.MAX_VALUE / 1_000L)?.times(1_000L) ?: 0L
                else -> 0L
            }
            val outcome = ExcerptFetchOutcome.fromStatus(status, challenged)
            diagnostics.logThrottled("excerpt-result-$status", "预览请求结果: HTTP=$status 处理=$outcome")
            ExcerptFetchResult(outcome, retryAfterMs)
        } finally {
            prefetchingIds.remove(topicId)
        }
    }

    private fun rawExcerptToPlainText(raw: String): String {
        val withoutExportHeader = raw.replace(RAW_EXPORT_HEADER, "")
        val withoutAttachments = withoutExportHeader
            .replace(RAW_ATTACHMENT_MARKDOWN, " ")
            .replace(RAW_ATTACHMENT_PLAIN_LINE, " ")
        val withoutMedia = withoutAttachments
            .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), " ")
            .replace(Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE), " ")
        val withoutLinks = withoutMedia.replace(
            Regex("""\[([^\]]+)]\([^)]*\)"""),
            "${'$'}1",
        )
        val withoutMarkers = withoutLinks
            .replace(Regex("""(?m)^\s{0,3}(?:#{1,6}\s+|>\s*|[-+*]\s+|\d+[.)]\s+)"""), "")
            .replace(Regex("""[`*_~]"""), "")
        return Jsoup.parseBodyFragment(withoutMarkers).text()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** `/raw/{topicId}` 首行是导出元信息，不属于用户正文。 */
    private val RAW_EXPORT_HEADER = Regex(
        """\A\s*[^|\r\n]+\s*\|\s*\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\s+(?:UTC|GMT|[A-Z]{2,5}|[+-]\d{2}:?\d{2})\s*\|\s*#\d+\s*(?:\r?\n)?""",
    )

    /** Discourse 附件 Markdown 与可选的紧随体积行，例如 `[200.txt|attachment](upload://...)\n(2.4 KB)`。 */
    private val RAW_ATTACHMENT_MARKDOWN = Regex(
        """(?is)!?\[[^\]\r\n]*\|attachment]\([^)\r\n]*\)[ \t]*(?:\r?\n[ \t]*\([ \t]*[\d.,]+[ \t]*(?:B|KB|MB|GB|TB)[ \t]*\))?""",
    )

    /** 容错处理被服务端展开过的附件标题行。 */
    private val RAW_ATTACHMENT_PLAIN_LINE = Regex(
        """(?im)^[ \t]*[^\r\n|]{1,200}\|attachment[ \t]*(?:\r?\n[ \t]*\([ \t]*[\d.,]+[ \t]*(?:B|KB|MB|GB|TB)[ \t]*\))?[ \t]*$""",
    )

    /**
     * 上报阅读进度(`POST /topics/timings`),让网页端的未读蓝点消失、总结页的
     * 已读帖子与阅读时长正常增长。只有用户在设置里开启同步开关后才会被调用。
     *
     * 走全局 gate 串行节流。此为可丢弃的统计请求，遇 Cloudflare 挑战或服务异常直接返回 false，
     * 绝不抛异常打断用户正常阅读。
     */
    private var timingsCooldownUntil = 0L

    fun readTimingsCooldownRemainingMs(): Long = maxOf(
        rateLimitRemainingMs(), timingsCooldownUntil - SystemClock.elapsedRealtime(), 0L,
    )

    /**
     * 路径 B 已证明能过 timings 时置位：A 的 OkHttp 指纹/出口拿着同一枚 cf_clearance 仍会被
     * POST 写规则挑战，继续先打 A 只会每次多吃一个 403，所以后续心跳直接走 B。
     */
    @Volatile private var preferWebViewTimings = false

    /** timings 被 CF 挑战且静默兜底无果，需要请用户手工过盾（由 ViewModel 展示横幅）。 */
    var onTimingsVerificationRequired: (() -> Unit)? = null

    /** timings 重新同步成功，横幅可以收起。 */
    var onTimingsRecovered: (() -> Unit)? = null

    private fun markTimingsSynced() {
        timingsCooldownUntil = 0L
        onTimingsRecovered?.invoke()
    }

    /** 用户手工过盾后调用：解除熔断并让下一次心跳优先走 WebView 网络栈。 */
    fun onManualVerificationCompleted() {
        timingsCooldownUntil = 0L
        preferWebViewTimings = true
        cachedCsrfToken = null
    }

    suspend fun recordTopicTimings(
        topicId: Long,
        topicTimeMs: Long,
        postTimesMs: Map<Int, Long>,
        isReadingActive: () -> Boolean,
    ): Boolean {
        val generation = sessionStore?.sessionGeneration
        suspend fun ensureReadingActive() {
            currentCoroutineContext().ensureActive()
            if (!isReadingActive() || sessionStore?.sessionChanging == true ||
                generation != sessionStore?.sessionGeneration
            ) throw CancellationException("阅读已暂停或会话已切换")
        }
        ensureReadingActive()
        if (sessionStore?.sessionChanging == true) {
            diagnostics.logThrottled("timings-gate", "[诊断] 阅读进度跳过: 会话切换中")
            return false
        }
        if (topicId <= 0L || topicTimeMs <= 0L) return false
        if (isRateLimitCoolingDown()) {
            diagnostics.logThrottled("timings-gate", "[诊断] 阅读进度跳过: 429 冷却还剩 ${rateLimitRemainingMs() / 1000}s")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now < timingsCooldownUntil) {
            diagnostics.logThrottled("timings-gate", "[诊断] 阅读进度跳过: 熔断冷却还剩 ${(timingsCooldownUntil - now) / 1000}s")
            return false
        }
        val timings = postTimesMs.filter { (number, time) -> number > 0 && time >= TopicReadTracker.MIN_POST_DWELL_MS }
            .mapValues { (_, time) -> time.coerceAtMost(MAX_POST_TIME_MS) }
        val numbers = timings.keys.sorted()
        if (numbers.isEmpty()) {
            diagnostics.log("阅读进度未上报: 话题 $topicId 没有可见楼层")
            return false
        }

        val currentUserId = sessionStore?.currentUserId() ?: 0L

        // 楼层已读不代表后续真实阅读时间重复；只拦截并发批次。
        val requestMarker = Any()
        if (inFlightTimingsTopics.putIfAbsent(topicId, requestMarker) != null) {
            diagnostics.log("阅读进度跳过: 话题 $topicId 已有正在进行的同步任务")
            return false
        }

        try {
            val topicTime = topicTimeMs.coerceAtMost(MAX_TOPIC_TIME_MS)
            // Rails 的嵌套参数写法:timings[楼层号]=停留毫秒数。JSON body 在这个端点上不被认。
            val form = buildString {
                append("topic_id=").append(topicId)
                append("&topic_time=").append(topicTime)
                numbers.forEach { number -> append("&timings%5B").append(number).append("%5D=").append(timings.getValue(number)) }
            }
            val primaryTransport = transportProvider()
            val fallbackTransport = fallbackTransportProvider?.invoke()
                ?.takeIf { it.name != primaryTransport.name }

            suspend fun sendViaFallback(transport: Transport, csrfFallback: String?): TransportResult? = try {
                postGate.withLock {
                    ensureReadingActive()
                    if (isRateLimitCoolingDown()) return@withLock null
                    throttlePost(burst = true)
                    if (isRateLimitCoolingDown()) return@withLock null
                    val fallbackCsrf = transport.obtainCsrfToken() ?: csrfFallback ?: return@withLock null
                    ensureReadingActive()
                    try {
                        transport.write("/topics/timings", "POST", form, fallbackCsrf, Transport.FORM_CONTENT_TYPE, present = false)
                    } finally {
                        markPostCompleted()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                diagnostics.log("[诊断] timings 兜底异常 ${error.javaClass.simpleName}: ${error.message.orEmpty().take(60)}")
                null
            }

            // ── 已验证可用时优先路径 B (WebView fetch) ──
            if (preferWebViewTimings && fallbackTransport != null) {
                val ready = fallbackTransport.isReady()
                val preferred = if (ready) sendViaFallback(fallbackTransport, cachedCsrfToken) else null
                ensureReadingActive()
                diagnostics.log(
                    "[诊断] timings 优先路径 B ready=$ready status=${preferred?.status ?: "未发出"} challenged=${preferred?.challenged}" +
                        " body=${preferred?.let { NetDiagnostics.bodyFeatures(it.body) } ?: "-"}",
                )
                when {
                    preferred == null -> Unit
                    preferred.status == 429 -> {
                        val cooldown = enterRateLimitCooldown("/topics/timings", preferred.retryAfterSeconds)
                        timingsCooldownUntil = SystemClock.elapsedRealtime() + cooldown.retryAfterMs
                        return false
                    }
                    preferred.status in 200..299 -> {
                        markTimingsSynced()
                        recordTopicPostsReported(currentUserId, topicId, numbers)
                        diagnostics.log("阅读进度已同步(${fallbackTransport.name}): 话题 $topicId, ${numbers.size} 层(至 #${numbers.last()}), ${topicTime / 1000}s")
                        return true
                    }
                    preferred.challenged -> {
                        // B 也被挑战：WebView 侧凭据同样失效，回到 A 并请用户重新过盾
                        preferWebViewTimings = false
                        timingsCooldownUntil = SystemClock.elapsedRealtime() + 180_000L
                        diagnostics.log("阅读进度上报未成功(${fallbackTransport.name} 遭 CF 挑战)，进入 3 分钟熔断冷却，等待用户手工验证")
                        notify403OrChallenged("/topics/timings", "timings B CF挑战")
                        onTimingsVerificationRequired?.invoke()
                        return false
                    }
                    else -> Unit
                }
            }

            // ── 尝试路径 A (OkHttp) ──
            val csrfStartedAt = SystemClock.elapsedRealtime()
            val primaryCsrf = cachedCsrf()
            val csrfElapsedMs = SystemClock.elapsedRealtime() - csrfStartedAt
            if (primaryCsrf.isNullOrBlank()) {
                diagnostics.log("阅读进度未上报: CSRF Token 缺失 (hasCsrf=false)")
                return false
            }

            val queuedAt = SystemClock.elapsedRealtime()
            var requestStartedAt = 0L
            val primaryResult = try {
                postGate.withLock {
                    ensureReadingActive()
                    if (isRateLimitCoolingDown()) return@withLock null
                    throttlePost()
                    ensureReadingActive()
                    if (isRateLimitCoolingDown()) return@withLock null
                    requestStartedAt = SystemClock.elapsedRealtime()
                    try {
                        primaryTransport.write("/topics/timings", "POST", form, primaryCsrf, Transport.FORM_CONTENT_TYPE, present = false)
                    } finally {
                        markPostCompleted()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("阅读进度上报异常(已忽略): ${error.javaClass.simpleName} ${error.message.orEmpty().take(80)}")
                return false
            }

            ensureReadingActive()
            if (requestStartedAt > 0L) diagnostics.log(
                "阅读同步耗时: 话题 $topicId, CSRF ${csrfElapsedMs}ms, " +
                    "排队 ${requestStartedAt - queuedAt}ms, POST ${SystemClock.elapsedRealtime() - requestStartedAt}ms",
            )
            if (primaryResult == null) {
                diagnostics.log("[诊断] 阅读进度跳过: 发送前进入 429 冷却")
                return false
            }

            diagnostics.recordStatus(primaryTransport.name, primaryResult.status, "/topics/timings")
            if (primaryResult.status !in 200..299) {
                diagnostics.log(
                    "[诊断] timings 主路径失败 status=${primaryResult.status} challenged=${primaryResult.challenged}" +
                        " body=${NetDiagnostics.bodyFeatures(primaryResult.body)} 话题=$topicId 楼层=${numbers.size}" +
                        " hasFallback=${fallbackTransport != null}",
                )
                logTimingsCookieInventory("timings主路径${primaryResult.status}")
            }

            if (primaryResult.status == 429) {
                val cooldown = enterRateLimitCooldown("/topics/timings", primaryResult.retryAfterSeconds)
                timingsCooldownUntil = SystemClock.elapsedRealtime() + cooldown.retryAfterMs
                return false
            }

            if (primaryResult.status in 200..299) {
                markTimingsSynced()
                recordTopicPostsReported(currentUserId, topicId, numbers)
                diagnostics.log("阅读进度已同步(路径 ${primaryTransport.name}): 话题 $topicId, ${numbers.size} 层(至 #${numbers.last()}), ${topicTime / 1000}s, hasCsrf=${primaryCsrf.isNotBlank()}")
                return true
            }

            // 遇到 BAD CSRF 时（精确收紧匹配：非 CF 挑战且服务端返回 BAD CSRF），仅轻度校准 _forum_session 并拉新 Token 重试
            val isBadCsrf = primaryResult.status == 403 && !primaryResult.challenged && primaryResult.body.contains("BAD CSRF", ignoreCase = true)
            if (isBadCsrf) {
                diagnostics.log("阅读进度遇到 BAD CSRF，执行轻度会话校准并重试")
                cachedCsrfToken = null
                sessionStore?.calibrateForumSessionForBadCsrf()
                val newCsrf = runCatching { obtainCsrf(forceRefresh = true) }.getOrNull()
                ensureReadingActive()
                if (newCsrf != null) {
                    val retryResult = try {
                        postGate.withLock {
                            if (isRateLimitCoolingDown()) return@withLock null
                            throttlePost(burst = true)
                            ensureReadingActive()
                            try {
                                primaryTransport.write("/topics/timings", "POST", form, newCsrf, Transport.FORM_CONTENT_TYPE, present = false)
                            } finally {
                                markPostCompleted()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                    ensureReadingActive()
                    diagnostics.log(
                        "[诊断] timings BAD CSRF 重试结果 status=${retryResult?.status ?: "未发出"} challenged=${retryResult?.challenged}" +
                            " body=${retryResult?.let { NetDiagnostics.bodyFeatures(it.body) } ?: "-"}",
                    )
                    if (retryResult != null && retryResult.status in 200..299) {
                        markTimingsSynced()
                        recordTopicPostsReported(currentUserId, topicId, numbers)
                        diagnostics.log("阅读进度已同步(BAD CSRF 校准重试成功): 话题 $topicId, ${numbers.size} 层, hasCsrf=${newCsrf.isNotBlank()}")
                        return true
                    }
                }
            }

            // 仅当确认遭遇 Cloudflare 挑战拦截时，才转交路径 B (WebView fetch) 进行兜底
            if (primaryResult.challenged && fallbackTransport != null) {
                if (isRateLimitCoolingDown()) return false
                diagnostics.log("阅读进度遭遇 Cloudflare 挑战，转交 ${fallbackTransport.name} 走原生 Chromium 网络栈兜底")
                val fallbackHandled = kotlinx.coroutines.coroutineScope {
                    ensureReadingActive()
                    val alreadyReady = fallbackTransport.isReady()
                    val fallbackReady = alreadyReady || fallbackTransport.revalidate("timings 挑战兜底")
                    ensureReadingActive()
                    diagnostics.log("[诊断] timings 兜底就绪 alreadyReady=$alreadyReady ready=$fallbackReady")
                    if (fallbackReady) {
                        val fallbackResult = sendViaFallback(fallbackTransport, primaryCsrf)
                        ensureReadingActive()
                        diagnostics.log(
                            "[诊断] timings 兜底结果 status=${fallbackResult?.status ?: "未发出"} challenged=${fallbackResult?.challenged}" +
                                " body=${fallbackResult?.let { NetDiagnostics.bodyFeatures(it.body) } ?: "-"}",
                        )
                        if (fallbackResult != null && fallbackResult.status == 429) {
                            val cooldown = enterRateLimitCooldown("/topics/timings", fallbackResult.retryAfterSeconds)
                            timingsCooldownUntil = SystemClock.elapsedRealtime() + cooldown.retryAfterMs
                            return@coroutineScope false
                        }
                        if (fallbackResult != null && fallbackResult.status in 200..299) {
                            timingsCooldownUntil = 0L
                            markTimingsSynced()
                            preferWebViewTimings = true
                            recordTopicPostsReported(currentUserId, topicId, numbers)
                            diagnostics.log("阅读进度已同步(${fallbackTransport.name} 兜底成功): 话题 $topicId, ${numbers.size} 层, ${topicTime / 1000}s")
                            return@coroutineScope true
                        }
                    }
                    null
                }
                if (fallbackHandled != null) {
                    return fallbackHandled
                }
            }

            // 遇到失败（403/挑战/超时等），进入 3 分钟熔断冷却，期间不再发送阅读心跳，杜绝任何刷屏或阻塞
            timingsCooldownUntil = SystemClock.elapsedRealtime() + 180_000L
            val reason = if (isBadCsrf) "BAD CSRF" else if (primaryResult.challenged) "CF挑战" else "HTTP ${primaryResult.status}"
            diagnostics.log("阅读进度上报未成功($reason)，进入 3 分钟熔断冷却，静默放弃本次同步")
            // 仅对确认的 CF 挑战拦截记入 403/挑战事件，避免误伤
            if (primaryResult.challenged) {
                notify403OrChallenged("/topics/timings", "timings CF挑战")
                onTimingsVerificationRequired?.invoke()
            }
            return false
        } catch (cancelled: CancellationException) {
            diagnostics.log("[诊断] 阅读进度上报协程被取消: 话题 $topicId (${cancelled.message.orEmpty().take(40)})")
            throw cancelled
        } finally {
            inFlightTimingsTopics.remove(topicId, requestMarker)
        }
    }

    private suspend fun logTimingsCookieInventory(stage: String) {
        val store = sessionStore ?: return
        val inventory = withContext(Dispatchers.Main) {
            runCatching { store.cookieInventory() }.getOrElse { "读取失败: ${it.javaClass.simpleName}" }
        }
        diagnostics.log("[诊断] Cookie清单($stage): $inventory")
    }

    private suspend fun obtainCsrf(forceRefresh: Boolean = false): String = csrfGate.withLock {
        if (!forceRefresh) {
            cachedCsrfToken?.takeIf { it.isNotBlank() }?.let { return@withLock it }
        }
        val generation = sessionStore?.sessionGeneration
        val token = objectRequest("/session/csrf.json").text("csrf").also { require(it.isNotBlank()) }
        if (generation != sessionStore?.sessionGeneration) throw CancellationException("会话已切换")
        cachedCsrfToken = token
        token
    }

    private suspend fun cachedCsrf(): String? = try { obtainCsrf() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: NeedsInteractiveVerification) {
            timingsCooldownUntil = SystemClock.elapsedRealtime() + 180_000L
            onTimingsVerificationRequired?.invoke()
            null
        }
        catch (error: HttpStatusException) {
            if (error.status == 403) timingsCooldownUntil = SystemClock.elapsedRealtime() + 180_000L
            null
        }
        catch (_: Exception) { null }

    private suspend fun getPollingConfiguration(generation: Long): MessageBusConfiguration = pollingConfigGate.withLock {
        if (pollingConfigGeneration != generation) {
            pollingConfigGeneration = generation
            pollingConfig = null
            nextPollingConfigAttemptAt = 0L
        }
        pollingConfig?.let { return@withLock it }
        val now = SystemClock.elapsedRealtime()
        if (now < nextPollingConfigAttemptAt) {
            throw HttpStatusException(503, "轮询配置读取正在退避", (nextPollingConfigAttemptAt - now + 999L) / 1_000L)
        }
        nextPollingConfigAttemptAt = now + 120_000L
        // 绝对地址让传输层请求 HTML；每个会话只取一次，不逐话题抓取首页。
        val config = try {
            val html = request(Config.BASE_URL + "/", present = false, retryAllowed = false)
            withContext(Dispatchers.Default) { parseMessageBusConfiguration(html) }
        } catch (cancelled: CancellationException) {
            nextPollingConfigAttemptAt = SystemClock.elapsedRealtime() + 3_000L
            throw cancelled
        } catch (error: Exception) {
            diagnostics.log("话题订阅配置未就绪，120秒后可重试(${error.javaClass.simpleName})")
            throw error
        }
        ensureRequestAllowed()
        if (generation != sessionStore?.sessionGeneration) throw CancellationException("会话已切换")
        pollingConfig = config
        nextPollingConfigAttemptAt = 0L
        csrfGate.withLock {
            if (generation != sessionStore?.sessionGeneration) throw CancellationException("会话已切换")
            if (cachedCsrfToken.isNullOrBlank()) cachedCsrfToken = config.csrfToken
        }
        diagnostics.log("话题订阅使用站点配置: ${config.baseUrl.host}, 共享认证=${!config.sharedSessionKey.isNullOrBlank()}")
        config
    }

    suspend fun fetchPosts(topicId: Long, ids: List<Long>, present: Boolean = true): List<Post> {
        require(ids.size in 1..20)
        val query = ids.joinToString("&") { "post_ids%5B%5D=$it" }
        val body = request("/t/$topicId/posts.json?$query&track_visit=false",
            present = present, retryAllowed = present)
        return withContext(Dispatchers.Default) {
            json.decodeFromString<PostStreamResponse>(body).postStream.posts
        }
    }

    /** MessageBus 的 Dont-Chunk 长轮询；不占用整个后台 GET 闸门等待服务端消息。 */
    suspend fun pollTopicMessages(topicId: Long, clientId: String, cursor: Long, sequence: Long): List<TopicBusMessage> =
        topicPollGate.withLock {
            val generation = sessionStore?.sessionGeneration ?: return@withLock emptyList()
            val path = "/message-bus/$clientId/poll"
            val config = getPollingConfiguration(generation)
            backgroundGetGate.withLock {
                val wait = Config.MIN_REQUEST_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastBackgroundGetAt)
                if (wait > 0L) delay(wait)
                ensureRequestAllowed()
                throwIfRateLimitCoolingDown()
                if (isSilentPrefetchBackingOff()) throw NeedsInteractiveVerification("话题订阅正在退避")
                acquireGetSlot { isRateLimitCoolingDown() }
                currentCoroutineContext().ensureActive()
                lastBackgroundGetAt = SystemClock.elapsedRealtime()
            }
            val sharedClient = requireNotNull(httpClientProvider?.invoke())
            val client = sharedClient.newBuilder()
                .readTimeout(70, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .build()
            val request = Request.Builder()
                .url(config.pollUrl(clientId))
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Dont-Chunk", "true")
                .header("Discourse-Background", "true")
                .header("X-SILENCE-LOGGER", "true")
                .header("Origin", Config.BASE_URL)
                .header("Referer", "${Config.BASE_URL}/t/$topicId")
                .tag(ActiveSessionRequest::class.java, ActiveSessionRequest(generation, sendCookies = config.sendCookies))
                .apply {
                    config.sharedSessionKey?.let { header("X-Shared-Session-Key", it) }
                    sessionStore.userAgentOrNull()?.let { header("User-Agent", it) }
                }
                .post(FormBody.Builder().add("/topic/$topicId", cursor.toString())
                    .add("__seq", sequence.toString()).build())
                .build()
            val result = suspendCancellableCoroutine<TransportResult> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { incoming ->
                                val body = incoming.body?.string().orEmpty()
                                val result = TransportResult(
                                    status = incoming.code,
                                    body = body,
                                    challenged = ChallengeDetector.isChallenge(incoming.code, incoming.header("cf-mitigated"), body),
                                    retryAfterSeconds = HttpHeaderUtils.extractRetryAfterSeconds(
                                        incoming.header("Retry-After"), incoming.header("x-ratelimit-reset"), body),
                                )
                                if (continuation.isActive) continuation.resumeWith(Result.success(result))
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }
                    }
                })
            }
            if (generation != sessionStore.sessionGeneration || sessionStore.sessionChanging) {
                throw CancellationException("会话已切换")
            }
            if (result.status in setOf(401, 403, 404)) {
                pollingConfig = null
                nextPollingConfigAttemptAt = SystemClock.elapsedRealtime() + 120_000L
            }
            when {
                result.status == 429 -> {
                    if (result.challenged) enterSilentPrefetchBackoff(NeedsInteractiveVerification("话题订阅被挑战拦截"))
                    throw enterRateLimitCooldown(path, maxOf(if (result.challenged) 120L else 15L, result.retryAfterSeconds ?: 0L),
                        cfChallenge = result.challenged)
                }
                result.challenged || result.status == 403 -> {
                    val error = NeedsInteractiveVerification("话题订阅被拦截，停止自动请求")
                    enterSilentPrefetchBackoff(error)
                    throw error
                }
                result.status !in 200..299 -> throw HttpStatusException(result.status, "话题订阅失败", result.retryAfterSeconds)
                else -> json.decodeFromString<List<TopicBusMessage>>(result.body)
            }
        }

    suspend fun categoryById(id: Int?): Category? = id?.let { categories()[it] }

    suspend fun fetchReplies(postId: Long, after: Int): List<Post> {
        val body = request("/posts/$postId/replies.json?after=$after")
        return withContext(Dispatchers.Default) {
            val root = json.parseToJsonElement(body)
            val array = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["post_stream"] as? JsonObject)?.get("posts") as? JsonArray
                    ?: root["replies"] as? JsonArray
                    ?: root["posts"] as? JsonArray
                else -> null
            } ?: return@withContext emptyList()
            array.map { json.decodeFromJsonElement(Post.serializer(), it) }
        }
    }

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

    suspend fun fetchBoost(boostId: Long): Boost =
        json.decodeFromString(objectRequest("/discourse-boosts/boosts/$boostId").toString())

    suspend fun flagBoost(boostId: Long, flagTypeId: Int, message: String? = null) {
        val body = buildString {
            append("flag_type_id=").append(flagTypeId)
            message?.takeIf { it.isNotBlank() }?.let { append("&message=").append(encode(it.trim())) }
        }
        mutate(
            "/discourse-boosts/boosts/$boostId/flags",
            "POST",
            body,
            Transport.FORM_CONTENT_TYPE,
        )
    }

    suspend fun deleteBoost(boostId: Long) { mutate("/discourse-boosts/boosts/$boostId.json", "DELETE") }

    suspend fun createPrivateMessage(targetUsernames: List<String>, title: String, raw: String): Long {
        require(targetUsernames.isNotEmpty() && title.isNotBlank() && raw.isNotBlank())
        val body = buildString {
            append("title=").append(encode(title.trim()))
            append("&raw=").append(encode(raw.trim()))
            append("&archetype=private_message")
            append("&target_recipients=").append(encode(targetUsernames.distinct().joinToString(",")))
        }
        val response = json.parseToJsonElement(
            mutate("/posts.json", "POST", body, Transport.FORM_CONTENT_TYPE),
        ).jsonObject
        return response["topic_id"]?.jsonPrimitive?.longOrNull
            ?: response["id"]?.jsonPrimitive?.longOrNull
            ?: error("私信响应缺少话题 ID")
    }

    private val userCardCache = java.util.concurrent.ConcurrentHashMap<String, UserCardData>()

    fun getCachedUserCard(username: String): UserCardData? {
        val key = username.lowercase().trim().removePrefix("@")
        return userCardCache[key]
    }

    fun clearUserCardCache() {
        userCardCache.clear()
    }

    /**
     * 多源深层探测动图头像：
     * 1. 全局 AvatarCache 本地高速缓存；
     * 2. /u/search/users.json?term={username}（Discourse 的 BasicUserSerializer 包含 animated_avatar）；
     * 3. /user_actions.json?username={username}&offset=0&limit=1（动态历史中提取 acting_user_animated_avatar）；
     * 4. /u/{username}.json（用户公开资料根对象）。
     * 成功探测后自动录入 AvatarCache 全局单例。
     */
    suspend fun resolveAnimatedAvatar(username: String): String? {
        val cleanUser = username.trim().removePrefix("@")
        if (cleanUser.isBlank()) return null
        val cached = AvatarCache.get(cleanUser)
        if (!cached.isNullOrBlank() && isAnimatedAvatarPath(cached)) {
            return cached
        }

        // 1. /u/search/users.json?term= (精准匹配 BasicUser)
        val fromSearch = runCatching {
            val searchJson = objectRequest("/u/search/users.json?term=${encode(cleanUser)}")
            val users = searchJson.objects("users")
            val matched = users.firstOrNull { it.text("username").equals(cleanUser, ignoreCase = true) }
            matched?.text("animated_avatar")?.takeIf(::isAnimatedAvatarPath)
        }.getOrNull()
        if (!fromSearch.isNullOrBlank()) {
            AvatarCache.record(cleanUser, fromSearch)
            return fromSearch
        }

        // 2. /user_actions.json
        val fromActions = runCatching {
            val acts = objectRequest("/user_actions.json?username=${encode(cleanUser)}&offset=0&limit=1").objects("user_actions")
            val firstAct = acts.firstOrNull()
            val candidate = firstAct?.text("acting_user_animated_avatar")?.ifBlank { null }
                ?: firstAct?.text("animated_avatar")?.ifBlank { null }
            candidate?.takeIf(::isAnimatedAvatarPath)
        }.getOrNull()
        if (!fromActions.isNullOrBlank()) {
            AvatarCache.record(cleanUser, fromActions)
            return fromActions
        }

        // 3. /u/{username}.json
        val fromUser = runCatching {
            val uJson = objectRequest("/u/${encode(cleanUser)}.json")
            val uUser = uJson["user"] as? JsonObject ?: uJson
            val candidate = uUser.text("animated_avatar")
            candidate.takeIf(::isAnimatedAvatarPath)
        }.getOrNull()
        if (!fromUser.isNullOrBlank()) {
            AvatarCache.record(cleanUser, fromUser)
            return fromUser
        }

        // 三条路径均未找到动图，写入负缓存，避免后续重复探测
        AvatarCache.recordNonAnimated(cleanUser)
        return null
    }

    data class UserCardStats(
        val topicCount: Int = 0,
        val postCount: Int = 0,
        val likesReceived: Int = 0,
        val badges: List<SummaryBadgeItem> = emptyList(),
        val animatedAvatar: String? = null,
        val isFollowed: Boolean? = null,
    )

    fun saveCachedUserCard(card: UserCardData) {
        val key = card.username.lowercase().trim().removePrefix("@")
        userCardCache[key] = card
    }

    /**
     * 用户资料卡。对齐 fluxdo：请求轻量的 /u/{username}/card.json，配合 burst 模式快速返回，
     * 绝不同步阻塞动图探测，配合内存缓存与 AvatarCache 实现 0ms 秒开。
     */
    suspend fun fetchUserCard(username: String, forceRefresh: Boolean = false): UserCardData {
        val key = username.lowercase().trim().removePrefix("@")
        val existingCached = userCardCache[key]
        if (!forceRefresh && existingCached != null) {
            val fallbackAnim = AvatarCache.get(key) ?: AvatarCache.get(existingCached.username)
            if (existingCached.animatedAvatar.isNullOrBlank() && !fallbackAnim.isNullOrBlank() && isAnimatedAvatarPath(fallbackAnim)) {
                val patched = existingCached.copy(animatedAvatar = fallbackAnim)
                userCardCache[key] = patched
                return patched
            }
            return existingCached
        }
        val root = objectRequest("/u/${encode(username)}/card.json", burst = true)
        val user = root["user"] as? JsonObject ?: root
        val card = json.decodeFromJsonElement<UserCardData>(user)
        val isFollowedInJson = (user["is_followed"] as? JsonPrimitive)?.booleanOrNull
            ?: (user["followed"] as? JsonPrimitive)?.booleanOrNull
            ?: (user["following"] as? JsonPrimitive)?.booleanOrNull
            ?: card.following

        val candidateAnim = if (!card.animatedAvatar.isNullOrBlank() && isAnimatedAvatarPath(card.animatedAvatar)) {
            card.animatedAvatar.also {
                AvatarCache.record(card.username, it)
                AvatarCache.record(username, it)
            }
        } else {
            AvatarCache.get(card.username) ?: AvatarCache.get(username)
        }
        // 若之前已有缓存的统计数据，保留之，防止刷新时指标闪烁归零
        val resolved = card.copy(
            animatedAvatar = candidateAnim,
            following = isFollowedInJson,
            topicCount = if (card.topicCount > 0) card.topicCount else (existingCached?.topicCount ?: 0),
            postCount = if (card.postCount > 0) card.postCount else (existingCached?.postCount ?: 0),
            likesReceived = if (card.likesReceived > 0) card.likesReceived else (existingCached?.likesReceived ?: 0),
            badges = if (card.badges.isNotEmpty()) card.badges else (existingCached?.badges ?: emptyList()),
        )
        userCardCache[key] = resolved
        return resolved
    }

    /**
     * 快速获取用户资料卡统计指标 (话题数、帖子数、获赞数、徽章与动图头像)
     * 优先通过 /u/{username}.json 一次性拉取 user_stat，避免 summary.json 的复杂耗时查询。
     */
    suspend fun fetchUserCardStats(username: String): UserCardStats {
        val cleanUser = username.trim().removePrefix("@")
        val userPath = encode(cleanUser)
        return try {
            val raw = objectRequest("/u/$userPath.json", burst = true)
            val userObj = raw["user"] as? JsonObject ?: raw
            val statObj = userObj["user_stat"] as? JsonObject
            val badgesList = userObj.objects("badges").ifEmpty { raw.objects("badges") }.map { b ->
                SummaryBadgeItem(
                    id = b.number("id") ?: 0L,
                    name = b.text("name"),
                    description = b.text("description").ifBlank { null },
                    icon = b.text("icon").ifBlank { null },
                    badgeTypeId = b.number("badge_type_id")?.toInt() ?: 0,
                )
            }
            val anim = userObj.text("animated_avatar").takeIf(::isAnimatedAvatarPath)
            if (!anim.isNullOrBlank()) {
                AvatarCache.record(cleanUser, anim)
            }
            val isFollowed = (userObj["is_followed"] as? JsonPrimitive)?.booleanOrNull
                ?: (userObj["followed"] as? JsonPrimitive)?.booleanOrNull
                ?: (userObj["following"] as? JsonPrimitive)?.booleanOrNull

            val topicCount = statObj?.number("topic_count")?.toInt() ?: 0
            val postCount = statObj?.number("post_count")?.toInt() ?: 0
            val likesReceived = statObj?.number("likes_received")?.toInt() ?: 0

            if (topicCount == 0 && postCount == 0 && likesReceived == 0) {
                val sumRaw = runCatching { objectRequest("/u/$userPath/summary.json", burst = true) }.getOrNull()
                val sumObj = sumRaw?.get("user_summary") as? JsonObject
                if (sumObj != null) {
                    val sumBadges = sumRaw.objects("badges").map { b ->
                        SummaryBadgeItem(
                            id = b.number("id") ?: 0L,
                            name = b.text("name"),
                            description = b.text("description").ifBlank { null },
                            icon = b.text("icon").ifBlank { null },
                            badgeTypeId = b.number("badge_type_id")?.toInt() ?: 0,
                        )
                    }
                    return UserCardStats(
                        topicCount = sumObj.number("topic_count")?.toInt() ?: 0,
                        postCount = sumObj.number("post_count")?.toInt() ?: 0,
                        likesReceived = sumObj.number("likes_received")?.toInt() ?: 0,
                        badges = if (badgesList.isNotEmpty()) badgesList else sumBadges,
                        animatedAvatar = anim,
                        isFollowed = isFollowed,
                    )
                }
            }

            UserCardStats(
                topicCount = topicCount,
                postCount = postCount,
                likesReceived = likesReceived,
                badges = badgesList,
                animatedAvatar = anim,
                isFollowed = isFollowed,
            )
        } catch (_: Exception) {
            val sumRaw = objectRequest("/u/$userPath/summary.json", burst = true)
            val sumObj = sumRaw["user_summary"] as? JsonObject ?: JsonObject(emptyMap())
            val badgesList = sumRaw.objects("badges").map { b ->
                SummaryBadgeItem(
                    id = b.number("id") ?: 0L,
                    name = b.text("name"),
                    description = b.text("description").ifBlank { null },
                    icon = b.text("icon").ifBlank { null },
                    badgeTypeId = b.number("badge_type_id")?.toInt() ?: 0,
                )
            }
            UserCardStats(
                topicCount = sumObj.number("topic_count")?.toInt() ?: 0,
                postCount = sumObj.number("post_count")?.toInt() ?: 0,
                likesReceived = sumObj.number("likes_received")?.toInt() ?: 0,
                badges = badgesList,
            )
        }
    }

    /**
     * UserCardSerializer 不保证返回 animated_avatar。资料页已完成首屏展示后，
     * 再从完整用户资料补齐该字段，避免为每张资料卡引入同步等待。
     */
    suspend fun enrichUserCardAvatar(username: String, card: UserCardData): UserCardData {
        val cachedAnim = AvatarCache.get(username) ?: AvatarCache.get(card.username)
        if (!cachedAnim.isNullOrBlank() && isAnimatedAvatarPath(cachedAnim)) {
            val resolved = card.copy(animatedAvatar = cachedAnim)
            userCardCache[username.lowercase().trim().removePrefix("@")] = resolved
            return resolved
        }
        val finalAnim = resolveAnimatedAvatar(username) ?: resolveAnimatedAvatar(card.username)
        val resolved = card.copy(
            animatedAvatar = finalAnim ?: card.animatedAvatar,
        )
        val key = username.lowercase().trim().removePrefix("@")
        val old = userCardCache[key]
        val preserved = resolved.copy(
            topicCount = if (resolved.topicCount > 0) resolved.topicCount else (old?.topicCount ?: 0),
            postCount = if (resolved.postCount > 0) resolved.postCount else (old?.postCount ?: 0),
            likesReceived = if (resolved.likesReceived > 0) resolved.likesReceived else (old?.likesReceived ?: 0),
            badges = if (resolved.badges.isNotEmpty()) resolved.badges else (old?.badges ?: emptyList()),
        )
        userCardCache[key] = preserved
        return preserved
    }

    suspend fun setFollow(username: String, following: Boolean) {
        val method = if (following) "PUT" else "DELETE"
        mutate("/follow/${encode(username)}", method)
    }

    private val followingCache = java.util.concurrent.ConcurrentHashMap<String, List<FollowUser>>()

    fun getCachedFollowing(username: String): List<FollowUser>? {
        val key = username.lowercase().trim().removePrefix("@")
        followingCache[key]?.let { return it }
        val raw = sessionStore?.getString("following_users_$key") ?: return null
        return runCatching { json.decodeFromString<List<FollowUser>>(raw) }.getOrNull()?.also {
            followingCache[key] = it
        }
    }

    fun saveCachedFollowing(username: String, users: List<FollowUser>) {
        val key = username.lowercase().trim().removePrefix("@")
        followingCache[key] = users
        runCatching {
            sessionStore?.putString("following_users_$key", json.encodeToString(users))
        }
    }

    suspend fun fetchFollowing(username: String): List<FollowUser> {
        val list = fetchFollowList(username, "following")
        saveCachedFollowing(username, list)
        return list
    }

    private suspend fun fetchFollowList(username: String, listName: String): List<FollowUser> {
        val result = json.parseToJsonElement(request("/u/${encode(username)}/follow/$listName")) as? JsonArray
            ?: return emptyList()
        return result.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<FollowUser>(element) }.getOrNull()
        }
    }

    fun getBlockedUsers(userId: Long = sessionStore?.currentUserId() ?: 0L): List<BlockedUser> {
        val store = sessionStore ?: return emptyList()
        val key = blockedUsersKey(userId)
        val raw = store.getString(key) ?: migrateLegacyBlockedUsers(store, userId) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BlockedUser>>(raw) }.getOrDefault(emptyList())
    }

    fun saveBlockedUsers(users: List<BlockedUser>, userId: Long = sessionStore?.currentUserId() ?: 0L) {
        runCatching {
            sessionStore?.putString(blockedUsersKey(userId), json.encodeToString(users))
        }
    }

    private fun blockedUsersKey(userId: Long): String =
        if (userId > 0L) "${KEY_BLOCKED_USERS}_$userId" else KEY_BLOCKED_USERS

    private fun migrateLegacyBlockedUsers(store: SessionStore, userId: Long): String? {
        if (userId <= 0L) return null
        val legacy = store.getString(KEY_BLOCKED_USERS)?.takeIf { it.isNotBlank() } ?: return null
        store.putString(blockedUsersKey(userId), legacy)
        store.removeString(KEY_BLOCKED_USERS)
        return legacy
    }

    private val userBadgesCache = java.util.concurrent.ConcurrentHashMap<String, List<UserBadgeItem>>()

    fun getCachedUserBadges(username: String): List<UserBadgeItem>? {
        val key = username.lowercase().trim().removePrefix("@")
        userBadgesCache[key]?.let { return it }
        val raw = sessionStore?.getString("user_badges_$key") ?: return null
        return runCatching { json.decodeFromString<List<UserBadgeItem>>(raw) }.getOrNull()?.also {
            userBadgesCache[key] = it
        }
    }

    fun saveCachedUserBadges(username: String, badges: List<UserBadgeItem>) {
        val key = username.lowercase().trim().removePrefix("@")
        userBadgesCache[key] = badges
        runCatching {
            sessionStore?.putString("user_badges_$key", json.encodeToString(badges))
        }
    }

    private val trustReportMemoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<TrustReport, Long>>()
    private val ldcUserInfoMemoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<LdcUserInfo, Long>>()

    fun getCachedTrustReport(username: String): Pair<TrustReport, Long>? {
        val key = username.lowercase().trim().removePrefix("@")
        trustReportMemoryCache[key]?.let { return it }
        val raw = sessionStore?.getString("trust_report_$key") ?: return null
        val time = sessionStore?.getString("trust_report_time_$key")?.toLongOrNull() ?: 0L
        return runCatching { json.decodeFromString<TrustReport>(raw) }.getOrNull()?.let { report ->
            (report to time).also { trustReportMemoryCache[key] = it }
        }
    }

    fun saveCachedTrustReport(username: String, report: TrustReport) {
        val key = username.lowercase().trim().removePrefix("@")
        val now = System.currentTimeMillis()
        trustReportMemoryCache[key] = report to now
        runCatching {
            sessionStore?.putString("trust_report_$key", json.encodeToString(report))
            sessionStore?.putString("trust_report_time_$key", now.toString())
        }
    }

    fun getCachedLdcUserInfo(username: String): Pair<LdcUserInfo, Long>? {
        val key = username.lowercase().trim().removePrefix("@")
        ldcUserInfoMemoryCache[key]?.let { return it }
        val raw = sessionStore?.getString("ldc_user_info_$key") ?: return null
        val time = sessionStore?.getString("ldc_user_info_time_$key")?.toLongOrNull() ?: 0L
        return runCatching { json.decodeFromString<LdcUserInfo>(raw) }.getOrNull()?.let { info ->
            (info to time).also { ldcUserInfoMemoryCache[key] = it }
        }
    }

    fun saveCachedLdcUserInfo(username: String, info: LdcUserInfo) {
        val key = username.lowercase().trim().removePrefix("@")
        val now = System.currentTimeMillis()
        ldcUserInfoMemoryCache[key] = info to now
        runCatching {
            sessionStore?.putString("ldc_user_info_$key", json.encodeToString(info))
            sessionStore?.putString("ldc_user_info_time_$key", now.toString())
        }
    }

    private val cdkScoreMemoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    fun getCachedCdkScore(username: String): Pair<Int, Long>? {
        val key = username.lowercase().trim().removePrefix("@")
        cdkScoreMemoryCache[key]?.let { return it }
        val score = sessionStore?.getString("cdk_score_$key")?.toIntOrNull() ?: return null
        val time = sessionStore?.getString("cdk_score_time_$key")?.toLongOrNull() ?: 0L
        return (score to time).also { cdkScoreMemoryCache[key] = it }
    }

    fun saveCachedCdkScore(username: String, score: Int) {
        val key = username.lowercase().trim().removePrefix("@")
        val now = System.currentTimeMillis()
        cdkScoreMemoryCache[key] = score to now
        runCatching {
            sessionStore?.putString("cdk_score_$key", score.toString())
            sessionStore?.putString("cdk_score_time_$key", now.toString())
        }
    }

    suspend fun fetchChatChannels(): List<ChatChannelSummary> {
        val root = objectRequest("/chat/api/me/channels")
        val tracking = ((root["tracking"] as? JsonObject)?.get("channel_tracking") as? JsonObject)
            ?: JsonObject(emptyMap())
        return (root.objects("public_channels") + root.objects("direct_message_channels"))
            .mapNotNull { channel ->
                val id = channel.number("id") ?: return@mapNotNull null
                val chatable = channel["chatable"] as? JsonObject
                val rawLastMessage = channel["last_message"] as? JsonObject
                val lastMessage = rawLastMessage
                    ?.takeIf { it.number("id") != null }
                    ?.let { message ->
                        val user = message["user"] as? JsonObject
                        ChatMessageSummary(
                            id = message.number("id") ?: return@let null,
                            channelId = id,
                            message = message.text("message"),
                            cooked = message.text("cooked"),
                            createdAt = message.text("created_at").ifBlank { null },
                            user = user?.let { rawUser ->
                                ChatUserSummary(
                                    username = rawUser.text("username"),
                                    name = rawUser.text("name").ifBlank { null },
                                    avatarTemplate = rawUser.text("avatar_template").ifBlank { null },
                                )
                            },
                        )
                    }
                val member = channel["current_user_membership"] as? JsonObject
                val channelTracking = tracking[id.toString()] as? JsonObject
                val dmUsers = chatable?.objects("users").orEmpty().mapNotNull { rawUser ->
                    val username = rawUser.text("username")
                    if (username.isBlank()) null
                    else ChatUserSummary(
                        username = username,
                        name = rawUser.text("name").ifBlank { null },
                        avatarTemplate = rawUser.text("avatar_template").ifBlank { null },
                    )
                }
                ChatChannelSummary(
                    id = id,
                    title = channel.text("unicode_title").ifBlank { channel.text("title") },
                    chatableType = channel.text("chatable_type"),
                    categoryName = chatable?.text("name"),
                    unreadCount = channelTracking?.text("unread_count")?.toIntOrNull() ?: 0,
                    lastMessage = lastMessage,
                    membership = member?.let {
                        ChatMembership(
                            following = it["following"]?.jsonPrimitive?.booleanOrNull ?: false,
                            muted = it["muted"]?.jsonPrimitive?.booleanOrNull ?: false,
                            lastReadMessageId = it["last_read_message_id"]?.jsonPrimitive?.longOrNull,
                        )
                    },
                    dmUsers = dmUsers,
                )
            }
            .distinctBy { it.id }
            .sortedByDescending { it.lastMessage?.createdAt.orEmpty() }
    }

    suspend fun fetchChatMessages(
        channelId: Long,
        fromLastRead: Boolean = true,
        direction: String? = null,
        targetMessageId: Long? = null,
    ): ChatMessagesPage {
        val params = mutableListOf("page_size=50")
        if (direction != null) {
            params.add("direction=$direction")
        } else if (fromLastRead) {
            params.add("fetch_from_last_read=true")
        }
        if (targetMessageId != null) {
            params.add("target_message_id=$targetMessageId")
        }
        val body = request(
            "/chat/api/channels/$channelId/messages?" + params.joinToString("&"),
        )
        val root = json.parseToJsonElement(body).jsonObject
        val messages = root.arrayObjects("messages").mapNotNull { message ->
            runCatching {
                json.decodeFromJsonElement<ChatMessageSummary>(message).copy(channelId = channelId)
            }.getOrNull()
        }
        val meta = root["meta"] as? JsonObject
        val canLoadMorePast = meta?.get("can_load_more_past")?.jsonPrimitive?.booleanOrNull ?: false
        val canLoadMoreFuture = meta?.get("can_load_more_future")?.jsonPrimitive?.booleanOrNull ?: false
        return ChatMessagesPage(
            messages = messages,
            canLoadMorePast = canLoadMorePast,
            canLoadMoreFuture = canLoadMoreFuture,
        )
    }

    suspend fun markChatChannelRead(channelId: Long, messageId: Long?) {
        val query = messageId?.let { "?message_id=$it" }.orEmpty()
        mutate("/chat/api/channels/$channelId/read$query", "PUT")
    }

    suspend fun sendChatMessage(
        channelId: Long,
        message: String,
        uploadIds: List<Long> = emptyList(),
    ) {
        require(message.isNotBlank())
        val fields = mutableMapOf<String, JsonElement>(
            "message" to JsonPrimitive(message.trim())
        )
        if (uploadIds.isNotEmpty()) {
            fields["upload_ids"] = JsonArray(uploadIds.map { JsonPrimitive(it) })
        }
        val body = JsonObject(fields).toString()
        mutate("/chat/$channelId", "POST", body)
    }

    suspend fun createOrGetDirectMessageChannel(targetUsername: String): ChatChannelSummary {
        val body = JsonObject(
            mapOf(
                "target_usernames" to JsonArray(listOf(JsonPrimitive(targetUsername))),
                "upsert" to JsonPrimitive(true),
            )
        ).toString()
        val raw = mutate("/chat/api/direct-message-channels", "POST", body)
        val root = json.parseToJsonElement(raw).jsonObject
        val channelObj = (root["channel"] as? JsonObject) ?: (root["chat_channel"] as? JsonObject) ?: root
        val id = channelObj.number("id") ?: throw IllegalStateException("返回数据中未找到有效频道 ID")
        val chatable = channelObj["chatable"] as? JsonObject
        val dmUsers = chatable?.objects("users").orEmpty().mapNotNull { rawUser ->
            val username = rawUser.text("username")
            if (username.isBlank()) null
            else ChatUserSummary(
                username = username,
                name = rawUser.text("name").ifBlank { null },
                avatarTemplate = rawUser.text("avatar_template").ifBlank { null },
            )
        }
        return ChatChannelSummary(
            id = id,
            title = channelObj.text("unicode_title").ifBlank { channelObj.text("title").ifBlank { targetUsername } },
            chatableType = channelObj.text("chatable_type").ifBlank { "DirectMessage" },
            categoryName = null,
            unreadCount = 0,
            lastMessage = null,
            membership = null,
            dmUsers = dmUsers,
        )
    }

    /**
     * 发表回复。replyToPostNumber 为 null 时回复整个话题,否则回复指定楼层。
     * POST /posts.json 返回的是裸 post 对象或 enqueued 状态。
     */
    suspend fun createPost(
        topicId: Long,
        raw: String,
        replyToPostNumber: Int? = null,
        draftKey: String? = null,
    ): PostResult {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "回复内容不能为空" }
        val fields = mutableMapOf<String, JsonElement>(
            "raw" to JsonPrimitive(trimmed),
            "topic_id" to JsonPrimitive(topicId),
        )
        replyToPostNumber?.takeIf { it > 0 }?.let { fields["reply_to_post_number"] = JsonPrimitive(it) }
        draftKey?.takeIf { it.isNotBlank() }?.let { fields["draft_key"] = JsonPrimitive(it) }
        val responseText = mutate("/posts.json", "POST", JsonObject(fields).toString())
        return PostResult.decode(json.parseToJsonElement(responseText), json)
    }

    /**
     * 创建新话题。
     * POST /posts.json
     */
    suspend fun createTopic(
        title: String,
        raw: String,
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
        draftKey: String? = "new_topic",
    ): PostResult {
        val trimmedTitle = title.trim()
        val trimmedRaw = raw.trim()
        require(trimmedTitle.isNotBlank()) { "请输入话题标题" }
        require(trimmedRaw.isNotBlank()) { "请输入话题内容" }
        val fields = mutableMapOf<String, JsonElement>(
            "title" to JsonPrimitive(trimmedTitle),
            "raw" to JsonPrimitive(trimmedRaw),
            "archetype" to JsonPrimitive("regular"),
        )
        categoryId?.takeIf { it > 0 }?.let { fields["category"] = JsonPrimitive(it) }
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanTags.isNotEmpty()) {
            fields["tags"] = JsonArray(cleanTags.map { JsonPrimitive(it) })
        }
        draftKey?.takeIf { it.isNotBlank() }?.let { fields["draft_key"] = JsonPrimitive(it) }
        val responseText = mutate("/posts.json", "POST", JsonObject(fields).toString())
        return PostResult.decode(json.parseToJsonElement(responseText), json)
    }

    /**
     * 照搬 FluxDO (DiscourseService.searchTags) 搜索与过滤分类标签。
     * GET /tags/filter/search?q={query}&limit=8&categoryId={categoryId}&filterForInput=true
     */
    suspend fun searchTags(
        categoryId: Int?,
        query: String = "",
        selectedTags: List<String> = emptyList(),
        limit: Int = 8,
        filterForInput: Boolean = true,
    ): DiscourseTagSearchResult {
        val params = mutableListOf<String>()
        params.add("q=${encode(query)}")
        if (filterForInput) {
            params.add("filterForInput=true")
        }
        params.add("limit=$limit")
        if (categoryId != null && categoryId > 0) {
            params.add("categoryId=$categoryId")
        }
        for (tag in selectedTags) {
            if (tag.isNotBlank()) {
                params.add("selected_tags[]=${encode(tag)}")
            }
        }
        val path = "/tags/filter/search?${params.joinToString("&")}"
        return runCatching {
            val responseText = request(path)
            val root = json.parseToJsonElement(responseText) as? JsonObject
            val resultsArray = root?.get("results") as? JsonArray
            val results = resultsArray.orEmpty().mapNotNull { elem ->
                (elem as? JsonObject)?.let { obj ->
                    val name = obj.text("name")
                    val text = obj.text("text").ifBlank { name }
                    val count = obj.number("count")?.toInt() ?: 0
                    if (name.isNotBlank()) DiscourseTagInfo(name = name, text = text, count = count) else null
                }
            }
            val reqObj = root?.get("required_tag_group") as? JsonObject
            val reqName = reqObj?.text("name")
            val reqMin = reqObj?.number("min_count")?.toInt() ?: 1

            if (results.isNotEmpty()) {
                DiscourseTagSearchResult(results = results, requiredTagName = reqName, requiredTagMinCount = reqMin)
            } else {
                val cat = if (categoryId != null) categoryCache?.get(categoryId) else null
                val fallbackList = fixedTagsForCategory(cat)
                    .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                    .map { DiscourseTagInfo(name = it, text = it, count = 0) }
                DiscourseTagSearchResult(results = fallbackList)
            }
        }.getOrElse {
            val cat = if (categoryId != null) categoryCache?.get(categoryId) else null
            val fallbackList = fixedTagsForCategory(cat)
                .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                .map { DiscourseTagInfo(name = it, text = it, count = 0) }
            DiscourseTagSearchResult(results = fallbackList)
        }
    }

    suspend fun fetchCategoryTags(categoryId: Int?, query: String = "", limit: Int = 8): List<String> {
        return searchTags(categoryId, query, limit = limit).results.map { it.name }
    }

    /**
     * 拉取当前用户的草稿列表（与 PC 端 `/my/activity/drafts` 打通）。
     * GET /drafts.json
     */
    suspend fun fetchDrafts(burst: Boolean = false): List<DraftItem> {
        val root = objectRequest("/drafts.json?offset=0&limit=50", burst = burst)
        val draftObjs = root.objects("drafts")
        val known = categoryCache ?: categories()
        return draftObjs.mapNotNull { obj ->
            val draftKey = obj.text("draft_key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sequence = obj.text("sequence").toLongOrNull() ?: 0L
            val dataRaw = obj.text("data")
            val dataObj = runCatching {
                if (dataRaw.isNotBlank()) json.parseToJsonElement(dataRaw).jsonObject else null
            }.getOrNull()

            val raw = dataObj?.text("reply").orEmpty()
            val topicId = obj.number("topic_id") ?: dataObj?.number("topic_id")
            val action = dataObj?.text("action")?.takeIf { it.isNotBlank() }
                ?: if (topicId != null && topicId > 0L) "reply" else "createTopic"
            val title = dataObj?.text("title")?.takeIf { it.isNotBlank() }
                ?: Jsoup.parseBodyFragment(obj.text("title")).text()
            val categoryId = dataObj?.text("categoryId")?.toIntOrNull()
                ?: dataObj?.text("category_id")?.toIntOrNull()
                ?: obj.text("category_id").toIntOrNull()
            val tags = (dataObj?.get("tags") as? JsonArray).orEmpty().mapNotNull { elem ->
                (elem as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: (elem as? JsonObject)?.text("name")?.takeIf { it.isNotBlank() }
                    ?: (elem as? JsonObject)?.text("id")?.takeIf { it.isNotBlank() }
            }
            val replyToPostNumber = dataObj?.text("postId")?.toIntOrNull()?.takeIf { it > 0 }
                ?: obj.text("post_number").toIntOrNull()?.takeIf { it > 0 }
            val postId = dataObj?.number("postId")
            val excerptSource = obj.text("excerpt").ifBlank { raw }
            val excerpt = Jsoup.parseBodyFragment(excerptSource).text().trim()
            val createdAt = obj.text("created_at").ifBlank { null }
            val username = obj.text("username").ifBlank { obj.text("name") }.ifBlank { null }
            val avatarUrl = avatarUrlOf(obj.text("avatar_template"))
            val closed = (obj["closed"] as? JsonPrimitive)?.booleanOrNull == true
            val archetype = obj.text("archetype").ifBlank { dataObj?.text("archetypeId").orEmpty() }.ifBlank { "regular" }

            DraftItem(
                draftKey = draftKey,
                sequence = sequence,
                title = title,
                raw = raw,
                excerpt = excerpt,
                categoryId = categoryId,
                category = categoryId?.let { known[it] },
                tags = tags,
                action = action,
                topicId = topicId,
                replyToPostNumber = replyToPostNumber,
                postId = postId,
                createdAt = createdAt,
                username = username,
                avatarUrl = avatarUrl,
                closed = closed,
                archetype = archetype,
            )
        }
    }

    /**
     * 保存草稿至 Discourse 服务端（POST /drafts.json）。
     * 若遇到 409 Conflict（跨端编辑导致 sequence 过期），自动拉取最新 sequence 重试一次。
     */
    suspend fun saveDraft(
        draftKey: String = "new_topic",
        sequence: Long = 0L,
        title: String = "",
        raw: String,
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
        action: String = "createTopic",
        topicId: Long? = null,
        replyToPostNumber: Int? = null,
    ): Long {
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val dataMap = mutableMapOf<String, JsonElement>(
            "reply" to JsonPrimitive(raw),
            "action" to JsonPrimitive(action),
            "title" to JsonPrimitive(title.trim()),
            "categoryId" to (categoryId?.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull),
            "tags" to JsonArray(cleanTags.map { JsonPrimitive(it) }),
            "archetypeId" to JsonPrimitive("regular"),
            "metaData" to JsonNull,
            "composerTime" to JsonPrimitive(1500),
            "typingTime" to JsonPrimitive(1200),
        )
        if (topicId != null && topicId > 0L) {
            dataMap["topic_id"] = JsonPrimitive(topicId)
        }
        if (replyToPostNumber != null && replyToPostNumber > 0) {
            dataMap["postId"] = JsonPrimitive(replyToPostNumber)
        }
        val dataString = JsonObject(dataMap).toString()

        suspend fun doSave(seq: Long): Long {
            val payload = JsonObject(
                mapOf(
                    "draft_key" to JsonPrimitive(draftKey),
                    "sequence" to JsonPrimitive(seq),
                    "data" to JsonPrimitive(dataString),
                ),
            ).toString()
            val responseText = mutate("/drafts.json", "POST", payload)
            val root = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
            return root?.text("draft_sequence")?.toLongOrNull() ?: (seq + 1L)
        }

        return try {
            doSave(sequence)
        } catch (e: HttpStatusException) {
            if (e.status == 409) {
                val latestSeq = runCatching {
                    fetchDrafts(burst = true).firstOrNull { it.draftKey == draftKey }?.sequence
                }.getOrNull() ?: 0L
                doSave(latestSeq)
            } else {
                throw e
            }
        }
    }

    /**
     * 删除服务端草稿（DELETE /drafts/{draft_key}.json）。
     */
    suspend fun deleteDraft(draftKey: String, sequence: Long = 0L) {
        require(draftKey.isNotBlank()) { "草稿标识不能为空" }
        suspend fun doDelete(seq: Long) {
            val payload = JsonObject(
                mapOf(
                    "draft_key" to JsonPrimitive(draftKey),
                    "sequence" to JsonPrimitive(seq),
                ),
            ).toString()
            mutate(
                "/drafts/${encode(draftKey)}.json?draft_key=${encode(draftKey)}&sequence=$seq",
                "DELETE",
                payload,
            )
        }
        try {
            doDelete(sequence)
        } catch (e: HttpStatusException) {
            if (e.status == 409) {
                val latestSeq = runCatching {
                    fetchDrafts(burst = true).firstOrNull { it.draftKey == draftKey }?.sequence
                }.getOrNull() ?: return
                doDelete(latestSeq)
            } else {
                throw e
            }
        }
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
     * 解析指定话题的 1 楼 Post ID（供列表页长按直接添加书签或举报使用）。
     */
    suspend fun fetchTopicFirstPostId(topicId: Long): Long {
        require(topicId > 0L) { "话题不存在" }
        val detail = fetchTopic(topicId, burst = true, trackVisit = false)
        return detail.postStream.posts.firstOrNull { it.postNumber == 1 }?.id
            ?: detail.postStream.posts.firstOrNull()?.id
            ?: detail.postStream.stream.firstOrNull()
            ?: error("无法获取该话题的首楼信息")
    }

    /**
     * 为楼层添加书签。
     * POST /bookmarks.json
     * 返回新建的书签 ID。
     */
    suspend fun bookmarkPost(postId: Long): Long {
        require(postId > 0L) { "楼层不存在" }
        val body = JsonObject(mapOf(
            "bookmarkable_id" to JsonPrimitive(postId),
            "bookmarkable_type" to JsonPrimitive("Post"),
        )).toString()
        val response = json.parseToJsonElement(mutate("/bookmarks.json", "POST", body)).jsonObject
        return (response["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
            ?: error("书签结果无法确认，请刷新楼层后检查收藏状态")
    }

    /**
     * 从话题列表直接为话题添加书签。
     */
    suspend fun bookmarkTopic(topicId: Long, knownFirstPostId: Long? = null): Long {
        val firstPostId = knownFirstPostId?.takeIf { it > 0L } ?: fetchTopicFirstPostId(topicId)
        return bookmarkPost(firstPostId)
    }

    /**
     * 删除书签。
     * DELETE /bookmarks/{id}.json
     */
    suspend fun unbookmarkPost(bookmarkId: Long, expectedUserId: Long? = null, expectedGeneration: Long? = null) {
        require(bookmarkId > 0L) { "缺少书签信息，请刷新楼层后再取消收藏" }
        mutate("/bookmarks/$bookmarkId.json", "DELETE", beforeAttempt = {
            if (expectedUserId != null && (expectedUserId <= 0L || sessionStore?.currentUserId() != expectedUserId || sessionStore?.sessionGeneration != expectedGeneration)) {
                throw CancellationException("账号已切换，已取消操作")
            }
        })
    }

    /**
     * 举报楼层。
     * POST /post_actions.json
     */
    suspend fun flagPost(postId: Long, reasonId: Int, message: String? = null) {
        require(postId > 0L) { "楼层不存在" }
        val reason = PostFlagReason.entries.firstOrNull { it.id == reasonId }
        require(reason != null) { "请选择有效的举报原因" }
        require(!reason.requiresMessage || !message.isNullOrBlank()) { "请补充需要版主处理的问题" }
        val fields = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(postId),
            "post_action_type_id" to JsonPrimitive(reasonId),
            "flag_topic" to JsonPrimitive(false),
        )
        if (reason.requiresMessage && !message.isNullOrBlank()) {
            fields["message"] = JsonPrimitive(message.trim())
        }
        mutate("/post_actions.json", "POST", JsonObject(fields).toString())
    }

    /**
     * 设置话题通知级别 (0: 免打扰, 1: 常规, 2: 跟踪, 3: 关注)。
     * POST /t/{topic_id}/notifications.json
     */
    suspend fun setTopicNotificationLevel(topicId: Long, level: Int) {
        require(topicId > 0L && level in 0..3) { "无效的话题通知级别" }
        val body = JsonObject(mapOf(
            "notification_level" to JsonPrimitive(level),
        )).toString()
        mutate("/t/$topicId/notifications.json", "POST", body)
        sessionStore?.saveTopicNotificationLevel(topicId, level)
    }

    /**
     * 上传一个文件,返回可直接写进正文 raw 的结果。
     *
     * Discourse 的发帖接口只收纯文本,附件要分两步:先 POST /uploads.json 拿 short_url
     * (形如 `upload://xxx.png`),再把 Markdown 短码拼进 raw。
     * `synchronous=true` 让服务端同步处理完再返回,省掉轮询。
     */
    suspend fun uploadFile(fileName: String, mimeType: String, bytes: ByteArray): UploadedFile {
        ensureRequestAllowed()
        UploadLimits.rejectReason(fileName, bytes.size.toLong())?.let { throw LoginRejectedException(it) }
        var revalidated = false
        var csrfRefreshed = false
        var rateLimitRetried = false
        while (true) {
            val csrf = obtainCsrf(forceRefresh = csrfRefreshed)
            val result = postGate.withLock {
                throttlePost(burst = true)
                val transport = transportProvider()
                try {
                    transport.upload(
                        "/uploads.json", csrf, fileName, mimeType, bytes,
                        mapOf("type" to "composer", "synchronous" to "true"),
                    )
                } finally { lastPostRequestAt = SystemClock.elapsedRealtime() }
            }
            diagnostics.recordStatus(transportProvider().name, result.status, "/uploads")
            if (result.status == 429) {
                val cfChallenge = isCfChallengeBody(result.body)
                val cooldownEx = enterRateLimitCooldown("/uploads.json", result.retryAfterSeconds, cfChallenge = cfChallenge)
                val waitSec = result.retryAfterSeconds ?: 3L
                if (!cfChallenge && !rateLimitRetried && waitSec in 1L..AUTO_RETRY_MAX_WAIT_SECONDS) {
                    rateLimitRetried = true
                    delay(waitSec * 1_000L)
                    continue
                }
                throw cooldownEx
            }
            if (result.challenged) {
                cachedCsrfToken = null
                sessionStore?.noteClearanceChallenged(result.sentClearanceFp)
                // 对齐 v1.3.1：撞盾先静默过盾，连 CSRF 一起重取后重传一次。
                if (!revalidated && sessionStore?.sessionChanging != true &&
                    transportProvider().revalidate("/uploads.json", result.sentClearanceFp)
                ) {
                    revalidated = true
                    csrfRefreshed = true
                    continue
                }
                throw NeedsInteractiveVerification("上传需要重新验证", failedClearanceFp = result.sentClearanceFp)
            }
            if (result.status == 0) {
                // 路径 B 的"不支持上传"也走这里,它把原因放在 body 里。
                throw result.body.takeIf { it.isNotBlank() && !it.startsWith("java.") }
                    ?.let { LoginRejectedException(it) } ?: NetworkTimeoutException()
            }
            if ((result.status == 403 || result.status == 422) && !csrfRefreshed &&
                (result.body.contains("BAD CSRF", ignoreCase = true) || result.body.contains("csrf", ignoreCase = true))
            ) {
                cachedCsrfToken = null
                csrfRefreshed = true
                continue
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
                id = root.number("id"),
                shortUrl = shortUrl,
                fileName = root.text("original_filename").ifBlank { fileName },
                width = root.text("width").toIntOrNull() ?: 0,
                height = root.text("height").toIntOrNull() ?: 0,
                url = root.text("url").takeIf { it.isNotBlank() },
            )
        }
    }

    /** 取楼层原文。post_stream 下发的楼层只有 cooked,编辑前必须单独取一次 raw。 */
    suspend fun fetchPostRaw(postId: Long): String =
        objectRequest("/posts/$postId.json").text("raw")

    suspend fun votePoll(postId: Long, pollName: String, optionIds: List<String>): DiscoursePoll {
        require(optionIds.isNotEmpty())
        val body = buildString {
            append("post_id=").append(encode(postId.toString()))
            append("&poll_name=").append(encode(pollName))
            optionIds.distinct().forEach { optionId ->
                append("&options%5B%5D=").append(encode(optionId))
            }
        }
        val response = json.parseToJsonElement(
            mutate("/polls/vote", "PUT", body, Transport.FORM_CONTENT_TYPE),
        ).jsonObject
        return json.decodeFromJsonElement(response["poll"] ?: error("投票响应缺少结果"))
    }
    suspend fun fetchUnreadSummary(scope: NotificationScope): UnreadSummary {
        // Discourse 的 /notifications.json 不支持 filter=unread 参数；不带 recent=true 时会返回账号
        // 自注册以来的全量历史分页，导致 while 循环把多年前未逐条点击的陈年记录累加为 99+ 且耗时十余秒。
        // 统一使用 recent=true&limit=50&silent=true，与网页端顶栏铃铛及后台 NotificationWorker 严格同源。
        return parseUnreadSummary(
            request(LOGIN_CF_GATE_PATH, present = false),
            scope,
        )
    }

    private fun parseUnreadSummary(payload: String, scope: NotificationScope): UnreadSummary {
        val page = json.decodeFromString<UnreadNotificationPage>(payload)
        val fresh = page.notifications.distinctBy { it.id }
        val count = fresh.count { it.countsAsUnread(scope) }
        val unreadByType = mutableMapOf<Int, Int>()
        fresh.filter { !it.read }.forEach { unreadByType[it.type] = (unreadByType[it.type] ?: 0) + 1 }
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

    suspend fun unreadCount(scope: NotificationScope): Int = fetchUnreadSummary(scope).count


    suspend fun markNotificationRead(id: Long) {
        mutate("/notifications/mark-read.json", "PUT", JsonObject(mapOf("id" to JsonPrimitive(id))).toString())
    }

    suspend fun markAllNotificationsRead() {
        mutate("/notifications/mark-read.json", "PUT", "{}")
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
    private suspend fun mutate(
        path: String,
        method: String,
        body: String? = null,
        contentType: String = Transport.JSON_CONTENT_TYPE,
        beforeAttempt: () -> Unit = {},
    ): String {
        ensureRequestAllowed()
        var revalidated = false
        var csrfRefreshed = false
        var rateLimitRetried = false
        while (true) {
            beforeAttempt()
            val csrf = obtainCsrf(forceRefresh = csrfRefreshed)
            val result = postGate.withLock {
                throttlePost(burst = true)
                beforeAttempt()
                val transport = transportProvider()
                try { transport.write(path, method, body, csrf, contentType) } finally { lastPostRequestAt = SystemClock.elapsedRealtime() }
            }
            diagnostics.recordStatus(transportProvider().name, result.status, path)
            if (result.status == 429) {
                val challenge429 = ChallengeDetector.isChallenge429(result.status, result.cfMitigated, result.body)
                if (challenge429 && !revalidated && sessionStore?.sessionChanging != true) {
                    cachedCsrfToken = null
                    sessionStore?.noteClearanceChallenged(result.sentClearanceFp)
                    if (isCarriedClearanceStale(result.sentClearanceFp) ||
                        transportProvider().revalidate(path, result.sentClearanceFp)
                    ) {
                        revalidated = true
                        csrfRefreshed = true
                        continue
                    }
                }
                val cfChallenge = challenge429 || isCfChallengeBody(result.body)
                val cooldownEx = enterRateLimitCooldown(path, result.retryAfterSeconds, cfChallenge = cfChallenge)
                val waitSec = result.retryAfterSeconds ?: 3L
                if (!cfChallenge && !rateLimitRetried && waitSec in 1L..AUTO_RETRY_MAX_WAIT_SECONDS) {
                    rateLimitRetried = true
                    delay(waitSec * 1_000L)
                    continue
                }
                throw cooldownEx
            }
            if (result.challenged || (result.status == 403 && ChallengeDetector.isChallenge(result.status, "", result.body))) {
                cachedCsrfToken = null
                if (!revalidated && isCarriedClearanceStale(result.sentClearanceFp)) {
                    diagnostics.log("写请求携带过期 cf_clearance 撞盾(sent=${result.sentClearanceFp})，直接换新值并重取 CSRF 重放: $path")
                    revalidated = true
                    csrfRefreshed = true
                    continue
                }
                sessionStore?.noteClearanceChallenged(result.sentClearanceFp)
                // 对齐 v1.3.1：先静默过盾再连 CSRF 一起重发一次。CF 挑战在边缘拦截，源站没收到，重放安全。
                // 不能在这里删 cf_clearance：删了之后所有请求都必然撞盾，静默过盾也失去了可续的凭据。
                // clearChallenge 最长要等 30 秒，绝不能占着 postGate 等。
                if (!revalidated && sessionStore?.sessionChanging != true &&
                    transportProvider().revalidate(path, result.sentClearanceFp)
                ) {
                    revalidated = true
                    csrfRefreshed = true
                    continue
                }
                notify403OrChallenged("mutate $path challenged")
                throw NeedsInteractiveVerification("安全验证已过期，请完成人机验证", failedClearanceFp = result.sentClearanceFp)
            }
            if (result.status == 0) throw NetworkTimeoutException()
            if ((result.status == 403 || result.status == 422) && !csrfRefreshed &&
                (result.body.contains("BAD CSRF", ignoreCase = true) || result.body.contains("csrf", ignoreCase = true))
            ) {
                cachedCsrfToken = null
                csrfRefreshed = true
                continue
            }
            if (result.status !in 200..299) {
                if (result.status == 403) {
                    notify403OrChallenged("mutate $path 403")
                }
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
            request("/search.json?q=${encode(orderedQuery)}&page=$page&type_filter=topic"),
        )
        val known = categories()
        val posts = response.posts.groupBy { it.topicId }
        val topicsById = response.topics.associateBy { it.id }
        val matches: List<Pair<Topic, Post?>> = response.posts.filter { it.postNumber > 0 }.mapNotNull { post ->
            topicsById[post.topicId]?.let { it to post }
        }
        val matchedTopics = matches.mapTo(hashSetOf()) { it.first.id }
        val entries = matches + response.topics.filterNot { it.id in matchedTopics }.map { it to null }
        return SearchPage(
            items = entries.map { (topic, author) ->
                val cachedLevel = sessionStore?.getTopicNotificationLevel(topic.id)
                val effectiveLevelCode = cachedLevel ?: topic.notificationLevel?.takeIf { it in 0..3 && it != 1 }
                val notificationLevel = effectiveLevelCode?.let { TopicNotificationLevel.fromCode(it) }
                TopicListItem(
                    topic = (reading?.mergeReadState(topic) ?: topic).copy(
                        excerpt = if (author != null) Jsoup.parseBodyFragment(author.blurb.ifBlank { author.cooked }).text() else topic.excerpt),
                    authorUsername = author?.username,
                    authorName = author?.name,
                    avatarUrl = avatarUrlOf(author?.avatarTemplate, author?.animatedAvatar, size = 64),
                    staticAvatarUrl = avatarUrlOf(author?.avatarTemplate, null, size = 64),
                    category = known[topic.categoryId],
                    notificationLevel = notificationLevel,
                    targetPostNumber = author?.postNumber?.takeIf { it > 0 },
                )
            },
            excerpts = posts.mapValues { (_, matches) -> Jsoup.parseBodyFragment(matches.first().blurb).text() },
            hasMore = response.grouped.more && response.topics.isNotEmpty(),
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    suspend fun searchInTopic(topicId: Long, query: String, page: Int, order: SearchOrder): TopicSearchPage {
        require(topicId > 0L)
        val clean = query.replace(Regex("(?i)(?:^|\\s)topic:\\S+"), " ")
            .replace(Regex("(?i)(?:^|\\s)order:\\S+"), " ").trim()
        if (clean.isBlank()) return TopicSearchPage(emptyList(), false)
        val scoped = "$clean topic:$topicId" + if (order.key.isBlank()) "" else " order:${order.key}"
        val response = json.decodeFromString<SearchResponse>(request("/search.json?q=${encode(scoped)}&page=$page&type_filter=topic"))
        val hits = response.posts.filter { it.topicId == topicId && it.postNumber > 0 }.distinctBy { it.id }.map {
            TopicSearchHit(it.id, it.postNumber, it.username, avatarUrlOf(it.avatarTemplate, it.animatedAvatar, size = 64),
                it.createdAt, Jsoup.parseBodyFragment(it.blurb.ifBlank { it.cooked }).text())
        }
        return TopicSearchPage(hits, response.grouped.more && response.posts.isNotEmpty())
    }

    suspend fun fetchTopicMetadata(topicId: Long): TopicMetadata {
        return parseTopicMetadata(objectRequest("/t/$topicId.json?track_visit=false"), topicId)
    }

    suspend fun fetchTopicEditRules(): TopicEditRules {
        val site = objectRequest("/site.json")
        val categories = site.objects("categories").map { json.decodeFromJsonElement(Category.serializer(), it) }
        val settings = try {
            val html = request("/", present = false, retryAllowed = false)
            val doc = Jsoup.parse(html)
            val element = doc.getElementById("data-preloaded") ?: doc.selectFirst("[data-preloaded]")
            val raw = if (element?.tagName() == "script") element.data() else element?.attr("data-preloaded")
            val preloaded = raw?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) as? JsonObject }
            when (val value = preloaded?.get("siteSettings")) {
                is JsonObject -> value
                is JsonPrimitive -> json.parseToJsonElement(value.content) as? JsonObject
                else -> null
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        return TopicEditRules(
            categories = flattenCategories(categories).distinctBy { it.id },
            canTagTopics = (site["can_tag_topics"] as? JsonPrimitive)?.booleanOrNull == true,
            minTitleLength = settings?.number("min_topic_title_length")?.toInt()?.takeIf { it > 0 } ?: 15,
            minPmTitleLength = settings?.number("min_personal_message_title_length")?.toInt()?.takeIf { it > 0 } ?: 2,
            uncategorizedId = settings?.number("uncategorized_category_id")?.toInt() ?: 1,
        )
    }

    suspend fun searchTopicEditTags(categoryId: Int?, query: String, selectedTags: List<String>): DiscourseTagSearchResult {
        val params = mutableListOf("q=${encode(query)}", "limit=8", "filterForInput=true")
        categoryId?.let { params += "categoryId=$it" }
        selectedTags.forEach { params += "selected_tags[]=${encode(it)}" }
        val root = objectRequest("/tags/filter/search?${params.joinToString("&")}")
        val group = root["required_tag_group"] as? JsonObject
        return DiscourseTagSearchResult(
            root.objects("results").mapNotNull { obj -> obj.text("name").takeIf { it.isNotBlank() }?.let {
                DiscourseTagInfo(it, obj.text("text").ifBlank { it }, obj.number("count")?.toInt() ?: 0)
            } }, group?.text("name"), group?.number("min_count")?.toInt() ?: 1,
        )
    }

    suspend fun updateTopicMetadata(original: TopicMetadata, rules: TopicEditRules, patch: TopicMetadataPatch, userId: Long, generation: Long): TopicMetadata {
        require(userId > 0L) { "请先登录账号" }
        require(patch.changed) { "话题信息没有变化" }
        validateTopicMetadata(original, rules, patch)?.let { throw IllegalArgumentException(it) }
        val guard = {
            val session = sessionStore ?: throw CancellationException("缺少账号状态")
            if (session.currentUserId() != userId || session.sessionGeneration != generation || session.sessionChanging) {
                throw CancellationException("账号已切换，已取消编辑")
            }
        }
        guard()
        mutate("/t/-/${original.topicId}.json", "PUT", patch.formBody(), Transport.FORM_CONTENT_TYPE, guard)
        guard()
        val confirmed = try { fetchTopicMetadata(original.topicId) }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        guard()
        if (patch.tags?.isEmpty() == true && (confirmed == null || confirmed.tags.isNotEmpty())) {
            throw HttpStatusException(503, "保存请求已返回，但尚未确认标签清空，请核对后重试")
        }
        return confirmed ?: patch.applyTo(original)
    }

    suspend fun fetchProfile(
        username: String,
        section: ProfileSection,
        cursor: Int,
        notificationFilter: NotificationFilter = NotificationFilter.All,
        excludeIds: Set<String> = emptySet(),
    ): ProfilePage {
        val userPath = encode(username)
        return when (section) {
            ProfileSection.Summary -> {
                val root = objectRequest("/u/$userPath/summary.json")
                val summary = root["user_summary"] as? JsonObject ?: JsonObject(emptyMap())
                val metrics = listOf("days_visited" to "访问天数", "time_read" to "阅读时长",
                    "topics_entered" to "浏览话题", "posts_read_count" to "已读帖子",
                    "likes_given" to "送出赞", "likes_received" to "收到赞",
                    "topic_count" to "创建话题", "post_count" to "回复数量",
                    "bookmark_count" to "我的收藏")
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
                        action.number("topic_id"),
                        postNumber = action.text("post_number").toIntOrNull()?.takeIf { it > 0 })
                }, hasMore = actions.size >= 30, nextCursor = cursor + 1)
            }
            ProfileSection.Notifications -> fetchNotifications(cursor, notificationFilter, excludeIds)
            ProfileSection.Messages -> {
                val result = toPage(json.decodeFromString(request("/topics/private-messages/$userPath.json?page=$cursor")))
                ProfilePage(entries = result.items.map { item -> ProfileEntry(item.topic.id.toString(), item.topic.title,
                    "${item.topic.replyCount} 回复 · ${item.topic.lastPostedAt?.take(10).orEmpty()}", item.topic.id,
                    unread = item.topic.unseen, avatarUrl = item.avatarUrl, actor = item.authorUsername) },
                    hasMore = result.hasMore, nextCursor = cursor + 1)
            }
        }
    }

    /** 获取用户在社区的回复历史列表 (filter=5 对应 Discourse user replies) */
    suspend fun fetchUserReplies(username: String, cursor: Int): Pair<List<UserReplyItem>, Boolean> {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val allCategories = categories()
        val actions = objectRequest("/user_actions.json?username=$userPath&offset=${cursor * 30}&limit=30&filter=5")
            .objects("user_actions")
        val items = actions.mapIndexed { index, action ->
            val catId = action.number("category_id")?.toInt()
            val cat = catId?.let { allCategories[it] }
            val avatarTpl = action.text("acting_avatar_template").ifBlank { action.text("avatar_template") }
            val avatarUrl = if (avatarTpl.isNotBlank()) avatarUrlOf(avatarTpl) else null
            UserReplyItem(
                id = "${cursor * 30 + index}:${action.text("post_id")}",
                topicId = action.number("topic_id") ?: 0L,
                postNumber = action.text("post_number").toIntOrNull()?.takeIf { it > 0 },
                title = action.text("title").ifBlank { "无标题话题" },
                categoryId = catId?.toLong(),
                categoryName = cat?.name,
                categoryColor = cat?.color,
                createdAt = action.text("created_at").ifBlank { null },
                commentExcerpt = Jsoup.parseBodyFragment(action.text("excerpt")).text(),
                username = action.text("acting_username").ifBlank { action.text("username").ifBlank { username } },
                avatarUrl = avatarUrl,
            )
        }
        return items to (actions.size >= 30)
    }

    /** 通用获取用户各类动态动作列表 (filter: 4=话题, 5=回复, 1=点赞, 17=投票, 15=已解决, 等等) */
    suspend fun fetchUserActionItems(username: String, filter: String?, cursor: Int): Pair<List<UserReplyItem>, Boolean> {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val allCategories = categories()
        val filterParam = if (!filter.isNullOrBlank()) "&filter=$filter" else ""
        val actions = objectRequest("/user_actions.json?username=$userPath&offset=${cursor * 30}&limit=30$filterParam")
            .objects("user_actions")
        val items = actions.mapIndexed { index, action ->
            val catId = action.number("category_id")?.toInt()
            val cat = catId?.let { allCategories[it] }
            val avatarTpl = action.text("acting_avatar_template").ifBlank { action.text("avatar_template") }
            val avatarUrl = if (avatarTpl.isNotBlank()) avatarUrlOf(avatarTpl) else null
            UserReplyItem(
                id = "${cursor * 30 + index}:${action.text("post_id")}",
                topicId = action.number("topic_id") ?: 0L,
                postNumber = action.text("post_number").toIntOrNull()?.takeIf { it > 0 },
                title = action.text("title").ifBlank { "无标题话题" },
                categoryId = catId?.toLong(),
                categoryName = cat?.name,
                categoryColor = cat?.color,
                createdAt = action.text("created_at").ifBlank { null },
                commentExcerpt = Jsoup.parseBodyFragment(action.text("excerpt")).text(),
                username = action.text("acting_username").ifBlank { action.text("username").ifBlank { username } },
                avatarUrl = avatarUrl,
            )
        }
        return items to (actions.size >= 30)
    }

    suspend fun fetchUserSummary(username: String): UserSummaryData {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val raw = objectRequest("/u/$userPath/summary.json")
        val summary = raw["user_summary"] as? JsonObject ?: JsonObject(emptyMap())

        val topicsList = raw.objects("topics").map { t ->
            SummaryTopicItem(
                id = t.number("id") ?: 0L,
                title = t.text("title"),
                likeCount = t.number("like_count")?.toInt() ?: 0,
                createdAt = t.text("created_at").ifBlank { null },
                categoryId = t.number("category_id"),
            )
        }
        val topicMap = topicsList.associateBy { it.id }

        val repliesList = summary.objects("replies").map { r ->
            val topicId = r.number("topic_id") ?: 0L
            val topic = topicMap[topicId]
            val topicTitle = (r["topic"] as? JsonObject)?.text("title") ?: topic?.title ?: "#$topicId"
            SummaryReplyItem(
                topicId = topicId,
                postNumber = r.number("post_number")?.toInt() ?: 1,
                title = topicTitle,
                likeCount = r.number("like_count")?.toInt() ?: 0,
                createdAt = r.text("created_at").ifBlank { null },
            )
        }

        val linksList = summary.objects("links").map { l ->
            val topicId = l.number("topic_id")
            val topic = topicId?.let { topicMap[it] }
            val topicTitle = (l["topic"] as? JsonObject)?.text("title") ?: topic?.title
            SummaryLinkItem(
                url = l.text("url"),
                title = l.text("title").ifBlank { null },
                clicks = l.number("clicks")?.toInt() ?: 0,
                postNumber = l.number("post_number")?.toInt(),
                topicId = topicId,
                topicTitle = topicTitle,
            )
        }

        fun parseUserWithCount(list: List<JsonObject>): List<SummaryUserItem> = list.mapNotNull { u ->
            val uName = u.text("username")
            if (uName.isBlank()) null
            else SummaryUserItem(
                id = u.number("id") ?: 0L,
                username = uName,
                avatarTemplate = u.text("avatar_template").ifBlank { null },
                count = u.number("count")?.toInt() ?: 0,
            )
        }

        val mostReplied = parseUserWithCount(summary.objects("most_replied_to_users"))
        val mostLikedBy = parseUserWithCount(summary.objects("most_liked_by_users"))
        val mostLiked = parseUserWithCount(summary.objects("most_liked_users"))

        val topCategories = summary.objects("top_categories").map { c ->
            SummaryCategoryItem(
                id = c.number("id") ?: 0L,
                name = c.text("name"),
                color = c.text("color").ifBlank { null },
                topicCount = c.number("topic_count")?.toInt() ?: 0,
                postCount = c.number("post_count")?.toInt() ?: 0,
            )
        }

        val badgesList = raw.objects("badges").map { b ->
            SummaryBadgeItem(
                id = b.number("id") ?: 0L,
                name = b.text("name"),
                description = b.text("description").ifBlank { null },
                icon = b.text("icon").ifBlank { null },
                badgeTypeId = b.number("badge_type_id")?.toInt() ?: 0,
            )
        }

        return UserSummaryData(
            daysVisited = summary.number("days_visited")?.toInt() ?: 0,
            postsReadCount = summary.number("posts_read_count")?.toInt() ?: 0,
            likesReceived = summary.number("likes_received")?.toInt() ?: 0,
            likesGiven = summary.number("likes_given")?.toInt() ?: 0,
            topicCount = summary.number("topic_count")?.toInt() ?: 0,
            postCount = summary.number("post_count")?.toInt() ?: 0,
            timeRead = summary.number("time_read")?.toInt() ?: 0,
            bookmarkCount = summary.number("bookmark_count")?.toInt() ?: 0,
            topicsEntered = summary.number("topics_entered")?.toInt() ?: 0,
            topTopics = topicsList,
            topReplies = repliesList,
            topLinks = linksList,
            mostRepliedToUsers = mostReplied,
            mostLikedByUsers = mostLikedBy,
            mostLikedUsers = mostLiked,
            topCategories = topCategories,
            badges = badgesList,
        )
    }

    @Volatile private var cachedAllBadgesRaw: Pair<Long, JsonObject?>? = null
    private val allBadgesGate = Mutex()

    private suspend fun getAllBadgesRaw(): JsonObject? {
        val now = SystemClock.elapsedRealtime()
        cachedAllBadgesRaw?.let { (time, data) ->
            if (now - time < 120_000L && data != null) return data
        }
        return allBadgesGate.withLock {
            val second = cachedAllBadgesRaw
            if (second != null && now - second.first < 120_000L && second.second != null) {
                return@withLock second.second
            }
            val fetched = runCatching { objectRequest("/badges.json") }.getOrNull()
            if (fetched != null) {
                cachedAllBadgesRaw = Pair(SystemClock.elapsedRealtime(), fetched)
            }
            fetched ?: second?.second
        }
    }

    private val userBadgesMemoryCache = ConcurrentHashMap<String, Pair<Long, List<UserBadgeItem>>>()
    private val inFlightUserBadges = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<UserBadgeItem>>>()

    /**
     * 获取用户徽章数据：
     * 1. 内存 30s 缓存防刷 + In-flight Single-Flight 去重，避免同屏重复拉取；
     * 2. 请求官方接口 /user-badges/{username}.json?grouped=true 获取用户已获得的徽章；
     * 3. 请求全站徽章池 /badges.json (带 120s 内存缓存) 补全未获得的徽章；
     * 4. 严格使用官方名称、描述 (HTML 清洗) 与图标/图片；
     * 5. 优先排序已获取的徽章，后排未获取的徽章。
     */
    suspend fun fetchUserBadges(username: String, forceRefresh: Boolean = false): List<UserBadgeItem> = withContext(Dispatchers.IO) {
        val key = username.lowercase().trim().removePrefix("@")
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh) {
            userBadgesMemoryCache[key]?.let { (time, list) ->
                if (now - time < 30_000L && list.isNotEmpty()) {
                    return@withContext list
                }
            }
        }
        val inFlight = inFlightUserBadges[key]
        if (inFlight != null && inFlight.isActive) {
            return@withContext inFlight.await()
        }
        val deferred = async {
            try {
                doFetchUserBadges(username)
            } finally {
                inFlightUserBadges.remove(key)
            }
        }
        inFlightUserBadges[key] = deferred
        deferred.await()
    }

    private suspend fun doFetchUserBadges(username: String): List<UserBadgeItem> {
        val key = username.lowercase().trim().removePrefix("@")
        val userPath = URLEncoder.encode(username, "UTF-8")
        val userBadgesRaw = runCatching {
            objectRequest("/user-badges/$userPath.json?grouped=true")
        }.getOrNull()

        val allBadgesRaw = getAllBadgesRaw()

        val userBadgesList = userBadgesRaw?.objects("user_badges").orEmpty()
        val earnedMap = mutableMapOf<Long, Pair<String?, Int>>() // badgeId -> (grantedAt, count)
        userBadgesList.forEach { ub ->
            val bId = ub.number("badge_id") ?: return@forEach
            val grantedAt = ub.text("granted_at").ifBlank { null }
            val count = ub.number("count")?.toInt() ?: 1
            earnedMap[bId] = Pair(grantedAt, count)
        }

        val badgeDefMap = mutableMapOf<Long, JsonObject>()
        userBadgesRaw?.objects("badges")?.forEach { b ->
            val id = b.number("id") ?: return@forEach
            badgeDefMap[id] = b
        }
        allBadgesRaw?.objects("badges")?.forEach { b ->
            val id = b.number("id") ?: return@forEach
            if (!badgeDefMap.containsKey(id)) {
                badgeDefMap[id] = b
            }
        }

        val result = mutableListOf<UserBadgeItem>()
        badgeDefMap.values.forEach { b ->
            val id = b.number("id") ?: return@forEach
            val name = b.text("name")
            if (name.isBlank()) return@forEach
            val rawDesc = b.text("description").ifBlank { null }
            val cleanDesc = rawDesc?.let { org.jsoup.Jsoup.parseBodyFragment(it).text() } ?: rawDesc
            val icon = b.text("icon").ifBlank { null }
            val imageUrl = b.text("image_url").ifBlank { null }?.let { url ->
                if (url.startsWith("/")) "https://linux.do$url" else url
            }
            val badgeTypeId = b.number("badge_type_id")?.toInt() ?: 3
            val badgeGroupingId = b.number("badge_grouping_id")?.toInt() ?: 0
            val grantCount = b.number("grant_count")?.toInt() ?: 0

            val earnedInfo = earnedMap[id]
            val isEarned = earnedInfo != null

            result.add(
                UserBadgeItem(
                    id = id,
                    badgeId = id,
                    name = name,
                    description = cleanDesc,
                    icon = icon,
                    imageUrl = imageUrl,
                    badgeTypeId = badgeTypeId,
                    badgeGroupingId = badgeGroupingId,
                    grantCount = grantCount,
                    grantedAt = earnedInfo?.first,
                    count = earnedInfo?.second ?: 0,
                    isEarned = isEarned,
                )
            )
        }

        // 优先已获得（按获得时间倒序），随后按未获得（按等级与获得人数排序）
        val sorted = result.sortedWith(
            compareByDescending<UserBadgeItem> { it.isEarned }
                .thenByDescending { it.grantedAt ?: "" }
                .thenBy { it.badgeTypeId }
                .thenByDescending { it.grantCount }
        )
        saveCachedUserBadges(username, sorted)
        userBadgesMemoryCache[key] = Pair(SystemClock.elapsedRealtime(), sorted)
        return sorted
    }

    suspend fun fetchUserBoosts(username: String, beforeBoostId: Long? = null): Pair<List<UserReplyItem>, Long?> {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val query = if (beforeBoostId != null) "?before_boost_id=$beforeBoostId" else ""
        val raw = objectRequest("/discourse-boosts/users/$userPath/boosts-given.json$query")
        val boosts = raw.objects("boosts")
        val items = boosts.mapNotNull { b ->
            val id = b.number("id") ?: return@mapNotNull null
            val post = b["post"] as? JsonObject
            val cooked = b.text("cooked")
            val postUrl = post?.text("url").orEmpty()
            val parsedPostNumber = postUrl.trimEnd('/').split('/').lastOrNull()?.toIntOrNull()
            val postNumber = parsedPostNumber ?: post?.number("post_number")?.toInt() ?: 1
            val topicId = post?.number("topic_id") ?: 0L
            val topicTitle = post?.text("topic_title")?.ifBlank { "话题 #$topicId" } ?: "话题 #$topicId"
            val excerptText = Jsoup.parseBodyFragment(cooked.ifBlank { post?.text("excerpt").orEmpty() }).text()
            UserReplyItem(
                id = "boost_$id",
                topicId = topicId,
                postNumber = postNumber,
                title = topicTitle,
                categoryId = null,
                categoryName = "Boost",
                categoryColor = "F59E0B",
                createdAt = b.text("created_at").ifBlank { null },
                commentExcerpt = excerptText,
                username = username,
                avatarUrl = null,
            )
        }.distinctBy { it.id }
        val minId = boosts.mapNotNull { it.number("id") }.minOrNull()
        return items to (if (boosts.size >= 20) minId else null)
    }

    suspend fun fetchUserVotedTopics(username: String, page: Int = 0): Pair<List<UserReplyItem>, Boolean> {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val query = if (page > 0) "?page=$page" else ""
        val raw = objectRequest("/topics/voted-by/$userPath.json$query")
        val allCategories = categories()
        val users = raw.objects("users").associate { u ->
            u.number("id") to (u.text("username") to u.text("avatar_template"))
        }
        val topicList = raw["topic_list"] as? JsonObject
        val topics = topicList?.objects("topics").orEmpty()
        val moreTopicsUrl = topicList?.text("more_topics_url")
        val hasMore = !moreTopicsUrl.isNullOrBlank()
        val items = topics.mapIndexedNotNull { index, t ->
            val id = t.number("id") ?: return@mapIndexedNotNull null
            val catId = t.number("category_id")?.toInt()
            val cat = catId?.let { allCategories[it] }
            val posters = t.objects("posters")
            val originalPosterId = posters.firstOrNull()?.number("user_id")
            val userInfo = originalPosterId?.let { users[it] }
            val authorName = userInfo?.first?.takeIf { it.isNotBlank() } ?: username
            val avatarTpl = userInfo?.second?.takeIf { it.isNotBlank() }
            val resolvedAvatar = avatarTpl?.let { avatarUrlOf(it, 48) }
            val replyCount = t.number("posts_count")?.minus(1)?.coerceAtLeast(0) ?: 0
            UserReplyItem(
                id = "vote_${page}_${index}_$id",
                topicId = id,
                postNumber = 1,
                title = t.text("title").ifBlank { "投票话题 #$id" },
                categoryId = catId?.toLong(),
                categoryName = cat?.name,
                categoryColor = cat?.color,
                createdAt = t.text("created_at").ifBlank { null },
                commentExcerpt = "已投票 · 获赞 ${t.number("like_count") ?: 0} · 回复 $replyCount",
                username = authorName,
                avatarUrl = resolvedAvatar,
            )
        }.distinctBy { it.topicId }
        return items to hasMore
    }

    suspend fun fetchUserSolvedPosts(username: String, offset: Int = 0): Pair<List<UserReplyItem>, Boolean> {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val raw = objectRequest("/solution/by_user.json?username=$userPath&offset=$offset")
        val allCategories = categories()
        val solvedPosts = raw.objects("user_solved_posts")
        val items = solvedPosts.mapIndexedNotNull { index, s ->
            val postId = s.number("post_id") ?: s.number("id") ?: return@mapIndexedNotNull null
            val topicId = s.number("topic_id") ?: 0L
            val catId = s.number("category_id")?.toInt()
            val cat = catId?.let { allCategories[it] }
            UserReplyItem(
                id = "solved_${offset + index}_$postId",
                topicId = topicId,
                postNumber = s.number("post_number")?.toInt() ?: 1,
                title = s.text("title").ifBlank { s.text("topic_title").ifBlank { "已采纳回答" } },
                categoryId = catId?.toLong(),
                categoryName = cat?.name ?: "已采纳解答",
                categoryColor = cat?.color ?: "10B981",
                createdAt = s.text("created_at").ifBlank { null },
                commentExcerpt = Jsoup.parseBodyFragment(s.text("excerpt")).text(),
                username = username,
                avatarUrl = null,
            )
        }.distinctBy { it.id }
        return items to (solvedPosts.size >= 30)
    }

    suspend fun fetchUserReactions(username: String, beforeReactionId: Long? = null): Pair<List<UserReplyItem>, Long?> {
        val userPath = URLEncoder.encode(username, "UTF-8")
        val query = if (beforeReactionId != null) "?username=$userPath&before_reaction_user_id=$beforeReactionId" else "?username=$userPath"
        val rawStr = request("/discourse-reactions/posts/reactions.json$query")
        val jsonEl = json.parseToJsonElement(rawStr)
        val reactionList: List<JsonObject> = when (jsonEl) {
            is JsonArray -> jsonEl.filterIsInstance<JsonObject>()
            is JsonObject -> jsonEl.objects("reactions").ifEmpty { jsonEl.objects("posts") }
            else -> emptyList()
        }
        val items = reactionList.mapNotNull { item ->
            val id = item.number("id") ?: return@mapNotNull null
            val post = item["post"] as? JsonObject
            val reaction = item["reaction"] as? JsonObject
            val reactionValue = reaction?.text("reaction_value")?.ifBlank { reaction?.text("id") } ?: "heart"
            val topicId = post?.number("topic_id") ?: 0L
            val postNumber = post?.number("post_number")?.toInt() ?: 1
            val title = post?.text("topic_title")?.ifBlank { "回应动态" } ?: "回应动态"
            val excerpt = Jsoup.parseBodyFragment(post?.text("excerpt").orEmpty()).text()
            UserReplyItem(
                id = "reaction_$id",
                topicId = topicId,
                postNumber = postNumber,
                title = title,
                categoryId = null,
                categoryName = "回应 :$reactionValue:",
                categoryColor = "E11D48",
                createdAt = item.text("created_at").ifBlank { null },
                commentExcerpt = excerpt,
                username = username,
                avatarUrl = null,
            )
        }.distinctBy { it.id }
        val minId = reactionList.mapNotNull { it.number("id") }.minOrNull()
        return items to (if (reactionList.size >= 20) minId else null)
    }

    /**
     * 按类型取通知。
     *
     * 首屏(offset == 0)对齐 PC 端右上角通知菜单，优先请求 `recent=true&silent=true`：
     * Discourse 后端仅在 `recent=true` 时走 `Notification.prioritized_list`，将未读的高优通知与所有非点赞类未读通知
     * （包括关注的人发帖 801、关注的人回复 802、新增关注 800、徽章 12、回复/提及等）置顶排列，且仅在此时生效 `filter_by_types`；
     * 若不带 `recent=true`，服务端按纯 `created_at DESC` 排序且忽略 `filter_by_types`，早期未读的关注提醒会被近期大量点赞淹没。
     * 继续向下翻页(offset > 0)时切回 `offset` 全量历史流，并用 `seenIds` 自动去重已展示条目。
     */
    private suspend fun fetchNotifications(
        offset: Int,
        filter: NotificationFilter,
        excludeIds: Set<String> = emptySet(),
    ): ProfilePage {
        val typeParam = filter.serverTypes.takeIf { it.isNotBlank() }
            ?.let { "&filter_by_types=${encode(it)}" }.orEmpty()
        val seenIds = excludeIds.toMutableSet()
        val collected = mutableListOf<ProfileEntry>()
        if (offset == 0) {
            val recentPage = try {
                objectRequest(
                    "/notifications.json?recent=true&limit=$NOTIFICATION_SCAN_LIMIT&silent=true$typeParam",
                    burst = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: NeedsInteractiveVerification) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("优先通知列表加载回退: ${error.message}")
                null
            }
            if (recentPage != null) {
                val recentNotices = recentPage.objects("notifications")
                collected += recentNotices.map(::toNotificationEntry)
                    .filter { filter.includes(it.notificationType) && seenIds.add(it.id) }
                if (collected.size >= PAGE_TARGET || recentNotices.size < NOTIFICATION_SCAN_LIMIT) {
                    return ProfilePage(
                        entries = collected,
                        hasMore = recentNotices.size >= PAGE_TARGET,
                        nextCursor = 1,
                    )
                }
            }
        }
        var scanned = if (offset <= 1) 0 else offset
        var hasMore = true
        var rounds = 0
        while (hasMore && collected.size < PAGE_TARGET && rounds < MAX_SCAN_ROUNDS) {
            rounds++
            val page = objectRequest(
                "/notifications.json?offset=$scanned&limit=$NOTIFICATION_SCAN_LIMIT$typeParam",
                // 第 2 轮起走突发间隔。低频分类(私信)可能要连扫几轮才凑够一页,
                // 每轮都等满 1 秒的话用户要干等 5 秒;轮数有上限,峰值是封顶的。
                burst = rounds > 1 || offset == 0,
            )
            val notices = page.objects("notifications")
            // 游标按请求的 limit 走,不按返回条数 —— 服务端是先分页、再剔除不可访问的话题,
            // 按返回条数累加会让游标落后、下一页重复拉同一批。
            scanned += NOTIFICATION_SCAN_LIMIT
            val total = page.number("total_rows_notifications")
            // 同理,"这一页不满"不能当作已到末尾,有总数就按总数判断。
            hasMore = total?.let { scanned < it } ?: (notices.size >= NOTIFICATION_SCAN_LIMIT)
            collected += notices.map(::toNotificationEntry)
                .filter { filter.includes(it.notificationType) && seenIds.add(it.id) }
        }
        return ProfilePage(entries = collected, hasMore = hasMore, nextCursor = scanned)
    }

    private fun toNotificationEntry(notice: JsonObject): ProfileEntry {
        val data = notice["data"] as? JsonObject ?: JsonObject(emptyMap())
        val rawType = notice.text("notification_type").toIntOrNull() ?: 0
        val message = data.text("message")
        val resolvedType = when {
            rawType == 14 && message.contains("assigned", ignoreCase = true) -> 34
            else -> rawType
        }
        val resolvedPostNumber = notice.text("post_number").toIntOrNull()?.takeIf { it > 0 }
            ?: data.text("original_post_number").toIntOrNull()?.takeIf { it > 0 }
            ?: data.text("post_number").toIntOrNull()?.takeIf { it > 0 }
        val badgeName = data.text("badge_name")
        val topicTitle = notice.text("fancy_title").ifBlank { data.text("fancy_title") }.ifBlank { data.text("topic_title") }
        val rawTitle = when {
            resolvedType == 12 && badgeName.isNotBlank() -> "获得了 '$badgeName'"
            resolvedType == 800 && topicTitle.isBlank() -> "已开始关注您。"
            else -> topicTitle
        }
        val baseActor = data.text("display_username")
            .ifBlank { data.text("original_username") }
            .ifBlank { data.text("username") }
            .ifBlank { notice.text("acting_user_name") }
            .ifBlank { null }
        val count = data.text("count").toIntOrNull() ?: 0
        val resolvedActor = if (baseActor != null && count > 1) {
            "$baseActor 和其他 ${count - 1} 人"
        } else {
            baseActor
        }
        val label = notificationLabel(resolvedType.toString())
        return ProfileEntry(
            id = notice.text("id"),
            title = Jsoup.parseBodyFragment(rawTitle).text().ifBlank { label },
            subtitle = listOf(label, resolvedActor.orEmpty(), notice.text("created_at").take(10))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            topicId = notice.number("topic_id") ?: data.number("topic_id"),
            unread = notice.text("read") == "false",
            notificationType = resolvedType,
            avatarUrl = avatarUrlOf(
                notice.text("acting_user_avatar_template").ifBlank { data.text("avatar_template") },
                notice.text("acting_user_animated_avatar").ifBlank { data.text("animated_avatar") }.ifBlank { null },
                size = 64,
            ),
            actor = resolvedActor,
            postNumber = resolvedPostNumber,
        )
    }

    private suspend fun objectRequest(path: String, burst: Boolean = false): JsonObject =
        json.parseToJsonElement(request(path, burst)).jsonObject
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.arrayObjects(key: String): List<JsonObject> =
        (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun activityLabel(type: String): String = when (type) {
        "1" -> "点赞"; "2" -> "收到赞"; "4" -> "发表话题"; "5" -> "回复"; "6" -> "收到回复"
        "7" -> "被提及"; "9" -> "引用"; "11", "12" -> "消息"; else -> "活动"
    }

    private fun notificationLabel(type: String): String = when (type) {
        "1", "15" -> "提及"; "2" -> "回复"; "3" -> "引用"; "4" -> "编辑"; "5", "19" -> "收到赞"
        "6", "7", "16" -> "私信"; "8" -> "接受邀请"; "9", "17" -> "发布话题"; "10" -> "移动话题"
        "11", "38" -> "链接"; "12" -> "获赠徽章"; "13" -> "话题邀请"; "14" -> "解决方案"
        "25" -> "表情回应"; "27" -> "活动提醒"; "28" -> "活动邀请"; "29", "32" -> "聊天提及"
        "30" -> "聊天消息"; "31" -> "聊天邀请"; "33" -> "聊天引用"; "34" -> "指派"; "43" -> "简评"
        "800" -> "关注"; "801" -> "关注的人发布话题"; "802" -> "关注的人回复"; else -> "社区通知"
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

    fun cachedTagList(): List<DiscourseTag>? = tagListCache

    fun seedTagsFromJson(rawJson: String) {
        try {
            val list = json.decodeFromString<DiscourseTagsResponse>(rawJson).allTagsSorted()
            if (list.isNotEmpty()) {
                tagListCache = list
            }
        } catch (error: Exception) {
            diagnostics.log("预填标签缓存失败: ${error.message}")
        }
    }

    suspend fun fetchTags(): List<DiscourseTag> = tagGate.withLock {
        tagListCache ?: json.decodeFromString<DiscourseTagsResponse>(
            request("/tags.json"),
        ).allTagsSorted().also { tagListCache = it }
    }

    suspend fun refreshTagsFromServer(): Pair<List<DiscourseTag>, String>? = tagGate.withLock {
        val raw = request("/tags.json")
        val freshList = json.decodeFromString<DiscourseTagsResponse>(raw).allTagsSorted()
        val changed = tagListCache != freshList
        tagListCache = freshList
        if (changed) freshList to raw else null
    }

    suspend fun fetchTagTopics(
        tag: String,
        page: Int,
        present: Boolean = true,
        order: String? = null,
        ascending: Boolean? = null,
    ): TopicPage {
        val params = mutableListOf<String>()
        if (page > 0) {
            params.add("page=$page")
        }
        if (!order.isNullOrBlank()) {
            params.add("order=$order")
            if (ascending != null) {
                params.add("ascending=$ascending")
            }
        }
        val querySuffix = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        val encodedTag = encode(tag).replace("+", "%20")
        val raw = try {
            request(
                "/tag/$encodedTag/l/latest.json$querySuffix",
                burst = page == 0,
                present = present,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: NeedsInteractiveVerification) {
            throw error
        } catch (_: Exception) {
            val fallbackQuery = if (params.isEmpty()) "?page=$page" else "?page=$page&${params.filterNot { it.startsWith("page=") }.joinToString("&")}".trimEnd('&')
            request(
                "/tag/$encodedTag.json$fallbackQuery",
                burst = page == 0,
                present = present,
            )
        }
        return withContext(Dispatchers.Default) {
            toPage(
                json.decodeFromString<TopicListResponse>(raw),
                awaitCategories = false,
            )
        }
    }

    /** 返回当前登录用户名;未登录(Discourse 返回 404)时为 null。这才是"登录态是否有效"的真实判断。 */
    suspend fun fetchCurrentUser(): String? {
        val user = fetchCurrentUserDetails()
        user?.id?.takeIf { it > 0L }?.let { sessionStore?.saveCurrentUserId(it) }
        return user?.username
    }

    suspend fun fetchCurrentUserDetails(): DiscourseUser? {
        val generation = sessionStore?.sessionGeneration
        val body = try {
            request("/session/current.json")
        } catch (error: HttpStatusException) {
            if (error.status == 404) return null
            throw error
        }
        val user = json.parseToJsonElement(body).jsonObject["current_user"]?.jsonObject ?: return null
        val id = user["id"]?.jsonPrimitive?.longOrNull ?: 0L
        val username = user["username"]?.jsonPrimitive?.contentOrNull ?: return null
        if (id <= 0L || username.isBlank()) throw IllegalStateException("会话响应格式不正确")
        if (generation != sessionStore?.sessionGeneration) throw CancellationException("会话已切换")
        if (generation != null) withContext(Dispatchers.Main) { sessionStore?.normalizeVerifiedAuthCookies(generation) }
        val animatedFromSession = user["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf(::isAnimatedAvatarPath)
        val animated = animatedFromSession
            ?: AvatarCache.get(username)?.takeIf(::isAnimatedAvatarPath)
            ?: resolveAnimatedAvatar(username)
        if (!animated.isNullOrBlank()) {
            AvatarCache.record(username, animated)
        }
        return DiscourseUser(
            id = id,
            username = username,
            name = user["name"]?.jsonPrimitive?.contentOrNull,
            avatarTemplate = user["avatar_template"]?.jsonPrimitive?.contentOrNull,
            animatedAvatar = animated,
            trustLevel = user["trust_level"]?.jsonPrimitive?.intOrNull,
        )
    }

    suspend fun fetchTrustReport(): TrustReport? {
        var html = try {
            request("https://connect.linux.do/")
        } catch (error: Exception) {
            diagnostics.log("拉取 Connect 升级进度失败: ${error.message}")
            return null
        }

        // 检查是否触发 Discourse Connect SSO 回调跳转
        if (html.contains("redirect_url") && html.contains("sso_callback")) {
            val redirectUrl = runCatching {
                json.parseToJsonElement(html).jsonObject["redirect_url"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!redirectUrl.isNullOrBlank()) {
                diagnostics.log("Connect 触发 SSO 授权，自动完成握手: $redirectUrl")
                try {
                    val ssoResp = request(redirectUrl)
                    html = if (ssoResp.isNotBlank() && ssoResp.contains("tl3-ring")) {
                        ssoResp
                    } else {
                        request("https://connect.linux.do/")
                    }
                } catch (error: Exception) {
                    diagnostics.log("Connect SSO 握手完成或重定向: ${error.message}")
                    html = try { request("https://connect.linux.do/") } catch (_: Exception) { html }
                }
            }
        }

        val report = TrustReport.fromHtml(html)
        if (report == null) {
            diagnostics.log("Connect 页面解析失败, HTML 长度=${html.length}, 内容: ${html.take(1000)}")
        }
        return report
    }

    /**
     * 获取 LINUX DO Credit 用户积分信息。
     * GET https://credit.linux.do/api/v1/oauth/user-info
     */
    suspend fun fetchLdcUserInfo(): LdcUserInfo {
        val raw = request("https://credit.linux.do/api/v1/oauth/user-info", present = false)
        val root = json.parseToJsonElement(raw).jsonObject
        val dataElem = root["data"] as? JsonObject ?: throw IllegalStateException("LDC 响应缺少 data 字段")
        fun readStr(key: String): String = (dataElem[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun readInt(key: String): Int = (dataElem[key] as? JsonPrimitive)?.intOrNull ?: (readStr(key).toIntOrNull() ?: 0)
        fun readLong(key: String): Long = (dataElem[key] as? JsonPrimitive)?.longOrNull ?: (readStr(key).toLongOrNull() ?: 0L)
        fun readBool(key: String): Boolean = (dataElem[key] as? JsonPrimitive)?.booleanOrNull ?: (readStr(key).toBooleanStrictOrNull() ?: false)
        return LdcUserInfo(
            id = readLong("id"),
            username = readStr("username"),
            nickname = readStr("nickname"),
            trustLevel = readInt("trust_level"),
            avatarUrl = readStr("avatar_url"),
            totalReceive = readStr("total_receive").ifBlank { "0" },
            totalPayment = readStr("total_payment").ifBlank { "0" },
            totalTransfer = readStr("total_transfer").ifBlank { "0" },
            totalCommunity = readStr("total_community").ifBlank { "0" },
            communityBalance = readStr("community_balance").ifBlank { "0" },
            availableBalance = readStr("available_balance").ifBlank { "0" },
            payScore = readInt("pay_score"),
            isPayKey = readBool("is_pay_key"),
            isAdmin = readBool("is_admin"),
            remainQuota = readStr("remain_quota").ifBlank { "0" },
            payLevel = readInt("pay_level"),
            dailyLimit = readInt("daily_limit"),
        )
    }

    /**
     * 获取 CDK 社区分(score,-100~100,由 CDK 服务端按徽章结算)。
     * GET https://cdk.linux.do/api/v1/oauth/user-info,依赖 CDK 自己的会话 Cookie,
     * 未授权时返回 401,由调用方走 [authorizeCdk] 后重试。
     */
    suspend fun fetchCdkScore(): Int {
        val raw = request("https://cdk.linux.do/api/v1/oauth/user-info", present = false)
        val root = json.parseToJsonElement(raw).jsonObject
        val data = root["data"] as? JsonObject ?: throw IllegalStateException("CDK 响应缺少 data 字段")
        val primitive = data["score"] as? JsonPrimitive ?: throw IllegalStateException("CDK 响应缺少 score 字段")
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
            ?: throw IllegalStateException("CDK score 格式不正确")
    }

    /**
     * LINUX DO Credit 应用内静默/快速 OAuth 授权流程。
     * 1. GET https://credit.linux.do/api/v1/oauth/login -> 获取 authUrl
     * 2. GET authUrl (不自动重定向) -> 若 302 直接提取 code/state; 若 200 解析 HTML 的 a[href*="/oauth2/approve/"] 并 GET 之
     * 3. 提取 302 重定向 Location 中的 code 与 state
     * 4. POST https://credit.linux.do/api/v1/oauth/callback (携带 code, state 及 X-Requested-With: XMLHttpRequest)
     */
    private data class LdcOAuthResponse(
        val status: Int,
        val location: String?,
        val body: String,
    )

    private fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url.replace(Regex("(?i)(code|state|token|client_secret|authenticity_token)=([^&]+)"), "$1=***")
    }

    /**
     * OAuth 请求接入进程级双轨调度器：
     * GET 请求受 [getSemaphore] (2 并发) 与滑动窗口 (4次/3秒) 约束；
     * POST 请求受 [postGate] (1 并发绝对串行) 约束。
     * 遇到 429 时统一进入全局退让，杜绝旁路超限穿透。
     */
    private suspend fun executeLdcOAuthRequest(
        client: OkHttpClient,
        request: Request,
        label: String,
    ): LdcOAuthResponse {
        val isPost = request.method.equals("POST", ignoreCase = true)
        val executeCall = suspend {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 429) {
                    val retryAfter = HttpHeaderUtils.extractRetryAfterSeconds(
                        retryAfterHeader = response.header("Retry-After"),
                        resetHeader = response.header("x-ratelimit-reset") ?: response.header("ratelimit-reset"),
                        body = body,
                    )
                    throw enterRateLimitCooldown(label, retryAfter)
                }
                LdcOAuthResponse(
                    status = response.code,
                    location = response.header("Location"),
                    body = body,
                )
            }
        }
        return if (isPost) {
            postGate.withLock {
                throttlePost(burst = true)
                try {
                    executeCall()
                } finally {
                    markPostCompleted()
                }
            }
        } else {
            getSemaphore.withPermit {
                acquireGetSlot { false }
                executeCall()
            }
        }
    }

    suspend fun authorizeLdc(): Boolean = authorizeOAuthSite("credit.linux.do", "LDC")

    /** CDK 与 LDC 是同一套 OAuth 握手(login -> connect approve -> callback),仅站点不同。 */
    suspend fun authorizeCdk(): Boolean = authorizeOAuthSite("cdk.linux.do", "CDK")

    private suspend fun authorizeOAuthSite(host: String, label: String): Boolean = ldcAuthGate.withLock {
        withContext(Dispatchers.IO) {
            val client = httpClientProvider?.invoke() ?: run {
                diagnostics.log("authorize$label 失败: 未提供 httpClientProvider")
                return@withContext false
            }

            try {
                // 先同步 WebView Cookie 到 store
                sessionStore?.let { s ->
                    withContext(Dispatchers.Main) { s.syncFromWebView() }
                }

                diagnostics.log("开始 $label OAuth 授权流程...")

                // 1. GET /api/v1/oauth/login
                val loginReq = Request.Builder()
                    .url("https://$host/api/v1/oauth/login")
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()

                val loginResp = executeLdcOAuthRequest(client, loginReq, "$label OAuth login")
                if (loginResp.status !in 200..299) {
                    diagnostics.log("$label OAuth login 请求失败: HTTP ${loginResp.status}")
                    return@withContext false
                }
                val loginBody = loginResp.body
                val authUrl = runCatching {
                    val elem = json.parseToJsonElement(loginBody).jsonObject
                    (elem["data"] as? JsonPrimitive)?.contentOrNull
                }.getOrNull()

                if (authUrl.isNullOrBlank()) {
                    diagnostics.log("$label OAuth 获取 authUrl 失败: $loginBody")
                    return@withContext false
                }

                diagnostics.log("$label OAuth 获得 authUrl: ${sanitizeUrl(authUrl)}")

                // 创建禁止自动重定向的 client，用于捕获 302 Location
                val noRedirectClient = client.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                // 2. GET authUrl
                val authReq = Request.Builder()
                    .url(authUrl)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()

                val authResp = executeLdcOAuthRequest(noRedirectClient, authReq, "$label OAuth authorize")
                val authCode = authResp.status
                var code: String? = null
                var state: String? = null

                if (authCode in 300..399) {
                    val loc = authResp.location
                    diagnostics.log("$label OAuth 直接 302 重定向: ${sanitizeUrl(loc)}")
                    if (!loc.isNullOrBlank()) {
                        code = extractQueryParam(loc, "code")
                        state = extractQueryParam(loc, "state")
                    }
                } else if (authCode == 200) {
                    val html = authResp.body
                    val doc = Jsoup.parse(html)
                    val approveLink = doc.select("a[href*=/oauth2/approve/]").firstOrNull()?.attr("href")
                    if (!approveLink.isNullOrBlank()) {
                        val approveUrl = if (approveLink.startsWith("http")) approveLink else "https://connect.linux.do$approveLink"
                        // 拟人化延迟：模拟人类用户查看授权页并点击“同意”的自然停顿（600~950ms），消除秒级机器特征
                        delay(Random.nextLong(600L, 950L))
                        diagnostics.log("$label OAuth 发起 approve 链接请求: ${sanitizeUrl(approveUrl)}")

                        val approveReq = Request.Builder()
                            .url(approveUrl)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .get()
                            .build()

                        val approveResp = executeLdcOAuthRequest(noRedirectClient, approveReq, "$label OAuth approve")
                        val loc = approveResp.location
                        diagnostics.log("$label OAuth approve 响应: ${approveResp.status}, Location: ${sanitizeUrl(loc)}")
                        if (!loc.isNullOrBlank()) {
                            code = extractQueryParam(loc, "code")
                            state = extractQueryParam(loc, "state")
                        }
                    } else {
                        // 尝试表单 POST 形式的授权（Rails / Discourse 常见表单）
                        val form = doc.select("form").firstOrNull()
                        val csrfToken = doc.select("input[name=authenticity_token]").firstOrNull()?.attr("value")
                        if (form != null && !csrfToken.isNullOrBlank()) {
                            val formAction = form.attr("action").ifBlank { authUrl }
                            val actionUrl = if (formAction.startsWith("http")) formAction else "https://connect.linux.do$formAction"
                            diagnostics.log("$label OAuth 发起表单 POST 授权: ${sanitizeUrl(actionUrl)}")
                            val formBodyBuilder = okhttp3.FormBody.Builder()
                                .add("authenticity_token", csrfToken)
                                .add("commit", "Authorize")
                            form.select("input[type=hidden]").forEach { hidden ->
                                val name = hidden.attr("name")
                                val value = hidden.attr("value")
                                if (name.isNotBlank() && name != "authenticity_token") {
                                    formBodyBuilder.add(name, value)
                                }
                            }
                            val formReq = Request.Builder()
                                .url(actionUrl)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .post(formBodyBuilder.build())
                                .build()
                            val formResp = executeLdcOAuthRequest(noRedirectClient, formReq, "$label OAuth form approve")
                            val loc = formResp.location
                            diagnostics.log("$label OAuth 表单授权响应: ${formResp.status}, Location: ${sanitizeUrl(loc)}")
                            if (!loc.isNullOrBlank()) {
                                code = extractQueryParam(loc, "code")
                                state = extractQueryParam(loc, "state")
                            }
                        } else {
                            diagnostics.log("$label OAuth 未在同意页找到 approve 链接或表单, HTML 长度=${html.length}")
                            return@withContext false
                        }
                    }
                } else {
                    diagnostics.log("$label OAuth 请求 authUrl 异常状态码: $authCode")
                    return@withContext false
                }

                if (code.isNullOrBlank() || state.isNullOrBlank()) {
                    diagnostics.log("$label OAuth 未能提取到 code 或 state")
                    return@withContext false
                }

                diagnostics.log("$label OAuth 提取到 code 与 state，正在提交 callback...")
                // 拟人化延迟：模拟浏览器处理 302 重定向并提交回调的自然间隔（500~850ms）
                delay(Random.nextLong(500L, 850L))

                // 3. POST https://credit.linux.do/api/v1/oauth/callback
                // 注意：必须携带 X-Requested-With: XMLHttpRequest，否则 credit.linux.do 会触发 CSRF 校验失败 403
                val callbackJson = """{"code":"$code","state":"$state"}"""
                val callbackReq = Request.Builder()
                    .url("https://$host/api/v1/oauth/callback")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(callbackJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val callbackResp = executeLdcOAuthRequest(client, callbackReq, "$label OAuth callback")
                val callbackBody = callbackResp.body
                if (callbackResp.status !in 200..299) {
                    diagnostics.log("$label OAuth callback 失败: HTTP ${callbackResp.status}, $callbackBody")
                    return@withContext false
                }

                withContext(Dispatchers.Main) {
                    runCatching {
                        android.webkit.CookieManager.getInstance().flush()
                    }
                }

                diagnostics.log("$label OAuth 授权握手圆满成功: $callbackBody")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.log("$label OAuth 流程发生异常: ${e.message}")
                false
            }
        }
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val query = url.substring(queryStart + 1)
        for (part in query.split('&')) {
            val kv = part.split('=', limit = 2)
            if (kv.isNotEmpty()) {
                val k = runCatching { java.net.URLDecoder.decode(kv[0], "UTF-8") }.getOrDefault(kv[0])
                if (k == key) {
                    val v = if (kv.size > 1) kv[1] else ""
                    return runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                }
            }
        }
        return null
    }

    /**
     * 从服务端拉取自定义侧边栏分区（与 PC 端打通）。
     * 若用户在 PC 端配置了自定义侧边栏分区，拉取并将其中的 FontAwesome 图标还原为移动端高颜值 Emoji。
     */
    suspend fun fetchCustomSidebarSection(): CustomSection? {
        val raw = try {
            request("/sidebar_sections.json")
        } catch (error: Exception) {
            diagnostics.log("拉取自定义侧边栏分区失败: ${error.message}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val sections = root.objects("sidebar_sections")
        // 查找属于用户的自定义版块：section_type != "community"，且 public != true
        val customSectionObj = sections.firstOrNull { sec ->
            val type = sec.text("section_type")
            val isPublic = (sec.get("public") as? JsonPrimitive)?.booleanOrNull == true
            type != "community" && !isPublic
        } ?: return null

        val serverId = customSectionObj.number("id")
        val title = customSectionObj.text("title").ifBlank { "自定义版块" }
        val linkObjs = customSectionObj.objects("links")
        val links = linkObjs.map { linkObj ->
            val linkServerId = linkObj.number("id")
            CustomSectionLink(
                id = linkServerId?.toString() ?: java.util.UUID.randomUUID().toString(),
                icon = CustomSectionIconMapper.toDisplayIcon(linkObj.text("icon")),
                name = linkObj.text("name"),
                url = linkObj.text("value"),
                serverId = linkServerId,
            )
        }
        return CustomSection(
            id = "custom_section_server_${serverId ?: "default"}",
            title = title,
            links = links,
            serverId = serverId,
        )
    }

    /**
     * 将自定义版块保存/同步至 Discourse 服务端（与 PC 端打通）。
     * [oldServerLinkIds] 用于在更新时告知后端已删除的 link 需要带上 _destroy: true。
     */
    suspend fun saveCustomSidebarSection(
        section: CustomSection,
        oldServerLinkIds: List<Long> = emptyList(),
    ): CustomSection {
        val serverId = section.serverId
        val linksArray = mutableListOf<JsonObject>()

        // 现存条目
        section.links.forEach { link ->
            val linkMap = mutableMapOf<String, JsonElement>(
                "name" to JsonPrimitive(link.name),
                "value" to JsonPrimitive(link.url),
                "icon" to JsonPrimitive(CustomSectionIconMapper.toFontAwesome(link.icon)),
            )
            if (link.serverId != null && link.serverId > 0L) {
                linkMap["id"] = JsonPrimitive(link.serverId)
            }
            linksArray.add(JsonObject(linkMap))
        }

        // 被删除的条目（通知 Rails 删除对应记录）
        val currentServerIds = section.links.mapNotNull { it.serverId }.toSet()
        oldServerLinkIds.filter { it !in currentServerIds }.forEach { removedId ->
            linksArray.add(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(removedId),
                        "_destroy" to JsonPrimitive(true),
                        "name" to JsonPrimitive(""),
                        "value" to JsonPrimitive(""),
                        "icon" to JsonPrimitive("link"),
                    ),
                ),
            )
        }

        val payload = JsonObject(
            mapOf(
                "title" to JsonPrimitive(section.title),
                "links" to JsonArray(linksArray),
            ),
        ).toString()

        val responseBody = if (serverId != null && serverId > 0L) {
            mutate("/sidebar_sections/$serverId.json", "PUT", payload)
        } else {
            mutate("/sidebar_sections.json", "POST", payload)
        }

        // 解析返回结果中的新 ID
        val returnedObj = runCatching {
            val element = json.parseToJsonElement(responseBody).jsonObject
            (element["sidebar_section"] as? JsonObject) ?: element
        }.getOrNull()

        val newServerId = returnedObj?.number("id") ?: serverId
        val returnedLinks = returnedObj?.objects("links").orEmpty()
        val mappedLinks = section.links.mapIndexed { index, link ->
            val matching = returnedLinks.getOrNull(index)
                ?: returnedLinks.firstOrNull { it.text("name") == link.name && it.text("value") == link.url }
            val linkServerId = matching?.number("id") ?: link.serverId
            link.copy(
                serverId = linkServerId,
                id = linkServerId?.toString() ?: link.id,
            )
        }

        return section.copy(
            id = "custom_section_server_${newServerId ?: "default"}",
            serverId = newServerId,
            links = mappedLinks,
        )
    }

    /**
     * 从服务端删除自定义侧边栏分区。
     */
    suspend fun deleteCustomSidebarSection(serverId: Long) {
        if (serverId <= 0L) return
        try {
            mutate("/sidebar_sections/$serverId.json", "DELETE")
        } catch (e: Exception) {
            diagnostics.log("删除服务端自定义侧边栏分区失败: ${e.message}")
        }
    }

    /**
     * 使用账号密码登录（支持两步验证 2FA：TOTP 动态口令与备用恢复码）。
     * 流程：GET /session/csrf → POST /session。
     * 成功返回 true，凭据错误返回 false，需要两步验证抛 SecondFactorRequiredException，
     * CF 挑战抛 NeedsInteractiveVerification，连不上服务器抛 NetworkTimeoutException。
     */
    suspend fun sessionLogin(
        login: String,
        password: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ): Boolean {
        return postGate.withLock {
            throttlePost()
            val transport = transportProvider()
            val csrfDeadlineNs = System.nanoTime() + Config.LOGIN_CALL_TIMEOUT_MS * 1_000_000L
            val csrf = loginCsrf(transport, csrfDeadlineNs)

            throttlePost(burst = true)
            val loginDeadlineNs = System.nanoTime() + Config.LOGIN_CALL_TIMEOUT_MS * 1_000_000L
            val fields = mutableMapOf<String, JsonElement>(
                "login" to JsonPrimitive(login),
                "password" to JsonPrimitive(password),
            )
            val trimmedToken = secondFactorToken?.trim().orEmpty()
            if (trimmedToken.isNotEmpty()) {
                fields["second_factor_token"] = JsonPrimitive(trimmedToken)
                fields["second_factor_method"] = JsonPrimitive(secondFactorMethod)
            }
            val body = JsonObject(fields).toString()
            val loginResult = try {
                loginCall("/session.json", loginDeadlineNs) { transport.write("/session.json", "POST", body, csrf) }
            } finally {
                markPostCompleted()
            }
            diagnostics.recordStatus(transport.name, loginResult.status, "/session")
            if (loginResult.status == 429) {
                enterRateLimitCooldown("/session.json", loginResult.retryAfterSeconds)
                throw HttpStatusException(429, "登录尝试过于频繁，请稍后再试")
            }
            if (loginResult.challenged) throw NeedsInteractiveVerification("登录请求被 Cloudflare 拦截 (403 挑战)")
            if (loginResult.status == 403) throw RiskControlException(403, "登录请求触发风控 (403)")
            if (loginResult.status == 0) throw NetworkTimeoutException()
            val responseObj = try {
                json.parseToJsonElement(loginResult.body).jsonObject
            } catch (e: Exception) {
                if (loginResult.status in 200..299) return@withLock true
                throw HttpStatusException(loginResult.status, "HTTP ${loginResult.status}: ${loginResult.body.take(150)}")
            }
            val error = responseObj["error"]?.jsonPrimitive?.contentOrNull
                ?: responseObj["message"]?.jsonPrimitive?.contentOrNull
            val reason = responseObj["reason"]?.jsonPrimitive?.contentOrNull
            val totpEnabled = responseObj["totp_enabled"]?.jsonPrimitive?.booleanOrNull == true
            val backupEnabled = responseObj["backup_enabled"]?.jsonPrimitive?.booleanOrNull == true
            val securityKeyEnabled = responseObj["security_key_enabled"]?.jsonPrimitive?.booleanOrNull == true
            val hasMulti2fa = responseObj["multiple_second_factor_methods"] != null
            val isSecondFactor = reason == "second_factor" ||
                reason == "invalid_second_factor" ||
                reason == "invalid_second_factor_method" ||
                reason?.contains("second_factor", ignoreCase = true) == true ||
                totpEnabled || backupEnabled || securityKeyEnabled || hasMulti2fa ||
                (trimmedToken.isEmpty() && !error.isNullOrBlank() && (
                    error.contains("双重身份验证") ||
                        error.contains("两步") ||
                        error.contains("二步") ||
                        error.contains("second factor", ignoreCase = true)
                ))
            if (isSecondFactor) {
                if (trimmedToken.isEmpty()) {
                    diagnostics.log("账号已开启 2FA，等待输入两步验证码")
                    throw SecondFactorRequiredException(
                        totpEnabled = totpEnabled || !backupEnabled,
                        backupEnabled = backupEnabled,
                        message = error?.takeIf { it.isNotBlank() } ?: "该账号已开启两步验证，请输入验证码",
                    )
                } else {
                    diagnostics.log("2FA 验证码错误: $error")
                    throw LoginRejectedException(error?.takeIf { it.isNotBlank() } ?: "两步验证码错误或已过期，请重试")
                }
            }
            if (!error.isNullOrBlank()) {
                diagnostics.log("登录失败: $error")
                throw LoginRejectedException(error)
            }
            if (loginResult.status !in 200..299) {
                throw HttpStatusException(loginResult.status, "HTTP ${loginResult.status}: ${loginResult.body.take(150)}")
            }
            if (responseObj["user"] == null) {
                throw LoginRejectedException("登录未返回有效用户凭据，响应: ${loginResult.body.take(150)}")
            }
            true
        }
    }

    /**
     * 向指定邮箱发送一次性登录链接邮件（Discourse magic link）。
     * 用户从邮件链接中复制 token，再交由 ViewModel.loginWithEmailCode 处理。
     */
    suspend fun sendEmailLogin(email: String) {
        postGate.withLock {
            throttlePost()
            val transport = transportProvider()
            val csrfDeadlineNs = System.nanoTime() + Config.LOGIN_CALL_TIMEOUT_MS * 1_000_000L
            val csrf = loginCsrf(transport, csrfDeadlineNs)
            throttlePost(burst = true)
            val sendDeadlineNs = System.nanoTime() + Config.LOGIN_CALL_TIMEOUT_MS * 1_000_000L
            val safeEmail = email.replace("\"", "\\\"")
            val body = "{\"login\":\"$safeEmail\"}"
            val result = try {
                loginCall("/u/email-login.json", sendDeadlineNs) { transport.write("/u/email-login.json", "POST", body, csrf) }
            } finally {
                markPostCompleted()
            }
            diagnostics.recordStatus(transport.name, result.status, "/u/email-login")
            if (result.challenged) throw NeedsInteractiveVerification("发邮件请求被 Cloudflare 拦截")
            if (result.status == 429) {
                enterRateLimitCooldown("/u/email-login.json", result.retryAfterSeconds)
                throw HttpStatusException(429, "发送登录邮件过于频繁，请稍后再试")
            }
            if (result.status == 403) throw RiskControlException(403, "发送登录邮件触发风控 (403)")
            if (result.status == 0) throw NetworkTimeoutException()
            if (result.status !in 200..299) throw HttpStatusException(result.status, result.body.take(120))
        }
    }

    private suspend fun loginCsrf(transport: Transport, deadlineNs: Long = 0L): String {
        val result = getSemaphore.withPermit {
            acquireGetSlot { false }
            loginCall("/session/csrf.json", deadlineNs) { transport.get("/session/csrf.json") }
        }
        diagnostics.recordStatus(transport.name, result.status, "/session/csrf")
        if (result.challenged) throw NeedsInteractiveVerification("CSRF 请求被 Cloudflare 拦截")
        if (result.status == 429) {
            enterRateLimitCooldown("/session/csrf.json", result.retryAfterSeconds)
            throw HttpStatusException(429, "获取凭据过于频繁，请稍后再试")
        }
        if (result.status == 403) throw RiskControlException(403, "获取登录凭证触发风控 (403)")
        if (result.status == 0) throw NetworkTimeoutException()
        if (result.status !in 200..299) throw HttpStatusException(result.status, "无法获取 CSRF token")
        return json.parseToJsonElement(result.body).jsonObject["csrf"]
            ?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("CSRF 响应缺少 token 字段")
    }

    /**
     * 用邮件里的 token 完成登录（支持携带两步验证码）。
     */
    suspend fun emailLoginWithToken(
        token: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        postGate.withLock {
            throttlePost()
            val transport = transportProvider()
            val csrfDeadlineNs = System.nanoTime() + Config.LOGIN_CALL_TIMEOUT_MS * 1_000_000L
            val csrf = loginCsrf(transport, csrfDeadlineNs)
            throttlePost(burst = true)
            val loginDeadlineNs = System.nanoTime() + Config.LOGIN_CALL_TIMEOUT_MS * 1_000_000L
            val path = "/session/email-login/${encode(token)}"
            val trimmed2fa = secondFactorToken?.trim().orEmpty()
            val reqBody = if (trimmed2fa.isNotEmpty()) {
                JsonObject(
                    mapOf(
                        "second_factor_token" to JsonPrimitive(trimmed2fa),
                        "second_factor_method" to JsonPrimitive(secondFactorMethod),
                    ),
                ).toString()
            } else {
                "{}"
            }
            val result = try {
                loginCall(path, loginDeadlineNs) { transport.write(path, "POST", reqBody, csrf) }
            } finally {
                markPostCompleted()
            }
            diagnostics.recordStatus(transport.name, result.status, "/session/email-login")
            if (result.status == 429) {
                enterRateLimitCooldown(path, result.retryAfterSeconds)
                throw HttpStatusException(429, "验证邮件令牌过于频繁，请稍后再试")
            }
            if (result.challenged) throw NeedsInteractiveVerification("邮件登录请求被 Cloudflare 拦截")
            if (result.status == 403) throw RiskControlException(403, "验证码登录触发风控 (403)")
            if (result.status == 0) throw NetworkTimeoutException()

        val body = runCatching { json.parseToJsonElement(result.body).jsonObject }.getOrNull()
        val serverError = body?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val reason = body?.get("reason")?.jsonPrimitive?.contentOrNull
        val secondFactorRequired = body?.get("second_factor_required")?.jsonPrimitive?.booleanOrNull == true ||
            body?.get("totp_enabled")?.jsonPrimitive?.booleanOrNull == true ||
            body?.get("backup_enabled")?.jsonPrimitive?.booleanOrNull == true ||
            body?.get("security_key_enabled")?.jsonPrimitive?.booleanOrNull == true ||
            reason == "second_factor" ||
            reason == "invalid_second_factor" ||
            reason == "invalid_second_factor_method" ||
            reason?.contains("second_factor", ignoreCase = true) == true ||
            (trimmed2fa.isEmpty() && serverError != null && (
                serverError.contains("双重身份验证") ||
                    serverError.contains("两步") ||
                    serverError.contains("二步") ||
                    serverError.contains("验证") ||
                    serverError.contains("second factor", ignoreCase = true) ||
                    serverError.contains("authentication code", ignoreCase = true)
                ))
        if (secondFactorRequired && trimmed2fa.isEmpty()) {
            diagnostics.log("邮件登录要求 2FA: ${serverError ?: "second_factor_required"}")
            throw SecondFactorRequiredException(
                totpEnabled = true,
                backupEnabled = true,
                message = "该账号已开启两步验证，请输入两步验证码",
            )
        }
        if (serverError != null) {
            diagnostics.log("邮件登录被拒: $serverError")
            throw LoginRejectedException(serverError)
        }
        if (result.status !in 200..299) {
            throw LoginRejectedException("登录链接无效或已过期，请重新获取")
        }
    }
}

    /**
     * 登录链路的硬超时。
     * OkHttp 的 connectTimeout 只覆盖建连,DNS 解析卡住时不生效;同时把实际网络调用放在独立
     * SupervisorJob 异步协程中等待,即使底层发生不可中断的同步阻塞 I/O,withTimeout 也能在
     * 到达预算时立即脱离并取消底层请求,保证 6 秒总超时严格生效。
     */
    private suspend fun loginCall(
        path: String,
        deadlineNs: Long = 0L,
        call: suspend () -> TransportResult,
    ): TransportResult {
        val timeoutMs = if (deadlineNs > 0L) {
            ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtMost(Config.LOGIN_CALL_TIMEOUT_MS)
        } else {
            Config.LOGIN_CALL_TIMEOUT_MS
        }
        if (timeoutMs <= 0L) {
            diagnostics.log("登录请求超时: $path")
            throw NetworkTimeoutException()
        }
        val deferred = CoroutineScope(Dispatchers.IO + SupervisorJob()).async { call() }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            deferred.cancel()
            diagnostics.log("登录请求超时: $path")
            throw NetworkTimeoutException()
        } finally {
            deferred.cancel()
            lastPostRequestAt = SystemClock.elapsedRealtime()
        }
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

    private suspend fun toPage(
        response: TopicListResponse,
        awaitCategories: Boolean = true,
        excludeReadUnread: Boolean = false,
    ): TopicPage {
        val known = if (awaitCategories) categories() else categoryCache.orEmpty()
        val usersById = response.users.associateBy { it.id }
        response.users.forEach { u -> AvatarCache.record(u.username, u.animatedAvatar) }
        // 开启同步只合并成功回执；关闭同步才用本地浏览记录覆盖未读状态。
        val filteredTopics = response.topicList.topics.distinctBy { it.id }.filterNot { topic ->
            excludeReadUnread && reading?.isUnreadExcluded(topic.id, topic.postsCount, topic.highestPostNumber) == true
        }
        val items = filteredTopics.map { topic ->
            // 对齐 fluxdo：优先取首个 Original Poster（兼容 user_id 映射顶层 users 表与 poster.user 内嵌对象两种形状），
            // 缺失时再按其余 poster 或 lastPosterUsername 匹配，确保人员图标绝不因序列化形状差异而漏取。
            val author = topic.posters.firstNotNullOfOrNull { p ->
                (p.user ?: p.userId?.let { usersById[it] })?.also { u ->
                    AvatarCache.record(u.username, u.animatedAvatar)
                }
            } ?: topic.lastPosterUsername?.let { uname ->
                response.users.firstOrNull { it.username.equals(uname, ignoreCase = true) }
            }
            val cachedLevel = sessionStore?.getTopicNotificationLevel(topic.id)
            val effectiveLevelCode = cachedLevel ?: topic.notificationLevel?.takeIf { it in 0..3 && it != 1 }
            val notificationLevel = effectiveLevelCode?.let { TopicNotificationLevel.fromCode(it) }
            TopicListItem(
                topic = reading?.mergeReadState(topic) ?: topic,
                authorUsername = author?.username ?: topic.lastPosterUsername,
                authorName = author?.name,
                avatarUrl = avatarUrlOf(author?.avatarTemplate, author?.animatedAvatar, size = 64, username = author?.username),
                staticAvatarUrl = avatarUrlOf(author?.avatarTemplate, null, size = 64),
                category = topic.categoryId?.let { known[it] },
                trustLevel = author?.trustLevel,
                notificationLevel = notificationLevel,
            )
        }
        return TopicPage(
            items = items,
            hasMore = response.topicList.moreTopicsUrl != null && response.topicList.topics.isNotEmpty(),
        )
    }

    private fun ensureRequestAllowed() {
        if (sessionStore?.sessionChanging == true) throw CancellationException("会话正在切换")
    }

    /**
     * P0-1 carriedIsStale 判定：
     * 检查本次请求发出时携带的 [sentClearanceFp] 是否已落后于 WebView 当前权威 cf_clearance 指纹。
     * 若在途期间另一条并发请求或 WebView 已刷新凭据（且新凭据未被判死），返回 true 触发免 WebView 换值重放。
     */
    private suspend fun isCarriedClearanceStale(sentClearanceFp: String): Boolean {
        val store = sessionStore ?: return false
        if (store.sessionChanging) return false
        val currentClearance = withContext(Dispatchers.Main) {
            store.syncFromWebView()[Config.CF_CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }
        } ?: return false
        val currentFp = NetDiagnostics.fingerprint(currentClearance)
        return currentFp != "-" &&
            currentFp != sentClearanceFp &&
            !store.isClearanceChallenged(currentClearance)
    }

    private suspend fun request(
        path: String,
        burst: Boolean = false,
        present: Boolean = true,
        retryAllowed: Boolean = true,
        beforeDispatch: (() -> Unit)? = null,
    ): String {
        try {
            return pacedGet(path, burst, present, allow429Challenge = retryAllowed, retryAllowed = retryAllowed, beforeDispatch = beforeDispatch)
        } catch (challenge: NeedsInteractiveVerification) {
            if (!retryAllowed || !present) throw challenge
            // 对齐 v1.3.1 & P0-1/P0-3：撞盾先在隐藏 WebView 里按当前出口静默重签 cf_clearance（带锁内去重合并），成功再重试一次。
            // 换代理/切直连后凭据与出口不一致时，这一步让第一个被挑战的请求就把凭据换掉，用户无感。
            // 必须在串行闸门外做：静默过盾最长 30 秒，占着闸门会把所有 GET 堵死。
            if (!silentRevalidateForGet(path, challenge.failedClearanceFp)) {
                throw challenge
            }
            return try {
                pacedGet(path, burst, present, allow429Challenge = false, beforeDispatch = beforeDispatch)
            } catch (retryChallenge: NeedsInteractiveVerification) {
                // P0-4: CLEARED 后重放立撞 403 -> 判定为 cf_clearance 对 OkHttp 通道无效（典型：分应用代理/双栈出口不一致）
                // 静默过盾进入 60s 熔断，避免后续请求反复空转重载 WebView；仅对前台 GET (present=true) 尝试单次切浏览器栈重放。
                getRevalidateBlockedUntil = maxOf(
                    getRevalidateBlockedUntil,
                    SystemClock.elapsedRealtime() + CLEARANCE_MISMATCH_COOLDOWN_MS,
                )
                diagnostics.log(
                    "静默过盾后重放仍被挑战(sent=${retryChallenge.failedClearanceFp})，判定凭据与 OkHttp 出口不匹配，" +
                        "熔断静默过盾 ${CLEARANCE_MISMATCH_COOLDOWN_MS / 1_000L}s: $path",
                )
                if (present) {
                    val primary = transportProvider()
                    val fallback = fallbackTransportProvider?.invoke()?.takeIf { it.name != primary.name }
                    if (fallback != null && fallback.isReady()) {
                        diagnostics.log("前台 GET 尝试单次切换 ${fallback.name} 兜底重放: $path")
                        val fbResult = fallback.getWithoutRevalidate(path, present = true)
                        diagnostics.recordStatus(fallback.name, fbResult.status, path)
                        if (fbResult.status in 200..299) {
                            return fbResult.body
                        }
                    }
                }
                throw retryChallenge
            }
        }
    }

    /**
     * 前台 GET 直接走读轨道（多账号测试分支实测又快又稳，不额外加间隔）。
     * 后台静默 GET（摘要预取、新话题探测、静默未读）全进程串行、相邻至少 1 秒：
     * 后台批量吃满读轨道会出现秒级连拉 /t/{id}/1.json，被 CF 判为爬取而挑战。
     */
    private suspend fun pacedGet(
        path: String,
        burst: Boolean,
        present: Boolean,
        allow429Challenge: Boolean = true,
        retryAllowed: Boolean = true,
        beforeDispatch: (() -> Unit)? = null,
    ): String {
        if (present) return dispatchGet(path, burst, present, allow429Challenge, retryAllowed, beforeDispatch)
        return backgroundGetGate.withLock {
            val wait = Config.MIN_REQUEST_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastBackgroundGetAt)
            if (wait > 0) delay(wait)
            try {
                dispatchGet(path, burst, present, allow429Challenge, retryAllowed, beforeDispatch)
            } finally {
                lastBackgroundGetAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** GET 撞盾后的静默过盾；失败后 [GET_REVALIDATE_FAILURE_COOLDOWN_MS] 内不再拉起，避免直连过不了盾时反复空等。 */
    private suspend fun silentRevalidateForGet(path: String, failedClearanceFp: String = "-"): Boolean {
        if (sessionStore?.sessionChanging == true || isRateLimitCoolingDown()) return false
        val now = SystemClock.elapsedRealtime()
        if (now < getRevalidateBlockedUntil) {
            diagnostics.logThrottled("get-revalidate", "GET 撞盾: 静默过盾冷却中，直接交由上层处理")
            return false
        }
        val generation = sessionStore?.sessionGeneration
        val cleared = transportProvider().revalidate(path, failedClearanceFp)
        if (generation != null && generation != sessionStore?.sessionGeneration) {
            throw CancellationException("会话已切换")
        }
        if (cleared) {
            // 重验证会重新加载首页，Discourse 可能借机换掉 _forum_session
            cachedCsrfToken = null
        } else {
            getRevalidateBlockedUntil = SystemClock.elapsedRealtime() + GET_REVALIDATE_FAILURE_COOLDOWN_MS
            diagnostics.log("GET 撞盾静默过盾未通过，${GET_REVALIDATE_FAILURE_COOLDOWN_MS / 60_000L} 分钟内不再静默重试: $path")
        }
        return cleared
    }

    private suspend fun dispatchGet(
        path: String,
        burst: Boolean,
        present: Boolean,
        allow429Challenge: Boolean = true,
        retryAllowed: Boolean = true,
        beforeDispatch: (() -> Unit)? = null,
    ): String {
        ensureRequestAllowed()
        if (!present) throwIfRateLimitCoolingDown()
        return getSemaphore.withPermit {
            ensureRequestAllowed()
            if (!present) throwIfRateLimitCoolingDown()
            var attempt = 0
            var staleReplayed = false
            var revalidated = false
            while (true) {
                acquireGetSlot { !present && isRateLimitCoolingDown() }
                currentCoroutineContext().ensureActive()
                if (!present) throwIfRateLimitCoolingDown()
                // 前台请求发出后，后台计量池在静默窗口内让行，避免与用户操作贴脸叠加
                if (present) ApiQuotaPool.markForeground()
                val transport = transportProvider()
                beforeDispatch?.invoke()
                val result = try {
                    if (retryAllowed) transport.get(path, present = present)
                    else transport.getWithoutRevalidate(path, present = present)
                } finally {
                    // 时间戳已在 acquireGetSlot 记录
                }
                diagnostics.recordStatus(transport.name, result.status, path)
                when {
                    result.challenged -> {
                        if (retryAllowed && !staleReplayed && isCarriedClearanceStale(result.sentClearanceFp)) {
                            staleReplayed = true
                            diagnostics.log("检测到携带过期 cf_clearance 撞盾(sent=${result.sentClearanceFp})，跳过 WebView 直接换新值重放: $path")
                            continue
                        }
                        sessionStore?.noteClearanceChallenged(result.sentClearanceFp)
                        notify403OrChallenged("request $path challenged")
                        throw NeedsInteractiveVerification(
                            "请求 $path 被 Cloudflare 挑战拦截",
                            failedClearanceFp = result.sentClearanceFp,
                        )
                    }
                    result.status == 429 -> {
                        val cooldownEx = enterRateLimitCooldown(
                            path, result.retryAfterSeconds, cfChallenge = false,
                        )
                        val waitSec = result.retryAfterSeconds ?: ((attempt + 1) * 2L)
                        if (retryAllowed && present && attempt < MAX_RETRIES && waitSec in 1L..AUTO_RETRY_MAX_WAIT_SECONDS) {
                            val jitterMs = (200L..800L).random()
                            val totalWaitMs = (waitSec * 1_000L) + jitterMs
                            diagnostics.log("前台请求 429 自动等待 ${totalWaitMs}ms (含抖动 ${jitterMs}ms) 后重试(${attempt + 1}/$MAX_RETRIES): $path")
                            delay(totalWaitMs)
                            attempt++
                        } else {
                            throw cooldownEx
                        }
                    }
                    result.status == 403 -> {
                        notify403OrChallenged("request $path 403")
                        throw HttpStatusException(result.status, result.body.take(120))
                    }
                    result.status in 200..299 -> return result.body
                    // 连不上服务器时重试只是让用户多等十几秒,直接报错;5xx 才是值得重试的服务端抖动。
                    result.status == 0 -> throw NetworkTimeoutException()
                    retryAllowed && result.status >= 500 && attempt < MAX_RETRIES -> {
                        delay(BACKOFF_BASE_MS shl attempt)
                        attempt++
                    }
                    else -> throw HttpStatusException(result.status, result.body.take(120), result.retryAfterSeconds)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    companion object {
        /** 策略二：读请求 (GET) 3 秒滑动窗口上限 6 次（对齐 fluxdo 2req/s），相邻请求最小错峰间隔 120ms。 */
        const val GET_WINDOW_MS = 3_000L
        const val GET_WINDOW_MAX_REQUESTS = 6
        const val MIN_GET_SPACING_MS = 120L

        // ── 进程级读写双轨调度器 (策略二核心) ──
        // 1. 读轨道 (GET)：全应用进程共享，maxConcurrent = 3（对齐 fluxdo），滑动窗口限速 6次/3秒
        val getSemaphore = Semaphore(3)
        val getRateLimiterLock = Mutex()
        val getRequestTimestamps = ArrayDeque<Long>(6)

        // 2. 写轨道 (POST/PUT/DELETE)：全应用进程共享，maxConcurrent = 1 绝对串行
        val postGate = Mutex()
        @Volatile var lastPostRequestAt = 0L

        /** 阅读进度已上报楼层缓存的最大容量（话题数），超出时按 LRU 自动淘汰，避免内存膨胀。 */
        private const val MAX_REPORTED_TOPICS_CACHE = 200

        // 3. Timings 阅读进度全进程共享上报跟踪与去重缓存（按 (userId, topicId) 隔离，LRU 淘汰）
        val inFlightTimingsTopics = ConcurrentHashMap<Long, Any>()
        private val reportedPostsLock = Any()
        private val reportedPostsMap = object : LinkedHashMap<Pair<Long, Long>, MutableSet<Int>>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, MutableSet<Int>>?): Boolean {
                return size > MAX_REPORTED_TOPICS_CACHE
            }
        }

        fun isTopicPostsReported(userId: Long, topicId: Long, numbers: List<Int>): Boolean {
            synchronized(reportedPostsLock) {
                val set = reportedPostsMap[userId to topicId] ?: return false
                return numbers.all { it in set }
            }
        }

        fun recordTopicPostsReported(userId: Long, topicId: Long, numbers: List<Int>) {
            synchronized(reportedPostsLock) {
                val key = userId to topicId
                val set = reportedPostsMap.getOrPut(key) { HashSet() }
                set.addAll(numbers)
            }
        }

        fun clearTimingsCache(userId: Long? = null) {
            synchronized(reportedPostsLock) {
                if (userId == null) {
                    reportedPostsMap.clear()
                } else {
                    reportedPostsMap.keys.removeAll { it.first == userId }
                }
            }
            inFlightTimingsTopics.clear()
        }

        /** 后台静默 GET 的全进程串行闸门与上一次完成时间。 */
        val backgroundGetGate = Mutex()
        private val topicPollGate = Mutex()
        @Volatile var lastBackgroundGetAt = 0L

        /** GET 静默过盾失败后的冷却截止时间（elapsedRealtime）。 */
        @Volatile var getRevalidateBlockedUntil = 0L
        const val GET_REVALIDATE_FAILURE_COOLDOWN_MS = 2 * 60_000L
        /** P0-4: 静默过盾成功但新 cf_clearance 对 OkHttp 仍立撞 403（出口/栈不一致）时的熔断时长。 */
        const val CLEARANCE_MISMATCH_COOLDOWN_MS = 60_000L

        /** 后台静默预取撞盾后的全进程退避截止时间（elapsedRealtime）。 */
        @Volatile var silentPrefetchBackoffUntil = 0L
        const val SILENT_PREFETCH_BACKOFF_MS = 2 * 60_000L

        /** 同一应用进程内的所有仓库实例共用一段 429 冷却期。 */
        val rateLimitLock = Any()
        var rateLimitedUntil = 0L

        fun isRateLimitCoolingDown(): Boolean = rateLimitRemainingMs() > 0L

        fun rateLimitRemainingMs(): Long = synchronized(rateLimitLock) {
            (rateLimitedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

        fun enterRateLimitCooldown(
            path: String,
            retryAfterSeconds: Long? = null,
            sessionStore: SessionStore? = null,
            cfChallenge: Boolean = false,
        ): Long {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            val cooldownMs = HttpHeaderUtils.rateLimitCooldownMs(retryAfterSeconds, RATE_LIMIT_COOLDOWN_MS)
            val remaining = synchronized(rateLimitLock) {
                rateLimitedUntil = maxOf(rateLimitedUntil, nowElapsed + cooldownMs)
                sessionStore?.setRateLimitedUntil(nowWall + cooldownMs)
                (rateLimitedUntil - nowElapsed).coerceAtLeast(0L)
            }
            ApiQuotaPool.onServerRateLimited(remaining, cfChallenge)
            return remaining
        }

        fun clearRateLimitCooldown(sessionStore: SessionStore? = null) {
            synchronized(rateLimitLock) {
                rateLimitedUntil = 0L
                sessionStore?.setRateLimitedUntil(0L)
            }
        }

        /** 429 响应体是 CF 质询页（Just a moment）而非 Discourse 的 JSON 限流提示。 */
        fun isCfChallengeBody(body: String): Boolean {
            val head = body.take(4_000)
            return head.contains("challenge-platform") || head.contains("Just a moment", ignoreCase = true)
        }
        const val MAX_RETRIES = 2
        const val BACKOFF_BASE_MS = 1_000L
        /** 登录前 CF 闸门复用启动后本就要刷新的通知读取请求。 */
        const val LOGIN_CF_GATE_PATH = "/notifications.json?recent=true&limit=50&silent=true"
        /** 前台请求遇到 429 时允许静默自动等待重放的最大秒数（对齐 fluxdo）。 */
        const val AUTO_RETRY_MAX_WAIT_SECONDS = 15L
        /** 429 后后台任务默认静默退让时长（无 Retry-After 提示时的兜底值）。 */
        const val RATE_LIMIT_COOLDOWN_MS = 10_000L
        const val KEY_BLOCKED_USERS = "local_blocked_users_json"

        /** 通知分区一次加载的目标条数。 */
        const val PAGE_TARGET = 20

        /** 单次扫描服务端通知流的条数。取大一些,低频类型才不至于每页只命中一两条。 */
        const val NOTIFICATION_SCAN_LIMIT = 60

        /** 单次加载最多往前扫几轮,避免某一类通知很久没有时无限翻页。 */
        const val MAX_SCAN_ROUNDS = 5

        /** 阅读进度上报的时长边界(毫秒)。服务端会按批次封顶,客户端先规整一遍免得被整条丢弃。 */
        const val MAX_POST_TIME_MS = 60_000L
        const val MAX_TOPIC_TIME_MS = 30 * 60 * 1_000L

        /** 申请读轨道 (GET) 令牌，强制执行滑动窗口限频与防尖峰错峰 */
        suspend fun acquireGetSlot(isCoolingDown: () -> Boolean) {
            while (true) {
                if (isCoolingDown()) {
                    val remaining = synchronized(rateLimitLock) {
                        (rateLimitedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    }
                    throw RateLimitException(remaining)
                }
                var waitMs = 0L
                getRateLimiterLock.withLock {
                    val now = SystemClock.elapsedRealtime()
                    while (getRequestTimestamps.isNotEmpty() && now - getRequestTimestamps.first() >= GET_WINDOW_MS) {
                        getRequestTimestamps.removeFirst()
                    }
                    if (getRequestTimestamps.size >= GET_WINDOW_MAX_REQUESTS) {
                        val oldest = getRequestTimestamps.first()
                        waitMs = GET_WINDOW_MS - (now - oldest) + 10L
                    } else {
                        val lastReq = getRequestTimestamps.lastOrNull() ?: 0L
                        val elapsedSinceLast = now - lastReq
                        if (elapsedSinceLast < MIN_GET_SPACING_MS) {
                            waitMs = MIN_GET_SPACING_MS - elapsedSinceLast
                        } else {
                            getRequestTimestamps.addLast(now)
                            return
                        }
                    }
                }
                if (waitMs > 0L) {
                    delay(waitMs)
                }
            }
        }

        /** 写轨道 (POST) 串行安全节流 */
        suspend fun throttlePost(burst: Boolean = false) {
            val interval = if (burst) Config.BURST_REQUEST_INTERVAL_MS else Config.MIN_REQUEST_INTERVAL_MS
            val wait = interval - (SystemClock.elapsedRealtime() - lastPostRequestAt)
            if (wait > 0) delay(wait)
        }

        fun markPostCompleted() {
            lastPostRequestAt = SystemClock.elapsedRealtime()
        }
    }
}

package org.linuxdo.android.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linuxdo.android.App
import org.linuxdo.android.Config
import org.linuxdo.android.data.TopicRepository
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.Post
import org.linuxdo.android.data.TopicDetail
import org.linuxdo.android.data.SearchOrder
import org.linuxdo.android.data.ProfileSection
import org.linuxdo.android.data.ProfilePage
import org.linuxdo.android.net.HttpStatusException
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.ui.screen.TopicListUiState

enum class Screen { Boot, Login, Verify, List }

data class LoginUiState(
    val loading: Boolean = false,
    val sendingCode: Boolean = false,
    val error: String? = null,
    val emailSent: Boolean = false,
)
enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }
enum class DetailLoad { Topic, Next, Previous }

data class DetailUiState(
    val topic: TopicDetail? = null,
    val posts: List<Post> = emptyList(),
    val consumedIds: Set<Long> = emptySet(),
    val category: Category? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val replyCursors: Map<Long, Int> = emptyMap(),
    val completedReplies: Set<Long> = emptySet(),
    val reactingPosts: Set<Long> = emptySet(),
    val reactionErrors: Map<Long, String> = emptyMap(),
    val emojiUrls: Map<String, String> = emptyMap(),
    val windowStartId: Long? = null,
    val failedLoad: DetailLoad = DetailLoad.Topic,
) {
    private val windowStart: Int get() = topic?.postStream?.stream.orEmpty().indexOf(windowStartId).coerceAtLeast(0)
    val remaining: List<Long> get() = topic?.postStream?.stream.orEmpty().drop(windowStart).filterNot { it in consumedIds }
    val previousIds: List<Long> get() = topic?.postStream?.stream.orEmpty().take(windowStart).filterNot { it in consumedIds }
}

data class CategoriesUiState(
    val items: List<Category> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val results: TopicListUiState = TopicListUiState(initialLoading = false, hasMore = false),
    val excerpts: Map<Long, String> = emptyMap(),
    val order: SearchOrder = SearchOrder.Relevance,
)

data class ProfileUiState(
    val content: ProfilePage = ProfilePage(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val page: Int = 0,
    val error: String? = null,
)

data class UiState(
    val screen: Screen = Screen.Boot,
    val username: String? = null,
    val latest: TopicListUiState = TopicListUiState(),
    val path: NetworkPath = NetworkPath.OKHTTP,
    val autoRefresh: Boolean = false,
    val cookieNames: List<String> = emptyList(),
    val cookieSavedAt: Long = 0L,
    val userAgent: String = "",
    val unreadCount: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as App).container
    private val store = container.sessionStore
    private val browser = container.browser
    val diagnostics = container.diagnostics

    private val _state = MutableStateFlow(UiState(userAgent = container.userAgent))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val repository = TopicRepository({ container.transport(_state.value.path) }, diagnostics)

    private var bootJob: Job? = null
    private var autoJob: Job? = null
    private var verifying = false
    private var resumeAfterVerification: (() -> Unit)? = null
    private var latestLoading = false
    private var searchJob: Job? = null
    private var unreadJob: Job? = null
    private var unreadFailures = 0
    private var nextUnreadRefreshAt = 0L
    private val pageJobs = mutableMapOf<String, Job>()
    private val _details = MutableStateFlow<Map<Long, DetailUiState>>(emptyMap())
    val details = _details.asStateFlow()
    private val _categories = MutableStateFlow(CategoriesUiState())
    val categories = _categories.asStateFlow()
    private val _categoryTopics = MutableStateFlow<Map<Int, TopicListUiState>>(emptyMap())
    val categoryTopics = _categoryTopics.asStateFlow()
    private val _search = MutableStateFlow(SearchUiState())
    val search = _search.asStateFlow()
    private val _profile = MutableStateFlow<Map<ProfileSection, ProfileUiState>>(emptyMap())
    val profile = _profile.asStateFlow()
    private val _treeView = MutableStateFlow(container.preferences.getBoolean("tree_view", true))
    val treeView = _treeView.asStateFlow()
    private val _themeMode = MutableStateFlow(ThemeMode.entries.firstOrNull {
        it.name == container.preferences.getString("theme_mode", ThemeMode.System.name)
    } ?: ThemeMode.System)
    val themeMode = _themeMode.asStateFlow()

    private val _loginUiState = MutableStateFlow(LoginUiState())
    val loginUiState: StateFlow<LoginUiState> = _loginUiState.asStateFlow()

    fun dismissLoginError() {
        _loginUiState.update { it.copy(error = null, emailSent = false) }
    }

    /** 账号密码登录：先拿 CSRF token，再 POST /session。 */
    fun loginWithPassword(login: String, password: String) {
        if (_loginUiState.value.loading) return
        viewModelScope.launch {
            _loginUiState.update { it.copy(loading = true, error = null) }
            try {
                val result = repository.sessionLogin(login, password)
                if (result) {
                    diagnostics.log("原生密码登录成功，回调 boot()")
                    _loginUiState.update { it.copy(loading = false) }
                    boot()
                } else {
                    _loginUiState.update { it.copy(loading = false, error = "用户名或密码错误，请检查后重试。") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: org.linuxdo.android.net.NeedsInteractiveVerification) {
                _loginUiState.update { it.copy(loading = false) }
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: Exception) {
                _loginUiState.update { it.copy(loading = false, error = error.message ?: "登录失败，请稍后重试。") }
            }
        }
    }

    /** 发送邮箱一次性登录链接。 */
    fun sendLoginEmail(email: String) {
        if (_loginUiState.value.sendingCode) return
        viewModelScope.launch {
            _loginUiState.update { it.copy(sendingCode = true, error = null, emailSent = false) }
            try {
                repository.sendEmailLogin(email)
                _loginUiState.update { it.copy(sendingCode = false, emailSent = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: org.linuxdo.android.net.NeedsInteractiveVerification) {
                _loginUiState.update { it.copy(sendingCode = false) }
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: Exception) {
                _loginUiState.update { it.copy(sendingCode = false, error = error.message ?: "发送失败，请稍后重试。") }
            }
        }
    }

    /**
     * 用用户从邮件链接中复制的 token 完成登录。
     * Discourse 的邮件登录链接格式为 /session/email-login/{token}，
     * 在 WebView 中打开该 URL 即可完成 Cookie 注入，随后 pollVerification() 会检测到 _t cookie。
     */
    fun loginWithEmailCode(email: String, token: String) {
        if (_loginUiState.value.loading) return
        viewModelScope.launch {
            _loginUiState.update { it.copy(loading = true, error = null) }
            try {
                // 在 WebView 中打开 email-login 链接完成认证
                browser.openUrl("${org.linuxdo.android.Config.BASE_URL}/session/email-login/$token")
                // pollVerification 会在检测到 _t cookie 后调用 boot()
                _loginUiState.update { it.copy(loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _loginUiState.update { it.copy(loading = false, error = error.message ?: "验证失败，请检查 token 是否正确。") }
            }
        }
    }


    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        container.preferences.edit { putString("theme_mode", mode.name) }
    }

    fun setTreeView(enabled: Boolean) {
        _treeView.value = enabled
        container.preferences.edit { putBoolean("tree_view", enabled) }
    }

    fun loadProfile(section: ProfileSection, refresh: Boolean = false, more: Boolean = false) {
        val username = _state.value.username ?: return
        val previous = _profile.value[section] ?: ProfileUiState()
        if (previous.loading || (previous.loaded && !refresh && !more)) return
        if (more && !previous.content.hasMore) return
        val page = if (more) previous.page + 1 else 0
        pageJobs["profile:$section"] = viewModelScope.launch {
            _profile.update { it + (section to previous.copy(loading = true, error = null)) }
            try {
                val result = repository.fetchProfile(username, section, page)
                val entries = if (more) (previous.content.entries + result.entries).distinctBy { it.id } else result.entries
                _profile.update { it + (section to ProfileUiState(
                    content = result.copy(entries = entries, hasMore = result.hasMore && (!more || entries.size > previous.content.entries.size)),
                    loaded = true, page = page,
                )) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _profile.update { it + (section to previous.copy(error = error.message ?: "加载失败")) }
                handlePageFailure(error) { loadProfile(section, refresh = true) }
            } finally {
                _profile.update { states -> states[section]?.let { states + (section to it.copy(loading = false)) } ?: states }
            }
        }
    }

    fun retryTopic(id: Long, postNumber: Int? = null) {
        val failed = _details.value[id]?.failedLoad
        loadTopic(id, more = failed == DetailLoad.Next, previous = failed == DetailLoad.Previous,
            retry = true, postNumber = postNumber)
    }

    fun loadTopic(id: Long, more: Boolean = false, retry: Boolean = false, postNumber: Int? = null, previous: Boolean = false) {
        val current = _details.value[id] ?: DetailUiState()
        val targetLoaded = postNumber == null || current.posts.any { it.postNumber == postNumber }
        if (current.loading || current.reactingPosts.isNotEmpty() || (!more && !previous && current.topic != null && !retry && targetLoaded)) return
        if (more && current.remaining.isEmpty()) return
        if (previous && current.previousIds.isEmpty()) return
        pageJobs["topic:$id"] = viewModelScope.launch {
            _details.update { it + (id to current.copy(loading = true, error = null)) }
            try {
                if (more || previous) {
                    val requested = if (previous) current.previousIds.takeLast(20) else current.remaining.take(20)
                    val posts = repository.fetchPosts(id, requested)
                    _details.update {
                        it + (id to current.copy(
                            posts = (current.posts + posts).distinctBy { post -> post.id }.sortedBy { post -> post.postNumber },
                            consumedIds = current.consumedIds + requested,
                            windowStartId = if (previous) requested.first() else current.windowStartId,
                            error = null, failedLoad = DetailLoad.Topic,
                        ))
                    }
                } else {
                    val topic = repository.fetchTopic(id, postNumber)
                    val category = repository.categoryById(topic.categoryId)
                    val emojis = repository.emojiUrls()
                    val posts = if (postNumber != null) (topic.postStream.posts + current.posts).distinctBy { it.id }.sortedBy { it.postNumber }
                        else topic.postStream.posts.distinctBy { it.id }
                    val missing = postNumber != null && posts.none { it.postNumber == postNumber }
                    _details.update {
                        it + (id to DetailUiState(topic, posts, posts.map { post -> post.id }.toSet(), category,
                            error = if (missing) "通知对应楼层 #$postNumber 已删除或不可访问" else null,
                            emojiUrls = emojis, windowStartId = topic.postStream.posts.minByOrNull { it.postNumber }?.id))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { it + (id to current.copy(error = error.message ?: "加载失败",
                    failedLoad = when { previous -> DetailLoad.Previous; more -> DetailLoad.Next; else -> DetailLoad.Topic })) }
                handlePageFailure(error) { loadTopic(id, more, retry = true, postNumber = postNumber, previous = previous) }
            } finally {
                _details.update { states -> states[id]?.let { states + (id to it.copy(loading = false)) } ?: states }
            }
        }
    }

    fun react(topicId: Long, postId: Long, reaction: String) {
        changePost(topicId, postId) { post ->
            repository.toggleReaction(postId, reaction).copy(boosts = post.boosts, canBoost = post.canBoost)
        }
    }

    fun refreshUnread(force: Boolean = false) {
        if (unreadJob?.isActive == true || _state.value.screen != Screen.List) return
        if (!force && SystemClock.elapsedRealtime() < nextUnreadRefreshAt) return
        unreadJob = viewModelScope.launch {
            try {
                val count = repository.unreadCount()
                val changed = count != _state.value.unreadCount
                unreadFailures = 0
                nextUnreadRefreshAt = 0L
                _state.update { it.copy(unreadCount = count) }
                if (changed && _profile.value[ProfileSection.Notifications]?.loaded == true) {
                    loadProfile(ProfileSection.Notifications, refresh = true)
                }
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                unreadFailures = (unreadFailures + 1).coerceAtMost(4)
                nextUnreadRefreshAt = SystemClock.elapsedRealtime() + (Config.UNREAD_POLL_INTERVAL_MS shl unreadFailures)
                diagnostics.log("未读数量刷新失败: ${error.javaClass.simpleName}")
            }
        }
    }

    fun readNotification(id: Long) {
        if (pageJobs["notification:$id"]?.isActive == true) return
        pageJobs["notification:$id"] = viewModelScope.launch {
            try {
                repository.markNotificationRead(id)
                _profile.update { panels ->
                    val notices = panels[ProfileSection.Notifications] ?: return@update panels
                    panels + (ProfileSection.Notifications to notices.copy(content = notices.content.copy(
                        entries = notices.content.entries.map { if (it.id == id.toString()) it.copy(unread = false) else it },
                    )))
                }
                // 等正在进行的分页统计结束,避免已读后的强制刷新被 isActive 守卫丢掉。
                unreadJob?.join()
                refreshUnread(force = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { diagnostics.log("通知已读状态未同步: ${error.javaClass.simpleName}") }
        }
    }

    fun refreshPost(topicId: Long, postId: Long) {
        changePost(topicId, postId) { repository.fetchPosts(topicId, listOf(postId)).single() }
    }

    fun setSolution(topicId: Long, postId: Long) {
        val post = _details.value[topicId]?.posts?.firstOrNull { it.id == postId } ?: return
        if (post.acceptedAnswer && !post.canUnacceptAnswer || !post.acceptedAnswer && !post.canAcceptAnswer) return
        changePost(topicId, postId) {
            val accepted = !it.acceptedAnswer
            repository.setSolution(postId, accepted)
            it.copy(acceptedAnswer = accepted, canAcceptAnswer = !accepted, canUnacceptAnswer = accepted)
        }
    }

    fun createBoost(topicId: Long, postId: Long, raw: String) {
        changePost(topicId, postId) { post ->
            val boost = repository.createBoost(postId, raw)
            post.copy(boosts = post.boosts + boost, canBoost = false)
        }
    }

    fun deleteBoost(topicId: Long, postId: Long, boostId: Long) {
        changePost(topicId, postId) { post ->
            repository.deleteBoost(boostId)
            post.copy(boosts = post.boosts.filterNot { it.id == boostId }, canBoost = !post.yours)
        }
    }

    private fun changePost(topicId: Long, postId: Long, action: suspend (Post) -> Post) {
        val current = _details.value[topicId] ?: return
        if (current.loading || current.reactingPosts.isNotEmpty()) return
        val original = current.posts.firstOrNull { it.id == postId } ?: return
        pageJobs["reaction:$postId"] = viewModelScope.launch {
            _details.update { it + (topicId to current.copy(reactingPosts = setOf(postId), reactionErrors = current.reactionErrors - postId)) }
            try {
                val updated = action(original)
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    states + (topicId to latest.copy(posts = latest.posts.map {
                        when {
                            it.id == updated.id -> updated
                            updated.acceptedAnswer && it.acceptedAnswer -> it.copy(acceptedAnswer = false,
                                canAcceptAnswer = it.canUnacceptAnswer, canUnacceptAnswer = false)
                            else -> it
                        }
                    }))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(reactionErrors = it.reactionErrors + (postId to (error.message ?: "回应失败"))))
                } ?: states }
                // toggle 不是幂等操作,验证后只刷新状态,不自动重放写请求。
                handlePageFailure(error) { loadTopic(topicId, retry = true) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(reactingPosts = emptySet())) } ?: states }
            }
        }
    }

    fun loadReplies(topicId: Long, postId: Long) {
        val current = _details.value[topicId] ?: return
        if (current.loading || current.reactingPosts.isNotEmpty() || postId in current.completedReplies) return
        pageJobs["topic:$topicId"] = viewModelScope.launch {
            _details.update { it + (topicId to current.copy(loading = true, error = null)) }
            try {
                val replies = repository.fetchReplies(postId, current.replyCursors[postId] ?: 1)
                val cursor = replies.maxOfOrNull { it.postNumber }
                _details.update { it + (topicId to current.copy(
                    posts = (current.posts + replies).distinctBy { post -> post.id }.sortedBy { post -> post.postNumber },
                    consumedIds = current.consumedIds + replies.map { post -> post.id },
                    replyCursors = if (cursor == null) current.replyCursors else current.replyCursors + (postId to cursor),
                    completedReplies = if (replies.isEmpty()) current.completedReplies + postId else current.completedReplies,
                )) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { it + (topicId to current.copy(error = error.message ?: "回复加载失败")) }
                handlePageFailure(error) { loadReplies(topicId, postId) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(loading = false)) } ?: states }
            }
        }
    }

    fun loadCategories(retry: Boolean = false) {
        val current = _categories.value
        if (current.loading || (current.loaded && !retry)) return
        pageJobs["categories"] = viewModelScope.launch {
            _categories.value = current.copy(loading = true, error = null)
            try {
                _categories.value = CategoriesUiState(items = repository.fetchCategories(), loaded = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _categories.value = current.copy(error = error.message ?: "加载失败")
                handlePageFailure(error) { loadCategories(retry = true) }
            } finally {
                _categories.update { it.copy(loading = false) }
            }
        }
    }

    fun loadCategory(id: Int, slug: String, refresh: Boolean = false, more: Boolean = false) {
        val current = _categoryTopics.value[id]
        if (pageJobs["category:$id"]?.isActive == true) return
        if (!refresh && !more && current != null) return
        val previous = current ?: TopicListUiState()
        if (more && !previous.hasMore) return
        val page = if (more) previous.page + 1 else 0
        pageJobs["category:$id"] = viewModelScope.launch {
            _categoryTopics.update { it + (id to previous.copy(
                refreshing = refresh, loadingMore = more, initialLoading = previous.items.isEmpty(), error = null,
            )) }
            try {
                val result = repository.fetchCategoryTopics(slug, id, page)
                val items = if (more) (previous.items + result.items).distinctBy { it.topic.id } else result.items
                _categoryTopics.update { it + (id to TopicListUiState(items, page,
                    result.hasMore && (!more || items.size > previous.items.size), initialLoading = false)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _categoryTopics.update { it + (id to previous.copy(initialLoading = false, error = error.message ?: "加载失败")) }
                handlePageFailure(error) { loadCategory(id, slug, refresh = !more, more = more) }
            } finally {
                _categoryTopics.update { states -> states[id]?.let {
                    states + (id to it.copy(initialLoading = false, refreshing = false, loadingMore = false))
                } ?: states }
            }
        }
    }

    fun updateSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        _search.value = SearchUiState(query, TopicListUiState(initialLoading = trimmed.isNotEmpty(), hasMore = false), order = _search.value.order)
        if (trimmed.isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(500)
            performSearch(trimmed, 1)
        }
    }

    fun setSearchOrder(order: SearchOrder) {
        if (_search.value.order == order) return
        _search.update { it.copy(order = order) }
        updateSearch(_search.value.query)
    }

    fun loadMoreSearch() {
        val current = _search.value
        if (searchJob?.isActive == true || !current.results.hasMore) return
        searchJob = viewModelScope.launch { performSearch(current.query.trim(), current.results.page + 1) }
    }

    private suspend fun performSearch(query: String, page: Int) {
        val previous = _search.value
        _search.update { it.copy(results = it.results.copy(initialLoading = page == 1, loadingMore = page > 1, error = null)) }
        try {
            val result = repository.search(query, page, previous.order)
            val items = if (page == 1) result.items else (previous.results.items + result.items).distinctBy { it.topic.id }
            _search.update { it.copy(
                results = TopicListUiState(items, page, result.hasMore && (page == 1 || items.size > previous.results.items.size), initialLoading = false),
                excerpts = if (page == 1) result.excerpts else previous.excerpts + result.excerpts,
            ) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _search.update { it.copy(results = it.results.copy(initialLoading = false, loadingMore = false, error = error.message ?: "搜索失败")) }
            handlePageFailure(error) { updateSearch(_search.value.query) }
        }
    }

    private suspend fun handlePageFailure(error: Exception, retry: () -> Unit) {
        diagnostics.log("浏览页面加载失败: ${error.message}")
        if (error is NeedsInteractiveVerification) {
            resumeAfterVerification = retry
            enterWebScreen(Screen.Verify, error.message.orEmpty())
        }
    }

    init {
        boot()
    }

    fun boot() {
        bootJob?.cancel()
        bootJob = viewModelScope.launch {
            verifying = false
            _state.update { it.copy(screen = Screen.Boot, latest = it.latest.copy(error = null)) }
            store.restoreToWebViewIfEmpty()
            refreshCookieInfo()
            if (!store.hasSessionCookie()) {
                enterWebScreen(Screen.Login)
                return@launch
            }
            try {
                val username = repository.fetchCurrentUser()
                if (username == null) {
                    diagnostics.log("会话 Cookie 存在但服务端判定未登录,转入登录")
                    enterWebScreen(Screen.Login)
                } else {
                    diagnostics.log("登录态有效: $username")
                    _state.update { it.copy(username = username, screen = Screen.List) }
                    resumeAfterVerification?.also { resumeAfterVerification = null }?.invoke()
                    loadLatest(page = 0, pullRefresh = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: NeedsInteractiveVerification) {
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: Exception) {
                diagnostics.log("启动检查失败: ${error.message}")
                _state.update {
                    it.copy(
                        screen = Screen.List,
                        latest = it.latest.copy(initialLoading = false, error = error.message),
                    )
                }
            }
        }
    }

    fun refreshLatest() {
        viewModelScope.launch { loadLatest(page = 0, pullRefresh = true) }
    }

    fun retryLatest() {
        viewModelScope.launch { loadLatest(page = 0, pullRefresh = false) }
    }

    fun loadMoreLatest() {
        viewModelScope.launch { loadLatest(page = _state.value.latest.page + 1, pullRefresh = false) }
    }

    fun setPath(path: NetworkPath) {
        if (path == _state.value.path) return
        _state.update { it.copy(path = path) }
        diagnostics.log("切换网络路径 -> ${path.label}")
        retryLatest()
    }

    fun toggleAutoRefresh() {
        val enable = !_state.value.autoRefresh
        _state.update { it.copy(autoRefresh = enable) }
        autoJob?.cancel()
        if (!enable) {
            diagnostics.log("自动刷新已关闭")
            return
        }
        diagnostics.log("自动刷新已开启(每 ${Config.AUTO_REFRESH_INTERVAL_MS / 60_000} 分钟)")
        autoJob = viewModelScope.launch {
            while (isActive) {
                delay(Config.AUTO_REFRESH_INTERVAL_MS)
                if (_state.value.screen == Screen.List) loadLatest(page = 0, pullRefresh = false)
            }
        }
    }

    /** 由界面在登录/验证页每秒轮询一次;完成条件满足后回到启动流程重新校验会话。 */
    suspend fun pollVerification() {
        if (verifying) return
        if (!browser.isPageCleared()) return
        val cookies = store.syncFromWebView()
        val done = when (_state.value.screen) {
            Screen.Verify -> true
            Screen.Login -> cookies.containsKey(Config.SESSION_COOKIE)
            else -> false
        }
        if (done) {
            verifying = true
            diagnostics.log("网页验证完成,重新校验会话")
            boot()
        }
    }

    fun logout() {
        autoJob?.cancel()
        searchJob?.cancel()
        unreadJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        resumeAfterVerification = null
        _details.value = emptyMap()
        _categories.value = CategoriesUiState()
        _categoryTopics.value = emptyMap()
        _search.value = SearchUiState()
        _profile.value = emptyMap()
        viewModelScope.launch {
            store.clear()
            browser.reset()
            diagnostics.log("已登出并清除 Cookie")
            _state.update { UiState(path = it.path, userAgent = it.userAgent) }
            enterWebScreen(Screen.Login)
        }
    }

    private suspend fun enterWebScreen(screen: Screen, reason: String = "") {
        if (screen == Screen.Verify) diagnostics.recordInteractiveRequired(reason.ifBlank { "未知" })
        _state.update { it.copy(screen = screen) }
        // Login 现在是原生 Compose 页面，不需要在 WebView 里打开登录 URL。
        // Verify 时打开根 URL 触发 Cloudflare 人机验证。
        if (screen == Screen.Verify) {
            browser.openUrl("${Config.BASE_URL}/")
        }
    }


    private suspend fun loadLatest(page: Int, pullRefresh: Boolean) {
        val current = _state.value.latest
        if (latestLoading || current.refreshing || current.loadingMore) return
        latestLoading = true

        _state.update {
            it.copy(
                latest = current.copy(
                    refreshing = pullRefresh && page == 0,
                    loadingMore = page > 0,
                    initialLoading = page == 0 && current.items.isEmpty() && !pullRefresh,
                    error = null,
                ),
            )
        }
        try {
            val result = repository.fetchLatest(page)
            _state.update { state ->
                val merged = if (page == 0) {
                    result.items
                } else {
                    (state.latest.items + result.items).distinctBy { it.topic.id }
                }
                state.copy(
                    latest = state.latest.copy(
                        items = merged,
                        page = page,
                        hasMore = result.hasMore && (page == 0 || merged.size > state.latest.items.size),
                        refreshing = false,
                        loadingMore = false,
                        initialLoading = false,
                        error = null,
                    ),
                )
            }
            refreshCookieInfo()
        } catch (error: CancellationException) {
            throw error
        } catch (error: NeedsInteractiveVerification) {
            settleLoading()
            enterWebScreen(Screen.Verify, error.message.orEmpty())
        } catch (error: HttpStatusException) {
            diagnostics.log("加载失败: ${error.message}")
            settleLoading(error.message)
        } catch (error: Exception) {
            diagnostics.log("加载异常: ${error.message}")
            settleLoading(error.message ?: error.javaClass.simpleName)
        } finally {
            latestLoading = false
        }
    }

    private fun settleLoading(message: String? = null) {
        _state.update {
            it.copy(
                latest = it.latest.copy(
                    refreshing = false,
                    loadingMore = false,
                    initialLoading = false,
                    error = message,
                ),
            )
        }
    }

    private fun refreshCookieInfo() {
        val cookies = store.syncFromWebView()
        _state.update {
            it.copy(cookieNames = cookies.keys.sorted(), cookieSavedAt = store.savedAtMillis())
        }
    }
}

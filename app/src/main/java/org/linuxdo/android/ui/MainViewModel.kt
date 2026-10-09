package org.linuxdo.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import org.linuxdo.android.App
import org.linuxdo.android.BuildConfig
import org.linuxdo.android.Config
import org.linuxdo.android.data.TopicRepository
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.Post
import org.linuxdo.android.data.TopicDetail
import org.linuxdo.android.data.SearchOrder
import org.linuxdo.android.data.ProfileSection
import org.linuxdo.android.data.ProfilePage
import org.linuxdo.android.data.NotificationFilter
import org.linuxdo.android.data.UploadLimits
import org.linuxdo.android.data.effectiveReplyToPostNumber
import org.linuxdo.android.data.threadRows
import org.linuxdo.android.net.HttpStatusException
import org.linuxdo.android.net.NeedsInteractiveVerification
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.net.RiskControlException
import org.linuxdo.android.net.SecondFactorRequiredException
import android.content.Context
import android.content.Intent
import org.linuxdo.android.ui.screen.TopicListUiState

enum class Screen { Boot, Login, Verify, List }

data class UpdateInfo(
    val latestVersion: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val mandatory: Boolean,
)

data class UpdateUiState(
    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore("-"),
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val checking: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val showDialog: Boolean = false,
    val statusMessage: String? = null,
)

data class LoginUiState(
    val loading: Boolean = false,
    val sendingCode: Boolean = false,
    val error: String? = null,
    val emailSent: Boolean = false,
    val secondFactorRequired: Boolean = false,
    val backupEnabled: Boolean = false,
    val showRiskControlDialog: Boolean = false,
    val webAuthLoading: Boolean = false,
    val webAuthMessage: String? = null,
)
enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }
enum class DetailLoad { Topic, Next, Previous }

data class DetailUiState(
    val topic: TopicDetail? = null,
    val posts: List<Post> = emptyList(),
    val consumedIds: Set<Long> = emptySet(),
    val category: Category? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val replyCursors: Map<Long, Int> = emptyMap(),
    val completedReplies: Set<Long> = emptySet(),
    val loadingReplies: Set<Long> = emptySet(),
    val reactingPosts: Set<Long> = emptySet(),
    val reactionErrors: Map<Long, String> = emptyMap(),
    val emojiUrls: Map<String, String> = emptyMap(),
    val windowStartId: Long? = null,
    val failedLoad: DetailLoad = DetailLoad.Topic,
    /**
     * 刚发出去的楼层 id。界面据此滚过去并高亮,用完调 clearCreatedPost 清掉。
     * 放在这里而不是让界面自己猜"楼层数变多了":它和新楼层是同一次状态更新送达的,
     * 界面不会读到"编辑器已关、但列表还没更新"的中间态。
     */
    val createdPostId: Long? = null,
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

/** 编辑器要做的事。三种目标共用一个输入框,区别只在提交时调哪个接口。 */
sealed interface ComposerTarget {
    val topicId: Long

    /**
     * 回复整个话题。不带 reply_to_post_number,所以不挂在任何楼层下面,
     * 发出去的楼层直接排在话题末尾 —— 这是网页端"回复"按钮的行为。
     */
    data class NewReply(override val topicId: Long) : ComposerTarget

    /** 回复某一楼层,结果会挂到该楼层下面。 */
    data class ReplyTo(
        override val topicId: Long,
        val postNumber: Int,
        val username: String,
    ) : ComposerTarget

    /** 编辑自己的楼层。 */
    data class Edit(
        override val topicId: Long,
        val postId: Long,
        val postNumber: Int,
    ) : ComposerTarget
}

data class ComposerUiState(
    val target: ComposerTarget? = null,
    val draft: String = "",
    /** 编辑要先取回原文,这期间输入框不可编辑。 */
    val loadingDraft: Boolean = false,
    val submitting: Boolean = false,
    /** 正在上传附件。期间不许提交 —— 不然短码还没插进草稿就发出去了。 */
    val uploading: Boolean = false,
    val error: String? = null,
) {
    val open: Boolean get() = target != null
    val canSubmit: Boolean get() = draft.isNotBlank() && !submitting && !loadingDraft && !uploading
}

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
    val error: String? = null,
    /** 这份数据拉完的时刻(elapsedRealtime),用来判断缓存有没有过期。 */
    val loadedAt: Long = 0L,
    /** 拉这份数据时的未读数。未读没涨说明列表大概率没变,可以接着用缓存。 */
    val unreadAtLoad: Int = 0,
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
    val unreadFilters: Set<NotificationFilter> = emptySet(),
    /** 各通知分类的未读条数。用于判断某个分类的缓存是否还新鲜。 */
    val unreadByFilter: Map<NotificationFilter, Int> = emptyMap(),
)


class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as App).container
    private val store = container.sessionStore
    private val browser = container.browser
    val diagnostics = container.diagnostics
    private val userApiKeyAuthService = container.userApiKeyAuthService

    private val _state = MutableStateFlow(UiState(userAgent = container.userAgent))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val repository = TopicRepository({ container.transport(_state.value.path) }, diagnostics)

    private var bootJob: Job? = null
    private var autoJob: Job? = null
    private var verifying = false
    private var resumeAfterVerification: (() -> Unit)? = null
    /**
     * 邮件登录被 CF 拦下、待重放的 token。
     * 与 resumeAfterVerification 分开是因为两者通过验证后的处理不同:
     * 重放登录要自己收尾(boot 或回登录页),而不是交给 boot() 去恢复一个页面加载。
     */
    private var pendingEmailLoginToken: String? = null
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
    private val _notificationFilter = MutableStateFlow(NotificationFilter.All)
    val notificationFilter = _notificationFilter.asStateFlow()
    /**
     * 通知各分类的数据。`_profile` 的键是 ProfileSection,四个分类挤同一个槽位,
     * 所以额外按分类留一份,切标签才能命中缓存直接显示。
     */
    private val notificationCache = mutableMapOf<NotificationFilter, ProfileUiState>()
    private val _composer = MutableStateFlow(ComposerUiState())
    val composer = _composer.asStateFlow()
    private val _treeView = MutableStateFlow(container.preferences.getBoolean("tree_view", true))
    val treeView = _treeView.asStateFlow()
    private val _themeMode = MutableStateFlow(ThemeMode.entries.firstOrNull {
        it.name == container.preferences.getString("theme_mode", ThemeMode.System.name)
    } ?: ThemeMode.System)
    val themeMode = _themeMode.asStateFlow()

    private val _loginUiState = MutableStateFlow(LoginUiState())
    val loginUiState: StateFlow<LoginUiState> = _loginUiState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    fun dismissUpdateDialog(ignoreThisVersion: Boolean = false) {
        val current = _updateState.value
        val info = current.updateInfo ?: return
        if (info.mandatory) return
        if (ignoreThisVersion) {
            container.preferences.edit { putString("ignored_update_version", info.latestVersion) }
        }
        _updateState.update { it.copy(showDialog = false) }
    }

    /**
     * 检查最新版本，优先读取公开仓库根目录的 `version.json` 静态版本清单，
     * 若 `version.json` 不可达则回退至 GitHub Releases 接口。
     *
     * 必升级触发规则（满足任一即为必升级）：
     * 1. `version.json` 中 `forceUpdate: true`
     * 2. `version.json` 中 `minVersionCode` 大于当前 `versionCode`（或 `minVersionName` 高于当前版本）
     * 3. 回退 Release 正文包含 `[force]`、`[mandatory]`、`[必升]`、`[min_version: X.Y.Z]` 或 `[min_code: N]`
     */
    fun checkForUpdates(manual: Boolean = false) {
        if (_updateState.value.checking) return
        viewModelScope.launch {
            _updateState.update {
                it.copy(
                    checking = true,
                    statusMessage = if (manual) "正在检查新版本..." else it.statusMessage,
                )
            }
            try {
                val currentVersion = _updateState.value.currentVersion
                val currentCode = _updateState.value.currentVersionCode
                val info = withContext(Dispatchers.IO) {
                    for (manifestUrl in Config.UPDATE_VERSION_JSON_URLS) {
                        val manifestJson = runCatching {
                            val req = Request.Builder()
                                .url(manifestUrl)
                                .header("Accept", "application/vnd.github.raw+json, application/json;q=0.9")
                                .header("Cache-Control", "no-cache")
                                .header("User-Agent", container.userAgent)
                                .get()
                                .build()
                            container.httpClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) {
                                    throw HttpStatusException(resp.code, "Manifest HTTP ${resp.code}")
                                }
                                resp.body?.string().orEmpty()
                            }
                        }.getOrNull()
                        if (!manifestJson.isNullOrBlank()) {
                            val (parsed, manifestInfo) = parseVersionManifest(manifestJson, currentVersion, currentCode)
                            if (parsed) {
                                return@withContext manifestInfo
                            }
                        }
                    }

                    val (_, localManifestInfo) = parseVersionManifest(
                        Config.FALLBACK_VERSION_MANIFEST_JSON,
                        currentVersion,
                        currentCode,
                    )
                    if (localManifestInfo != null) {
                        return@withContext localManifestInfo
                    }

                    val apiJson = runCatching {
                        val req = Request.Builder()
                            .url(Config.UPDATE_RELEASES_API_URL)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", container.userAgent)
                            .get()
                            .build()
                        container.httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw HttpStatusException(resp.code, "GitHub API HTTP ${resp.code}")
                            }
                            resp.body?.string().orEmpty()
                        }
                    }.getOrNull()

                    if (!apiJson.isNullOrBlank()) {
                        parseReleaseUpdateInfo(apiJson, currentVersion, currentCode)
                    } else {
                        // 兜底：若 api.github.com 触发匿名限流或连接受阻，直接请求 GitHub Releases 页面利用 302 重定向提取最新 tag。
                        val req = Request.Builder()
                            .url(Config.UPDATE_RELEASES_PAGE_URL)
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("User-Agent", container.userAgent)
                            .get()
                            .build()
                        container.httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw HttpStatusException(resp.code, "GitHub Releases HTTP ${resp.code}")
                            }
                            val finalUrl = resp.request.url.toString()
                            val html = resp.body?.string().orEmpty()
                            parseReleaseHtmlFallback(finalUrl, html, currentVersion, currentCode)
                        }
                    }
                }
                if (info != null) {
                    val ignoredVersion = container.preferences.getString("ignored_update_version", null)
                    val shouldPopup = info.mandatory || manual || ignoredVersion != info.latestVersion
                    diagnostics.log("检测到新版本: v${info.latestVersion} (必升=${info.mandatory})")
                    _updateState.update {
                        it.copy(
                            checking = false,
                            updateInfo = info,
                            showDialog = shouldPopup,
                            statusMessage = "发现新版本 v${info.latestVersion}",
                        )
                    }
                } else {
                    diagnostics.log("版本检查完成: 当前已是最新 (v$currentVersion)")
                    _updateState.update {
                        it.copy(
                            checking = false,
                            updateInfo = null,
                            showDialog = false,
                            statusMessage = if (manual) "当前已是最新版本 (v$currentVersion)" else null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("检查更新失败: ${error.message}")
                _updateState.update {
                    it.copy(
                        checking = false,
                        statusMessage = if (manual) "检查更新失败，请稍后重试" else it.statusMessage,
                    )
                }
            }
        }
    }

    private fun parseVersionManifest(
        rawJson: String,
        currentVersion: String,
        currentCode: Int,
    ): Pair<Boolean, UpdateInfo?> {
        val outer = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return false to null
        val root = if (outer["versionName"] == null && outer["encoding"]?.jsonPrimitive?.contentOrNull == "base64") {
            val encoded = outer["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val decoded = runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull().orEmpty()
            runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return false to null
        } else {
            outer
        }

        val latestVersion = root["versionName"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("v")?.removePrefix("V")?.substringBefore("-")?.trim().orEmpty()
        if (latestVersion.isEmpty()) return false to null

        val latestCode = root["versionCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val isNewer = if (latestCode > 0 && currentCode > 0) {
            latestCode > currentCode || compareVersions(latestVersion, currentVersion) > 0
        } else {
            compareVersions(latestVersion, currentVersion) > 0
        }
        if (!isNewer) return true to null

        val title = root["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "v$latestVersion 版本更新"
        val changelogElement = root["changelog"]
        val changelog = when {
            changelogElement == null -> ""
            runCatching { changelogElement.jsonArray }.isSuccess -> {
                changelogElement.jsonArray
                    .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            }
            else -> runCatching { changelogElement.jsonPrimitive.contentOrNull?.trim().orEmpty() }.getOrDefault("")
        }.ifBlank { "优化使用体验并修复已知问题。" }

        val forceUpdate = root["forceUpdate"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val minVersionCode = root["minVersionCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val minVersionName = root["minVersionName"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("v")?.removePrefix("V")?.trim()
        val mandatory = forceUpdate ||
            (minVersionCode != null && currentCode < minVersionCode) ||
            (!minVersionName.isNullOrBlank() && compareVersions(currentVersion, minVersionName) < 0)

        val apkUrl = root["apkUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: Config.UPDATE_RELEASES_PAGE_URL
        val releasePageUrl = root["releasePageUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: Config.UPDATE_RELEASES_PAGE_URL

        return true to UpdateInfo(
            latestVersion = latestVersion,
            title = title,
            changelog = changelog,
            downloadUrl = apkUrl,
            releasePageUrl = releasePageUrl,
            mandatory = mandatory,
        )
    }

    private fun parseReleaseUpdateInfo(
        rawJson: String,
        currentVersion: String,
        currentCode: Int,
    ): UpdateInfo? {
        val root = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val latestVersion = tagName.removePrefix("v").removePrefix("V").substringBefore("-").trim()
        if (latestVersion.isEmpty() || compareVersions(latestVersion, currentVersion) <= 0) {
            return null
        }
        val releaseName = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "v$latestVersion 版本更新"
        val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: Config.UPDATE_RELEASES_PAGE_URL
        val assets = root["assets"]?.jsonArray.orEmpty()
        val apkUrl = assets.firstNotNullOfOrNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) url else null
        } ?: htmlUrl

        val rawBody = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return buildUpdateInfo(latestVersion, releaseName, rawBody, apkUrl, htmlUrl, currentVersion, currentCode)
    }

    private fun parseReleaseHtmlFallback(
        finalUrl: String,
        html: String,
        currentVersion: String,
        currentCode: Int,
    ): UpdateInfo? {
        val tagName = finalUrl.substringAfter("/releases/tag/", "").substringBefore("?").substringBefore("#").trim()
            .ifBlank {
                Regex("""/releases/tag/(v?[0-9][^"'/\s<>]*)""").find(html)?.groupValues?.getOrNull(1).orEmpty()
            }
        val latestVersion = tagName.removePrefix("v").removePrefix("V").substringBefore("-").trim()
        if (latestVersion.isEmpty() || compareVersions(latestVersion, currentVersion) <= 0) {
            return null
        }
        val repoBase = Config.UPDATE_RELEASES_PAGE_URL.substringBefore("/releases")
        val apkUrl = "$repoBase/releases/download/$tagName/linuxdo-$latestVersion-release.apk"
        val bodyText = runCatching {
            org.jsoup.Jsoup.parse(html).select(".markdown-body").firstOrNull()?.wholeText()?.trim().orEmpty()
        }.getOrDefault("")
        return buildUpdateInfo(
            latestVersion = latestVersion,
            releaseName = "v$latestVersion 版本更新",
            rawBody = bodyText,
            apkUrl = apkUrl,
            htmlUrl = finalUrl.ifBlank { Config.UPDATE_RELEASES_PAGE_URL },
            currentVersion = currentVersion,
            currentCode = currentCode,
        )
    }

    private fun buildUpdateInfo(
        latestVersion: String,
        releaseName: String,
        rawBody: String,
        apkUrl: String,
        htmlUrl: String,
        currentVersion: String,
        currentCode: Int,
    ): UpdateInfo {
        val hasForceFlag = Regex("""\[(force|mandatory|必升)]|force_update\s*:\s*true""", RegexOption.IGNORE_CASE)
            .containsMatchIn(rawBody)
        val minVersionMatch = Regex("""\[min_version\s*:\s*v?([0-9.]+)]""", RegexOption.IGNORE_CASE)
            .find(rawBody)?.groupValues?.getOrNull(1)
        val minCodeMatch = Regex("""\[min_code\s*:\s*(\d+)]""", RegexOption.IGNORE_CASE)
            .find(rawBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val belowMinVersion = minVersionMatch != null && compareVersions(currentVersion, minVersionMatch) < 0
        val belowMinCode = minCodeMatch != null && currentCode < minCodeMatch
        val mandatory = hasForceFlag || belowMinVersion || belowMinCode

        val cleanChangelog = rawBody
            .replace(Regex("""\[(force|mandatory|必升)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[min_version\s*:\s*v?[0-9.]+]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[min_code\s*:\s*\d+]""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "优化使用体验并修复已知问题。" }

        return UpdateInfo(
            latestVersion = latestVersion,
            title = releaseName,
            changelog = cleanChangelog,
            downloadUrl = apkUrl,
            releasePageUrl = htmlUrl,
            mandatory = mandatory,
        )
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun dismissLoginError() {
        _loginUiState.update { it.copy(error = null, emailSent = false) }
    }

    fun resetSecondFactor() {
        _loginUiState.update { it.copy(secondFactorRequired = false, backupEnabled = false, error = null) }
    }

    fun dismissRiskControlDialog() {
        _loginUiState.update { it.copy(showRiskControlDialog = false) }
    }

    /** 账号密码登录：先拿 CSRF token，再 POST /session（支持 2FA 两步验证）。 */
    fun loginWithPassword(
        login: String,
        password: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        if (_loginUiState.value.loading) return
        viewModelScope.launch {
            _loginUiState.update { it.copy(loading = true, error = null) }
            try {
                val result = repository.sessionLogin(login, password, secondFactorToken, secondFactorMethod)
                if (result) {
                    diagnostics.log("原生密码登录成功，回调 boot()")
                    _loginUiState.update { LoginUiState() }
                    boot()
                } else {
                    _loginUiState.update { it.copy(loading = false, error = "用户名或密码错误，请检查后重试。") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecondFactorRequiredException) {
                _loginUiState.update {
                    it.copy(
                        loading = false,
                        secondFactorRequired = true,
                        backupEnabled = error.backupEnabled,
                        error = null,
                    )
                }
            } catch (error: NeedsInteractiveVerification) {
                _loginUiState.update { it.copy(loading = false) }
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: RiskControlException) {
                diagnostics.log("密码登录遇到 403 风控: ${error.message}")
                _loginUiState.update {
                    it.copy(
                        loading = false,
                        showRiskControlDialog = true,
                        error = null,
                    )
                }
            } catch (error: HttpStatusException) {
                if (error.status == 403) {
                    diagnostics.log("密码登录遇到 403 状态: ${error.message}")
                    _loginUiState.update {
                        it.copy(
                            loading = false,
                            showRiskControlDialog = true,
                            error = null,
                        )
                    }
                } else {
                    _loginUiState.update { it.copy(loading = false, error = error.message ?: "登录失败，请稍后重试。") }
                }
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
            } catch (error: NeedsInteractiveVerification) {
                _loginUiState.update { it.copy(sendingCode = false) }
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: RiskControlException) {
                diagnostics.log("发送邮箱验证码遇到 403 风控: ${error.message}")
                _loginUiState.update {
                    it.copy(
                        sendingCode = false,
                        showRiskControlDialog = true,
                        error = null,
                    )
                }
            } catch (error: HttpStatusException) {
                if (error.status == 403) {
                    diagnostics.log("发送邮箱验证码遇到 403 状态: ${error.message}")
                    _loginUiState.update {
                        it.copy(
                            sendingCode = false,
                            showRiskControlDialog = true,
                            error = null,
                        )
                    }
                } else {
                    _loginUiState.update { it.copy(sendingCode = false, error = error.message ?: "发送失败，请稍后重试。") }
                }
            } catch (error: Exception) {
                _loginUiState.update { it.copy(sendingCode = false, error = error.message ?: "发送失败，请稍后重试。") }
            }
        }
    }

    /**
     * 用用户从邮件链接中复制的 token 完成登录（支持 2FA 两步验证）。
     */
    fun loginWithEmailCode(
        email: String,
        token: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        if (_loginUiState.value.loading) return
        val sanitizedToken = token.trim()
        viewModelScope.launch {
            _loginUiState.update { it.copy(loading = true, error = null) }
            try {
                if (sanitizedToken.isBlank()) {
                    _loginUiState.update { it.copy(loading = false, error = "请输入邮件链接中的 token") }
                    return@launch
                }
                repository.emailLoginWithToken(sanitizedToken, secondFactorToken, secondFactorMethod)
                diagnostics.log("邮件 token 登录成功,复核会话")
                _loginUiState.update { LoginUiState() }
                boot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecondFactorRequiredException) {
                _loginUiState.update {
                    it.copy(
                        loading = false,
                        secondFactorRequired = true,
                        backupEnabled = error.backupEnabled,
                        error = null,
                    )
                }
            } catch (error: NeedsInteractiveVerification) {
                _loginUiState.update { it.copy(loading = false) }
                pendingEmailLoginToken = sanitizedToken
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: RiskControlException) {
                diagnostics.log("邮箱 token 登录遇到 403 风控: ${error.message}")
                _loginUiState.update {
                    it.copy(
                        loading = false,
                        showRiskControlDialog = true,
                        error = null,
                    )
                }
            } catch (error: HttpStatusException) {
                if (error.status == 403) {
                    diagnostics.log("邮箱 token 登录遇到 403 状态: ${error.message}")
                    _loginUiState.update {
                        it.copy(
                            loading = false,
                            showRiskControlDialog = true,
                            error = null,
                        )
                    }
                } else {
                    _loginUiState.update { it.copy(loading = false, error = error.message ?: "登录失败，请检查 token 是否正确。") }
                }
            } catch (error: Exception) {
                _loginUiState.update { it.copy(loading = false, error = error.message ?: "登录失败，请检查 token 是否正确。") }
            }
        }
    }

    /** CF 验证通过后重放被拦下的邮件登录。 */
    private fun retryEmailLogin(sanitizedToken: String) {
        viewModelScope.launch {
            _loginUiState.update { it.copy(loading = true, error = null) }
            try {
                repository.emailLoginWithToken(sanitizedToken)
                _loginUiState.update { LoginUiState() }
                boot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecondFactorRequiredException) {
                _loginUiState.update {
                    it.copy(
                        loading = false,
                        secondFactorRequired = true,
                        backupEnabled = error.backupEnabled,
                        error = null,
                    )
                }
                _state.update { it.copy(screen = Screen.Login) }
            } catch (error: RiskControlException) {
                diagnostics.log("重试邮件登录遇到 403 风控: ${error.message}")
                _loginUiState.update {
                    it.copy(
                        loading = false,
                        showRiskControlDialog = true,
                        error = null,
                    )
                }
                _state.update { it.copy(screen = Screen.Login) }
            } catch (error: Exception) {
                _loginUiState.update {
                    it.copy(loading = false, error = error.message ?: "登录失败，请重新获取验证码。")
                }
                _state.update { it.copy(screen = Screen.Login) }
            }
        }
    }

    /** 发起网页授权认证（拉起系统浏览器或外部应用） */
    fun startWebAuth(context: Context) {
        viewModelScope.launch {
            _loginUiState.update {
                it.copy(
                    showRiskControlDialog = false,
                    webAuthLoading = true,
                    webAuthMessage = "正在准备网页授权...",
                    error = null,
                )
            }
            try {
                val url = userApiKeyAuthService.buildAuthorizeUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = true,
                        webAuthMessage = "已调起浏览器，请在网页中完成授权...",
                    )
                }
            } catch (e: Exception) {
                diagnostics.log("拉起网页授权失败: ${e.message}")
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = false,
                        webAuthMessage = null,
                        error = "无法打开浏览器，请检查是否安装了浏览器应用",
                    )
                }
            }
        }
    }

    /** 接收深链回调并兑换令牌 */
    fun handleWebAuthCallback(uri: Uri) {
        viewModelScope.launch {
            _loginUiState.update {
                it.copy(
                    webAuthLoading = true,
                    webAuthMessage = "正在兑换登录令牌并验证...",
                    error = null,
                )
            }
            try {
                userApiKeyAuthService.handleAuthRedirect(uri)
                diagnostics.log("网页授权登录成功，重新加载会话")
                _loginUiState.update { LoginUiState() }
                boot()
            } catch (e: Exception) {
                diagnostics.log("网页授权登录兑换失败: ${e.message}")
                _loginUiState.update {
                    it.copy(
                        webAuthLoading = false,
                        webAuthMessage = null,
                        error = e.message ?: "网页授权登录失败，请重试",
                    )
                }
            }
        }
    }

    fun cancelWebAuth() {
        userApiKeyAuthService.cancel()
        _loginUiState.update {
            it.copy(
                webAuthLoading = false,
                webAuthMessage = null,
            )
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
        val filter = _notificationFilter.value
        val previous = _profile.value[section] ?: ProfileUiState()
        if (previous.loading) return
        if (!refresh && !more && previous.loaded && isCacheUsable(section, filter, previous)) return
        if (more && !previous.content.hasMore) return
        val cursor = if (more) previous.content.nextCursor else 0
        pageJobs["profile:$section"] = viewModelScope.launch {
            _profile.update { it + (section to previous.copy(loading = true, error = null)) }
            try {
                val fresh = fetchProfilePage(username, section, filter, previous, more)
                _profile.update { it + (section to fresh) }
                if (section == ProfileSection.Notifications) notificationCache[filter] = fresh
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

    /** 拉一页并组装成新状态。前台加载和后台预取共用这一条路径,避免两套逻辑走偏。 */
    private suspend fun fetchProfilePage(
        username: String,
        section: ProfileSection,
        filter: NotificationFilter,
        previous: ProfileUiState,
        more: Boolean,
    ): ProfileUiState {
        val cursor = if (more) previous.content.nextCursor else 0
        val unreadNow = unreadBaseline(section, filter)
        val result = repository.fetchProfile(username, section, cursor, filter)
        val entries = if (more) (previous.content.entries + result.entries).distinctBy { it.id } else result.entries
        return ProfileUiState(
            // 防死循环的守卫看的是游标有没有往前走,而不是条目有没有变多 ——
            // 低频通知类型完全可能某一轮一条都没命中,但后面还有。
            content = result.copy(entries = entries,
                hasMore = result.hasMore && (!more || result.nextCursor > previous.content.nextCursor)),
            loaded = true,
            loadedAt = SystemClock.elapsedRealtime(),
            unreadAtLoad = unreadNow,
        )
    }

    /**
     * 这个分区该拿哪个未读数做基准。
     * 通知按分类分项取;总结/活动/账号这些和未读无关的分区返回 0,等于只受 TTL 约束 ——
     * 别因为来了条回复就把账号资料的缓存也作废。
     */
    private fun unreadBaseline(section: ProfileSection, filter: NotificationFilter): Int = when (section) {
        ProfileSection.Notifications -> _state.value.unreadByFilter[filter] ?: 0
        ProfileSection.Messages -> _state.value.unreadByFilter[NotificationFilter.Messages] ?: 0
        else -> 0
    }

    /**
     * 这份缓存还能不能直接拿来显示。
     *
     * 两条判据取"与":该分区对应的未读没涨 **且** 没到 TTL。
     * 未读必须按分类取 —— 铃铛的总数只算回复类和私信类,点赞压根不计入,
     * 拿总数判断会让点赞分类永远判定为没变化、一直吃旧缓存。
     * 未读变少是用户自己读掉的,列表已经就地更新过,所以用 `<=` 而不是 `==`。
     */
    private fun isCacheUsable(
        section: ProfileSection,
        filter: NotificationFilter,
        state: ProfileUiState,
    ): Boolean {
        if (!state.loaded) return false
        if (SystemClock.elapsedRealtime() - state.loadedAt >= Config.PROFILE_CACHE_TTL_MS) return false
        return unreadBaseline(section, filter) <= state.unreadAtLoad
    }

    /**
     * 切换通知分类。
     *
     * 四个分类共用 `_profile` 里同一个槽位(键是 ProfileSection),所以这里额外用
     * notificationCache 按分类留一份。命中且仍新鲜就直接贴回去 —— 点标签立刻有内容,
     * 不用再等一轮网络。
     */
    fun setNotificationFilter(filter: NotificationFilter) {
        if (_notificationFilter.value == filter) return
        pageJobs["profile:${ProfileSection.Notifications}"]?.cancel()
        _notificationFilter.value = filter
        val cached = notificationCache[filter]
        if (cached != null && isCacheUsable(ProfileSection.Notifications, filter, cached)) {
            _profile.update { it + (ProfileSection.Notifications to cached.copy(loading = false, error = null)) }
            return
        }
        notificationCache.remove(filter)
        _profile.update { it - ProfileSection.Notifications }
        loadProfile(ProfileSection.Notifications)
    }

    fun retryTopic(id: Long, postNumber: Int? = null) {
        val failed = _details.value[id]?.failedLoad
        loadTopic(id, more = failed == DetailLoad.Next, previous = failed == DetailLoad.Previous,
            retry = true, postNumber = postNumber)
    }

    fun refreshTopic(id: Long, silent: Boolean = false) {
        val current = _details.value[id] ?: return
        if (current.loading || current.refreshing || current.reactingPosts.isNotEmpty()) return
        if (pageJobs["topic:$id"]?.isActive == true) return
        pageJobs["topic:$id"] = viewModelScope.launch {
            if (!silent) {
                _details.update { states ->
                    states[id]?.let { states + (id to it.copy(refreshing = true, error = null)) } ?: states
                }
            }
            try {
                val freshTopic = repository.fetchTopic(id)
                val freshById = freshTopic.postStream.posts.associateBy { it.id }
                val latest = _details.value[id] ?: current
                val wasAtEnd = latest.remaining.isEmpty()
                val updatedExisting = latest.posts.map { freshById[it.id] ?: it }
                var mergedPosts = (updatedExisting + freshTopic.postStream.posts)
                    .distinctBy { it.id }
                    .sortedBy { it.postNumber }
                var consumed = latest.consumedIds + freshTopic.postStream.posts.map { it.id }

                val windowIdx = freshTopic.postStream.stream.indexOf(latest.windowStartId).coerceAtLeast(0)
                val newRemaining = freshTopic.postStream.stream.drop(windowIdx).filterNot { it in consumed }
                // 若刷新前用户已看完旧楼层，且服务端出现了新回复，直接把最新一批新回复拉下来并入列表。
                if (wasAtEnd && newRemaining.isNotEmpty()) {
                    val tailBatch = newRemaining.take(20)
                    val fetchedTail = runCatching { repository.fetchPosts(id, tailBatch) }.getOrNull()
                    if (fetchedTail != null) {
                        mergedPosts = (mergedPosts + fetchedTail).distinctBy { it.id }.sortedBy { it.postNumber }
                        consumed = consumed + tailBatch
                    }
                }
                _details.update { states ->
                    val cur = states[id] ?: return@update states
                    states + (id to cur.copy(
                        topic = freshTopic,
                        posts = mergedPosts,
                        consumedIds = consumed,
                        refreshing = false,
                        error = null,
                    ))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!silent) {
                    _details.update { states ->
                        states[id]?.let {
                            states + (id to it.copy(error = error.message ?: "刷新失败", failedLoad = DetailLoad.Topic))
                        } ?: states
                    }
                    handlePageFailure(error) { refreshTopic(id, silent = false) }
                }
            } finally {
                _details.update { states ->
                    states[id]?.let { states + (id to it.copy(refreshing = false)) } ?: states
                }
            }
        }
    }

    fun loadTopic(id: Long, more: Boolean = false, retry: Boolean = false, postNumber: Int? = null, previous: Boolean = false) {
        val current = _details.value[id] ?: DetailUiState()
        val targetLoaded = postNumber == null || current.posts.any { it.postNumber == postNumber }
        val isExplicitJump = !more && !previous && postNumber != null && !targetLoaded
        if (current.reactingPosts.isNotEmpty() || (current.loading && !isExplicitJump)) return
        if (!more && !previous && current.topic != null && !retry && targetLoaded) {
            // 再次进入已缓存的话题时，后台增量同步最新 postStream 与新增回复，避免卡在旧评论数。
            if (postNumber == null) refreshTopic(id, silent = true)
            return
        }
        if (more && current.remaining.isEmpty()) return
        if (previous && current.previousIds.isEmpty()) return
        pageJobs["topic:$id"]?.cancel()
        pageJobs["topic:$id"] = viewModelScope.launch {
            _details.update { it + (id to current.copy(loading = true, error = null)) }
            try {
                if (more || previous) {
                    val requested = if (previous) current.previousIds.takeLast(20) else current.remaining.take(20)
                    val posts = repository.fetchPosts(id, requested)
                    _details.update {
                        val latest = it[id] ?: current
                        it + (id to latest.copy(
                            posts = (latest.posts + posts).distinctBy { post -> post.id }.sortedBy { post -> post.postNumber },
                            consumedIds = latest.consumedIds + requested,
                            windowStartId = if (previous) requested.first() else current.windowStartId,
                            error = null, failedLoad = DetailLoad.Topic,
                        ))
                    }
                } else {
                    val topic = repository.fetchTopic(id, postNumber)
                    val category = repository.categoryById(topic.categoryId)
                    val emojis = repository.emojiUrls()
                    val rootPost = current.posts.filter { it.postNumber == 1 }
                    val posts = if (postNumber != null) (rootPost + topic.postStream.posts).distinctBy { it.id }.sortedBy { it.postNumber }
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

    fun refreshUnread(force: Boolean = false, reloadNotifications: Boolean = true) {
        if (unreadJob?.isActive == true || _state.value.screen != Screen.List) return
        if (!force && SystemClock.elapsedRealtime() < nextUnreadRefreshAt) return
        unreadJob = viewModelScope.launch {
            try {
                val beforeByFilter = _state.value.unreadByFilter
                val summary = repository.fetchUnreadSummary()
                unreadFailures = 0
                nextUnreadRefreshAt = 0L
                _state.update { it.copy(
                    unreadCount = summary.count,
                    unreadFilters = summary.unreadFilters,
                    unreadByFilter = summary.unreadByFilter,
                ) }
                // 任一分类的未读变多才需要动。变少是用户自己读掉的,列表已经就地更新过。
                val grew = summary.unreadByFilter.any { (filter, now) -> now > (beforeByFilter[filter] ?: 0) }
                if (reloadNotifications && grew) prefetchUnreadNotifications()
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                unreadFailures = (unreadFailures + 1).coerceAtMost(4)
                nextUnreadRefreshAt = SystemClock.elapsedRealtime() + (Config.UNREAD_POLL_INTERVAL_MS shl unreadFailures)
                diagnostics.log("未读数量刷新失败: ${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * 预热有未读的通知分类,让用户点标签时直接有内容。
     *
     * 一轮只预热一个分类:repository 的 gate 是全局串行锁,预取一旦占住它,
     * 用户紧接着的操作就得排在后面等。未读每 10 秒轮询一次,几轮下来相关分类都会热好,
     * 而单次占用是有上限的。当前正在看的分类优先,其余按声明顺序补。
     */
    private fun prefetchUnreadNotifications() {
        if (latestLoading || pageJobs.values.any { it.isActive }) return
        val username = _state.value.username ?: return
        val current = _notificationFilter.value
        val unread = _state.value.unreadFilters
        val target = (listOf(current) + NotificationFilter.entries).distinct()
            .filter { it == current || it in unread }
            .firstOrNull { filter ->
                val cached = notificationCache[filter]
                cached == null || !isCacheUsable(ProfileSection.Notifications, filter, cached)
            } ?: return

        pageJobs["prefetch:notifications"] = viewModelScope.launch {
            // 预取的正好是用户当前看的那一类时,把 loading 立起来:
            // 既让界面有反馈,也挡住 loadProfile 再发一次重复请求。
            val showing = _notificationFilter.value == target
            if (showing) {
                _profile.update { states ->
                    states + (ProfileSection.Notifications to
                        (states[ProfileSection.Notifications] ?: ProfileUiState()).copy(loading = true, error = null))
                }
            }
            try {
                val fresh = fetchProfilePage(username, ProfileSection.Notifications, target, ProfileUiState(), more = false)
                notificationCache[target] = fresh
                if (_notificationFilter.value == target) {
                    _profile.update { it + (ProfileSection.Notifications to fresh) }
                }
                diagnostics.log("已预取通知分类: ${target.label}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 用户还没点进来,预取失败不该弹错误;真点进去时会正常重试并展示错误。
                diagnostics.log("预取通知失败: ${error.javaClass.simpleName}")
            } finally {
                if (showing) {
                    _profile.update { states ->
                        states[ProfileSection.Notifications]?.let {
                            states + (ProfileSection.Notifications to it.copy(loading = false))
                        } ?: states
                    }
                }
            }
        }
    }

    fun readNotification(id: Long) {
        if (pageJobs["notification:$id"]?.isActive == true) return
        // 先本地扣减再发请求。否则角标要等 markNotificationRead 和 fetchUnreadSummary
        // 两趟请求(各自还有 1 秒节流 + 往返)才更新,用户点进去再退回来会看到它还亮着。
        val type = _profile.value[ProfileSection.Notifications]?.content?.entries
            ?.firstOrNull { it.id == id.toString() }?.notificationType
        markReadLocally(id, type)
        pageJobs["notification:$id"] = viewModelScope.launch {
            try {
                repository.markNotificationRead(id)
                // 等正在进行的分页统计结束,避免已读后的强制刷新被 isActive 守卫丢掉。
                unreadJob?.join()
                // 这条通知上面已经就地标成已读了,不要再整段重拉 —— 那会把用户翻过的页面
                // 和滚动位置一起丢掉(低频分类还要为此多扫好几页)。
                refreshUnread(force = true, reloadNotifications = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { diagnostics.log("通知已读状态未同步: ${error.javaClass.simpleName}") }
        }
    }

    /**
     * 就地把一条通知标为已读,并把它从未读计数里扣掉。
     *
     * **按条减 1,不是清零。** 有 5 条未读回复时读掉 1 条,回复分类的计数变 4、角标继续亮着,
     * 减到 0 才摘点。拿不到这条的类型(不在当前已加载列表里)就不动计数,退回等服务端的老行为。
     * 这只是个本地估算,随后的 refreshUnread 会用服务端权威数据覆盖,偏了也能自愈。
     */
    private fun markReadLocally(id: Long, type: Int?) {
        val key = id.toString()
        fun markRead(page: ProfilePage) = page.copy(
            entries = page.entries.map { if (it.id == key) it.copy(unread = false) else it },
        )
        _profile.update { panels ->
            panels[ProfileSection.Notifications]?.let { notices ->
                panels + (ProfileSection.Notifications to notices.copy(content = markRead(notices.content)))
            } ?: panels
        }
        // 按分类的缓存也要跟着改,否则切出去再切回来这条又变回未读。
        notificationCache.replaceAll { _, cached -> cached.copy(content = markRead(cached.content)) }
        if (type == null) return
        _state.update { state ->
            val reduced = state.unreadByFilter.mapValues { (filter, count) ->
                if (filter.includes(type)) (count - 1).coerceAtLeast(0) else count
            }
            state.copy(
                unreadByFilter = reduced,
                unreadFilters = reduced.filterValues { it > 0 }.keys,
                // 铃铛口径只算回复类和私信类,和 NotificationCountItem.countsAsUnread 保持一致。
                unreadCount = if (NotificationFilter.Replies.includes(type) || NotificationFilter.Messages.includes(type)) {
                    (state.unreadCount - 1).coerceAtLeast(0)
                } else state.unreadCount,
            )
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

    /** 打开编辑器。编辑已有楼层时顺带把原文取回来填进输入框。 */
    fun openComposer(target: ComposerTarget) {
        if (_composer.value.submitting) return
        val needsDraft = target is ComposerTarget.Edit
        _composer.value = ComposerUiState(target = target, loadingDraft = needsDraft)
        if (target !is ComposerTarget.Edit) return
        pageJobs["draft:${target.postId}"] = viewModelScope.launch {
            try {
                val raw = repository.fetchPostRaw(target.postId)
                _composer.update { if (it.target == target) it.copy(draft = raw, loadingDraft = false) else it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _composer.update {
                    if (it.target == target) it.copy(loadingDraft = false,
                        error = error.message ?: "原文加载失败，请关闭后重试") else it
                }
            }
        }
    }

    fun updateDraft(text: String) {
        _composer.update { if (it.open) it.copy(draft = text, error = null) else it }
    }

    fun closeComposer() {
        if (_composer.value.submitting) return
        _composer.value = ComposerUiState()
    }

    /**
     * 上传选中的文件,成功后把 Markdown 短码追加到草稿末尾。
     *
     * 走"选完即传、插短码"这条路(和网页端一致):正文里直接留 `![名|宽x高](upload://…)`,
     * 所以图文可以穿插排版,而且新建回复和编辑旧楼层完全通用。
     */
    fun attachToComposer(uri: Uri) {
        val current = _composer.value
        if (!current.open || current.uploading || current.submitting) return
        pageJobs["upload"] = viewModelScope.launch {
            _composer.update { it.copy(uploading = true, error = null) }
            try {
                val picked = withContext(Dispatchers.IO) { readPickedFile(uri) }
                val uploaded = repository.uploadFile(picked.name, picked.mimeType, picked.bytes)
                _composer.update {
                    if (!it.open) it.copy(uploading = false) else {
                        val gap = if (it.draft.isEmpty() || it.draft.endsWith("\n")) "" else "\n"
                        it.copy(draft = it.draft + gap + uploaded.toMarkdown() + "\n", uploading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _composer.update { it.copy(uploading = false, error = error.message ?: "上传失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    private data class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

    /** 先按大小、类型预检,再限量读取；位图按需降采样。 */
    private fun readPickedFile(uri: Uri): PickedFile {
        val resolver = getApplication<Application>().contentResolver
        var displayName: String? = null
        var reportedSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        reportedSize = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                    }
                }
            }
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "upload"
        val bytes = UploadLimits.readBytes(name, reportedSize) { resolver.openInputStream(uri) }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (!UploadLimits.isCompressibleImage(name)) return PickedFile(name, mime, bytes)
        return shrinkImage(name, bytes) ?: PickedFile(name, mime, bytes)
    }

    /**
     * 把超尺寸/超大的位图收一收。返回 null 表示不需要动或处理不了(调用方原样上传)。
     *
     * PNG 优先仍编码成 PNG —— 转 JPEG 会把透明区域填成黑色。只有降采样后 PNG 还是太大时
     * 才退成 JPEG,那种情况下用户本来也没得选(4MB 是硬上限)。
     * GIF/SVG/AVIF/JXL 不走这里:动图会被压成静态,后两者解码支持也不稳。
     */
    private fun shrinkImage(name: String, bytes: ByteArray): PickedFile? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return@runCatching null
        // 既没超尺寸也没超大小就别碰,重编码只会白掉画质。
        if (longEdge <= UploadLimits.MAX_IMAGE_EDGE && bytes.size <= UploadLimits.MAX_BYTES) return@runCatching null

        var sample = 1
        while (longEdge / sample > UploadLimits.MAX_IMAGE_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null

        val isPng = UploadLimits.extensionOf(name) == "png"
        val out = ByteArrayOutputStream()
        if (isPng) {
            decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (out.size() <= UploadLimits.MAX_BYTES) {
                decoded.recycle()
                return@runCatching PickedFile(name, "image/png", out.toByteArray())
            }
        }
        var quality = UploadLimits.JPEG_QUALITY
        do {
            out.reset()
            decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)
            quality -= 10
        } while (out.size() > UploadLimits.MAX_BYTES && quality >= 40)
        decoded.recycle()
        PickedFile("${name.substringBeforeLast('.', name)}.jpg", "image/jpeg", out.toByteArray())
    }.getOrNull()

    /** 提交编辑器内容。成功后就地并入已加载楼层,不整页重拉。 */
    fun submitComposer() {
        val current = _composer.value
        val target = current.target ?: return
        if (!current.canSubmit) return
        val raw = current.draft
        pageJobs["composer"] = viewModelScope.launch {
            _composer.update { it.copy(submitting = true, error = null) }
            try {
                when (target) {
                    is ComposerTarget.NewReply ->
                        applyCreatedPost(target.topicId, repository.createPost(target.topicId, raw), forceTailWindow = true)
                    is ComposerTarget.ReplyTo ->
                        applyCreatedPost(target.topicId, repository.createPost(target.topicId, raw, target.postNumber), forceTailWindow = false)
                    is ComposerTarget.Edit -> applyEditedPost(target.topicId, repository.updatePost(target.postId, raw))
                }
                _composer.value = ComposerUiState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _composer.update { it.copy(submitting = false, error = error.message ?: "提交失败，请稍后重试") }
                handlePageFailure(error) {}
            }
        }
    }

    /**
     * 新楼层必须同时登记进 consumedIds 和 stream:
     * 不进 consumedIds,`remaining` 会把它当成"未加载楼层"再拉一次;
     * 不进 stream,话题总楼层数和楼层进度就对不上。
     */
    private suspend fun applyCreatedPost(topicId: Long, post: Post, forceTailWindow: Boolean) {
        val snapshot = _details.value[topicId]
        val atTail = forceTailWindow || post.replyToPostNumber == null || !_treeView.value
        if (snapshot != null && atTail && snapshot.remaining.isNotEmpty()) {
            val tailTopic = runCatching {
                repository.fetchTopic(topicId, postNumber = post.postNumber, burst = true)
            }.getOrNull()
            if (tailTopic != null) {
                _details.update { states ->
                    val current = states[topicId] ?: return@update states
                    val firstPost = current.posts.firstOrNull { it.postNumber == 1 }
                    val tailPosts = (tailTopic.postStream.posts + post)
                        .distinctBy { it.id }
                        .sortedBy { it.postNumber }
                    val windowAnchor = tailPosts.firstOrNull { it.postNumber > 1 }?.id
                        ?: tailPosts.firstOrNull()?.id
                        ?: post.id
                    val mergedPosts = (listOfNotNull(firstPost) + tailPosts)
                        .distinctBy { it.id }
                        .sortedBy { it.postNumber }
                    val rawStream = tailTopic.postStream.stream
                    val fullStream = if (post.id in rawStream) rawStream else rawStream + post.id
                    states + (topicId to current.copy(
                        topic = tailTopic.copy(postStream = tailTopic.postStream.copy(stream = fullStream)),
                        posts = mergedPosts,
                        consumedIds = mergedPosts.map { it.id }.toSet(),
                        windowStartId = windowAnchor,
                        createdPostId = post.id,
                        error = null,
                    ))
                }
                return
            }
        }
        applyNewPost(topicId, post)
    }

    private fun applyNewPost(topicId: Long, post: Post) {
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            val stream = current.topic?.postStream?.stream.orEmpty()
            val topic = current.topic?.let {
                if (post.id in stream) it
                else it.copy(postStream = it.postStream.copy(stream = stream + post.id))
            }
            states + (topicId to current.copy(
                topic = topic,
                posts = (current.posts + post).distinctBy { it.id }.sortedBy { it.postNumber }
                    // 父楼层的 reply_count 同步 +1,否则"更多回复"的计数判断会漏掉服务端其余回复。1 楼为主贴不挂楼中楼。
                    .map { if (it.postNumber == post.replyToPostNumber && it.postNumber > 1) it.copy(replyCount = it.replyCount + 1) else it },
                consumedIds = current.consumedIds + post.id,
                windowStartId = current.windowStartId ?: post.id,
                createdPostId = post.id,
            ))
        }
    }

    /** 界面已经滚到新楼层了,清掉标记,免得后面再触发一次。 */
    fun clearCreatedPost(topicId: Long) {
        _details.update { states ->
            states[topicId]?.takeIf { it.createdPostId != null }
                ?.let { states + (topicId to it.copy(createdPostId = null)) } ?: states
        }
    }

    private fun applyEditedPost(topicId: Long, post: Post) {
        _details.update { states ->
            val current = states[topicId] ?: return@update states
            states + (topicId to current.copy(
                // 编辑只改内容。响应里缺字段会被反序列化成默认值,整条替换会清空点赞/简评,
                // 更糟的是 reply_to_post_number 变 null 会把这条回复抬成根节点、打乱树形排列,
                // 所以除 cooked 之外的既有状态一律保留。
                posts = current.posts.map { existing ->
                    if (existing.id != post.id) existing
                    else post.copy(
                        postNumber = existing.postNumber,
                        replyToPostNumber = existing.replyToPostNumber,
                        replyCount = existing.replyCount,
                        boosts = existing.boosts, reactions = existing.reactions,
                        currentUserReaction = existing.currentUserReaction, canBoost = existing.canBoost,
                        acceptedAnswer = existing.acceptedAnswer, canAcceptAnswer = existing.canAcceptAnswer,
                        canUnacceptAnswer = existing.canUnacceptAnswer,
                        avatarTemplate = post.avatarTemplate ?: existing.avatarTemplate,
                        username = post.username.ifBlank { existing.username },
                        createdAt = post.createdAt ?: existing.createdAt,
                    )
                },
            ))
        }
    }

    /** 删除楼层。#1 楼会连带删除整个话题,由界面负责在确认框里说清楚。 */
    fun deletePost(topicId: Long, postId: Long) {
        val current = _details.value[topicId] ?: return
        if (current.loading || current.reactingPosts.isNotEmpty()) return
        if (current.posts.none { it.id == postId }) return
        pageJobs["delete:$postId"] = viewModelScope.launch {
            _details.update { it + (topicId to current.copy(reactingPosts = setOf(postId),
                reactionErrors = current.reactionErrors - postId)) }
            try {
                repository.deletePost(postId)
                _details.update { states ->
                    val latest = states[topicId] ?: return@update states
                    val removed = latest.posts.firstOrNull { it.id == postId }
                    val topic = latest.topic?.let { topic ->
                        topic.copy(postStream = topic.postStream.copy(
                            stream = topic.postStream.stream.filterNot { it == postId }))
                    }
                    states + (topicId to latest.copy(
                        topic = topic,
                        posts = latest.posts.filterNot { it.id == postId }.map {
                            if (it.postNumber == removed?.replyToPostNumber && it.postNumber > 1) {
                                it.copy(replyCount = (it.replyCount - 1).coerceAtLeast(0))
                            } else it
                        },
                        consumedIds = latest.consumedIds - postId,
                        windowStartId = if (latest.windowStartId == postId) {
                            latest.posts.firstOrNull { it.id != postId }?.id
                        } else latest.windowStartId,
                    ))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let {
                    states + (topicId to it.copy(reactionErrors = it.reactionErrors + (postId to (error.message ?: "删除失败"))))
                } ?: states }
                handlePageFailure(error) { loadTopic(topicId, retry = true) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(reactingPosts = emptySet())) } ?: states }
            }
        }
    }

    /** 回到 1 楼。窗口化加载下 1 楼可能还没取回来,这时从头重拉整个话题。 */
    fun reloadFromStart(topicId: Long) {
        val current = _details.value[topicId] ?: return
        if (current.loading) return
        pageJobs["topic:$topicId"]?.cancel()
        _details.update { it - topicId }
        loadTopic(topicId)
    }

    fun loadReplies(topicId: Long, postId: Long) {
        val current = _details.value[topicId] ?: return
        if (postId in current.loadingReplies || current.reactingPosts.isNotEmpty()) return
        val tree = threadRows(current.posts)
        val matchedNode = tree.firstOrNull { it.post.id == postId }
        val rootPostId = matchedNode?.rootPostId ?: postId
        val rootNode = tree.firstOrNull { it.post.id == rootPostId }
        val pendingSubtreeIds = rootNode?.unloadedCandidateIds?.filterNot { it in current.completedReplies }.orEmpty()
        if (postId in current.completedReplies && pendingSubtreeIds.isEmpty()) return

        val activeLoadingIds = mutableSetOf(postId, rootPostId)
        pageJobs["replies:$topicId:$rootPostId"] = viewModelScope.launch {
            _details.update { states ->
                states[topicId]?.let { states + (topicId to it.copy(loadingReplies = it.loadingReplies + activeLoadingIds, error = null)) } ?: states
            }
            try {
                var iterations = 0
                val maxIterations = 30
                while (isActive && iterations < maxIterations) {
                    iterations++
                    val snapshot = _details.value[topicId] ?: break
                    val currentTree = threadRows(snapshot.posts)
                    val currentRootRow = currentTree.firstOrNull { it.post.id == rootPostId }
                    val nextCandidateId = if (postId !in snapshot.completedReplies) {
                        postId
                    } else {
                        currentRootRow?.unloadedCandidateIds?.firstOrNull { it !in snapshot.completedReplies }
                    } ?: break

                    if (nextCandidateId !in activeLoadingIds) {
                        activeLoadingIds.add(nextCandidateId)
                        _details.update { states ->
                            states[topicId]?.let { states + (topicId to it.copy(loadingReplies = it.loadingReplies + nextCandidateId)) } ?: states
                        }
                    }

                    val after = snapshot.replyCursors[nextCandidateId] ?: 1
                    val replies = repository.fetchReplies(nextCandidateId, after)
                    val cursor = replies.maxOfOrNull { it.postNumber }

                    _details.update { states ->
                        val latest = states[topicId] ?: return@update states
                        val parent = latest.posts.firstOrNull { it.id == nextCandidateId }
                        val parentNumber = parent?.postNumber
                        val normalizedReplies = replies.map { reply ->
                            if (parentNumber != null && reply.postNumber > parentNumber) {
                                reply.copy(replyToPostNumber = parentNumber)
                            } else {
                                reply
                            }
                        }
                        val replyById = normalizedReplies.associateBy { it.id }
                        val patchedExisting = latest.posts.map { existing ->
                            val fetched = replyById[existing.id] ?: return@map existing
                            val targetParent = fetched.effectiveReplyToPostNumber() ?: parentNumber
                            val existingParent = existing.effectiveReplyToPostNumber()
                            val hasLoadedParent = existingParent != null && latest.posts.any { it.postNumber == existingParent && it.postNumber < existing.postNumber }
                            if (!hasLoadedParent && targetParent != null && existing.postNumber > targetParent) {
                                existing.copy(replyToPostNumber = targetParent)
                            } else {
                                existing
                            }
                        }
                        val existingIds = patchedExisting.map { it.id }.toSet()
                        val mergedPosts = (patchedExisting + normalizedReplies.filterNot { it.id in existingIds }).sortedBy { it.postNumber }
                        val childCount = if (parentNumber != null) {
                            mergedPosts.count { it.postNumber > parentNumber && it.effectiveReplyToPostNumber() == parentNumber }
                        } else 0
                        val progressed = cursor != null && cursor > after
                        val isCandidateDone = replies.isEmpty() || replies.size < 20 || !progressed || (parent != null && childCount >= parent.replyCount)
                        states + (topicId to latest.copy(
                            posts = mergedPosts,
                            consumedIds = latest.consumedIds + replies.map { post -> post.id },
                            replyCursors = if (cursor == null) latest.replyCursors else latest.replyCursors + (nextCandidateId to cursor),
                            completedReplies = if (isCandidateDone) latest.completedReplies + nextCandidateId else latest.completedReplies,
                        ))
                    }

                    if (replies.isEmpty()) {
                        _details.update { states ->
                            states[topicId]?.let { states + (topicId to it.copy(completedReplies = it.completedReplies + nextCandidateId)) } ?: states
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(error = error.message ?: "回复加载失败")) } ?: states }
                handlePageFailure(error) { loadReplies(topicId, postId) }
            } finally {
                _details.update { states -> states[topicId]?.let { states + (topicId to it.copy(loadingReplies = it.loadingReplies - activeLoadingIds)) } ?: states }
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
        checkForUpdates(manual = false)
    }

    fun boot() {
        if (repository.cachedCategoryList() == null) {
            container.preferences.getString("cached_categories_json", null)?.let { rawJson ->
                repository.seedCategoriesFromJson(rawJson)
                repository.cachedCategoryList()?.let { list ->
                    _categories.value = CategoriesUiState(items = list, loaded = true)
                }
            }
        }
        bootJob?.cancel()
        bootJob = viewModelScope.launch {
            verifying = false
            _state.update { it.copy(screen = Screen.Boot, latest = it.latest.copy(error = null)) }
            store.restoreMissingToWebView()
            refreshCookieInfo()
            if (!store.hasSessionCookie()) {
                enterWebScreen(Screen.Login)
                return@launch
            }
            try {
                val cachedUser = container.preferences.getString("cached_username", null)?.takeIf { it.isNotBlank() }
                if (cachedUser != null) {
                    diagnostics.log("命中本地会话缓存($cachedUser),直拉首屏列表并后台复核")
                    _state.update { it.copy(username = cachedUser, screen = Screen.List) }
                    resumeAfterVerification?.also { resumeAfterVerification = null }?.invoke()
                    loadLatest(page = 0, pullRefresh = false)
                    reconcileSessionAndCategories(cachedUser)
                    return@launch
                }
                val username = repository.fetchCurrentUser()
                if (username == null) {
                    diagnostics.log("会话 Cookie 存在但服务端判定未登录,转入登录")
                    // 服务端都说无效了就别留着它:同步是合并式的,不主动清掉的话
                    // 每次启动都会拿这个死 token 白跑一次校验。
                    store.clearSessionCookie()
                    enterWebScreen(Screen.Login)
                } else {
                    diagnostics.log("登录态有效: $username")
                    container.preferences.edit { putString("cached_username", username) }
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

    /**
     * 首屏列表上屏后，后台静默探测分类树更新并复核会话有效性（Stale-While-Revalidate）。
     * 既保证冷启动首屏 0 阻塞秒开，又确保服务端分类变动或会话失效能够及时同步。
     */
    private fun reconcileSessionAndCategories(cachedUser: String) {
        syncCategoriesInBackground()
        viewModelScope.launch {
            try {
                val realUser = repository.fetchCurrentUser()
                when {
                    realUser == null -> {
                        diagnostics.log("后台复核发现会话已失效,清除缓存并转入登录")
                        container.preferences.edit { remove("cached_username") }
                        store.clearSessionCookie()
                        enterWebScreen(Screen.Login)
                    }
                    realUser != cachedUser -> {
                        diagnostics.log("后台复核用户名更新: $cachedUser -> $realUser")
                        container.preferences.edit { putString("cached_username", realUser) }
                        _state.update { it.copy(username = realUser) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: NeedsInteractiveVerification) {
                enterWebScreen(Screen.Verify, error.message.orEmpty())
            } catch (error: Exception) {
                diagnostics.log("后台会话复核跳过: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun syncCategoriesInBackground() {
        pageJobs["sync:categories"] = viewModelScope.launch {
            try {
                val updated = repository.refreshCategoriesFromServer()
                val known = repository.cachedCategoryMap()
                if (updated != null) {
                    val (freshList, rawJson) = updated
                    container.preferences.edit { putString("cached_categories_json", rawJson) }
                    _categories.value = CategoriesUiState(items = freshList, loaded = true)
                    diagnostics.log("分类表已从服务端同步更新(${known.size} 项)")
                }
                if (known.isNotEmpty()) {
                    _state.update { state ->
                        val patched = state.latest.items.map { item ->
                            val cat = item.topic.categoryId?.let { known[it] }
                            if (cat != item.category) item.copy(category = cat) else item
                        }
                        if (patched != state.latest.items) state.copy(latest = state.latest.copy(items = patched)) else state
                    }
                    _categoryTopics.update { map ->
                        map.mapValues { (_, listState) ->
                            val patched = listState.items.map { item ->
                                val cat = item.topic.categoryId?.let { known[it] }
                                if (cat != item.category) item.copy(category = cat) else item
                            }
                            if (patched != listState.items) listState.copy(items = patched) else listState
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.log("后台分类探测跳过: ${error.javaClass.simpleName}")
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

    /**
     * 由界面在验证页每秒轮询一次;完成条件满足后回到启动流程重新校验会话。
     *
     * 这里**必须**以"拿到会话 Cookie"为完成条件。早先 `Screen.Verify -> true` 是无条件成立,
     * 只要 WebView 打开的页面不是 CF 挑战页就立刻判定验证完成 —— 而邮件登录的确认页
     * 域名和标题都正常,于是刚打开就被当成验证通过,随即 boot() 发现没有 _t 又弹回登录页。
     */
    suspend fun pollVerification() {
        if (verifying) return
        if (!browser.isPageCleared()) return
        val cookies = store.syncFromWebView()
        val hasSession = cookies.containsKey(Config.SESSION_COOKIE)
        when (_state.value.screen) {
            Screen.Verify -> when {
                hasSession -> {
                    verifying = true
                    pendingEmailLoginToken = null
                    diagnostics.log("安全验证完成,重新校验会话")
                    boot()
                }
                // 页面不再被挑战但仍无会话:本来就没登录,回登录页收场,不要在这里干等。
                pendingEmailLoginToken == null -> {
                    diagnostics.log("安全验证通过但无会话,回到登录页")
                    enterWebScreen(Screen.Login)
                }
                // 被 CF 拦下的邮件登录:验证一过就重放,重放自己收尾。
                else -> {
                    val token = pendingEmailLoginToken ?: return
                    verifying = true
                    pendingEmailLoginToken = null
                    diagnostics.log("安全验证完成,重放邮件登录")
                    retryEmailLogin(token)
                }
            }
            Screen.Login -> if (hasSession) {
                verifying = true
                boot()
            }
            else -> Unit
        }
    }

    fun logout() {
        autoJob?.cancel()
        searchJob?.cancel()
        unreadJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        resumeAfterVerification = null
        pendingEmailLoginToken = null
        container.preferences.edit { remove("cached_username") }
        _details.value = emptyMap()
        _categories.value = CategoriesUiState()
        _categoryTopics.value = emptyMap()
        _search.value = SearchUiState()
        _profile.value = emptyMap()
        notificationCache.clear()
        _notificationFilter.value = NotificationFilter.All
        _composer.value = ComposerUiState()
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
            val result = repository.fetchLatest(page, awaitCategories = false)
            if (page == 0 && pageJobs["sync:categories"]?.isActive != true && repository.cachedCategoryList() == null) {
                syncCategoriesInBackground()
            }
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

package org.linuxdo.android.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.linuxdo.android.App
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import org.linuxdo.android.Config
import org.linuxdo.android.net.BrowserSession
import org.linuxdo.android.net.gecko.GeckoAuthBridge
import org.linuxdo.android.net.gecko.GeckoManager
import org.linuxdo.android.ui.design.IosTheme
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError

internal const val IN_APP_BOOTSTRAP_PATH = "/robots.txt"
private const val IN_APP_BOOTSTRAP_URL = "${Config.BASE_URL}$IN_APP_BOOTSTRAP_PATH"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserSheet(
    url: String,
    onDismiss: () -> Unit,
    initialTitle: String? = null,
    injectCss: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var title by remember(url) { mutableStateOf(initialTitle.orEmpty()) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var progress by remember(url) { mutableFloatStateOf(0f) }
    var canGoBack by remember(url) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val latestUrl by rememberUpdatedState(currentUrl)
    val geckoView = remember(url) {
        GeckoManager.createGeckoView(context, allowTouchInterceptOverride = true)
    }
    var geckoSessionRef by remember(url) { mutableStateOf<GeckoSession?>(null) }
    var bridgeRef by remember(url) { mutableStateOf<GeckoAuthBridge?>(null) }
    var bootstrapping by remember(url) { mutableStateOf(true) }
    var mainFrameError by remember(url) { mutableStateOf<String?>(null) }
    var isTimeout by remember(url) { mutableStateOf(false) }

    LaunchedEffect(currentUrl, progress, mainFrameError) {
        if (progress < 0.85f && mainFrameError == null) {
            delay(4_000)
            if (progress < 0.85f && mainFrameError == null) {
                isTimeout = true
            }
        } else {
            isTimeout = false
        }
    }

    // 若同源 bootstrap (/robots.txt) 因遭遇 CF 挑战耗时超过 1.2s，提前显示视图以便用户交互过盾
    LaunchedEffect(bootstrapping, mainFrameError) {
        if (bootstrapping && mainFrameError == null) {
            delay(1_200)
            if (bootstrapping) {
                geckoView.alpha = 1f
            }
        }
    }

    DisposableEffect(geckoView, url) {
        val uri = url.toUri()
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show()
            onDismiss()
            return@DisposableEffect onDispose {}
        }
        if (!isTrustedForumUrl(url)) {
            openExternal(context, url)
            onDismiss()
            return@DisposableEffect onDispose {}
        }

        val container = (context.applicationContext as? App)?.container
        val sessionStore = container?.sessionStore
        val browserSession = container?.browser
        var disposed = false
        var bootstrapDone = false

        fun isBootstrapLocation(raw: String?): Boolean {
            val parsed = raw?.toUri() ?: return false
            return parsed.host?.equals(Config.HOST, ignoreCase = true) == true &&
                parsed.path == IN_APP_BOOTSTRAP_PATH
        }

        fun syncClearanceBack(pageUrl: String) {
            if (disposed || !isTrustedForumUrl(pageUrl)) return
            scope.launch {
                val clearances = runCatching { bridgeRef?.readCfClearances() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: withContext(Dispatchers.IO) { GeckoManager.cfClearancesFromDb(context).toList() }
                if (!disposed) {
                    browserSession?.syncFreshClearanceFromInAppGecko(clearances, "内置浏览器")
                    val pageHost = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull().orEmpty()
                    org.linuxdo.android.net.NetDiagnostics.log(
                        "内置浏览器页面完成(Gecko) host=$pageHost title=${title.take(30)} Cookie清单: " +
                            (sessionStore?.let { runCatching { it.cookieInventory() }.getOrNull() } ?: "-"),
                    )
                }
            }
        }

        val session = GeckoManager.createChallengeSession(
            context = context,
            onLocationChanged = { navUrl ->
                if (!isBootstrapLocation(navUrl)) {
                    currentUrl = navUrl
                    geckoView.alpha = 1f
                }
            },
            onPageStarted = { startUrl ->
                if (!isBootstrapLocation(startUrl)) {
                    currentUrl = startUrl
                    geckoView.alpha = 1f
                }
                progress = 0.08f
                mainFrameError = null
                isTimeout = false
            },
            onProgressChanged = { newProgress ->
                if (!bootstrapping) {
                    progress = (newProgress / 100f).coerceIn(0.05f, 1f)
                }
            },
            onTitleChanged = { newTitle ->
                if (!bootstrapping && newTitle.isNotBlank()) {
                    title = newTitle
                }
            },
            onCanGoBackChanged = { back ->
                canGoBack = back && !bootstrapping
            },
            onPageFinished = { finishedUrl ->
                if (isBootstrapLocation(finishedUrl) && !bootstrapDone) {
                    return@createChallengeSession
                }
                bootstrapping = false
                geckoView.alpha = 1f
                currentUrl = finishedUrl
                progress = 1f
                syncClearanceBack(finishedUrl)
                injectCss?.let { css ->
                    val encodedCss = JSONObject.quote(css)
                    bridgeRef?.evaluateJavascript(
                        "(function(){var s=document.getElementById('linuxdo-native-websheet-style');if(!s){s=document.createElement('style');s.id='linuxdo-native-websheet-style';document.head.appendChild(s);}s.textContent=$encodedCss;})();",
                    )
                }
            },
            onPageError = { failedUrl, error ->
                bootstrapping = false
                geckoView.alpha = 1f
                if (!isBootstrapLocation(failedUrl)) currentUrl = failedUrl.ifBlank { currentUrl }
                mainFrameError = describeGeckoLoadError(error)
            },
            onExternalRequest = { targetUri ->
                val target = targetUri.toUri()
                val scheme = target.scheme?.lowercase().orEmpty()
                if (scheme !in setOf("http", "https")) {
                    openExternal(context, targetUri)
                    true
                } else {
                    false
                }
            },
            returnErrorPageHtml = false,
        )
        geckoView.alpha = 0f
        geckoView.setSession(session)
        geckoSessionRef = session

        val initJob = scope.launch {
            val bridge = runCatching {
                GeckoAuthBridge.create(
                    context = context,
                    session = session,
                    onReady = { readyUrl ->
                        if (disposed) return@create
                        if (!bootstrapDone && isBootstrapLocation(readyUrl)) {
                            bootstrapDone = true
                            scope.launch {
                                runCatching {
                                    val cookies = sessionStore?.syncFromWebView().orEmpty()
                                    val challenged = sessionStore?.cfClearancesForVerification()
                                        ?.filterTo(mutableSetOf()) { sessionStore.isClearanceChallenged(it) }
                                        .orEmpty()
                                    bridgeRef?.prepareInAppSessionCookies(cookies, challenged)
                                }
                                if (!disposed) {
                                    syncClearanceBack(readyUrl)
                                    bootstrapping = false
                                    geckoView.alpha = 1f
                                    session.loadUri(url)
                                }
                            }
                        } else {
                            syncClearanceBack(readyUrl)
                        }
                    },
                    onCallback = { _, _ -> },
                )
            }.getOrNull()
            if (disposed) {
                bridge?.close()
                return@launch
            }
            bridgeRef = bridge
            if (bridge != null) {
                session.loadUri(IN_APP_BOOTSTRAP_URL)
                // 兜底：若 /robots.txt 在 3.5 秒内未完成且未报错，直接跳转目标链接并展示页面（如挑战页）
                delay(3_500)
                if (!disposed && !bootstrapDone && mainFrameError == null) {
                    bootstrapDone = true
                    bootstrapping = false
                    geckoView.alpha = 1f
                    session.loadUri(url)
                }
            } else {
                bootstrapDone = true
                bootstrapping = false
                geckoView.alpha = 1f
                session.loadUri(url)
            }
        }

        onDispose {
            disposed = true
            initJob.cancel()
            runCatching { bridgeRef?.close() }
            bridgeRef = null
            runCatching { geckoView.releaseSession() }
            runCatching { session.stop() }
            runCatching { session.close() }
            geckoSessionRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler {
            if (canGoBack) {
                geckoSessionRef?.goBack()
            } else {
                onDismiss()
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(IosTheme.colors.card)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("关闭", color = IosTheme.colors.accent) }
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { latestUrl.toUri().host.orEmpty() },
                        maxLines = 1,
                        color = IosTheme.colors.label,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        currentUrl,
                        maxLines = 1,
                        color = IosTheme.colors.secondaryLabel,
                        style = IosTheme.type.caption,
                    )
                }
                TextButton(
                    onClick = { if (canGoBack) geckoSessionRef?.goBack() },
                    enabled = canGoBack,
                ) {
                    Text(
                        "后退",
                        color = if (canGoBack) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel,
                    )
                }
                TextButton(
                    onClick = {
                        mainFrameError = null
                        geckoSessionRef?.reload()
                    },
                ) { Text("刷新", color = IosTheme.colors.accent) }
                Box {
                    TextButton(onClick = { menuExpanded = true }) { Text("更多", color = IosTheme.colors.accent) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("复制链接") },
                            onClick = {
                                menuExpanded = false
                                clipboard.setText(AnnotatedString(currentUrl))
                                Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("系统浏览器打开") },
                            onClick = {
                                menuExpanded = false
                                openExternal(context, currentUrl)
                            },
                        )
                    }
                }
            }
            if (progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = IosTheme.colors.accent,
                    trackColor = Color.Transparent,
                )
            }
            if (isTimeout && mainFrameError == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFEF3C7))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "连接异常或加载较慢，建议在外部浏览器中打开",
                        color = Color(0xFF92400E),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { geckoSessionRef?.reload() }) {
                        Text("刷新", fontSize = 12.sp, color = Color(0xFFB45309))
                    }
                    TextButton(onClick = { openExternal(context, currentUrl) }) {
                        Text("外部打开", fontSize = 12.sp, color = Color(0xFFB45309))
                    }
                }
            }
            if (mainFrameError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(IosTheme.colors.groupedBackground)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "页面加载失败",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = IosTheme.colors.label,
                        )
                        Text(
                            text = mainFrameError.orEmpty(),
                            fontSize = 13.5.sp,
                            color = IosTheme.colors.secondaryLabel,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                mainFrameError = null
                                geckoSessionRef?.loadUri(currentUrl.ifBlank { url })
                            }) {
                                Text("重新加载", color = IosTheme.colors.accent)
                            }
                            TextButton(onClick = { openExternal(context, currentUrl) }) {
                                Text("系统浏览器打开", color = IosTheme.colors.accent)
                            }
                        }
                    }
                }
            } else {
                AndroidView(
                    factory = { geckoView },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

internal fun describeGeckoLoadError(error: WebRequestError): String = when (error.code) {
    WebRequestError.ERROR_UNKNOWN_HOST -> "域名解析失败，请检查网络连接 (${error.code})"
    WebRequestError.ERROR_NET_TIMEOUT -> "网络连接超时，请刷新重试 (${error.code})"
    WebRequestError.ERROR_OFFLINE -> "当前网络已断开 (${error.code})"
    WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "连接被意外中断，请刷新重试 (${error.code})"
    WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_SECURITY_BAD_CERT,
    WebRequestError.ERROR_BAD_HSTS_CERT -> "安全连接握手失败 (${error.code})"
    else -> "网络连接异常 (${error.category}/${error.code})"
}

fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show() }
}

val TRUSTED_FORUM_HOSTS = setOf(Config.HOST, "www.${Config.HOST}")

fun isTrustedForumHost(rawHost: String?): Boolean {
    val host = rawHost?.lowercase()?.trimEnd('.') ?: return false
    return host == Config.HOST || host.endsWith(".${Config.HOST}")
}

fun isTrustedForumUrl(rawUrl: String?): Boolean {
    val host = rawUrl?.toUri()?.host ?: return false
    return isTrustedForumHost(host)
}


package org.linuxdo.android.ui.screen

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.net.toUri
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
import org.linuxdo.android.ui.design.IosTheme

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
    var title by remember(url) { mutableStateOf(initialTitle.orEmpty()) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var progress by remember(url) { mutableFloatStateOf(0f) }
    var canGoBack by remember(url) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val latestUrl by rememberUpdatedState(currentUrl)
    val webView = remember(url) {
        WebView(context).also { view ->
            @Suppress("SetJavaScriptEnabled")
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadsImagesAutomatically = true
            view.settings.mediaPlaybackRequiresUserGesture = true
            view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            (context.applicationContext as? App)?.container?.userAgent?.let { userAgent ->
                view.settings.userAgentString = userAgent
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, false)
            }
        }
    }
    var mainFrameError by remember(url) { mutableStateOf<String?>(null) }
    var isTimeout by remember(url) { mutableStateOf(false) }

    LaunchedEffect(currentUrl, progress, mainFrameError) {
        if (progress < 0.85f && mainFrameError == null) {
            kotlinx.coroutines.delay(4_000)
            if (progress < 0.85f && mainFrameError == null) {
                isTimeout = true
            }
        } else {
            isTimeout = false
        }
    }

    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                mainFrameError = "网页渲染进程异常终止，请刷新重试"
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val destination = request.url
                val scheme = destination.scheme?.lowercase().orEmpty()
                val host = destination.host?.lowercase()?.trimEnd('.').orEmpty()
                if (scheme !in setOf("http", "https")) {
                    openExternal(context, destination.toString())
                    return true
                }
                if (host !in TRUSTED_FORUM_HOSTS) {
                    openExternal(context, destination.toString())
                    return true
                }
                currentUrl = destination.toString()
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    val desc = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "网络连接异常 (${error.errorCode})"
                    mainFrameError = desc
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame) {
                    val code = errorResponse.statusCode
                    if (code in 400..599) {
                        val reason = errorResponse.reasonPhrase?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                        mainFrameError = "服务返回异常: $reason ($code)"
                    }
                }
            }

            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                currentUrl = pageUrl.orEmpty()
                progress = 0f
                canGoBack = view.canGoBack()
                mainFrameError = null
                isTimeout = false
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                currentUrl = pageUrl.orEmpty()
                title = view.title.orEmpty()
                canGoBack = view.canGoBack()
                progress = 1f
                if (isTrustedForumUrl(pageUrl)) {
                    val sessionStore = (context.applicationContext as? App)?.container?.sessionStore
                    sessionStore?.syncFromWebView()
                    // 403 定位：子站跳转与过盾后，主站/子站的 CF Cookie 作用域是否被改写
                    val pageHost = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull().orEmpty()
                    org.linuxdo.android.net.NetDiagnostics.log(
                        "内置浏览器页面完成 host=$pageHost title=${view.title.orEmpty().take(30)} Cookie清单: " +
                            (sessionStore?.let { runCatching { it.cookieInventory() }.getOrNull() } ?: "-"),
                    )
                }
                injectCss?.let { css ->
                    val encodedCss = JSONObject.quote(css)
                    view.evaluateJavascript(
                        "(function(){var s=document.getElementById('linuxdo-native-websheet-style');if(!s){s=document.createElement('style');s.id='linuxdo-native-websheet-style';document.head.appendChild(s);}s.textContent=$encodedCss;})();",
                        null,
                    )
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = (newProgress / 100f).coerceIn(0f, 1f)
                title = view.title.orEmpty().ifBlank { title }
                canGoBack = view.canGoBack()
            }
        }
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(webView, url) {
        val uri = url.toUri()
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
            if (host !in TRUSTED_FORUM_HOSTS) {
                openExternal(context, url)
                onDismiss()
            } else {
                webView.loadUrl(url)
            }
        } else {
            Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show()
            onDismiss()
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
                webView.goBack()
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
                    onClick = { if (canGoBack) webView.goBack() },
                    enabled = canGoBack,
                ) {
                    Text(
                        "后退",
                        color = if (canGoBack) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel,
                    )
                }
                TextButton(onClick = { webView.reload() }) { Text("刷新", color = IosTheme.colors.accent) }
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
                    TextButton(onClick = { webView.reload() }) {
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
                                webView.reload()
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
                    factory = { webView },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
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

fun isTrustedForumUrl(rawUrl: String?): Boolean {
    val host = rawUrl?.toUri()?.host?.lowercase()?.trimEnd('.') ?: return false
    return host in TRUSTED_FORUM_HOSTS
}


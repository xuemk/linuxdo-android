package org.linuxdo.android.net.gecko

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import android.os.Handler
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import org.linuxdo.android.App
import org.linuxdo.android.Config
import org.linuxdo.android.net.DohPresets
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.io.File
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GeckoManager {
    private const val TAG = "GeckoManager"
    private var runtime: GeckoRuntime? = null

    /**
     * 获取或初始化全局单例 GeckoRuntime，使用 Gecko 自身的 DoH 与 ECH。
     */
    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        check(Looper.myLooper() == Looper.getMainLooper()) { "只能在主线程初始化 GeckoRuntime" }
        val container = (context.applicationContext as? App)?.container
        val store = container?.sessionStore
        val dohUri = store?.getDohServerUrl()?.takeIf { it.isNotBlank() } ?: DohPresets.DEFAULT.url
        val useDoh = store?.isDohEnabled() != false &&
            (container?.networkMonitor?.detect()?.shouldPauseDoh != true || store?.isDohForceOverProxyEnabled() == true)
        val trrMode = if (useDoh) GeckoRuntimeSettings.TRR_MODE_ONLY else GeckoRuntimeSettings.TRR_MODE_DISABLED
        runtime?.let {
            it.settings.setTrustedRecursiveResolverUri(dohUri)
            it.settings.setTrustedRecursiveResolverMode(trrMode)
            return it
        }

        val configFile = File(context.filesDir, "geckoview-config.yaml")
        // prefs 的键是原始 Gecko 参数名；类型由 YAML 值决定，不追加类型后缀。
        val configContent = """
            prefs:
              network.proxy.type: 5
              security.tls.version.max: 4
              security.tls.version.min: 3
              network.dns.echconfig.enabled: true
              network.dns.http3_echconfig.enabled: true
              network.dns.use_https_rr_as_altsvc: true
        """.trimIndent()

        configFile.writeText(configContent, Charsets.UTF_8)

        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(configFile.absolutePath)
            .trustedRecursiveResolverUri(dohUri)
            .trustedRecursiveResolverMode(trrMode)
            .javaScriptEnabled(true)
            .consoleOutput(false)
            .debugLogging(false)
            .build()

        val rt = GeckoRuntime.create(context.applicationContext, settings)
        runtime = rt
        Log.d(TAG, "GeckoRuntime 已启用 ECH，TRR mode=$trrMode，DoH host=${Uri.parse(dohUri).host}")
        return rt
    }

    /** 验证视图共用 TextureView，并兼容宿主转发的非标准触摸序列。 */
    fun createGeckoView(context: Context): GeckoView {
        return object : GeckoView(context) {
            private var gestureDownTime: Long? = null
            private var touchCorrectionLogged = false

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    gestureDownTime = event.downTime
                }
                val expectedDownTime = gestureDownTime
                // Gecko 135 会丢弃 downTime 与 DOWN 不同的 MOVE/UP，导致页面收不到完整点击。
                // 仅修正已开始的触摸序列；鼠标和正常事件继续原样交给 Gecko。
                val corrected = if (expectedDownTime != null && event.downTime != expectedDownTime &&
                    event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE
                ) {
                    if (!touchCorrectionLogged) {
                        touchCorrectionLogged = true
                        val message = "Gecko 触摸序列校正: action=${event.actionMasked}, " +
                            "downTimeDelta=${event.downTime - expectedDownTime}, " +
                            "source=${event.source}, tool=${event.getToolType(0)}"
                        Log.w(TAG, message)
                        (context.applicationContext as? App)?.container?.diagnostics?.log(message)
                    }
                    copyTouchEventWithDownTime(event, expectedDownTime)
                } else null
                return try {
                    super.onTouchEvent(corrected ?: event)
                } finally {
                    corrected?.recycle()
                    if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) gestureDownTime = null
                }
            }

            override fun onDetachedFromWindow() {
                gestureDownTime = null
                super.onDetachedFromWindow()
            }
        }.apply {
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun copyTouchEventWithDownTime(event: MotionEvent, downTime: Long): MotionEvent {
        val properties = Array(event.pointerCount) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
        }
        val coordinates = Array(event.pointerCount) { MotionEvent.PointerCoords() }
        val screenOffsetX = event.rawX - event.x
        val screenOffsetY = event.rawY - event.y
        fun readCoordinates(historyIndex: Int) {
            coordinates.forEachIndexed { index, coords ->
                if (historyIndex < event.historySize) {
                    event.getHistoricalPointerCoords(index, historyIndex, coords)
                } else {
                    event.getPointerCoords(index, coords)
                }
                coords.x += screenOffsetX
                coords.y += screenOffsetY
            }
        }
        readCoordinates(0)
        val corrected = MotionEvent.obtain(
            downTime,
            if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime,
            event.action, event.pointerCount, properties, coordinates,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags, event.source, event.flags,
        )
        for (historyIndex in 1..event.historySize) {
            readCoordinates(historyIndex)
            val eventTime = if (historyIndex < event.historySize) {
                event.getHistoricalEventTime(historyIndex)
            } else event.eventTime
            corrected.addBatch(eventTime, coordinates, event.metaState)
        }
        // 同时保留局部/屏幕坐标，Gecko 会通过 rawX - x 更新弹窗内的屏幕原点。
        corrected.offsetLocation(-screenOffsetX, -screenOffsetY)
        return corrected
    }

    /**
     * 创建专门用于过 Cloudflare 挑战或登录的 GeckoSession
     */
    fun createChallengeSession(
        context: Context,
        onLocationChanged: (url: String) -> Unit = {},
        onPageFinished: (url: String) -> Unit = {},
        onCanGoBackChanged: (canGoBack: Boolean) -> Unit = {},
        onPageError: (String, WebRequestError) -> Unit = { _, _ -> },
    ): GeckoSession {
        val rt = getOrCreateRuntime(context)
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false) // 非无痕模式，确保 Cookie 落地到 cookies.sqlite
            .build()

        val session = GeckoSession(sessionSettings)
        var currentUrl = ""
        var pageLoadFailed = false
        var contentPaintLogged = false

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                Log.d(TAG, "Gecko onLoadRequest: ${diagnosticUrl(request.uri)}")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                currentUrl = url.orEmpty()
                if (!currentUrl.startsWith("https://") && !currentUrl.startsWith("http://")) return
                Log.d(TAG, "Gecko onLocationChange: ${diagnosticUrl(currentUrl)}")
                onLocationChanged(currentUrl)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                onCanGoBackChanged(canGoBack)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                pageLoadFailed = true
                Log.e(TAG, "Gecko onLoadError: uri=${diagnosticUrl(uri)}, category=${error.category}, code=${error.code}")
                onPageError(uri.orEmpty(), error)
                // 返回 null 会直接中止导航，首次加载失败时只留下 about:blank。
                return GeckoResult.fromValue(createErrorPage(uri, error))
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                currentUrl = url
                contentPaintLogged = false
                if (!url.startsWith("https://") && !url.startsWith("http://")) return
                pageLoadFailed = false
                Log.d(TAG, "Gecko onPageStart: ${diagnosticUrl(url)}")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                Log.d(TAG, "Gecko onPageStop, success=$success")
                if (success && !pageLoadFailed && (currentUrl.startsWith("https://") || currentUrl.startsWith("http://"))) {
                    onPageFinished(currentUrl)
                }
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(session: GeckoSession) {
                if (!contentPaintLogged) {
                    contentPaintLogged = true
                    Log.d(TAG, "Gecko 首屏内容已绘制")
                }
            }
        }

        session.open(rt)
        return session
    }

    internal fun diagnosticUrl(value: String?): String {
        val uri = Uri.parse(value.orEmpty())
        if (uri.scheme != "https" && uri.scheme != "http") return "${uri.scheme.orEmpty()}:"
        val path = uri.path.orEmpty().let {
            if (it.startsWith("/session/email-login/")) "/session/email-login/[token]" else it
        }
        return "${uri.scheme}://${uri.host}$path"
    }

    private fun createErrorPage(uri: String?, error: WebRequestError): String {
        val retryUrl = uri?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: "${Config.BASE_URL}/challenge"
        val message = when (error.code) {
            WebRequestError.ERROR_UNKNOWN_HOST -> "无法解析验证服务地址，请检查网络设置后重试。"
            WebRequestError.ERROR_NET_TIMEOUT -> "连接验证服务超时，请稍后重试。"
            WebRequestError.ERROR_OFFLINE -> "网络已断开，请连接网络后重试。"
            WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_SECURITY_BAD_CERT,
            WebRequestError.ERROR_BAD_HSTS_CERT -> "无法建立安全连接，请检查设备时间与网络后重试。"
            else -> "暂时无法连接验证服务，请检查网络后重试。"
        }
        val html = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="color-scheme" content="light dark">
            <style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:sans-serif}
            main{padding:28px;text-align:center}h2{font-size:20px}p{line-height:1.6;opacity:.7}
            a{display:inline-block;padding:12px 24px;border-radius:10px;background:#1677ff;color:white;text-decoration:none}</style>
            </head><body><main><h2>验证页面加载失败</h2><p>$message</p>
            <a href="${TextUtils.htmlEncode(retryUrl)}">重新加载</a></main></body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," +
            Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /**
     * 查找 GeckoView 的 cookies.sqlite 数据库文件
     */
    fun findCookiesDbFile(context: Context): File? {
        val candidateDirs = listOfNotNull(
            File(context.filesDir, "mozilla"),
            context.filesDir.parentFile?.resolve("files/mozilla"),
            File(context.dataDir, "files/mozilla")
        )
        for (dir in candidateDirs) {
            if (dir.exists()) {
                val dbFile = dir.walkTopDown().firstOrNull { it.name == "cookies.sqlite" }
                if (dbFile != null && dbFile.exists()) return dbFile
            }
        }
        return null
    }

    /**
     * 清理 GeckoView 数据库中的旧 cf_clearance，避免二次验证时命中旧值导致误判
     */
    fun clearCfClearanceFromDb(context: Context) {
        val dbFile = findCookiesDbFile(context) ?: return
        try {
            val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags)
            db.use {
                it.execSQL("DELETE FROM moz_cookies WHERE host LIKE '%linux.do%' AND name = 'cf_clearance'")
                Log.d(TAG, "已从 GeckoView cookies.sqlite 中清除旧 cf_clearance")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "清理 GeckoView cf_clearance 异常: ${e.message}")
        }
    }

    /**
     * 从 GeckoView 的 moz_cookies SQLite 数据库中提取 linux.do 的凭据 (如 cf_clearance, _t, _forum_session)
     */
    fun extractCookiesFromDb(context: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val dbFile = findCookiesDbFile(context) ?: return result

        try {
            val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags)
            db.use {
                val cursor = it.rawQuery(
                    "SELECT name, value FROM moz_cookies WHERE host LIKE '%linux.do%'",
                    null
                )
                cursor.use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    val valIdx = c.getColumnIndex("value")
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && valIdx >= 0) {
                            val name = c.getString(nameIdx)
                            val value = c.getString(valIdx)
                            if (!name.isNullOrBlank() && !value.isNullOrBlank()) {
                                result[name] = value
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读取 GeckoView moz_cookies 数据库异常: ${e.message}")
        }
        return result
    }
}

internal enum class CfDiagnosticMode(val title: String) {
    FLOW("CF 流程模拟"),
    ONLINE("CF 直连加载测试"),
}

internal data class CfDiagnosticStatus(
    val message: String,
    val terminal: Boolean = false,
    val success: Boolean = false,
)

internal object CfDiagnosticPage {
    fun status(mode: CfDiagnosticMode, event: String): CfDiagnosticStatus? = when (event) {
        "ready" -> CfDiagnosticStatus(if (mode == CfDiagnosticMode.FLOW) "模拟页已加载，请勾选方框" else "测试页已加载，正在连接 Cloudflare")
        "script-ready" -> if (mode == CfDiagnosticMode.ONLINE) CfDiagnosticStatus("Cloudflare 脚本已加载，正在初始化测试控件") else null
        "interactive" -> CfDiagnosticStatus("交互界面已就绪，等待你的操作")
        "success" -> CfDiagnosticStatus(
            if (mode == CfDiagnosticMode.FLOW) "流程模拟通过：点击、回调与模拟重试完成" else "CF 资源加载及测试控件客户端回调通过；不代表 linux.do 已过盾",
            terminal = true, success = true,
        )
        "simulation-error" -> if (mode == CfDiagnosticMode.FLOW) CfDiagnosticStatus("已收到模拟失败，请点重试验证恢复流程", terminal = true) else null
        "script-error" -> CfDiagnosticStatus("Cloudflare 脚本加载失败，请检查当前网络后重试", terminal = true)
        "page-error" -> CfDiagnosticStatus("测试控件初始化失败，请重试并查看日志", terminal = true)
        "expired", "timeout" -> CfDiagnosticStatus("测试控件已过期或超时，请点重试", terminal = true)
        else -> if (event.matches(Regex("error:[a-zA-Z0-9_-]{1,24}"))) {
            CfDiagnosticStatus("CF 测试控件报错：${event.substringAfter(':')}，请点重试", terminal = true)
        } else null
    }

    fun html(mode: CfDiagnosticMode, dark: Boolean, channel: String): String {
        require(channel.matches(Regex("[a-zA-Z0-9-]+")))
        val online = mode == CfDiagnosticMode.ONLINE
        val controls = if (online) "<div id=widget></div>" else """
            <label><input id="verify" type="checkbox"> 我是真实访客（模拟）</label>
            <button id="fail" type="button">模拟失败</button>
        """.trimIndent()
        val script = if (online) """
            window.cfDiagnosticReady = function() {
              report('script-ready', 'Cloudflare 脚本已加载，正在初始化控件');
              try {
                turnstile.render('#widget', {
                  sitekey: '3x00000000000000000000FF',
                  theme: '${if (dark) "dark" else "light"}',
                  size: 'flexible', retry: 'never',
                  'refresh-expired': 'manual', 'refresh-timeout': 'manual',
                  'before-interactive-callback': function() { report('interactive', '请完成下面的交互测试'); },
                  callback: function(token) { report(token ? 'success' : 'error:empty-response', token ? '测试控件回调成功；这不是 linux.do 的认证结果' : '未收到测试响应'); },
                  'error-callback': function(code) { report('error:' + String(code), '控件报错：' + String(code)); return true; },
                  'expired-callback': function() { report('expired', '测试已过期，请点上方重试'); },
                  'timeout-callback': function() { report('timeout', '交互超时，请点上方重试'); }
                });
              } catch (error) { report('page-error', '测试控件初始化失败'); }
            };
            var api = document.createElement('script');
            api.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?onload=cfDiagnosticReady&render=explicit';
            api.async = true;
            api.onerror = function() { report('script-error', 'Cloudflare 脚本加载失败'); };
            document.head.appendChild(api);
        """.trimIndent() else """
            document.getElementById('verify').onchange = function() {
              if (!this.checked) return;
              this.disabled = true;
              document.getElementById('fail').disabled = true;
              report('interactive', '已收到点击，正在模拟重试');
              setTimeout(function() { report('success', '模拟请求重试成功（HTTP 200）'); }, 200);
            };
            document.getElementById('fail').onclick = function() {
              this.disabled = true;
              document.getElementById('verify').disabled = true;
              report('simulation-error', '已模拟验证失败，请点上方重试');
            };
        """.trimIndent()
        return """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>CF 测试</title><style>
            html,body{margin:0;min-height:100%;background:${if (dark) "#1c1c1e" else "#fff"};color:${if (dark) "#f5f5f7" else "#1c1c1e"};font:16px sans-serif}
            main{padding:24px 16px;text-align:center}h2{font-size:20px}p{line-height:1.6}
            label{display:flex;align-items:center;justify-content:center;gap:12px;padding:24px 0}
            input{width:44px;height:44px}button{padding:12px 24px;margin:12px;border-radius:8px}
            #widget{max-width:100%;margin:24px auto}#result{font-weight:600;overflow-wrap:anywhere}
            </style></head><body><main><h2>${mode.title}</h2>
            <p>${if (online) "使用 Cloudflare 官方交互测试密钥。请关闭手机及卓易通宿主的代理后测试。" else "本地模拟 HTTP 403 / cf-mitigated: challenge，不连接网站。"}</p>
            $controls<p id="result">准备测试…</p>
            <p>${if (online) "通过仅说明当前网络下测试控件可用，不能保证 linux.do 的真实 CF 挑战放行。" else "通过仅说明本地测试页的触摸、回调与模拟重试正常。"}</p>
            </main><script>
            function report(event, message) {
              document.getElementById('result').textContent = message;
              document.title = 'linuxdo-cf-test:$channel:' + event;
            }
            report('ready', '${if (online) "正在连接 Cloudflare…" else "请勾选方框，或点击模拟失败"}');
            $script
            </script></body></html>
        """.trimIndent()
    }
}

/** Turnstile 仅支持 HTTP(S) 页面；服务只在测试窗口存续时监听本机随机端口。 */
internal class CfDiagnosticPageServer(html: String, channel: String) : Closeable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val path = "/$channel"
    private val content = html.toByteArray(Charsets.UTF_8)
    @Volatile private var activeClient: Socket? = null
    val url: String = "http://127.0.0.1:${listener.localPort}$path"

    fun serve() {
        while (!listener.isClosed) {
            val client = try { listener.accept() } catch (error: Exception) {
                if (listener.isClosed) return else throw error
            }
            activeClient = client
            client.use {
                if (listener.isClosed) return
                try {
                    it.soTimeout = 2_000
                    val input = it.getInputStream()
                    val request = StringBuilder()
                    while (request.length < 8_192 && !request.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) break
                        request.append(next.toChar())
                    }
                    val firstLine = request.lineSequence().firstOrNull().orEmpty().trimEnd('\r')
                    val allowed = request.endsWith("\r\n\r\n") &&
                        (firstLine == "GET $path HTTP/1.1" || firstLine == "GET $path HTTP/1.0")
                    val body = if (allowed) content else "Not found".toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 ${if (allowed) "200 OK" else "404 Not Found"}\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                        "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(headers.toByteArray(Charsets.UTF_8)); write(body); flush() }
                } catch (_: Exception) {
                    // 无效或中断的请求不影响后续手工重试。
                }
            }
            activeClient = null
        }
    }

    override fun close() {
        runCatching { listener.close() }
        runCatching { activeClient?.close() }
    }
}

internal class CfDiagnosticSession(
    private val context: Context,
    private val mode: CfDiagnosticMode,
    private val dark: Boolean,
    private val onStatus: (CfDiagnosticStatus) -> Unit,
) {
    val view = GeckoManager.createGeckoView(context)
    private val channel = UUID.randomUUID().toString()
    private val contextId = "cf-diagnostic-$channel"
    private val prefix = "linuxdo-cf-test:$channel:"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var session: GeckoSession? = null
    private var runtime: GeckoRuntime? = null
    @Volatile private var server: CfDiagnosticPageServer? = null
    @Volatile private var closed = false
    private var terminal = false
    private var started = false
    private var environment = "本地流程模拟"
    private val timeout = Runnable { publish(CfDiagnosticStatus("测试页面或 CF 资源加载超时，请点重试；这不表示验证通过", terminal = true)) }

    private fun publish(status: CfDiagnosticStatus) {
        if (closed || terminal) return
        terminal = status.terminal
        if (terminal) handler.removeCallbacks(timeout)
        onStatus(status.copy(message = "$environment\n${status.message}"))
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed) return
        started = true
        if (mode == CfDiagnosticMode.ONLINE) {
            val network = (context.applicationContext as? App)?.container?.networkMonitor?.detect()
            environment = if (network?.shouldPauseDoh == true) "检测到 VPN/系统代理：本次不计为无梯直连测试" else "未检测到 VPN/系统代理；请同时确认宿主代理已关闭"
        } else if (!org.linuxdo.android.net.ChallengeDetector.isChallenge(403, "challenge", "")) {
            publish(CfDiagnosticStatus("模拟挑战未被识别", terminal = true))
            return
        }
        publish(CfDiagnosticStatus("正在准备独立测试窗口…"))
        handler.postDelayed(timeout, 30_000L)
        scope.launch(Dispatchers.IO) {
            try {
                CfDiagnosticPageServer(CfDiagnosticPage.html(mode, dark, channel), channel).use { local ->
                    server = local
                    if (closed) return@launch
                    withContext(Dispatchers.Main) {
                        if (closed) return@withContext
                        val engine = GeckoManager.getOrCreateRuntime(context)
                        runtime = engine
                        val browser = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(contextId).build())
                        session = browser
                        browser.navigationDelegate = object : GeckoSession.NavigationDelegate {
                            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                                return GeckoResult.fromValue(if (request.uri == local.url || request.uri == "about:blank") AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                            }

                            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                                publish(CfDiagnosticStatus("本地测试页加载失败（${error.category}/${error.code}）；尚未验证 CF 网络，请重试", terminal = true))
                                return null
                            }
                        }
                        browser.contentDelegate = object : GeckoSession.ContentDelegate {
                            override fun onTitleChange(session: GeckoSession, title: String?) {
                                if (closed || terminal || title?.startsWith(prefix) != true) return
                                val event = title.removePrefix(prefix)
                                val status = CfDiagnosticPage.status(mode, event) ?: return
                                if (event == "interactive" || (event == "ready" && mode == CfDiagnosticMode.FLOW)) handler.removeCallbacks(timeout)
                                if (event == "script-ready") {
                                    handler.removeCallbacks(timeout)
                                    handler.postDelayed(timeout, 60_000L)
                                }
                                if (event == "success" && mode == CfDiagnosticMode.FLOW &&
                                    org.linuxdo.android.net.ChallengeDetector.isChallenge(200, null, "{}")
                                ) {
                                    publish(CfDiagnosticStatus("模拟重试结果仍被识别为挑战", terminal = true))
                                } else publish(status)
                            }
                        }
                        browser.open(engine)
                        view.setSession(browser)
                        view.requestFocus()
                        browser.loadUri(local.url)
                    }
                    if (!closed) local.serve()
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    publish(CfDiagnosticStatus("测试组件初始化失败，请关闭后重试", terminal = true))
                }
            }
        }
    }

    fun close(reportCancellation: Boolean = true) {
        if (closed) return
        if (reportCancellation && !terminal) publish(CfDiagnosticStatus("测试已取消，未取得完成结果", terminal = true))
        closed = true
        handler.removeCallbacks(timeout)
        server?.close()
        scope.cancel()
        runCatching { view.releaseSession() }
        runCatching { session?.stop() }
        runCatching { session?.close() }
        session = null
        runCatching { runtime?.storageController?.clearDataForSessionContext(contextId) }
    }
}

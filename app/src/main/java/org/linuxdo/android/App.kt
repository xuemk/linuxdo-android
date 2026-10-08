package org.linuxdo.android

import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import java.io.File
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.net.BrowserSession
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.net.OkHttpTransport
import org.linuxdo.android.net.Transport
import org.linuxdo.android.net.WebViewFetchTransport
import org.linuxdo.android.net.buildSharedHttpClient

/** 实现 ImageLoaderFactory,Coil 的 AsyncImage 就会自动用上带凭据的共享客户端。 */
class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}

class AppContainer(context: Context) {
    val preferences = context.getSharedPreferences("display_preferences", Context.MODE_PRIVATE)
    val diagnostics = Diagnostics(File(context.filesDir, "diagnostics.log"))
    val sessionStore = SessionStore(context)
    val userAgent: String = resolveUserAgent(context, sessionStore)
    val browser = BrowserSession(context, userAgent, sessionStore, diagnostics)

    /** JSON 请求与图片共用,保证 UA 与 Cookie 一致。 */
    private val httpClient = buildSharedHttpClient(sessionStore, userAgent)

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(httpClient)
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache"))
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

    private val okHttpTransport = OkHttpTransport(sessionStore, browser, httpClient, diagnostics)
    private val webViewTransport = WebViewFetchTransport(browser, diagnostics)

    fun transport(path: NetworkPath): Transport = when (path) {
        NetworkPath.OKHTTP -> okHttpTransport
        NetworkPath.WEBVIEW -> webViewTransport
    }

    private companion object {
        /**
         * 全应用共用同一个 UA。首次运行时由系统 WebView 的默认 UA 规整成 Chrome 移动端的形态
         * (去掉 "; wv" 与 "Version/4.0"),并持久化——WebView 升级也不改变它,
         * 因为 cf_clearance 与 UA 绑定,UA 漂移会触发重新挑战。
         */
        fun resolveUserAgent(context: Context, store: SessionStore): String {
            store.userAgentOrNull()?.let { return it }
            val normalized = normalize(WebSettings.getDefaultUserAgent(context))
            store.saveUserAgent(normalized)
            return normalized
        }

        fun normalize(raw: String): String = raw
            .replace(Regex("\\(Linux; Android [^)]*\\)"), "(Linux; Android 10; K)")
            .replace("; wv", "")
            .replace(" Version/4.0", "")
    }
}

package org.linuxdo.android.net

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.linuxdo.android.Config
import org.linuxdo.android.data.SessionStore

/**
 * 全应用共用的 HTTP 客户端。
 *
 * 图片(Coil)和 JSON 请求必须走同一个客户端:cf_clearance 与 UA 绑定,
 * 图片请求若用了 Coil 默认客户端(不同 UA、没有 Cookie),会被 Cloudflare 直接拦掉,
 * 结果就是列表里头像全空。
 */
fun buildSharedHttpClient(store: SessionStore, userAgent: String): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor(SessionHeaderInterceptor(store, userAgent))
        .build()

/** 给站内请求统一注入固定 UA 与会话 Cookie。 */
private class SessionHeaderInterceptor(
    private val store: SessionStore,
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // 重定向后的每一跳都重新检查,避免站内图片跳转到 CDN 时携带论坛 Cookie。
        if (original.url.host != Config.HOST || !original.url.isHttps) {
            return chain.proceed(original.newBuilder().removeHeader("Cookie").removeHeader("X-CSRF-Token").build())
        }

        val builder = original.newBuilder().header("User-Agent", userAgent)
        // 这里运行在 OkHttp 的 IO 线程,不能调 syncFromWebView() —— 它要在主线程访问
        // CookieManager。只读加密存储里的快照,快照由 Transport 在每次 JSON 请求前刷新。
        val cookies = store.storedCookies()
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", SessionStore.toCookieHeader(cookies))
        }
        return chain.proceed(builder.build())
    }
}

package org.linuxdo.android.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.linuxdo.android.Config
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.SessionStore

/** 路径 A:OkHttp + 与 WebView 同步的 Cookie + 固定 UA;遇到挑战时静默重验证并重试一次。 */
class OkHttpTransport(
    private val store: SessionStore,
    private val browser: BrowserSession,
    private val client: OkHttpClient,
    private val diagnostics: Diagnostics,
) : Transport {
    override val name: String = NetworkPath.OKHTTP.label

    override suspend fun get(path: String): TransportResult {
        var result = execute(path)
        if (result.challenged && revalidate(path)) {
            result = execute(path)
        }
        return result
    }

    override suspend fun write(path: String, method: String, body: String?, csrf: String): TransportResult =
        execute(path, csrf, method, body)

    override suspend fun revalidate(reason: String): Boolean {
        diagnostics.recordSilentRevalidation()
        return browser.clearChallenge("A: $reason")
    }

    override suspend fun upload(
        path: String,
        csrf: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        fields: Map<String, String>,
    ): TransportResult {
        withContext(Dispatchers.Main) { store.syncFromWebView() }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (key, value) -> addFormDataPart(key, value) }
            // Discourse 收的是 files[] 这个字段名。
            addFormDataPart("files[]", fileName, bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
        }.build()
        val request = Request.Builder()
            .url(Config.BASE_URL + path)
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-CSRF-Token", csrf)
            .post(body)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val setCookies = Cookie.parseAll(response.request.url, response.headers)
                    val text = response.body?.string().orEmpty()
                    val mitigated = response.header("cf-mitigated")
                    withContext(Dispatchers.Main) { store.applyResponseCookies(setCookies) }
                    TransportResult(
                        status = response.code,
                        body = text,
                        challenged = ChallengeDetector.isChallenge(response.code, mitigated, text),
                    )
                }
            }
        } catch (error: IOException) {
            TransportResult(0, error.message.orEmpty(), false)
        }
    }

    private suspend fun execute(path: String, csrf: String? = null, method: String = "GET", body: String? = null): TransportResult {
        // 先把 WebView 的现场 Cookie 同步进加密存储,共享客户端的 Interceptor 会读这份快照。
        withContext(Dispatchers.Main) { store.syncFromWebView() }
        val request = Request.Builder()
            .url(Config.BASE_URL + path)
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (csrf != null) method(method, (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("X-CSRF-Token", csrf) }
            .build()

        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val setCookies = Cookie.parseAll(response.request.url, response.headers)
                    val body = response.body?.string().orEmpty()
                    val mitigated = response.header("cf-mitigated")
                    withContext(Dispatchers.Main) { store.applyResponseCookies(setCookies) }
                    TransportResult(
                        status = response.code,
                        body = body,
                        challenged = ChallengeDetector.isChallenge(response.code, mitigated, body),
                    )
                }
            }
        } catch (error: IOException) {
            TransportResult(0, error.message.orEmpty(), false)
        }
    }
}

package org.linuxdo.android.net

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.linuxdo.android.Config
import org.linuxdo.android.data.Diagnostics
import org.linuxdo.android.data.SessionStore

/** 路径 A:OkHttp + 与 WebView 同步的 Cookie + 固定 UA;遇到挑战时静默重验证并重试一次。 */
class OkHttpTransport(
    private val store: SessionStore,
    private val browser: BrowserSession,
    private var client: OkHttpClient,
    private val diagnostics: Diagnostics,
    @Volatile private var clientLabel: String = "-",
) : Transport {
    override val name: String = NetworkPath.OKHTTP.label

    /** 动态更新底层 OkHttpClient（DoH 节点切换或开关变更后调用）；[label] 记代次与链路，供失败诊断对照。 */
    fun updateClient(newClient: OkHttpClient, label: String = clientLabel) {
        this.client = newClient
        this.clientLabel = label
    }

    override suspend fun get(path: String, present: Boolean): TransportResult {
        return execute(path, present = present)
    }

    override suspend fun write(
        path: String,
        method: String,
        body: String?,
        csrf: String,
        contentType: String,
        present: Boolean,
    ): TransportResult = execute(path, csrf, method, body, contentType, present = present)

    override suspend fun revalidate(reason: String, failedClearanceFp: String): Boolean {
        diagnostics.recordSilentRevalidation()
        return browser.clearChallenge("A: $reason", failedClearanceFp)
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
            .header("Discourse-Present", "true")
            .header("Origin", Config.BASE_URL)
            .header("Referer", "${Config.BASE_URL}/")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("X-CSRF-Token", csrf)
            .post(body)
            .build()
        return runRequest(request)
    }

    private suspend fun execute(
        path: String,
        csrf: String? = null,
        method: String = "GET",
        body: String? = null,
        contentType: String = Transport.JSON_CONTENT_TYPE,
        present: Boolean = true,
    ): TransportResult {
        // 先把 WebView 的现场 Cookie 同步进加密存储,共享客户端的 Interceptor 会读这份快照。
        withContext(Dispatchers.Main) { store.syncFromWebView() }
        val targetUrl = if (path.startsWith("http://") || path.startsWith("https://")) path else Config.BASE_URL + path
        val isConnect = targetUrl.contains("connect.linux.do")
        val acceptHeader = if (path.startsWith("/raw/")) {
            "text/plain"
        } else if (isConnect) {
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        } else if (path.endsWith(".json") || !path.startsWith("http")) {
            "application/json"
        } else {
            "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8"
        }
        val request = Request.Builder()
            .url(targetUrl)
            .header("Accept", acceptHeader)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .apply {
                if (!isConnect) {
                    header("X-Requested-With", "XMLHttpRequest")
                    if (path.startsWith("/topics/timings")) {
                        // 与官方前端 screen-track 一致：后台心跳不刷新在线状态、不写服务端日志
                        header("Discourse-Background", "true")
                        header("X-SILENCE-LOGGER", "true")
                    } else if (present) {
                        header("Discourse-Present", "true")
                    }
                }
                if (method != "GET") {
                    method(method, (body ?: "").toRequestBody(contentType.toMediaType()))
                    header("Origin", Config.BASE_URL)
                    header("Referer", "${Config.BASE_URL}/")
                    header("Sec-Fetch-Site", "same-origin")
                    header("Sec-Fetch-Mode", "cors")
                    header("Sec-Fetch-Dest", "empty")
                }
                if (csrf != null) {
                    header("X-CSRF-Token", csrf)
                }
            }
            .build()

        return runRequest(request)
    }

    private suspend fun runRequest(request: Request): TransportResult {
        // 在发起前取标签：失败时记的是这次请求实际走的代次，而不是之后被回收换上的新代次。
        val label = clientLabel
        return try {
            sendRequest(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // status=0 会被上层统一换成"连接超时"，真实异常只在这里还看得到。
            val cause = error.cause?.let { " ← ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
            diagnostics.log(
                "[A: OkHttp] ${request.method} ${request.url.encodedPath} 网络失败: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}$cause | 链路 $label",
            )
            TransportResult(0, error.message.orEmpty(), false)
        }
    }

    private suspend fun sendRequest(request: Request): TransportResult {
        val reqGeneration = store.sessionGeneration
        val activeTag = ActiveSessionRequest(reqGeneration)
        val result = suspendCancellableCoroutine<TransportResult> { continuation ->
            val call = client.newCall(request.newBuilder()
                .tag(ActiveSessionRequest::class.java, activeTag).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            val text = resp.body?.string().orEmpty()
                            val mitigated = resp.header("cf-mitigated")
                            val retryAfter = if (resp.code == 429 || resp.code == 503) {
                                HttpHeaderUtils.extractRetryAfterSeconds(
                                    retryAfterHeader = resp.header("Retry-After"),
                                    resetHeader = resp.header("x-ratelimit-reset"),
                                    body = text,
                                )
                            } else {
                                HttpHeaderUtils.parseRetryAfter(resp.header("Retry-After"))
                            }
                            val transportResult = TransportResult(
                                status = resp.code,
                                body = text,
                                challenged = ChallengeDetector.isChallenge(resp.code, mitigated, text),
                                retryAfterSeconds = retryAfter,
                                sentClearanceFp = activeTag.sentClearanceFp,
                                cfMitigated = mitigated,
                            )
                            if (continuation.isActive) {
                                continuation.resume(transportResult)
                            }
                        }
                    } catch (error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
        if (store.sessionGeneration != reqGeneration) throw CancellationException("会话已切换")
        return result
    }
}

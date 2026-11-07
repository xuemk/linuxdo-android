package org.linuxdo.android.net

import android.net.Uri
import org.linuxdo.android.Config
import org.linuxdo.android.data.Diagnostics

/** 路径 B:所有请求在 WebView 内用 fetch() 发出;遇到挑战同样静默重验证并重试一次。 */
class WebViewFetchTransport(
    private val browser: BrowserSession,
    private val diagnostics: Diagnostics,
) : Transport {
    override val name: String = NetworkPath.WEBVIEW.label

    /** 路径 B 的 fetch() 走真实 WebView 容器,按调用方指定的 [contentType] 发送。 */
    override suspend fun write(
        path: String,
        method: String,
        body: String?,
        csrf: String,
        contentType: String,
        present: Boolean,
    ): TransportResult = browser.fetchJson(path, csrf, method, body, contentType, present = present)

    override suspend fun get(path: String, present: Boolean): TransportResult {
        var result = browser.fetchJson(path, present = present)
        val requestHost = Uri.parse(path).host ?: Config.HOST
        if (result.challenged && revalidate(path, requestHost = requestHost)) {
            result = browser.fetchJson(path, present = present)
        }
        return result
    }

    override suspend fun getWithoutRevalidate(path: String, present: Boolean): TransportResult =
        browser.fetchJson(path, present = present, allowRevalidate = false)

    override suspend fun getForVerification(path: String, present: Boolean): TransportResult =
        getWithoutRevalidate(path, present)

    override suspend fun revalidate(reason: String, failedClearanceFp: String, requestHost: String): Boolean {
        diagnostics.recordSilentRevalidation()
        return browser.clearChallenge("B: $reason", failedClearanceFp, requestHost)
    }

    override suspend fun ensureReady(): Boolean = browser.ensureReady()

    override suspend fun isReady(): Boolean = browser.isPageCleared()

    override suspend fun obtainCsrfToken(): String? = browser.currentCsrfToken()

    /**
     * 路径 B 不支持上传。
     *
     * evaluateJavascript 只能传字符串,文件得 Base64 编进 JS 再在页面里还原成 Blob ——
     * 4MB 的文件编码后 5.4MB,塞进一条 JS 语句既不稳也白占内存。而路径 B 只有 DEBUG 构建的
     * 开发者选项才能切到,正式包压根到不了这里,所以不为它铺这条脆弱的路。
     */
    override suspend fun upload(
        path: String,
        csrf: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        fields: Map<String, String>,
    ): TransportResult = TransportResult(0, "路径 B 暂不支持上传，请在开发者选项切回路径 A", false)
}

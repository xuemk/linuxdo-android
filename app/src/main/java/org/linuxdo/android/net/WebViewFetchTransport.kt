package org.linuxdo.android.net

import org.linuxdo.android.data.Diagnostics

/** 路径 B:所有请求在 WebView 内用 fetch() 发出;遇到挑战同样静默重验证并重试一次。 */
class WebViewFetchTransport(
    private val browser: BrowserSession,
    private val diagnostics: Diagnostics,
) : Transport {
    override val name: String = NetworkPath.WEBVIEW.label

    override suspend fun write(path: String, method: String, body: String?, csrf: String): TransportResult =
        browser.fetchJson(path, csrf, method, body)

    override suspend fun get(path: String): TransportResult {
        var result = browser.fetchJson(path)
        if (result.challenged && revalidate(path)) {
            result = browser.fetchJson(path)
        }
        return result
    }

    override suspend fun revalidate(reason: String): Boolean {
        diagnostics.recordSilentRevalidation()
        return browser.clearChallenge("B: $reason")
    }

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

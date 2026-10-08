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
        if (result.challenged) {
            diagnostics.recordSilentRevalidation()
            if (browser.clearChallenge("B: $path")) {
                result = browser.fetchJson(path)
            }
        }
        return result
    }
}

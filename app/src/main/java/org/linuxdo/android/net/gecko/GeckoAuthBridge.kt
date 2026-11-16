package org.linuxdo.android.net.gecko

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.linuxdo.android.Config
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val LINKED_H5_CONTEXT_PREFIX = "linuxdo-h5-"

/** 为现有认证 HTML 提供 Gecko 的脚本调用和 Native 回调，不改变登录协议。 */
class GeckoAuthBridge private constructor(
    private val session: GeckoSession,
    private val extension: WebExtension,
    private val onReady: (String) -> Unit,
    private val onCallback: (String, String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val pending = mutableMapOf<String, CompletableDeferred<String>>()
    private var connection = CompletableDeferred<WebExtension.Port>()
    private var activePort: WebExtension.Port? = null
    private var closed = false

    init {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    val sender = port.sender
                    val origin = Uri.parse(sender.url)
                    if (closed || sender.session !== session || !sender.isTopLevel ||
                        origin.scheme != "https" || origin.host != Config.HOST
                    ) {
                        port.disconnect()
                        return
                    }
                    activePort?.let { oldPort ->
                        disconnect(oldPort)
                        oldPort.disconnect()
                    }
                    activePort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (closed || activePort !== port) return
                            val payload = message as? JSONObject ?: return
                            when (payload.optString("type")) {
                                "ready" -> onReady(sender.url)
                                "callback" -> onCallback(payload.optString("name"), payload.optString("payload"))
                                "response" -> {
                                    val result = pending.remove(payload.optString("id")) ?: return
                                    if (payload.has("error")) {
                                        result.completeExceptionally(IllegalStateException(payload.optString("error")))
                                    } else {
                                        result.complete(payload.optString("value", "null"))
                                    }
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            disconnect(port)
                        }
                    })
                    if (!connection.isCompleted) connection.complete(port)
                }
            },
            NATIVE_APP,
        )
    }

    private fun disconnect(port: WebExtension.Port) {
        if (activePort !== port) return
        activePort = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException("认证页面已切换")) }
        pending.clear()
        connection = CompletableDeferred()
    }

    private suspend fun request(command: String, arguments: JSONObject = JSONObject()): String =
        withContext(Dispatchers.Main.immediate) {
            check(!closed) { "认证会话已关闭" }
            withTimeout(10_000L) {
                val port = connection.await()
                val requestId = UUID.randomUUID().toString()
                val result = CompletableDeferred<String>()
                pending[requestId] = result
                try {
                    port.postMessage(arguments.put("id", requestId).put("command", command))
                    result.await()
                } finally {
                    pending.remove(requestId)
                }
            }
        }

    fun evaluateJavascript(script: String, callback: (String?) -> Unit = {}) {
        scope.launch {
            try {
                callback(request("evaluate", JSONObject().put("script", script)))
            } catch (_: CancellationException) {
                if (!closed) callback(null)
            } catch (error: Exception) {
                Log.w(TAG, "认证脚本执行失败: ${error.message}")
                if (!closed) callback(null)
            }
        }
    }

    suspend fun readCookies(): JSONArray {
        check(session.settings.contextId?.startsWith(LINKED_H5_CONTEXT_PREFIX) != true) { "隔离 H5 不允许回读主站 Cookie" }
        return JSONArray(request("cookies"))
    }

    suspend fun observeInAppPage(): JSONObject = JSONObject(request("observeInAppPage"))

    suspend fun prepareAuthCookies(cookies: Map<String, String>) {
        check(session.settings.contextId?.startsWith(LINKED_H5_CONTEXT_PREFIX) != true) { "隔离 H5 不允许清理主站 Cookie" }
        request("prepareAuth", JSONObject().put("cookies", JSONObject(cookies)))
    }

    suspend fun prepareInAppSessionCookies(
        cookies: Map<String, String>,
        challengedClearances: Set<String>,
    ) {
        val payload = JSONObject()
            .put("cookies", JSONObject(cookies))
            .put("challengedClearances", JSONArray(challengedClearances.toList()))
        val contextId = session.settings.contextId
        if (contextId?.startsWith(LINKED_H5_CONTEXT_PREFIX) == true) {
            request("prepareIsolatedInAppSession", payload.put("contextId", contextId))
        } else {
            request("prepareInAppSession", payload)
        }
    }

    suspend fun readCfClearances(): List<String> {
        val array = readCookies()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("name").equals("cf_clearance", ignoreCase = true)) {
                val value = item.optString("value").trim()
                if (value.isNotEmpty()) result.add(value)
            }
        }
        return result
    }

    suspend fun clearCfClearance() {
        request("clearCf")
    }

    /** 当前文档是否命中各组标记词，返回 组名 -> 是否命中。 */
    suspend fun probeMarkers(groups: Map<String, List<String>>): Map<String, Boolean> {
        val payload = JSONObject()
        groups.forEach { (name, words) -> payload.put(name, JSONArray(words)) }
        val raw = request("markers", JSONObject().put("groups", payload))
        val result = JSONObject(raw)
        return groups.keys.associateWith { result.optBoolean(it) }
    }

    /** 用 Gecko 自己的网络栈请求 /cdn-cgi/trace，供与 OkHttp 出口对照。 */
    suspend fun readEgressTrace(): String = JSONTokener(request("trace")).nextValue()?.toString().orEmpty()

    /** WebExtension 内容脚本端口是否已与当前同源文档建立连接。 */
    fun isConnected(): Boolean = !closed && activePort != null

    fun close() {
        if (closed) return
        closed = true
        session.webExtensionController.setMessageDelegate(extension, null, NATIVE_APP)
        activePort?.disconnect()
        activePort = null
        connection.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "GeckoAuthBridge"
        private const val NATIVE_APP = "linuxdoAuth"
        private const val EXTENSION_ID = "linuxdo-auth@linuxdo.android"
        private const val COOKIE_CONTROL_APP = "linuxdoCookieControl"
        private var extensionResult: GeckoResult<WebExtension>? = null
        private var cookieExtension: WebExtension? = null
        private var cookiePort: WebExtension.Port? = null
        private var cookieConnection = CompletableDeferred<WebExtension.Port>()
        private val cookieRequests = mutableMapOf<String, CompletableDeferred<Boolean>>()

        private suspend fun installedExtension(context: Context): WebExtension {
            val controller = GeckoManager.getOrCreateRuntime(context).webExtensionController
            val result = extensionResult ?: controller.ensureBuiltIn("resource://android/assets/gecko-auth/", EXTENSION_ID)
                .also { extensionResult = it }
            val extension = suspendCancellableCoroutine<WebExtension> { continuation ->
                result.accept(
                    { value ->
                        if (continuation.isActive) {
                            if (value != null) continuation.resume(value)
                            else {
                                extensionResult = null
                                continuation.resumeWithException(IllegalStateException("认证扩展未安装"))
                            }
                        }
                    },
                    { error ->
                        extensionResult = null
                        if (continuation.isActive) continuation.resumeWithException(error ?: IllegalStateException("认证扩展安装失败"))
                    },
                )
            }
            bindCookieControl(extension)
            return extension
        }

        private fun disconnectCookieControl(port: WebExtension.Port) {
            if (cookiePort !== port) return
            cookiePort = null
            cookieConnection = CompletableDeferred()
            val waiting = cookieRequests.values.toList()
            cookieRequests.clear()
            waiting.forEach { it.complete(false) }
        }

        private fun bindCookieControl(extension: WebExtension) {
            if (cookieExtension === extension) return
            cookiePort?.let { old -> disconnectCookieControl(old); old.disconnect() }
            cookieExtension = extension
            extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    val sender = port.sender
                    if (sender.webExtension.id != EXTENSION_ID || sender.session != null ||
                        sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION) {
                        port.disconnect()
                        return
                    }
                    if (cookiePort === port) return
                    cookiePort?.let { old -> disconnectCookieControl(old); old.disconnect() }
                    cookiePort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (cookiePort !== port) return
                            val response = message as? JSONObject ?: return
                            cookieRequests.remove(response.optString("id"))?.complete(response.optBoolean("value", false))
                        }

                        override fun onDisconnect(port: WebExtension.Port) = disconnectCookieControl(port)
                    })
                    if (!cookieConnection.isCompleted) cookieConnection.complete(port)
                }
            }, COOKIE_CONTROL_APP)
        }

        suspend fun clearMainSiteCookies(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
            try {
                withTimeoutOrNull(5_000L) {
                    installedExtension(context)
                    val port = cookieConnection.await()
                    val id = UUID.randomUUID().toString()
                    val response = CompletableDeferred<Boolean>()
                    cookieRequests[id] = response
                    try {
                        port.postMessage(JSONObject().put("id", id).put("command", "clearSiteCookies"))
                        response.await()
                    } finally {
                        cookieRequests.remove(id)
                    }
                } ?: false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "清理主站默认容器 Cookie 失败: ${error.message}")
                false
            }
        }

        suspend fun create(
            context: Context,
            session: GeckoSession,
            onReady: (String) -> Unit,
            onCallback: (String, String) -> Unit,
        ): GeckoAuthBridge = withContext(Dispatchers.Main.immediate) {
            GeckoAuthBridge(session, installedExtension(context), onReady, onCallback)
        }
    }
}

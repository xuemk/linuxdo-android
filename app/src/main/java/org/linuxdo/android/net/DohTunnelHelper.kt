package org.linuxdo.android.net

import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.json.JSONArray


/**
 * 从持久化存储读取当前完整的 DoH 节点列表（若未自定义则返回内置预设）。
 */
fun loadDohServers(store: DohConfigProvider): List<DohServer> {
    val presetsMap = DohPresets.ALL.associateBy { it.id }
    val json = store.getDohServersJson()
    if (!json.isNullOrBlank()) {
        val parsed = runCatching {
            JSONArray(json).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val rawUrl = obj.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                    val url = if (rawUrl.contains("kudo.ddd.oaifree.com")) {
                        rawUrl.replace("kudo.ddd.oaifree.com", "neo.ddd.oaifree.com")
                    } else {
                        rawUrl
                    }
                    val name = obj.optString("name").ifBlank { url }
                    val isCustom = obj.optBoolean("isCustom", false)
                    val jsonIps = obj.optJSONArray("bootstrapIps")?.let { ipsArr ->
                        (0 until ipsArr.length()).mapNotNull { idx -> ipsArr.optString(idx).takeIf { it.isNotBlank() } }
                    } ?: emptyList()
                    val matchedPreset = presetsMap[url.trimEnd('/')]
                    val finalIps = if (jsonIps.isNotEmpty()) jsonIps else (matchedPreset?.bootstrapIps ?: emptyList())
                    DohServer(url = url, name = name, bootstrapIps = finalIps, isCustom = isCustom)
                }
            }
        }.getOrNull()
        if (!parsed.isNullOrEmpty()) return parsed
    }

    // 兼容旧版仅存自定义节点的情形
    val customJson = store.getDohCustomServersJson()
    if (!customJson.isNullOrBlank()) {
        val parsedCustom = runCatching {
            JSONArray(customJson).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val rawUrl = obj.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                    val url = if (rawUrl.contains("kudo.ddd.oaifree.com")) {
                        rawUrl.replace("kudo.ddd.oaifree.com", "neo.ddd.oaifree.com")
                    } else {
                        rawUrl
                    }
                    val name = obj.optString("name").ifBlank { url }
                    DohServer(url = url, name = name, isCustom = true)
                }
            }
        }.getOrDefault(emptyList())
        if (parsedCustom.isNotEmpty()) {
            val existingIds = parsedCustom.map { it.id }.toSet()
            return parsedCustom + DohPresets.ALL.filter { it.id !in existingIds }
        }
    }

    return DohPresets.ALL
}

/**
 * 根据持久化偏好构建 [AdaptiveDohDns] 实例。
 */
fun buildDohDns(store: DohConfigProvider): AdaptiveDohDns {
    val allServers = loadDohServers(store)
    val selectedUrl = store.getDohServerUrl() ?: DohPresets.DEFAULT.url
    val autoFailover = store.isDohAutoFailoverEnabled()

    return AdaptiveDohDns(
        servers = allServers,
        bootstrapClient = buildBootstrapClient(),
        autoFailover = autoFailover,
    ).also { dns ->
        val selected = allServers.firstOrNull { it.url == selectedUrl } ?: allServers.firstOrNull()
        if (selected != null) dns.primaryServerId = selected.id
    }
}

/**
 * 根据持久化偏好构建原生 [DohTransport] 实例（开启 ECH TLS 传输）。
 */
fun buildActiveDohTransport(store: DohConfigProvider): DohTransport? {
    return runCatching {
        val allServers = loadDohServers(store)
        val selectedUrl = store.getDohServerUrl() ?: DohPresets.DEFAULT.url
        val selected = allServers.firstOrNull { it.url == selectedUrl } ?: allServers.firstOrNull()
        if (selected != null) {
            DohTransport(selected)
        } else {
            null
        }
    }.onFailure {
        Log.w("DohTunnelHelper", "构建原生 DohTransport 失败，回退系统/纯DNS模式: ${it.message}")
    }.getOrNull()
}

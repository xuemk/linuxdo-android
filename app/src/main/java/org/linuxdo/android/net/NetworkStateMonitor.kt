package org.linuxdo.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkSecurityState(
    val isVpn: Boolean = false,
    val isProxy: Boolean = false,
) {
    /** 当开启 VPN 或系统代理时，DoH 安全直连应当自动暂停，直透系统代理 */
    val shouldPauseDoh: Boolean get() = isVpn || isProxy

    val summary: String get() = when {
        isVpn -> "VPN 已开启，DoH 暂停"
        isProxy -> "系统代理已开启，DoH 暂停"
        else -> "正常直连"
    }
}

/**
 * 网络安全状态监听器（感知 VPN 与系统代理的开启/关闭）。
 *
 * 当用户手机启用 VPN 或系统代理时：
 * 自动暂停 DoH + ECH 传输，直走系统代理分流；
 * 当 VPN 或系统代理断开后，自动恢复 DoH 安全直连。
 */
class NetworkStateMonitor(
    context: Context,
    private val onStateChanged: (NetworkSecurityState) -> Unit = {}
) {
    companion object {
        private const val TAG = "NetworkStateMonitor"
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(detect())
    val state = _state.asStateFlow()

    private val refreshTask = Runnable {
        val newState = detect()
        if (newState != _state.value) {
            Log.i(TAG, "网络安全状态变更: 旧 ${_state.value} → 新 $newState")
            _state.value = newState
            onStateChanged(newState)
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scheduleRefresh() }
        override fun onLost(network: Network) { scheduleRefresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { scheduleRefresh() }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) { scheduleRefresh() }
    }

    @Volatile private var lastDefaultNetwork: Network? = null
    var onDefaultNetworkChanged: (() -> Unit)? = null

    private val defaultNetworkChangedTask = Runnable {
        onDefaultNetworkChanged?.invoke()
    }

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = lastDefaultNetwork
            lastDefaultNetwork = network
            scheduleRefresh()
            if (previous != null && previous != network) {
                Log.i(TAG, "默认网络切换: $previous -> $network")
                handler.removeCallbacks(defaultNetworkChangedTask)
                handler.post(defaultNetworkChangedTask)
            }
        }

        override fun onLost(network: Network) {
            scheduleRefresh()
        }
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity?.registerNetworkCallback(request, callback)
            connectivity?.registerDefaultNetworkCallback(defaultNetworkCallback)
        }.onFailure {
            Log.w(TAG, "注册网络回调失败: ${it.message}")
        }
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    fun detect(): NetworkSecurityState {
        val cm = connectivity ?: return NetworkSecurityState()
        val isVpn = runCatching {
            cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)

        val isProxy = runCatching {
            cm.defaultProxy != null
        }.getOrDefault(false)

        return NetworkSecurityState(isVpn = isVpn, isProxy = isProxy)
    }
}

package org.linuxdo.android.net

import androidx.annotation.Keep

/**
 * Tunnel 核心对抗与混淆底层（JNI 接入层）。
 * Anycast IP 种子加密存储与 LocalProxyServer 的 ClientHello TLS Record 级分片转发均在 libtunnel_core.so 闭环执行。
 */
@Keep
internal object NativeTunnelCore {
    init {
        System.loadLibrary("tunnel_core")
    }

    fun getSeeds(): List<String> = nativeGetSeeds().toList()
    fun getFallbackSeeds(): List<String> = nativeGetFallbackSeeds().toList()
    fun getBorrowHosts(): List<String> = nativeGetBorrowHosts().toList()
    fun getProbeSni(): String = nativeGetProbeSni()

    fun startProxy(targetSuffix: String): Int = nativeStartProxy(targetSuffix)
    fun stopProxy() = nativeStopProxy()
    fun setProxyUpstreamIp(ip: String) = nativeSetProxyUpstreamIp(ip)
    fun getProxyPort(): Int = nativeGetProxyPort()
    fun setAllowNonTarget(allow: Boolean) = nativeSetAllowNonTarget(allow)

    @JvmStatic private external fun nativeGetSeeds(): Array<String>
    @JvmStatic private external fun nativeGetFallbackSeeds(): Array<String>
    @JvmStatic private external fun nativeGetBorrowHosts(): Array<String>
    @JvmStatic private external fun nativeGetProbeSni(): String

    @JvmStatic private external fun nativeStartProxy(targetSuffix: String): Int
    @JvmStatic private external fun nativeStopProxy()
    @JvmStatic private external fun nativeSetProxyUpstreamIp(ip: String)
    @JvmStatic private external fun nativeGetProxyPort(): Int
    @JvmStatic private external fun nativeSetAllowNonTarget(allow: Boolean)
}

package org.linuxdo.android.net

import okhttp3.Protocol
import okhttp3.internal.platform.Platform
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.*

internal interface NativeAlpnSocket {
    fun configureAlpn(protocols: List<String>)
    val negotiatedProtocol: String?
}

/** Pinned to OkHttp 4.12.0. Only our sockets use this ALPN bridge; all Android behavior is delegated. */
internal class NativeAlpnPlatform(private val android: Platform) : Platform() {
    companion object {
        @Synchronized fun install() {
            val current = get()
            if (current !is NativeAlpnPlatform) resetForTests(NativeAlpnPlatform(current))
        }
    }
    override fun configureTlsExtensions(sslSocket: SSLSocket, hostname: String?, protocols: List<Protocol>) {
        if (sslSocket is NativeAlpnSocket) sslSocket.configureAlpn(protocols.filter { it == Protocol.HTTP_2 || it == Protocol.HTTP_1_1 }.map { it.toString() })
        else android.configureTlsExtensions(sslSocket, hostname, protocols)
    }
    override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
        if (sslSocket is NativeAlpnSocket) sslSocket.negotiatedProtocol else android.getSelectedProtocol(sslSocket)
    override fun afterHandshake(sslSocket: SSLSocket) = android.afterHandshake(sslSocket)
    override fun newSSLContext(): SSLContext = android.newSSLContext()
    override fun platformTrustManager(): X509TrustManager = android.platformTrustManager()
    override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = android.trustManager(sslSocketFactory)
    override fun connectSocket(socket: Socket, address: InetSocketAddress, connectTimeout: Int) = android.connectSocket(socket, address, connectTimeout)
    override fun log(message: String, level: Int, t: Throwable?) = android.log(message, level, t)
    override fun isCleartextTrafficPermitted(hostname: String): Boolean = android.isCleartextTrafficPermitted(hostname)
    override fun getStackTraceForCloseable(closer: String): Any? = android.getStackTraceForCloseable(closer)
    override fun logCloseableLeak(message: String, stackTrace: Any?) = android.logCloseableLeak(message, stackTrace)
    override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner = android.buildCertificateChainCleaner(trustManager)
    override fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex = android.buildTrustRootIndex(trustManager)
    override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory = android.newSslSocketFactory(trustManager)
}

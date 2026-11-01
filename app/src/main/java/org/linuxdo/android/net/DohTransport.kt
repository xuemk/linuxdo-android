package org.linuxdo.android.net

import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.KeyStore
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.*

private val CipherSuites = arrayOf(
    "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
)

class DohTransport(
    val serverUrl: String,
    val bootstrapIps: List<String> = emptyList(),
    private val handshake: (String, Boolean) -> Unit = { _, _ -> }
) : Closeable {
    constructor(server: DohServer, handshake: (String, Boolean) -> Unit = { _, _ -> }) :
        this(server.url, server.bootstrapIps, handshake)

    init { NativeAlpnPlatform.install() }
    private val profile = NativeDoh.createProfile(
        JSONObject().put("url", serverUrl).put("bootstrap", JSONArray(bootstrapIps)).toString()
    )
    private val lock = Any()
    private var closed = false
    private val sockets = mutableSetOf<RustSocket>()

    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            synchronized(lock) { if (closed) throw UnknownHostException("DoH: profile_closed") }
            val ips = JSONObject(NativeDoh.lookup(profile, hostname)).getJSONArray("ips")
            return (0 until ips.length()).map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(ips.getString(it)).address)
            }
        }
    }

    private fun socket(): RustSocket = synchronized(lock) {
        if (closed) throw SocketException("DoH: profile_closed")
        RustSocket(NativeDoh.openSocket(profile), dns) { socket ->
            synchronized(lock) { sockets.remove(socket) }
        }.also { sockets += it }
    }

    private val socketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = socket()
        override fun createSocket(host: String, port: Int): Socket =
            socket().apply { connect(InetSocketAddress(dns.lookup(host).first(), port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket =
            socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            throw SocketException("DoH: explicit_bind_unsupported")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            throw SocketException("DoH: explicit_bind_unsupported")
    }

    private val tlsFactory = object : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = CipherSuites.clone()
        override fun getSupportedCipherSuites(): Array<String> = CipherSuites.clone()
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            if (socket !is RustSocket || !autoClose) throw SocketException("DoH: incompatible_socket")
            return RustTlsSocket(socket, host, port, handshake)
        }
        override fun createSocket(host: String, port: Int): Socket =
            createSocket(socketFactory.createSocket(host, port), host, port, true)
        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket(socketFactory.createSocket(host, port), host.hostName, port, true)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            throw SocketException("DoH: explicit_bind_unsupported")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            throw SocketException("DoH: explicit_bind_unsupported")
    }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers
        val trust = managers.filterIsInstance<X509TrustManager>().single()
        return builder.dns(dns).socketFactory(socketFactory).sslSocketFactory(tlsFactory, trust)
            .addInterceptor { chain -> chain.proceed(dohRequestWithoutReplay(chain.request())) }
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
    }

    override fun close() {
        val active = synchronized(lock) { if (closed) return; closed = true; sockets.toList() }
        active.forEach { it.close() }
        NativeDoh.destroyProfile(profile)
    }
}

private class RustSocket(val handle: Long, private val dns: Dns, private val onClose: (RustSocket) -> Unit) : Socket() {
    private val closed = AtomicBoolean(false)
    private var remote: InetSocketAddress? = null
    private var readTimeout = 0
    private val input = object : InputStream() {
        override fun read(): Int = ByteArray(1).let { if (read(it) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkBounds(bytes, offset, length)
            if (length == 0) return 0
            if (closed.get()) throw SocketException("Socket closed")
            return NativeDoh.read(handle, bytes, offset, minOf(length, 65536))
        }
        override fun close() = this@RustSocket.close()
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkBounds(bytes, offset, length)
            var position = offset
            while (position < offset + length) {
                if (closed.get()) throw SocketException("Socket closed")
                val count = minOf(offset + length - position, 65536)
                NativeDoh.write(handle, bytes, position, count)
                position += count
            }
        }
        override fun close() = this@RustSocket.close()
    }
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        require(timeout >= 0)
        if (closed.get()) throw SocketException("Socket closed")
        val address = endpoint as? InetSocketAddress ?: throw SocketException("Unsupported address")
        val ip = address.address ?: dns.lookup(address.hostString).first()
        NativeDoh.connect(handle, ip.hostAddress.orEmpty(), address.port, timeout)
        remote = InetSocketAddress(ip, address.port)
        NativeDoh.timeout(handle, readTimeout)
    }
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun setSoTimeout(timeout: Int) { require(timeout >= 0); NativeDoh.timeout(handle, timeout); readTimeout = timeout }
    override fun getSoTimeout(): Int = readTimeout
    override fun setTcpNoDelay(on: Boolean) { /* Native TCP always uses NODELAY. */ }
    override fun getTcpNoDelay(): Boolean = true
    override fun isConnected(): Boolean = remote != null
    override fun isClosed(): Boolean = closed.get()
    override fun getInetAddress(): InetAddress? = remote?.address
    override fun getPort(): Int = remote?.port ?: 0
    override fun getRemoteSocketAddress(): SocketAddress? = remote
    override fun close() { if (closed.compareAndSet(false, true)) { NativeDoh.close(handle); onClose(this) } }
    override fun shutdownInput() = close()
    override fun shutdownOutput() = close()
    override fun toString(): String = "DoH socket"
}

private fun checkBounds(bytes: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) throw IndexOutOfBoundsException()
}

private class RustTlsSocket(
    private val raw: RustSocket,
    private val host: String,
    private val remotePort: Int,
    private val onHandshake: (String, Boolean) -> Unit
) : SSLSocket(), NativeAlpnSocket {
    private var offeredAlpn = listOf("h2", "http/1.1")
    override var negotiatedProtocol: String? = null
        private set
    override fun configureAlpn(protocols: List<String>) { offeredAlpn = protocols }
    private var enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
    private var enabledCiphers = CipherSuites.clone()
    private var activeSession: SSLSession? = null
    private val listeners = mutableSetOf<HandshakeCompletedListener>()
    override fun startHandshake() {
        if (activeSession != null) return
        val metadata = JSONObject(NativeDoh.handshake(raw.handle, host, offeredAlpn.joinToString(",")))
        val session = RustSession(metadata, host, remotePort)
        if (session.protocol !in enabledProtocols || session.cipherSuite !in enabledCiphers) {
            raw.close(); throw SSLHandshakeException("DoH: tls_negotiation_mismatch")
        }
        activeSession = session
        negotiatedProtocol = metadata.getString("alpn")
        onHandshake(host, metadata.getString("ech") == "Accepted")
        listeners.toList().forEach { it.handshakeCompleted(HandshakeCompletedEvent(this, session)) }
    }
    override fun getSession(): SSLSession { if (activeSession == null) startHandshake(); return activeSession!! }
    override fun getInputStream(): InputStream = raw.getInputStream()
    override fun getOutputStream(): OutputStream = raw.getOutputStream()
    override fun setSoTimeout(timeout: Int) { raw.soTimeout = timeout }
    override fun getSoTimeout(): Int = raw.soTimeout
    override fun setTcpNoDelay(on: Boolean) { raw.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = true
    override fun isConnected(): Boolean = raw.isConnected
    override fun isClosed(): Boolean = raw.isClosed
    override fun getInetAddress(): InetAddress? = raw.inetAddress
    override fun getPort(): Int = remotePort
    override fun getRemoteSocketAddress(): SocketAddress? = raw.remoteSocketAddress
    override fun close() = raw.close()
    override fun shutdownInput() = raw.shutdownInput()
    override fun shutdownOutput() = raw.shutdownOutput()
    override fun getSupportedCipherSuites(): Array<String> = CipherSuites.clone()
    override fun getEnabledCipherSuites(): Array<String> = enabledCiphers.clone()
    override fun setEnabledCipherSuites(suites: Array<String>) {
        require(suites.isNotEmpty() && suites.all { it in CipherSuites }); enabledCiphers = suites.clone()
    }
    override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3", "TLSv1.2")
    override fun getEnabledProtocols(): Array<String> = enabledProtocols.clone()
    override fun setEnabledProtocols(protocols: Array<String>) {
        require(protocols.isNotEmpty() && protocols.all { it in supportedProtocols }); enabledProtocols = protocols.clone()
    }
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) { listeners += listener }
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) { require(listeners.remove(listener)) }
    override fun setUseClientMode(mode: Boolean) { require(mode) }
    override fun getUseClientMode(): Boolean = true
    override fun setNeedClientAuth(need: Boolean) { require(!need) }
    override fun getNeedClientAuth(): Boolean = false
    override fun setWantClientAuth(want: Boolean) { require(!want) }
    override fun getWantClientAuth(): Boolean = false
    override fun setEnableSessionCreation(flag: Boolean) { require(flag) }
    override fun getEnableSessionCreation(): Boolean = true
    override fun toString(): String = "DoH TLS socket"
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class RustSession(metadata: JSONObject, private val host: String, private val port: Int) : SSLSession {
    private val created = System.currentTimeMillis()
    private val certificates: Array<Certificate> = metadata.getJSONArray("certs").let { list ->
        Array(list.length()) {
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(Base64.getDecoder().decode(list.getString(it)))
            )
        }
    }
    private val tlsProtocol = metadata.getString("protocol")
    private val cipher = metadata.getString("cipher")
    private val values = mutableMapOf<String, Any>()
    override fun getId(): ByteArray = byteArrayOf()
    override fun getSessionContext(): SSLSessionContext? = null
    override fun getCreationTime(): Long = created
    override fun getLastAccessedTime(): Long = created
    override fun invalidate() {}
    override fun isValid(): Boolean = true
    override fun putValue(name: String, value: Any) { values[name] = value }
    override fun getValue(name: String): Any? = values[name]
    override fun removeValue(name: String) { values.remove(name) }
    override fun getValueNames(): Array<String> = values.keys.toTypedArray()
    override fun getPeerCertificates(): Array<Certificate> = certificates.clone()
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
        throw SSLPeerUnverifiedException("Use peerCertificates")
    override fun getPeerPrincipal(): Principal = (certificates.first() as X509Certificate).subjectX500Principal
    override fun getLocalPrincipal(): Principal? = null
    override fun getCipherSuite(): String = cipher
    override fun getProtocol(): String = tlsProtocol
    override fun getPeerHost(): String = host
    override fun getPeerPort(): Int = port
    override fun getPacketBufferSize(): Int = 65536
    override fun getApplicationBufferSize(): Int = 65536
}

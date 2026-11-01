package org.linuxdo.android.net

import androidx.annotation.Keep

/** JNI names are part of the native library ABI. Loading this object performs no networking. */
@Keep
internal object NativeDoh {
    init { System.loadLibrary("linuxdo_doh") }
    @JvmStatic external fun createProfile(configuration: String): Long
    @JvmStatic external fun destroyProfile(id: Long)
    @JvmStatic external fun lookup(id: Long, host: String): String
    @JvmStatic external fun openSocket(profile: Long): Long
    @JvmStatic external fun connect(id: Long, ip: String, port: Int, millis: Int)
    @JvmStatic external fun handshake(id: Long, host: String, alpn: String): String
    @JvmStatic external fun read(id: Long, bytes: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun write(id: Long, bytes: ByteArray, offset: Int, length: Int)
    @JvmStatic external fun timeout(id: Long, millis: Int)
    @JvmStatic external fun close(id: Long)
}

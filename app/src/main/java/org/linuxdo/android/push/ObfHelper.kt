package org.linuxdo.android.push

import java.nio.charset.StandardCharsets

internal object ObfHelper {
    private const val KEY: Byte = 0x5A

    fun dec(bytes: ByteArray): String {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor KEY.toInt()).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    val CH_NOTIFICATIONS: String by lazy {
        dec(byteArrayOf(54, 51, 52, 47, 34, 62, 53, 5, 47, 52, 40, 63, 59, 62, 5, 52, 53, 46, 51, 60, 51, 57, 59, 46, 51, 53, 52, 41, 5, 44, 105))
    }

    val CH_KEEPALIVE: String by lazy {
        dec(byteArrayOf(54, 51, 52, 47, 34, 62, 53, 5, 49, 63, 63, 42, 59, 54, 51, 44, 63, 5, 41, 63, 40, 44, 51, 57, 63))
    }

    val ACT_MINIMIZED: String by lazy {
        dec(byteArrayOf(53, 40, 61, 116, 54, 51, 52, 47, 34, 62, 53, 116, 59, 52, 62, 40, 53, 51, 62, 116, 27, 25, 14, 19, 21, 20, 5, 23, 19, 20, 19, 23, 19, 0, 31, 30, 5, 10, 21, 22, 22))
    }
}

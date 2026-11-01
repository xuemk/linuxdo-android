package org.linuxdo.android.net

import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Connection-failure retries being disabled does not stop OkHttp's 503 Retry-After:0 follow-up. */
internal fun dohRequestWithoutReplay(request: Request): Request {
    if (request.method in listOf("GET", "HEAD")) return request
    val body = request.body ?: byteArrayOf().toRequestBody()
    if (body.isOneShot()) return request
    val once = object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun isOneShot(): Boolean = true
        override fun isDuplex(): Boolean = body.isDuplex()
        override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
    }
    return request.newBuilder().method(request.method, once).build()
}

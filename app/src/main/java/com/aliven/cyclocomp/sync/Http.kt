package com.aliven.cyclocomp.sync

import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val code: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Seam for tests: the GitHub client talks to this instead of the network. */
interface Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse
}

class UrlConnectionHttp : Http {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return HttpResponse(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }
}

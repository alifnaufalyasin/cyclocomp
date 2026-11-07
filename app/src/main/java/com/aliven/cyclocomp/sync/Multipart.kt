package com.aliven.cyclocomp.sync

import java.io.ByteArrayOutputStream
import java.util.UUID

class MultipartBody(val contentType: String, val bytes: ByteArray)

/** Builds a `multipart/form-data` request body (text fields plus one file) without any library. */
object Multipart {
    fun build(
        fields: Map<String, String>,
        fileField: String? = null,
        fileName: String? = null,
        fileBytes: ByteArray? = null,
        boundary: String = "cyclocomp-" + UUID.randomUUID().toString().replace("-", ""),
    ): MultipartBody {
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

        for ((name, value) in fields) {
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"${clean(name)}\"\r\n\r\n")
            text(value)
            text("\r\n")
        }
        if (fileField != null && fileName != null && fileBytes != null) {
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"${clean(fileField)}\"; filename=\"${clean(fileName)}\"\r\n")
            text("Content-Type: application/octet-stream\r\n\r\n")
            out.write(fileBytes)
            text("\r\n")
        }
        text("--$boundary--\r\n")
        return MultipartBody("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    // Quotes and line breaks would end the header value early.
    private fun clean(s: String) = s.replace("\"", "%22").replace("\r", "").replace("\n", "")
}

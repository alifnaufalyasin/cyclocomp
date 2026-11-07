package com.aliven.cyclocomp.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Reads a stream into memory, or returns null if it is longer than [maxBytes]. */
fun readAtMost(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** The file name a picked document goes by, if the provider tells us. */
fun displayNameOf(context: Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment
} catch (e: Exception) {
    uri.lastPathSegment
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ride"))
}

/** Page layout is a per-viewer preference, so it lives in SharedPreferences. */
object PageStore {
    private const val PREFS = "cyclocomp"
    private const val KEY = "pages"

    fun load(context: Context): List<List<DataField>> =
        Pages.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, pages: List<List<DataField>>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, Pages.encode(pages)).apply()
    }
}

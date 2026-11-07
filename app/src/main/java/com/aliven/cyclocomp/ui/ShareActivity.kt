package com.aliven.cyclocomp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.aliven.cyclocomp.sync.StoreException
import com.aliven.cyclocomp.sync.PutResult
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Share target: "Share -> Cyclocomp" from Gallery / Files / Huawei Health uploads the chosen
 * images or GPX files to `inbox/<phone name>/` in the data repo. Nothing is uploaded unless
 * the user shared it, and files are never overwritten.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ShareScreen(uris) { finish() } }
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else -> emptyList()
    }
}

private const val MAX_BYTES = 25 * 1024 * 1024

@Composable
private fun ShareScreen(uris: List<Uri>, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cfg = SyncSettings.load(context)
            if (uris.isEmpty()) {
                lines.add("Nothing to upload.")
            } else if (cfg == null) {
                lines.add("Sync isn't set up yet. Open Cyclocomp > Sync and enter the repo, phone name and token.")
            } else {
                val repo = SyncSettings.repo(cfg)
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(ZonedDateTime.now(ZoneId.systemDefault()))
                for ((i, uri) in uris.withIndex()) {
                    val name = displayName(context, uri) ?: "shared_$i"
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                        when {
                            bytes == null -> lines.add("$name: too large (max 25 MB) or unreadable")
                            else -> {
                                val path = "inbox/${cfg.device}/${stamp}_${i}_${RepoPaths.safeSegment(name)}"
                                val r = repo.putNew(path, bytes, "Add inbox file from ${cfg.device}")
                                lines.add(if (r == PutResult.CREATED) "Uploaded $name" else "$name: already uploaded")
                            }
                        }
                    } catch (e: StoreException) {
                        lines.add("$name: ${e.message}")
                        if (e.code == 401 || e.code == 403 || e.code == 404) break
                    } catch (e: Exception) {
                        lines.add("$name: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            done = true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Send to data repo", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (!done) Text("Uploading ${uris.size} file(s)…")
        lines.forEach { Text(it) }
        if (done) Button(onClick = onClose) { Text("Close") }
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

/** Reads at most [MAX_BYTES]; returns null if the stream is larger. */
private fun readLimited(input: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > MAX_BYTES) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

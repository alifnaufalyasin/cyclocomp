package com.aliven.cyclocomp.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.recording.RideRecorder
import com.aliven.cyclocomp.sync.RideImportOutcome
import com.aliven.cyclocomp.sync.RideImporter
import com.aliven.cyclocomp.sync.RideSync
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val MAX_BYTES = 25 * 1024 * 1024

/**
 * Imports the chosen GPX files (from Strava, a watch export, ...) as rides, one line of result per
 * file. Blocking: call off the main thread. A file that matches a ride already here is merged into
 * it (heart rate) instead of creating a duplicate. New rides are uploaded by the next sync.
 */
fun importGpxFiles(context: Context, uris: List<Uri>): List<String> {
    RideRecorder.init(context.applicationContext)
    val ridesDir = RideRecorder.ridesDir()
    val local = RideImporter.loadCandidates(ridesDir)
    return uris.map { uri ->
        val name = displayName(context, uri)
        if (!name.endsWith(".gpx", ignoreCase = true)) {
            return@map "$name: not a .gpx file (FIT and zipped files are not supported)"
        }
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: return@map "$name: unreadable or larger than 25 MB"
            val outcome = RideImporter.importTrack(bytes, ridesDir, local) { RideSync.markDirty(context, it.name) }
            "$name: " + describe(outcome)
        } catch (e: Exception) {
            "$name: not a readable GPX file (${e.message ?: e.javaClass.simpleName})"
        }
    }
}

internal fun describe(outcome: RideImportOutcome): String = when (outcome) {
    is RideImportOutcome.Created -> "added as ride ${RideNames.display(outcome.rideName)}"
    is RideImportOutcome.Merged -> "added heart rate to ride ${RideNames.display(outcome.rideName)} (${outcome.points} points)"
    is RideImportOutcome.AlreadyHasHr -> "matches ride ${RideNames.display(outcome.rideName)}, which already has heart rate"
    is RideImportOutcome.NoHrToAdd -> "matches ride ${RideNames.display(outcome.rideName)}; nothing new to add"
    is RideImportOutcome.AlreadyImported -> "already in your rides (${RideNames.display(outcome.rideName)})"
    is RideImportOutcome.Skipped -> "skipped, ${outcome.reason}"
}

private fun displayName(context: Context, uri: Uri): String =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "file"
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

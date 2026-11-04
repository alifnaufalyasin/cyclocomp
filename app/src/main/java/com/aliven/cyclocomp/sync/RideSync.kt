package com.aliven.cyclocomp.sync

import android.content.Context
import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.RideSummary
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale

class SyncReport(val uploaded: Int, val alreadyThere: Int, val failed: List<String>)

object RideSummaryJson {
    fun toJson(fileName: String, s: RideSummary): String {
        val avgKmh = Math.round(s.avgSpeedMps * 3.6 * 100) / 100.0
        val maxKmh = Math.round(s.maxSpeedMps * 3.6 * 100) / 100.0
        return JSONObject()
            .put("file", fileName)
            .put("start", Instant.ofEpochMilli(s.startMs).toString())
            .put("distance_m", Math.round(s.distanceM))
            .put("moving_s", s.movingMs / 1000)
            .put("elapsed_s", s.elapsedMs / 1000)
            .put("avg_speed_kmh", avgKmh)
            .put("max_speed_kmh", maxKmh)
            .put("ascent_m", Math.round(s.ascentM))
            .put("descent_m", Math.round(s.descentM))
            .apply {
                s.avgHr?.let { put("avg_hr", it) }
                s.maxHr?.let { put("max_hr", it) }
            }
            .toString(2)
    }
}

object RideSync {
    private const val PREFS = "cyclocomp_sync_state"
    private const val KEY = "uploaded_rides"
    private const val DIRTY = "dirty_rides"

    /** A ride changed after upload (heart rate merged in): the next sync replaces the repo copy. */
    fun markDirty(context: Context, rideName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dirty = prefs.getStringSet(DIRTY, emptySet())!!.toMutableSet().apply { add(rideName) }
        prefs.edit().putStringSet(DIRTY, dirty).apply()
    }

    /** Uploads each saved ride (GPX + summary JSON) to `rides/<year>/`. Safe to re-run: done rides are skipped. */
    fun uploadRides(context: Context, repo: GitHubDataRepo, rides: List<File>): SyncReport {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = prefs.getStringSet(KEY, emptySet())!!.toMutableSet()
        val dirty = prefs.getStringSet(DIRTY, emptySet())!!.toMutableSet()
        var uploaded = 0
        var existed = 0
        val failed = mutableListOf<String>()

        for (file in rides) {
            val replace = file.name in dirty
            if (file.name in done && !replace) continue
            try {
                val year = file.name.take(4).takeIf { y -> y.all { it.isDigit() } } ?: "unknown"
                if (file.name.endsWith(WorkoutStore.EXTENSION_SUFFIX)) {
                    // Screenshot workout stored beside the rides: uploaded as is, never overwritten.
                    val r = repo.putNew("rides/$year/${file.name}", file.readBytes(), "Add workout ${file.name}")
                    if (r == PutResult.CREATED) uploaded++ else existed++
                    done.add(file.name)
                    prefs.edit().putStringSet(KEY, done).apply()
                    continue
                }
                val summary = file.inputStream().use { RideSummaries.summarize(GpxParser.parse(it)) }
                val json = summary?.let { RideSummaryJson.toJson(file.name, it).toByteArray(Charsets.UTF_8) }
                val gpxPath = "rides/$year/${file.name}"
                val jsonPath = "rides/$year/${file.nameWithoutExtension}.json"
                if (replace) {
                    repo.putOrUpdate(gpxPath, file.readBytes(), "Update ride ${file.name}")
                    if (json != null) repo.putOrUpdate(jsonPath, json, "Update summary ${file.name}")
                    uploaded++
                } else {
                    val gpxResult = repo.putNew(gpxPath, file.readBytes(), "Add ride ${file.name}")
                    if (json != null) repo.putNew(jsonPath, json, "Add summary ${file.name}")
                    if (gpxResult == PutResult.CREATED) uploaded++ else existed++
                }
                done.add(file.name)
                dirty.remove(file.name)
                prefs.edit().putStringSet(KEY, done).putStringSet(DIRTY, dirty).apply()
            } catch (e: GitHubException) {
                failed.add("${file.name}: ${e.message}")
                // Auth/permission problems affect every file; stop instead of repeating the failure.
                if (e.code == 401 || e.code == 403 || e.code == 404) break
            } catch (e: Exception) {
                failed.add("${file.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return SyncReport(uploaded, existed, failed)
    }

    fun describe(r: SyncReport): String = buildString {
        append(String.format(Locale.US, "Uploaded %d, already in repo %d", r.uploaded, r.alreadyThere))
        if (r.failed.isNotEmpty()) append("\nFailed:\n").append(r.failed.joinToString("\n"))
    }
}

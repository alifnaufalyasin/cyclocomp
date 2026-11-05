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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A ride changed after upload (heart rate merged in): the next sync replaces the repo copy. */
    fun markDirty(context: Context, rideName: String) {
        val p = prefs(context)
        p.edit().putStringSet(DIRTY, p.getStringSet(DIRTY, emptySet())!!.toSet() + rideName).apply()
    }

    /** The file is known to be in the repo (it was just downloaded from there). */
    fun markUploaded(context: Context, fileName: String) {
        val p = prefs(context)
        p.edit().putStringSet(KEY, p.getStringSet(KEY, emptySet())!!.toSet() + fileName).apply()
    }

    private fun yearOf(name: String) = name.take(4).takeIf { y -> y.all { it.isDigit() } } ?: "unknown"

    /**
     * Uploads whatever the repo is missing or has an older copy of.
     *
     * [remoteNames] is the real listing of `rides/` in the repo. A ride counts as present only if it
     * is in that list, so files lost from the repo are uploaded again, and a ride whose summary JSON
     * is missing gets the summary. Without a listing it falls back to this phone's own upload record.
     */
    fun uploadRides(
        context: Context,
        repo: GitHubDataRepo,
        rides: List<File>,
        remoteNames: Set<String>? = null,
    ): SyncReport {
        val p = prefs(context)
        val done = p.getStringSet(KEY, emptySet())!!.toMutableSet()
        val dirty = p.getStringSet(DIRTY, emptySet())!!.toMutableSet()
        var uploaded = 0
        var existed = 0
        val failed = mutableListOf<String>()

        for (file in rides) {
            val name = file.name
            val inRepo = remoteNames?.contains(name) ?: (name in done)
            val replace = name in dirty
            val isWorkout = name.endsWith(WorkoutStore.EXTENSION_SUFFIX)
            val summaryName = file.nameWithoutExtension + ".json"
            val summaryMissing = !isWorkout && remoteNames != null && summaryName !in remoteNames
            if (inRepo && !replace && !summaryMissing) continue

            try {
                val year = yearOf(name)
                if (isWorkout) {
                    // Screenshot workout stored beside the rides: uploaded as is, never overwritten.
                    val r = repo.putNew("rides/$year/$name", file.readBytes(), "Add workout $name")
                    if (r == PutResult.CREATED) uploaded++ else existed++
                } else {
                    val summary = file.inputStream().use { RideSummaries.summarize(GpxParser.parse(it)) }
                    val json = summary?.let { RideSummaryJson.toJson(name, it).toByteArray(Charsets.UTF_8) }
                    val gpxPath = "rides/$year/$name"
                    val jsonPath = "rides/$year/$summaryName"
                    if (replace) {
                        repo.putOrUpdate(gpxPath, file.readBytes(), "Update ride $name")
                        if (json != null) repo.putOrUpdate(jsonPath, json, "Update summary $name")
                        uploaded++
                    } else {
                        if (inRepo) {
                            existed++ // only the summary was missing
                        } else {
                            val r = repo.putNew(gpxPath, file.readBytes(), "Add ride $name")
                            if (r == PutResult.CREATED) uploaded++ else existed++
                        }
                        if (json != null && (remoteNames == null || summaryName !in remoteNames)) {
                            repo.putNew(jsonPath, json, "Add summary $name")
                        }
                    }
                }
                done.add(name)
                dirty.remove(name)
                p.edit().putStringSet(KEY, done).putStringSet(DIRTY, dirty).apply()
            } catch (e: GitHubException) {
                failed.add("$name: ${e.message}")
                // Auth/permission problems affect every file; stop instead of repeating the failure.
                if (e.code == 401 || e.code == 403 || e.code == 404) break
            } catch (e: Exception) {
                failed.add("$name: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return SyncReport(uploaded, existed, failed)
    }

    /** Tells the other phones about rides deleted here, so they do not pull them back. */
    fun pushTombstones(context: Context, repo: GitHubDataRepo): List<String> {
        val lines = mutableListOf<String>()
        for (name in SyncState.pendingTombstones(context)) {
            try {
                repo.putNew(
                    "rides/${yearOf(name)}/$name${PullPlanner.TOMBSTONE_SUFFIX}",
                    "deleted".toByteArray(Charsets.UTF_8),
                    "Mark $name deleted",
                )
                SyncState.tombstoneUploaded(context, name)
                lines.add("Deletion of $name shared with other phones")
            } catch (e: GitHubException) {
                lines.add("$name deletion not shared yet: ${e.message}")
                if (e.code == 401 || e.code == 403 || e.code == 404) break
            } catch (e: Exception) {
                lines.add("$name deletion not shared yet: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return lines
    }

    fun describe(r: SyncReport): String = buildString {
        append(String.format(Locale.US, "Uploaded %d, already in repo %d", r.uploaded, r.alreadyThere))
        if (r.failed.isNotEmpty()) append("\nFailed:\n").append(r.failed.joinToString("\n"))
    }
}

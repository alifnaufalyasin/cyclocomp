package com.aliven.cyclocomp.analytics

import android.content.Context
import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.RideRecord
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.TrainingStats
import com.aliven.cyclocomp.sync.StoredWorkout
import com.aliven.cyclocomp.sync.WorkoutJson
import com.aliven.cyclocomp.sync.WorkoutStore
import java.io.File

/** Heart-rate settings for load calculation. Plain preferences: not secret. */
object TrainingSettings {
    private const val PREFS = "cyclocomp_training"

    fun restHr(context: Context): Int =
        prefs(context).getInt("rest_hr", TrainingStats.DEFAULT_REST_HR)

    fun maxHr(context: Context): Int =
        prefs(context).getInt("max_hr", TrainingStats.DEFAULT_MAX_HR)

    /** 0 until the user saves their own values; used to decide which phone's settings are newer. */
    fun updatedMs(context: Context): Long = prefs(context).getLong("updated_ms", 0L)

    fun save(context: Context, restHr: Int, maxHr: Int, updatedMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putInt("rest_hr", restHr).putInt("max_hr", maxHr).putLong("updated_ms", updatedMs).apply()
    }

    /** Resting below max, both in a plausible human range. */
    fun isValid(restHr: Int, maxHr: Int) = restHr in 30..100 && maxHr in 120..230 && restHr < maxHr

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object RideRecords {
    /**
     * One record per ride GPX, with heart rate and calories taken from the matching Huawei
     * screenshot workout when the GPX has none. A screenshot workout with no matching ride
     * (e.g. done without the phone) still counts as its own record.
     */
    fun load(dir: File): List<RideRecord> {
        val workouts = WorkoutStore.files(dir).mapNotNull { f ->
            try { WorkoutJson.fromJson(f.readText()) } catch (e: Exception) { null }
        }
        val used = mutableSetOf<StoredWorkout>()
        val records = mutableListOf<RideRecord>()

        for (f in dir.listFiles { x -> x.extension == "gpx" }.orEmpty()) {
            val s = (try {
                f.inputStream().use { RideSummaries.summarize(GpxParser.parse(it)) }
            } catch (e: Exception) {
                null
            }) ?: continue
            val w = workouts.filter { WorkoutStore.matches(it, s.startMs, s.distanceM) }
                .minByOrNull { kotlin.math.abs((it.startEpochMs ?: 0) - s.startMs) }
            if (w != null) used.add(w)
            records.add(
                RideRecord(
                    startMs = s.startMs, distanceM = s.distanceM, movingMs = s.movingMs, ascentM = s.ascentM,
                    avgHr = s.avgHr ?: w?.workout?.avgHr, kcal = w?.workout?.kcal,
                )
            )
        }

        for (w in workouts) {
            if (w in used) continue
            val start = w.startEpochMs ?: continue
            val d = w.workout
            records.add(
                RideRecord(
                    startMs = start, distanceM = (d.distanceKm ?: 0.0) * 1000, movingMs = (d.durationS ?: 0) * 1000L,
                    ascentM = 0.0, avgHr = d.avgHr, kcal = d.kcal,
                )
            )
        }
        return records.sortedBy { it.startMs }
    }
}

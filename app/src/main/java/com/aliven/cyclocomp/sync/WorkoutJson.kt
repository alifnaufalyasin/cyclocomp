package com.aliven.cyclocomp.sync

import com.aliven.cyclocomp.core.HuaweiWorkout
import com.aliven.cyclocomp.core.ImportPlanner
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** A workout read from a Huawei screenshot, with its start as an absolute time. */
class StoredWorkout(val workout: HuaweiWorkout, val startEpochMs: Long?)

/**
 * The file the capture phone uploads to `inbox/<phone>/` after the user confirmed the values.
 * It never contains the user's name, place names or the image itself.
 */
object WorkoutJson {
    const val SOURCE = "huawei-screenshot"
    const val SUFFIX = "_workout.json"

    fun toJson(w: HuaweiWorkout, device: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val o = JSONObject()
        o.put("schema", 1)
        o.put("source", SOURCE)
        o.put("device", device)
        w.startLocal?.let {
            o.put("start_local", it.toString())
            o.put("zone", zone.id)
            o.put("start_epoch_ms", it.atZone(zone).toInstant().toEpochMilli())
        }
        w.activity?.let { o.put("activity", it) }
        w.distanceKm?.let { o.put("distance_km", it) }
        w.durationS?.let { o.put("duration_s", it) }
        w.avgSpeedKmh?.let { o.put("avg_speed_kmh", it) }
        w.maxSpeedKmh?.let { o.put("max_speed_kmh", it) }
        w.kcal?.let { o.put("kcal", it) }
        w.avgHr?.let { o.put("avg_hr", it) }
        w.maxHr?.let { o.put("max_hr", it) }
        if (w.zoneMinutes.isNotEmpty()) o.put("zones_min", JSONObject(w.zoneMinutes as Map<*, *>))
        if (w.hrRecoveryStart != null || w.hrRecoveryEnd != null || w.hrRecoveryDrop != null) {
            val r = JSONObject()
            w.hrRecoveryStart?.let { r.put("start", it) }
            w.hrRecoveryEnd?.let { r.put("end", it) }
            w.hrRecoveryDrop?.let { r.put("drop", it) }
            o.put("hr_recovery", r)
        }
        w.aerobicStress?.let { o.put("aerobic_stress", it) }
        w.stressLevel?.let { o.put("stress_level", it) }
        w.recoveryHours?.let { o.put("recovery_h", it) }
        w.watchModel?.let { o.put("watch", it) }
        return o.toString(2)
    }

    /** Null when the text is not a workout file written by [toJson]. */
    fun fromJson(text: String): StoredWorkout? = try {
        val o = JSONObject(text)
        if (o.optString("source") != SOURCE) null
        else {
            val zones = o.optJSONObject("zones_min")?.let { z ->
                z.keys().asSequence().associateWith { z.getInt(it) }
            } ?: emptyMap()
            val rec = o.optJSONObject("hr_recovery")
            val epoch = if (o.has("start_epoch_ms")) o.getLong("start_epoch_ms") else null
            StoredWorkout(
                HuaweiWorkout(
                    activity = o.optString("activity").ifEmpty { null },
                    startLocal = o.optString("start_local").ifEmpty { null }?.let { LocalDateTime.parse(it) },
                    distanceKm = if (o.has("distance_km")) o.getDouble("distance_km") else null,
                    durationS = if (o.has("duration_s")) o.getInt("duration_s") else null,
                    avgSpeedKmh = if (o.has("avg_speed_kmh")) o.getDouble("avg_speed_kmh") else null,
                    maxSpeedKmh = if (o.has("max_speed_kmh")) o.getDouble("max_speed_kmh") else null,
                    kcal = if (o.has("kcal")) o.getInt("kcal") else null,
                    avgHr = if (o.has("avg_hr")) o.getInt("avg_hr") else null,
                    maxHr = if (o.has("max_hr")) o.getInt("max_hr") else null,
                    zoneMinutes = zones,
                    hrRecoveryStart = rec?.let { if (it.has("start")) it.getInt("start") else null },
                    hrRecoveryEnd = rec?.let { if (it.has("end")) it.getInt("end") else null },
                    hrRecoveryDrop = rec?.let { if (it.has("drop")) it.getInt("drop") else null },
                    aerobicStress = if (o.has("aerobic_stress")) o.getDouble("aerobic_stress") else null,
                    stressLevel = o.optString("stress_level").ifEmpty { null },
                    recoveryHours = if (o.has("recovery_h")) o.getInt("recovery_h") else null,
                    watchModel = o.optString("watch").ifEmpty { null },
                ),
                epoch,
            )
        }
    } catch (e: Exception) {
        null
    }
}

/** Workouts stored next to the rides as `<start stamp>.workout.json`. */
object WorkoutStore {
    const val EXTENSION_SUFFIX = ".workout.json"

    fun files(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(EXTENSION_SUFFIX) }.orEmpty().sortedBy { it.name }

    /**
     * The workout that describes the ride starting at [rideStartMs] covering [rideDistanceM], if any.
     * Same rule as GPX matching: start within 10 minutes and distance within 15%; duration is ignored.
     */
    fun findFor(dir: File, rideStartMs: Long, rideDistanceM: Double): StoredWorkout? =
        files(dir).mapNotNull { f -> try { WorkoutJson.fromJson(f.readText()) } catch (e: Exception) { null } }
            .filter { matches(it, rideStartMs, rideDistanceM) }
            .minByOrNull { abs((it.startEpochMs ?: 0) - rideStartMs) }

    fun matches(w: StoredWorkout, rideStartMs: Long, rideDistanceM: Double): Boolean {
        val start = w.startEpochMs ?: return false
        if (abs(start - rideStartMs) > ImportPlanner.MAX_START_DIFF_MS) return false
        val km = w.workout.distanceKm ?: return true // no distance read: start time alone decides
        val a = km * 1000.0
        val larger = maxOf(a, rideDistanceM)
        return larger > 0 && abs(a - rideDistanceM) / larger <= ImportPlanner.MAX_DISTANCE_RATIO
    }
}

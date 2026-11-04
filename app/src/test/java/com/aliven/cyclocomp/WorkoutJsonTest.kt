package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.HuaweiWorkout
import com.aliven.cyclocomp.sync.StoredWorkout
import com.aliven.cyclocomp.sync.WorkoutJson
import com.aliven.cyclocomp.sync.WorkoutStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

class WorkoutJsonTest {
    private val jakarta = ZoneId.of("Asia/Jakarta")
    private val start = LocalDateTime.of(2026, 11, 3, 6, 42)
    private val workout = HuaweiWorkout(
        activity = "Outdoor cycle", startLocal = start, distanceKm = 18.63, durationS = 3586,
        avgSpeedKmh = 18.7, maxSpeedKmh = 43.56, kcal = 469, avgHr = 143, maxHr = 170,
        zoneMinutes = linkedMapOf("Extreme" to 0, "Aerobic" to 23),
        hrRecoveryStart = 144, hrRecoveryEnd = 125, aerobicStress = 2.9, stressLevel = "Maintained",
        recoveryHours = 13, watchModel = "HUAWEI WATCH FIT3",
        warnings = listOf("not stored"),
    )
    private val startEpoch = start.atZone(jakarta).toInstant().toEpochMilli()

    @Test
    fun roundTripKeepsEverythingExceptWarnings() {
        val back = WorkoutJson.fromJson(WorkoutJson.toJson(workout, "capture-phone", jakarta))!!
        assertEquals(workout.copy(warnings = emptyList()), back.workout)
        assertEquals(startEpoch, back.startEpochMs)
    }

    @Test
    fun jsonContainsOnlyWorkoutValues() {
        val o = JSONObject(WorkoutJson.toJson(workout, "capture-phone", jakarta))
        assertEquals("huawei-screenshot", o.getString("source"))
        assertEquals("capture-phone", o.getString("device"))
        assertEquals("Asia/Jakarta", o.getString("zone"))
        assertEquals(startEpoch, o.getLong("start_epoch_ms"))
        assertEquals(23, o.getJSONObject("zones_min").getInt("Aerobic"))
        assertFalse(o.has("warnings"))
    }

    @Test
    fun missingFieldsAreOmittedAndComeBackNull() {
        val sparse = HuaweiWorkout(startLocal = start, kcal = 300)
        val o = JSONObject(WorkoutJson.toJson(sparse, "p", jakarta))
        assertFalse(o.has("distance_km"))
        assertFalse(o.has("hr_recovery"))
        val back = WorkoutJson.fromJson(o.toString())!!.workout
        assertNull(back.distanceKm)
        assertNull(back.hrRecoveryStart)
        assertTrue(back.zoneMinutes.isEmpty())
        assertEquals(300, back.kcal)
    }

    @Test
    fun otherJsonOrGarbageIsRejected() {
        assertNull(WorkoutJson.fromJson("""{"source":"something-else"}"""))
        assertNull(WorkoutJson.fromJson("not json"))
        assertNull(WorkoutJson.fromJson("{}"))
    }

    private fun stored(distanceKm: Double?, startMs: Long?) =
        StoredWorkout(HuaweiWorkout(distanceKm = distanceKm), startMs)

    @Test
    fun matchesByStartAndDistanceNotDuration() {
        val w = stored(18.63, startEpoch)
        assertTrue(WorkoutStore.matches(w, startEpoch + 60_000, 18_700.0))
        assertTrue(WorkoutStore.matches(w, startEpoch - 9 * 60_000, 18_700.0))
        assertFalse(WorkoutStore.matches(w, startEpoch + 11 * 60_000, 18_700.0)) // start too far
        assertFalse(WorkoutStore.matches(w, startEpoch, 30_000.0)) // distance too different
        assertTrue(WorkoutStore.matches(stored(null, startEpoch), startEpoch, 30_000.0)) // no distance: start decides
        assertFalse(WorkoutStore.matches(stored(18.63, null), startEpoch, 18_700.0)) // no start: cannot match
    }

    @Test
    fun findForPicksTheClosestStoredWorkout() {
        val dir = Files.createTempDirectory("cyclocomp-workouts").toFile()
        try {
            fun save(name: String, startMs: Long) = java.io.File(dir, name + WorkoutStore.EXTENSION_SUFFIX).writeText(
                WorkoutJson.toJson(workout.copy(startLocal = java.time.Instant.ofEpochMilli(startMs).atZone(jakarta).toLocalDateTime()), "p", jakarta)
            )
            save("near", startEpoch + 2 * 60_000)
            save("nearer", startEpoch + 30_000)
            save("far", startEpoch + 3 * 3_600_000)
            java.io.File(dir, "unrelated.json").writeText("{}")

            val found = WorkoutStore.findFor(dir, startEpoch, 18_700.0)!!
            assertEquals(startEpoch + 30_000, found.startEpochMs)
            assertNull(WorkoutStore.findFor(dir, startEpoch - 5 * 3_600_000, 18_700.0))
        } finally {
            dir.deleteRecursively()
        }
    }
}

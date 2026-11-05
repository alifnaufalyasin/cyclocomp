package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.RideRecord
import com.aliven.cyclocomp.core.TrainingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TrainingStatsTest {
    // Wednesday 2026-11-04 12:00 UTC; the current week starts Monday 2026-11-02.
    private val now = Instant.parse("2026-11-04T12:00:00Z")

    private fun ride(day: String, km: Double, minutes: Int = 60, hr: Int? = 143, kcal: Int? = null) = RideRecord(
        startMs = Instant.parse("${day}T08:00:00Z").toEpochMilli(),
        distanceM = km * 1000, movingMs = minutes * 60_000L, ascentM = 50.0, avgHr = hr, kcal = kcal,
    )

    private fun compute(vararg rides: RideRecord) = TrainingStats.compute(rides.toList(), now, ZoneOffset.UTC)

    @Test
    fun trimpMatchesBanisterFormulaAndHandlesDegenerateInput() {
        // ratio (143-60)/(190-60) = 0.638; 60 min -> about 83.5
        assertEquals(83.53, TrainingStats.trimp(60.0, 143, 60, 190), 0.05)
        assertEquals(0.0, TrainingStats.trimp(60.0, 143, 190, 190), 0.0)
        assertEquals(0.0, TrainingStats.trimp(0.0, 143, 60, 190), 0.0)
        assertEquals(0.0, TrainingStats.trimp(60.0, 50, 60, 190), 0.0) // below resting
        assertTrue(TrainingStats.trimp(60.0, 250, 60, 190) > 0) // clamped, not NaN
    }

    @Test
    fun weeksStartOnMonday() {
        assertEquals(LocalDate.of(2026, 10, 26), TrainingStats.weekStart(LocalDate.of(2026, 11, 1))) // Sunday
        assertEquals(LocalDate.of(2026, 11, 2), TrainingStats.weekStart(LocalDate.of(2026, 11, 2))) // Monday
    }

    @Test
    fun ridesAreGroupedIntoEightWeeksEndingWithTheCurrentOne() {
        val o = compute(ride("2026-10-20", 20.0), ride("2026-10-27", 24.0, kcal = 400), ride("2026-11-03", 18.63))
        assertEquals(8, o.weeks.size)
        assertEquals(LocalDate.of(2026, 11, 2), o.weeks.last().weekStart)
        assertEquals(18.63, o.weeks.last().distanceKm, 1e-9)
        assertEquals(24.0, o.weeks[6].distanceKm, 1e-9)
        assertEquals(400, o.weeks[6].kcal)
        assertEquals(20.0, o.weeks[5].distanceKm, 1e-9)
        assertEquals(0, o.weeks[0].rides)
        assertEquals(60, o.weeks.last().movingMin)
    }

    @Test
    fun acuteChronicRatioAndNote() {
        val o = compute(ride("2026-10-20", 20.0), ride("2026-10-27", 24.0), ride("2026-11-03", 18.63))
        val one = TrainingStats.trimp(60.0, 143, 60, 190)
        assertEquals(one, o.acuteLoad, 1e-6) // only the Nov 3 ride is in the last 7 days
        assertEquals(one * 3 / 4, o.chronicLoad, 1e-6) // three rides over 28 days, per week
        assertEquals(1.333, o.ratio!!, 0.001)
        assertTrue(o.notes.any { it.contains("rising quickly") })
        assertTrue(o.notes.any { it.contains("up 20%") })
    }

    @Test
    fun bigWeeklyJumpGetsARuleOfThumbNote() {
        val o = compute(ride("2026-10-20", 20.0), ride("2026-10-27", 40.0))
        assertTrue(o.notes.any { it.contains("up 100%") && it.contains("rule of thumb") })
    }

    @Test
    fun noRecentRidesIsReported() {
        val o = compute(ride("2026-10-20", 20.0))
        assertTrue(o.notes.any { it.contains("No rides in the last 2 weeks") })
    }

    @Test
    fun ridesWithoutHeartRateAreCountedButLeftOutOfLoad() {
        val o = compute(ride("2026-11-03", 18.63, hr = null))
        assertEquals(0.0, o.weeks.last().load, 0.0)
        assertEquals(1, o.weeks.last().ridesWithoutHr)
        assertNull(o.ratio)
        assertTrue(o.notes.any { it.contains("needs heart rate") })
        assertTrue(o.notes.any { it.contains("no heart rate") })
    }

    @Test
    fun emptyHistoryIsFine() {
        val o = compute()
        assertEquals(8, o.weeks.size)
        assertEquals(0.0, o.acuteLoad, 0.0)
        assertNotNull(o.notes)
        assertTrue(o.notes.any { it.contains("No rides in the last 2 weeks") })
    }
}

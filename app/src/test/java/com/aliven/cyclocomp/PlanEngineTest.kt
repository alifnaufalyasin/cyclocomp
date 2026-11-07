package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.Overview
import com.aliven.cyclocomp.core.PlanEngine
import com.aliven.cyclocomp.core.PlanGoal
import com.aliven.cyclocomp.core.WeekStats
import com.aliven.cyclocomp.sync.PlanSettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanEngineTest {
    // The current (partial) week starts Monday 2026-11-02.
    private val currentWeek = LocalDate.of(2026, 11, 2)

    /** Weekly distances oldest first; the last value is the week in progress. */
    private fun overview(vararg km: Double, acute: Double = 0.0, chronic: Double = 0.0): Overview {
        val weeks = km.mapIndexed { i, d ->
            WeekStats(
                weekStart = currentWeek.minusWeeks((km.size - 1 - i).toLong()),
                rides = if (d > 0) 1 else 0, distanceKm = d, movingMin = 0, ascentM = 0,
                load = 0.0, ridesWithoutHr = 0, kcal = 0,
            )
        }
        return Overview(weeks, acute, chronic, emptyList())
    }

    private fun goal(
        km: Double = 40.0, rides: Int = 3, ramp: Int = 10,
        startedOn: LocalDate = LocalDate.of(2026, 10, 5), // the current week is week 4 of the plan
        startKm: Double = 15.0,
    ) = PlanGoal(km, rides, ramp, startedOn, startKm)

    private val steady = overview(0.0, 0.0, 0.0, 0.0, 20.0, 22.0, 24.0, 6.0)

    @Test
    fun buildsTenPercentOnLastWeeksActualAndCarriesOnFromThePlan() {
        val w = PlanEngine.outlook(goal(), steady).weeks
        assertEquals(5, w.size)
        assertEquals(currentWeek, w[0].weekStart)
        assertEquals(listOf(26.4, 29.0, 31.9), w.take(3).map { it.targetKm })
        assertTrue(w[0].note.contains("24.0"))
    }

    @Test
    fun everyFourthWeekOfThePlanIsARecoveryWeek() {
        val w = PlanEngine.outlook(goal(), steady).weeks
        assertTrue(w[3].recovery) // plan week index 7, the 8th week
        assertEquals(24.0, w[3].targetKm, 0.05) // 75% of the week before (31.94)
        assertTrue(w[3].note.contains("Recovery"))
        assertFalse(w[4].recovery)
        assertEquals(26.4, w[4].targetKm, 0.05) // builds again from the recovery week
    }

    @Test
    fun recoveryCanFallOnTheWeekInProgress() {
        val w = PlanEngine.outlook(goal(startedOn = LocalDate.of(2026, 10, 12)), steady).weeks
        assertTrue(w[0].recovery)
        assertEquals(18.0, w[0].targetKm, 0.05)
    }

    @Test
    fun neverPlansAboveTheGoalAndReportsWhenItIsReached() {
        val out = PlanEngine.outlook(goal(km = 25.0), steady)
        assertEquals(25.0, out.weeks[0].targetKm, 0.0)
        assertTrue(out.weeks[0].note.contains("Goal distance reached"))
        assertEquals(0, out.weeksToGoal)
        assertTrue(out.weeks.all { it.targetKm <= 25.0 })
    }

    @Test
    fun weeksToGoalCountsFromTheCurrentWeek() {
        // 26.4, 29.04, then 31.9 is capped to 30: reached in the third planned week (index 2).
        assertEquals(2, PlanEngine.outlook(goal(km = 30.0), steady).weeksToGoal)
        assertNull(PlanEngine.outlook(goal(km = 200.0), steady).weeksToGoal)
    }

    @Test
    fun highLoadHoldsTheWeekAndRisingLoadHalvesTheIncrease() {
        val high = PlanEngine.outlook(goal(), overview(0.0, 0.0, 0.0, 0.0, 20.0, 22.0, 24.0, 6.0, acute = 200.0, chronic = 100.0))
        assertEquals(24.0, high.weeks[0].targetKm, 0.0)
        assertTrue(high.weeks[0].note.contains("Held"))

        val rising = PlanEngine.outlook(goal(), overview(0.0, 0.0, 0.0, 0.0, 20.0, 22.0, 24.0, 6.0, acute = 140.0, chronic = 100.0))
        assertEquals(25.2, rising.weeks[0].targetKm, 0.05)
        assertTrue(rising.weeks[0].note.contains("Half the usual"))
    }

    @Test
    fun aWeekWithoutRidesRestartsBelowTheRecentAverage() {
        val w = PlanEngine.outlook(goal(), overview(0.0, 0.0, 20.0, 22.0, 24.0, 0.0, 0.0)).weeks[0]
        // The last complete week had no rides; the recent weeks that did were 20, 22 and 24 km (average 22).
        assertEquals(15.4, w.targetKm, 0.05) // 70% of 22
        assertTrue(w.note.contains("Restart"))
    }

    @Test
    fun noHistoryStartsAtTheStartingDistanceAndBuildsFromThere() {
        val w = PlanEngine.outlook(goal(), overview(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).weeks
        assertEquals(15.0, w[0].targetKm, 0.0)
        assertTrue(w[0].note.contains("Starting point"))
        assertEquals(16.5, w[1].targetKm, 0.05)
    }

    @Test
    fun goalSurvivesTheJsonRoundTripUsedForSyncingBetweenPhones() {
        val g = PlanGoal(42.5, 4, 8, LocalDate.of(2026, 10, 5), 15.5)
        val back = PlanSettingsJson.fromJson(PlanSettingsJson.toJson(PlanSettingsJson.Values(g, 123L)))!!
        assertEquals(g, back.goal)
        assertEquals(123L, back.updatedMs)
        assertNull(PlanSettingsJson.fromJson("{}"))
        assertNull(PlanSettingsJson.fromJson("not json"))
    }

    @Test
    fun ridesAndLongRideAreSplit() {
        val w = PlanEngine.outlook(goal(rides = 3), steady).weeks[0]
        assertEquals(3, w.rides)
        assertEquals(10.6, w.longRideKm, 0.05) // 40% of 26.4

        val one = PlanEngine.outlook(goal(rides = 1), steady).weeks[0]
        assertEquals(one.targetKm, one.longRideKm, 0.0)
        assertEquals(1, PlanEngine.outlook(goal(rides = 0), steady).weeks[0].rides)
    }
}

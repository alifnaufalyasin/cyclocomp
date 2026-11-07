package com.aliven.cyclocomp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    @Test fun noPermissionSuggestsNothing() {
        assertEquals(emptyList<ReliabilityAdvice>(), Reliability.advice(LocationLevel.NONE, false))
    }

    @Test fun whileInUseAsksForAlways() {
        assertEquals(
            listOf(ReliabilityAdvice.ALLOW_ALL_THE_TIME),
            Reliability.advice(LocationLevel.WHILE_IN_USE, true),
        )
    }

    @Test fun batterySavingIsFlaggedEvenWithAlways() {
        assertEquals(
            listOf(ReliabilityAdvice.STOP_BATTERY_SAVING),
            Reliability.advice(LocationLevel.ALWAYS, false),
        )
    }

    @Test fun bothProblemsAreListedInOrder() {
        assertEquals(
            listOf(ReliabilityAdvice.ALLOW_ALL_THE_TIME, ReliabilityAdvice.STOP_BATTERY_SAVING),
            Reliability.advice(LocationLevel.WHILE_IN_USE, false),
        )
    }

    @Test fun gpsLostOnlyAfterThreshold() {
        assertFalse(Reliability.gpsLost(100_000, 0))
        assertFalse(Reliability.gpsLost(100_000, 80_000))
        assertTrue(Reliability.gpsLost(100_000, 60_000))
    }
}

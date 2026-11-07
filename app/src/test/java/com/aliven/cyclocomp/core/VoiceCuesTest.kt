package com.aliven.cyclocomp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCuesTest {
    private val origin = LatLon(0.0, 0.0)

    private fun step(type: String, modifier: String?, name: String, startAlongM: Double) =
        NavStep(type, modifier, name, 100.0, null, startAlongM, origin)

    // depart -> turn left onto Main Street at 500 m -> turn right onto Park Road at 1000 m -> arrive
    private fun route() = NavRoute(
        points = listOf(origin, LatLon(0.0, 0.02)),
        steps = listOf(
            step("depart", null, "", 0.0),
            step("turn", "left", "Main Street", 500.0),
            step("turn", "right", "Park Road", 1000.0),
            step("arrive", null, "", 1500.0),
        ),
        distanceM = 1500.0,
        durationS = 300.0,
    )

    // A position [toNext] metres before the maneuver that ends step [stepIndex].
    private fun at(stepIndex: Int, toNext: Double) =
        NavProgress(alongM = 0.0, offRouteM = 0.0, remainingM = 1000.0, stepIndex = stepIndex, toNextManeuverM = toNext, segment = 0)

    private fun VoiceCueEngine.go(route: NavRoute?, p: NavProgress?, arrived: Boolean = false, rerouting: Boolean = false, off: Boolean = false) =
        update(route, p, arrived, rerouting, off)

    @Test fun nothingIsSaidFarFromTheTurn() {
        val r = route()
        assertEquals(emptyList<String>(), VoiceCueEngine().go(r, at(0, 400.0)))
    }

    @Test fun announcesAheadOnceThenAtTheTurn() {
        val e = VoiceCueEngine()
        val r = route()
        assertEquals(listOf("In 140 meters, turn left onto Main Street"), e.go(r, at(0, 140.0)))
        assertEquals(emptyList<String>(), e.go(r, at(0, 120.0)))
        assertEquals(emptyList<String>(), e.go(r, at(0, 60.0)))
        assertEquals(listOf("Turn left onto Main Street"), e.go(r, at(0, 35.0)))
        assertEquals(emptyList<String>(), e.go(r, at(0, 10.0)))
    }

    @Test fun aTurnFirstSeenCloseIsAnnouncedOnlyOnce() {
        val e = VoiceCueEngine()
        val r = route()
        assertEquals(listOf("Turn left onto Main Street"), e.go(r, at(0, 30.0)))
        assertEquals(emptyList<String>(), e.go(r, at(0, 15.0)))
    }

    @Test fun eachTurnGetsItsOwnAnnouncement() {
        val e = VoiceCueEngine()
        val r = route()
        e.go(r, at(0, 140.0))
        e.go(r, at(0, 30.0))
        assertEquals(listOf("In 100 meters, turn right onto Park Road"), e.go(r, at(1, 100.0)))
        assertEquals(listOf("Turn right onto Park Road"), e.go(r, at(1, 20.0)))
    }

    @Test fun theFinalStepIsAnnouncedAsArriving() {
        val e = VoiceCueEngine()
        val r = route()
        assertEquals(listOf("In 90 meters, arrive at your destination"), e.go(r, at(2, 90.0)))
    }

    @Test fun arrivalIsSaidOnceAndStopsTurnCues() {
        val e = VoiceCueEngine()
        val r = route()
        assertEquals(listOf("You have arrived"), e.go(r, at(2, 5.0), arrived = true))
        assertEquals(emptyList<String>(), e.go(r, at(2, 3.0), arrived = true))
    }

    @Test fun reroutingAndOffCourseAreSaidOncePerOccurrence() {
        val e = VoiceCueEngine()
        val r = route()
        assertEquals(listOf("Rerouting"), e.go(r, at(0, 400.0), rerouting = true))
        assertEquals(emptyList<String>(), e.go(r, at(0, 400.0), rerouting = true))
        assertEquals(emptyList<String>(), e.go(r, at(0, 400.0), rerouting = false))
        assertEquals(listOf("Rerouting"), e.go(r, at(0, 400.0), rerouting = true))

        assertEquals(listOf("You are off the course"), e.go(r, at(0, 400.0), off = true))
        assertEquals(emptyList<String>(), e.go(r, at(0, 400.0), off = true))
        assertEquals(emptyList<String>(), e.go(r, at(0, 400.0), off = false))
        assertEquals(listOf("You are off the course"), e.go(r, at(0, 400.0), off = true))
    }

    @Test fun aNewRouteStartsAnnouncingAgain() {
        val e = VoiceCueEngine()
        val first = route()
        e.go(first, at(0, 140.0))
        val second = route() // a different object, as after a reroute
        assertEquals(listOf("In 140 meters, turn left onto Main Street"), e.go(second, at(0, 140.0)))
    }

    @Test fun noRouteOrProgressMeansSilence() {
        val e = VoiceCueEngine()
        assertEquals(emptyList<String>(), e.go(null, null))
        assertEquals(emptyList<String>(), e.go(route(), null))
    }

    @Test fun distancesAreSpokenInWords() {
        assertEquals("150 meters", VoiceCueEngine.spokenDistance(147.0))
        assertEquals("1.2 kilometers", VoiceCueEngine.spokenDistance(1234.0))
        assertTrue(VoiceCueEngine.spokenDistance(44.0).endsWith("meters"))
    }
}

package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.Course
import com.aliven.cyclocomp.core.CourseParser
import com.aliven.cyclocomp.core.CourseSteps
import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.RouteGeometry
import com.aliven.cyclocomp.core.RouteNames
import com.aliven.cyclocomp.sync.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CourseTest {
    private fun parse(xml: String) = CourseParser.parse(xml.byteInputStream())

    // ---------------------------------------------------------------- reading GPX

    @Test
    fun routePointsWithoutTimesAreEnough() {
        val c = parse(
            """<gpx version="1.1"><rte><name>Ciamis loop</name>
               <rtept lat="-7.30" lon="108.20"/><rtept lat="-7.31" lon="108.21"/><rtept lat="-7.32" lon="108.22"/>
               </rte></gpx>"""
        )!!
        assertEquals("Ciamis loop", c.name)
        assertEquals(listOf(LatLon(-7.30, 108.20), LatLon(-7.31, 108.21), LatLon(-7.32, 108.22)), c.points)
    }

    @Test
    fun trackPointsAreUsedWhenThereIsNoRouteEvenWithoutTimeOrWithExtensions() {
        val c = parse(
            """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="x"><metadata><name>From a watch</name></metadata>
               <trk><trkseg>
                 <trkpt lat="1.0" lon="2.0"><ele>5</ele><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>90</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
                 <trkpt lat="1.1" lon="2.1"/>
               </trkseg></trk></gpx>"""
        )!!
        assertEquals("From a watch", c.name)
        assertEquals(2, c.points.size)
    }

    @Test
    fun routePointsWinWhenBothExistAndWaypointNamesAreNeverTheRouteName() {
        val c = parse(
            """<gpx><wpt lat="9" lon="9"><name>Coffee stop</name></wpt>
               <rte><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte>
               <trk><name>Track name</name><trkseg><trkpt lat="5" lon="5"/><trkpt lat="6" lon="6"/></trkseg></trk></gpx>"""
        )!!
        assertEquals(listOf(LatLon(1.0, 1.0), LatLon(2.0, 2.0)), c.points)
        assertEquals("Track name", c.name) // the waypoint's name is ignored
    }

    @Test
    fun aFileWithoutARouteGivesNothing() {
        assertNull(parse("not xml"))
        assertNull(parse("""<gpx><wpt lat="1" lon="1"/></gpx>"""))
        assertNull(parse("""<gpx><rte><rtept lat="1" lon="1"/></rte></gpx>""")) // one point is not a route
        assertNull(parse("""<gpx><rte><rtept lat="95" lon="1"/><rtept lat="1" lon="500"/></rte></gpx>""")) // impossible coordinates
        // An external entity declaration must not be processed.
        assertNull(parse("""<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x "y">]><gpx><rte><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte></gpx>"""))
    }

    @Test
    fun aNamelessRouteGetsADefaultName() {
        assertEquals("Route", parse("""<gpx><rte><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte></gpx>""")!!.name)
    }

    // ---------------------------------------------------------------- turn cues

    private val lat0 = -7.3
    private val mPerDegLon = cos(Math.toRadians(lat0)) * 111_320.0
    private val mPerDegLat = 110_540.0

    /** A path built from legs of 10 m steps: (compass bearing in degrees, length in metres). */
    private fun path(vararg legs: Pair<Double, Int>): Course {
        val pts = mutableListOf(LatLon(lat0, 108.0))
        for ((bearing, length) in legs) {
            repeat(length / 10) {
                val last = pts.last()
                val dx = 10 * sin(Math.toRadians(bearing))
                val dy = 10 * cos(Math.toRadians(bearing))
                pts.add(LatLon(last.lat + dy / mPerDegLat, last.lon + dx / mPerDegLon))
            }
        }
        return Course("Test", pts)
    }

    private fun turns(c: Course) = CourseSteps.toNavRoute(c).steps.filter { it.type == "turn" }

    @Test
    fun aStraightRouteHasOnlyDepartAndArrive() {
        val steps = CourseSteps.toNavRoute(path(90.0 to 500)).steps
        assertEquals(listOf("depart", "arrive"), steps.map { it.type })
    }

    @Test
    fun aCornerToTheLeftAndToTheRight() {
        val left = turns(path(90.0 to 200, 0.0 to 200)) // east, then north
        assertEquals(1, left.size)
        assertEquals("left", left[0].modifier)
        assertEquals(200.0, left[0].startAlongM, 3.0)

        val right = turns(path(90.0 to 200, 180.0 to 200)) // east, then south
        assertEquals(listOf("right"), right.map { it.modifier })
    }

    @Test
    fun turnSharpnessIsDescribed() {
        assertEquals("slight right", turns(path(90.0 to 200, 135.0 to 200)).single().modifier) // 45 degrees
        assertEquals("sharp right", turns(path(90.0 to 200, 225.0 to 200)).single().modifier) // 135 degrees
        assertEquals("uturn", turns(path(90.0 to 200, 270.0 to 200)).single().modifier)
    }

    @Test
    fun gpsNoiseAndGentleCurvesAreNotTurns() {
        // A straight line wobbling by a few metres either side.
        val wobble = Course(
            "Noisy",
            (0..60).map { i -> LatLon(lat0 + (if (i % 2 == 0) 3.0 else -3.0) / mPerDegLat, 108.0 + i * 10 / mPerDegLon) },
        )
        assertTrue(turns(wobble).isEmpty())

        // A 90 degree bend spread over a 230 m radius arc is a curve, not a corner.
        val r = 230.0
        val arc = Course(
            "Arc",
            (0..18).map { i ->
                val a = Math.toRadians(i * 5.0)
                LatLon(lat0 + r * (1 - cos(a)) / mPerDegLat, 108.0 + r * sin(a) / mPerDegLon)
            },
        )
        assertTrue(turns(arc).isEmpty())
    }

    @Test
    fun stepsRunAlongTheRouteAndAddUp() {
        val route = CourseSteps.toNavRoute(path(90.0 to 200, 0.0 to 200))
        assertEquals(400.0, route.distanceM, 4.0)
        assertEquals(listOf("depart", "turn", "arrive"), route.steps.map { it.type })
        assertEquals(0.0, route.steps.first().startAlongM, 0.0)
        assertEquals(route.distanceM, route.steps.last().startAlongM, 0.001)
        assertEquals(route.distanceM, route.steps.sumOf { it.distanceM }, 0.001)
        assertTrue(route.steps.zipWithNext().all { (a, b) -> a.startAlongM <= b.startAlongM })
        assertEquals("Test", route.steps.first().name)
    }

    @Test
    fun theRouteCanBeFollowedWithTheNormalProgressLogic() {
        val route = CourseSteps.toNavRoute(path(90.0 to 200, 0.0 to 200))
        val g = RouteGeometry(route)
        // Halfway along the first leg: the next cue is the left turn, about 100 m ahead.
        val p = g.progress(LatLon(lat0, 108.0 + 100 / mPerDegLon))
        assertEquals(0, p.stepIndex)
        assertEquals(100.0, p.toNextManeuverM, 5.0)
        assertEquals(route.steps[1].type, "turn")
        // 120 m south of the line is clearly off the course.
        assertTrue(g.progress(LatLon(lat0 - 120 / mPerDegLat, 108.0 + 100 / mPerDegLon)).offRouteM > 100)
    }

    @Test
    fun veryLongRoutesAreThinnedButKeepTheirEnds() {
        val long = Course("Long", (0..20_000).map { LatLon(lat0, 108.0 + it * 1.0 / mPerDegLon) })
        val route = CourseSteps.toNavRoute(long)
        assertTrue(route.points.size <= 4_000)
        assertEquals(long.points.first(), route.points.first())
        assertEquals(long.points.last(), route.points.last())
        assertEquals(20_000.0, route.distanceM, 60.0)
    }

    // ---------------------------------------------------------------- names and sync

    @Test
    fun routeFileNamesAreSafeAndUnique() {
        assertEquals("Ciamis_loop.gpx", RouteNames.fileName("Ciamis loop.gpx", emptySet()))
        assertEquals("Ciamis_loop-2.gpx", RouteNames.fileName("Ciamis loop", setOf("Ciamis_loop.gpx")))
        assertEquals("Ciamis_loop-3.gpx", RouteNames.fileName("Ciamis loop", setOf("Ciamis_loop.gpx", "Ciamis_loop-2.gpx")))
        assertEquals("route.gpx", RouteNames.fileName("///", emptySet()))
        assertFalse(RouteNames.fileName("../../etc/passwd", emptySet()).contains("/"))
        assertEquals("Ciamis loop", RouteNames.displayName("Ciamis_loop.gpx"))
    }

    @Test
    fun onlyPlainGpxNamesAreAcceptedFromTheStore() {
        assertTrue(RouteNames.isSafe("ok-1.gpx"))
        assertFalse(RouteNames.isSafe("../x.gpx"))
        assertFalse(RouteNames.isSafe("a..b.gpx"))
        assertFalse(RouteNames.isSafe("x.gpx.deleted"))
        assertFalse(RouteNames.isSafe("dir/x.gpx"))
        assertFalse(RouteNames.isSafe("x.txt"))
    }

    @Test
    fun routePlannerSharesRoutesAndDeletionsBetweenPhones() {
        val plan = RoutePlanner.plan(
            remoteNames = setOf("a.gpx", "b.gpx", "c.gpx", "c.gpx.deleted", "e.gpx", "evil..gpx"),
            localNames = setOf("a.gpx", "c.gpx", "d.gpx"),
            deletedHere = setOf("e.gpx"),
            pendingTombstones = setOf("z.gpx", "c.gpx"),
        )
        assertEquals(listOf("b.gpx"), plan.download) // new elsewhere; not c (deleted), e (deleted here), evil (unsafe name)
        assertEquals(listOf("c.gpx"), plan.deleteLocal) // deleted on another phone
        assertEquals(listOf("d.gpx"), plan.upload) // only here
        assertEquals(listOf("z.gpx"), plan.uploadTombstones) // c's marker is already in the store
    }

    @Test
    fun aDeletedRouteIsNeverUploadedAgain() {
        val plan = RoutePlanner.plan(setOf("x.gpx.deleted"), setOf("x.gpx"), emptySet(), emptySet())
        assertTrue(plan.upload.isEmpty())
        assertEquals(listOf("x.gpx"), plan.deleteLocal)
        assertNotNull(plan)
    }
}

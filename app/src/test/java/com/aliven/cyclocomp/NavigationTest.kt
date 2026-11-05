package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.NavRoute
import com.aliven.cyclocomp.core.NavStep
import com.aliven.cyclocomp.core.OffRouteDetector
import com.aliven.cyclocomp.core.OsrmParser
import com.aliven.cyclocomp.core.RouteGeometry
import com.aliven.cyclocomp.core.TurnText
import com.aliven.cyclocomp.nav.NavException
import com.aliven.cyclocomp.nav.OsrmAddress
import com.aliven.cyclocomp.nav.OsrmClient
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class NavigationTest {
    // A straight route east along the equator, 10 x 0.001 degrees (about 1113 m), no real place.
    private val coords = (0..10).joinToString(",") { "[${108.0 + it * 0.001},0.0]" }
    private val osrmJson = """
        {"code":"Ok","routes":[{"distance":1113.0,"duration":200.0,
         "geometry":{"type":"LineString","coordinates":[$coords]},
         "legs":[{"steps":[
           {"distance":556.5,"name":"Jalan A","maneuver":{"type":"depart","location":[108.0,0.0]}},
           {"distance":556.5,"name":"Jalan B","maneuver":{"type":"turn","modifier":"right","location":[108.005,0.0]}},
           {"distance":0.0,"name":"Jalan B","maneuver":{"type":"arrive","location":[108.01,0.0]}}
         ]}]}]}
    """.trimIndent()

    private val route get() = OsrmParser.parse(osrmJson)!!

    @Test
    fun parsesGeometryInLatLonOrderAndStepsWithCumulativeStart() {
        val r = route
        assertEquals(11, r.points.size)
        assertEquals(LatLon(0.0, 108.0), r.points.first()) // GeoJSON is lon,lat; the app uses lat,lon
        assertEquals(1113.0, r.distanceM, 0.0)
        assertEquals(3, r.steps.size)
        assertEquals(listOf(0.0, 556.5, 1113.0), r.steps.map { it.startAlongM })
        assertNull(r.steps[0].modifier)
        assertEquals("right", r.steps[1].modifier)
        assertEquals("arrive", r.steps[2].type)
    }

    @Test
    fun failedOrBrokenResponsesGiveNoRoute() {
        assertNull(OsrmParser.parse("""{"code":"NoRoute","message":"Impossible route between points"}"""))
        assertNull(OsrmParser.parse("not json"))
        assertEquals("Impossible route between points", OsrmParser.reason("""{"code":"NoRoute","message":"Impossible route between points"}"""))
        assertEquals("NoSegment", OsrmParser.reason("""{"code":"NoSegment"}"""))
        assertNull(OsrmParser.reason("""{"code":"Ok"}"""))
        assertNull(OsrmParser.reason("garbage"))
    }

    @Test
    fun progressAlongTheRouteAndCurrentStep() {
        val g = RouteGeometry(route)
        val early = g.progress(LatLon(0.0, 108.0025))
        assertEquals(278.3, early.alongM, 2.0)
        assertEquals(0.0, early.offRouteM, 1.0)
        assertEquals(0, early.stepIndex)
        assertEquals(556.5 - early.alongM, early.toNextManeuverM, 0.01)
        assertEquals(1113.0 - early.alongM, early.remainingM, 0.01)

        val late = g.progress(LatLon(0.0, 108.0075))
        assertEquals(1, late.stepIndex)
        assertEquals(278.0, late.toNextManeuverM, 3.0)
    }

    @Test
    fun distanceFromTheRouteLineIsMeasured() {
        val g = RouteGeometry(route)
        // 0.0005 degrees of latitude is about 55 m north of the line.
        assertEquals(55.3, g.progress(LatLon(0.0005, 108.005)).offRouteM, 1.0)
    }

    @Test
    fun pastTheEndSnapsToTheEndWithNothingLeft() {
        val p = RouteGeometry(route).progress(LatLon(0.0, 108.02))
        assertEquals(0.0, p.remainingM, 0.01)
        assertEquals(2, p.stepIndex)
    }

    @Test
    fun outAndBackRouteUsesTheHintToTellTheLegsApart() {
        // East 10 segments, then straight back west over the same line: 20 segments.
        val pts = (0..10).map { LatLon(0.0, 108.0 + it * 0.001) } + (9 downTo 0).map { LatLon(0.0, 108.0 + it * 0.001) }
        val out = NavRoute(pts, emptyList(), distanceM = 2226.4, durationS = 400.0)
        val g = RouteGeometry(out)
        val here = LatLon(0.0, 108.002)
        assertTrue(g.progress(here, hintSegment = 2).alongM < 300)
        assertTrue(g.progress(here, hintSegment = 17).alongM > 1900)
    }

    @Test
    fun hintBeyondTheRouteIsClampedNotCrashed() {
        val p = RouteGeometry(route).progress(LatLon(0.0, 108.005), hintSegment = 9999)
        assertTrue(p.offRouteM < 1.0)
    }

    @Test
    fun offRouteDetectorWaitsThenFiresOnceAndCoolsDown() {
        val d = OffRouteDetector(thresholdM = 50.0, minOffMs = 8_000, cooldownMs = 30_000)
        assertFalse(d.update(10.0, 0))
        assertFalse(d.update(60.0, 1_000)) // just left the route
        assertFalse(d.update(60.0, 5_000)) // not long enough yet
        assertTrue(d.update(60.0, 9_500)) // 8.5 s away: reroute
        assertFalse(d.update(60.0, 10_000)) // new timing starts
        assertFalse(d.update(60.0, 18_500)) // away long enough but still cooling down
        assertTrue(d.update(60.0, 41_000))
        assertFalse(d.update(10.0, 42_000)) // back on route resets the timer
        assertFalse(d.update(60.0, 43_000))
    }

    @Test
    fun instructionsReadNaturally() {
        fun step(type: String, mod: String? = null, name: String = "", exit: Int? = null) =
            NavStep(type, mod, name, 0.0, exit, 0.0, LatLon(0.0, 0.0))
        assertEquals("Turn right onto Jalan B", TurnText.of(step("turn", "right", "Jalan B")))
        assertEquals("Turn slight left", TurnText.of(step("turn", "slight left")))
        assertEquals("Continue straight", TurnText.of(step("continue", "straight")))
        assertEquals("Make a U-turn", TurnText.of(step("turn", "uturn")))
        assertEquals("At the roundabout take exit 2 onto Jalan C", TurnText.of(step("roundabout", exit = 2, name = "Jalan C")))
        // "exit roundabout" really appears in OSRM answers, right after the roundabout step.
        assertEquals("Leave the roundabout onto Jalan D", TurnText.of(step("exit roundabout", "left", "Jalan D")))
        assertEquals("At the roundabout turn right", TurnText.of(step("roundabout turn", "right")))
        assertEquals("Arrive at your destination", TurnText.of(step("arrive")))
        assertEquals("Head out", TurnText.of(step("depart")))
        assertEquals("Head out on Jalan A", TurnText.of(step("depart", name = "Jalan A")))
    }

    @Test
    fun distancesAndArrivalEstimate() {
        assertEquals("90 m", TurnText.distance(85.0))
        assertEquals("1.2 km", TurnText.distance(1200.0))
        assertEquals("15 km", TurnText.distance(15_000.0))
        assertEquals("0 m", TurnText.distance(0.0))
        assertEquals(10, TurnText.etaMinutes(3000.0, avgSpeedMps = 5.0))
        assertEquals(10, TurnText.etaMinutes(3000.0, avgSpeedMps = 0.0)) // falls back to 18 km/h = 5 m/s
    }

    private class FakeHttp(private val reply: (String) -> HttpResponse) : Http {
        var lastUrl = ""
        var lastHeaders: Map<String, String> = emptyMap()
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            lastUrl = url
            lastHeaders = headers
            return reply(url)
        }
    }

    @Test
    fun clientAsksOsrmForAStepByStepRouteInLonLatOrder() {
        val http = FakeHttp { HttpResponse(200, osrmJson.toByteArray()) }
        val r = OsrmClient("https://osrm.test/", http).route(LatLon(0.0, 108.0), LatLon(0.0, 108.01))
        assertEquals(
            "https://osrm.test/route/v1/bike/108.000000,0.000000;108.010000,0.000000" +
                "?overview=full&geometries=geojson&steps=true&alternatives=false",
            http.lastUrl,
        )
        assertEquals("cyclocomp", http.lastHeaders["User-Agent"])
        assertEquals(3, r.steps.size)
    }

    @Test
    fun clientTurnsFailuresIntoReadableMessages() {
        fun expectFailure(reply: () -> HttpResponse, expected: String) {
            try {
                OsrmClient("https://osrm.test", FakeHttp { reply() }).route(LatLon(0.0, 0.0), LatLon(1.0, 1.0))
                fail("expected NavException")
            } catch (e: NavException) {
                assertTrue(e.message!!, e.message!!.contains(expected))
            }
        }
        expectFailure({ HttpResponse(400, """{"code":"NoRoute","message":"Impossible route between points"}""".toByteArray()) }, "Impossible route")
        expectFailure({ HttpResponse(500, ByteArray(0)) }, "answered 500")
        expectFailure({ HttpResponse(200, "garbage".toByteArray()) }, "No route found")
        try {
            OsrmClient("https://osrm.test", object : Http {
                override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
                    throw IOException("timeout")
            }).route(LatLon(0.0, 0.0), LatLon(1.0, 1.0))
            fail("expected NavException")
        } catch (e: NavException) {
            assertTrue(e.message!!.contains("not reachable"))
        }
    }

    @Test
    fun serverAddressMustBeHttps() {
        assertEquals("https://osrm.example.org:5000", OsrmAddress.normalize(" https://osrm.example.org:5000/ "))
        assertNull(OsrmAddress.normalize("http://osrm.example.org"))
        assertNull(OsrmAddress.normalize("osrm.example.org"))
        assertNull(OsrmAddress.normalize("https://"))
        assertNull(OsrmAddress.normalize("https://a b"))
        assertNull(OsrmAddress.normalize(""))
    }
}

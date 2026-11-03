package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.core.Geo
import com.aliven.cyclocomp.core.GpxWriter
import com.aliven.cyclocomp.core.RideStats
import com.aliven.cyclocomp.core.TrackJournal
import com.aliven.cyclocomp.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    // ~111.2 m per 0.001 degree of latitude.
    private fun pt(i: Int, lat: Double = 0.0, ele: Double? = null, acc: Float? = null) =
        TrackPoint(timeMs = i * 10_000L, lat = lat, lon = 0.0, ele = ele, accuracyM = acc)

    @Test
    fun haversineMatchesKnownDistance() {
        val d = Geo.distanceM(0.0, 0.0, 0.001, 0.0)
        assertEquals(111.19, d, 0.5)
    }

    @Test
    fun movingSegmentsCountDistanceAndTime() {
        val s = RideStats()
        s.add(pt(0, 0.0))
        s.add(pt(1, 0.001)) // 111 m in 10 s = 11 m/s
        s.add(pt(2, 0.002))
        assertEquals(222.4, s.distanceM, 1.0)
        assertEquals(20_000L, s.movingMs)
        assertEquals(20_000L, s.elapsedMs)
        assertEquals(11.1, s.maxSpeedMps, 0.1)
        assertEquals(11.1, s.avgSpeedMps, 0.1)
    }

    @Test
    fun standstillJitterIsAutoPaused() {
        val s = RideStats()
        s.add(pt(0, 0.0))
        s.add(pt(1, 0.00001)) // ~1.1 m in 10 s = 0.11 m/s
        s.add(pt(2, 0.0))
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        assertEquals(20_000L, s.elapsedMs)
        assertEquals(0.0, s.currentSpeedMps, 0.0)
    }

    @Test
    fun poorAccuracyAndOutOfOrderPointsAreRejected() {
        val s = RideStats()
        assertTrue(s.add(pt(1, 0.0)))
        assertFalse(s.add(pt(2, 0.001, acc = 80f)))
        assertFalse(s.add(pt(0, 0.002)))
        assertEquals(0.0, s.distanceM, 0.0)
    }

    @Test
    fun breakSegmentDoesNotCountPauseGap() {
        val s = RideStats()
        s.add(pt(0, 0.0))
        s.add(pt(1, 0.001))
        s.breakSegment()
        s.add(pt(50, 0.5)) // far away after a long pause
        assertEquals(111.2, s.distanceM, 1.0)
        assertEquals(10_000L, s.elapsedMs)
    }

    @Test
    fun elevationNoiseIsIgnoredButRealClimbCounts() {
        val s = RideStats()
        s.add(pt(0, 0.0, ele = 100.0))
        s.add(pt(1, 0.001, ele = 101.0))
        s.add(pt(2, 0.002, ele = 99.5))
        assertEquals(0.0, s.ascentM, 0.0)
        s.add(pt(3, 0.003, ele = 110.0))
        assertEquals(10.0, s.ascentM, 0.0)
        s.add(pt(4, 0.004, ele = 104.0))
        assertEquals(6.0, s.descentM, 0.0)
    }

    @Test
    fun journalRoundTripKeepsSegmentsAndSkipsTornLine() {
        val a = TrackPoint(1000, 1.5, 2.5, ele = 3.0, accuracyM = 4f, speedMps = 5f)
        val b = TrackPoint(2000, 1.6, 2.6)
        val lines = listOf(
            TrackJournal.encode(a), TrackJournal.SEGMENT_LINE, TrackJournal.encode(b), "P,3000,1.7",
        )
        val segs = TrackJournal.decode(lines)
        assertEquals(2, segs.size)
        assertEquals(a, segs[0][0])
        assertEquals(b, segs[1][0])
        assertEquals(1, segs[1].size)
    }

    @Test
    fun gpxContainsPointsAndEscapesName() {
        val gpx = GpxWriter.write("A & B", listOf(listOf(TrackPoint(0, 1.0, 2.0, ele = 3.0))))
        assertTrue(gpx.contains("<name>A &amp; B</name>"))
        assertTrue(gpx.contains("""<trkpt lat="1.0" lon="2.0"><ele>3.0</ele><time>1970-01-01T00:00:00Z</time></trkpt>"""))
    }

    @Test
    fun formatting() {
        assertEquals("1:05", Format.duration(65_000))
        assertEquals("1:01:01", Format.duration(3_661_000))
        assertEquals("36.0", Format.kmh(10.0))
        assertEquals("1.50", Format.km(1500.0))
    }
}

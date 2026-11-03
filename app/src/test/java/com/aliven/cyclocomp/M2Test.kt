package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.GpxWriter
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.core.RideSeriesBuilder
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.TrackPoint
import com.aliven.cyclocomp.ui.DataField
import com.aliven.cyclocomp.ui.Pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M2Test {
    // Fake data shaped like a Strava-exported GPX with per-point heart rate and a long gap
    // between two stretches (the real sample has both). No real coordinates.
    private val stravaLike = """<?xml version="1.0" encoding="UTF-8"?>
<gpx creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
 <metadata><time>2026-01-01T00:00:00Z</time></metadata>
 <trk><name>Outdoor cycle</name><type>cycling</type>
  <trkseg>
   <trkpt lat="0.0000000" lon="0.0000000"><ele>10.0</ele><time>2026-01-01T00:00:00Z</time>
    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>100</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
   <trkpt lat="0.0010000" lon="0.0000000"><ele>20.0</ele><time>2026-01-01T00:00:10Z</time>
    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>140</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
   <trkpt lat="0.0020000" lon="0.0000000"><ele>20.0</ele><time>2026-01-01T01:00:00Z</time>
    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
  </trkseg>
 </trk>
</gpx>"""

    @Test
    fun parsesStravaStyleGpxWithHeartRate() {
        val segs = GpxParser.parse(stravaLike.byteInputStream())
        assertEquals(1, segs.size)
        val pts = segs[0]
        assertEquals(3, pts.size)
        assertEquals(100, pts[0].hr)
        assertEquals(20.0, pts[1].ele!!, 0.0)
        assertEquals(1_767_225_610_000L, pts[1].timeMs)
        assertEquals(0.001, pts[1].lat, 1e-9)
    }

    @Test
    fun writerOutputRoundTripsThroughParser() {
        val original = listOf(
            listOf(TrackPoint(1_000_000, 1.5, 2.5, ele = 3.0, hr = 130), TrackPoint(2_000_000, 1.6, 2.6, hr = 135)),
            listOf(TrackPoint(9_000_000, 1.7, 2.7)),
        )
        val parsed = GpxParser.parse(GpxWriter.write("t", original).byteInputStream())
        assertEquals(2, parsed.size)
        assertEquals(130, parsed[0][0].hr)
        assertEquals(3.0, parsed[0][0].ele!!, 0.0)
        assertNull(parsed[1][0].hr)
        assertEquals(9_000_000L, parsed[1][0].timeMs)
    }

    @Test
    fun summaryReplaysStatsAndAveragesHeartRate() {
        val segs = GpxParser.parse(stravaLike.byteInputStream())
        val s = RideSummaries.summarize(segs)!!
        assertEquals(120, s.avgHr)
        assertEquals(140, s.maxHr)
        assertEquals(10.0, s.ascentM, 0.0)
        // 111 m in 10 s is moving; the next hour-long hop is slow enough to be auto-paused.
        assertEquals(111.2, s.distanceM, 1.0)
        assertNull(RideSummaries.summarize(emptyList()))
    }

    @Test
    fun seriesAreDownsampledToLimit() {
        val v = List(1000) { it.toFloat() }
        val d = RideSeriesBuilder.downsample(v, 100)
        assertEquals(100, d.size)
        assertEquals(0f, d.first(), 0f)
        assertEquals(999f, d.last(), 0f)
        assertEquals(v, RideSeriesBuilder.downsample(v, 5000))
    }

    @Test
    fun rideNamesAreReadable() {
        assertEquals("2026-11-03 06:42", RideNames.display("20261103_064238.gpx"))
        assertEquals("other.gpx", RideNames.display("other.gpx"))
    }

    @Test
    fun pageConfigRoundTripsAndRepairsBadInput() {
        val pages = listOf(listOf(DataField.SPEED, DataField.ASCENT), listOf(DataField.DISTANCE))
        assertEquals(pages, Pages.decode(Pages.encode(pages)))
        assertEquals(Pages.DEFAULT, Pages.decode(null))
        assertEquals(Pages.DEFAULT, Pages.decode("NOPE;;,"))
        assertEquals(listOf(listOf(DataField.SPEED)), Pages.decode("SPEED,SPEED,BOGUS;"))
        assertEquals("SPEED;DISTANCE", Pages.encode(listOf(listOf(DataField.SPEED), emptyList(), listOf(DataField.DISTANCE))))
    }
}

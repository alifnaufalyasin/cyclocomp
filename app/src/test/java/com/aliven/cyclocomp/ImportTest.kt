package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.HrMerger
import com.aliven.cyclocomp.core.ImportDecision
import com.aliven.cyclocomp.core.ImportPlanner
import com.aliven.cyclocomp.core.RideCandidate
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.TrackPoint
import com.aliven.cyclocomp.sync.GitHubDataRepo
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.HttpResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ImportTest {
    private val start = 1_800_000_000_000L
    private val lonPerSecond = 0.000045 // ~5 m/s along the equator

    /** [seconds] of 1 Hz points beginning [startOffsetS] after [start], moving east at ~5 m/s. */
    private fun stretch(startOffsetS: Long, seconds: Int, withHr: Int? = null, fromSecond: Long = startOffsetS) =
        List(seconds) { i ->
            val t = startOffsetS + i
            TrackPoint(timeMs = start + t * 1000, lat = 0.0, lon = (fromSecond + i) * lonPerSecond, hr = withHr)
        }

    // Shaped like the real watch export: three riding stretches with long time jumps between them.
    // The long jumps keep the path continuous, so only the timestamps jump.
    private val watch = listOf(
        stretch(0, 600, withHr = 140),
        stretch(3_300, 300, withHr = 150, fromSecond = 600),
        stretch(7_500, 200, withHr = 130, fromSecond = 900),
    )

    // The phone recorded the same ride continuously (no long gaps) a second later.
    private val phone = listOf(
        stretch(1, 600),
        stretch(3_301, 300, fromSecond = 600),
        stretch(7_501, 200, fromSecond = 900),
    )

    private fun candidate(name: String, segs: List<List<TrackPoint>>): RideCandidate {
        val s = RideSummaries.summarize(segs)!!
        return RideCandidate(name, s.startMs, s.distanceM)
    }

    @Test
    fun matchesByStartAndDistanceDespiteLongTimeGaps() {
        val d = ImportPlanner.decide(listOf(candidate("a.gpx", phone)), watch)
        assertTrue(d is ImportDecision.Merge)
        assertEquals("a.gpx", (d as ImportDecision.Merge).rideName)
    }

    @Test
    fun picksTheClosestStartAmongSeveral() {
        val other = RideCandidate("far.gpx", start + 8 * 60_000, candidate("x", phone).distanceM)
        val d = ImportPlanner.decide(listOf(other, candidate("a.gpx", phone)), watch) as ImportDecision.Merge
        assertEquals("a.gpx", d.rideName)
    }

    @Test
    fun noPhoneRideNearbyMeansNewImport() {
        val earlier = RideCandidate("old.gpx", start - 5 * 3_600_000L, 9_000.0)
        assertTrue(ImportPlanner.decide(listOf(earlier), watch) is ImportDecision.New)
        assertTrue(ImportPlanner.decide(emptyList(), watch) is ImportDecision.New)
    }

    @Test
    fun sameStartButVeryDifferentDistanceIsSkippedNotDuplicated() {
        val c = candidate("a.gpx", phone)
        val longer = RideCandidate("a.gpx", c.startMs, c.distanceM * 2)
        val d = ImportPlanner.decide(listOf(longer), watch)
        assertTrue(d is ImportDecision.Skip)
    }

    @Test
    fun emptyFileIsSkipped() {
        assertTrue(ImportPlanner.decide(emptyList(), emptyList()) is ImportDecision.Skip)
    }

    @Test
    fun mergeCopiesNearestHeartRateOntoEveryPhonePoint() {
        val merged = HrMerger.merge(phone, watch)
        assertEquals(phone.sumOf { it.size }, HrMerger.countWithHr(merged))
        assertEquals(140, merged[0][0].hr)
        assertEquals(150, merged[1][10].hr)
        assertEquals(130, merged[2][5].hr)
        // Position and time stay the phone's own.
        assertEquals(phone[1][10].lon, merged[1][10].lon, 0.0)
        assertEquals(phone[1][10].timeMs, merged[1][10].timeMs)
    }

    @Test
    fun pointsWithoutANearbyWatchSampleStayWithoutHeartRate() {
        // Phone kept recording during a stretch where the watch has nothing within 10 s.
        val phoneExtra = phone + listOf(stretch(20_000, 50, fromSecond = 1_100))
        val merged = HrMerger.merge(phoneExtra, watch)
        assertNull(merged.last().first().hr)
        assertEquals(phone.sumOf { it.size }, HrMerger.countWithHr(merged))
    }

    @Test
    fun noHeartRateInWatchFileLeavesPhoneUntouched() {
        val noHr = listOf(stretch(0, 100))
        assertEquals(phone, HrMerger.merge(phone, noHr))
    }

    @Test
    fun rideStampUsesTheGivenZone() {
        // 2026-11-02T23:42:38Z is 06:42:38 in Western Indonesia Time (UTC+7).
        val t = java.time.Instant.parse("2026-11-02T23:42:38Z").toEpochMilli()
        assertEquals("20261103_064238", RideNames.stamp(t, ZoneOffset.ofHours(7)))
    }

    @Test
    fun putOrUpdateSendsExistingShaAndCreatesWhenMissing() {
        val calls = mutableListOf<Pair<String, String?>>()
        val existing = object : Http {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                calls.add(method to body?.toString(Charsets.UTF_8))
                return if (method == "GET") HttpResponse(200, """{"sha":"abc123","name":"r.gpx"}""".toByteArray())
                else HttpResponse(200, ByteArray(0))
            }
        }
        GitHubDataRepo("me/data", "t", existing, "https://api.test").putOrUpdate("rides/2026/r.gpx", byteArrayOf(1), "upd")
        assertEquals("abc123", JSONObject(calls[1].second!!).getString("sha"))

        calls.clear()
        val missing = object : Http {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                calls.add(method to body?.toString(Charsets.UTF_8))
                return if (method == "GET") HttpResponse(404, ByteArray(0)) else HttpResponse(201, ByteArray(0))
            }
        }
        GitHubDataRepo("me/data", "t", missing, "https://api.test").putOrUpdate("rides/2026/r.gpx", byteArrayOf(1), "new")
        assertTrue(!JSONObject(calls[1].second!!).has("sha"))
    }
}

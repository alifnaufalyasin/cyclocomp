package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.FitWriter
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitWriterTest {
    // ---------------------------------------------------------------- a small FIT reader, written from the spec

    private class Message(val global: Int, val fields: Map<Int, Long>)

    private class Decoded(val messages: List<Message>, val definitions: Int)

    private fun u(bytes: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = v or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun decode(bytes: ByteArray): Decoded {
        val headerSize = bytes[0].toInt()
        assertEquals(14, headerSize)
        assertEquals(".FIT", String(bytes, 8, 4, Charsets.US_ASCII))
        val dataSize = u(bytes, 4, 4).toInt()
        assertEquals(headerSize + dataSize + 2, bytes.size)
        assertEquals("header CRC", u(bytes, 12, 2).toInt(), FitWriter.crc16(bytes, 0, 12))
        assertEquals("file CRC", u(bytes, bytes.size - 2, 2).toInt(), FitWriter.crc16(bytes, 0, bytes.size - 2))

        class Def(val global: Int, val fields: List<Triple<Int, Int, Int>>)
        val defs = mutableMapOf<Int, Def>()
        val messages = mutableListOf<Message>()
        var definitions = 0
        var pos = headerSize
        val end = headerSize + dataSize
        while (pos < end) {
            val h = bytes[pos++].toInt() and 0xFF
            assertTrue("compressed headers are not written", h and 0x80 == 0)
            val local = h and 0x0F
            if (h and 0x40 != 0) { // definition
                pos++ // reserved
                assertEquals("little endian", 0, bytes[pos++].toInt())
                val global = u(bytes, pos, 2).toInt(); pos += 2
                val n = bytes[pos++].toInt() and 0xFF
                val fields = (0 until n).map {
                    Triple(bytes[pos + it * 3].toInt() and 0xFF, bytes[pos + it * 3 + 1].toInt() and 0xFF, bytes[pos + it * 3 + 2].toInt() and 0xFF)
                }
                pos += n * 3
                defs[local] = Def(global, fields)
                definitions++
            } else { // data
                val d = defs[local] ?: error("data message for undefined local type $local")
                val values = mutableMapOf<Int, Long>()
                for ((number, size, _) in d.fields) {
                    values[number] = u(bytes, pos, size)
                    pos += size
                }
                messages.add(Message(d.global, values))
            }
        }
        assertEquals("the messages use exactly the declared data size", end, pos)
        return Decoded(messages, definitions)
    }

    private fun Decoded.of(global: Int) = messages.filter { it.global == global }

    /** JUnit has no Long-with-tolerance overload. */
    private fun assertNear(expected: Long, actual: Long?, tolerance: Long) {
        assertNotNull(actual)
        assertTrue("expected about $expected but was $actual", kotlin.math.abs(expected - actual!!) <= tolerance)
    }

    // ---------------------------------------------------------------- test data

    private val start = 1_800_000_000_000L

    /** 1 Hz points moving east at about 5 m/s, with heart rate and altitude. */
    private fun ride(count: Int = 60, offsetS: Int = 0, hr: Boolean = true, ele: Boolean = true) = List(count) { i ->
        TrackPoint(
            timeMs = start + (offsetS + i) * 1000L,
            lat = 0.0, lon = (offsetS + i) * 5.0 / 111_195.0,
            ele = if (ele) 100.0 + i * 0.5 else null,
            hr = if (hr) 100 + i else null,
        )
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun crcMatchesTheStandardCheckValue() {
        // The FIT checksum is CRC-16/ARC, whose published check value for "123456789" is 0xBB3D.
        assertEquals(0xBB3D, FitWriter.crc16("123456789".toByteArray()))
        assertEquals(0, FitWriter.crc16(ByteArray(0)))
    }

    @Test
    fun theFileHasAValidHeaderChecksumsAndOnlyDeclaredMessages() {
        val fit = FitWriter.write(listOf(ride()))!!
        val decoded = decode(fit) // asserts size, signature, both CRCs and that every data message was defined
        assertTrue(decoded.definitions >= 6) // file_id, event, record, lap, session, activity
    }

    @Test
    fun itIsAnActivityFileAndTheMessagesComeInTheRightOrder() {
        val d = decode(FitWriter.write(listOf(ride()))!!)
        val fileId = d.messages.first()
        assertEquals(0, fileId.global)
        assertEquals(4L, fileId.fields[0]) // type: activity
        assertEquals(255L, fileId.fields[1]) // manufacturer: development
        assertEquals(FitWriter.toFitTime(start), fileId.fields[4])
        // lap, session and activity close the file, in that order.
        assertEquals(listOf(19, 18, 34), d.messages.takeLast(3).map { it.global })
    }

    @Test
    fun everyPointBecomesARecordWithPositionAltitudeHeartRateDistanceAndSpeed() {
        val points = ride()
        val records = decode(FitWriter.write(listOf(points))!!).of(20)
        assertEquals(points.size, records.size)

        val first = records.first()
        assertEquals(FitWriter.toFitTime(points.first().timeMs), first.fields[253])
        assertEquals(0L, first.fields[0]) // latitude 0
        assertEquals(100.0, first.fields[2]!! / 5.0 - 500.0, 0.2) // altitude: scale 5, offset 500
        assertEquals(100L, first.fields[3]) // heart rate
        assertEquals(0L, first.fields[5]) // distance so far

        // Position is stored in semicircles; the tenth point is 50 m east of the start.
        val tenth = records[10]
        assertEquals(points[10].lon, (tenth.fields[1]!!.toInt()) / (2_147_483_648.0 / 180.0), 1e-6)
        assertEquals(110L, tenth.fields[3])

        val last = records.last()
        assertNear(59L * 5 * 100, last.fields[5], 150L) // about 295 m, in centimetres
        assertNear(5000L, last.fields[6], 100L) // 5 m/s in mm/s
        // Time and distance only ever move forward.
        assertTrue(records.zipWithNext().all { (a, b) -> a.fields[253]!! <= b.fields[253]!! && a.fields[5]!! <= b.fields[5]!! })
    }

    @Test
    fun unknownHeartRateAndAltitudeAreWrittenAsTheFormatsInvalidValues() {
        val r = decode(FitWriter.write(listOf(ride(count = 5, hr = false, ele = false)))!!).of(20).first()
        assertEquals(0xFFFFL, r.fields[2]) // altitude not known
        assertEquals(0xFFL, r.fields[3]) // heart rate not known
        val session = decode(FitWriter.write(listOf(ride(count = 5, hr = false)))!!).of(18).single()
        assertEquals(0xFFL, session.fields[16]) // no average heart rate
        assertEquals(0xFFL, session.fields[17])
    }

    @Test
    fun theSessionSummarisesTheRideLikeTheAppDoes() {
        val points = ride()
        val summary = RideSummaries.summarize(listOf(points))!!
        val d = decode(FitWriter.write(listOf(points))!!)
        val s = d.of(18).single()
        assertEquals(2L, s.fields[5]) // sport: cycling
        assertNear(Math.round(summary.distanceM * 100), s.fields[9], 1L)
        assertEquals(summary.elapsedMs, s.fields[7])
        assertEquals(summary.movingMs, s.fields[8])
        assertEquals(FitWriter.toFitTime(start), s.fields[2])
        assertEquals(1L, s.fields[26]) // one lap
        assertEquals(159L, s.fields[17]) // max heart rate: 100 + 59
        assertEquals(130L, s.fields[16]) // average of 100..159 is 129.5, written as a whole number
    }

    @Test
    fun theLapAndTheActivityAgreeWithTheSession() {
        val d = decode(FitWriter.write(listOf(ride()))!!)
        val s = d.of(18).single()
        val lap = d.of(19).single()
        val activity = d.of(34).single()
        assertEquals(s.fields[9], lap.fields[9]) // distance
        assertEquals(s.fields[8], lap.fields[8]) // timer time
        assertEquals(s.fields[8], activity.fields[0])
        assertEquals(1L, activity.fields[1]) // one session
        assertEquals(s.fields[253], activity.fields[253])
    }

    @Test
    fun aPauseBetweenSegmentsIsMarkedWithTimerStopAndStart() {
        val first = ride(count = 30)
        val second = ride(count = 30, offsetS = 600) // ten minutes later
        val d = decode(FitWriter.write(listOf(first, second))!!)
        val timer = d.of(21).map { it.fields[1]!! to it.fields[0]!! } // (event type, event)
        assertEquals(listOf(0L, 4L, 0L, 4L), timer.map { it.first }) // start, stop_all, start, stop_all
        assertTrue(timer.all { it.second == 0L }) // all timer events
        val times = d.of(21).map { it.fields[253]!! }
        assertEquals(times.sorted(), times)
        // The ten-minute gap is not counted as moving time.
        val s = d.of(18).single()
        assertTrue(s.fields[8]!! < s.fields[7]!!)
    }

    @Test
    fun noUsablePointsGiveNoFile() {
        assertNull(FitWriter.write(emptyList()))
        assertNull(FitWriter.write(listOf(emptyList())))
        val poorFix = listOf(TrackPoint(start, 0.0, 0.0, accuracyM = 80f), TrackPoint(start + 1000, 0.0, 0.0001, accuracyM = 80f))
        assertNull(FitWriter.write(listOf(poorFix))) // every fix is worse than the 30 m the live screen accepts
        assertNotNull(FitWriter.write(listOf(ride(count = 2))))
    }

    @Test
    fun coordinatesAndTimesUseTheFormatsUnits() {
        assertEquals(0, FitWriter.semicircles(0.0))
        assertEquals(-1_073_741_824, FitWriter.semicircles(-90.0)) // -2^30
        assertEquals(Int.MAX_VALUE, FitWriter.semicircles(180.0)) // 180 degrees would be 2^31: clamped
        assertEquals(0L, FitWriter.toFitTime(631_065_600_000L)) // 1989-12-31T00:00:00Z
        assertEquals(1_800_000_000L - 631_065_600L, FitWriter.toFitTime(1_800_000_000_000L))
    }
}

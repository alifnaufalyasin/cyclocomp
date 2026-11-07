package com.aliven.cyclocomp.core

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Writes a ride as an activity FIT file (the format Garmin devices use and Strava / Garmin Connect
 * accept): one cycling session with one lap, a record per GPS point (position, altitude, heart rate,
 * distance, speed) and timer start/stop events around pauses.
 *
 * Distance and speed follow the same rules as the live screen ([RideStats]), so the numbers in the
 * file match what the app showed.
 */
object FitWriter {
    private const val FIT_EPOCH_OFFSET_S = 631_065_600L // 1989-12-31T00:00:00Z in Unix seconds
    private const val SEMICIRCLES_PER_DEGREE = 2_147_483_648.0 / 180.0

    // Global message numbers
    private const val FILE_ID = 0
    private const val SESSION = 18
    private const val LAP = 19
    private const val RECORD = 20
    private const val EVENT = 21
    private const val ACTIVITY = 34

    // Base types
    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val SINT32 = 0x85
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86

    private class Field(val number: Int, val size: Int, val baseType: Int)

    /** FIT's CRC-16 (the nibble-table form of CRC-16/ARC). */
    fun crc16(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        val table = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
        )
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[b and 0xF]
            tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[(b shr 4) and 0xF]
        }
        return crc
    }

    fun toFitTime(unixMs: Long): Long = unixMs / 1000 - FIT_EPOCH_OFFSET_S

    fun semicircles(degrees: Double): Int =
        (degrees * SEMICIRCLES_PER_DEGREE).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /** The FIT file for a ride, or null if there are no usable points. */
    fun write(segments: List<List<TrackPoint>>): ByteArray? {
        val body = ByteArrayOutputStream()

        fun u8(v: Int) = body.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        fun s32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun definition(local: Int, global: Int, fields: List<Field>) {
            u8(0x40 or local)
            u8(0) // reserved
            u8(0) // little endian
            u16(global)
            u8(fields.size)
            fields.forEach { u8(it.number); u8(it.size); u8(it.baseType) }
        }

        // ------------------------------------------------------------ walk the points once
        class Rec(val p: TrackPoint, val distanceM: Double, val speedMps: Double)

        val stats = RideStats()
        val records = mutableListOf<Rec>()
        val timerEvents = mutableListOf<Pair<Long, Int>>() // (time ms, 0 = start, 4 = stop_all)
        var maxHr = 0
        var hrSum = 0L
        var hrCount = 0
        for ((index, segment) in segments.withIndex()) {
            if (index > 0) stats.breakSegment()
            var segmentStart: Long? = null
            var segmentEnd = 0L
            for (p in segment) {
                if (!stats.add(p)) continue
                if (segmentStart == null) segmentStart = p.timeMs
                segmentEnd = p.timeMs
                records.add(Rec(p, stats.distanceM, stats.currentSpeedMps))
                p.hr?.let { maxHr = max(maxHr, it); hrSum += it; hrCount++ }
            }
            if (segmentStart != null) {
                timerEvents.add(segmentStart to 0)
                timerEvents.add(segmentEnd to 4)
            }
        }
        if (records.isEmpty()) return null

        val startMs = records.first().p.timeMs
        val endMs = records.last().p.timeMs
        val elapsedMs = endMs - startMs
        val movingMs = stats.movingMs
        val totalDistanceCm = (stats.distanceM * 100).roundToLong()
        fun ts(ms: Long) = toFitTime(ms).coerceAtLeast(0)

        // ------------------------------------------------------------ file_id
        definition(0, FILE_ID, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16), Field(4, 4, UINT32)))
        u8(0); u8(4) /* activity */; u16(255) /* development manufacturer */; u16(0); u32(ts(startMs))

        // ------------------------------------------------------------ events and records, in time order
        definition(1, EVENT, listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)))
        definition(2, RECORD, listOf(
            Field(253, 4, UINT32), Field(0, 4, SINT32), Field(1, 4, SINT32), Field(2, 2, UINT16),
            Field(3, 1, UINT8), Field(5, 4, UINT32), Field(6, 2, UINT16),
        ))
        data class Item(val timeMs: Long, val order: Int, val event: Int?, val rec: Rec?)
        val items = timerEvents.map { Item(it.first, if (it.second == 0) 0 else 2, it.second, null) } +
            records.map { Item(it.p.timeMs, 1, null, it) }
        for (item in items.sortedWith(compareBy({ it.timeMs }, { it.order }))) {
            if (item.event != null) {
                u8(1); u32(ts(item.timeMs)); u8(0) /* timer */; u8(item.event)
            } else {
                val r = item.rec!!
                u8(2)
                u32(ts(r.p.timeMs))
                s32(semicircles(r.p.lat))
                s32(semicircles(r.p.lon))
                // Altitude is stored with an offset of 500 m and a scale of 5; 0xFFFF means "not known".
                u16(r.p.ele?.let { ((it + 500) * 5).roundToInt().coerceIn(0, 0xFFFE) } ?: 0xFFFF)
                u8(r.p.hr?.coerceIn(1, 254) ?: 0xFF)
                u32((r.distanceM * 100).roundToLong())
                u16((r.speedMps * 1000).roundToInt().coerceIn(0, 0xFFFE))
            }
        }

        // ------------------------------------------------------------ lap, session, activity
        val avgSpeed = (stats.avgSpeedMps * 1000).roundToInt().coerceIn(0, 0xFFFE)
        val maxSpeed = (stats.maxSpeedMps * 1000).roundToInt().coerceIn(0, 0xFFFE)
        val avgHr = if (hrCount > 0) (hrSum.toDouble() / hrCount).roundToInt().coerceIn(1, 254) else 0xFF
        val maxHrValue = if (hrCount > 0) maxHr.coerceIn(1, 254) else 0xFF

        definition(3, LAP, listOf(
            Field(254, 2, UINT16), Field(253, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32),
            Field(8, 4, UINT32), Field(9, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM),
        ))
        u8(3); u16(0); u32(ts(endMs)); u32(ts(startMs)); u32(elapsedMs); u32(movingMs); u32(totalDistanceCm)
        u8(9) /* lap */; u8(1) /* stop */

        definition(4, SESSION, listOf(
            Field(254, 2, UINT16), Field(253, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32),
            Field(8, 4, UINT32), Field(9, 4, UINT32), Field(5, 1, ENUM), Field(6, 1, ENUM),
            Field(25, 2, UINT16), Field(26, 2, UINT16), Field(14, 2, UINT16), Field(15, 2, UINT16),
            Field(22, 2, UINT16), Field(23, 2, UINT16), Field(16, 1, UINT8), Field(17, 1, UINT8),
            Field(0, 1, ENUM), Field(1, 1, ENUM),
        ))
        u8(4); u16(0); u32(ts(endMs)); u32(ts(startMs)); u32(elapsedMs); u32(movingMs); u32(totalDistanceCm)
        u8(2) /* cycling */; u8(0) /* generic */
        u16(0); u16(1)
        u16(avgSpeed); u16(maxSpeed)
        u16(stats.ascentM.roundToInt().coerceIn(0, 0xFFFE)); u16(stats.descentM.roundToInt().coerceIn(0, 0xFFFE))
        u8(avgHr); u8(maxHrValue)
        u8(8) /* session */; u8(1) /* stop */

        definition(5, ACTIVITY, listOf(
            Field(253, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16), Field(2, 1, ENUM),
            Field(3, 1, ENUM), Field(4, 1, ENUM),
        ))
        u8(5); u32(ts(endMs)); u32(movingMs); u16(1); u8(0) /* manual */; u8(26) /* activity */; u8(1) /* stop */

        // ------------------------------------------------------------ header, body, trailing CRC
        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        val header = ByteArrayOutputStream()
        header.write(14)           // header size
        header.write(0x20)         // protocol version 2.0
        header.write(2100 and 0xFF); header.write(2100 shr 8) // profile version 21.00
        for (i in 0 until 4) header.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) // data size
        header.write(".FIT".toByteArray(Charsets.US_ASCII))
        val headerBytes = header.toByteArray()
        out.write(headerBytes)
        val headerCrc = crc16(headerBytes)
        out.write(headerCrc and 0xFF); out.write(headerCrc shr 8)
        out.write(data)
        val all = out.toByteArray()
        val fileCrc = crc16(all)
        out.write(fileCrc and 0xFF); out.write(fileCrc shr 8)
        return out.toByteArray()
    }
}

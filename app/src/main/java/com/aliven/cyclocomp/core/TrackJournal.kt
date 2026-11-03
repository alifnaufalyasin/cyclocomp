package com.aliven.cyclocomp.core

/**
 * Line format used to persist points while recording, so a killed process loses at most
 * the last point. `P,<timeMs>,<lat>,<lon>,<ele>,<acc>,<speed>` (empty = null) and `S` = new segment.
 */
object TrackJournal {
    const val SEGMENT_LINE = "S"

    fun encode(p: TrackPoint): String =
        "P,${p.timeMs},${p.lat},${p.lon},${p.ele ?: ""},${p.accuracyM ?: ""},${p.speedMps ?: ""}"

    fun decode(lines: List<String>): List<List<TrackPoint>> {
        val segments = mutableListOf<MutableList<TrackPoint>>()
        var current = mutableListOf<TrackPoint>()
        for (line in lines) {
            if (line == SEGMENT_LINE) {
                if (current.isNotEmpty()) segments.add(current)
                current = mutableListOf()
                continue
            }
            val p = parsePoint(line) ?: continue // a half-written last line is skipped
            current.add(p)
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    private fun parsePoint(line: String): TrackPoint? {
        val f = line.split(",")
        if (f.size != 7 || f[0] != "P") return null
        return try {
            TrackPoint(
                timeMs = f[1].toLong(),
                lat = f[2].toDouble(),
                lon = f[3].toDouble(),
                ele = f[4].toDoubleOrNull(),
                accuracyM = f[5].toFloatOrNull(),
                speedMps = f[6].toFloatOrNull(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}

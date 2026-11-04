package com.aliven.cyclocomp.core

data class RideSummary(
    val startMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val ascentM: Double,
    val descentM: Double,
    val avgHr: Int?,
    val maxHr: Int?,
)

object RideSummaries {
    /** Replays the points through [RideStats], so history numbers match what the live screen showed. */
    fun summarize(segments: List<List<TrackPoint>>): RideSummary? {
        val first = segments.firstOrNull()?.firstOrNull() ?: return null
        val stats = RideStats()
        segments.forEachIndexed { i, seg ->
            if (i > 0) stats.breakSegment()
            seg.forEach { stats.add(it) }
        }
        val hrs = segments.flatten().mapNotNull { it.hr }
        return RideSummary(
            startMs = first.timeMs,
            distanceM = stats.distanceM,
            movingMs = stats.movingMs,
            elapsedMs = stats.elapsedMs,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
            ascentM = stats.ascentM,
            descentM = stats.descentM,
            avgHr = if (hrs.isEmpty()) null else hrs.average().toInt(),
            maxHr = hrs.maxOrNull(),
        )
    }
}

data class RideSeries(
    val elevationM: List<Float>,
    val speedKmh: List<Float>,
    val hr: List<Float>,
)

object RideSeriesBuilder {
    fun build(segments: List<List<TrackPoint>>, maxPoints: Int = 200): RideSeries {
        val ele = mutableListOf<Float>()
        val speed = mutableListOf<Float>()
        val hr = mutableListOf<Float>()
        for (seg in segments) {
            for ((i, p) in seg.withIndex()) {
                p.ele?.let { ele.add(it.toFloat()) }
                p.hr?.let { hr.add(it.toFloat()) }
                if (i > 0) {
                    val prev = seg[i - 1]
                    val dt = (p.timeMs - prev.timeMs) / 1000.0
                    if (dt > 0) {
                        val d = Geo.distanceM(prev.lat, prev.lon, p.lat, p.lon)
                        speed.add((d / dt * 3.6).toFloat())
                    }
                }
            }
        }
        return RideSeries(downsample(ele, maxPoints), downsample(speed, maxPoints), downsample(hr, maxPoints))
    }

    fun downsample(values: List<Float>, maxPoints: Int): List<Float> {
        if (values.size <= maxPoints || maxPoints < 2) return values
        return List(maxPoints) { values[it * (values.size - 1) / (maxPoints - 1)] }
    }
}

object RideNames {
    /** File-name stamp for a ride that started at [startMs], e.g. `20261103_064238`. */
    fun stamp(startMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone)
            .format(java.time.Instant.ofEpochMilli(startMs))

    private val pattern = Regex("""^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})\d{2}\.gpx$""")

    /** `20261103_064238.gpx` -> `2026-11-03 06:42`; anything else is shown unchanged. */
    fun display(fileName: String): String {
        val m = pattern.find(fileName) ?: return fileName
        val (y, mo, d, h, mi) = m.destructured
        return "$y-$mo-$d $h:$mi"
    }
}

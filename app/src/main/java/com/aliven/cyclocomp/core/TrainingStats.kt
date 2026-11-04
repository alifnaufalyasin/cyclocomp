package com.aliven.cyclocomp.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.roundToInt

/** One ride reduced to what training statistics need. Heart rate comes from the GPX or the screenshot. */
data class RideRecord(
    val startMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val ascentM: Double,
    val avgHr: Int?,
    val kcal: Int?,
)

data class WeekStats(
    val weekStart: LocalDate,
    val rides: Int,
    val distanceKm: Double,
    val movingMin: Int,
    val ascentM: Int,
    /** Sum of TRIMP over the rides that have heart rate. */
    val load: Double,
    val ridesWithoutHr: Int,
    val kcal: Int,
)

data class Overview(
    /** Oldest first; the last entry is the current (partial) week. */
    val weeks: List<WeekStats>,
    /** Load over the last 7 days. */
    val acuteLoad: Double,
    /** Average weekly load over the last 28 days. */
    val chronicLoad: Double,
    val notes: List<String>,
) {
    val ratio: Double? get() = if (chronicLoad > 0) acuteLoad / chronicLoad else null
}

/**
 * Training load and weekly progress from ride history.
 *
 * Load is Banister's TRIMP from average heart rate; it needs resting and maximum heart rate, which
 * are settings with rough defaults. The notes are plain rules of thumb, not medical advice.
 */
object TrainingStats {
    const val DEFAULT_REST_HR = 60
    const val DEFAULT_MAX_HR = 190

    /** Banister TRIMP (male coefficients). Result is in arbitrary load units. */
    fun trimp(durationMin: Double, avgHr: Int, restHr: Int, maxHr: Int): Double {
        if (maxHr <= restHr || durationMin <= 0) return 0.0
        val ratio = ((avgHr - restHr).toDouble() / (maxHr - restHr)).coerceIn(0.0, 1.0)
        return durationMin * ratio * 0.64 * exp(1.92 * ratio)
    }

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun loadOf(r: RideRecord, restHr: Int, maxHr: Int): Double? =
        r.avgHr?.let { trimp(r.movingMs / 60_000.0, it, restHr, maxHr) }

    fun compute(
        records: List<RideRecord>,
        now: Instant,
        zone: ZoneId,
        restHr: Int = DEFAULT_REST_HR,
        maxHr: Int = DEFAULT_MAX_HR,
        weeks: Int = 8,
    ): Overview {
        val thisWeek = weekStart(now.atZone(zone).toLocalDate())
        val firstWeek = thisWeek.minusWeeks((weeks - 1).toLong())

        val byWeek = records.groupBy { weekStart(Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()) }
        val stats = (0 until weeks).map { i ->
            val ws = firstWeek.plusWeeks(i.toLong())
            val rs = byWeek[ws].orEmpty()
            WeekStats(
                weekStart = ws,
                rides = rs.size,
                distanceKm = rs.sumOf { it.distanceM } / 1000.0,
                movingMin = (rs.sumOf { it.movingMs } / 60_000.0).roundToInt(),
                ascentM = rs.sumOf { it.ascentM }.roundToInt(),
                load = rs.sumOf { loadOf(it, restHr, maxHr) ?: 0.0 },
                ridesWithoutHr = rs.count { it.avgHr == null },
                kcal = rs.sumOf { it.kcal ?: 0 },
            )
        }

        val nowMs = now.toEpochMilli()
        val day = 24 * 3_600_000L
        fun loadBetween(fromMs: Long, toMs: Long) = records
            .filter { it.startMs in fromMs..toMs }
            .sumOf { loadOf(it, restHr, maxHr) ?: 0.0 }
        val acute = loadBetween(nowMs - 7 * day, nowMs)
        val chronic = loadBetween(nowMs - 28 * day, nowMs) / 4.0

        return Overview(stats, acute, chronic, notes(stats, acute, chronic, records, nowMs))
    }

    private fun notes(
        weeks: List<WeekStats>,
        acute: Double,
        chronic: Double,
        records: List<RideRecord>,
        nowMs: Long,
    ): List<String> {
        val out = mutableListOf<String>()
        val day = 24 * 3_600_000L
        if (records.none { it.startMs >= nowMs - 14 * day }) {
            out.add("No rides in the last 2 weeks.")
        }

        // Compare the last two COMPLETE weeks; the current week is still in progress.
        val last = weeks.getOrNull(weeks.size - 2)
        val before = weeks.getOrNull(weeks.size - 3)
        if (last != null && before != null && before.distanceKm > 0 && last.distanceKm > 0) {
            val change = ((last.distanceKm - before.distanceKm) / before.distanceKm * 100).roundToInt()
            when {
                change > 30 -> out.add(
                    "Last week's distance was up $change% on the week before. " +
                        "A common rule of thumb is to raise weekly distance by about 10–15% at most; consider holding steady."
                )
                change > 0 -> out.add("Last week's distance was up $change% on the week before.")
                change < -30 -> out.add("Last week's distance was down ${-change}% on the week before.")
                else -> out.add("Last week's distance was about the same as the week before ($change%).")
            }
        }

        if (chronic > 0) {
            val ratio = acute / chronic
            val text = when {
                ratio < 0.8 -> "Your load over the last 7 days is below your recent average (a lighter or rest week)."
                ratio <= 1.3 -> "Your load over the last 7 days is in line with your recent average."
                ratio <= 1.5 -> "Your load over the last 7 days is rising quickly compared with your recent average."
                else -> "Your load over the last 7 days is high compared with your recent average; an easier day or two may help."
            }
            out.add(text)
        } else if (records.isNotEmpty()) {
            out.add("Load needs heart rate. Import watch GPX or Huawei screenshots to see it.")
        }

        val noHr = weeks.sumOf { it.ridesWithoutHr }
        if (noHr > 0) out.add("$noHr ride(s) in this period have no heart rate and are left out of the load.")
        return out
    }
}

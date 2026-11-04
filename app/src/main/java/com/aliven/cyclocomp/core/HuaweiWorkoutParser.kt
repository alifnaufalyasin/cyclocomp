package com.aliven.cyclocomp.core

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

class OcrCell(val text: String, val centerX: Int)

class OcrRow(val y: Int, val cells: List<OcrCell>) {
    val text: String get() = cells.joinToString(" | ") { it.text }
}

/** What could be read from a Huawei Health "workout detail" screenshot. Missing fields are null. */
data class HuaweiWorkout(
    val activity: String? = null,
    val startLocal: LocalDateTime? = null,
    val distanceKm: Double? = null,
    val durationS: Int? = null,
    val avgSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val kcal: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    /** Minutes per zone, keyed by Huawei's zone names (Warm-up, Fat-burning, Aerobic, Anaerobic, Extreme). */
    val zoneMinutes: Map<String, Int> = emptyMap(),
    val hrRecoveryStart: Int? = null,
    val hrRecoveryEnd: Int? = null,
    val hrRecoveryDrop: Int? = null,
    val aerobicStress: Double? = null,
    val stressLevel: String? = null,
    val recoveryHours: Int? = null,
    val watchModel: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val isCycling: Boolean get() = activity?.contains("cycl", ignoreCase = true) == true

    fun describe(): String = buildString {
        fun line(label: String, v: Any?) = append(label).append(": ").append(v ?: "—").append('\n')
        line("Activity", activity)
        line("Start (local)", startLocal)
        line("Distance km", distanceKm)
        line("Duration", durationS?.let { Format.duration(it * 1000L) })
        line("Avg / max speed km/h", "${avgSpeedKmh ?: "—"} / ${maxSpeedKmh ?: "—"}")
        line("Calories kcal", kcal)
        line("Avg / max HR", "${avgHr ?: "—"} / ${maxHr ?: "—"}")
        line("Zones min", if (zoneMinutes.isEmpty()) null else zoneMinutes.entries.joinToString { "${it.key} ${it.value}" })
        line("HR recovery start/end/drop", "${hrRecoveryStart ?: "—"}/${hrRecoveryEnd ?: "—"}/${hrRecoveryDrop ?: "—"}")
        line("Aerobic training stress", "${aerobicStress ?: "—"} ${stressLevel ?: ""}".trim())
        line("Recovery time h", recoveryHours)
        line("Watch", watchModel)
        if (warnings.isNotEmpty()) append("\nCheck these:\n").append(warnings.joinToString("\n") { "- $it" })
    }
}

/**
 * Reads the Huawei Health workout-detail layout from recognised text rows. Written against real
 * recognizer output, which drops or garbles some cells (units, small numbers, icons), so every
 * field is optional and the result carries warnings for the review step.
 *
 * Name and map text in the screenshot header are deliberately never read or stored.
 * The date is taken as day/month/year unless the numbers make that impossible.
 */
object HuaweiWorkoutParser {
    private val ZONES = linkedMapOf(
        "Warm-up" to Regex("""\bwarm[- ]?up\b""", RegexOption.IGNORE_CASE),
        "Fat-burning" to Regex("""\bfat[- ]?burning\b""", RegexOption.IGNORE_CASE),
        "Aerobic" to Regex("""\baerobic\b""", RegexOption.IGNORE_CASE), // \b keeps "Anaerobic" out
        "Anaerobic" to Regex("""\banaerobic\b""", RegexOption.IGNORE_CASE),
        "Extreme" to Regex("""\bextreme\b""", RegexOption.IGNORE_CASE),
    )
    // Words that identify the activity-name row, so map credits ("Google Maps") are never taken for it.
    private val ACTIVITY = Regex("""outdoor|indoor|cycl|\brun|walk|hik|swim|\bride""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    private val DATE = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})[,\s]+(\d{1,2}):(\d{2})""")
    private val NUMBER_ONLY = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*\.?\s*(?:km)?\s*$""", RegexOption.IGNORE_CASE)

    fun fromLines(lines: List<OcrLine>): List<OcrRow> =
        OcrLayout.rows(lines).map { row ->
            OcrRow(row.first().centerY, row.map { OcrCell(it.text.trim(), (it.left + it.right) / 2) })
        }

    fun parse(rows: List<OcrRow>): HuaweiWorkout {
        var w = HuaweiWorkout()
        val warnings = mutableListOf<String>()

        w = w.copy(watchModel = rows.firstOrNull { it.text.startsWith("HUAWEI WATCH", ignoreCase = true) }?.text)

        // Headline block: activity name, a big distance number and the start date are consecutive
        // rows, but the large distance font means the recognizer may order them differently.
        val dateIdx = rows.indexOfFirst { DATE.containsMatchIn(it.text) }
        if (dateIdx >= 0) {
            DATE.find(rows[dateIdx].text)?.let { m ->
                val (a, b, y, h, min) = m.destructured
                // Day first by default; month first only when the second number cannot be a month.
                val monthFirst = a.toInt() <= 12 && b.toInt() > 12
                val day = if (monthFirst) b.toInt() else a.toInt()
                val month = if (monthFirst) a.toInt() else b.toInt()
                w = w.copy(startLocal = try {
                    LocalDateTime.of(y.toInt(), month, day, h.toInt(), min.toInt())
                } catch (e: Exception) {
                    null
                })
            }
            for (i in (dateIdx - 1).downTo(maxOf(0, dateIdx - 3))) {
                val text = rows[i].text
                val num = NUMBER_ONLY.find(text)
                when {
                    num != null && w.distanceKm == null -> w = w.copy(distanceKm = parseNumber(num.groupValues[1]))
                    w.activity == null && ACTIVITY.containsMatchIn(text) -> w = w.copy(activity = text)
                }
            }
        }

        var section = ""
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            val t = row.text
            val lower = t.lowercase()
            when {
                lower.startsWith("heart rate recovery") -> section = "rec"
                lower.startsWith("heart rate (") -> section = "hr"
                lower.startsWith("speed (km/h)") && section != "speed" -> section = "speed"
                lower == "performance" -> section = "perf"
            }

            // Zone rows: "<bullet> Aerobic | 23 min"
            if (row.cells.size >= 2) {
                val zone = ZONES.entries.firstOrNull { it.value.containsMatchIn(row.cells[0].text) }
                val minutes = row.cells.getOrNull(1)?.text?.let { parseMinutes(it) }
                if (zone != null && minutes != null) w = w.copy(zoneMinutes = w.zoneMinutes + (zone.key to minutes))
            }

            // Label row followed by a value row (cells line up by order, or by horizontal position).
            val next = rows.getOrNull(i + 1)
            val labels = row.cells.map { it.text.trim().lowercase() }
            if (next != null && labels.any { it in LABELS }) {
                val values = pair(row, next)
                labels.forEachIndexed { idx, label ->
                    val v = values[idx] ?: return@forEachIndexed
                    w = applyLabel(w, section, label, v)
                }
            }

            if (lower == "aerobic training stress") {
                // The level word ("Maintained") and the number are separate rows after the label.
                for (r in rows.subList(i + 1, minOf(rows.size, i + 4))) {
                    if (r.text.lowercase() == "recovery time") break
                    val n = NUMBER_ONLY.find(r.text)
                    if (n != null) w = w.copy(aerobicStress = parseNumber(n.groupValues[1]))
                    else if (r.text.all { it.isLetter() || it == ' ' }) w = w.copy(stressLevel = r.text)
                }
            }
            if (lower == "recovery time") {
                rows.getOrNull(i + 1)?.let { r ->
                    Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE).find(r.text)?.let {
                        w = w.copy(recoveryHours = it.groupValues[1].toInt())
                    }
                }
            }
            i++
        }

        // Cross-checks that catch misread digits.
        val d = w.distanceKm
        val s = w.durationS
        val v = w.avgSpeedKmh
        if (w.startLocal == null) warnings.add("start date/time not found")
        if (d == null) warnings.add("distance not found")
        if (s == null) warnings.add("duration not found")
        if (d != null && s != null && s > 0 && v != null) {
            val expected = d / (s / 3600.0)
            if (abs(expected - v) / expected > 0.05) {
                warnings.add("average speed $v does not match distance/duration (${String.format(Locale.US, "%.2f", expected)})")
            }
        }
        if (w.zoneMinutes.isNotEmpty() && s != null) {
            val sum = w.zoneMinutes.values.sum()
            if (abs(sum - s / 60.0) > 5) warnings.add("zone minutes add up to $sum, duration is ${s / 60} min")
        }
        val avgHr = w.avgHr
        val maxHr = w.maxHr
        for ((name, hr) in listOf("avg HR" to avgHr, "max HR" to maxHr)) {
            if (hr != null && hr !in 30..240) warnings.add("$name $hr looks wrong")
        }
        if (avgHr != null && maxHr != null && avgHr > maxHr) warnings.add("avg HR is above max HR")
        return w.copy(warnings = warnings)
    }

    private val LABELS = setOf(
        "duration", "avg speed", "total calories", "avg heart rate",
        "average", "maximum", "fastest", "dropped", "start/end",
    )

    /** Maps each label cell to the value cell below it; null when the recognizer dropped that value. */
    private fun pair(labels: OcrRow, values: OcrRow): List<String?> {
        if (labels.cells.size == values.cells.size) return values.cells.map { it.text }
        val result = arrayOfNulls<String>(labels.cells.size)
        for (cell in values.cells) {
            val idx = labels.cells.indices.minByOrNull { abs(labels.cells[it].centerX - cell.centerX) } ?: continue
            result[idx] = cell.text
        }
        return result.toList()
    }

    private fun applyLabel(w: HuaweiWorkout, section: String, label: String, v: String): HuaweiWorkout = when (label) {
        "duration" -> if (w.durationS == null) w.copy(durationS = parseDuration(v)) else w
        "avg speed" -> if (w.avgSpeedKmh == null) w.copy(avgSpeedKmh = parseNumber(v)) else w
        "total calories" -> w.copy(kcal = parseNumber(v)?.toInt() ?: w.kcal)
        "avg heart rate" -> w.copy(avgHr = parseNumber(v)?.toInt() ?: w.avgHr)
        "average" -> when (section) {
            "hr" -> if (w.avgHr == null) w.copy(avgHr = parseNumber(v)?.toInt()) else w
            "speed" -> if (w.avgSpeedKmh == null) w.copy(avgSpeedKmh = parseNumber(v)) else w
            else -> w
        }
        "maximum" -> if (section == "hr") w.copy(maxHr = parseNumber(v)?.toInt() ?: w.maxHr) else w
        "fastest" -> w.copy(maxSpeedKmh = parseNumber(v) ?: w.maxSpeedKmh)
        "dropped" -> w.copy(hrRecoveryDrop = parseNumber(v)?.toInt())
        "start/end" -> {
            val m = Regex("""(\d+)\s*/\s*(\d+)""").find(v)
            if (m != null) w.copy(hrRecoveryStart = m.groupValues[1].toInt(), hrRecoveryEnd = m.groupValues[2].toInt()) else w
        }
        else -> w
    }

    /** `00:59:46`, `59:46` (possibly with a stray icon letter in front, e.g. `O 00:59:46`). */
    fun parseDuration(text: String): Int? {
        Regex("""(\d+):(\d{2}):(\d{2})""").find(text)?.let {
            return it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt()
        }
        Regex("""(\d+):(\d{2})""").find(text)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        return null
    }

    /** `23 min`, `1 h 5 min`, `1h05min` -> minutes. */
    fun parseMinutes(text: String): Int? {
        val m = Regex("""(?:(\d+)\s*h)?\s*(\d+)\s*min""", RegexOption.IGNORE_CASE).find(text) ?: return null
        return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + m.groupValues[2].toInt()
    }

    fun parseNumber(text: String): Double? = NUMBER.find(text)?.value?.replace(',', '.')?.toDoubleOrNull()
}

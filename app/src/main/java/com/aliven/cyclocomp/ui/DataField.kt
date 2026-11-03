package com.aliven.cyclocomp.ui

import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.recording.RideSnapshot

/** A value that can be placed on a ride-screen page. */
enum class DataField(val label: String, val unit: String) {
    SPEED("Speed", "km/h"),
    DISTANCE("Distance", "km"),
    MOVING_TIME("Moving time", ""),
    ELAPSED_TIME("Elapsed time", ""),
    AVG_SPEED("Avg speed", "km/h"),
    MAX_SPEED("Max speed", "km/h"),
    ASCENT("Ascent", "m");

    fun value(s: RideSnapshot): String = when (this) {
        SPEED -> Format.kmh(s.speedMps)
        DISTANCE -> Format.km(s.distanceM)
        MOVING_TIME -> Format.duration(s.movingMs)
        ELAPSED_TIME -> Format.duration(s.elapsedMs)
        AVG_SPEED -> Format.kmh(s.avgSpeedMps)
        MAX_SPEED -> Format.kmh(s.maxSpeedMps)
        ASCENT -> Format.meters(s.ascentM)
    }
}

object Pages {
    val DEFAULT: List<List<DataField>> = listOf(
        listOf(DataField.SPEED, DataField.DISTANCE, DataField.MOVING_TIME),
        listOf(DataField.AVG_SPEED, DataField.MAX_SPEED, DataField.ASCENT, DataField.ELAPSED_TIME),
    )

    const val MAX_FIELDS_PER_PAGE = 6

    /** `SPEED,DISTANCE;AVG_SPEED` — pages separated by `;`, fields by `,`. */
    fun encode(pages: List<List<DataField>>): String =
        pages.filter { it.isNotEmpty() }.joinToString(";") { page -> page.joinToString(",") { it.name } }

    /** Unknown names and empty pages are dropped; if nothing valid is left, the defaults are used. */
    fun decode(text: String?): List<List<DataField>> {
        if (text.isNullOrBlank()) return DEFAULT
        val pages = text.split(";").map { page ->
            page.split(",").mapNotNull { name -> DataField.values().firstOrNull { it.name == name.trim() } }
                .distinct()
                .take(MAX_FIELDS_PER_PAGE)
        }.filter { it.isNotEmpty() }
        return pages.ifEmpty { DEFAULT }
    }
}

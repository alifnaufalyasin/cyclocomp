package com.aliven.cyclocomp.core

import kotlin.math.abs

/** One recognised line of text with its bounding box in image pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/**
 * Helpers for turning raw recognised lines into something readable. Text recognition returns
 * blocks in reading order of its own choosing, so a label and its value can end up far apart;
 * grouping by vertical position recovers the visual rows ("Duration   00:59:46").
 */
object OcrLayout {
    /** Lines whose vertical centres are within ~60% of a typical line height share a row. */
    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        if (lines.isEmpty()) return emptyList()
        val heights = lines.map { it.height }.filter { it > 0 }.sorted()
        val typical = if (heights.isEmpty()) 20 else heights[heights.size / 2]
        val tolerance = maxOf(4, (typical * 0.6).toInt())

        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedWith(compareBy({ it.centerY }, { it.left }))) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.map { it.centerY }.average() - line.centerY) <= tolerance) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.map { r -> r.sortedBy { it.left } }
    }

    /** One text line per visual row, cells separated by ` | `, with the row's y position first. */
    fun formatRows(lines: List<OcrLine>): String =
        rows(lines).joinToString("\n") { row ->
            "y=${row.first().centerY}: " + row.joinToString(" | ") { it.text }
        }
}

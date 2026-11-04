package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.OcrLayout
import com.aliven.cyclocomp.core.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutTest {
    private fun line(text: String, left: Int, top: Int, h: Int = 30) = OcrLine(text, left, top, left + 100, top + h)

    @Test
    fun labelAndValueOnTheSameVisualRowAreJoinedLeftToRight() {
        // The recognizer may return the value before the label; geometry puts them back in order.
        val lines = listOf(
            line("00:59:46", left = 300, top = 500),
            line("Duration", left = 40, top = 503),
            line("Avg speed", left = 40, top = 700),
            line("18.70 km/h", left = 300, top = 698),
        )
        val rows = OcrLayout.rows(lines)
        assertEquals(2, rows.size)
        assertEquals(listOf("Duration", "00:59:46"), rows[0].map { it.text })
        assertEquals(listOf("Avg speed", "18.70 km/h"), rows[1].map { it.text })
    }

    @Test
    fun rowsAreOrderedTopToBottom() {
        val lines = listOf(line("b", 0, 300), line("a", 0, 100), line("c", 0, 500))
        assertEquals(listOf("a", "b", "c"), OcrLayout.rows(lines).map { it.single().text })
    }

    @Test
    fun differentRowsCloseTogetherStaySeparate() {
        val lines = listOf(line("one", 0, 100), line("two", 0, 140)) // 40 px apart, 30 px tall
        assertEquals(2, OcrLayout.rows(lines).size)
    }

    @Test
    fun emptyInputAndFormatting() {
        assertTrue(OcrLayout.rows(emptyList()).isEmpty())
        val text = OcrLayout.formatRows(listOf(line("Duration", 40, 500), line("00:59:46", 300, 502)))
        assertEquals("y=515: Duration | 00:59:46", text)
    }
}

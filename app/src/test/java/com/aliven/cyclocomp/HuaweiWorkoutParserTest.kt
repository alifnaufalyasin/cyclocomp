package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.HuaweiWorkoutParser
import com.aliven.cyclocomp.core.OcrCell
import com.aliven.cyclocomp.core.OcrRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class HuaweiWorkoutParserTest {
    /** `a | b` -> cells; x positions are synthetic (left column 300, right 900, middle 600). */
    private fun row(y: Int, text: String, vararg xs: Int): OcrRow {
        val parts = text.split(" | ")
        val positions = if (xs.isNotEmpty()) xs.toList() else when (parts.size) {
            1 -> listOf(600)
            2 -> listOf(300, 900)
            else -> parts.indices.map { 200 + it * 300 }
        }
        return OcrRow(y, parts.mapIndexed { i, t -> OcrCell(t, positions[i]) })
    }

    // Recognizer output from a real Huawei Health cycling screenshot (rows as the debug screen
    // printed them). Includes the recognizer's mistakes: "km" lost after the distance, the
    // "Dropped" value missing, icon read as the letter "O", axis labels garbled.
    private val real = listOf(
        row(129, "Alif Naufal"),
        row(179, "HUAWEI WATCH FIT3"),
        row(251, "MUMUNGGANG"),
        row(789, "Tasik(alaya"),
        row(1357, "Google Maps"),
        row(1556, "Outdoor cycle"),
        row(1583, "18.63."),
        row(1622, "03/11/2026, 06:42"),
        row(1772, "Duration | Avg speed"),
        row(1837, "O 00:59:46 | 18.70 km/h"),
        row(1998, "Total calories | Avg heart rate"),
        row(2074, "469 kcal | 143 bpm"),
        row(2272, "Heart rate (bpm)"),
        row(2376, "Average | Maximum"),
        row(2455, "143 | 170"),
        row(2555, "Heart rate"),
        row(2610, "180"),
        row(2745, "143"),
        row(2892, "100 |"),
        row(2944, "10 | 20 | 30 | 40 | 50"),
        row(2990, "Time (min)"),
        row(3169, "• Extreme | 0 min"),
        row(3259, "o Anaerobic | 20 min"),
        row(3354, "• Aerobic | 23 min"),
        row(3447, "• Fat-burning | 12 min"),
        row(3542, "• Warm-up | 2 min"),
        row(3697, "Heart rate recovery (bpm)"),
        row(3796, "Dropped | Start/End"),
        row(3873, "144/125", 900),
        row(3978, "Heart rate"),
        row(4036, "150,44"),
        row(4163, "l28"),
        row(4288, "I06"),
        row(4341, "09:51 | 1 min | 2 min"),
        row(4604, "Performance"),
        row(4695, "Aerobic training stress"),
        row(4757, "Maintained"),
        row(4784, "2.9"),
        row(4935, "Recovery time"),
        row(5024, "13h"),
        row(5253, "Speed (km/h)"),
        row(5357, "Average | Fastest"),
        row(5429, "18.70 | 43.56"),
        row(5537, "Speed"),
        row(5591, "48"),
        row(5897, "10 | 20 | 30"),
        row(5943, "Time (min)"),
        row(6211, "Speed (km/h)"),
        row(6303, "Avg speed"),
        row(6376, "18.70"),
        row(6477, "KM Speed (km/h)"),
        row(6571, "21.25"),
        row(6648, "6-10 | 18.35"),
        row(6726, "11-15 | 17.34"),
        row(6800, "Less than 5 km 18.15"),
        row(7050, "Huawei Health"),
    )

    @Test
    fun readsTheRealScreenshotHeadlineAndSummary() {
        val w = HuaweiWorkoutParser.parse(real)
        assertEquals("Outdoor cycle", w.activity)
        assertTrue(w.isCycling)
        assertEquals(LocalDateTime.of(2026, 11, 3, 6, 42), w.startLocal)
        assertEquals(18.63, w.distanceKm!!, 0.0)
        assertEquals(59 * 60 + 46, w.durationS)
        assertEquals(18.70, w.avgSpeedKmh!!, 0.0)
        assertEquals(43.56, w.maxSpeedKmh!!, 0.0)
        assertEquals(469, w.kcal)
        assertEquals(143, w.avgHr)
        assertEquals(170, w.maxHr)
        assertEquals("HUAWEI WATCH FIT3", w.watchModel)
    }

    @Test
    fun readsZonesRecoveryAndPerformance() {
        val w = HuaweiWorkoutParser.parse(real)
        assertEquals(
            mapOf("Extreme" to 0, "Anaerobic" to 20, "Aerobic" to 23, "Fat-burning" to 12, "Warm-up" to 2),
            w.zoneMinutes,
        )
        assertEquals(144, w.hrRecoveryStart)
        assertEquals(125, w.hrRecoveryEnd)
        assertEquals(2.9, w.aerobicStress!!, 0.0)
        assertEquals("Maintained", w.stressLevel)
        assertEquals(13, w.recoveryHours)
    }

    @Test
    fun aMissingValueStaysNullInsteadOfTakingTheNeighboursValue() {
        // "Dropped" lost its value in the real output; the lone "144/125" belongs to "Start/End".
        val w = HuaweiWorkoutParser.parse(real)
        assertNull(w.hrRecoveryDrop)
    }

    @Test
    fun consistentScreenshotHasNoWarnings() {
        assertTrue(HuaweiWorkoutParser.parse(real).warnings.toString(), HuaweiWorkoutParser.parse(real).warnings.isEmpty())
    }

    @Test
    fun personalHeaderAndMapTextAreNeverStored() {
        val text = HuaweiWorkoutParser.parse(real).toString()
        assertFalse(text.contains("Alif"))
        assertFalse(text.contains("Tasik"))
        assertFalse(text.contains("MUMUNGGANG"))
        assertFalse(text.contains("Google"))
    }

    @Test
    fun misreadDigitsAreFlaggedForReview() {
        val wrongSpeed = real.map { if (it.y == 1837) row(1837, "O 00:59:46 | 28.70 km/h") else it }
        val w = HuaweiWorkoutParser.parse(wrongSpeed)
        assertTrue(w.warnings.any { it.contains("average speed") })

        val noDate = HuaweiWorkoutParser.parse(real.filter { it.y != 1622 })
        assertTrue(noDate.warnings.any { it.contains("start date") })
        assertNull(noDate.startLocal)
    }

    @Test
    fun dateIsDayFirstUnlessImpossible() {
        fun start(date: String) = HuaweiWorkoutParser.parse(real.map { if (it.y == 1622) row(1622, date) else it }).startLocal
        assertEquals(LocalDateTime.of(2026, 11, 3, 6, 42), start("03/11/2026, 06:42"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 6, 42), start("25/11/2026, 06:42"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 6, 42), start("11/25/2026, 06:42")) // 25 cannot be a month
    }

    // A second real screenshot (another day, a slower ride). Differences from the first: the
    // aerobic-stress number and its level word share one row, an icon is read as "(", "recOvery"
    // has a capital O, "115bpm" has no space, and "Dropped" kept its value.
    private val real2 = listOf(
        row(133, "Alif Naufal"),
        row(183, "HUAWEI WATCH FIT3"),
        row(240, "MUMUNGGANG"),
        row(408, "109)"),
        row(569, "(6)"),
        row(787, "Tasikrgglaya"),
        row(852, "(17)"),
        row(1355, "Google Maps"),
        row(1552, "Outdoor cycle"),
        row(1585, "17.73."),
        row(1616, "07/11/2026, 06:02"),
        row(1772, "Duration | Avg speed"),
        row(1840, "O 01:12:38 | ( 14.65 km/h"),
        row(1997, "Total calories | Avg heart rate"),
        row(2074, "391 kcal | 115bpm"),
        row(2271, "Heart rate (bpm)"),
        row(2376, "Average | Maximum"),
        row(2456, "115 | 134"),
        row(2555, "Heart rate"),
        row(2942, "15 | 3( | 60"),
        row(3169, "• Extreme | 0 min"),
        row(3259, "o Anaerobic | 0 min"),
        row(3354, "• Aerobic | <1 min"),
        row(3446, "• Fat-burning | 40 min"),
        row(3542, "• Warm-up | 31 min"),
        row(3696, "Heart rate recOvery (bpm)"),
        row(3797, "Dropped | Start/End"),
        row(3870, "3 | 103/96"),
        row(4341, "07:25 | 1 min | 2 min"),
        row(4604, "Performance"),
        row(4695, "Aerobic training stress"),
        row(4785, "1.8 | Recovery"),
        row(4935, "Recovery time"),
        row(5023, "2h"),
        row(5253, "Speed (km/h)"),
        row(5357, "Average | Fastest"),
        row(5430, "14.65 | 26.64"),
        row(6211, "Speed (km/h)"),
        row(6303, "Avg speed"),
        row(6377, "14.65"),
        row(6477, "KM Speed (km/h)"),
        row(6571, "19.35"),
        row(6648, "6-10 | 14.80"),
        row(6726, "11-15 | 18.91"),
        row(6800, "Less than 5 km 7.80"),
        row(7051, "Huawei Health"),
    )

    @Test
    fun readsTheSecondRealScreenshot() {
        val w = HuaweiWorkoutParser.parse(real2)
        assertEquals("Outdoor cycle", w.activity)
        assertEquals(LocalDateTime.of(2026, 11, 7, 6, 2), w.startLocal)
        assertEquals(17.73, w.distanceKm!!, 0.0)
        assertEquals(1 * 3600 + 12 * 60 + 38, w.durationS)
        assertEquals(14.65, w.avgSpeedKmh!!, 0.0)
        assertEquals(26.64, w.maxSpeedKmh!!, 0.0)
        assertEquals(391, w.kcal)
        assertEquals(115, w.avgHr)
        assertEquals(134, w.maxHr)
        assertEquals(103, w.hrRecoveryStart)
        assertEquals(96, w.hrRecoveryEnd)
        assertEquals(3, w.hrRecoveryDrop)
        assertEquals(2, w.recoveryHours)
        assertTrue(w.warnings.toString(), w.warnings.isEmpty())
    }

    @Test
    fun numberAndLevelOnTheSameRowAreBothRead() {
        val w = HuaweiWorkoutParser.parse(real2)
        assertEquals(1.8, w.aerobicStress!!, 0.0)
        assertEquals("Recovery", w.stressLevel)
    }

    @Test
    fun lessThanOneMinuteInAZoneCountsAsZero() {
        val w = HuaweiWorkoutParser.parse(real2)
        assertEquals(
            mapOf("Extreme" to 0, "Anaerobic" to 0, "Aerobic" to 0, "Fat-burning" to 40, "Warm-up" to 31),
            w.zoneMinutes,
        )
        assertEquals(0, HuaweiWorkoutParser.parseMinutes("<1 min"))
        assertEquals(1, HuaweiWorkoutParser.parseMinutes("1 min"))
    }

    @Test
    fun helpers() {
        assertEquals(3586, HuaweiWorkoutParser.parseDuration("O 00:59:46"))
        assertEquals(3586, HuaweiWorkoutParser.parseDuration("59:46"))
        assertNull(HuaweiWorkoutParser.parseDuration("no time"))
        assertEquals(65, HuaweiWorkoutParser.parseMinutes("1 h 5 min"))
        assertEquals(23, HuaweiWorkoutParser.parseMinutes("23 min"))
        assertEquals(18.63, HuaweiWorkoutParser.parseNumber("18,63 km")!!, 0.0)
    }
}

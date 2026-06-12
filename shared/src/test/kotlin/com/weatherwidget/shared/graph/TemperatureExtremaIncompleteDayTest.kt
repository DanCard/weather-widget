package com.weatherwidget.shared.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The current (incomplete) day's observed maximum is not its daily high if the day hasn't peaked yet
 * — e.g. mid-morning "now" with a much warmer afternoon still in the forecast. Such a morning bump
 * must NOT be labeled as that day's ACTUAL_HIGH (the desktop/emulator "67.6° mid-day" report).
 * Completed past days keep their actual high.
 */
class TemperatureExtremaIncompleteDayTest {

    private data class Pt(val dt: LocalDateTime, val temp: Float, val actual: Float?, val isActual: Boolean)

    private fun hour(day: Int, h: Int, temp: Float, actual: Float? = null, isActual: Boolean = false) =
        Pt(LocalDateTime.of(2026, 4, day, h, 0), temp, actual, isActual)

    private fun build(points: List<Pt>): List<HourData> =
        points.map { p ->
            HourData(
                dateTime = p.dt,
                temperature = p.temp,
                actualTemperature = p.actual,
                isActual = p.isActual,
                label = "${p.dt.hour}h",
            )
        }

    // Day 1 (Apr 8) is complete with a real afternoon high of 90°. Day 2 (Apr 9) is observed only
    // through 08:00 (morning max 67°); index 6 is "now". The forecast for the rest of Apr 9 climbs to
    // 85°, so the day has NOT peaked.
    private val incompleteDayPoints = listOf(
        hour(8, 12, temp = 85f, actual = 88f, isActual = true),
        hour(8, 13, temp = 88f, actual = 90f, isActual = true),   // idx1: Apr 8 actual high (90)
        hour(8, 14, temp = 86f, actual = 87f, isActual = true),
        hour(8, 15, temp = 84f, actual = 85f, isActual = true),
        hour(9, 6, temp = 60f, actual = 62f, isActual = true),
        hour(9, 7, temp = 64f, actual = 67f, isActual = true),    // idx5: Apr 9 morning max (67)
        hour(9, 8, temp = 63f, actual = 64f, isActual = true),    // idx6: "now" (cutoff)
        hour(9, 12, temp = 80f, actual = null, isActual = false),
        hour(9, 15, temp = 85f, actual = null, isActual = false), // idx8: Apr 9 forecast high (85)
        hour(9, 18, temp = 72f, actual = null, isActual = false),
    )

    @Test
    fun `incomplete current day's morning max is not labeled as the daily actual high`() {
        val extrema = TemperatureExtrema.compute(
            hours = build(incompleteDayPoints),
            transitionX = 100f,
            effectiveActualEndIndex = 6,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )

        assertTrue(
            "Completed Apr 8 actual high (idx 1) should be labeled. highs=${extrema.actualDailyHighIndices}",
            1 in extrema.actualDailyHighIndices,
        )
        assertFalse(
            "Incomplete Apr 9 morning max (idx 5) must NOT be labeled — the day's high is still ahead " +
                "in the forecast. highs=${extrema.actualDailyHighIndices}",
            5 in extrema.actualDailyHighIndices,
        )
    }

    @Test
    fun `current day's observed max IS the high when the rest of the day is forecast no warmer`() {
        // Same shape, but Apr 9's afternoon forecast stays cool (<= the 67° morning max), so the day
        // has effectively peaked and its actual high is kept.
        val coolAfternoon = incompleteDayPoints.toMutableList().apply {
            this[7] = hour(9, 12, temp = 66f, actual = null, isActual = false)
            this[8] = hour(9, 15, temp = 65f, actual = null, isActual = false)
            this[9] = hour(9, 18, temp = 62f, actual = null, isActual = false)
        }

        val extrema = TemperatureExtrema.compute(
            hours = build(coolAfternoon),
            transitionX = 100f,
            effectiveActualEndIndex = 6,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )

        assertTrue(
            "Apr 9 morning max (idx 5) should be kept when the rest of the day is no warmer. " +
                "highs=${extrema.actualDailyHighIndices}",
            5 in extrema.actualDailyHighIndices,
        )
    }
}

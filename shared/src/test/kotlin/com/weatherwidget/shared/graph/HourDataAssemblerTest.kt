package com.weatherwidget.shared.graph

import com.weatherwidget.shared.actuals.ActualTemperaturePoint
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HourDataAssemblerTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun ms(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun result(points: List<ActualTemperaturePoint>) =
        ActualTemperatureSeriesResult(
            points = points,
            blendStats = null,
            selectedStationId = null,
            sourceObservationCount = 0,
            blendInputCount = 0,
        )

    @Test
    fun `maps every point including sub-hourly so an off-hour peak is preserved`() {
        // 13:00 anchor = 72.0, but the true observed peak is the OFF-HOUR 13:07 = 72.9. A naive
        // hourly-only assembly would drop 13:07 and label 72.0; the assembler must keep it so the
        // labeled actual high equals the real peak (which is what daily_history stores).
        val points = listOf(
            ActualTemperaturePoint(ms("2026-06-03T12:00:00"), forecastTemp = 70f, actualTemp = 69.5f, isActual = true, isObservedActual = true),
            ActualTemperaturePoint(ms("2026-06-03T13:00:00"), forecastTemp = 74f, actualTemp = 72.0f, isActual = true, isObservedActual = true),
            ActualTemperaturePoint(ms("2026-06-03T13:07:00"), forecastTemp = 74f, actualTemp = 72.9f, isActual = true, isObservedActual = true),
            ActualTemperaturePoint(ms("2026-06-03T14:00:00"), forecastTemp = 75f, actualTemp = 71.0f, isActual = true, isObservedActual = true),
        )

        val hours = HourDataAssembler.assembleHourData(result(points), zone)

        assertEquals("every series point becomes a HourData", 4, hours.size)
        val offHour = hours.singleOrNull { it.dateTime == LocalDateTime.parse("2026-06-03T13:07:00") }
        assertNotNull("the off-hour 13:07 point must survive assembly", offHour)
        val actualHigh = hours.filter { it.isActual }.mapNotNull { it.actualTemperature }.max()
        assertEquals("labeled actual high = the off-hour peak", 72.9f, actualHigh, 0.001f)
    }

    @Test
    fun `forecast temperature and actual flags are carried from the series`() {
        val points = listOf(
            ActualTemperaturePoint(ms("2026-06-03T10:00:00"), forecastTemp = 66f, actualTemp = 64f, isActual = true, isObservedActual = true),
            ActualTemperaturePoint(ms("2026-06-03T16:00:00"), forecastTemp = 80f, actualTemp = null, isActual = false, isObservedActual = false),
        )

        val hours = HourDataAssembler.assembleHourData(result(points), zone)

        assertEquals(66f, hours[0].temperature, 0.001f)
        assertTrue(hours[0].isActual)
        assertEquals(64f, hours[0].actualTemperature!!, 0.001f)
        assertEquals(80f, hours[1].temperature, 0.001f)
        assertTrue("future point is not actual", !hours[1].isActual)
    }

    @Test
    fun `decorator receives top-hour flag and index and can override platform fields`() {
        val points = listOf(
            ActualTemperaturePoint(ms("2026-06-03T13:00:00"), forecastTemp = 74f, actualTemp = 72f, isActual = true, isObservedActual = true),
            ActualTemperaturePoint(ms("2026-06-03T13:07:00"), forecastTemp = 74f, actualTemp = 73f, isActual = true, isObservedActual = true),
        )

        val hours = HourDataAssembler.assembleHourData(result(points), zone) { base, isTopHour, index ->
            base.copy(label = "$index", showLabel = isTopHour, iconRes = if (isTopHour) 42 else null)
        }

        assertEquals("0", hours[0].label)
        assertTrue(hours[0].showLabel)
        assertEquals(42, hours[0].iconRes)
        // Sub-hourly point: decorator told not to show a label or icon.
        assertEquals("1", hours[1].label)
        assertTrue(!hours[1].showLabel)
        assertEquals(null, hours[1].iconRes)
    }
}

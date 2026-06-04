package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TemperatureInterpolatorTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `interpolates linearly between surrounding hourly buckets`() {
        val ten = epoch("2026-06-03T10:00")
        val eleven = epoch("2026-06-03T11:00")
        val target = epoch("2026-06-03T10:30")

        val result = TemperatureInterpolator.getInterpolatedTemperature(
            listOf(
                HourlyForecast(ten, 70f, "Clear"),
                HourlyForecast(eleven, 80f, "Clear"),
            ),
            target,
            zone,
        )

        assertEquals(75f, result!!, 0.001f)
    }

    @Test
    fun `returns single surrounding bucket when only one side exists`() {
        val ten = epoch("2026-06-03T10:00")
        val target = epoch("2026-06-03T10:45")

        val result = TemperatureInterpolator.getInterpolatedTemperature(
            listOf(HourlyForecast(ten, 70f, "Clear")),
            target,
            zone,
        )

        assertEquals(70f, result!!, 0.001f)
    }

    @Test
    fun `returns closest bucket when target hour is missing`() {
        val noon = epoch("2026-06-03T12:00")
        val target = epoch("2026-06-03T10:30")

        val result = TemperatureInterpolator.getInterpolatedTemperature(
            listOf(HourlyForecast(noon, 82f, "Clear")),
            target,
            zone,
        )

        assertEquals(82f, result!!, 0.001f)
    }

    @Test
    fun `returns null for empty forecasts`() {
        assertNull(TemperatureInterpolator.getInterpolatedTemperature(emptyList(), epoch("2026-06-03T10:30"), zone))
    }

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
}

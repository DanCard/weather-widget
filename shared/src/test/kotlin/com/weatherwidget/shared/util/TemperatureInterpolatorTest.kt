package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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

    @Test
    fun `interpolates linearly for gaps up to 3 hours`() {
        val ten = epoch("2026-06-03T10:00")
        val onePm = epoch("2026-06-03T13:00") // 3 hours gap
        val target = epoch("2026-06-03T11:30") // half way (1.5 hours)

        val result = TemperatureInterpolator.getInterpolatedTemperature(
            listOf(
                HourlyForecast(ten, 70f, "Clear"),
                HourlyForecast(onePm, 80f, "Clear"),
            ),
            target,
            zone,
        )

        assertEquals(75f, result!!, 0.001f)
    }

    @Test
    fun `falls back to closest bucket for gaps greater than 3 hours`() {
        val ten = epoch("2026-06-03T10:00")
        val twoPm = epoch("2026-06-03T14:00") // 4 hours gap
        
        // Target closer to 10:00 (1.5 hours from 10:00, 2.5 hours from 14:00)
        val target1 = epoch("2026-06-03T11:30")
        val result1 = TemperatureInterpolator.getInterpolatedTemperature(
            listOf(
                HourlyForecast(ten, 70f, "Clear"),
                HourlyForecast(twoPm, 80f, "Clear"),
            ),
            target1,
            zone,
        )
        assertEquals(70f, result1!!, 0.001f)

        // Target closer to 14:00 (1 hour from 14:00, 3 hours from 10:00)
        val target2 = epoch("2026-06-03T13:00")
        val result2 = TemperatureInterpolator.getInterpolatedTemperature(
            listOf(
                HourlyForecast(ten, 70f, "Clear"),
                HourlyForecast(twoPm, 80f, "Clear"),
            ),
            target2,
            zone,
        )
        assertEquals(80f, result2!!, 0.001f)
    }

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
}

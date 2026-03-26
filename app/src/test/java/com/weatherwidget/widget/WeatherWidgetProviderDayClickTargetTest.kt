package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class WeatherWidgetProviderDayClickTargetTest {

    @Test
    fun `future day keeps preferred offset when preferred hour exists`() {
        val now = LocalDateTime.of(2026, 3, 25, 19, 51)
        val targetDate = LocalDate.of(2026, 4, 1)
        val preferredOffset = 157

        val result =
            WeatherWidgetProvider.resolveHourlyGraphClickTarget(
                now = now,
                targetDate = targetDate,
                preferredOffset = preferredOffset,
                supportedRows =
                    listOf(
                        forecastAt("2026-04-01T07:00"),
                        forecastAt("2026-04-01T08:00"),
                        forecastAt("2026-04-01T09:00"),
                    ),
            )

        assertEquals(preferredOffset, result.offset)
        assertEquals(LocalDateTime.of(2026, 4, 1, 8, 0), result.resolvedTime)
        assertFalse(result.wasClamped)
    }

    @Test
    fun `future day clamps to closest available hour when preferred hour is unavailable`() {
        val now = LocalDateTime.of(2026, 3, 25, 19, 51)
        val targetDate = LocalDate.of(2026, 4, 1)
        val preferredOffset = 157

        val result =
            WeatherWidgetProvider.resolveHourlyGraphClickTarget(
                now = now,
                targetDate = targetDate,
                preferredOffset = preferredOffset,
                supportedRows =
                    listOf(
                        forecastAt("2026-04-01T04:00"),
                        forecastAt("2026-04-01T05:00"),
                        forecastAt("2026-04-01T06:00"),
                    ),
            )

        assertEquals(155, result.offset)
        assertEquals(LocalDateTime.of(2026, 4, 1, 6, 0), result.resolvedTime)
        assertTrue(result.wasClamped)
    }

    @Test
    fun `today keeps current offset without clamping`() {
        val now = LocalDateTime.of(2026, 3, 25, 19, 51)
        val targetDate = now.toLocalDate()

        val result =
            WeatherWidgetProvider.resolveHourlyGraphClickTarget(
                now = now,
                targetDate = targetDate,
                preferredOffset = 0,
                supportedRows =
                    listOf(
                        forecastAt("2026-03-25T17:00"),
                        forecastAt("2026-03-25T18:00"),
                    ),
            )

        assertEquals(0, result.offset)
        assertEquals(LocalDateTime.of(2026, 3, 25, 19, 0), result.resolvedTime)
        assertFalse(result.wasClamped)
    }

    private fun forecastAt(dateTime: String): HourlyForecastEntity {
        val localDateTime = LocalDateTime.parse(dateTime)
        return HourlyForecastEntity(
            dateTime = localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            locationLat = 37.422,
            locationLon = -122.0841,
            temperature = 60f,
            condition = "Cloudy",
            source = WeatherSource.NWS.id,
            fetchedAt = 0L,
        )
    }
}

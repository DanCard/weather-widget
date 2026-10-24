package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category


@Category(ShortDuration::class)
class CurrentTemperatureResolverTest {
    @Test
    fun `resolve prefers interpolated estimate over observed temp`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 30)
        val observedFetchedAt = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs(now)),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs(now)),
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = 39f,
                observedAt = observedFetchedAt,
            )

        assertEquals(39f, result.displayTemp!!, 0.01f)
        assertEquals(42f, result.estimatedTemp!!, 0.01f)
        assertEquals(39f, result.observedTemp!!, 0.01f)
        assertEquals(-3f, result.appliedDelta!!, 0.01f)
    }

    @Test
    fun `resolve falls back to observed when interpolation unavailable`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 15)

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = emptyList(),
                lastObservedTemp = 57f,
                observedAt = nowMs(now),
            )

        assertEquals(57f, result.displayTemp!!, 0.01f)
        assertEquals(null, result.estimatedTemp)
        assertFalse(result.isStaleEstimate)
    }

    @Test
    fun `resolve marks stale estimate when hourly fetch is old`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 30)
        val staleFetchedAt = nowMs(now) - (3 * 60 * 60 * 1000L)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 60f, fetchedAt = staleFetchedAt),
                hourly(now.plusHours(1).withMinute(0), 64f, fetchedAt = staleFetchedAt),
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = null,
                observedAt = null,
            )

        assertTrue(result.isStaleEstimate)
        assertEquals("62.0°", CurrentTemperatureResolver.formatDisplayTemperature(result.displayTemp!!, 3, result.isStaleEstimate, useCelsius = false))
    }

    @Test
    fun `format keeps decimal precision when space allows regardless of freshness`() {
        // Fresh estimate
        val freshFormatted = CurrentTemperatureResolver.formatDisplayTemperature(62.4f, 3, isStaleEstimate = false, useCelsius = false)
        assertEquals("62.4°", freshFormatted)

        // Stale estimate (previously would have been "62°")
        val staleFormatted = CurrentTemperatureResolver.formatDisplayTemperature(62.4f, 3, isStaleEstimate = true, useCelsius = false)
        assertEquals("62.4°", staleFormatted)
    }

    @Test
    fun `resolve anchors on an observation forty-five minutes old`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = 39f,
                observedAt = nowMs(now.minusMinutes(45)),
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        assertEquals(-1f, result.appliedDelta!!, 0.01f)
        assertEquals(42f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `resolve anchors on a fifteen-minute-old observation`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = 41f,
                observedAt = nowMs(now.minusMinutes(15)),
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        assertEquals(-1f, result.appliedDelta!!, 0.01f)
        assertEquals(42f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `resolve measures the delta from the newest observation`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        val oldObsFetchedAt = nowMs - (60 * 60 * 1000L)

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = 41f,
                observedAt = nowMs,
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        assertEquals(41f, result.displayTemp!!, 0.01f)
        assertEquals(-2f, result.appliedDelta!!, 0.01f)
    }

    @Test
    fun `resolve falls back to observed temp when active observation lacks strict forecast support`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
            )
        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = 37f,
                observedAt = nowMs(now.minusMinutes(30)),
            )

        assertEquals(37f, result.displayTemp!!, 0.01f)
        assertEquals(null, result.estimatedTemp)
        assertEquals(null, result.appliedDelta)
    }

    @Test
    fun `no observation in the window is the bare forecast`() {
        // A delta from an earlier reading is not carried forward (user, 2026-10-24): the Pixel showed
        // forecast + 1.9 °F with no reading while desktop showed the forecast.
        val now = LocalDateTime.of(2026, 10, 21, 23, 58)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 58f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 58f, fetchedAt = nowMs),
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                lastObservedTemp = null,
                observedAt = null,
            )

        assertEquals(58f, result.displayTemp!!, 0.01f)
        assertEquals(null, result.appliedDelta)
    }

    @Test
    fun `resolve uses smoothedForecasts when provided`() {
        val now = LocalDateTime.of(2026, 1, 15, 14, 30) // Middle of 14:00 and 15:00
        val recentFetchMs = nowMs(now.minusMinutes(10))

        // Raw temperatures are 70 and 74 (would interpolate to 72 without smoothing)
        val hourlyForecasts =
            listOf(
                hourly(LocalDateTime.of(2026, 1, 15, 14, 0), 70f, recentFetchMs),
                hourly(LocalDateTime.of(2026, 1, 15, 15, 0), 74f, recentFetchMs),
            )

        // Provide smoothed overrides
        val smoothedForecasts = mapOf(
            hourlyForecasts[0].dateTime to 68f,
            hourlyForecasts[1].dateTime to 72f
        )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourlyForecasts,
                lastObservedTemp = null,
                observedAt = null,
                smoothedForecasts = smoothedForecasts
            )

        // Should interpolate between 68 and 72 at halfway point -> 70.0
        assertEquals(70.0f, result.displayTemp!!, 0.01f)
        assertEquals(70.0f, result.estimatedTemp!!, 0.01f)
    }

    private fun hourly(
        dateTime: LocalDateTime,
        temp: Float,
        fetchedAt: Long,
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = nowMs(dateTime),
            locationLat = 0.0,
            locationLon = 0.0,
            temperature = temp,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = null,
            fetchedAt = fetchedAt,
        )
    }

    private fun nowMs(dateTime: LocalDateTime): Long {
        return dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

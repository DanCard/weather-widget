package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CurrentTemperatureResolverTest {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

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
                observedCurrentTemp = 39f,
                observedAt = observedFetchedAt,
                storedDeltaState = null,
                currentLat = 0.0,
                currentLon = 0.0,
            )

        assertEquals(39f, result.displayTemp!!, 0.01f)
        assertEquals(42f, result.estimatedTemp!!, 0.01f)
        assertEquals(39f, result.observedTemp!!, 0.01f)
        assertEquals(-3f, result.appliedDelta!!, 0.01f)
        assertEquals(observedFetchedAt, result.updatedDeltaState?.lastObservedAt)
    }

    @Test
    fun `resolve falls back to observed when interpolation unavailable`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 15)

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = emptyList(),
                observedCurrentTemp = 57f,
                observedAt = nowMs(now),
                storedDeltaState = null,
                currentLat = 0.0,
                currentLon = 0.0,
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
                observedCurrentTemp = null,
                observedAt = null,
                storedDeltaState = null,
                currentLat = 0.0,
                currentLon = 0.0,
            )

        assertTrue(result.isStaleEstimate)
        assertEquals("62.0°", CurrentTemperatureResolver.formatDisplayTemperature(result.displayTemp!!, 3, result.isStaleEstimate))
    }

    @Test
    fun `format keeps decimal precision when space allows regardless of freshness`() {
        // Fresh estimate
        val freshFormatted = CurrentTemperatureResolver.formatDisplayTemperature(62.4f, 3, isStaleEstimate = false)
        assertEquals("62.4°", freshFormatted)

        // Stale estimate (previously would have been "62°")
        val staleFormatted = CurrentTemperatureResolver.formatDisplayTemperature(62.4f, 3, isStaleEstimate = true)
        assertEquals("62.4°", staleFormatted)
    }

    @Test
    fun `resolve preserves full delta during one hour grace period`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        val stored =
            CurrentTemperatureDeltaState(
                delta = -4f,
                lastObservedTemp = 39f,
                lastObservedAt = 1000L,
                updatedAtMs = nowMs - (45 * 60 * 1000L), // 45 mins ago
                sourceId = WeatherSource.NWS.id,
                locationLat = 0.0,
                locationLon = 0.0,
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                observedCurrentTemp = 39f,
                observedAt = 1000L,
                storedDeltaState = stored,
                currentLat = 0.0,
                currentLon = 0.0,
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        // Delta should NOT decay yet (still in grace period)
        assertEquals(-4f, result.appliedDelta!!, 0.01f)
        assertEquals(39f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `resolve linearly decays stored delta after grace period`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        // Decay window is 4h, grace is 1h. 
        // 2.5h total elapsed means 1.5h into the 3h decay period.
        // remaining = 1 - (1.5 / 3) = 0.5
        val stored =
            CurrentTemperatureDeltaState(
                delta = -4f,
                lastObservedTemp = 39f,
                lastObservedAt = 1000L,
                updatedAtMs = nowMs - (150 * 60 * 1000L), // 2.5 hours ago
                sourceId = WeatherSource.NWS.id,
                locationLat = 0.0,
                locationLon = 0.0,
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                observedCurrentTemp = 39f,
                observedAt = 1000L,
                storedDeltaState = stored,
                currentLat = 0.0,
                currentLon = 0.0,
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        // 50% decay
        assertEquals(-2f, result.appliedDelta!!, 0.01f)
        assertEquals(41f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `resolve updates delta when observed reading timestamp changes`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        val oldObsFetchedAt = nowMs - (60 * 60 * 1000L)
        val stored =
            CurrentTemperatureDeltaState(
                delta = -3f,
                lastObservedTemp = 39f,
                lastObservedAt = oldObsFetchedAt,
                updatedAtMs = nowMs - (3 * 60 * 60 * 1000L),
                sourceId = WeatherSource.NWS.id,
                locationLat = 0.0,
                locationLon = 0.0,
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                observedCurrentTemp = 41f,
                observedAt = nowMs,
                storedDeltaState = stored,
                currentLat = 0.0,
                currentLon = 0.0,
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        assertEquals(41f, result.displayTemp!!, 0.01f)
        assertEquals(-2f, result.appliedDelta!!, 0.01f)
        assertEquals(nowMs, result.updatedDeltaState?.lastObservedAt)
    }

    @Test
    fun `resolve decays stored delta to zero after four hours`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 45)
        val nowMs = nowMs(now)
        val hourly =
            listOf(
                hourly(now.withMinute(0), 40f, fetchedAt = nowMs),
                hourly(now.plusHours(1).withMinute(0), 44f, fetchedAt = nowMs),
            )
        val stored =
            CurrentTemperatureDeltaState(
                delta = -6f,
                lastObservedTemp = 37f,
                lastObservedAt = 1000L,
                updatedAtMs = nowMs - (4 * 60 * 60 * 1000L),
                sourceId = WeatherSource.NWS.id,
                locationLat = 0.0,
                locationLon = 0.0,
            )

        val result =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourly,
                observedCurrentTemp = 37f,
                observedAt = 1000L,
                storedDeltaState = stored,
                currentLat = 0.0,
                currentLon = 0.0,
            )

        assertEquals(43f, result.estimatedTemp!!, 0.01f)
        assertEquals(43f, result.displayTemp!!, 0.01f)
        assertEquals(0f, result.appliedDelta!!, 0.01f)
    }

    private fun hourly(
        dateTime: LocalDateTime,
        temp: Float,
        fetchedAt: Long,
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime.format(formatter),
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

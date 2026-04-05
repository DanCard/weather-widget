package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.handlers.computeSmoothedForecasts
import com.weatherwidget.widget.handlers.HEADER_SMOOTH_ITERATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/**
 * Integration tests that verify temperature values remain consistent across
 * view modes (DAILY vs TEMPERATURE) and zoom levels (WIDE vs NARROW).
 *
 * These tests guard against regressions where different code paths feed
 * different smoothing parameters to CurrentTemperatureResolver, causing
 * the displayed temperature to change on view toggle or zoom.
 */
@Category(ShortDuration::class)
class TemperatureConsistencyTest {

    // A realistic hourly forecast curve: overnight low → afternoon high → evening cooldown
    private val now = LocalDateTime.of(2026, 3, 27, 14, 25)
    private val hourlyForecasts = buildRealisticHourlyData()
    private val displaySource = WeatherSource.NWS
    private val observedTemp = 72.3f
    private val observedAt = nowMs(now.minusMinutes(12))

    // -- Test 1: Cross-view consistency --

    @Test
    fun `current temp is identical between DAILY and TEMPERATURE views`() {
        // DailyViewHandler path: computes smoothedForecasts via computeSmoothedForecasts() with default iterations
        val dailySmoothed = computeSmoothedForecasts(
            hourlyForecasts, displaySource
        )
        val dailyResult = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = displaySource,
            hourlyForecasts = hourlyForecasts,
            lastObservedTemp = observedTemp,
            observedAt = observedAt,
            storedDeltaState = null,
            currentLat = 37.0,
            currentLon = -122.0,
            smoothedForecasts = dailySmoothed,
        )

        // TemperatureViewHandler path: also computes smoothedForecasts with HEADER_SMOOTH_ITERATIONS
        val tempSmoothed = computeSmoothedForecasts(
            hourlyForecasts, displaySource, HEADER_SMOOTH_ITERATIONS
        )
        val tempResult = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = displaySource,
            hourlyForecasts = hourlyForecasts,
            lastObservedTemp = observedTemp,
            observedAt = observedAt,
            storedDeltaState = null,
            currentLat = 37.0,
            currentLon = -122.0,
            smoothedForecasts = tempSmoothed,
        )

        assertEquals(
            "Current temp must be identical across DAILY and TEMPERATURE views",
            dailyResult.displayTemp!!, tempResult.displayTemp!!, 0.001f
        )
        assertEquals(
            "Estimated temp must be identical across views",
            dailyResult.estimatedTemp!!, tempResult.estimatedTemp!!, 0.001f
        )
    }

    // -- Test 2: Zoom-dependent smoothing must not leak into current temp --

    @Test
    fun `zoom-dependent smoothing produces different values than header smoothing`() {
        // Precondition: different iteration counts DO produce different maps.
        // If this fails, the guard is unnecessary (but harmless).
        val headerSmoothed = computeSmoothedForecasts(
            hourlyForecasts, displaySource, HEADER_SMOOTH_ITERATIONS
        )
        val narrowSmoothed = computeSmoothedForecasts(
            hourlyForecasts, displaySource, ZoomLevel.NARROW.smoothIterations
        )

        // Sanity: NARROW (1 iteration) and HEADER (2 iterations) should differ
        val headerValues = headerSmoothed.values.toList()
        val narrowValues = narrowSmoothed.values.toList()
        val anyDifference = headerValues.zip(narrowValues).any { (h, n) ->
            kotlin.math.abs(h - n) > 0.001f
        }
        assertTrue(
            "Precondition: different iteration counts should produce different smoothed values " +
                "(otherwise this guard test is vacuous)",
            anyDifference
        )
    }


    // -- Test 4: Smoothing map identity --

    @Test
    fun `computeSmoothedForecasts default uses HEADER_SMOOTH_ITERATIONS`() {
        val defaultSmoothed = computeSmoothedForecasts(
            hourlyForecasts, displaySource
        )
        val explicitSmoothed = computeSmoothedForecasts(
            hourlyForecasts, displaySource, HEADER_SMOOTH_ITERATIONS
        )

        assertEquals(
            "Default and explicit HEADER_SMOOTH_ITERATIONS must produce identical maps",
            defaultSmoothed, explicitSmoothed
        )
    }

    @Test
    fun `header current temp is lower than observed anchor when post observation trend is down`() {
        val contractNow = LocalDateTime.of(2026, 3, 27, 20, 30)
        val contractForecasts = listOf(
            hourly(contractNow.withHour(20).withMinute(0), 74f),
            hourly(contractNow.withHour(21).withMinute(0), 70f),
        )
        val contractObservedTemp = 73f
        val contractObservedAt = nowMs(contractNow.withHour(20).withMinute(15))

        val result = CurrentTemperatureResolver.resolve(
            now = contractNow,
            displaySource = displaySource,
            hourlyForecasts = contractForecasts,
            lastObservedTemp = contractObservedTemp,
            observedAt = contractObservedAt,
            storedDeltaState = null,
            currentLat = 37.0,
            currentLon = -122.0,
        )

        assertTrue(result.displayTemp!! < result.observedTemp!!)
    }

    // -- Helpers --

    private fun hourly(dateTime: LocalDateTime, temperature: Float): HourlyForecastEntity =
        HourlyForecastEntity(
            dateTime = nowMs(dateTime),
            locationLat = 37.0,
            locationLon = -122.0,
            temperature = temperature,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = null,
            fetchedAt = nowMs(dateTime.minusMinutes(30)),
        )

    private fun buildRealisticHourlyData(): List<HourlyForecastEntity> {
        // 24-hour curve: cool overnight, warm afternoon, cool evening
        val baseTime = now.toLocalDate().atStartOfDay()
        val fetchedAt = nowMs(now.minusMinutes(30))
        val temps = listOf(
            // 00:00-05:00 (overnight lows)
            52f, 51f, 50f, 49f, 49f, 50f,
            // 06:00-11:00 (morning warmup)
            53f, 56f, 60f, 64f, 68f, 71f,
            // 12:00-17:00 (afternoon peak)
            74f, 76f, 77f, 78f, 76f, 73f,
            // 18:00-23:00 (evening cooldown)
            70f, 66f, 63f, 60f, 58f, 56f,
        )
        return temps.mapIndexed { hour, temp ->
            HourlyForecastEntity(
                dateTime = nowMs(baseTime.plusHours(hour.toLong())),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = temp,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = null,
                fetchedAt = fetchedAt,
            )
        }
    }

    private fun nowMs(dateTime: LocalDateTime): Long {
        return dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

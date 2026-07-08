package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the day-tap NPE: computeSmoothedForecasts crashed with a
 * NullPointerException when any hour bucket had rows from neither the displayed source nor
 * GENERIC_GAP (pickBestForecast returns null for such buckets; the old code asserted `!!`).
 * Repro'd on-device 2026-07-08: NWS had 20/22 hour buckets while other sources had 22, with NWS
 * as the displayed source — the two NWS-less buckets killed handleSetView and the widget
 * silently stayed on the daily view.
 */
class CurrentTemperatureResolverSourceGapTest {

    private val baseMs = 1_700_000_000_000L
    private val hourMs = 3_600_000L

    private fun forecast(
        hourIndex: Int,
        source: WeatherSource,
        temperature: Float,
        fetchedAt: Long = baseMs,
    ) = HourlyForecast(
        dateTime = baseMs + hourIndex * hourMs,
        temperature = temperature,
        condition = "Clear",
        source = source.id,
        fetchedAt = fetchedAt,
    )

    @Test
    fun bucketWithoutDisplaySourceRows_isDroppedInsteadOfCrashing() {
        val forecasts = listOf(
            forecast(0, WeatherSource.NWS, 60f),
            forecast(1, WeatherSource.OPEN_METEO, 61f), // no NWS row for this hour
            forecast(2, WeatherSource.NWS, 62f),
        )

        val result = CurrentTemperatureResolver.computeSmoothedForecasts(forecasts, WeatherSource.NWS.id)

        assertEquals(setOf(baseMs, baseMs + 2 * hourMs), result.keys)
        assertEquals(60f, result.getValue(baseMs))
        assertEquals(62f, result.getValue(baseMs + 2 * hourMs))
    }

    @Test
    fun noRowsForDisplaySourceAtAll_returnsEmptyMap() {
        val forecasts = listOf(
            forecast(0, WeatherSource.OPEN_METEO, 60f),
            forecast(1, WeatherSource.SILURIAN, 61f),
        )

        val result = CurrentTemperatureResolver.computeSmoothedForecasts(forecasts, WeatherSource.NWS.id)

        assertTrue(result.isEmpty())
    }

    @Test
    fun genericGapRows_fillBucketsMissingTheDisplaySource() {
        val forecasts = listOf(
            forecast(0, WeatherSource.NWS, 60f),
            forecast(1, WeatherSource.GENERIC_GAP, 55f),
            forecast(1, WeatherSource.OPEN_METEO, 61f),
        )

        val result = CurrentTemperatureResolver.computeSmoothedForecasts(forecasts, WeatherSource.NWS.id)

        assertEquals(setOf(baseMs, baseMs + hourMs), result.keys)
        assertEquals(55f, result.getValue(baseMs + hourMs))
    }

    @Test
    fun duplicateRowsInBucket_latestFetchWins() {
        val forecasts = listOf(
            forecast(0, WeatherSource.NWS, 58f, fetchedAt = baseMs - hourMs),
            forecast(0, WeatherSource.NWS, 60f, fetchedAt = baseMs),
        )

        val result = CurrentTemperatureResolver.computeSmoothedForecasts(forecasts, WeatherSource.NWS.id)

        assertEquals(60f, result.getValue(baseMs))
    }

    @Test
    fun emptyInput_returnsEmptyMap() {
        val result = CurrentTemperatureResolver.computeSmoothedForecasts(emptyList(), WeatherSource.NWS.id)

        assertTrue(result.isEmpty())
    }

    @Test
    fun fullCoverage_dropsNothing() {
        val forecasts = (0..5).map { forecast(it, WeatherSource.NWS, 60f + it) }

        val result = CurrentTemperatureResolver.computeSmoothedForecasts(forecasts, WeatherSource.NWS.id)

        assertEquals(6, result.size)
        assertFalse(result.containsValue(Float.NaN))
    }
}

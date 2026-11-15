package com.weatherwidget.ui

import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Rows from the Pixel's app_logs, 2026-11-15, diagnosing a stale Open-Meteo. */
@Category(ShortDuration::class)
class AppLogSourceFilterTest {
    private fun row(tag: String, message: String) = AppLogEntity(tag = tag, message = message)
    private val meteo = WeatherSource.OPEN_METEO

    @Test
    fun `Meteo matches its failure tag, its id and its short name`() {
        assertTrue(AppLogSourceFilter.matches(row("FETCH_METEO_FAIL", "error=Request timeout has expired"), meteo))
        assertTrue(AppLogSourceFilter.matches(row("SOURCE_RETRY", "fire source=OPEN_METEO charging=true"), meteo))
        assertTrue(AppLogSourceFilter.matches(row("REFRESH_TOAST", "none=UP_TO_DATE source=Meteo subject=FORECAST"), meteo))
        assertTrue(AppLogSourceFilter.matches(row("SYNC_START", "reason=missing_actuals_OPEN_METEO_today"), meteo))
    }

    @Test
    fun `other providers' rows do not match Meteo`() {
        assertFalse(AppLogSourceFilter.matches(row("FETCH_NWS_FAIL", "source=NWS error=404"), meteo))
        assertFalse(AppLogSourceFilter.matches(row("NET_FETCH_COMPLETE", "durationMs=2000 sources=SILURIAN"), meteo))
    }

    @Test
    fun `a short name inside a longer word does not match`() {
        // "OWM" must not match inside an unrelated identifier.
        assertFalse(AppLogSourceFilter.matches(row("X", "flowmeter=1"), WeatherSource.OPEN_WEATHER_MAP))
        assertTrue(AppLogSourceFilter.matches(row("X", "source=OWM"), WeatherSource.OPEN_WEATHER_MAP))
    }

    @Test
    fun `All matches everything, and only forecast providers get a chip`() {
        assertTrue(AppLogSourceFilter.matches(row("ANY", "anything"), null))
        assertFalse(WeatherSource.METAR in AppLogSourceFilter.sources)
        assertTrue(meteo in AppLogSourceFilter.sources)
    }
}

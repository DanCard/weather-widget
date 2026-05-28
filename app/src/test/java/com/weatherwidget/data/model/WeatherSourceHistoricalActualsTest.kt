package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Locks the precip-provenance policy: only sources with a genuine historical data product may
 * persist past-day precip as a measured "actual" (see `saveHistoricalActuals`). Forecast-only
 * sources must NOT — otherwise their own past forecast is shown as a measurement, the bug we
 * removed for NWS. This test forces a deliberate choice whenever a source is added, rather than
 * silently inheriting the `providesHistoricalActuals = false` default.
 */
@Category(ShortDuration::class)
class WeatherSourceHistoricalActualsTest {

    @Test
    fun `sources with a real historical product provide historical actuals`() {
        val expectedTrue = setOf(
            WeatherSource.NWS,            // station observations
            WeatherSource.OPEN_METEO,     // past_days reanalysis archive
            WeatherSource.WEATHER_API,    // /history.json
            WeatherSource.SILURIAN,       // /history/hourly
        )
        val actualTrue = WeatherSource.values().filter { it.providesHistoricalActuals }.toSet()
        assertEquals(
            "Only sources with a genuine history product may persist measured past-day precip. " +
                "A new entry here means a new source was added — confirm it truly has historical " +
                "actuals (not just a forecast sliced into the past) before flipping the flag.",
            expectedTrue,
            actualTrue,
        )
    }

    @Test
    fun `forecast-only sources do not provide historical actuals`() {
        // No history endpoint / archive — their past hours are just forecast.
        assertEquals(false, WeatherSource.VISUAL_CROSSING.providesHistoricalActuals)
        assertEquals(false, WeatherSource.OPEN_WEATHER_MAP.providesHistoricalActuals)
        assertEquals(false, WeatherSource.TOMORROW_IO.providesHistoricalActuals)
    }
}

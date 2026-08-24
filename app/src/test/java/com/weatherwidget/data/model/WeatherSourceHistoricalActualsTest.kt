package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Locks the provider-history provenance policy. Every source must explicitly identify what its
 * past-hour product represents rather than collapsing station observations, reanalysis, archived
 * provider history, and forecast-only data into one misleading boolean.
 */
@Category(ShortDuration::class)
class WeatherSourceHistoricalActualsTest {

    @Test
    fun `every source has the expected historical data provenance`() {
        val expected = mapOf(
            WeatherSource.NWS to HistoricalDataKind.STATION_OBSERVATION,
            WeatherSource.OPEN_METEO to HistoricalDataKind.NONE,
            WeatherSource.WEATHER_API to HistoricalDataKind.ARCHIVED_PROVIDER_HISTORY,
            WeatherSource.SILURIAN to HistoricalDataKind.NONE,
            WeatherSource.TOMORROW_IO to HistoricalDataKind.RECENT_ANALYSIS,
            WeatherSource.VISUAL_CROSSING to HistoricalDataKind.NONE,
            WeatherSource.OPEN_WEATHER_MAP to HistoricalDataKind.NONE,
            WeatherSource.GENERIC_GAP to HistoricalDataKind.NONE,
            // Raw airport METARs from aviationweather.gov are station observations in the same
            // sense NWS's are — measured at a real site, not a provider's model output.
            WeatherSource.METAR to HistoricalDataKind.STATION_OBSERVATION,
        )
        assertEquals(
            "A new source must make an explicit provider-history provenance choice.",
            expected,
            WeatherSource.values().associateWith { it.historicalDataKind },
        )
    }

    @Test
    fun `forecast-only sources do not retain historical precipitation`() {
        assertEquals(
            false,
            WeatherSource.VISUAL_CROSSING.historicalDataKind.preservesHistoricalPrecipitation,
        )
        assertEquals(
            false,
            WeatherSource.OPEN_WEATHER_MAP.historicalDataKind.preservesHistoricalPrecipitation,
        )
        assertEquals(
            false,
            WeatherSource.SILURIAN.historicalDataKind.preservesHistoricalPrecipitation,
        )
        assertEquals(false, WeatherSource.OPEN_METEO.supportsHistoricalActualsBackfill)
        assertEquals(true, WeatherSource.TOMORROW_IO.supportsHistoricalActualsBackfill)
    }
}

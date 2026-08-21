package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the ONE rule deciding which sources draw an actual cloud curve
 * ([WeatherSource.supportsCloudActuals]). The write side (`HistoricalActualsBackfill`) and both
 * read sides (Android `ObservationDao` / desktop `DesktopWeatherDao`, via `MetarCloudBlender`)
 * follow it, so this set is the contract between them.
 */
@Category(ShortDuration::class)
class WeatherSourceCloudActualsTest {

    @Test
    fun `nws and every provider-history source support cloud actuals`() {
        val expected = setOf(
            WeatherSource.NWS,
            WeatherSource.OPEN_METEO,
            WeatherSource.WEATHER_API,
            WeatherSource.SILURIAN,
            WeatherSource.TOMORROW_IO,
        )
        assertEquals(expected, WeatherSource.entries.filter { it.supportsCloudActuals }.toSet())
    }

    @Test
    fun `forecast-only sources have no actual curve`() {
        val expected = setOf(
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.GENERIC_GAP,
        )
        assertEquals(expected, WeatherSource.entries.filterNot { it.supportsCloudActuals }.toSet())
    }

    /**
     * The cloud provenance gate must stay in lockstep with the precip gate it was derived from:
     * a source whose past values are never revised cannot file cloud actuals either.
     */
    @Test
    fun `cloud provenance gate matches the precipitation gate for every kind`() {
        HistoricalDataKind.entries.forEach { kind ->
            assertEquals(kind.name, kind.preservesHistoricalPrecipitation, kind.preservesHistoricalCloud)
        }
    }
}

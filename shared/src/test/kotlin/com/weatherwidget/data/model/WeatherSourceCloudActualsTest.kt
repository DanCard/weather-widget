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
    fun `only sources with verified observation or analysis cloud products support actuals`() {
        val expected = setOf(
            WeatherSource.NWS,
            WeatherSource.WEATHER_API,
            // aviationweather.gov serves the METAR sky-condition group itself — the same measured
            // product MetarSkyCover already reads on the NWS path, both as a decoded `clouds[]`
            // array and in the raw report. Verified, not inferred.
            WeatherSource.METAR,
            WeatherSource.TOMORROW_IO,
        )
        assertEquals(expected, WeatherSource.entries.filter { it.supportsCloudActuals }.toSet())
    }

    @Test
    fun `sources without a verified cloud actual product have no actual curve`() {
        val expected = setOf(
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.GENERIC_GAP,
            WeatherSource.SILURIAN,
            WeatherSource.OPEN_METEO,
        )
        assertEquals(expected, WeatherSource.entries.filterNot { it.supportsCloudActuals }.toSet())
    }

    /** Historical-data kinds default cloud eligibility to their precipitation provenance. A
     * source may still opt out more strictly when its own API documentation warrants it. */
    @Test
    fun `historical kinds default cloud provenance to their precipitation gate`() {
        HistoricalDataKind.entries.forEach { kind ->
            assertEquals(kind.name, kind.preservesHistoricalPrecipitation, kind.preservesHistoricalCloud)
        }
    }

    @Test
    fun `silurian forecast history is not a cloud actual product`() {
        assertEquals(HistoricalDataKind.NONE, WeatherSource.SILURIAN.historicalDataKind)
        assertEquals(false, WeatherSource.SILURIAN.supportsCloudActuals)
    }

    @Test
    fun `open meteo forecast api model history is not a cloud actual product`() {
        assertEquals(HistoricalDataKind.NONE, WeatherSource.OPEN_METEO.historicalDataKind)
        assertEquals(false, WeatherSource.OPEN_METEO.supportsCloudActuals)
    }
}

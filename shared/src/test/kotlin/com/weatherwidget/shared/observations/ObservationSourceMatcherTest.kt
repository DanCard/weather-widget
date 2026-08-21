package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationSourceMatcherTest {

    @Test
    fun `NWS keeps real station ids`() {
        assertTrue(ObservationSourceMatcher.matchesObservationSource("KNUQ", WeatherSource.NWS))
        assertTrue(ObservationSourceMatcher.matchesObservationSource("AW020", WeatherSource.NWS))
    }

    @Test
    fun `NWS excludes the synthetic blend and history-backfill rows`() {
        assertFalse(ObservationSourceMatcher.matchesObservationSource("NWS_BLEND", WeatherSource.NWS))
        // The NWS->Open-Meteo fallback mints this; it is not a station observation.
        assertFalse(ObservationSourceMatcher.matchesObservationSource("NWS_MAIN", WeatherSource.NWS))
    }

    @Test
    fun `NWS excludes other sources' backfill rows`() {
        assertFalse(ObservationSourceMatcher.matchesObservationSource("OPEN_METEO_MAIN", WeatherSource.NWS))
        assertFalse(ObservationSourceMatcher.matchesObservationSource("SILURIAN_MAIN", WeatherSource.NWS))
        assertFalse(ObservationSourceMatcher.matchesObservationSource("TOMORROW_IO_MAIN", WeatherSource.NWS))
    }

    @Test
    fun `non-NWS keeps its own backfill row and excludes other sources`() {
        // Forecast-only sources have no real stations, so the backfill row stays.
        assertTrue(ObservationSourceMatcher.matchesObservationSource("OPEN_METEO_MAIN", WeatherSource.OPEN_METEO))
        assertTrue(ObservationSourceMatcher.matchesObservationSource("SILURIAN_2", WeatherSource.SILURIAN))
        assertFalse(ObservationSourceMatcher.matchesObservationSource("WEATHER_API_MAIN", WeatherSource.SILURIAN))
    }

    @Test
    fun `Tomorrow stations list and actuals accept recent history and realtime provenance`() {
        assertTrue(
            ObservationSourceMatcher.matchesObservationSource(
                "TOMORROW_IO_RECENT_HISTORY",
                WeatherSource.TOMORROW_IO,
            ),
        )
        assertTrue(
            ObservationSourceMatcher.matchesObservationSource(
                "TOMORROW_IO_REALTIME",
                WeatherSource.TOMORROW_IO,
            ),
        )
        assertFalse(
            ObservationSourceMatcher.matchesObservationSource(
                "TOMORROW_IO_MAIN",
                WeatherSource.TOMORROW_IO,
            ),
        )
        assertTrue(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "TOMORROW_IO_RECENT_HISTORY",
                api = WeatherSource.TOMORROW_IO.id,
                source = WeatherSource.TOMORROW_IO,
            ),
        )
        assertTrue(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "TOMORROW_IO_REALTIME",
                api = WeatherSource.TOMORROW_IO.id,
                source = WeatherSource.TOMORROW_IO,
            ),
        )
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "TOMORROW_IO_MAIN",
                api = WeatherSource.TOMORROW_IO.id,
                source = WeatherSource.TOMORROW_IO,
            ),
        )
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "GAP",
                api = WeatherSource.GENERIC_GAP.id,
                source = WeatherSource.TOMORROW_IO,
            ),
        )
    }
}

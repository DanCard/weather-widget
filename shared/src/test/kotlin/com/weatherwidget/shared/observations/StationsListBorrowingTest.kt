package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The observations screen showed "No recent observations found for Open-Meteo" with 4,993 NWS rows
 * and 233 METAR rows sitting in the table, directly beneath an "Actuals source" picker naming the
 * feed it was refusing to show. Two independent causes, one test each below.
 */
@Category(ShortDuration::class)
class StationsListBorrowingTest {

    @Test
    fun `borrowing source lists its default provider's stations`() {
        assertTrue(
            "METAR is the default borrowed provider, so its stations belong in the list",
            ObservationSourceMatcher.matchesStationsList("KNUQ", "METAR", WeatherSource.OPEN_METEO),
        )
    }

    @Test
    fun `borrowing source follows the per-source preference`() {
        val preferNws: (WeatherSource) -> WeatherSource? = { WeatherSource.NWS }
        assertTrue(
            ObservationSourceMatcher.matchesStationsList("KNUQ", "NWS", WeatherSource.OPEN_METEO, preferNws),
        )
        assertFalse(
            "with NWS chosen the METAR feed must drop out, or the list mixes two providers",
            ObservationSourceMatcher.matchesStationsList("KNUQ", "METAR", WeatherSource.OPEN_METEO, preferNws),
        )
    }

    @Test
    fun `non-borrowing source keeps the station-ID rule`() {
        assertTrue(ObservationSourceMatcher.matchesStationsList("KNUQ", "NWS", WeatherSource.NWS))
        assertFalse(
            "NWS must still strip its own synthetic blend row",
            ObservationSourceMatcher.matchesStationsList("NWS_BLEND", "NWS", WeatherSource.NWS),
        )
        assertFalse(
            "NWS must not absorb the METAR feed just because both are measured",
            ObservationSourceMatcher.matchesStationsList("KNUQ", "METAR", WeatherSource.NWS),
        )
    }

    @Test
    fun `GENERIC_GAP is not a station`() {
        assertFalse(
            ObservationSourceMatcher.matchesStationsList("X", "Generic", WeatherSource.OPEN_METEO),
        )
    }

    /**
     * `fromId` ends in `else -> NWS`, so a missing entry mislabels rather than crashing: the picker
     * resolved METAR and rendered the row as "NWS".
     */
    @Test
    fun `fromId round-trips every source id`() {
        for (source in WeatherSource.entries) {
            assertEquals(
                "WeatherSource.fromId does not know ${source.id}; it silently answers NWS",
                source,
                WeatherSource.fromId(source.id),
            )
        }
    }
}

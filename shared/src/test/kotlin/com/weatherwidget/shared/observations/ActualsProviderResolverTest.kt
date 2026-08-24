package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ActualsProviderResolverTest {

    @Test
    fun `forecast-only sources borrow, sources with their own actuals do not`() {
        assertTrue(ActualsProviderResolver.borrows(WeatherSource.OPEN_METEO))
        assertTrue(ActualsProviderResolver.borrows(WeatherSource.SILURIAN))
        assertFalse(ActualsProviderResolver.borrows(WeatherSource.NWS))
        assertFalse(ActualsProviderResolver.borrows(WeatherSource.TOMORROW_IO))
        assertFalse(ActualsProviderResolver.borrows(WeatherSource.WEATHER_API))
    }

    /** METAR is the provider; it must never be treated as needing one. */
    @Test
    fun `metar does not borrow from itself`() {
        assertFalse(ActualsProviderResolver.borrows(WeatherSource.METAR))
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.METAR))
    }

    @Test
    fun `a borrowing source defaults to METAR`() {
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO))
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN))
    }

    @Test
    fun `a source with its own actuals resolves to itself`() {
        assertEquals("NWS", ActualsProviderResolver.providerIdFor(WeatherSource.NWS))
        assertEquals("TOMORROW_IO", ActualsProviderResolver.providerIdFor(WeatherSource.TOMORROW_IO))
    }

    /** The seam the future Settings picker plugs into. */
    @Test
    fun `a preference overrides the default for a borrowing source`() {
        assertEquals(
            "NWS",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.NWS },
        )
    }

    /** Borrowing is a remedy for absence, never a general substitution. */
    @Test
    fun `a preference cannot override a source that has its own actuals`() {
        assertEquals(
            "NWS",
            ActualsProviderResolver.providerIdFor(WeatherSource.NWS) { WeatherSource.METAR },
        )
    }

    /** A stale preference must degrade to the default, not leave the source with no curve again. */
    @Test
    fun `a preference naming a source with no actuals falls back to the default`() {
        assertEquals(
            "METAR",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.SILURIAN },
        )
    }

    @Test
    fun `a source cannot borrow from itself`() {
        assertEquals(
            "METAR",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.OPEN_METEO },
        )
    }

    @Test
    fun `candidates lead with METAR and exclude the synthetic filler`() {
        val candidates = ActualsProviderResolver.candidates()
        assertEquals(WeatherSource.METAR, candidates.first())
        assertFalse(WeatherSource.GENERIC_GAP in candidates)
        assertFalse("a borrowing source cannot be a provider", WeatherSource.OPEN_METEO in candidates)
        assertTrue(WeatherSource.NWS in candidates)
    }

    // ---- the shared predicate ----

    /** The regression this whole phase exists to fix. */
    @Test
    fun `a METAR row now drives Open-Meteo actuals`() {
        assertTrue(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "KNUQ", api = "METAR", source = WeatherSource.OPEN_METEO,
            ),
        )
    }

    @Test
    fun `a METAR row does not drive NWS actuals`() {
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "KNUQ", api = "METAR", source = WeatherSource.NWS,
            ),
        )
    }

    @Test
    fun `NWS rows still drive NWS actuals`() {
        assertTrue(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "KNUQ", api = "NWS", source = WeatherSource.NWS,
            ),
        )
    }

    /** Open-Meteo's own synthetic rows must not come back through the borrowed path. */
    @Test
    fun `an Open-Meteo row does not drive Open-Meteo actuals once it borrows`() {
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "OPEN_METEO_MAIN", api = "OPEN_METEO", source = WeatherSource.OPEN_METEO,
            ),
        )
    }

    @Test
    fun `the preference redirects which rows match`() {
        val preferNws: (WeatherSource) -> WeatherSource? = { WeatherSource.NWS }
        assertTrue(
            ObservationSourceMatcher.matchesActualSource(
                "KNUQ", "NWS", WeatherSource.OPEN_METEO, actualsPreference = preferNws,
            ),
        )
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                "KNUQ", "METAR", WeatherSource.OPEN_METEO, actualsPreference = preferNws,
            ),
        )
    }

    /** Tomorrow.io's station allowlist still applies on its own (non-borrowed) path. */
    @Test
    fun `tomorrow io keeps its station allowlist`() {
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                "SOME_OTHER", "TOMORROW_IO", WeatherSource.TOMORROW_IO,
            ),
        )
    }

    @Test
    fun `generic gap handling is unchanged`() {
        assertTrue(
            ObservationSourceMatcher.matchesActualSource("x", "Generic", WeatherSource.OPEN_METEO),
        )
        assertFalse(
            ObservationSourceMatcher.matchesActualSource(
                "x", "Generic", WeatherSource.OPEN_METEO, allowGenericGap = false,
            ),
        )
    }
}

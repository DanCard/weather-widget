package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ActualsProviderResolverTest {

    @org.junit.After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    // ---- the installed preference seam (what the Settings picker writes through) ----

    /**
     * Nine call sites read the resolver without passing a lookup, several inside pure blend code.
     * They pick up the platform's stored choice through this installed default. If it did not take
     * effect, the picker would appear to work and change nothing.
     */
    @Test
    fun `an installed preference is honoured by callers that pass no lookup`() {
        assertEquals("OPEN_METEO", ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO))
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN))
        ActualsProviderResolver.installPreferenceSource { src ->
            WeatherSource.NWS.takeIf { src == WeatherSource.OPEN_METEO }
        }
        assertEquals("NWS", ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO))
        // Only the source the lookup names is redirected.
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN))
    }

    /** The blend predicate must follow the installed preference too, not just providerIdFor. */
    @Test
    fun `the shared matcher follows the installed preference`() {
        ActualsProviderResolver.installPreferenceSource { WeatherSource.NWS }
        assertTrue(
            ObservationSourceMatcher.matchesActualSource("KNUQ", "NWS", WeatherSource.OPEN_METEO),
        )
        assertFalse(
            "METAR rows must stop feeding Open-Meteo once NWS is chosen",
            ObservationSourceMatcher.matchesActualSource("KNUQ", "METAR", WeatherSource.OPEN_METEO),
        )
    }

    @Test
    fun `resetting restores the default`() {
        ActualsProviderResolver.installPreferenceSource { WeatherSource.NWS }
        ActualsProviderResolver.resetPreferenceSource()
        assertEquals("OPEN_METEO", ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO))
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN))
    }

    /** An installed preference still cannot override a source that does not allow alternatives. */
    @Test
    fun `installed preference cannot redirect a non-alternative source`() {
        ActualsProviderResolver.installPreferenceSource { WeatherSource.SYNOPTIC }
        assertEquals("NWS", ActualsProviderResolver.providerIdFor(WeatherSource.NWS))
    }

    @Test
    fun `forecast-only sources borrow, sources with their own actuals do not`() {
        assertFalse(ActualsProviderResolver.borrows(WeatherSource.OPEN_METEO))
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
        assertEquals("METAR", ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN))
    }

    @Test
    fun `a source with its own actuals resolves to itself`() {
        assertEquals("OPEN_METEO", ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO))
        assertEquals("NWS", ActualsProviderResolver.providerIdFor(WeatherSource.NWS))
        assertEquals("TOMORROW_IO", ActualsProviderResolver.providerIdFor(WeatherSource.TOMORROW_IO))
    }

    /** The seam the Settings picker plugs into. */
    @Test
    fun `a preference overrides the default for an alternative-capable source`() {
        assertEquals(
            "NWS",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.NWS },
        )
        assertEquals(
            "NWS",
            ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN) { WeatherSource.NWS },
        )
    }

    /** Unconfigured sources without alternative support resolve to themselves. */
    @Test
    fun `a preference cannot override a source that does not allow alternative providers`() {
        assertEquals(
            "NWS",
            ActualsProviderResolver.providerIdFor(WeatherSource.NWS) { WeatherSource.METAR },
        )
    }

    /** A stale preference must degrade to the default, not leave the source with no curve again. */
    @Test
    fun `a preference naming a source with no actuals falls back to the default`() {
        assertEquals(
            "OPEN_METEO",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.SILURIAN },
        )
        assertEquals(
            "METAR",
            ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN) { WeatherSource.SILURIAN },
        )
    }

    @Test
    fun `a source cannot borrow from itself`() {
        assertEquals(
            "METAR",
            ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN) { WeatherSource.SILURIAN },
        )
    }

    @Test
    fun `candidates lead with METAR and exclude the synthetic filler`() {
        val candidates = ActualsProviderResolver.candidates()
        assertEquals(WeatherSource.METAR, candidates.first())
        assertFalse(WeatherSource.GENERIC_GAP in candidates)
        assertFalse("a borrowing source cannot be a provider", WeatherSource.SILURIAN in candidates)
        assertTrue(WeatherSource.OPEN_METEO in candidates)
        assertTrue(WeatherSource.NWS in candidates)
    }

    /**
     * The bug this guards: `supportsTemperatureActuals` defaults to TRUE, so filtering on it offered
     * OpenWeatherMap and Visual Crossing.
     *
     * OpenWeatherMap does have a live current-conditions endpoint, so the exclusion is not "no
     * product" — it is that the product names a CITY rather than a station, carries no history, and
     * is a blended analysis. See ActualsProviderResolver's KDoc for the evidence.
     */
    @Test
    fun `sources whose observations are their own forecast are never offered`() {
        val candidates = ActualsProviderResolver.candidates()
        assertFalse("OpenWeatherMap files its own model output", WeatherSource.OPEN_WEATHER_MAP in candidates)
        assertFalse("Visual Crossing has no historical product", WeatherSource.VISUAL_CROSSING in candidates)
        assertFalse(ActualsProviderResolver.canProvide(WeatherSource.OPEN_WEATHER_MAP))
        assertNull(ActualsProviderResolver.tierOf(WeatherSource.OPEN_WEATHER_MAP))
    }

    @Test
    fun `measured feeds are offered ahead of derived ones`() {
        val candidates = ActualsProviderResolver.candidates()
        assertEquals(
            listOf(WeatherSource.METAR, WeatherSource.NWS, WeatherSource.SYNOPTIC),
            candidates.filter { ActualsProviderResolver.tierOf(it) == ActualsProviderResolver.Tier.MEASURED },
        )
        val derived = candidates.filter { ActualsProviderResolver.tierOf(it) == ActualsProviderResolver.Tier.DERIVED }
        assertEquals(setOf(WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API, WeatherSource.TOMORROW_IO), derived.toSet())
        assertTrue(
            "every measured option precedes every derived one",
            candidates.indexOf(WeatherSource.SYNOPTIC) < candidates.indexOf(derived.first()),
        )
    }

    @Test
    fun `a preference naming a forecast-derived-only source falls back to the default`() {
        assertEquals(
            "OPEN_METEO",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.OPEN_WEATHER_MAP },
        )
        assertEquals(
            "METAR",
            ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN) { WeatherSource.OPEN_WEATHER_MAP },
        )
    }

    @Test
    fun `NWS and Synoptic are both selectable`() {
        assertEquals(
            "NWS",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.NWS },
        )
        assertEquals(
            "SYNOPTIC",
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO) { WeatherSource.SYNOPTIC },
        )
    }

    // ---- the shared predicate ----

    /** Silurian borrows METAR rows. */
    @Test
    fun `a METAR row now drives Silurian actuals`() {
        assertTrue(
            ObservationSourceMatcher.matchesActualSource(
                stationId = "KNUQ", api = "METAR", source = WeatherSource.SILURIAN,
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

    /** Open-Meteo rows drive Open-Meteo actuals by default. */
    @Test
    fun `an Open-Meteo row drives Open-Meteo actuals by default`() {
        assertTrue(
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
                "OPEN_METEO_MAIN", "OPEN_METEO", WeatherSource.OPEN_METEO, actualsPreference = preferNws,
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

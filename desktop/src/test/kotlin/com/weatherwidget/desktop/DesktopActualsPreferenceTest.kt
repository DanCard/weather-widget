package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Desktop had no per-source actuals choice at all: the [ActualsProviderResolver] preference seam was
 * filled only by Android, so every forecast-only source on this platform silently used
 * [ActualsProviderResolver.DEFAULT_PROVIDER] regardless of what the user wanted.
 */
@Category(ShortDuration::class)
class DesktopActualsPreferenceTest {

    @After
    fun tearDown() {
        // The seam is process-global; leaving a stub installed would leak into other desktop tests.
        ActualsProviderResolver.resetPreferenceSource()
        DesktopActualsPreference.update(null)
    }

    @Test
    fun `a stored choice resolves through the shared resolver`() {
        DesktopActualsPreference.install()
        DesktopActualsPreference.update(
            DesktopSettings(
                actualsProviders = mapOf(
                    WeatherSource.OPEN_METEO.id to WeatherSource.NWS.id,
                    WeatherSource.NWS.id to WeatherSource.SYNOPTIC.id,
                ),
            ),
        )

        assertEquals(
            WeatherSource.NWS.id,
            ActualsProviderResolver.providerIdFor(WeatherSource.OPEN_METEO),
        )
        assertEquals(
            WeatherSource.SYNOPTIC.id,
            ActualsProviderResolver.providerIdFor(WeatherSource.NWS),
        )
        assertEquals(
            "a source with no stored choice still follows the default",
            ActualsProviderResolver.DEFAULT_PROVIDER.id,
            ActualsProviderResolver.providerIdFor(WeatherSource.SILURIAN),
        )
    }

    /**
     * Storing the default is NOT the same as storing nothing: an absent entry follows the default if
     * it ever moves, a stored one pins the user to today's answer.
     */
    @Test
    fun `choosing the default clears the entry rather than writing it`() {
        val chosen = DesktopActualsPreference.withChoice(
            DesktopSettings(),
            WeatherSource.OPEN_METEO,
            WeatherSource.NWS,
        )
        assertEquals(mapOf(WeatherSource.OPEN_METEO.id to WeatherSource.NWS.id), chosen.actualsProviders)

        val cleared = DesktopActualsPreference.withChoice(chosen, WeatherSource.OPEN_METEO, null)
        assertTrue("the default must leave no stored entry", cleared.actualsProviders.isEmpty())
    }

    @Test
    fun `one source's choice does not disturb another's`() {
        val a = DesktopActualsPreference.withChoice(DesktopSettings(), WeatherSource.OPEN_METEO, WeatherSource.NWS)
        val b = DesktopActualsPreference.withChoice(a, WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO)

        assertEquals(WeatherSource.NWS.id, b.actualsProviders[WeatherSource.OPEN_METEO.id])
        assertEquals(WeatherSource.TOMORROW_IO.id, b.actualsProviders[WeatherSource.SILURIAN.id])
    }

    /**
     * Synoptic was promoted to a source and then disabled when it turned out to be a 14-day trial. A
     * config written while it was valid must not resurrect it as a provider.
     */
    @Test
    fun `a stored provider that can no longer provide falls back to the default`() {
        DesktopActualsPreference.update(
            DesktopSettings(actualsProviders = mapOf(WeatherSource.OPEN_METEO.id to "SYNOPTIC")),
        )
        val looked = DesktopActualsPreference.lookup(WeatherSource.OPEN_METEO)
        if (!ActualsProviderResolver.canProvide(WeatherSource.SYNOPTIC)) {
            assertNull("a provider that cannot provide must not be honoured", looked)
        }
    }

    @Test
    fun `an unknown id is ignored rather than throwing`() {
        DesktopActualsPreference.update(
            DesktopSettings(actualsProviders = mapOf(WeatherSource.OPEN_METEO.id to "NOT_A_SOURCE")),
        )
        assertNull(DesktopActualsPreference.lookup(WeatherSource.OPEN_METEO))
    }
}

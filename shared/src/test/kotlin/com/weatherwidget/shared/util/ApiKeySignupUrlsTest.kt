package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards the API-key signup URL single source of truth (now on [WeatherSource] itself).
 * The liveness check (real network requests) lives in `ApiKeySignupUrlLivenessTest` as
 * LongDuration; this is the fast structural test that always runs.
 */
@Category(ShortDuration::class)
class ApiKeySignupUrlsTest {

    @Test
    fun everyKeyRequiringSourceHasAnHttpsSignupUrl() {
        for (source in ApiKeySignupUrls.sourcesRequiringKeys) {
            val url = source.signupUrl
            assertTrue(
                "signup URL for ${source.id} must be https, got: $url",
                url.startsWith("https://"),
            )
        }
    }

    @Test
    fun sourcesRequiringKeysMatchesTheConfigurableKeyedSources() {
        // Every configurable source that needs a key must be listed here, and vice versa.
        val expected = setOf(
            WeatherSource.TOMORROW_IO,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
        )
        assertEquals(expected, ApiKeySignupUrls.sourcesRequiringKeys.toSet())
    }

    /**
     * The only static key fact: whether a key is needed at all. Whether the USER must type one is
     * a per-build question (baked key or not) and is answered by each platform's Settings screen.
     */
    @Test
    fun requiresApiKeyIdentifiesTheKeyedSources() {
        assertTrue(WeatherSource.OPEN_WEATHER_MAP.requiresApiKey)
        assertTrue(WeatherSource.WEATHER_API.requiresApiKey)
        assertTrue(WeatherSource.TOMORROW_IO.requiresApiKey)
        assertTrue(WeatherSource.SILURIAN.requiresApiKey)
        assertFalse(WeatherSource.NWS.requiresApiKey)
        assertFalse(WeatherSource.OPEN_METEO.requiresApiKey)
    }

    @Test
    fun nonKeyedSourcesFallBackToOpenMeteoCom() {
        // NWS / Open-Meteo are free and keyless; the fallback URL just points the user at the
        // open-meteo landing page rather than a signup form.
        for (source in listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.GENERIC_GAP)) {
            assertFalse(
                "$source should not be in sourcesRequiringKeys",
                source in ApiKeySignupUrls.sourcesRequiringKeys,
            )
            assertEquals(
                "https://open-meteo.com",
                source.signupUrl,
            )
        }
    }
}

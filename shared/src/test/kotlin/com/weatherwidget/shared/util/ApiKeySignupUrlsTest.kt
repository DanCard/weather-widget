package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards the API-key signup URL single source of truth. The liveness check (real network
 * requests) lives in `ApiKeySignupUrlLivenessTest` as LongDuration; this is the fast
 * structural test that always runs.
 */
@Category(ShortDuration::class)
class ApiKeySignupUrlsTest {

    @Test
    fun everyKeyRequiringSourceHasAnHttpsSignupUrl() {
        for (source in ApiKeySignupUrls.sourcesRequiringKeys) {
            val url = ApiKeySignupUrls.signupUrl(source)
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

    @Test
    fun requiresUserKeyIdentifiesSourcesNeedingUserKeys() {
        assertTrue(ApiKeySignupUrls.requiresUserKey(WeatherSource.OPEN_WEATHER_MAP))
        assertTrue(ApiKeySignupUrls.requiresUserKey(WeatherSource.WEATHER_API))
        assertTrue(ApiKeySignupUrls.requiresUserKey(WeatherSource.TOMORROW_IO))
        assertFalse(ApiKeySignupUrls.requiresUserKey(WeatherSource.SILURIAN))
        assertFalse(ApiKeySignupUrls.requiresUserKey(WeatherSource.NWS))
        assertFalse(ApiKeySignupUrls.requiresUserKey(WeatherSource.OPEN_METEO))
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
                ApiKeySignupUrls.signupUrl(source),
            )
        }
    }
}

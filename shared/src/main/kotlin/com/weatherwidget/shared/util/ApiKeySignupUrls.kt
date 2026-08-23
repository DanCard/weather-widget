package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * Signup/API-key pages for the key-requiring providers, surfaced by the Settings "Get key…"
 * buttons. Kept to top-level, stable entry points — deep "developer console" paths churn; the
 * provider's signup page is where a keyless user needs to land.
 *
 * Lives in `:shared` so both the Android `SettingsActivity` and the desktop `SettingsWindow`
 * read the same URLs. The accompanying `ApiKeySignupUrlLivenessTest` (also in `:shared`) verifies
 * the links aren't stale.
 *
 * Phase 1 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`).
 */
object ApiKeySignupUrls {

    val sourcesRequiringKeys: List<WeatherSource> =
        listOf(
            WeatherSource.TOMORROW_IO,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
            WeatherSource.OPEN_WEATHER_MAP,
        )

    fun signupUrl(source: WeatherSource): String = when (source) {
        WeatherSource.TOMORROW_IO -> "https://app.tomorrow.io/signup"
        // Product app (also the host production keys authenticate against, see SilurianApi.kt);
        // the marketing root silurian.ai has no signup or key path.
        WeatherSource.SILURIAN -> "https://earth.weather.silurian.ai"
        WeatherSource.WEATHER_API -> "https://www.weatherapi.com/signup.aspx"
        WeatherSource.OPEN_WEATHER_MAP -> "https://home.openweathermap.org/users/sign_up"
        else -> "https://open-meteo.com"
    }

    fun requiresUserKey(source: WeatherSource): Boolean = when (source) {
        WeatherSource.OPEN_WEATHER_MAP,
        WeatherSource.WEATHER_API,
        WeatherSource.TOMORROW_IO,
        WeatherSource.VISUAL_CROSSING -> true
        WeatherSource.SILURIAN,
        WeatherSource.NWS,
        WeatherSource.OPEN_METEO,
        WeatherSource.GENERIC_GAP -> false
    }
}

package com.weatherwidget.ui

import com.weatherwidget.data.model.WeatherSource

/**
 * Signup/API-key pages for the key-requiring providers, surfaced by the Settings "Get key…"
 * buttons. Kept to top-level, stable entry points — deep "developer console" paths churn; the
 * provider's signup page is where a keyless user needs to land.
 *
 * Extracted from SettingsActivity so ApiKeySignupUrlLivenessTest can verify the links aren't
 * stale without inflating the activity.
 */
object ApiKeySignupUrls {

    val sourcesRequiringKeys: List<WeatherSource> =
        listOf(
            WeatherSource.TOMORROW_IO,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.OPEN_WEATHER_MAP,
        )

    fun signupUrl(source: WeatherSource): String = when (source) {
        WeatherSource.TOMORROW_IO -> "https://app.tomorrow.io/signup"
        // Product app (also the host production keys authenticate against, see SilurianApi.kt);
        // the marketing root silurian.ai has no signup or key path.
        WeatherSource.SILURIAN -> "https://earth.weather.silurian.ai"
        WeatherSource.WEATHER_API -> "https://www.weatherapi.com/signup.aspx"
        WeatherSource.VISUAL_CROSSING -> "https://www.visualcrossing.com/sign-up"
        WeatherSource.OPEN_WEATHER_MAP -> "https://home.openweathermap.org/users/sign_up"
        else -> "https://open-meteo.com"
    }
}

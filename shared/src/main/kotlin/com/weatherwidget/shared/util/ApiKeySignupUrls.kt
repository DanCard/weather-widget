package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * The list of configurable sources that need an API key, shown in the Settings "API Keys"
 * section. Derived from [WeatherSource.requiresApiKey] so it stays in sync automatically.
 *
 * Signup URLs and key-requirement flags now live on [WeatherSource] itself as constructor
 * params (`source.signupUrl`, `source.requiresUserEnteredKey`). The accompanying
 * [ApiKeySignupUrlLivenessTest] verifies the signup links aren't stale.
 *
 * Phase 1 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`).
 */
object ApiKeySignupUrls {

    val sourcesRequiringKeys: List<WeatherSource> =
        WeatherSourceOrdering.ALL_CONFIGURABLE.filter { it.requiresApiKey }
}

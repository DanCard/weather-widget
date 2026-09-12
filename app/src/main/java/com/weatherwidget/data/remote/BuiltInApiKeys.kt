package com.weatherwidget.data.remote

import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager

/**
 * The key a source will actually fetch with: the user's, else the one this build baked from
 * `local.properties` (see `app/build.gradle.kts` `buildConfigField`s; blank when the property was
 * absent, as on a build without the file).
 *
 * One resolver for both the fetch path (`AppModule` providers) and the Settings toggle gate, so
 * Settings can never demand a key the fetch path already has, nor enable a source the fetch path
 * cannot serve. Mirrors `needsKeyFromUser` / `DesktopApiKeys.DEFAULTS` on desktop.
 */
object BuiltInApiKeys {
    /** This build's baked key for [source], or null when none was baked or the source is keyless. */
    fun baked(source: WeatherSource): String? = when (source) {
        WeatherSource.OPEN_WEATHER_MAP -> BuildConfig.OPEN_WEATHER_MAP_API_KEY
        WeatherSource.SILURIAN -> BuildConfig.SILURIAN_API_KEY
        WeatherSource.TOMORROW_IO -> BuildConfig.TOMORROW_IO_API_KEY
        WeatherSource.WEATHER_API -> BuildConfig.WEATHER_API_KEY
        WeatherSource.VISUAL_CROSSING -> BuildConfig.VISUAL_CROSSING_API_KEY
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** User-entered key wins; a missing or blank one falls back to [baked]. */
    fun effectiveKey(source: WeatherSource, widgetStateManager: WidgetStateManager): String? =
        widgetStateManager.getApiKey(source)?.trim()?.takeIf { it.isNotBlank() } ?: baked(source)

    fun hasEffectiveKey(source: WeatherSource, widgetStateManager: WidgetStateManager): Boolean =
        effectiveKey(source, widgetStateManager) != null
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * English default descriptions for [WeatherSource], used as the Settings row subtext. The desktop
 * has no localization layer, so this is its single source of truth. Android keeps its localized
 * `R.string.api_source_*_desc` overrides (`strings.xml:48-53`, 15 locales) and only falls back
 * here when a source is added without a string resource.
 *
 * Text mirrors the English `strings.xml` so the desktop matches the Android English rendering.
 *
 * Phase 1 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`).
 */
object WeatherSourceDescriptions {

    fun describe(source: WeatherSource): String = when (source) {
        WeatherSource.SILURIAN -> "Silurian.ai — shown as Silur (global coverage)"
        WeatherSource.NWS -> "National Weather Service (US only)"
        WeatherSource.TOMORROW_IO -> "Tomorrow.io — shown as Tmrw (global coverage)"
        WeatherSource.VISUAL_CROSSING -> "Visual Crossing — shown as VisCr (global coverage)"
        WeatherSource.OPEN_METEO -> "Open-Meteo — shown as Meteo (global coverage)"
        WeatherSource.WEATHER_API -> "WeatherAPI — shown as WAPI (global coverage)"
        WeatherSource.OPEN_WEATHER_MAP -> "OpenWeatherMap — shown as OWM (global coverage)"
        WeatherSource.GENERIC_GAP -> "Synthetic climate-normal fallback (never user-selectable)"
        WeatherSource.METAR -> "Airport METAR observations (actuals only, never user-selectable)"
    }
}

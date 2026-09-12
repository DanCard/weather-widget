package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the effective Android WeatherAPI credential without exposing it to setup diagnostics.
 * A user-entered key wins; a missing/blank user key falls back to the build-time release key.
 */
@Singleton
class WeatherApiCredentialProvider
    @Inject
    constructor(
        private val widgetStateManager: WidgetStateManager,
    ) {
        fun get(): String? = BuiltInApiKeys.effectiveKey(WeatherSource.WEATHER_API, widgetStateManager)

        fun isConfigured(): Boolean = get() != null
    }

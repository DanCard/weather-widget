package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.ForecastDao

object ActiveLocationResolver {
    suspend fun resolve(
        context: Context,
        stateManager: WidgetStateManager,
        forecastDao: ForecastDao
    ): Pair<Double, Double> {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val configuredLocation = appWidgetIds.toList().firstNotNullOfOrNull { id ->
            stateManager.getWidgetLocation(id)
        }
        return configuredLocation
            ?: forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }
            ?: (WeatherWidgetWorker.DEFAULT_LAT to WeatherWidgetWorker.DEFAULT_LON)
    }
}

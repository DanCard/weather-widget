package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WidgetStateManager

/**
 * Resolves the database, physical site, displayed source, and freshness used by one widget
 * interaction. This component is deliberately side-effect free: deciding to schedule work happens
 * only after the caller has enough context to render cached data.
 */
internal class WidgetRefreshContextResolver(
    private val databaseProvider: (Context) -> WeatherDatabase = WeatherDatabase::getDatabase,
    private val sourceSuccessAt: (Context, String, Double, Double) -> Long =
        FetchMetadata::getLastForecastSourceSuccessTime,
) {
    data class Location(
        val lat: Double,
        val lon: Double,
    )

    data class Resolved(
        val database: WeatherDatabase,
        val forecastDao: ForecastDao,
        val location: Location,
        val displaySource: WeatherSource,
        val latestSuccessfulOrContentAtMs: Long?,
    )

    suspend fun resolve(context: Context, appWidgetId: Int): Resolved {
        val database = databaseProvider(context)
        val forecastDao = database.forecastDao()
        val stateManager = WidgetStateManager(context)
        // The app has one active site. Per-widget coordinate keys are synchronized compatibility
        // copies; the canonical resolver also neutralizes divergent legacy keys.
        val targetLocation = ActiveLocationResolver.resolve(context, stateManager, forecastDao)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val content = forecastDao.getLatestForecastBySource(
            displaySource.id,
            targetLocation.first,
            targetLocation.second,
        )
        val successfulCheckAt =
            if (content == null) {
                null
            } else {
                sourceSuccessAt(
                    context,
                    displaySource.id,
                    targetLocation.first,
                    targetLocation.second,
                ).takeIf { it > 0L }
            }

        return Resolved(
            database = database,
            forecastDao = forecastDao,
            location = Location(targetLocation.first, targetLocation.second),
            displaySource = displaySource,
            latestSuccessfulOrContentAtMs = freshestAt(content?.fetchedAt, successfulCheckAt),
        )
    }

    companion object {
        /**
         * A provider can confirm unchanged content without rewriting its row. The successful-check
         * timestamp therefore wins when it is newer; row age remains the bootstrap fallback.
         */
        internal fun freshestAt(contentAtMs: Long?, successfulCheckAtMs: Long?): Long? =
            listOfNotNull(contentAtMs, successfulCheckAtMs).maxOrNull()
    }
}

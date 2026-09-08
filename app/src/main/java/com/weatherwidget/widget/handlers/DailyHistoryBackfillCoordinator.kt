package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.ActualsReadScope
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Coordinates missing data and observation backfill checks for daily view widgets.
 * Extracted from [DailyViewHandler].
 */
object DailyHistoryBackfillCoordinator {

    const val HISTORY_BACKFILL_VISIBLE_DAYS = 7L

    fun shouldProbeHistoryBackfill(
        displaySource: WeatherSource,
        centerDate: LocalDate,
        today: LocalDate,
        visibleDays: Long = HISTORY_BACKFILL_VISIBLE_DAYS,
    ): Boolean =
        displaySource == WeatherSource.NWS && !centerDate.isBefore(today.minusDays(visibleDays))

    suspend fun maybeBackfillIncompleteHistory(
        context: Context,
        database: WeatherDatabase,
        repository: WeatherRepository?,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        centerDate: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
    ) {
        if (repository == null) return
        if (!shouldProbeHistoryBackfill(displaySource, centerDate, today)) return
        if (hourlyBackfillCoolingDown(stateManager, appWidgetId, displaySource, lat, lon)) return

        val graphStart = now.minusHours(WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS)
        val minEpoch = graphStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val maxEpoch = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val observations = repository.getObservationsInRange(
            minEpoch,
            maxEpoch,
            lat,
            lon,
            ActualsReadScope.apisFor(displaySource),
        )
        maybeEnqueueHourlyObservationBackfill(
            context = context,
            database = database,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            displaySource = displaySource,
            graphStart = graphStart,
            graphEnd = now,
            observations = observations,
            repositoryPresent = true,
            observationsLat = lat,
            observationsLon = lon,
        )
    }

    suspend fun requestMissingDataRefresh(
        context: Context,
        appLogDao: AppLogDao,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        refreshType: String,
        cooldownMs: Long,
        logTag: String,
        forceRefresh: Boolean,
        reason: String,
        message: String,
    ) {
        if (!stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, refreshType, cooldownMs)) {
            return
        }
        appLogDao.log(logTag, message, "INFO")
        WidgetWorkScheduler.enqueueRedundantImmediateSync(
            context = context,
            forceRefresh = forceRefresh,
            reason = reason,
        )
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, refreshType)
    }
}

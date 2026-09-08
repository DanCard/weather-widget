package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.CloudCoverViewHandler
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.ObservationData
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import com.weatherwidget.widget.handlers.WeatherData
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Dispatches widget rendering to the appropriate view handler based on [effectiveViewMode].
 * Extracted from [WidgetRenderer].
 */
object WidgetViewModeDispatcher {

    data class DispatchParams(
        val context: Context,
        val appWidgetManager: AppWidgetManager,
        val appWidgetId: Int,
        val effectiveViewMode: ViewMode,
        val stateManager: WidgetStateManager,
        val sourceFilteredHourly: List<HourlyForecastEntity>,
        val nowCenteredHourlyForecasts: List<HourlyForecastEntity>,
        val unifiedHourlyForecasts: List<HourlyForecastEntity>,
        val weatherList: List<ForecastEntity>,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        val currentTemps: List<ObservationEntity>,
        val dailyActualsBySource: DailyActualsBySource,
        val centerTime: LocalDateTime,
        val displaySource: WeatherSource,
        val targetPrecip: Int?,
        val observation: ObservationResolver.ObservedCurrentTemperature?,
        val repository: WeatherRepository?,
        val startupToken: String?,
        val uiOnly: Boolean,
        val partialPush: Boolean,
        val origin: WidgetPushDispatcher.Origin,
        val sourceMissingFromLoad: Boolean,
        val dataWatermarkMs: Long?,
        val paintOwed: Boolean,
        val fullyPaintedDailyWidgetIds: MutableSet<Int>,
    )

    suspend fun dispatch(params: DispatchParams) {
        when (params.effectiveViewMode) {
            ViewMode.TEMPERATURE -> {
                TemperatureViewHandler.updateWidget(
                    context = params.context,
                    appWidgetManager = params.appWidgetManager,
                    appWidgetId = params.appWidgetId,
                    hourlyForecasts = params.sourceFilteredHourly,
                    currentTempHourlyForecasts = params.nowCenteredHourlyForecasts,
                    centerTime = params.centerTime,
                    displaySource = params.displaySource,
                    precipProbability = params.targetPrecip,
                    lastObservedTemp = params.observation?.temperature,
                    observedAt = params.observation?.observedAt,
                    repository = params.repository,
                    startupToken = params.startupToken,
                    deferCurrentTempResolution = params.startupToken != null,
                    uiOnly = params.uiOnly,
                    partialPush = params.partialPush,
                    origin = params.origin,
                    sourceMissingFromLoad = params.sourceMissingFromLoad,
                    dataWatermarkMs = params.dataWatermarkMs,
                    paintOwed = params.paintOwed,
                )
            }
            ViewMode.PRECIPITATION -> {
                PrecipViewHandler.updateWidget(
                    context = params.context,
                    appWidgetManager = params.appWidgetManager,
                    appWidgetId = params.appWidgetId,
                    hourlyForecasts = params.sourceFilteredHourly,
                    centerTime = params.centerTime,
                    precipProbability = params.targetPrecip,
                    lastObservedTemp = params.observation?.temperature,
                    observedAt = params.observation?.observedAt,
                    repository = params.repository,
                    startupToken = params.startupToken,
                    uiOnly = params.uiOnly,
                    partialPush = params.partialPush,
                    origin = params.origin,
                    sourceMissingFromLoad = params.sourceMissingFromLoad,
                    dataWatermarkMs = params.dataWatermarkMs,
                    paintOwed = params.paintOwed,
                )
            }
            ViewMode.CLOUD_COVER -> {
                CloudCoverViewHandler.updateWidget(
                    context = params.context,
                    appWidgetManager = params.appWidgetManager,
                    appWidgetId = params.appWidgetId,
                    hourlyForecasts = params.sourceFilteredHourly,
                    centerTime = params.centerTime,
                    displaySource = params.displaySource,
                    precipProbability = params.targetPrecip,
                    lastObservedTemp = params.observation?.temperature,
                    observedAt = params.observation?.observedAt,
                    repository = params.repository,
                    startupToken = params.startupToken,
                    uiOnly = params.uiOnly,
                    partialPush = params.partialPush,
                    origin = params.origin,
                    sourceMissingFromLoad = params.sourceMissingFromLoad,
                    dataWatermarkMs = params.dataWatermarkMs,
                    paintOwed = params.paintOwed,
                )
            }
            ViewMode.DAILY -> {
                val transientPending = params.stateManager.hasTransientMessagePending(
                    params.appWidgetId,
                    WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
                )
                if (WidgetRenderer.shouldSkipDailyUiOnlyRepaint(
                        params.uiOnly,
                        params.fullyPaintedDailyWidgetIds.contains(params.appWidgetId),
                    ) && !transientPending
                ) {
                    WeatherDatabase.getDatabase(params.context).appLogDao().log(
                        com.weatherwidget.widget.WidgetPerfLogger.TAG_WIDGET_PAINT,
                        "widget=${params.appWidgetId} caller=DAILY origin=${params.origin.name} state=skipped_ui_only thread=${Thread.currentThread().name}",
                    )
                    return
                }
                DailyViewHandler.updateWidget(
                    context = params.context,
                    appWidgetManager = params.appWidgetManager,
                    appWidgetId = params.appWidgetId,
                    weatherData = WeatherData(
                        weatherList = params.weatherList,
                        forecastSnapshots = params.forecastSnapshots,
                        hourlyForecasts = params.unifiedHourlyForecasts,
                        currentTemps = params.currentTemps,
                        dailyActualsBySource = params.dailyActualsBySource,
                    ),
                    observationData = ObservationData(
                        lastObservedTemp = params.observation?.temperature,
                        observedAt = params.observation?.observedAt,
                        currentTempHourlyForecasts = params.nowCenteredHourlyForecasts,
                    ),
                    now = LocalDateTime.now(),
                    startupToken = params.startupToken,
                    stateManagerNullable = params.stateManager,
                    repository = params.repository,
                    partialPush = params.partialPush,
                    origin = params.origin,
                )
                params.fullyPaintedDailyWidgetIds.add(params.appWidgetId)
            }
        }
    }
}

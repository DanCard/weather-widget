package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.local.getLatestForecastsInRange
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.DailyActualsLoader
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetQueryWindows
import com.weatherwidget.widget.WidgetPushDispatcher
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Owns daily-view bounds, loading, current-temperature assembly, and RemoteViews dispatch. */
internal object DailyInteractionRenderer {
    private const val DAILY_LOOKBACK_DAYS = 30L

    /** Single source of truth, shared with the startup and worker paths. */
    private const val DAILY_FORECAST_DAYS = WidgetQueryWindows.DAILY_FORECAST_DAYS

    data class DailyRenderRequest(
        val context: Context,
        val appWidgetId: Int,
        val refreshContext: WidgetRefreshContextResolver.Resolved,
        val now: LocalDateTime,
        val repository: WeatherRepository? = null,
        val startTimeMs: Long = SystemClock.elapsedRealtime(),
        val actionTag: String = "DAILY_REFRESH",
        val extraMetadata: String = "",
        val partialPush: Boolean = false,
        val origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    )

    data class DailyRenderData(
        val weatherListRaw: List<ForecastEntity>,
        val dailyActuals: DailyActualsBySource,
    )

    internal data class TimeBounds(
        val today: LocalDate,
        val todayStartMs: Long,
        val hourlyStartMs: Long,
        val hourlyEndMs: Long,
    )

    internal fun timeBounds(now: LocalDateTime, zoneId: ZoneId): TimeBounds {
        val today = now.toLocalDate()
        return TimeBounds(
            today = today,
            todayStartMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            hourlyStartMs =
                now.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)
                    .atZone(zoneId).toInstant().toEpochMilli(),
            hourlyEndMs =
                now.plusHours(WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS)
                    .atZone(zoneId).toInstant().toEpochMilli(),
        )
    }

    suspend fun navigate(
        request: DailyRenderRequest,
        isLeft: Boolean,
    ) {
        val context = request.context
        val appWidgetId = request.appWidgetId
        val database = request.refreshContext.database
        val location = request.refreshContext.location
        val stateManager = WidgetStateManager(context)
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val zoneId = ZoneId.systemDefault()
        val timeBounds = timeBounds(request.now, zoneId)
        val today = timeBounds.today
        val range = rangeFor(today)
        val cachedData = loadData(context, database, location.lat, location.lon, today, range)
        val weatherList =
            ClimateGapFiller(database.climateNormalDao()).appendGaps(
                cachedData.weatherListRaw,
                location.lat,
                location.lon,
                today,
                horizonDays = DAILY_FORECAST_DAYS,
            )

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val skipYesterday = NavigationUtils.shouldSkipYesterday(numColumns = numColumns)
        val availableForecastDates =
            weatherList.map {
                LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY)
            }.toSet()
        val availableObsDates = cachedData.dailyActuals.values.flatMap { it.keys }.toSet()
        val availableDates = (availableForecastDates + availableObsDates).distinct().sorted()
        val minDate = availableDates.firstOrNull()
        val maxDate = availableDates.lastOrNull()

        val canNavigate: Boolean
        val navDebug: String
        if (isLeft) {
            val (newLeftmost, _) =
                NavigationUtils.getVisibleDateRange(
                    today,
                    currentOffset - 1,
                    numColumns,
                    skipYesterday,
                )
            canNavigate = minDate != null && minDate.isBefore(newLeftmost.plusDays(1))
            navDebug = "LEFT: newLeftmost=$newLeftmost, minDate=$minDate"
        } else {
            val (_, newRightmost) =
                NavigationUtils.getVisibleDateRange(
                    today,
                    currentOffset + 1,
                    numColumns,
                    skipYesterday,
                )
            canNavigate = maxDate != null && maxDate.isAfter(newRightmost.minusDays(1))
            navDebug = "RIGHT: newRightmost=$newRightmost, maxDate=$maxDate"
        }
        val direction = if (isLeft) "LEFT" else "RIGHT"
        val appLogDao = database.appLogDao()
        appLogDao.log(
            "DAILY_NAV_ATTEMPT",
            "widget=$appWidgetId dir=$direction offset=$currentOffset cols=$numColumns " +
                "rows=${dimensions.rows} skipYesterday=$skipYesterday source=${displaySource.id} " +
                "minDate=$minDate maxDate=$maxDate $navDebug canNavigate=$canNavigate",
        )
        if (!canNavigate) {
            appLogDao.log(
                "DAILY_NAV_BLOCKED",
                "widget=$appWidgetId dir=$direction offset=$currentOffset cols=$numColumns " +
                    "skipYesterday=$skipYesterday source=${displaySource.id} minDate=$minDate maxDate=$maxDate",
            )
            return
        }

        val newOffset =
            if (isLeft) stateManager.navigateLeft(appWidgetId)
            else stateManager.navigateRight(appWidgetId)
        appLogDao.log(
            "DAILY_NAV_APPLY",
            "widget=$appWidgetId dir=$direction offset=$currentOffset->$newOffset source=${displaySource.id}",
        )
        render(
            request.copy(actionTag = "DAILY_NAV", extraMetadata = "dir=$direction"),
            cachedData,
        )
    }

    suspend fun render(
        request: DailyRenderRequest,
        preloaded: DailyRenderData? = null,
    ) {
        val context = request.context
        val database = request.refreshContext.database
        val location = request.refreshContext.location
        val lat = location.lat
        val lon = location.lon
        val timeBounds = timeBounds(request.now, ZoneId.systemDefault())
        val today = timeBounds.today
        val range = rangeFor(today)
        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val gapFiller = ClimateGapFiller(database.climateNormalDao())
        val loaded = preloaded ?: loadData(context, database, lat, lon, today, range)
        val finalWeatherList =
            gapFiller.appendGaps(
                loaded.weatherListRaw,
                lat,
                lon,
                today,
                horizonDays = DAILY_FORECAST_DAYS,
            )
        val pastSnapshots =
            forecastDao.getLatestForecastsInRange(
                range.historyStart,
                range.pastSnapshotEnd,
                lat,
                lon,
            )
        val recentSnapshots =
            forecastDao.getAllForecastsInRange(
                range.recentSnapshotStart,
                range.forecastEnd,
                lat,
                lon,
            )
        val forecastSnapshots =
            gapFiller.appendGapsToSnapshots(
                (pastSnapshots + recentSnapshots)
                    .groupBy {
                        LocalDate.ofEpochDay(
                            it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY,
                        )
                    },
                lat,
                lon,
                today,
                horizonDays = DAILY_FORECAST_DAYS,
            )

        val hourlyForecasts =
            GraphDataLoader.unifyToNearestSite(
                hourlyDao.getHourlyForecasts(
                    timeBounds.hourlyStartMs,
                    timeBounds.hourlyEndMs,
                    lat,
                    lon,
                ),
                lat,
                lon,
            )
        val currentTemps =
            request.repository?.getMainObservationsWithComputedNwsBlend(
                lat,
                lon,
                timeBounds.todayStartMs,
            ) ?: database.observationDao().getLatestMainObservations(
                lat,
                lon,
                timeBounds.todayStartMs,
            )
        val currentTempHourlyForecasts =
            GraphDataLoader.loadCurrentTempResolutionHourlyForecasts(
                hourlyDao,
                lat,
                lon,
                request.now,
            )
        val stateManager = WidgetStateManager(context)
        val displaySource = stateManager.getCurrentDisplaySource(request.appWidgetId)
        val graphStyleObservation =
            CurrentTempResolver.resolveGraphStyleCurrentTemp(
                repository = request.repository,
                lat = lat,
                lon = lon,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts,
                now = request.now,
                personalStationWeight = stateManager.getPersonalStationWeight(),
            )
        val observation =
            graphStyleObservation
                ?: ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
        val smoothedForecasts =
            CurrentTemperatureResolver.computeSmoothedForecasts(
                currentTempHourlyForecasts.map { it.toHourlyForecast() },
                displaySource.id,
            )

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = AppWidgetManager.getInstance(context),
            appWidgetId = request.appWidgetId,
            weatherData =
                WeatherData(
                    weatherList = finalWeatherList,
                    forecastSnapshots = forecastSnapshots,
                    hourlyForecasts = hourlyForecasts,
                    currentTemps = currentTemps,
                    dailyActualsBySource = loaded.dailyActuals,
                ),
            observationData =
                ObservationData(
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    smoothedForecasts = smoothedForecasts,
                    currentTempHourlyForecasts = currentTempHourlyForecasts,
                ),
            now = request.now,
            startupToken = null,
            stateManagerNullable = stateManager,
            repository = request.repository,
            partialPush = request.partialPush,
            origin = request.origin,
        )
        InteractionTimingLogger.log(
            database,
            request.appWidgetId,
            request.actionTag,
            request.startTimeMs,
            request.extraMetadata,
        )
    }

    private suspend fun loadData(
        context: Context,
        database: com.weatherwidget.data.local.WeatherDatabase,
        lat: Double,
        lon: Double,
        today: LocalDate,
        range: DailyRange,
    ): DailyRenderData {
        val key = WidgetInteractionCache.Key.of(lat, lon, today.toEpochDay())
        val loaded =
            WidgetInteractionCache.getOrLoad(key) {
                val weatherListRaw =
                    database.forecastDao().getForecastsInRange(
                        range.historyStart,
                        range.forecastEnd,
                        lat,
                        lon,
                    )
                val dailyActuals =
                    DailyActualsLoader.load(
                        database,
                        lat,
                        lon,
                        WidgetStateManager(context).getPersonalStationWeight(),
                    )
                WidgetInteractionCache.Data(weatherListRaw, dailyActuals)
            }
        return DailyRenderData(loaded.weatherListRaw, loaded.dailyActuals)
    }

    private data class DailyRange(
        val historyStart: Long,
        val forecastEnd: Long,
        val pastSnapshotEnd: Long,
        val recentSnapshotStart: Long,
    )

    private fun rangeFor(today: LocalDate) =
        DailyRange(
            historyStart =
                today.minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() *
                    WeatherTimeUtils.MILLIS_PER_DAY,
            forecastEnd =
                today.plusDays(DAILY_FORECAST_DAYS).toEpochDay() *
                    WeatherTimeUtils.MILLIS_PER_DAY,
            pastSnapshotEnd =
                today.minusDays(2).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY,
            recentSnapshotStart =
                today.minusDays(1).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY,
        )
}

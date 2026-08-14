package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Owns widget painting from already-resolved data: the per-widget render throttle, the shared
 * `updateAllWidgets`/`renderNoLocationAndFinish`/`refreshWidgetsFromCache` paths, and the
 * source-race handling around actuals. Shared by the worker's lightweight modes and the full-sync
 * pipeline so neither reimplements painting.
 */
internal class WidgetPaintCoordinator(
    private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    private val hourlyForecastLoader: HourlyForecastLoader,
    private val dataBundleLoader: WidgetDataBundleLoader,
) {
    private val lastRenderMs = mutableMapOf<Int, Long>()

    /**
     * Paints the no-location state on every placed widget and logs it. Returns [androidx.work.ListenableWorker.Result.success]
     * so the caller can `return renderNoLocationAndFinish(...)` — this is a settled state, not a
     * transient failure, so retrying would only burn wakeups until the user acts or device
     * following lands a fix.
     *
     * Deliberately does *not* honour the screen-off paint skip that [updateAllWidgets] applies.
     * That skip is a battery optimisation for repeated data repaints; here it would strand a
     * first-ever run behind the "Loading..." placeholder indefinitely.
     */
    suspend fun renderNoLocationAndFinish(reason: String): androidx.work.ListenableWorker.Result {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        appLogDao.log(
            "NO_LOCATION",
            "reason=$reason widgets=${appWidgetIds.size} action=render_error_skip_fetch",
            "INFO",
        )
        appWidgetIds.forEach { appWidgetId ->
            WidgetRenderer.updateWidgetNoLocation(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
            )
        }
        return androidx.work.ListenableWorker.Result.success()
    }

    suspend fun updateAllWidgets(
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        dailyActuals: DailyActualsBySource = emptyMap(),
        jobType: WidgetUpdateTracker.JobType = WidgetUpdateTracker.JobType.BACKGROUND_SYNC,
        uiOnly: Boolean = false,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
        loadedActualsSourceIds: Collection<String> = emptyList(),
        reloadActuals: (suspend (List<String>) -> DailyActualsBySource)? = null,
        loadedHourlySourceIds: Collection<String> = emptyList(),
        hourlyLat: Double? = null,
        hourlyLon: Double? = null,
    ) = coroutineScope {
        if (!isScreenInteractive()) {
            appLogDao.log("WIDGET_PAINT_SKIP", "reason=screen_off", "INFO")
            return@coroutineScope
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val effectiveActuals = resolveEffectiveActuals(
            appWidgetIds = appWidgetIds,
            loadedActualsSourceIds = loadedActualsSourceIds,
            reloadActuals = reloadActuals,
            dailyActuals = dailyActuals,
        )

        val effectiveHourlyForecasts = resolveEffectiveHourly(
            appWidgetIds = appWidgetIds,
            loadedHourlySourceIds = loadedHourlySourceIds,
            lat = hourlyLat,
            lon = hourlyLon,
            hourlyForecasts = hourlyForecasts,
        )

        val effectiveOrigin = if (uiOnly) WidgetPushDispatcher.Origin.UI_ONLY else origin
        for (appWidgetId in appWidgetIds) {
            if (shouldSkipWidgetRender(appWidgetId)) {
                Log.v(TAG, "Skipping render for widget $appWidgetId (throttled)")
                continue
            }
            val job = launch {
                WidgetRenderer.updateWidgetWithData(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    weatherList = weatherList,
                    forecastSnapshots = forecastSnapshots,
                    hourlyForecasts = effectiveHourlyForecasts,
                    currentTemps = currentTemps,
                    dailyActualsBySource = effectiveActuals,
                    repository = weatherRepository,
                    uiOnly = uiOnly,
                    partialPush = true,
                    origin = effectiveOrigin,
                )
            }
            WidgetUpdateTracker.trackJob(appWidgetId, job, jobType)
            lastRenderMs[appWidgetId] = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun resolveEffectiveActuals(
        appWidgetIds: IntArray,
        loadedActualsSourceIds: Collection<String>,
        reloadActuals: (suspend (List<String>) -> DailyActualsBySource)?,
        dailyActuals: DailyActualsBySource,
    ): DailyActualsBySource {
        val paintSourceIds = appWidgetIds.map { widgetStateManager.getCurrentDisplaySource(it).id }.distinct()
        val uncoveredSources = DailyActualsCoverage.uncoveredSources(paintSourceIds, loadedActualsSourceIds)
        if (uncoveredSources.isEmpty() || reloadActuals == null) return dailyActuals

        appLogDao.log(
            "ACTUALS_SOURCE_RACE",
            "uncovered=${uncoveredSources.joinToString(",")} " +
                "loaded=${loadedActualsSourceIds.joinToString(",")} " +
                "paint=${paintSourceIds.joinToString(",")} reloading",
            "INFO",
        )
        return reloadActuals(
            DailyActualsCoverage.unionSourceIds(paintSourceIds, loadedActualsSourceIds),
        ).takeIf { it.isNotEmpty() } ?: dailyActuals
    }

    /**
     * Hourly counterpart of [resolveEffectiveActuals]. [HourlyForecastLoader.load] scopes its SQL to
     * the sources displayed when the caller asked, and a source toggle landing after that snapshot —
     * but before this paint — leaves the passed list with zero rows for the newly selected source. The
     * hourly views then paint "Cloud data unavailable" (or a blank curve) and the gap detector would
     * burn a redundant forced sync on data the API already delivered. Reload once, here, against the
     * sources actually on screen; the common path (no toggle in flight) does no extra query.
     */
    private suspend fun resolveEffectiveHourly(
        appWidgetIds: IntArray,
        loadedHourlySourceIds: Collection<String>,
        lat: Double?,
        lon: Double?,
        hourlyForecasts: List<HourlyForecastEntity>,
    ): List<HourlyForecastEntity> {
        val paintSourceIds = appWidgetIds.map { widgetStateManager.getCurrentDisplaySource(it).id }.distinct()
        val missing = HourlyForecastLoader.sourcesMissingFromLoad(
            loadedSourceIds = loadedHourlySourceIds.toList(),
            displaySourceIdsAtPaint = paintSourceIds,
        )
        if (missing.isEmpty() || lat == null || lon == null) return hourlyForecasts

        val reloaded = hourlyForecastLoader.load(lat, lon, hourlyForecastLoader.hourlySourceIds())
        appLogDao.log(
            "HOURLY_SOURCE_RACE",
            "loaded=${loadedHourlySourceIds.joinToString("|")} " +
                "atPaint=${paintSourceIds.joinToString("|")} " +
                "missing=${missing.joinToString("|")} " +
                "staleRows=${hourlyForecasts.size} reloadedRows=${reloaded.size}",
            "WARN",
        )
        // Keep the original when the repair reload comes back empty — a transient DB miss must not
        // blank every widget's hourly graph.
        return reloaded.takeIf { it.isNotEmpty() } ?: hourlyForecasts
    }

    private fun shouldSkipWidgetRender(appWidgetId: Int): Boolean {
        val last = lastRenderMs[appWidgetId] ?: return false
        return SystemClock.elapsedRealtime() - last < MIN_RENDER_INTERVAL_MS
    }

    suspend fun refreshWidgetsFromCache() {
        val location = ActiveLocationResolver.resolve(
            context, widgetStateManager, WeatherDatabase.getDatabase(context).forecastDao(),
        ) ?: run {
            renderNoLocationAndFinish("refresh_from_cache")
            return
        }
        val bundle = dataBundleLoader.load(
            latitude = location.first,
            longitude = location.second,
            networkAllowed = false,
            recomputeActuals = false,
            forceRefresh = false,
            targetSourceId = null,
            fetchContext = null,
        )
        updateAllWidgets(
            weatherList = bundle.weatherList,
            forecastSnapshots = bundle.forecastSnapshots,
            hourlyForecasts = bundle.hourlyForecasts,
            currentTemps = bundle.currentTemps,
            dailyActuals = bundle.dailyActuals,
            jobType = WidgetUpdateTracker.JobType.BACKGROUND_SYNC,
            origin = WidgetPushDispatcher.Origin.WORKER_CACHE,
            loadedActualsSourceIds = bundle.activeSourceIds,
            loadedHourlySourceIds = bundle.activeSourceIds,
            hourlyLat = location.first,
            hourlyLon = location.second,
            reloadActuals = { sourceIds ->
                dataBundleLoader.reloadDailyActuals(
                    lat = location.first,
                    lon = location.second,
                    hourlyForecasts = bundle.hourlyForecasts,
                    sourceIds = sourceIds,
                )
            },
        )
    }

    private fun isScreenInteractive(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    companion object {
        private const val TAG = "WidgetPaintCoordinator"
        private const val MIN_RENDER_INTERVAL_MS = 30_000L
    }
}

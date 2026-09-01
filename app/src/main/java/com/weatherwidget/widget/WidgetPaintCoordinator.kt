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
    /** Paint-time location resampling; see the call in [updateAllWidgets]. */
    private val gpsResampler: GpsResampler,
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
        reloadActuals: (suspend (List<String>, List<HourlyForecastEntity>) -> DailyActualsBySource)? = null,
        loadedHourlySourceIds: Collection<String> = emptyList(),
        hourlyLat: Double? = null,
        hourlyLon: Double? = null,
    ) = coroutineScope {
        if (!isScreenInteractive()) {
            // Record the debt. Nothing repaints on unlock — ACTION_USER_PRESENT is manifest-declared
            // and undeliverable at targetSdk 26+ (see ScreenOnReceiver) — so without this the next
            // paint to run would consult GraphRepaintGate's temp-string signal against a render that
            // predates this fetch and could legitimately decide nothing changed, stranding a stale
            // station label on screen. See plans/260818-widget-repaint-gate-data-watermark.md.
            widgetStateManager.setPaintOwed(true)
            appLogDao.log("WIDGET_PAINT_SKIP", "reason=screen_off", "INFO")
            return@coroutineScope
        }

        // A paint for an interactive screen is the app's only reliable evidence that the user is
        // looking. Unlock is undeliverable (see ScreenOnReceiver) and screen-on only reaches a live
        // process, so this is the signal that survives when those do not — and it is exactly the
        // moment stale-by-location data would be seen. Rate-limited in maybeResample: paints are far
        // more frequent than syncs (every tap, zoom and day-click).
        //
        // Fire-and-forget on purpose. A move applies and enqueues its own refresh, which repaints;
        // blocking this paint on a Play services read would trade a visible delay for nothing.
        launch {
            runCatching { gpsResampler.maybeResample(context, trigger = "paint") }
                .onFailure { Log.w(TAG, "Paint-time resample failed", it) }
        }

        val paintOwed = widgetStateManager.isPaintOwed()

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        // Resolve hourly first so the actuals repair below can consume the reloaded rows instead of
        // the stale list the caller loaded (both races fire together when a source toggles late).
        val effectiveHourlyForecasts = resolveEffectiveHourly(
            appWidgetIds = appWidgetIds,
            loadedHourlySourceIds = loadedHourlySourceIds,
            lat = hourlyLat,
            lon = hourlyLon,
            hourlyForecasts = hourlyForecasts,
        )

        val effectiveActuals = resolveEffectiveActuals(
            appWidgetIds = appWidgetIds,
            loadedActualsSourceIds = loadedActualsSourceIds,
            reloadActuals = reloadActuals,
            dailyActuals = dailyActuals,
            hourlyForecasts = effectiveHourlyForecasts,
        )

        val effectiveOrigin = if (uiOnly) WidgetPushDispatcher.Origin.UI_ONLY else origin
        var rendered = false
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
                    paintOwed = paintOwed,
                )
            }
            WidgetUpdateTracker.trackJob(appWidgetId, job, jobType)
            lastRenderMs[appWidgetId] = SystemClock.elapsedRealtime()
            rendered = true
        }

        // Cleared only once a render actually launched. Clearing it up front would let the 30s
        // per-widget throttle swallow the debt outright: every widget skipped, flag gone, and the
        // stale bitmap left standing until some later signal happens to fire.
        if (paintOwed && rendered) {
            widgetStateManager.setPaintOwed(false)
            appLogDao.log("WIDGET_PAINT_OWED", "action=force_rebuild widgets=${appWidgetIds.size}", "INFO")
        }
    }

    private suspend fun resolveEffectiveActuals(
        appWidgetIds: IntArray,
        loadedActualsSourceIds: Collection<String>,
        reloadActuals: (suspend (List<String>, List<HourlyForecastEntity>) -> DailyActualsBySource)?,
        dailyActuals: DailyActualsBySource,
        hourlyForecasts: List<HourlyForecastEntity>,
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
            hourlyForecasts,
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
    @androidx.annotation.VisibleForTesting
    internal suspend fun resolveEffectiveHourly(
        appWidgetIds: IntArray,
        loadedHourlySourceIds: Collection<String>,
        lat: Double?,
        lon: Double?,
        hourlyForecasts: List<HourlyForecastEntity>,
    ): List<HourlyForecastEntity> {
        var effective = hourlyForecasts
        var loadedIds = loadedHourlySourceIds.toList()

        // Re-check AFTER each reload, not just once. The reload is itself a query taking ~1s on the
        // Fold, and a toggle landing inside that window walks straight back into the stale state the
        // reload just repaired. Observed 2026-09-01 (widget 345): the repair caught up to SILURIAN at
        // 06:49:18.76, the user selected TOMORROW_IO at 06:49:19.57, and the paint 0.24s later drew
        // "Cloud data unavailable" over a correct frame. See
        // plans/260901-stale-source-paint-clobbers-hourly-graph.md.
        repeat(MAX_HOURLY_SOURCE_RACE_RELOADS) { attempt ->
            val paintSourceIds = appWidgetIds.map { widgetStateManager.getCurrentDisplaySource(it).id }.distinct()
            val missing = HourlyForecastLoader.sourcesMissingFromLoad(
                loadedSourceIds = loadedIds,
                displaySourceIdsAtPaint = paintSourceIds,
            )
            if (missing.isEmpty() || lat == null || lon == null) return effective

            // Scope the reload to the very source list the check above ran on, so an iteration
            // cannot request something other than what it just found missing.
            val requestedIds = HourlyForecastLoader.scopeForDisplaySources(paintSourceIds)
            val reloaded = hourlyForecastLoader.load(lat, lon, requestedIds, caller = "source_race_reload")
            appLogDao.log(
                "HOURLY_SOURCE_RACE",
                "attempt=${attempt + 1}/$MAX_HOURLY_SOURCE_RACE_RELOADS " +
                    "loaded=${loadedIds.joinToString("|")} " +
                    "atPaint=${paintSourceIds.joinToString("|")} " +
                    "missing=${missing.joinToString("|")} " +
                    "staleRows=${effective.size} reloadedRows=${reloaded.size}",
                "WARN",
            )
            // Keep what we have when the repair reload comes back empty — a transient DB miss must
            // not blank every widget's hourly graph. Stop retrying too: a second identical query
            // would only spend the same second to learn the same thing.
            if (reloaded.isEmpty()) return effective

            effective = reloaded
            // Track what was REQUESTED, not what came back: a source can legitimately hold zero rows
            // at this site, and reading coverage off the returned rows would loop until the bound.
            loadedIds = requestedIds
        }
        // Still uncovered after the bound — a source toggling faster than the query can follow.
        // WidgetRenderer.shouldSkipStaleSourcePaint keeps that harmless: the background repaint is
        // dropped rather than painted empty over whatever is correctly on screen.
        return effective
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
            reloadActuals = { sourceIds, hourly ->
                dataBundleLoader.reloadDailyActuals(
                    lat = location.first,
                    lon = location.second,
                    hourlyForecasts = hourly,
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

        /**
         * How many times [resolveEffectiveHourly] may reload chasing a source that keeps moving.
         *
         * Two, because each reload is a real query (~1s on the Fold: 467 rows stitched out of
         * 3636 current + 21474 history) and the paint is waiting on it. One reload covers the
         * ordinary case — a single toggle during the fetch. The second covers a toggle landing
         * inside that reload, which is the 2026-09-01 failure. A third toggle inside the second
         * reload means the user is cycling the indicator faster than the DB can answer; chasing
         * that is not worth another second of paint latency, and the paint-skip in
         * [WidgetRenderer.shouldSkipStaleSourcePaint] already makes it invisible.
         */
        @androidx.annotation.VisibleForTesting
        internal const val MAX_HOURLY_SOURCE_RACE_RELOADS = 2
    }
}

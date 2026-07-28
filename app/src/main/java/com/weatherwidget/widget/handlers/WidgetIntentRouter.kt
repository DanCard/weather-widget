package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.local.getLatestForecastsInRange
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.DailyActualsLoader
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetPushDispatcher
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Router for handling widget intent actions.
 * Delegates to appropriate handlers based on the action type.
 */
object WidgetIntentRouter {
    private const val TAG = "WidgetIntentRouter"
    private const val DAILY_LOOKBACK_DAYS = 30L
    private const val DAILY_FORECAST_DAYS = 30L
    private const val SLOW_THRESHOLD_MS = 200L
    private const val RESIZE_DEBOUNCE_MS = 250L

    // Staleness policy (source-state probe + refresh decision + `TOGGLE_REFRESH_STALE_MS`) lives in
    // [SourceStalenessProbe] now. See that object for the constants and the policy rationale.

    /**
     * Widget PendingIntents are dispatched into independent IO coroutines by WeatherWidgetProvider.
     * Serialize state mutation + rendering for one widget so an older, slower interaction cannot
     * overwrite a newer tap with a stale source/mode/offset snapshot. Different widgets remain
     * independent.
     */
    private val interactionMutexes = ConcurrentHashMap<Int, Mutex>()

    @VisibleForTesting
    internal suspend fun <T> withWidgetInteractionLock(
        appWidgetId: Int,
        block: suspend () -> T,
    ): T = interactionMutexes.computeIfAbsent(appWidgetId) { Mutex() }.withLock { block() }

    /**
     * Newest resize request token per widget, used by [handleResize] for trailing-edge debouncing.
     * Separate from [interactionMutexes] because the debounce deliberately runs OUTSIDE the lock.
     *
     * A monotonic counter rather than a timestamp: two resize broadcasts can land inside the same
     * millisecond, and equal timestamps would let both survive the debounce and render.
     */
    private val resizeRequestSequence = AtomicLong(0L)
    private val latestResizeRequest = ConcurrentHashMap<Int, Long>()

    fun forgetWidget(appWidgetId: Int) {
        interactionMutexes.remove(appWidgetId)
        latestResizeRequest.remove(appWidgetId)
    }

    @VisibleForTesting
    internal fun clearInteractionMutexesForTesting() {
        interactionMutexes.clear()
        latestResizeRequest.clear()
    }

    /**
     * Runs one user interaction under this widget's lock and leaves a persistent breadcrumb either
     * way: `<tag>_RENDER_OK` on success, `<tag>_FAIL` on a caught exception.
     *
     * Every caller mutates persisted widget state (source, view mode, zoom, offset) BEFORE
     * rendering, so a swallowed render failure leaves the pref flipped while the surface still shows
     * the old content — the user's next tap flips it back and the button reads as dead. Logging only
     * to logcat is what let the 2026-07-08 source-gap NPE hide from app_logs sweeps entirely; this
     * generalizes the fix handleSetView already carried to the rest of the tap handlers.
     *
     * Cancellation stays terminal — it means the scope went away, not that the interaction failed.
     *
     * The OK breadcrumb is emitted OUTSIDE the try (and outside the lock): if the render succeeded
     * then the work is done regardless of whether its breadcrumb write succeeds, so a SQLite I/O
     * hiccup on the OK insert cannot flip the outcome to `_FAIL` and mislead a later diagnostic
     * sweep. The failure-side breadcrumb path is also best-effort and surfaces its own write failure
     * to logcat rather than swallowing it silently.
     */
    private suspend fun runInteraction(
        context: Context,
        appWidgetId: Int,
        tag: String,
        metadata: String = "",
        block: suspend () -> Unit,
    ) {
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        runInteractionWithDao(appLogDao, appWidgetId, tag, metadata, block)
    }

    @VisibleForTesting
    internal suspend fun runInteractionWithDao(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        tag: String,
        metadata: String = "",
        block: suspend () -> Unit,
    ) {
        val suffix = if (metadata.isNotEmpty()) " $metadata" else ""
        try {
            withWidgetInteractionLock(appWidgetId) {
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$tag failed for widget $appWidgetId", e)
            runCatching {
                appLogDao.log(
                    "${tag}_FAIL",
                    "widget=$appWidgetId$suffix ${e.javaClass.simpleName}: ${e.message}",
                    "ERROR",
                )
            }.onFailure { logWriteError ->
                Log.w(TAG, "$tag failed for widget $appWidgetId and FAIL breadcrumb write also failed", logWriteError)
            }
            return
        }
        runCatching {
            appLogDao.log("${tag}_RENDER_OK", "widget=$appWidgetId$suffix")
        }.onFailure { logWriteError ->
            Log.w(TAG, "$tag rendered for widget $appWidgetId but OK breadcrumb write failed", logWriteError)
        }
    }

    /**
     * Runs [render] for each widget ID in isolation: a throw from one widget cannot abort the loop,
     * and a throw from [onFailure] while reporting another widget's failure cannot either (it is
     * logged and swallowed). [CancellationException] is terminal in both cases — it means the
     * caller's scope went away, so we stop the loop and propagate.
     *
     * Used by [renderAllWidgetsFromCache] so one widget's broken repaint does not suppress the
     * blank-widget self-heal of its siblings.
     */
    @VisibleForTesting
    internal suspend fun forEachWidgetIsolated(
        appWidgetIds: IntArray,
        onFailure: suspend (Int, Exception) -> Unit = { _, _ -> },
        render: suspend (Int) -> Unit,
    ) {
        for (appWidgetId in appWidgetIds) {
            try {
                render(appWidgetId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    onFailure(appWidgetId, e)
                } catch (reportingCancellation: CancellationException) {
                    throw reportingCancellation
                } catch (reportingError: Exception) {
                    Log.e(TAG, "Failed to report render failure for widget $appWidgetId", reportingError)
                }
            }
        }
    }

    // Intent actions — defined in WidgetActions

    @VisibleForTesting
    internal data class LocationResult(
        val lat: Double,
        val lon: Double,
        val fetchedAt: Long?,
    )

    internal data class RefreshContext(
        val database: WeatherDatabase,
        val forecastDao: ForecastDao,
        val location: LocationResult,
    )

    private suspend fun resolveLocation(context: Context, forecastDao: ForecastDao, latestWeather: ForecastEntity?): LocationResult {
        val latLon = ActiveLocationResolver.resolve(context, WidgetStateManager(context), forecastDao)
        return LocationResult(
            lat = latLon.first,
            lon = latLon.second,
            fetchedAt = latestWeather?.fetchedAt,
        )
    }

    private suspend fun resolveRefreshContext(
        context: Context,
        staleReason: String,
    ): RefreshContext {
        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val latestWeather = forecastDao.getLatestWeather()
        val loc = resolveLocation(context, forecastDao, latestWeather)
        // Always pass the DAO: refreshIfStale only emits its STALE_REFRESH_SKIP breadcrumb when it is
        // non-null, and every interaction callsite used to omit it — so the "why didn't this refresh?"
        // row never once fired from a tap.
        RefreshScheduler.refreshIfStale(context, loc.fetchedAt, staleReason, database.appLogDao())
        return RefreshContext(database, forecastDao, loc)
    }

    @VisibleForTesting
    fun setIsRefreshDisabledForTesting(disableRefreshFlag: Boolean) {
        RefreshScheduler.setIsRefreshDisabledForTesting(disableRefreshFlag)
    }

    /**
     * Repaints every widget from cached DB data immediately (no network), reusing the same per-widget
     * render path as the tap handlers. Called on a manual refresh so the user sees current cached
     * content in ~0.5s instead of waiting on the forced background fetch (~7s). Reading here — before
     * the fetch's DB writes — also keeps this read from being blocked behind those writes (the reason
     * routing the cache paint through a second WorkManager job was still slow).
     */
    suspend fun renderAllWidgetsFromCache(context: Context, repository: WeatherRepository? = null) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            .toIntArray()
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        forEachWidgetIsolated(
            appWidgetIds = ids,
            onFailure = { id, error ->
                Log.e(TAG, "renderAllWidgetsFromCache failed for widget $id", error)
                appLogDao.log(
                    "WIDGET_RENDER_FAIL",
                    "widget=$id path=refresh_action_cache_first ${error.javaClass.simpleName}: ${error.message}",
                    "ERROR",
                )
            },
        ) { id ->
            withWidgetInteractionLock(id) {
                refreshWidget(
                    context = context,
                    appWidgetId = id,
                    reason = "refresh_action_cache_first",
                    repository = repository,
                    actionTag = "REFRESH",
                    partialPush = true,
                    origin = WidgetPushDispatcher.Origin.ACTION_REFRESH,
                )
            }
        }
    }

    /**
     * Handle navigation (left/right) action.
     */
    suspend fun handleNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        runInteraction(context, appWidgetId, "NAV", "dir=${if (isLeft) "LEFT" else "RIGHT"}") {
            handleNavigationInternal(context, appWidgetId, isLeft, repository)
        }
    }

    private suspend fun handleNavigationInternal(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        val direction = if (isLeft) "LEFT" else "RIGHT"
        Log.d(TAG, "handleNavigation: widget=$appWidgetId, direction=$direction, viewMode=$viewMode")

        if (viewMode.isGraphMode) {
            handleGraphNavigation(context, appWidgetId, isLeft, repository)
        } else {
            handleDailyNavigation(context, appWidgetId, isLeft, repository)
        }
    }

    /**
     * Handle daily view navigation.
     */
    private suspend fun handleDailyNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        val ctx = resolveRefreshContext(context, "daily_nav")
        val appLogDao = ctx.database.appLogDao()

        val today = LocalDate.now()
        val historyStart = today.minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val forecastEnd = today.plusDays(DAILY_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY

        // Cache-backed: a nav burst (or nav interleaved with a view toggle) reuses one forecast +
        // actuals load instead of re-querying ~1.6k observations per tap. The raw list is gap-filled
        // here exactly as before.
        val cachedData = loadCachedDailyData(
            context, ctx.database, ctx.location.lat, ctx.location.lon, today, historyStart, forecastEnd,
        )
        val weatherList = ClimateGapFiller(ctx.database.climateNormalDao()).appendGaps(
            cachedData.weatherListRaw,
            ctx.location.lat,
            ctx.location.lon,
            today,
            horizonDays = DAILY_FORECAST_DAYS,
        )

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val skipYesterday = NavigationUtils.shouldSkipYesterday(numColumns = numColumns)

        val availableForecastDates = weatherList.map { LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY) }.toSet()

        val dailyActuals = cachedData.dailyActuals
        val availableObsDates = dailyActuals.values.flatMap { it.keys }.toSet()

        val availableDates = (availableForecastDates + availableObsDates)
            .distinct()
            .sorted()

        val minDate = availableDates.firstOrNull()
        val maxDate = availableDates.lastOrNull()

        val canNavigate: Boolean
        val navDebug: String
        if (isLeft) {
            val (newLeftmost, _) =
                NavigationUtils.getVisibleDateRange(
                    today = today,
                    dateOffset = currentOffset - 1,
                    numColumns = numColumns,
                    skipYesterday = skipYesterday,
                )
            canNavigate = minDate != null && minDate.isBefore(newLeftmost.plusDays(1))
            navDebug = "LEFT: newLeftmost=$newLeftmost, minDate=$minDate"
        } else {
            val (_, newRightmost) =
                NavigationUtils.getVisibleDateRange(
                    today = today,
                    dateOffset = currentOffset + 1,
                    numColumns = numColumns,
                    skipYesterday = skipYesterday,
                )
            canNavigate = maxDate != null && maxDate.isAfter(newRightmost.minusDays(1))
            navDebug = "RIGHT: newRightmost=$newRightmost, maxDate=$maxDate"
        }

        appLogDao.log(
            "DAILY_NAV_ATTEMPT",
            "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset cols=$numColumns rows=${dimensions.rows} skipYesterday=$skipYesterday source=${displaySource.id} minDate=$minDate maxDate=$maxDate $navDebug canNavigate=$canNavigate"
        )

        if (!canNavigate) {
            appLogDao.log(
                "DAILY_NAV_BLOCKED",
                "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset cols=$numColumns skipYesterday=$skipYesterday source=${displaySource.id} minDate=$minDate maxDate=$maxDate"
            )
            return
        }

        val newOffset =
            if (isLeft) {
                stateManager.navigateLeft(appWidgetId)
            } else {
                stateManager.navigateRight(appWidgetId)
            }
        appLogDao.log(
            "DAILY_NAV_APPLY",
            "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset->$newOffset source=${displaySource.id}"
        )

        refreshDailyView(
            context = context,
            appWidgetId = appWidgetId,
            database = ctx.database,
            lat = ctx.location.lat,
            lon = ctx.location.lon,
            repository = repository,
            weatherList = weatherList,
            dailyActuals = dailyActuals,
            startTimeMs = startMs,
            actionTag = "DAILY_NAV",
            extraMetadata = "dir=${if (isLeft) "LEFT" else "RIGHT"}",
        )
    }

    /**
     * Handle hourly/precipitation view navigation.
     */
    private suspend fun handleGraphNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)

        val newOffset =
            if (isLeft) {
                stateManager.navigateHourlyLeft(appWidgetId)
            } else {
                stateManager.navigateHourlyRight(appWidgetId)
            }
        Log.d(TAG, "handleGraphNavigation: Navigated to offset $newOffset for widget $appWidgetId")

        val ctx = resolveRefreshContext(context, "graph_nav")

        refreshGraphView(
            context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
            startTimeMs = startMs, actionTag = "GRAPH_NAV", extraMetadata = "dir=${if (isLeft) "LEFT" else "RIGHT"}"
        )
    }

    /**
     * Handle zoom level cycle action.
     */
    suspend fun handleCycleZoom(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int? = null,
        repository: WeatherRepository? = null,
    ) {
        // from=<currentZoom> is a pre-lock peek; same-widget interactions are serialized by the
        // per-widget mutex, so the value captured here is still accurate when the lock is acquired.
        // tapOffset is included when present so a tap-zone-initiated cycle is greppable in the
        // _RENDER_OK / _FAIL rows alongside the existing CYCLE_ZOOM_TIMING zoom= breadcrumb.
        val fromZoom = WidgetStateManager(context).getZoomLevel(appWidgetId)
        val offsetMeta = if (zoomCenterOffset != null) " tapOffset=$zoomCenterOffset" else ""
        runInteraction(context, appWidgetId, "CYCLE_ZOOM", "from=${fromZoom.name}$offsetMeta") {
            handleCycleZoomInternal(context, appWidgetId, zoomCenterOffset, repository)
        }
    }

    private suspend fun handleCycleZoomInternal(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int? = null,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleCycleZoom: viewMode=$viewMode widget=$appWidgetId")
        if (viewMode == ViewMode.DAILY) {
            Log.w(TAG, "handleCycleZoom: ignoring — widget $appWidgetId is in DAILY mode (stale PendingIntent)")
            return
        }
        val oldZoom = stateManager.getZoomLevel(appWidgetId)
        val newZoom = stateManager.cycleZoomLevel(appWidgetId)

        // When a center offset is provided by a tap zone, re-center the view
        if (zoomCenterOffset != null) {
            // The zoomCenterOffset is the pre-calculated absolute offset of the tapped zone.
            stateManager.setHourlyOffset(appWidgetId, zoomCenterOffset)
            Log.d(TAG, "handleCycleZoom: Re-centered to absolute offset $zoomCenterOffset for widget $appWidgetId")
        }
        Log.d(TAG, "handleCycleZoom: $oldZoom -> $newZoom, zoomCenterOffset=$zoomCenterOffset widget=$appWidgetId")

        val ctx = resolveRefreshContext(context, "cycle_zoom")

        refreshGraphView(
            context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
            startTimeMs = startMs, actionTag = "CYCLE_ZOOM", extraMetadata = "zoom=${newZoom.name}"
        )
    }

    /**
     * Handle API source toggle action.
     */
    suspend fun handleToggleApi(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        // from=<currentSource> disambiguates "NWS → OPEN_METEO failed" from the reverse in a
        // TOGGLE_API_FAIL sweep; the resulting target source appears in TOGGLE_API_TIMING.
        val fromSource = WidgetStateManager(context).getCurrentDisplaySource(appWidgetId)
        runInteraction(context, appWidgetId, "TOGGLE_API", "from=${fromSource.id}") {
            handleToggleApiInternal(context, appWidgetId, repository)
        }
    }

    private suspend fun handleToggleApiInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleToggleApi: Toggled to $newSource for widget $appWidgetId, viewMode=$viewMode")

        val ctx = resolveRefreshContext(context, "toggle_api")
        val hourlyDao = ctx.database.hourlyForecastDao()

        val now = LocalDateTime.now()
        val currentGraphZoom =
            if (viewMode.isGraphMode) {
                stateManager.getZoomLevel(appWidgetId)
            } else {
                null
            }
        // Must use the SAME resolver refreshGraphView renders with: on an anchored past/future window
        // resolveHourlyCenterTime returns the pinned anchor, while now.plusHours(offset) drifts away
        // from it by however long ago it was pinned. Probing the drifted window gave sourceNeedsRefresh
        // a hasHourly for a window nobody is looking at — spurious fetches, or a needed fetch skipped
        // while the pinned view stayed empty.
        val currentGraphCenterTime =
            currentGraphZoom?.let { stateManager.resolveHourlyCenterTime(appWidgetId, now, it) }
        val selectedSourceState =
            SourceStalenessProbe.sourceWindowState(
                forecastDao = ctx.forecastDao,
                hourlyDao = hourlyDao,
                hourlyHistoryDao = ctx.database.hourlyForecastHistoryDao(),
                lat = ctx.location.lat,
                lon = ctx.location.lon,
                source = newSource,
                centerTime = currentGraphCenterTime,
                zoom = currentGraphZoom,
                now = now,
                lastSuccessfulFetchAtMs = FetchMetadata.getLastForecastSourceSuccessTime(
                    context = context,
                    sourceId = newSource.id,
                    latitude = ctx.location.lat,
                    longitude = ctx.location.lon,
                ).takeIf { it > 0L },
            )

        // Non-primary sources are throttled, so switching to one often lands on data that is present
        // but hours old. Refresh when it is missing OR stale — targeted at just this source, so one
        // tap does not force-fetch every enabled provider.
        //
        // Enqueued BEFORE the repaint on purpose: this is a non-blocking WorkManager hand-off, and
        // if it sat after the repaint then any render failure (widgetInfo null, RemoteViews throw —
        // see [[samsung_widget_dead_native_sigsegv]], [[widget_blank_selfheal_render_ok]]) would
        // also silently cancel the fetch, i.e. a broken widget would suppress the data refresh most
        // likely to fix it.
        if (SourceStalenessProbe.sourceNeedsRefresh(selectedSourceState, System.currentTimeMillis())) {
            Log.d(TAG, "handleToggleApi: $newSource needs refresh ($selectedSourceState), enqueueing forced refresh")
            RefreshScheduler.enqueueForcedRefresh(
                context,
                reason = "toggle_api_stale",
                targetSourceId = newSource.id,
            )
        }

        if (viewMode.isGraphMode) {
            refreshGraphView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_API", extraMetadata = "source=${newSource.id}"
            )
        } else {
            refreshDailyView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_API", extraMetadata = "source=${newSource.id}"
            )
        }
    }

    /**
     * Handle view mode toggle action.
     */
    suspend fun handleToggleView(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        // from=<currentMode> identifies the half of the toggle that failed in a TOGGLE_VIEW_FAIL
        // sweep; the resulting mode appears in TOGGLE_VIEW_TIMING.
        val fromMode = WidgetStateManager(context).getViewMode(appWidgetId)
        runInteraction(context, appWidgetId, "TOGGLE_VIEW", "from=${fromMode.name}") {
            handleToggleViewInternal(context, appWidgetId, repository)
        }
    }

    private suspend fun handleToggleViewInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.toggleViewMode(appWidgetId)
        Log.d(TAG, "handleToggleView: Toggled to $newMode for widget $appWidgetId")

        val ctx = resolveRefreshContext(context, "toggle_view")

        if (newMode.isGraphMode) {
            refreshGraphView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_VIEW", extraMetadata = "mode=${newMode.name}"
            )
        } else {
            refreshDailyView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_VIEW", extraMetadata = "mode=${newMode.name}"
            )
        }
    }

    /**
     * Loads the two heaviest interaction-path values — the raw daily forecast list and
     * [DailyActualsBySource] — through [WidgetInteractionCache] so a burst of taps (across widgets or
     * repeated on one) shares a single load instead of re-querying per tap. See the cache's docs for
     * why staleness is bounded by TTL alone. [historyStart]/[forecastEnd] MUST match the daily range
     * both callers already use, so the cached raw list is interchangeable between them.
     */
    private suspend fun loadCachedDailyData(
        context: Context,
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
        today: LocalDate,
        historyStart: Long,
        forecastEnd: Long,
    ): WidgetInteractionCache.Data {
        val key = WidgetInteractionCache.Key.of(lat, lon, today.toEpochDay())
        val nowMs = WidgetInteractionCache.nowMs()
        WidgetInteractionCache.get(key, nowMs)?.let { return it }
        val weatherListRaw = database.forecastDao().getForecastsInRange(historyStart, forecastEnd, lat, lon)
        val dailyActuals =
            DailyActualsLoader.load(
                database,
                lat,
                lon,
                WidgetStateManager(context).getPersonalStationWeight(),
            )
        val data = WidgetInteractionCache.Data(weatherListRaw, dailyActuals)
        WidgetInteractionCache.put(key, data, nowMs)
        return data
    }

    /**
     * Handle precipitation mode toggle action.
     */
    suspend fun handleTogglePrecip(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        // from=<currentMode> identifies the half of the toggle that failed in a TOGGLE_PRECIP_FAIL
        // sweep; the resulting mode appears in TOGGLE_PRECIP_TIMING.
        val fromMode = WidgetStateManager(context).getViewMode(appWidgetId)
        runInteraction(context, appWidgetId, "TOGGLE_PRECIP", "from=${fromMode.name}") {
            handleTogglePrecipInternal(context, appWidgetId, repository)
        }
    }

    private suspend fun handleTogglePrecipInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.togglePrecipitationMode(appWidgetId)
        Log.d(TAG, "handleTogglePrecip: Toggled to $newMode for widget $appWidgetId")

        val ctx = resolveRefreshContext(context, "toggle_precip")

        if (newMode == ViewMode.PRECIPITATION) {
            refreshGraphView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_PRECIP"
            )
        } else {
            refreshDailyView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_PRECIP"
            )
        }
    }

    /**
     * Handle set view mode action.
     */
    suspend fun handleSetView(
        context: Context,
        appWidgetId: Int,
        targetMode: ViewMode,
        targetOffset: Int = Int.MIN_VALUE,
        repository: WeatherRepository? = null,
    ) {
        // SET_VIEW_RENDER_OK / SET_VIEW_FAIL breadcrumbs (parallel to WIDGET_RENDER_OK on the refresh
        // path) come from runInteraction; the day-tap path bypasses refreshWidget's own breadcrumb.
        // Tests assert on the "widget=<id> mode=<NAME>" shape, so keep that metadata order.
        runInteraction(
            context,
            appWidgetId,
            "SET_VIEW",
            "mode=${targetMode.name} offset=$targetOffset",
        ) {
            handleSetViewInternal(context, appWidgetId, targetMode, targetOffset, repository)
        }
    }

    private suspend fun handleSetViewInternal(
        context: Context,
        appWidgetId: Int,
        targetMode: ViewMode,
        targetOffset: Int = Int.MIN_VALUE,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val previousMode = stateManager.getViewMode(appWidgetId)
        val previousZoom = stateManager.getZoomLevel(appWidgetId)
        val previousOffset = stateManager.getHourlyOffset(appWidgetId)
        stateManager.setViewMode(appWidgetId, targetMode)
        Log.d(
            TAG,
            "handleSetView: target=$targetMode previousMode=$previousMode previousZoom=$previousZoom " +
                "previousOffset=$previousOffset widget=$appWidgetId",
        )
        if (targetMode == ViewMode.DAILY) {
            stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
        } else if (targetMode.isGraphMode) {
            // Reset to WIDE only when entering from daily — preserves zoom when navigating
            // between hourly view types (temperature ↔ precipitation ↔ cloud cover)
            if (previousMode == ViewMode.DAILY) {
                stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
                Log.d(TAG, "handleSetView: RESET zoom to WIDE (was $previousZoom, previousMode=$previousMode)")
            }
            if (targetOffset != Int.MIN_VALUE) {
                stateManager.setHourlyOffset(appWidgetId, targetOffset)
                Log.d(TAG, "handleSetView: set hourlyOffset=$targetOffset (was $previousOffset)")
            }
        }
        val finalOffset = stateManager.getHourlyOffset(appWidgetId)
        Log.d(
            TAG,
            "handleSetView: FINISHED mode=$targetMode targetOffset=$targetOffset " +
                "finalStoredOffset=$finalOffset widget=$appWidgetId",
        )

        val ctx = resolveRefreshContext(context, "set_view")

        when (targetMode) {
            ViewMode.DAILY -> {
                refreshDailyView(
                    context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                    startTimeMs = startMs, actionTag = "SET_VIEW", extraMetadata = "mode=${targetMode.name}"
                )
            }
            else -> {
                refreshGraphView(
                    context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                    startTimeMs = startMs, actionTag = "SET_VIEW", extraMetadata = "mode=${targetMode.name}"
                )
            }
        }
    }

    /**
     * Handle widget resize.
     */
    suspend fun handleResize(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        // Trailing-edge debounce, deliberately OUTSIDE the interaction lock. A drag-resize emits many
        // OPTION_APPWIDGET_* updates; each used to sleep 250ms and then render unconditionally, so
        // nothing coalesced. Once these were serialized by the per-widget mutex the sleeps stopped
        // overlapping and became additive (N * 250ms of held lock), stalling taps on this widget and
        // blocking renderAllWidgetsFromCache's sequential sweep over the others. Now only the newest
        // request survives the sleep, and the lock is taken solely for the one render that follows.
        if (!awaitLatestResizeRequest(appWidgetId)) return

        runInteraction(context, appWidgetId, "RESIZE") {
            handleResizeInternal(context, appWidgetId, repository)
        }
    }

    /**
     * Stamps this resize request, waits out [RESIZE_DEBOUNCE_MS], and reports whether it is still the
     * newest one for [appWidgetId]. Returns false when a later request superseded it — that caller
     * drops its render and the later one does the work.
     */
    @VisibleForTesting
    internal suspend fun awaitLatestResizeRequest(appWidgetId: Int): Boolean {
        val token = resizeRequestSequence.incrementAndGet()
        latestResizeRequest[appWidgetId] = token
        delay(RESIZE_DEBOUNCE_MS)
        if (latestResizeRequest[appWidgetId] != token) {
            Log.d(TAG, "handleResize: superseded request for widget $appWidgetId, dropping")
            return false
        }
        return true
    }

    private suspend fun handleResizeInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val database = WeatherDatabase.getDatabase(context)
        database.appLogDao().log("WIDGET_LIFECYCLE", "phase=handleResize_entry widget=$appWidgetId thread=${Thread.currentThread().name}")

        val stateManager = WidgetStateManager(context)
        // viewMode is fetched here for the entry-state diagnostic log below; refreshWidget re-reads
        // it for dispatch. The duplication is intentional: if refreshWidget throws, the entry log
        // still captures what mode the launcher was rendering into when it delivered the resize.
        val viewMode = stateManager.getViewMode(appWidgetId)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        ResizeDiagnosticsLogger.log(context, appWidgetManager, appWidgetId, viewMode.name, database.appLogDao())

        refreshWidget(
            context, appWidgetId, "resize", repository,
            startTimeMs = startMs, actionTag = "RESIZE",
            partialPush = false,
            origin = WidgetPushDispatcher.Origin.RESIZE,
        )
    }

    /**
     * Central dispatcher for refreshing the widget based on the current view mode.
     */
    private suspend fun refreshWidget(
        context: Context,
        appWidgetId: Int,
        reason: String,
        repository: WeatherRepository? = null,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "REFRESH",
        extraMetadata: String = "",
        partialPush: Boolean = true,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.ACTION_REFRESH,
    ) {
        val ctx = resolveRefreshContext(context, reason)

        val viewMode = WidgetStateManager(context).getViewMode(appWidgetId)
        if (!viewMode.isGraphMode) {
            refreshDailyView(
                context = context,
                appWidgetId = appWidgetId,
                database = ctx.database,
                lat = ctx.location.lat,
                lon = ctx.location.lon,
                repository = repository,
                startTimeMs = startTimeMs,
                actionTag = actionTag,
                extraMetadata = extraMetadata,
                partialPush = partialPush,
                origin = origin,
            )
        } else {
            refreshGraphView(
                context = context,
                appWidgetId = appWidgetId,
                database = ctx.database,
                lat = ctx.location.lat,
                lon = ctx.location.lon,
                repository = repository,
                startTimeMs = startTimeMs,
                actionTag = actionTag,
                extraMetadata = extraMetadata,
                partialPush = partialPush,
                origin = origin,
            )
        }
        // WIDGET_RENDER_OK: same greppable "last good full paint" breadcrumb emitted by the onUpdate
        // path (see WeatherWidgetProvider.renderStartupWidgets). Reached only if the refresh above did
        // not throw, so it marks a successful full-content repaint via the refresh/cache path (e.g. the
        // direct cache repaint that now self-heals a blank widget on every ACTION_REFRESH).
        ctx.database.appLogDao().log(
            "WIDGET_RENDER_OK",
            "widget=$appWidgetId view=$viewMode path=$reason action=$actionTag",
        )
    }

    /**
     * Refreshes the daily view by loading all necessary forecast and observation data.
     */
    private suspend fun refreshDailyView(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
        repository: WeatherRepository? = null,
        weatherList: List<ForecastEntity>? = null,
        dailyActuals: DailyActualsBySource? = null,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "DAILY_REFRESH",
        extraMetadata: String = "",
        partialPush: Boolean = false,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val today = LocalDate.now()
        val historyStart = today.minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val forecastEnd = today.plusDays(DAILY_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val pastSnapshotEnd = today.minusDays(2).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val recentSnapshotStart = today.minusDays(1).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY

        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val gapFiller = ClimateGapFiller(database.climateNormalDao())

        // When the caller (e.g. a view toggle) didn't hand us pre-loaded data, resolve the two heavy
        // loads through WidgetInteractionCache so rapid taps share one query. Daily-nav passes both
        // (already cache-backed on its side), so it skips this lookup entirely.
        val rawWeatherList: List<ForecastEntity>
        val resolvedDailyActuals: DailyActualsBySource
        if (weatherList != null && dailyActuals != null) {
            rawWeatherList = weatherList
            resolvedDailyActuals = dailyActuals
        } else {
            val cached = loadCachedDailyData(context, database, lat, lon, today, historyStart, forecastEnd)
            rawWeatherList = weatherList ?: cached.weatherListRaw
            resolvedDailyActuals = dailyActuals ?: cached.dailyActuals
        }
        val finalWeatherList = gapFiller.appendGaps(
            rawWeatherList,
            lat,
            lon,
            today,
            horizonDays = DAILY_FORECAST_DAYS,
        )
        val pastSnapshots = forecastDao.getLatestForecastsInRange(historyStart, pastSnapshotEnd, lat, lon)
        val recentSnapshots = forecastDao.getAllForecastsInRange(recentSnapshotStart, forecastEnd, lat, lon)
        val forecastSnapshots = gapFiller.appendGapsToSnapshots(
            (pastSnapshots + recentSnapshots)
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY) },
            lat,
            lon,
            today,
            horizonDays = DAILY_FORECAST_DAYS,
        )

        val now = LocalDateTime.now()
        val zoneId = ZoneId.systemDefault()
        val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_GRAPH_LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()
        // Unify to the current site: the proximity-box query also returns frozen fragments from
        // earlier GPS fixes, whose stale noon-cloud rows otherwise win DailyNoonCloudCover's
        // firstOrNull and make this refresh pass disagree with the onUpdate pass (WidgetRenderer
        // already unifies) — the daily bar's cloud split flapped between the two.
        val hourlyForecasts = GraphDataLoader.unifyToNearestSite(
            hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon),
            lat,
            lon,
        )

        val todayStartMs = LocalDate.now().atStartOfDay(zoneId).toInstant().toEpochMilli()
        val ctCurrentTemps = repository?.getMainObservationsWithComputedNwsBlend(lat, lon, todayStartMs)
            ?: database.observationDao().getLatestMainObservations(lat, lon, todayStartMs)
        val currentTempHourlyForecasts =
            GraphDataLoader.loadCurrentTempResolutionHourlyForecasts(
                hourlyDao = hourlyDao,
                lat = lat,
                lon = lon,
                now = now,
            )

        val stateManager = WidgetStateManager(context)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        val graphStyleObs = CurrentTempResolver.resolveGraphStyleCurrentTemp(
            repository = repository,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            hourlyForecasts = currentTempHourlyForecasts,
            now = now,
            personalStationWeight = stateManager.getPersonalStationWeight(),
        )

        val observation = graphStyleObs ?: ObservationResolver.resolveObservedCurrentTemp(ctCurrentTemps, displaySource)

        val smoothedForecasts = CurrentTemperatureResolver.computeSmoothedForecasts(
            currentTempHourlyForecasts.map { it.toHourlyForecast() }, displaySource.id
        )

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherData = WeatherData(
                weatherList = finalWeatherList,
                forecastSnapshots = forecastSnapshots,
                hourlyForecasts = hourlyForecasts,
                currentTemps = ctCurrentTemps,
                dailyActualsBySource = resolvedDailyActuals,
            ),
            observationData = ObservationData(
                lastObservedTemp = observation?.temperature,
                observedAt = observation?.observedAt,
                smoothedForecasts = smoothedForecasts,
                currentTempHourlyForecasts = currentTempHourlyForecasts,
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = stateManager,
            repository = repository,
            partialPush = partialPush,
            origin = origin,
        )

        logTiming(database, appWidgetId, actionTag, startTimeMs, extraMetadata)
    }

    /**
     * Refreshes the graph view by loading hourly forecast data for the current window.
     */
    @VisibleForTesting
    internal suspend fun refreshGraphView(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
        repository: WeatherRepository? = null,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "GRAPH_REFRESH",
        extraMetadata: String = "",
        partialPush: Boolean = false,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    ) {
        val stateManager = WidgetStateManager(context)
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val now = LocalDateTime.now()
        // Pure history/future views render from their fixed nav anchor so refreshes don't drift them;
        // live (now-in-window) views keep tracking `now`. Navigation is unaffected.
        val centerTime = stateManager.resolveHourlyCenterTime(appWidgetId, now, zoom)
        Log.d(
            TAG,
            "refreshGraphView: widget=$appWidgetId view=${stateManager.getViewMode(appWidgetId)} " +
                "zoom=$zoom offset=$hourlyOffset now=$now centerTime=$centerTime source=${displaySource.id}",
        )

        val hourlyForecasts =
            GraphDataLoader.loadGraphWindowHourlyForecasts(
                hourlyDao = database.hourlyForecastDao(),
                hourlyHistoryDao = database.hourlyForecastHistoryDao(),
                lat = lat,
                lon = lon,
                centerTime = centerTime,
                zoom = zoom,
                now = now,
                source = displaySource,
            )

        updateHourlyViewWithData(
            context = context,
            appWidgetId = appWidgetId,
            database = database,
            stateManager = stateManager,
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            displaySource = displaySource,
            lat = lat,
            lon = lon,
            repository = repository,
            now = now,
            partialPush = partialPush,
            origin = origin,
        )

        logTiming(database, appWidgetId, actionTag, startTimeMs, extraMetadata)
    }

    /**
     * Emits the `<tag>_TIMING` row for one interaction, plus a `<tag>_SLOW` row when it overran
     * [SLOW_THRESHOLD_MS]. Shared by the daily and graph refresh paths.
     */
    private suspend fun logTiming(
        database: WeatherDatabase,
        appWidgetId: Int,
        actionTag: String,
        startTimeMs: Long,
        extraMetadata: String,
    ) {
        val totalMs = SystemClock.elapsedRealtime() - startTimeMs
        val metadataString = if (extraMetadata.isNotEmpty()) " $extraMetadata" else ""
        database.appLogDao().log("${actionTag}_TIMING", "widget=$appWidgetId total=${totalMs}ms$metadataString")
        if (totalMs > SLOW_THRESHOLD_MS) {
            database.appLogDao().log("${actionTag}_SLOW", "widget=$appWidgetId total=${totalMs}ms$metadataString")
        }
    }

    private suspend fun updateHourlyViewWithData(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        repository: WeatherRepository? = null,
        now: LocalDateTime,
        partialPush: Boolean = false,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    ) {
        val viewMode = stateManager.getViewMode(appWidgetId)
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Derive today's bounds from the caller's `now` rather than re-calling LocalDate.now():
        // the graph window, current-temp resolution, and this render's precip/current-temp reads
        // all need to agree on which day "today" is across a tick or midnight boundary.
        val today = now.toLocalDate()
        val zoneId = ZoneId.systemDefault()
        val todayEpoch = today.toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val todayStartMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val weatherList = database.forecastDao().getForecastsInRange(todayEpoch, todayEpoch, lat, lon)
        val currentTemps = repository?.getMainObservationsWithComputedNwsBlend(lat, lon, todayStartMs)
            ?: database.observationDao().getLatestMainObservations(lat, lon, todayStartMs)
        val currentTempHourlyForecasts =
            GraphDataLoader.loadCurrentTempResolutionHourlyForecasts(
                hourlyDao = database.hourlyForecastDao(),
                lat = lat,
                lon = lon,
                now = now,
            )

        val todayPrecip = weatherList.find { it.source == displaySource.id }?.precipProbability

        // Resolve current temperature using the graph's IDW + forward extrapolation logic for consistency.
        val graphStyleObs = CurrentTempResolver.resolveGraphStyleCurrentTemp(
            repository = repository,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            hourlyForecasts = currentTempHourlyForecasts,
            now = now,
            personalStationWeight = stateManager.getPersonalStationWeight(),
        )

        val observation = graphStyleObs ?: ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)

        CurrentTempStalenessLogger.log(
            appLogDao = database.appLogDao(),
            appWidgetId = appWidgetId,
            viewMode = viewMode,
            displaySource = displaySource,
            observation = observation,
            centerTime = centerTime,
        )

        when (viewMode) {
            ViewMode.PRECIPITATION -> {
                PrecipViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository,
                    partialPush = partialPush,
                    origin = origin,
                )
            }
            ViewMode.CLOUD_COVER -> {
                CloudCoverViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository,
                    partialPush = partialPush,
                    origin = origin,
                )
            }
            else -> {
                TemperatureViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    currentTempHourlyForecasts = currentTempHourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository,
                    partialPush = partialPush,
                    origin = origin,
                )
            }
        }
    }

}

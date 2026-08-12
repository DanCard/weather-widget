package com.weatherwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.WeatherWidgetApp
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import com.weatherwidget.widget.handlers.CurrentTempResolver
import com.weatherwidget.widget.handlers.GraphDataLoader
import com.weatherwidget.widget.handlers.CloudCoverViewHandler
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.ObservationData
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import com.weatherwidget.widget.handlers.WeatherData
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime

object WidgetRenderer {

    private const val TAG = "WidgetRenderer"

    /**
     * Widget IDs that have had a full DAILY graph paint in the *current process*. The DAILY view skips
     * the expensive rebuild on opportunistic UI-only repaints (see [shouldSkipDailyUiOnlyRepaint]); that
     * skip is only safe once a real graph bitmap exists. After a force-stop / fresh process / app
     * update the widget shows the "Loading…" placeholder and the first update is often UI-only —
     * skipping then would strand it on "Loading…". Cleared implicitly when the process dies.
     */
    private val fullyPaintedDailyWidgetIds: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Whether a UI-only DAILY repaint may skip the rebuild. Safe to skip only when the widget already
     * has a real graph painted this process; otherwise we must do a full paint or the widget stays on
     * the "Loading…" placeholder. Pure so it is unit-testable without a Context/AppWidgetManager.
     */
    @androidx.annotation.VisibleForTesting
    internal fun shouldSkipDailyUiOnlyRepaint(uiOnly: Boolean, alreadyPaintedThisProcess: Boolean): Boolean =
        uiOnly && alreadyPaintedThisProcess

    /** Test hook: reset the process-scoped paint tracker between cases. */
    @androidx.annotation.VisibleForTesting
    internal fun resetPaintTrackingForTest() = fullyPaintedDailyWidgetIds.clear()

    @androidx.annotation.VisibleForTesting
    internal fun markDailyPaintedForTest(appWidgetId: Int) {
        fullyPaintedDailyWidgetIds.add(appWidgetId)
    }

    @androidx.annotation.VisibleForTesting
    internal fun hasDailyPaintedForTest(appWidgetId: Int): Boolean =
        fullyPaintedDailyWidgetIds.contains(appWidgetId)

    suspend fun updateWidgetLoading(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.LOADING,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        views.setViewVisibility(R.id.text_container, View.VISIBLE)
        views.setViewVisibility(R.id.graph_view, View.GONE)
        views.setTextViewText(R.id.day2_label, context.getString(R.string.today))
        views.setTextViewText(R.id.day2_high, "--°")
        views.setTextViewText(R.id.day2_low, context.getString(R.string.widget_loading))
        Log.d(TAG, "WIDGET_PAINT widget=$appWidgetId caller=loading origin=${origin.name} state=loading thread=${Thread.currentThread().name}")
        WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = false,
            caller = "LOADING",
            appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
            origin = origin,
        )
    }

    /**
     * Shown when no location is resolvable at all — no canonical active location, no configured widget
     * location, no cached weather. This is a deliberate dead end, not a transient failure: the app
     * previously fetched and labelled Google HQ here, so a user whose GPS never resolved was shown
     * Mountain View's weather as if it were their own.
     *
     * **The tap has to be wired here.** This is a full push of a fresh view tree, so every
     * PendingIntent from the last render is gone — including `setupDeadZoneCatchAll`, without which a
     * Samsung launcher sends an unclaimed tap to MainActivity and every other launcher does nothing.
     * The message says "tap to set", so the tap opens [ConfigActivity]; with the device-following path
     * possibly blocked, it is the user's only way out of this state.
     *
     * Device following keeps running behind it (`GpsResampler`, driven by the periodic full sync), so
     * a FOLLOW_DEVICE widget can also leave on its own once a fix arrives.
     * Kept deliberately simple — it must never itself throw.
     */
    suspend fun updateWidgetNoLocation(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.NO_LOCATION,
    ) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setTextViewText(R.id.day2_label, context.getString(R.string.today))
            views.setTextViewText(R.id.day2_high, "--°")
            views.setTextViewText(R.id.day2_low, context.getString(R.string.widget_no_location))
            views.setOnClickPendingIntent(R.id.widget_root, setLocationIntent(context, appWidgetId))
            Log.d(TAG, "WIDGET_PAINT widget=$appWidgetId caller=no_location origin=${origin.name} state=no_location thread=${Thread.currentThread().name}")
            WidgetPushDispatcher.push(
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                views = views,
                partialPush = false,
                caller = "NO_LOCATION",
                appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
                origin = origin,
            )
        } catch (e: Exception) {
            Log.e(TAG, "updateWidgetNoLocation failed for widget=$appWidgetId", e)
        }
    }

    /**
     * Minimal fallback shown when a widget update throws unexpectedly. Without this, a crash mid-update
     * leaves the "Loading..." placeholder on the home screen indefinitely (see [updateWidgetLoading]).
     * Kept deliberately simple — it must never itself throw.
     *
     * "Tap to refresh" was the promise; the intent that makes it true is bound below. Like
     * [updateWidgetNoLocation] this is a full push, so it inherits no PendingIntent from the render it
     * replaced, and the recovery path this placeholder exists to offer was doing nothing at all.
     */
    suspend fun updateWidgetError(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.ERROR,
    ) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setTextViewText(R.id.day2_label, context.getString(R.string.today))
            views.setTextViewText(R.id.day2_high, "--°")
            views.setTextViewText(R.id.day2_low, context.getString(R.string.widget_tap_to_refresh))
            views.setOnClickPendingIntent(R.id.widget_root, errorRefreshIntent(context, appWidgetId))
            Log.d(TAG, "WIDGET_PAINT widget=$appWidgetId caller=error origin=${origin.name} state=error thread=${Thread.currentThread().name}")
            WidgetPushDispatcher.push(
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                views = views,
                partialPush = false,
                caller = "ERROR",
                appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
                origin = origin,
            )
        } catch (e: Exception) {
            Log.e(TAG, "updateWidgetError failed for widget=$appWidgetId", e)
        }
    }

    /**
     * Opens the location setup screen in **global** mode: a location applies to every widget
     * (`applyActiveLocationToAllWidgets`), and global mode plain `finish()`es on save instead of
     * completing a widget-add handshake that nobody started here. The widget id still rides along so
     * the CONFIG breadcrumb attributes the visit.
     */
    private fun setLocationIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            WidgetRequestCodes.setLocation(appWidgetId),
            Intent(context, ConfigActivity::class.java)
                .putExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, true)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun errorRefreshIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.errorRefresh(appWidgetId),
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(WidgetActions.ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun updateWidgetWithData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>> = emptyMap(),
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
        currentTemps: List<ObservationEntity> = emptyList(),
        dailyActualsBySource: DailyActualsBySource = emptyMap(),
        repository: WeatherRepository? = null,
        startupToken: String? = null,
        // True for opportunistic UI-only repaints (now-tracking alarm); lets the TEMPERATURE handler
        // skip re-rendering an anchored (static) graph from a narrow now-centered window.
        uiOnly: Boolean = false,
        // True for background (worker-driven) repaints: handlers push via partiallyUpdateAppWidget
        // (in-place patch, no launcher re-inflate flash). See WidgetViewHandler.
        partialPush: Boolean = false,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.UNSPECIFIED,
    ) {
        val renderStartMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val effectiveViewMode =
            if (dimensions.rows == 1 && viewMode != ViewMode.DAILY) {
                ViewMode.DAILY
            } else {
                viewMode
            }
        Log.d(
            TAG,
            "updateWidgetInternal: widget=$appWidgetId viewMode=$viewMode effectiveViewMode=$effectiveViewMode " +
                "rows=${dimensions.rows} zoom=${stateManager.getZoomWindow(appWidgetId)}",
        )

        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val zoom = stateManager.getZoomWindow(appWidgetId)
        val now = LocalDateTime.now()
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        // History views render from a fixed anchor so this automatic refresh doesn't drift them forward;
        // only views that still include the now/fetch-dot point keep tracking live `now`.
        val centerTime = stateManager.resolveHourlyCenterTime(appWidgetId, now, zoom)

        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val locationLat =
            configuredLocation?.first
                ?: weatherList.firstOrNull()?.locationLat
                ?: hourlyForecasts.firstOrNull()?.locationLat
                ?: currentTemps.firstOrNull()?.locationLat
        val locationLon =
            configuredLocation?.second
                ?: weatherList.firstOrNull()?.locationLon
                ?: hourlyForecasts.firstOrNull()?.locationLon
                ?: currentTemps.firstOrNull()?.locationLon
        // Unlike the handler-level fallbacks, this one is not decoration: it is the site every row is
        // unified against. With no configured location and no data carrying one, there is nothing to
        // render, so paint the no-location state instead of unifying against a fabricated coordinate.
        // The worker gates on this too; this is the last line of defence for direct render paths.
        if (locationLat == null || locationLon == null || !locationLat.isFinite() || !locationLon.isFinite()) {
            Log.w(TAG, "updateWidgetWithData: no location for widget=$appWidgetId; painting no-location state")
            updateWidgetNoLocation(context, appWidgetManager, appWidgetId)
            return
        }

        // 1. Pick the coordinate pair in the hourly data closest to our target location, then keep every
        // row at that SAME physical site. We can't use exact float equality: one site accumulates
        // sub-precision fragments across fetches (e.g. 37.4168014 vs 37.4168434, ~tens of metres apart),
        // and exact-matching one fragment silently drops the others — which blanked the forecast line
        // for part of a past day. LocationMatch.sameSite merges those fragments while still excluding a
        // genuinely different marker (e.g. 37.422 vs 37.4168) that would jitter the smoothing.
        val unifiedHourlyForecasts =
            GraphDataLoader.unifyToNearestSite(hourlyForecasts, locationLat, locationLon)

        // Filter hourly forecasts to the NOW-centered window for current temp resolution.
        // This ensures the current temp display is always based on forecasts around NOW,
        // not any scrolled graph window.
        val nowResolutionWindow = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val nowZoneId = ZoneId.systemDefault()
        val nowMinEpoch = nowResolutionWindow.start.atZone(nowZoneId).toInstant().toEpochMilli()
        val nowMaxEpoch = nowResolutionWindow.end.atZone(nowZoneId).toInstant().toEpochMilli()
        val nowCenteredHourlyForecasts = unifiedHourlyForecasts.filter { row ->
            row.dateTime in nowMinEpoch..nowMaxEpoch
        }

        val graphStyleObs =
            if (repository != null) {
                val observations = repository.getObservationsInRange(nowMinEpoch, nowMaxEpoch, locationLat, locationLon)
                CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
                    observations = observations,
                    hourlyForecasts = nowCenteredHourlyForecasts,
                    displaySource = displaySource,
                    lat = locationLat,
                    lon = locationLon,
                    now = now,
                    queryWindow = nowResolutionWindow,
                    personalStationWeight = stateManager.getPersonalStationWeight(),
                )
            } else {
                val resolved = ActualsAggregator.resolveCurrentObservation(
                    observations = currentTemps.map { it.toReading() },
                    hourlyForecasts = nowCenteredHourlyForecasts.map { it.toHourlyForecast() },
                    displaySourceId = displaySource.id,
                    userLat = locationLat,
                    userLon = locationLon,
                    nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    lookbackHours = 12L,
                    lookaheadHours = 2L,
                    personalStationWeight = stateManager.getPersonalStationWeight(),
                )
                resolved?.let { (temp, timestamp, fetchedAt) ->
                    ObservationResolver.ObservedCurrentTemperature(
                        temperature = temp,
                        observedAt = timestamp,
                        source = displaySource.id,
                        rowFetchedAt = fetchedAt
                    )
                }
            }
        val fallbackObservation = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
        val observation = graphStyleObs ?: fallbackObservation
        val observationSource =
            when {
                graphStyleObs != null -> "graph_style"
                observation != null -> "raw_observation"
                else -> "none"
            }
        Log.d(
            TAG,
            "currentObservationSelection: widget=$appWidgetId viewMode=$viewMode zoom=$zoom " +
                "source=${displaySource.id} selected=$observationSource " +
                "graphStyleTemp=${graphStyleObs?.temperature} graphStyleObservedAt=${graphStyleObs?.observedAt} graphStyleRowFetchedAt=${graphStyleObs?.rowFetchedAt} " +
                "fallbackTemp=${fallbackObservation?.temperature} fallbackObservedAt=${fallbackObservation?.observedAt} " +
                "finalTemp=${observation?.temperature} finalObservedAt=${observation?.observedAt}",
        )

        val targetDateEpoch = centerTime.toLocalDate().toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val targetPrecip = weatherList
            .find { it.targetDate == targetDateEpoch && it.source == displaySource.id }
            ?.precipProbability

        val sourceFilteredHourly = unifiedHourlyForecasts.filter {
            it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id
        }

        // Carrying hourly rows but NONE for the display source paints an empty graph ("no cloud
        // data", blank curve) and then makes the gap detector burn a forced sync. Two very different
        // causes look identical on screen, so name them here:
        //   * present=<other sources only> → the caller's list was source-scoped to a STALE snapshot.
        //     HourlyForecastLoader.hourlySourceIds() reads every widget's display source BEFORE the
        //     worker fetches; a source toggle during the fetch leaves the list unable to contain the
        //     new source. Self-heals on the next paint. Cross-check HOURLY_SOURCE_SNAPSHOT_STALE.
        //   * present=<display source absent everywhere> with origin=USER_INTERACTION → a genuine
        //     upstream gap; the source really has no rows at this site.
        val sourceMissingFromLoad = sourceFilteredHourly.isEmpty() && unifiedHourlyForecasts.isNotEmpty()
        if (sourceMissingFromLoad) {
            val present = unifiedHourlyForecasts.groupingBy { it.source }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(",") { "${it.key}:${it.value}" }
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "HOURLY_SOURCE_MISS",
                "widget=$appWidgetId view=$effectiveViewMode origin=${origin.name} " +
                    "displaySource=${displaySource.id} unified=${unifiedHourlyForecasts.size} " +
                    "present=$present site=$locationLat,$locationLon",
                "WARN",
            )
        }

        when (effectiveViewMode) {
            ViewMode.TEMPERATURE -> {
                TemperatureViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = sourceFilteredHourly,
                    currentTempHourlyForecasts = nowCenteredHourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository,
                    startupToken = startupToken,
                    deferCurrentTempResolution = startupToken != null,
                    uiOnly = uiOnly,
                    partialPush = partialPush,
                    origin = origin,
                    sourceMissingFromLoad = sourceMissingFromLoad,
                )
            }
            ViewMode.PRECIPITATION -> {
                PrecipViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = sourceFilteredHourly,
                    centerTime = centerTime,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository,
                    startupToken = startupToken,
                    uiOnly = uiOnly,
                    partialPush = partialPush,
                    origin = origin,
                    sourceMissingFromLoad = sourceMissingFromLoad,
                )
            }
            ViewMode.CLOUD_COVER -> {
                CloudCoverViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = sourceFilteredHourly,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository,
                    startupToken = startupToken,
                    uiOnly = uiOnly,
                    partialPush = partialPush,
                    origin = origin,
                    sourceMissingFromLoad = sourceMissingFromLoad,
                )
            }
            ViewMode.DAILY -> {
                // Daily view has no sub-hourly moving element; skip the expensive rebuild on
                // opportunistic UI-only repaints (the ~2-min now-tracking alarm) — but ONLY once this
                // widget has a real graph painted in the current process. After a force-stop / fresh
                // process / app update the widget shows the "Loading…" placeholder and the first update
                // is often UI-only; skipping then strands it on "Loading…" (graph bitmap never set), so
                // fall through to a full paint instead. See shouldSkipDailyUiOnlyRepaint.
                // A pending transient message (e.g. the no-hourly banner) must paint even on a
                // UI-only repaint — otherwise the daily skip optimization strands the banner shown
                // (never painted) or, worse, never clears it. Grace covers the one post-expiry
                // repaint that removes the banner.
                val transientPending = stateManager.hasTransientMessagePending(
                    appWidgetId,
                    WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
                )
                if (shouldSkipDailyUiOnlyRepaint(uiOnly, fullyPaintedDailyWidgetIds.contains(appWidgetId)) && !transientPending) {
                    WeatherDatabase.getDatabase(context).appLogDao().log(
                        com.weatherwidget.widget.WidgetPerfLogger.TAG_WIDGET_PAINT,
                        "widget=$appWidgetId caller=DAILY origin=${origin.name} state=skipped_ui_only thread=${Thread.currentThread().name}",
                    )
                    return
                }
                DailyViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    weatherData = WeatherData(
                        weatherList = weatherList,
                        forecastSnapshots = forecastSnapshots,
                        hourlyForecasts = unifiedHourlyForecasts,
                        currentTemps = currentTemps,
                        dailyActualsBySource = dailyActualsBySource,
                    ),
                    observationData = ObservationData(
                        lastObservedTemp = observation?.temperature,
                        observedAt = observation?.observedAt,
                        currentTempHourlyForecasts = nowCenteredHourlyForecasts,
                    ),
                    now = LocalDateTime.now(),
                    startupToken = startupToken,
                    stateManagerNullable = stateManager,
                    repository = repository,
                    partialPush = partialPush,
                    origin = origin,
                )
                // A real graph was just painted, so future UI-only ticks for this widget may skip.
                fullyPaintedDailyWidgetIds.add(appWidgetId)
            }
        }

        val totalMs = SystemClock.elapsedRealtime() - renderStartMs
        val firstPaintAgeMs = WeatherWidgetApp.logFirstPaintOnce(
            appWidgetId = appWidgetId,
            view = effectiveViewMode.toString(),
            path = if (startupToken != null) "startupFastPath" else "full",
        )
        // First paint of this process: persist the cold-start latency to app_logs ONLY when slow, so a
        // future ~20s outlier survives logcat rotation. firstPaintAgeMs is process-age to paint (the
        // 20s symptom), distinct from the per-render totalMs logged below. firstTriggerAgeMs in the
        // message isolates OS-slow-to-deliver vs render-slow.
        if (firstPaintAgeMs >= 0L) {
            WidgetPerfLogger.logIfSlow(
                appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
                thresholdMs = WidgetPerfLogger.COLD_START_SLOW_MS,
                totalMs = firstPaintAgeMs,
                appLogTag = WidgetPerfLogger.TAG_COLD_START_PERF,
                message = WidgetPerfLogger.kv(
                    "widget" to appWidgetId,
                    "view" to effectiveViewMode,
                    "path" to (if (startupToken != null) "startupFastPath" else "full"),
                    "firstPaintAgeMs" to firstPaintAgeMs,
                    "firstTriggerAgeMs" to WeatherWidgetApp.coldStartTriggerAgeMs(),
                ),
                debugTag = TAG,
            )
        }
        WidgetPerfLogger.logIfSlow(
            appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
            thresholdMs = WidgetPerfLogger.WIDGET_RENDER_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_WIDGET_RENDER_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to effectiveViewMode,
                "storedView" to viewMode,
                "hourlyCount" to unifiedHourlyForecasts.size,
                "forecastCount" to weatherList.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }
}

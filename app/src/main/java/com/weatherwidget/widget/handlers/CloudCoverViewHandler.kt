package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.ForecastProduct
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HeaderFormatter
import com.weatherwidget.shared.util.HourLabelFormatter
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CloudCoverGraphRenderer
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.actuals.MetarCloudBlender
import com.weatherwidget.shared.graph.CloudActualSeries
import com.weatherwidget.shared.graph.CloudSeriesBuilder
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.util.ViewingRefreshPolicy
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.widget.DataFreshness
import com.weatherwidget.widget.ForecastFetchPolicy
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.GraphRepaintGate
import com.weatherwidget.widget.ObservationWatermark
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import com.weatherwidget.shared.observations.ActualsProviderResolver

/**
 * Handler for the cloud cover view mode.
 */
object CloudCoverViewHandler {
    private const val TAG = "CloudCoverViewHandler"
    private const val CELL_HEIGHT_DP = 90

    @androidx.annotation.VisibleForTesting
    internal fun localizedActualsSourceLabel(
        context: Context,
        sourceName: String?,
    ): DominantStationLabel.LabelText? {
        val name = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DominantStationLabel.plainLabelText(
            context.getString(R.string.actual_cloud_cover_data_from, name),
        )
    }

    /**
     * Look up the most likely upstream reason for missing cloud cover data by checking
     * recent app_logs entries written by NwsForecastMapper. Returns a short human phrase
     * suitable for display in the graph diagnostic, or null if no recent matching event.
     */
    private suspend fun resolveMissingDataReason(
        context: Context,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        lookbackMs: Long = 4 * 60 * 60 * 1000L,
    ): String? {
        val cutoff = System.currentTimeMillis() - lookbackMs
        val gridFail = appLogDao.getLogsByTag("NWS_GRIDPOINTS_FAIL", limit = 1).firstOrNull()
        if (gridFail != null && gridFail.timestamp >= cutoff) {
            return context.getString(R.string.cloud_reason_gridpoints_failed)
        }
        val skyEmpty = appLogDao.getLogsByTag("NWS_SKYCOVER_EMPTY", limit = 1).firstOrNull()
        if (skyEmpty != null && skyEmpty.timestamp >= cutoff) {
            return context.getString(R.string.cloud_reason_skycover_unavailable)
        }
        return null
    }

    /**
     * Build the set of epoch-ms keys for every hour in the visible cloud cover window
     * around [centerTime] given the current [zoom]. Used to count how many hours in the
     * window have cloud cover data, so the renderer can flag missing data honestly
     * without silently switching weather sources.
     */
    @androidx.annotation.VisibleForTesting
    internal fun buildWindowHourKeys(
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomWindow,
    ): Set<Long> {
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        val zoneId = ZoneId.systemDefault()
        return buildSet {
            var currentHour = startHour
            // End-inclusive, matching the hours buildCloudHourDataList actually draws — otherwise
            // this under-counts the window by one hour and the missing-data flag lies about it.
            while (!currentHour.isAfter(endHour)) {
                add(currentHour.atZone(zoneId).toInstant().toEpochMilli())
                currentHour = currentHour.plusHours(1)
            }
        }
    }

    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        precipProbability: Int? = null,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
        startupToken: String? = null,
        uiOnly: Boolean = false,
        // Background (worker-driven) repaints push partially — no launcher re-inflate flash.
        // See WidgetViewHandler.
        partialPush: Boolean = false,
        origin: com.weatherwidget.widget.WidgetPushDispatcher.Origin = com.weatherwidget.widget.WidgetPushDispatcher.Origin.UNSPECIFIED,
        // True when the caller's row set held data for OTHER sources but none for this one — a stale
        // source snapshot in the loader, not a real data gap (see HOURLY_SOURCE_MISS). The window
        // then reads as 100% missing, so the gap detector must NOT spend an API round-trip on it;
        // the API has nothing we don't already hold and the next paint heals it.
        sourceMissingFromLoad: Boolean = false,
        // Newest observation time among the rows this source draws; drives GraphRepaintGate's
        // data-changed check. See ObservationWatermark.
        //
        // Null means "this caller did not measure it" — the interaction path (nav taps, refresh)
        // renders unconditionally and never consults the gate, so it has no watermark to offer.
        // Such a render must PRESERVE the stored value rather than stamp NONE over it: the stored
        // one was measured by the same query the next gated pass will use, and overwriting it with
        // a value from a different query makes the two incomparable.
        dataWatermarkMs: Long? = null,
        // A repaint was skipped outright for screen-off; force a full rebuild. See WidgetPaintCoordinator.
        paintOwed: Boolean = false,
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        setupDeadZoneCatchAll(context, views, appWidgetId)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        // Gate bitmap rebuilds on real change for opportunistic UI-only repaints.
        if (uiOnly) {
            val zoom = stateManager.getZoomWindow(appWidgetId)
            val lastRender = stateManager.getLastGraphRender(appWidgetId)
            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)
            val windowSpanMinutes = zoom.totalSpanHours * 60
            val gateDecision = GraphRepaintGate.shouldRebuildBitmap(
                displayedTemp = null,
                currentDisplayedTemp = null,
                lastRenderMs = lastRender?.renderMs ?: 0L,
                nowMs = SystemClock.elapsedRealtime(),
                windowSpanMinutes = windowSpanMinutes,
                bitmapWidthPx = bitmapDims.widthPx,
                lastWatermarkMs = lastRender?.dataWatermarkMs,
                currentWatermarkMs = dataWatermarkMs ?: ObservationWatermark.NONE,
                paintOwed = paintOwed,
            )
            if (!gateDecision.shouldRebuild) {
                appLogDao.log(
                    WidgetPerfLogger.TAG_WIDGET_PAINT,
                    "widget=$appWidgetId caller=CLOUD_COVER state=skipped reason=${gateDecision.reason} thread=${Thread.currentThread().name}",
                )
                return
            }
        }

        val sourceRows = hourlyForecasts.count { it.source == displaySource.id }
        val sourceRowsWithCloudCover = hourlyForecasts.count { it.source == displaySource.id && it.cloudCover != null }
        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}, " +
                "source=$displaySource sourceRows=$sourceRows sourceRowsWithCloudCover=$sourceRowsWithCloudCover",
        )

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        // Reset sticky visibility from DailyViewHandler
        DailyViewHandler.bindTransientMessage(views, stateManager, appWidgetId, callerTag = "CLOUD_COVER")

        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        val zoom = stateManager.getZoomWindow(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val windowHourKeys = buildWindowHourKeys(centerTime, zoom)
        val effectiveDisplaySource = displaySource
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        setupNavigationButtons(context, views, appWidgetId, stateManager)

        // Temperature header taps toggle back to DAILY view
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)

        if (
            ApiSourceWarningHelper.checkAndRenderBlockingWarning(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                numRows = numRows,
                appLogDao = appLogDao,
                displaySource = displaySource,
                hasSelectedSourceData = hourlyForecasts.any { it.source == displaySource.id },
                callerTag = "CLOUD",
            )
        ) {
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=CLOUD_COVER origin=${origin.name} state=warning thread=${Thread.currentThread().name}")
            com.weatherwidget.widget.WidgetPushDispatcher.push(
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                views = views,
                partialPush = false,
                caller = "CLOUD_COVER_WARNING",
                appLogDao = appLogDao,
                origin = origin,
            )
            return
        }

        val sourceIndicator = HeaderFormatter.formatSourceIndicator(
            centerTime = centerTime,
            now = LocalDateTime.now(),
            sourceName = effectiveDisplaySource.shortDisplayName,
            widthDp = dimensions.widthDp
        )

        val now = LocalDateTime.now()
        // NaN, never a hardcoded coordinate: this is derived from the rows about to be drawn, so it
        // only fires when there are none. NaN degrades honestly downstream (sun shading falls back to
        // UNKNOWN_LOCATION, IDW distance weights drop out) instead of silently rendering Google HQ.
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN
        val sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon)
        val currentHourForecast = WeatherTimeUtils.getCurrentHourForecast(hourlyForecasts, effectiveDisplaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = sunInfo.isNight,
            cloudCover = currentHourForecast?.cloudCover,
            precipProbability = currentHourForecast?.precipProbability,
            isTwilight = sunInfo.phase == SunPhase.TWILIGHT,
            isSunBoundary = sunInfo.isSunBoundary,
        )

        // Weather icon + bottom zone → back to temperature view
        val goTempIconIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.TEMPERATURE.name)
        }
        val goTempIconPending = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.iconViewToggle(appWidgetId), goTempIconIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, goTempIconPending)

        views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        views.setViewVisibility(R.id.current_temp_delta_label, View.GONE)

        val (currentTempResolution, resolveMs) =
            CurrentTempResolutionHelper.resolveAndPersistDelta(
                now = now,
                displaySource = effectiveDisplaySource,
                hourlyForecasts = hourlyForecasts,
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                lat = lat,
                lon = lon,
            )
        val currentTemp = currentTempResolution.displayTemp
        val formattedTemp = if (currentTemp != null) {
            CurrentTemperatureResolver.formatDisplayTemperature(
                temp = currentTemp,
                numColumns = numColumns,
                isStaleEstimate = currentTempResolution.isStaleEstimate,
                useCelsius = stateManager.useCelsius(),
            )
        } else null

        val headerPrecipProbability = HeaderPrecipCalculator.getNext6HourPrecipProbability(
            hourlyForecasts = hourlyForecasts,
            displaySource = effectiveDisplaySource,
            fallbackDailyProbability = precipProbability,
            referenceTime = centerTime,
        )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)
        val precipTextSizeDp = if (headerPrecipProbability != null) HeaderPrecipCalculator.getPrecipTextSize(headerPrecipProbability) else null

        val today = LocalDateTime.now().toLocalDate()
        val isToday = centerTime.toLocalDate() == today

        val headerResult = HourlyHeaderBinder.bindHourlyHeader(
            context = context,
            views = views,
            iconRes = iconRes,
            formattedTemp = formattedTemp,
            isPrecipVisible = isPrecipVisible,
            headerPrecipProbability = headerPrecipProbability,
            precipTextSizeDp = precipTextSizeDp,
            widthDp = dimensions.widthDp,
            numRows = numRows,
            sourceIndicator = sourceIndicator,
            showStations = isToday,
        )
        val headerScale = headerResult.headerScale
        val disclosure = headerResult.disclosure

        setupHomeShortcut(context, views, appWidgetId, scale = headerScale)
        if (!isIconWidth) {
            setupSettingsShortcut(context, views, appWidgetId)
        }
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource, scale = headerScale)
        setupWeatherStationsShortcut(context, views, appWidgetId, scale = headerScale)

        setupGraphSelectorShortcut(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            currentViewMode = com.weatherwidget.widget.ViewMode.CLOUD_COVER,
            widthDp = dimensions.widthDp,
            isPrecipVisible = isPrecipVisible && disclosure.showsPrecip(),
            scale = headerScale,
        )

        // Setup API toggle (skipped at 1 icon wide — target is hidden)
        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows, scale = headerScale)
        }

        positionCenterIcons(views, dimensions.widthDp, context.resources.displayMetrics.density, isPrecipVisible && disclosure.showsPrecip(), isToday, headerResult.inlineZoneWidthDp)

val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        var buildHoursMs = 0L
        var renderMs = 0L

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)
            views.setViewVisibility(R.id.graph_interaction_container, View.VISIBLE)

            val buildHoursStartMs = SystemClock.elapsedRealtime()
            val cloudData = CloudSeriesLoader.loadCloudSeries(
                context = context,
                hourlyForecasts = hourlyForecasts,
                windowHourKeys = windowHourKeys,
                effectiveDisplaySource = effectiveDisplaySource,
            )
            val siteLat = cloudData.siteLat
            val siteLon = cloudData.siteLon
            val windowStart = cloudData.windowStart
            val windowEnd = cloudData.windowEnd
            val priorCloud = cloudData.priorCloud
            val priorBands = cloudData.priorBands
            val retroActual = cloudData.retroActual

            // While-viewing watchdog: this view is literally being drawn, so the user is looking.
            // Refreshes the viewed source's current temp/actuals when stale, and its forecast only when
            // due by the normal cadence (ViewingRefreshPolicy). Debounced per widget/source.
            maybeRefreshWhileViewing(
                context = context,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                displaySource = effectiveDisplaySource,
                repository = repository,
                hourlyForecasts = hourlyForecasts,
                siteLat = siteLat,
                siteLon = siteLon,
            )

            // Repair probe for the actual cloud series. The coverage decision lives in the
            // temperature/daily views, but a widget parked in CLOUD view never renders those —
            // and the missing curve above is THIS view's data, so it must be able to heal itself.
            // NWS-only like the other probes; the shared decision/cooldown prevents
            // double-fetching across views. The cooldown is checked BEFORE the 72h observation
            // read: while it is active the evaluation could only log a cooldown SKIP, so this
            // render skips the expensive read too instead of paying it every paint.
            if (effectiveDisplaySource == WeatherSource.NWS && repository != null && siteLat != null && siteLon != null &&
                !hourlyBackfillCoolingDown(stateManager, appWidgetId, effectiveDisplaySource, siteLat, siteLon)
            ) {
                val backfillStart = now.minusHours(
                    com.weatherwidget.widget.WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS,
                )
                val observations = repository.getObservationsInRange(
                    backfillStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    siteLat,
                    siteLon,
                    // Same consumer as the daily probe: evaluateHourlyBackfillNeed filters this
                    // list through matchesObservationSource, so every other api was read to be
                    // dropped one call later.
                    ActualsReadScope.apisFor(effectiveDisplaySource),
                )
                maybeEnqueueHourlyObservationBackfill(
                    context = context,
                    database = WeatherDatabase.getDatabase(context),
                    stateManager = stateManager,
                    appWidgetId = appWidgetId,
                    displaySource = effectiveDisplaySource,
                    graphStart = backfillStart,
                    graphEnd = now,
                    observations = observations,
                    repositoryPresent = true,
                    observationsLat = siteLat,
                    observationsLon = siteLon,
                )
            }

            val hours = buildCloudHourDataList(
                hourlyForecasts, centerTime, numColumns, effectiveDisplaySource, zoom,
                priorDayCloudForecast = priorCloud,
                retroCloudActual = retroActual.hours,
                priorDayBandForecast = priorBands,
                retroCloudBands = retroActual.bands,
            )
            buildHoursMs = SystemClock.elapsedRealtime() - buildHoursStartMs

            val totalWindowHours = windowHourKeys.size
            val missingHours = (totalWindowHours - hours.size).coerceAtLeast(0)

            val zoneId = ZoneId.systemDefault()
            val presentHourMs = hours.asSequence()
                .map { it.dateTime.atZone(zoneId).toInstant().toEpochMilli() }
                .toSet()
            val missingHourTimes = (windowHourKeys - presentHourMs).asSequence()
                .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDateTime() }
                .sorted()
                .toList()
            val missingDescription = HourLabelFormatter.missingHourRanges(missingHourTimes).takeIf { it.isNotEmpty() }
            val missingReason = if (missingHours > 0) resolveMissingDataReason(context, appLogDao) else null

            if (hours.isEmpty() && hourlyForecasts.isNotEmpty()) {
                Log.w(TAG, "buildCloudHourDataList returned empty despite ${hourlyForecasts.size} hourly rows — " +
                    "centerTime=$centerTime zoom=$zoom source=$effectiveDisplaySource, rendering diagnostic")
            }
            if (missingHours > 0) {
                appLogDao.log(
                    "CLOUD_COVER_GAPS",
                    "widget=$appWidgetId source=${effectiveDisplaySource.id} " +
                        "missing=$missingHours total=$totalWindowHours " +
                        "ranges=${missingDescription ?: "-"} reason=${missingReason ?: "-"} " +
                        "sourceMissingFromLoad=$sourceMissingFromLoad",
                )
                // The one gap-fill: only a fetch that can cover a day in view, that source alone.
                if (!sourceMissingFromLoad) {
                    com.weatherwidget.widget.WidgetDayClickCoordinator.fillHourlyGaps(context, appWidgetId, "cloud_gaps")
                }
            }

            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

            val actualsProviderId = com.weatherwidget.shared.observations.ActualsProviderResolver.providerIdFor(effectiveDisplaySource)
            // Only show the borrowed-actuals label when the visible window actually contains
            // observed cloud data; future-only views (no actuals in hours) suppress it.
            val hasActualsInWindow = hours.any { it.actualCloudCover != null }
            val dominantStationLabel = if (actualsProviderId != effectiveDisplaySource.id && hasActualsInWindow) {
                val provider = WeatherSource.fromId(actualsProviderId)
                localizedActualsSourceLabel(context, provider.displayName)
            } else {
                null
            }

            val hourLabelSpacingDp = if (zoom.stage == com.weatherwidget.widget.ZoomStage.NARROW) 18f else 28f
            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = CloudCoverGraphRenderer.renderGraph(
                com.weatherwidget.widget.CloudRenderRequest(
                    context = context,
                    hours = hours,
                    widthPx = bitmapDims.widthPx,
                    heightPx = bitmapDims.heightPx,
                    currentTime = now,
                    bitmapScale = bitmapDims.bitmapScale,
                    smoothIterations = zoom.smoothIterations,
                    actualSeries = CloudActualSeries.points(
                        values = retroActual.hours,
                        startMs = windowStart,
                        endMs = minOf(windowEnd, System.currentTimeMillis()),
                    ),
                    hourLabelSpacingDp = hourLabelSpacingDp,
                    missingHours = missingHours,
                    totalHours = totalWindowHours,
                    numColumns = numColumns,
                    missingDescription = missingDescription,
                    missingReason = missingReason,
                    job = coroutineContext[Job],
                    showErrorWatermark = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).show,
                    errorSourceLabel = effectiveDisplaySource.displayName,
                    errorCode = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).errorCode,
                    errorFailureTimeMs = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).failureTimeMs,
                    errorBannerSinceMs = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).bannerSinceMs,
                    dominantStationLabel = dominantStationLabel,
                ),
            )
            renderMs = SystemClock.elapsedRealtime() - renderStartMs

            views.setImageViewBitmap(R.id.graph_view, bitmap)

            // Cloud-cover body taps always zoom; bottom zones still route by icon.
            val hourIcons = hours.map { it.iconRes }
            setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)
            HourlyBottomZoneHelper.setup(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                hourIconResources = hourIcons,
                currentViewMode = com.weatherwidget.widget.ViewMode.CLOUD_COVER,
                zoom = zoom,
                hourlyOffset = hourlyOffset,
            )
            ErrorPillTouchTargetHelper.setupErrorPillTouchTarget(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                showErrorWatermark = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).show,
                errorCode = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).errorCode,
                bannerSinceMs = stateManager.viewWatermark(effectiveDisplaySource, ForecastProduct.HOURLY).bannerSinceMs,
                sourceId = effectiveDisplaySource.id,
            )
        } else {
            views.setViewVisibility(R.id.error_pill_touch_zone, View.GONE)
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
            updateCloudTextMode(views, hourlyForecasts, centerTime, numColumns, effectiveDisplaySource)
        }

        if (isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
        }

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=CLOUD_COVER origin=${origin.name} state=data push=${if (partialPush) "partial" else "full"} thread=${Thread.currentThread().name}")
        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = partialPush,
            caller = "CLOUD_COVER",
            appLogDao = appLogDao,
            origin = origin,
        )

        // Persist render metadata for the GraphRepaintGate on future uiOnly cycles.
        stateManager.setLastGraphRender(
            appWidgetId,
            com.weatherwidget.widget.WidgetStateManager.LastGraphRenderState(
                renderMs = SystemClock.elapsedRealtime(),
                displayedTemp = null,
                dataWatermarkMs = dataWatermarkMs
                    ?: stateManager.getLastGraphRender(appWidgetId)?.dataWatermarkMs,
            ),
        )
        val totalMs = SystemClock.elapsedRealtime() - handlerStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = appLogDao,
            thresholdMs = WidgetPerfLogger.WIDGET_RENDER_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_WIDGET_RENDER_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to "CLOUD_COVER",
                "useGraph" to useGraph,
                "resolveMs" to resolveMs,
                "buildHoursMs" to buildHoursMs,
                "renderMs" to renderMs,
                "hourlyCount" to hourlyForecasts.size,
                "source" to effectiveDisplaySource.id,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    /**
     * While-viewing watchdog ([ViewingRefreshPolicy], user's rule 2026-10-08). The CLOUD view is
     * rendering, so the user is looking at it. For the viewed source only:
     * - current temp / actuals older than 15 min → a current-temp refresh (`viewing_actuals`);
     * - forecast due by the normal cadence → a targeted forced refresh, hourly-limited
     *   (`viewing_forecast_due`). Never sooner: the screen being on is not a reason to refetch it.
     * Debounced per widget + source with the store the backfill probe uses.
     *
     * [lastActualsAtMs] / [forecastIntervalMs] default to the device's own state; tests pass them.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun maybeRefreshWhileViewing(
        context: Context,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
        hourlyForecasts: List<HourlyForecastEntity>,
        siteLat: Double? = null,
        siteLon: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
        lastActualsAtMs: Long? = null,
        forecastIntervalMs: Long? = null,
    ): ViewingRefreshPolicy.Decision? {
        if (repository == null) return null
        val hourlyFetchedAt = hourlyForecasts
            .filter { it.source == displaySource.id }
            .maxOfOrNull { it.fetchedAt }
            ?: return null
        // Android skips rewriting unchanged hourly rows, so the provider's last success is the better
        // "last forecast fetch"; the rows are the fallback when the site is unknown.
        val lastForecastAtMs = if (siteLat != null && siteLon != null) {
            maxOf(
                hourlyFetchedAt,
                FetchMetadata.getLastForecastSourceSuccessTime(context, displaySource.id, siteLat, siteLon),
            )
        } else {
            hourlyFetchedAt
        }
        val actualsAtMs = lastActualsAtMs ?: if (siteLat != null && siteLon != null) {
            FetchMetadata.getLastCurrentTempFetchTime(context, siteLat, siteLon)
        } else {
            FetchMetadata.getLastCurrentTempFetchTime(context)
        }.takeIf { it > 0L }
        val intervalMs = forecastIntervalMs ?: DataFreshness.deviceFetchContext(context, stateManager).let {
            ForecastFetchPolicy.intervalMinutes(
                isCharging = it.isCharging,
                isScreenInteractive = it.isScreenInteractive,
                isActiveSource = true,
                batteryLevel = it.batteryLevel,
            )?.times(60_000L)
        }
        val decision = ViewingRefreshPolicy.decide(actualsAtMs, lastForecastAtMs, intervalMs, nowMs)
        if (!decision.any) return decision
        if (!stateManager.shouldRefreshMissingData(
                appWidgetId,
                displaySource.id,
                VIEWING_COOLDOWN_KEY,
                ViewingRefreshPolicy.ACTUALS_STALE_WHILE_VIEWING_MS,
            )
        ) {
            return ViewingRefreshPolicy.Decision(refreshActuals = false, refreshForecast = false)
        }
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, VIEWING_COOLDOWN_KEY)
        Log.i(
            TAG,
            "VIEWING_REFRESH source=${displaySource.id} actuals=${decision.refreshActuals} " +
                "forecast=${decision.refreshForecast} forecastAgeMin=${(nowMs - lastForecastAtMs) / 60_000L}",
        )
        if (decision.refreshForecast) {
            RefreshScheduler.enqueueForcedRefresh(
                context = context,
                reason = "viewing_forecast_due",
                targetSourceId = displaySource.id,
                hourlyLimited = true,
            )
        }
        if (decision.refreshActuals) {
            com.weatherwidget.widget.CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = context,
                reason = "viewing_actuals",
                opportunistic = false,
                targetSourceId = displaySource.id,
            )
        }
        return decision
    }

    private const val VIEWING_COOLDOWN_KEY = "cloud_viewing"

    @androidx.annotation.VisibleForTesting
    internal fun buildCloudHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomWindow = com.weatherwidget.widget.ZoomStage.WIDE.window(),
        // Day-ago predictions by top-of-hour epoch ms. Empty for every source without a
        // previous-runs product, which collapses both curves onto the live value.
        priorDayCloudForecast: Map<Long, Int> = emptyMap(),
        // Low-cloud actuals by native provider timestamp. Authoritative — a timestamp draws an
        // actual if and only if it appears here.
        retroCloudActual: Map<Long, Int> = emptyMap(),
        // Day-ago band predictions from our own hourly snapshots — the Previous Runs API has no
        // band data (see PriorDayBandForecast). Empty for every source but Open-Meteo.
        priorDayBandForecast: Map<Long, com.weatherwidget.shared.graph.CloudBands> = emptyMap(),
        // Observed bands by native provider timestamp, PROVIDER_BANDS rows only.
        retroCloudBands: Map<Long, com.weatherwidget.shared.graph.CloudBands> = emptyMap(),
        now: LocalDateTime = LocalDateTime.now(),
    ): List<CloudCoverGraphRenderer.CloudHourData> {
        // NaN, never a hardcoded coordinate: derived from the rows about to be drawn, so it only
        // fires when there are none. Sun shading degrades to UNKNOWN_LOCATION downstream.
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN

        // One row per hour for the display source (first matching row per hour, as before), then
        // the SHARED pairing below — Android and desktop must not disagree about which value lands
        // on which curve: forecast = day-ago prediction for past hours where stored (live value
        // otherwise, low layer preferred), actual = filed observations only.
        val entityByTime = hourlyForecasts.groupBy { it.dateTime }
            .mapValues { entry -> entry.value.find { it.source == displaySource.id } }

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        Log.d(
            TAG,
            "buildCloudHourDataList: centerTime=$centerTime alignedCenter=$alignedCenter " +
                "startHour=$startHour endHour=$endHour zoom=$zoom source=$displaySource",
        )

        val zoneId = ZoneId.systemDefault()
        // End-INCLUSIVE window, same as the temperature graph's shared ActualTemperatureSeriesBuilder: an
        // n-hour window spans start..start+n and needs n+1 marks, or the drawn axis is an hour
        // narrower than the Hourly Zoom setting promises.
        val windowStartMs = startHour.atZone(zoneId).toInstant().toEpochMilli()
        val windowEndMs = endHour.atZone(zoneId).toInstant().toEpochMilli()
        val series = CloudSeriesBuilder.build(
            liveHours = entityByTime.values.filterNotNull()
                .filter { it.dateTime in windowStartMs..windowEndMs }
                .map { it.toHourlyForecast() },
            priorForecast = priorDayCloudForecast,
            retroActual = retroCloudActual,
            nowMs = now.atZone(zoneId).toInstant().toEpochMilli(),
            priorBands = priorDayBandForecast,
            retroBands = retroCloudBands,
        )

        // Narrow widgets widen the marker cadence to fit the inline footer groups: WIDE 6h vs 4h,
        // and NARROW every other hour once its span is widened past 6h. Wide widgets keep the
        // default at both zooms. Matches the temperature graph.
        val labelInterval = when {
            !com.weatherwidget.widget.HourlyFooterRenderer.isNarrowWidget(numColumns) ->
                zoom.labelInterval
            zoom.stage == com.weatherwidget.widget.ZoomStage.WIDE ->
                com.weatherwidget.shared.graph.HourlyGraphDefaults.NARROW_WIDE_LABEL_INTERVAL
            zoom.stage == com.weatherwidget.widget.ZoomStage.NARROW ->
                com.weatherwidget.shared.graph.HourlyZoomRules
                    .narrowWidgetLabelInterval(zoom.totalSpanHours.toInt())
            else -> zoom.labelInterval
        }

        // On multi-day windows switch the footer to one date label per day ("Tue 23"), matching the
        // temperature graph (shared rule in HourlyGraphViewCommon.resolveHourLabel).
        val dateMode = com.weatherwidget.shared.graph.HourlyZoomRules.isDateMode(zoom.totalSpanHours)
        val dateLabelMillis = if (dateMode) dateLabelMillis(startHour, endHour, zoneId) else emptySet()

        val hours = mutableListOf<CloudCoverGraphRenderer.CloudHourData>()
        var hourIndex = 0
        for (point in series) {
            val entity = entityByTime[point.timeMs] ?: continue
            val cover = point.forecastCover ?: continue
            val currentHour = Instant.ofEpochMilli(point.timeMs).atZone(zoneId).toLocalDateTime()
            val p = HourlyGraphViewCommon.resolveHourPresentation(
                currentHour, entity, now, lat, lon, labelInterval, hourIndex,
                hourMs = point.timeMs, dateMode = dateMode, dateLabelMillis = dateLabelMillis,
            )
            hours.add(
                CloudCoverGraphRenderer.CloudHourData(
                    dateTime = currentHour,
                    cloudCover = cover,
                    actualCloudCover = point.actualCover,
                    isFrozenForecast = point.isFrozen,
                    // Resolved by CloudSeriesBuilder exactly as the low curve is, NOT read off the
                    // live row. For an elapsed hour that row has already been retro-corrected by
                    // later Open-Meteo runs, so reading it here drew observed values in the
                    // forecast's grey.
                    midCover = point.forecastBands.mid,
                    highCover = point.forecastBands.high,
                    actualLowCover = point.actualBands.low,
                    actualMidCover = point.actualBands.mid,
                    actualHighCover = point.actualBands.high,
                    isFrozenBands = point.isFrozenBands,
                    // Not drawn; used only to tell a redundant band glyph from an explanatory one.
                    lowCover = entity.cloudCoverLow,
                    label = p.label,
                    iconRes = p.iconRes,
                    isNight = p.isNight,
                    isTwilight = p.isTwilight,
                    isSunBoundary = p.isSunBoundary,
                    isSunny = p.isSunny,
                    isRainy = p.isRainy,
                    isMixed = p.isMixed,
                    isCurrentHour = p.isCurrentHour,
                    showLabel = p.showLabel,
                    isDateLabel = p.isDateLabel,
                ),
            )
            hourIndex++
        }

        return hours
    }

    private fun updateCloudTextMode(
        views: RemoteViews,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
    ) {
        HourlyGraphViewCommon.bindHourlyTextMode(
            views, hourlyForecasts, centerTime, numColumns, displaySource,
        ) { forecast ->
            forecast?.let {
                com.weatherwidget.shared.util.VisibleCloudCover.of(
                    total = it.cloudCover, low = it.cloudCoverLow,
                    mid = it.cloudCoverMid, high = it.cloudCoverHigh,
                )
            }?.let { "$it%" } ?: "--%"
        }
    }
}

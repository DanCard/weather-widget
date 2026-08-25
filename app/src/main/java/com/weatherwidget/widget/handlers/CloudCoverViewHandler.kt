package com.weatherwidget.widget.handlers

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
import com.weatherwidget.util.HeaderFormatter
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
import com.weatherwidget.shared.util.CloudViewingRefreshPolicy
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
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
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        lookbackMs: Long = 4 * 60 * 60 * 1000L,
    ): String? {
        val cutoff = System.currentTimeMillis() - lookbackMs
        val gridFail = appLogDao.getLogsByTag("NWS_GRIDPOINTS_FAIL", limit = 1).firstOrNull()
        if (gridFail != null && gridFail.timestamp >= cutoff) return "NWS gridpoints fetch failed"
        val skyEmpty = appLogDao.getLogsByTag("NWS_SKYCOVER_EMPTY", limit = 1).firstOrNull()
        if (skyEmpty != null && skyEmpty.timestamp >= cutoff) return "NWS sky cover unavailable"
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

        val headerPrecipProbability = HeaderPrecipCalculator.getNext8HourPrecipProbability(
            hourlyForecasts = hourlyForecasts,
            displaySource = effectiveDisplaySource,
            fallbackDailyProbability = precipProbability,
            referenceTime = centerTime,
        )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)
        val precipTextSizeDp = if (headerPrecipProbability != null) HeaderPrecipCalculator.getPrecipTextSize(headerPrecipProbability) else null

        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = sourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = formattedTemp,
            deltaText = null,
            precipText = if (isPrecipVisible) "$headerPrecipProbability%" else null,
            precipTextSizeDp = precipTextSizeDp,
        )

        HeaderRemoteViewsBinder.bindApiSource(
            context = context,
            views = views,
            sourceText = sourceIndicator,
            textSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            scale = headerScale,
        )
        views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.settings_icon,
            iconRes = R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = headerScale,
            tintColor = 0xAAFFFFFF.toInt()
        )
        views.setViewVisibility(R.id.top_right_header_container, View.VISIBLE)

        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.weather_icon,
            iconRes = iconRes,
            sizeDp = HeaderConstants.WEATHER_ICON_SIZE_DP,
            scale = headerScale,
        )

        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = formattedTemp,
            scale = headerScale,
        )

        HeaderRemoteViewsBinder.bindPrecipProbability(
            context = context,
            views = views,
            precipText = if (isPrecipVisible) "$headerPrecipProbability%" else null,
            textSizeDp = precipTextSizeDp ?: 0f,
            scale = headerScale,
        )
HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible)

// Apply progressive disclosure for narrow widgets
val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
    context = context,
    widthDp = dimensions.widthDp,
    apiSourceText = sourceIndicator,
    apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
    currentTempText = formattedTemp,
    deltaText = null,
    precipText = if (isPrecipVisible) "$headerPrecipProbability%" else null,
    precipTextSizeDp = precipTextSizeDp,
)
HeaderRemoteViewsBinder.applyDisclosure(views, disclosure, isPrecipVisible = isPrecipVisible)

        val today = LocalDateTime.now().toLocalDate()
        val isToday = centerTime.toLocalDate() == today

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

        positionCenterIcons(views, dimensions.widthDp, context.resources.displayMetrics.density, isPrecipVisible && disclosure.showsPrecip(), isToday)

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
            // Day-ago predictions for the visible window: the forecast curve's frozen half. Scoped
            // to the same site the rows being drawn came from, and empty for every source without a
            // previous-runs product — which collapses both curves onto the live value.
            val siteLat = hourlyForecasts.firstOrNull()?.locationLat
            val siteLon = hourlyForecasts.firstOrNull()?.locationLon
            val siteResolved = siteLat != null && siteLon != null && windowHourKeys.isNotEmpty()
            // The actual curve exists wherever a cloud product exists, asked of the feed that
            // actually SUPPLIES this source's cloud rather than of the source itself: a
            // provider-history source via its synthetic backfill row, a station-observation feed via
            // the read-time station blend, and a forecast-only source via whichever feed it borrows
            // (ActualsProviderResolver — the same choice that supplies its temperature actuals).
            // Must agree with MetarCloudBlender.fromSiteRows, which gates on the same provider; a
            // curve declared available here and refused there paints an empty graph.
            //
            // The frozen forecast curve stays Open-Meteo-only — it is the one source with a
            // previous-runs product, so under every other source the forecast falls back to the
            // live value with isFrozen = false, which the builder and renderer already handle.
            val cloudProvider =
                WeatherSource.fromId(ActualsProviderResolver.providerIdFor(effectiveDisplaySource))
            val cloudSeriesAvailable = siteResolved && cloudProvider.supportsCloudActuals
            val priorCloudAvailable = siteResolved && effectiveDisplaySource == WeatherSource.OPEN_METEO
            val cloudHistoryDao = if (priorCloudAvailable) {
                WeatherDatabase.getDatabase(context).hourlyForecastHistoryDao()
            } else {
                null
            }
            val windowStart = windowHourKeys.minOrNull() ?: 0L
            val windowEnd = (windowHourKeys.maxOrNull() ?: 0L) + 1
            val priorCloud = if (cloudHistoryDao != null) {
                runCatching {
                    cloudHistoryDao.getPriorDayCloudForecast(
                        startDateTime = windowStart,
                        endDateTime = windowEnd,
                        lat = siteLat!!,
                        lon = siteLon!!,
                    )
                }.getOrElse {
                    Log.w(TAG, "prior-day cloud read failed; falling back to live values", it)
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            val retroActual = if (cloudSeriesAvailable) {
                runCatching {
                    WeatherDatabase.getDatabase(context).observationDao().getCloudActuals(
                        startTs = windowStart,
                        // Bounded at "now", matching DesktopWeatherRepository. Cloud buckets round to
                        // the NEAREST hour, so a reading at 11:35 buckets to 12:00 — and with the
                        // window end alone, the actual curve would draw a real observation to the
                        // RIGHT of the NOW marker. A past-day window keeps its own end.
                        endTs = minOf(windowEnd, System.currentTimeMillis()),
                        lat = siteLat!!,
                        lon = siteLon!!,
                        sourceId = effectiveDisplaySource.id,
                    )
                }.getOrElse {
                    Log.w(TAG, "cloud actual read failed; graph shows forecast only", it)
                    MetarCloudBlender.empty(isMetarBlend = false)
                }
            } else {
                MetarCloudBlender.empty(isMetarBlend = false)
            }

            // Permanent diagnostic: the cloud actual has now failed silently twice — once because the
            // write dropped it, once because nothing was stored at all — and both looked identical
            // on screen (a single solid curve). This pins which leg is empty without a DB pull, and
            // the METAR blend stats separate "every station is a PWS" from a thin-but-alive blend.
            val metarStats = if (retroActual.isMetarBlend) " ${retroActual.stats.summary()}" else ""
            Log.i(
                TAG,
                "CLOUD_SERIES src=${effectiveDisplaySource.id} site=$siteLat,$siteLon " +
                    "window=${windowStart}..${windowEnd} prior=${priorCloud.size} actual=${retroActual.hours.size} " +
                    "inWindow=${retroActual.hours.keys.count { it in windowStart until windowEnd }}$metarStats",
            )

            // Cloud-while-viewing watchdog: this view is literally being drawn, so if the active
            // source's cloud data is stale, fetch it now instead of waiting for the slow full-forecast
            // loop. Debounced per widget/source so a repaint storm can't stampede the network.
            maybeRefreshCloudWhileViewing(
                context = context,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                displaySource = effectiveDisplaySource,
                repository = repository,
                hourlyForecasts = hourlyForecasts,
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
            val missingDescription = formatMissingHourRanges(missingHourTimes).takeIf { it.isNotEmpty() }
            val missingReason = if (missingHours > 0) resolveMissingDataReason(appLogDao) else null

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
                val cooldownMs = 15 * 60 * 1000L
                if (!sourceMissingFromLoad &&
                    stateManager.shouldRefreshMissingData(appWidgetId, effectiveDisplaySource.id, "hourly_gaps", cooldownMs)
                ) {
                    stateManager.markMissingDataRefreshRequested(appWidgetId, effectiveDisplaySource.id, "hourly_gaps")
                    appLogDao.log(
                        "CLOUD_COVER_GAPS_REFRESH",
                        "widget=$appWidgetId source=${effectiveDisplaySource.id} missing=$missingHours, requesting immediate API update",
                        "INFO"
                    )
                    WidgetWorkScheduler.enqueueRedundantImmediateSync(
                        context = context,
                        forceRefresh = true,
                        reason = "hourly_gaps"
                    )
                }
            }

            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

            val dominantStationLabel = if (com.weatherwidget.shared.observations.ActualsProviderResolver.borrows(effectiveDisplaySource)) {
                val provider = WeatherSource.fromId(com.weatherwidget.shared.observations.ActualsProviderResolver.providerIdFor(effectiveDisplaySource))
                localizedActualsSourceLabel(context, provider.displayName)
            } else {
                null
            }

            val hourLabelSpacingDp = if (zoom.stage == com.weatherwidget.widget.ZoomStage.NARROW) 18f else 28f
            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = CloudCoverGraphRenderer.renderGraph(
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
                showErrorWatermark = stateManager.isSourceErrored(effectiveDisplaySource),
                errorSourceLabel = effectiveDisplaySource.displayName,
                errorCode = stateManager.getSourceLastErrorCode(effectiveDisplaySource),
                errorFailureTimeMs = stateManager.getSourceLastFailureTime(effectiveDisplaySource),
                dominantStationLabel = dominantStationLabel,
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
        } else {
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
     * Cloud-while-viewing watchdog. The CLOUD view is rendering right now, so "the user is looking
     * at the cloud graph" is true by construction. If the active source's hourly forecast is stale
     * beyond [CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS], enqueue a targeted refresh of
     * that source (which also re-files its cloud actuals) instead of waiting for the slow
     * full-forecast loop. Debounced per widget + source with the same store the backfill probe uses.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun maybeRefreshCloudWhileViewing(
        context: Context,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        if (repository == null) return
        val latestFetchedAt = hourlyForecasts
            .filter { it.source == displaySource.id }
            .maxOfOrNull { it.fetchedAt }
            ?: return
        val nowMs = System.currentTimeMillis()
        if (!CloudViewingRefreshPolicy.isStale(latestFetchedAt, nowMs)) return
        if (!stateManager.shouldRefreshMissingData(
                appWidgetId,
                displaySource.id,
                "cloud_viewing",
                CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS,
            )
        ) {
            return
        }
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, "cloud_viewing")
        Log.i(
            TAG,
            "CLOUD_VIEWING_STALE source=${displaySource.id} ageMin=${(nowMs - latestFetchedAt) / 60_000L} " +
                "enqueueing targeted cloud refresh",
        )
        RefreshScheduler.enqueueForcedRefresh(
            context = context,
            reason = "cloud_while_viewing",
            targetSourceId = displaySource.id,
        )
    }

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
    ): List<CloudCoverGraphRenderer.CloudHourData> {
        val now = LocalDateTime.now()
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
        ) { forecast -> (forecast?.cloudCoverLow ?: forecast?.cloudCover)?.let { "$it%" } ?: "--%" }
    }
}

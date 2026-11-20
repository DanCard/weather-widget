package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.data.model.ForecastProduct
import android.content.Context
import android.util.Log
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.ObservationPoolDiagnostics
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.shared.actuals.DominantBlend
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.FetchDotDebug
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class MissingForecastHours(
    val missingCount: Int,
    val noSelectedForecastCount: Int,
    val wrongSourceCount: Int,
    val spans: List<Pair<LocalDateTime, LocalDateTime>>,
    /**
     * Gaps older than the live-table boundary (`now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS`).
     * These are history's to fill — `ElapsedForecastBackfill` already files every elapsed hour a
     * payload carries — so a live fetch cannot change them and must not be forced for them
     * (performance/260912-hourly-gap-refresh-ignores-elapsed-hours.md).
     */
    val elapsedCount: Int = 0,
) {
    /** Gaps a live fetch could still fill: the current hour onward. */
    val fillableCount: Int get() = missingCount - elapsedCount

    fun diagnosticText(): String {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val spanText = spans.joinToString(",") { (start, endExclusive) ->
            "${start.format(formatter)}..${endExclusive.format(formatter)}"
        }
        return "missing=$missingCount elapsed=$elapsedCount noSelected=$noSelectedForecastCount " +
            "wrongSource=$wrongSourceCount spans=[$spanText]"
    }
}

internal object TemperatureStateResolver {
    private const val TAG = "TemperatureStateResolver"
    private const val CELL_HEIGHT_DP = 90
    private const val GRAPH_MIN_ROWS = 1.4f
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 12

    /**
     * Localized "Actual temperature data from X" annotation for borrowed actuals, mirroring
     * [CloudCoverViewHandler.localizedActualsSourceLabel]. User-facing prose stays at this
     * Android boundary; the shared label only carries the finished text.
     */
    @androidx.annotation.VisibleForTesting
    internal fun temperatureActualsSourceLabel(
        context: Context,
        sourceName: String?,
    ): DominantStationLabel.LabelText? {
        val name = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DominantStationLabel.formatActualsSourceLabel(
            context.getString(R.string.actual_temperature_data_from),
            name,
        )
    }

    /**
     * Runs the graph render, turning a failed render into `null` for the binder.
     *
     * Cancellation is not a failure and is rethrown. `renderGraph`'s first statement is
     * `job?.ensureActive()`; a bare `catch (e: Exception)` here caught that
     * `CancellationException`, so a paint cancelled by its replacement carried on inside a dead
     * coroutine and pushed `GraphState(bitmap=null, showTextMode=true)` — an empty body, since
     * `text_container` is the daily text layout and nothing on this path populates it (widget #88
     * blank for four minutes, 2026-09-12; see
     * plans/260912-cancelled-temperature-render-pushes-blank-body.md). The replacing paint owns
     * the screen; a cancelled one must send nothing.
     */
    @androidx.annotation.VisibleForTesting
    internal inline fun <T : Any> renderOrNull(
        onFailure: (Exception) -> Unit,
        render: () -> T,
    ): T? =
        try {
            render()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            null
        }

    data class ResolutionResult(
        val state: TemperatureWidgetState,
        val resolveMs: Long,
        val obsQueryMs: Long,
        val buildHourDataMs: Long,
        val renderMs: Long,
        val currentTempResolution: CurrentTemperatureResolution,
        val headerPrecipProbability: Int?,
        val lat: Double,
        val lon: Double,
        val smoothedForecasts: Map<Long, Float>,
        val isNowLineVisible: Boolean,
        val deltaFromYesterday: Float?,
    )

    suspend fun resolve(
        context: Context,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        precipProbability: Int?,
        lastObservedTemp: Float?,
        observedAt: Long?,
        // "From yesterday", taken with the observation (`:shared` CurrentTempRun.observe).
        deltaFromYesterday: Float?,
        dimensions: WidgetDimensions,
        stateManager: WidgetStateManager,
        repository: WeatherRepository?,
        deferCurrentTempResolution: Boolean,
        startupToken: String? = null,
        // Skip the graph's observation read and paint the forecast curve alone. Set by the startup
        // token, and — since 2026-09-06 — by the interaction path's phase-1 paint, which follows it
        // immediately with a full one. See [deferGraphActuals] below; the mechanism is identical,
        // only the caller is new.
        deferGraphActualsRequested: Boolean = false,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        appLogDao: AppLogDao? = null,
        now: LocalDateTime = LocalDateTime.now(),
        // See CloudCoverViewHandler: a stale source snapshot in the loader, not a real data gap.
        sourceMissingFromLoad: Boolean = false,
    ): ResolutionResult {
        val effectiveAppLogDao = appLogDao ?: WeatherDatabase.getDatabase(context).appLogDao()
        // The location the user is AT, not the location the first cached row happened to be fetched
        // at. See BlendCentre: this is the CENTRE of ObservationSiteMerge's merge box, so a stale
        // value does not down-weight fresh observations, it excludes every one of them. Mirrors the
        // current-temp resolution in TemperatureViewHandler — the two must not disagree within one
        // paint, which is exactly what shipped before 2026-08-28.
        val blendCentre = resolveBlendCentre(appWidgetId, hourlyForecasts, displaySource, stateManager, effectiveAppLogDao)
        val lat = blendCentre.lat
        val lon = blendCentre.lon

        val zoom = stateManager.getZoomWindow(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)

        val hourlyRangeStr = if (hourlyForecasts.isEmpty()) "empty" else {
            val start = java.time.Instant.ofEpochMilli(hourlyForecasts.minOf { it.dateTime }).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            val end = java.time.Instant.ofEpochMilli(hourlyForecasts.maxOf { it.dateTime }).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            "$start to $end"
        }
        Log.d(TAG, "resolve: widget=$appWidgetId centerTime=$centerTime offset=$hourlyOffset hourlyForecastsRange=$hourlyRangeStr count=${hourlyForecasts.size}")

        // 1. Source Warning
        val warning = ApiSourceWarningHelper.resolveBlockingSourceWarning(
            appLogDao = effectiveAppLogDao,
            displaySource = displaySource,
            hasSelectedSourceData = hourlyForecasts.any { it.source == displaySource.id },
        )
        if (warning != null) {
            return buildFallbackResult(appWidgetId, displaySource, zoom, hourlyOffset, lat, lon, warning = warning)
        }

        // 2. Data Pre-processing
        val smoothedForecasts = computeSmoothedForecasts(hourlyForecasts, displaySource)
        // Smoothed forecasts for current temp resolution use the NOW-centered window, not the
        // scrolled graph window, so that interpolation finds the correct current-hour data.
        val currentTempSmoothedForecasts = computeSmoothedForecasts(currentTempHourlyForecasts, displaySource)
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= GRAPH_MIN_ROWS
        val deferGraphActuals =
            shouldDeferGraphActuals(startupToken, deferGraphActualsRequested, useGraph)

        // 3. Load Graph Hours
        val graphLoadResult = TemperatureGraphHoursLoader.loadGraphHours(
            context = context,
            appWidgetId = appWidgetId,
            database = WeatherDatabase.getDatabase(context),
            stateManager = stateManager,
            repository = repository,
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            numColumns = dimensions.cols,
            displaySource = displaySource,
            zoom = zoom,
            lat = lat,
            lon = lon,
            useGraph = useGraph,
            deferGraphActuals = deferGraphActuals,
            smoothedForecasts = smoothedForecasts,
            sourceMissingFromLoad = sourceMissingFromLoad,
        )

        // A `when` whose Empty branch returns, so every value below is a plain val: the conditionally
        // assigned `var …Ms = 0L` locals this replaced are the shape whose dex ART rejected in
        // CloudCoverViewHandler (plans/261119-split-oversized-android-suspend-functions.md).
        val loaded = when (graphLoadResult) {
            is TemperatureGraphHoursLoader.GraphLoadOutcome.Empty -> {
                // HOURLY_PAINT_TRACE: an empty hour list yields a blank graph state. The widget still
                // paints (so this should NOT leave the "Loading..." placeholder), but it explains a
                // graph that renders empty.
                effectiveAppLogDao.log(
                    "HOURLY_PAINT_TRACE",
                    "phase=resolve_EMPTY widget=$appWidgetId reason=${graphLoadResult.reason} " +
                        "hourlyCount=${hourlyForecasts.size} " +
                        "centerTime=$centerTime useGraph=$useGraph defer=$deferGraphActuals",
                    "WARN",
                )
                return buildFallbackResult(appWidgetId, displaySource, zoom, hourlyOffset, lat, lon, smoothedForecasts = smoothedForecasts)
            }
            is TemperatureGraphHoursLoader.GraphLoadOutcome.Loaded -> graphLoadResult
        }
        val graphHours = loaded.hours

        logHourlyDayExtrema(graphHours, appWidgetId, displaySource, zoom, hourlyOffset, effectiveAppLogDao)

        // 4. Current Temp Resolution
        val (currentTempResolution, resolveMs) = resolveCurrentTempPhase(
            now = now,
            displaySource = displaySource,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            deferCurrentTempResolution = deferCurrentTempResolution,
            currentTempSmoothedForecasts = currentTempSmoothedForecasts,
        )

        // 5–6. Header state and graph rendering
        val isNowLineVisible = graphHours.any { it.isCurrentHour }
        val hg = buildHeaderAndGraph(
            context, appWidgetId, stateManager, effectiveAppLogDao, displaySource, hourlyForecasts,
            currentTempHourlyForecasts, loaded, centerTime, now, zoom, dimensions, currentTempResolution,
            deltaFromYesterday, precipProbability, blendCentre, lastObservedTemp, observedAt, useGraph,
            onFetchDotResolved,
        )
        val headerState = hg.headerState
        val headerPrecipProbability = hg.headerPrecipProbability
        val graphState = hg.graphState
        val renderMs = hg.renderMs

        return ResolutionResult(
            state = TemperatureWidgetState(
                appWidgetId = appWidgetId,
                numRows = dimensions.rows,
                widthDp = dimensions.widthDp,
                header = headerState,
                graph = graphState,
                warning = null,
                displaySource = displaySource,
                zoom = zoom,
                hourlyOffset = hourlyOffset
            ),
            resolveMs = resolveMs,
            obsQueryMs = loaded.obsQueryMs,
            buildHourDataMs = loaded.buildHourDataMs,
            renderMs = renderMs,
            currentTempResolution = currentTempResolution,
            headerPrecipProbability = headerPrecipProbability,
            lat = lat,
            lon = lon,
            smoothedForecasts = smoothedForecasts,
            isNowLineVisible = isNowLineVisible,
            deltaFromYesterday = deltaFromYesterday,
        )
    }

    /** [resolve]'s blend centre (configured location over the data's), logging a divergence. */
    private suspend fun resolveBlendCentre(
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        stateManager: WidgetStateManager,
        effectiveAppLogDao: AppLogDao,
    ): BlendCentre.Centre {
        val dataDerivedLocation = hourlyForecasts.firstOrNull()?.let { it.locationLat to it.locationLon }
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val blendCentre = BlendCentre.resolve(configuredLocation, dataDerivedLocation)
        if (BlendCentre.divergesBeyondMergeTolerance(configuredLocation, dataDerivedLocation)) {
            effectiveAppLogDao.log(
                "BLEND_CENTRE_DIVERGENCE",
                "widget=$appWidgetId source=${displaySource.id} " +
                    "configured=${formatCoords(configuredLocation)} data=${formatCoords(dataDerivedLocation)} " +
                    "hourlyRows=${hourlyForecasts.size}",
                "WARN",
            )
        }
        return blendCentre
    }

    private class HeaderAndGraph(
        val headerState: TemperatureWidgetState.HeaderState,
        val headerPrecipProbability: Int?,
        val graphState: TemperatureWidgetState.GraphState,
        val renderMs: Long,
    )

    /**
     * Steps 5–6 of [resolve]: header state, then the graph bitmap. Split out (2026-11-19) so the loaded
     * observation fields are not live across [resolve]'s other suspension points
     * (plans/261119-split-oversized-android-suspend-functions.md).
     */
    private suspend fun buildHeaderAndGraph(
        context: Context,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        effectiveAppLogDao: AppLogDao,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        loaded: TemperatureGraphHoursLoader.GraphLoadOutcome.Loaded,
        centerTime: LocalDateTime,
        now: LocalDateTime,
        zoom: ZoomWindow,
        dimensions: WidgetDimensions,
        currentTempResolution: CurrentTemperatureResolution,
        deltaFromYesterday: Float?,
        precipProbability: Int?,
        blendCentre: BlendCentre.Centre,
        lastObservedTemp: Float?,
        observedAt: Long?,
        useGraph: Boolean,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
    ): HeaderAndGraph {
        val graphHours = loaded.hours
        val lat = blendCentre.lat
        val lon = blendCentre.lon
        // 5. Header State Resolution
        val useCelsius = stateManager.useCelsius()
        val (headerState, headerPrecipProbability) = TemperatureHeaderStateBuilder.buildHeaderState(
            currentTempResolution = currentTempResolution,
            deltaFromYesterday = deltaFromYesterday,
            centerTime = centerTime,
            now = now,
            displaySource = displaySource,
            dimensions = dimensions,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            lat = lat,
            lon = lon,
            precipProbability = precipProbability,
            useCelsius = useCelsius,
            rainNow = com.weatherwidget.widget.AndroidRainNow.level(context, lat, lon, displaySource, where = "temperature"),
            rainLookaheadHours = stateManager.getHeaderRainLookaheadHours(),
            appWidgetId = appWidgetId,
            allApiSourceLabels = stateManager.getVisibleSourcesOrder().map {
                HeaderContent.sourceLabel(HeaderContent.View.TEMPERATURE, it.id, now, centerTime, dimensions.widthDp)
            },
        )

        // 6. Graph Rendering
        val graph = if (useGraph) {
            renderGraphPhase(
                context, appWidgetId, stateManager, effectiveAppLogDao, displaySource, hourlyForecasts, graphHours,
                centerTime, now, zoom, dimensions, useCelsius, headerState, currentTempResolution, blendCentre, lat,
                lon, lastObservedTemp, observedAt, loaded.dominantStation, loaded.newestObservationMs, loaded.observationRowCount,
                loaded.obsPoolDiagnostics, loaded.obsWindowStartMs, loaded.obsWindowEndMs, onFetchDotResolved,
            )
        } else {
            GraphPhase.NONE
        }
        val renderMs = graph.renderMs
        val bitmap = graph.bitmap

        val graphState = TemperatureWidgetState.GraphState(
            useGraph = useGraph,
            bitmap = bitmap,
            hourData = graphHours,
            // Text mode is the 1-row layout only. A null bitmap with useGraph is a render failure and
            // must NOT route here: text_container is unpopulated on this path (blank body).
            showTextMode = !useGraph,
        )

        // HOURLY_PAINT_TRACE: final graph decision. A null bitmap with useGraph=true means renderGraph
        // threw (caught above); the binder then leaves the previous bitmap standing.
        if (useGraph && bitmap == null) {
            effectiveAppLogDao.log(
                "HOURLY_PAINT_TRACE",
                "phase=resolve_NULL_BITMAP widget=$appWidgetId hours=${graphHours.size} renderMs=$renderMs",
                "WARN",
            )
        }

        return HeaderAndGraph(headerState, headerPrecipProbability, graphState, renderMs)
    }

    /**
     * HOURLY_DAY_EXTREMA: per-day actual high/low the hourly graph derives from its rendered points,
     * for direct comparison against the daily bar's persisted daily_history (logged as
     * DAILY_HISTORY_BLEND). Diagnoses the "daily bar 72.4 vs hourly 72.9" divergence: same blend
     * function, but the two pipelines feed it different obs windows. Logs the actual-point count and
     * window span too so we can see whether a window/interpolation edge is moving the max.
     */
    private suspend fun logHourlyDayExtrema(
        graphHours: List<HourData>,
        appWidgetId: Int,
        displaySource: WeatherSource,
        zoom: ZoomWindow,
        hourlyOffset: Int,
        appLogDao: AppLogDao,
    ) {
        val zoneId = ZoneId.systemDefault()
        val actualPts = graphHours.filter { it.isActual }
        val byDay = actualPts.groupBy { it.dateTime.toLocalDate() }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val perDay = byDay.entries.sortedBy { it.key }.joinToString("; ") { (date, pts) ->
            val hi = pts.maxByOrNull { it.actualTemperature ?: it.temperature }!!
            val lo = pts.minByOrNull { it.actualTemperature ?: it.temperature }!!
            "$date hi=${"%.2f".format(hi.actualTemperature ?: hi.temperature)}@${hi.dateTime.format(fmt)} " +
                "lo=${"%.2f".format(lo.actualTemperature ?: lo.temperature)}@${lo.dateTime.format(fmt)} n=${pts.size}"
        }
        val span = if (graphHours.isEmpty()) "none" else "${graphHours.first().dateTime}..${graphHours.last().dateTime}"
        appLogDao.log(
            "HOURLY_DAY_EXTREMA",
            "widget=$appWidgetId source=${displaySource.id} zoom=$zoom offset=$hourlyOffset span=$span perDay=[$perDay]",
        )
    }

    /** Phase 4 of [resolve]: resolve the current display temperature (quick or full path). */

    /** The graph phase's result: the rendered bitmap (null when it failed) and how long it took. */
    private class GraphPhase(val bitmap: android.graphics.Bitmap?, val renderMs: Long) {
        companion object {
            val NONE = GraphPhase(null, 0L)
        }
    }

    /**
     * The graph branch of [resolve]: render the temperature graph and bind its touch zones. Split out
     * (2026-11-19) from the ~390-line suspend function, the shape whose dex ART rejected in
     * CloudCoverViewHandler (plans/261119-split-oversized-android-suspend-functions.md).
     */
    private suspend fun renderGraphPhase(
        context: Context,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        effectiveAppLogDao: AppLogDao,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        graphHours: List<HourData>,
        centerTime: LocalDateTime,
        now: LocalDateTime,
        zoom: ZoomWindow,
        dimensions: WidgetDimensions,
        useCelsius: Boolean,
        headerState: TemperatureWidgetState.HeaderState,
        currentTempResolution: CurrentTemperatureResolution,
        blendCentre: BlendCentre.Centre,
        lat: Double,
        lon: Double,
        lastObservedTemp: Float?,
        observedAt: Long?,
        dominantStation: DominantBlend?,
        newestObservationMs: Long?,
        observationRowCount: Int,
        obsPoolDiagnostics: ObservationPoolDiagnostics.Summary?,
        obsWindowStartMs: Long,
        obsWindowEndMs: Long,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
    ): GraphPhase {
        var renderMs = 0L
        var bitmap: android.graphics.Bitmap? = null
        val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)
        // The RemoteViews header row covers the bitmap's top strip; the context annotations
        // (dominant station, actuals source) keep below it
        // (plans/261106-actuals-source-label-clears-header.md).
        val headerBandBottomPx = TemperatureHeaderFit.headerBandBottomPx(
            context = context,
            resolved = TemperatureHeaderFit.resolve(
                context = context,
                header = headerState,
                widthDp = dimensions.widthDp,
                numRows = dimensions.rows,
                zoom = zoom,
                centerTime = centerTime,
                today = now.toLocalDate(),
            ),
            header = headerState,
            bitmapScale = bitmapDims.bitmapScale,
        )

        val dominantContribution = dominantStation?.contribution
        val dominantStationLabel =
            DominantStationLabel.formatLabelText(
                contribution = dominantContribution,
                useCelsius = useCelsius,
            )
        val dominantTextReason =
            when {
                dominantContribution == null -> "no_contribution"
                dominantContribution.isSynthetic -> "synthetic"
                dominantStationLabel == null -> "format_null"
                else -> "text_ok"
            }
        // Mirrors the desktop DominantStationDiag. Upstream gate only: the placement-side
        // span_too_wide/no_empty_band/drawn gate lives in TemperatureGraphAnnotationRenderer.
        Log.v(
            TAG,
            "DominantStationDiag: source=${displaySource.id} reason=$dominantTextReason " +
                "contribution=${dominantContribution?.let { "${it.stationId} raw=${it.rawTemp} synthetic=${it.isSynthetic}" } ?: "null"} " +
                "text=${dominantStationLabel?.fullText ?: "null"}",
        )
        // Persisted counterpart of the line above. The VERBOSE one is logcat-only (see the
        // AppLogDao level gate), so a report of "the label showed a stale temperature an hour
        // ago" had no evidence left to check. What makes this answerable is the pairing of
        // readingAgeMin (how old the NAMED station's raw reading is) with newestObsAgeMin (how
        // old the freshest row the render actually loaded is): both large means the fetch
        // pipeline stalled, readingAge large while newestObsAge is small means the blend named
        // a lagging station while fresher data was on hand.
        //
        // Those two fields could not tell either case apart from a THIRD: the read was centred on
        // a coordinate the device had left, so both ages were large while fresh rows sat in the
        // database, unreachable behind the merge box (2026-08-28). locSource/obsCentre name it.
        val nowEpochMs = com.weatherwidget.shared.util.WeatherTimeUtils.liveEpochMs(now)
        fun ageMin(ms: Long?): String =
            ms?.let { ((nowEpochMs - it).coerceAtLeast(0L) / 60_000L).toString() } ?: "na"
        effectiveAppLogDao.log(
            "DOMINANT_STATION",
            "widget=$appWidgetId source=${displaySource.id} reason=$dominantTextReason " +
                "station=${dominantContribution?.stationId ?: "null"} " +
                "rawTemp=${dominantContribution?.rawTemp} " +
                "weightShare=${dominantContribution?.weightShare} " +
                "readingAgeMin=${ageMin(dominantContribution?.lastReadingMs)} " +
                "newestObsAgeMin=${ageMin(newestObservationMs)} obsRows=$observationRowCount " +
                "locSource=${blendCentre.source.name.lowercase(Locale.US)} " +
                "obsCentre=${formatCoords(lat to lon)} " +
                "text=${dominantStationLabel?.fullText ?: "null"}",
            "DEBUG",
        )

        // OBS_POOL_DIAG: the companion line that makes DOMINANT_STATION's readingAgeMin
        // actionable. `readingAgeMin` large says the label is old; it does not say WHY, and the
        // three candidate causes need different fixes:
        //
        //   verdict=merge_dropped_fresher  the coarse box held newer rows than the merge kept —
        //                                  a device-site fragment outside MERGE_TOLERANCE_DEG.
        //                                  fresherSites names it, with coordinates ready to
        //                                  paste into a `locationLat=` query.
        //   verdict=box_had_nothing_newer  the read returned everything in the box. The location
        //                                  plumbing is exonerated; the fetch (or `win`, the query
        //                                  window) is the subject.
        //
        // Deliberately conditional (see ObservationPoolDiagnostics.shouldLog): this runs on every
        // temperature render, so an unconditional line would add thousands of app_logs rows a day
        // to say "the pool is fresh", which is the normal state and the one nobody queries back.
        obsPoolDiagnostics?.let { diag ->
            if (ObservationPoolDiagnostics.shouldLog(diag, nowEpochMs)) {
                effectiveAppLogDao.log(
                    "OBS_POOL_DIAG",
                    "widget=$appWidgetId source=${displaySource.id} " +
                        ObservationPoolDiagnostics.format(
                            summary = diag,
                            nowMs = nowEpochMs,
                            startTs = obsWindowStartMs,
                            endTs = obsWindowEndMs,
                            lat = lat,
                            lon = lon,
                        ),
                    "DEBUG",
                )
            }
        }

        // "Actual temperature data from X" — for sources using an alternative provider
        // (e.g. Silurian borrowing METAR, or Open-Meteo configured to METAR/NWS/Synoptic).
        // Only shown when the visible window actually contains observed data; future-only
        // views (no actuals in graphHours) suppress the annotation.
        val actualsProviderId = ActualsProviderResolver.providerIdFor(displaySource)
        val actualsSourceLabel = if (ActualsProviderResolver.showsBorrowedLabel(displaySource, drawsActuals = graphHours.any { it.isActual })) {
            val provider = WeatherSource.fromId(actualsProviderId)
            temperatureActualsSourceLabel(context, provider.displayName)
        } else {
            null
        }
        Log.v(
            TAG,
            "ActualsSourceDiag: source=${displaySource.id} provider=$actualsProviderId " +
                "text=${actualsSourceLabel?.fullText ?: "null"}",
        )

        val renderStartMs = System.currentTimeMillis()
        bitmap = renderOrNull(
            onFailure = { e ->
                Log.e(TAG, "renderGraph failed", e)
                // Log.e never reaches app_logs, so a bug report of a blank body carried no
                // trace of the throw. The binder keeps the previous bitmap on screen for this.
                effectiveAppLogDao.logException(
                    "HOURLY_RENDER_EXCEPTION",
                    "widget=$appWidgetId source=${displaySource.id} hours=${graphHours.size} " +
                        "size=${bitmapDims.widthPx}x${bitmapDims.heightPx}",
                    e,
                )
            },
        ) {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = graphHours,
                widthPx = bitmapDims.widthPx,
                heightPx = bitmapDims.heightPx,
                currentTime = now,
                bitmapScale = bitmapDims.bitmapScale,
                appliedDelta = currentTempResolution.appliedDelta,
                observedAt = observedAt,
                lastObservedTemp = lastObservedTemp,
                numColumns = dimensions.cols,
                job = coroutineContext[Job],
                onFetchDotResolved = onFetchDotResolved,
                showErrorWatermark = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).show,
                errorSourceLabel = displaySource.displayName,
                errorCode = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).errorCode,
                errorFailureTimeMs = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).failureTimeMs,
                errorRetryAtMs = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).retryAtMs,
                errorBannerSinceMs = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).bannerSinceMs,
                useCelsius = useCelsius,
                dominantStationLabel = dominantStationLabel,
                actualsSourceLabel = actualsSourceLabel,
                headerBandBottomPx = headerBandBottomPx,
            )
        }
        bitmap?.let { drawn ->
            com.weatherwidget.widget.HourlyEmptyNoticeRenderer.message(
                context, stateManager, appWidgetId, hourlyForecasts, displaySource.id,
                com.weatherwidget.shared.util.WeatherTimeUtils.hourWindow(centerTime, zoom.backHours, zoom.forwardHours),
                now, lat, lon,
            )?.let { notice ->
                com.weatherwidget.widget.HourlyEmptyNoticeRenderer.draw(
                    drawn, notice, context.resources.displayMetrics.density * bitmapDims.bitmapScale,
                )
            }
        }
        renderMs = System.currentTimeMillis() - renderStartMs
        return GraphPhase(bitmap, renderMs)
    }

    private suspend fun resolveCurrentTempPhase(
        now: LocalDateTime,
        displaySource: WeatherSource,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        deferCurrentTempResolution: Boolean,
        currentTempSmoothedForecasts: Map<Long, Float>,
    ): Pair<CurrentTemperatureResolution, Long> {
        val resolveStartMs = System.currentTimeMillis()
        val currentTempResolution = if (deferCurrentTempResolution) {
            val quick = CurrentTemperatureResolver.resolveQuick(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts.map { it.toHourlyForecast() },
                lastObservedTemp = lastObservedTemp,
                smoothedForecasts = currentTempSmoothedForecasts,
            )
            CurrentTemperatureResolution(
                displayTemp = quick.displayTemp,
                estimatedTemp = quick.estimatedTemp,
                observedTemp = quick.observedTemp,
                isStaleEstimate = quick.isStaleEstimate,
                appliedDelta = null,
            )
        } else {
            CurrentTempResolutionHelper.header(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts,
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
            )
        }
        val resolveMs = System.currentTimeMillis() - resolveStartMs
        return currentTempResolution to resolveMs
    }

    /**
     * Whether this render skips the graph's observation read and paints the forecast curve alone.
     *
     * Two callers, one mechanism: the startup fast path (which follows itself up with a deferred
     * broadcast) and an interaction's phase-1 paint (whose caller runs phase 2 inline). Text mode
     * has no graph and no actual overlay to defer, so [useGraph] is the real precondition — not who
     * asked.
     */
    @androidx.annotation.VisibleForTesting
    internal fun shouldDeferGraphActuals(
        startupToken: String?,
        deferGraphActualsRequested: Boolean,
        useGraph: Boolean,
    ): Boolean = (startupToken != null || deferGraphActualsRequested) && useGraph

    /**
     * 5 dp, matching the `configuredLoc`/`dataLoc` pair already logged by `TemperatureViewHandler`:
     * stored coordinates are quantized to 3 dp, so 5 dp shows whether a value is a raw configured
     * coordinate or a row's quantized one — which is what separated the two sites in the
     * 2026-08-28 report.
     */
    private fun formatCoords(location: Pair<Double, Double>?): String =
        location?.let { String.format(Locale.US, "%.5f,%.5f", it.first, it.second) } ?: "none"

    private fun buildFallbackResult(
        appWidgetId: Int,
        displaySource: WeatherSource,
        zoom: ZoomWindow,
        hourlyOffset: Int,
        lat: Double,
        lon: Double,
        smoothedForecasts: Map<Long, Float> = emptyMap(),
        warning: ApiSourceWarningHelper.SourceWarning? = null,
    ): ResolutionResult = ResolutionResult(
        state = TemperatureWidgetState(
            appWidgetId = appWidgetId,
            numRows = 1, // Fallback
            widthDp = 300, // Fallback
            header = emptyHeaderState(),
            graph = emptyGraphState(),
            warning = warning?.let { TemperatureWidgetState.SourceWarningState(it) },
            displaySource = displaySource,
            zoom = zoom,
            hourlyOffset = hourlyOffset,
        ),
        resolveMs = 0L,
        obsQueryMs = 0L,
        buildHourDataMs = 0L,
        renderMs = 0L,
        currentTempResolution = emptyResolution(),
        headerPrecipProbability = null,
        lat = lat,
        lon = lon,
        smoothedForecasts = smoothedForecasts,
        isNowLineVisible = false,
        deltaFromYesterday = null,
    )

    private fun emptyHeaderState() = TemperatureWidgetState.HeaderState(
        sourceIndicator = "",
        iconRes = 0,
        currentTemp = null,
        currentTempSizeDp = 0f,
        deltaText = null,
        deltaColor = 0,
        precipProbability = null,
        precipTextSizeDp = 0f,
        isPrecipVisible = false,
        isCurrentTempVisible = false,
        isDeltaVisible = false,
        isStaleEstimate = false,
    )

    private fun emptyGraphState() = TemperatureWidgetState.GraphState(false, null, emptyList(), false)

    private fun emptyResolution() = CurrentTemperatureResolution(null, null, null, false, null)

}

package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.ObservationPoolDiagnostics
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.actuals.DominantBlend
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.HeaderFormatter
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunInfo
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureDeltaState
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.FetchDotDebug
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetQueryWindows
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import com.weatherwidget.widget.ZoomWindow
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal data class MissingForecastHours(
    val missingCount: Int,
    val noSelectedForecastCount: Int,
    val wrongSourceCount: Int,
    val spans: List<Pair<LocalDateTime, LocalDateTime>>,
) {
    fun diagnosticText(): String {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val spanText = spans.joinToString(",") { (start, endExclusive) ->
            "${start.format(formatter)}..${endExclusive.format(formatter)}"
        }
        return "missing=$missingCount noSelected=$noSelectedForecastCount wrongSource=$wrongSourceCount spans=[$spanText]"
    }
}

internal object TemperatureStateResolver {
    private const val TAG = "TemperatureStateResolver"
    private const val CELL_HEIGHT_DP = 90
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private const val GRAPH_MIN_ROWS = 1.4f
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 12

    /** Observation lookback for the text-mode (graph-less) header yesterday-delta query. */
    private const val TEXT_MODE_DELTA_LOOKBACK_HOURS = 30L

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
        return DominantStationLabel.plainLabelText(
            context.getString(R.string.actual_temperature_data_from, name),
        )
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
        val dataDerivedLocation = hourlyForecasts.firstOrNull()?.let { it.locationLat to it.locationLon }
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val blendCentre = BlendCentre.resolve(configuredLocation, dataDerivedLocation)
        val lat = blendCentre.lat
        val lon = blendCentre.lon
        if (BlendCentre.divergesBeyondMergeTolerance(configuredLocation, dataDerivedLocation)) {
            effectiveAppLogDao.log(
                "BLEND_CENTRE_DIVERGENCE",
                "widget=$appWidgetId source=${displaySource.id} " +
                    "configured=${formatCoords(configuredLocation)} data=${formatCoords(dataDerivedLocation)} " +
                    "hourlyRows=${hourlyForecasts.size}",
                "WARN",
            )
        }
        val sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon)

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
            observedAt = observedAt,
            lastObservedTemp = lastObservedTemp,
            sourceMissingFromLoad = sourceMissingFromLoad,
        )

        val graphHours: List<HourData>
        val obsQueryMs: Long
        val buildHourDataMs: Long
        var deltaFromYesterday: Float? = null
        var dominantStation: DominantBlend? = null
        var newestObservationMs: Long? = null
        var observationRowCount = 0
        var obsPoolDiagnostics: ObservationPoolDiagnostics.Summary? = null
        var obsWindowStartMs = 0L
        var obsWindowEndMs = 0L
        when (graphLoadResult) {
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
            is TemperatureGraphHoursLoader.GraphLoadOutcome.Loaded -> {
                graphHours = graphLoadResult.hours
                obsQueryMs = graphLoadResult.obsQueryMs
                buildHourDataMs = graphLoadResult.buildHourDataMs
                deltaFromYesterday = graphLoadResult.deltaFromYesterday
                dominantStation = graphLoadResult.dominantStation
                newestObservationMs = graphLoadResult.newestObservationMs
                observationRowCount = graphLoadResult.observationRowCount
                obsPoolDiagnostics = graphLoadResult.obsPoolDiagnostics
                obsWindowStartMs = graphLoadResult.obsWindowStartMs
                obsWindowEndMs = graphLoadResult.obsWindowEndMs
            }
        }

        logHourlyDayExtrema(graphHours, appWidgetId, displaySource, zoom, hourlyOffset, effectiveAppLogDao)

        // 4. Current Temp Resolution
        val (currentTempResolution, resolveMs) = resolveCurrentTempPhase(
            now = now,
            displaySource = displaySource,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            deferCurrentTempResolution = deferCurrentTempResolution,
            appWidgetId = appWidgetId,
            stateManager = stateManager,
            lat = lat,
            lon = lon,
            currentTempSmoothedForecasts = currentTempSmoothedForecasts,
        )

        // 5. Header State Resolution
        val isNowLineVisible = graphHours.any { it.isCurrentHour }
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
            sunInfo = sunInfo,
            precipProbability = precipProbability,
            useCelsius = useCelsius,
        )

        // 6. Graph Rendering
        var renderMs = 0L
        var bitmap: android.graphics.Bitmap? = null
        if (useGraph) {
            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

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
            val nowEpochMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
            val hasActualsInWindow = graphHours.any { it.isActual }
            val actualsSourceLabel = if (actualsProviderId != displaySource.id && hasActualsInWindow) {
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
            bitmap = try {
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
                    showErrorWatermark = stateManager.isSourceErrored(displaySource),
                    errorSourceLabel = displaySource.displayName,
                    errorCode = stateManager.getSourceLastErrorCode(displaySource),
                    errorFailureTimeMs = stateManager.getSourceLastFailureTime(displaySource),
                    useCelsius = useCelsius,
                    dominantStationLabel = dominantStationLabel,
                    actualsSourceLabel = actualsSourceLabel,
                )
            } catch (e: Exception) {
                Log.e(TAG, "renderGraph failed", e)
                null
            }
            renderMs = System.currentTimeMillis() - renderStartMs
        }

        val graphState = TemperatureWidgetState.GraphState(
            useGraph = useGraph,
            bitmap = bitmap,
            hourData = graphHours,
            showTextMode = !useGraph || bitmap == null
        )

        // HOURLY_PAINT_TRACE: final graph decision. A null bitmap with useGraph=true means renderGraph
        // threw (caught above) and we fall back to text mode — another path that can look "blank".
        if (useGraph && bitmap == null) {
            effectiveAppLogDao.log(
                "HOURLY_PAINT_TRACE",
                "phase=resolve_NULL_BITMAP widget=$appWidgetId hours=${graphHours.size} renderMs=$renderMs",
                "WARN",
            )
        }

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
            obsQueryMs = obsQueryMs,
            buildHourDataMs = buildHourDataMs,
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
    private suspend fun resolveCurrentTempPhase(
        now: LocalDateTime,
        displaySource: WeatherSource,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        deferCurrentTempResolution: Boolean,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        lat: Double,
        lon: Double,
        currentTempSmoothedForecasts: Map<Long, Float>,
    ): Pair<CurrentTemperatureResolution, Long> {
        val storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource)
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
                updatedDeltaState = null,
                shouldClearStoredDelta = false,
            )
        } else {
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts.map { it.toHourlyForecast() },
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                storedDeltaState = storedDeltaState,
                currentLat = lat,
                currentLon = lon,
                smoothedForecasts = currentTempSmoothedForecasts,
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

    private fun computeDeltaFromYesterday(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        observedAt: Long?,
        lastObservedTemp: Float?,
        personalStationWeight: Double,
    ): Float? =
        YesterdayDeltaCalculator.computeDelta(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            displaySourceId = displaySource.id,
            userLat = lat,
            userLon = lon,
            observedAtMs = observedAt,
            currentObservedTemp = lastObservedTemp,
            personalStationWeight = personalStationWeight,
            zoneId = ZoneId.systemDefault(),
        )

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

    private fun emptyResolution() = CurrentTemperatureResolution(null, null, null, false, null, null, false)

}

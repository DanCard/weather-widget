package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.HeaderFormatter
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureDeltaState
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.shared.graph.HeaderDeltaGate
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.FetchDotDebug
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

internal object TemperatureStateResolver {
    private const val TAG = "TemperatureStateResolver"
    private const val CELL_HEIGHT_DP = 90
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private const val GRAPH_MIN_ROWS = 1.4f
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 12

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
        val isDeltaWindowVisible: Boolean,
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
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        appLogDao: AppLogDao? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): ResolutionResult {
        val effectiveAppLogDao = appLogDao ?: WeatherDatabase.getDatabase(context).appLogDao()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val sunInfo = SunPositionUtils.getSunInfo(now, lat, lon)

        val zoom = stateManager.getZoomLevel(appWidgetId)
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
            return buildWarningResult(appWidgetId, displaySource, zoom, hourlyOffset, warning, lat, lon)
        }

        // 2. Data Pre-processing
        val smoothedForecasts = computeSmoothedForecasts(hourlyForecasts, displaySource)
        // Smoothed forecasts for current temp resolution use the NOW-centered window, not the
        // scrolled graph window, so that interpolation finds the correct current-hour data.
        val currentTempSmoothedForecasts = computeSmoothedForecasts(currentTempHourlyForecasts, displaySource)
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= GRAPH_MIN_ROWS
        val deferStartupGraphActuals = startupToken != null && useGraph

        // 3. Load Graph Hours
        val graphLoadResult = loadGraphHours(
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
            deferStartupGraphActuals = deferStartupGraphActuals,
            smoothedForecasts = smoothedForecasts,
            observedAt = observedAt,
            lastObservedTemp = lastObservedTemp,
        )

        val graphHours: List<HourData>
        val obsQueryMs: Long
        val buildHourDataMs: Long
        var deltaFromYesterday: Float? = null
        when (graphLoadResult) {
            is GraphLoadOutcome.Empty -> {
                // HOURLY_PAINT_TRACE: an empty hour list yields a blank graph state. The widget still
                // paints (so this should NOT leave the "Loading..." placeholder), but it explains a
                // graph that renders empty.
                effectiveAppLogDao.log(
                    "HOURLY_PAINT_TRACE",
                    "phase=resolve_EMPTY widget=$appWidgetId reason=${graphLoadResult.reason} " +
                        "hourlyCount=${hourlyForecasts.size} " +
                        "centerTime=$centerTime useGraph=$useGraph defer=$deferStartupGraphActuals",
                    "WARN",
                )
                return buildEmptyGraphResult(appWidgetId, displaySource, zoom, hourlyOffset, lat, lon, smoothedForecasts)
            }
            is GraphLoadOutcome.Loaded -> {
                graphHours = graphLoadResult.hours
                obsQueryMs = graphLoadResult.obsQueryMs
                buildHourDataMs = graphLoadResult.buildHourDataMs
                deltaFromYesterday = graphLoadResult.deltaFromYesterday
            }
        }

        // HOURLY_DAY_EXTREMA: per-day actual high/low the hourly graph derives from its rendered points,
        // for direct comparison against the daily bar's persisted daily_history (logged as
        // DAILY_HISTORY_BLEND). Diagnoses the "daily bar 72.4 vs hourly 72.9" divergence: same blend
        // function, but the two pipelines feed it different obs windows. Logs the actual-point count and
        // window span too so we can see whether a window/interpolation edge is moving the max.
        run {
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
            effectiveAppLogDao.log(
                "HOURLY_DAY_EXTREMA",
                "widget=$appWidgetId source=${displaySource.id} zoom=$zoom offset=$hourlyOffset span=$span perDay=[$perDay]",
            )
        }

        // 4. Current Temp Resolution
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

        // 5. Header State Resolution
        val currentTemp = currentTempResolution.displayTemp
        val isNowLineVisible = graphHours.any { it.isCurrentHour }
        val delta = currentTempResolution.appliedDelta
        // Delta stays visible on future scroll (no "now" in window yet reached) and hides only once
        // the window has scrolled entirely into the past, matching the ghost line's own visibility
        // (see GhostLineGate) rather than the stricter "now must be on screen" isNowLineVisible check.
        val graphWindowEndTime = graphHours.lastOrNull()?.dateTime
        val isDeltaWindowVisible = graphWindowEndTime != null &&
            HeaderDeltaGate.isVisible(graphWindowEndTime, now, delta, DELTA_VISIBILITY_THRESHOLD)
        val deltaVisible = currentTemp != null && isDeltaWindowVisible

        val sourceIndicator = HeaderFormatter.formatSourceIndicator(
            centerTime = centerTime,
            now = now,
            sourceName = displaySource.shortDisplayName,
            widthDp = dimensions.widthDp
        )

        val currentHourForecast = WeatherTimeUtils.getCurrentHourForecast(currentTempHourlyForecasts, displaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = sunInfo.isNight,
            cloudCover = currentHourForecast?.cloudCover,
            precipProbability = currentHourForecast?.precipProbability,
            isTwilight = sunInfo.phase == SunPhase.TWILIGHT,
            isSunBoundary = sunInfo.isSunBoundary,
        )

        val headerPrecipProbability = HeaderPrecipCalculator.getNext8HourPrecipProbability(
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            fallbackDailyProbability = precipProbability,
            referenceTime = centerTime,
        )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)

        val fetchFailureMessage = FetchFailureIndicatorHelper.resolveFetchError(
            displaySourceId = displaySource.id,
            appLogDao = effectiveAppLogDao,
            lastGoodObsMs = observedAt,
        )

        val useCelsius = stateManager.useCelsius()
        val headerState = TemperatureWidgetState.HeaderState(
            sourceIndicator = sourceIndicator,
            iconRes = iconRes,
            currentTemp = if (currentTemp != null) {
                val formatted = CurrentTemperatureResolver.formatDisplayTemperature(
                    currentTemp,
                    dimensions.cols,
                    currentTempResolution.isStaleEstimate,
                    useCelsius = useCelsius
                )
                formatted
            } else null,
            currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = if (deltaVisible) {
                val displayDelta = delta?.let { if (useCelsius) it / 1.8f else it }
                if (displayDelta != null) String.format("%+.1f", displayDelta) else null
            } else null,
            deltaColor = Color.parseColor(DELTA_COLOR_HEX),
            precipProbability = if (isPrecipVisible) "$headerPrecipProbability%" else null,
            precipTextSizeDp = if (isPrecipVisible) HeaderPrecipCalculator.getPrecipTextSize(checkNotNull(headerPrecipProbability)) else 0f,
            isPrecipVisible = isPrecipVisible,
            isCurrentTempVisible = currentTemp != null,
            isDeltaVisible = deltaVisible,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
            fetchFailureMessage = fetchFailureMessage
        )

        // 6. Graph Rendering
        var renderMs = 0L
        var bitmap: android.graphics.Bitmap? = null
        if (useGraph) {
            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

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
                    deltaFromYesterday = deltaFromYesterday,
                    numColumns = dimensions.cols,
                    job = coroutineContext[Job],
                    onFetchDotResolved = onFetchDotResolved,
                    showErrorWatermark = stateManager.isSourceErrored(displaySource),
                    errorSourceLabel = displaySource.displayName,
                    errorCode = stateManager.getSourceLastErrorCode(displaySource),
                    errorFailureTimeMs = stateManager.getSourceLastFailureTime(displaySource),
                    useCelsius = useCelsius,
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
            isDeltaWindowVisible = isDeltaWindowVisible,
        )
    }

    private sealed class GraphLoadOutcome {
        data class Empty(val reason: String) : GraphLoadOutcome()
        data class Loaded(
            val hours: List<HourData>,
            val obsQueryMs: Long,
            val buildHourDataMs: Long,
            val deltaFromYesterday: Float? = null,
        ) : GraphLoadOutcome()
    }

    private suspend fun loadGraphHours(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        repository: WeatherRepository?,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: ZoomLevel,
        lat: Double,
        lon: Double,
        useGraph: Boolean,
        deferStartupGraphActuals: Boolean,
        smoothedForecasts: Map<Long, Float>,
        observedAt: Long?,
        lastObservedTemp: Float?,
    ): GraphLoadOutcome {
        if (!useGraph) return GraphLoadOutcome.Loaded(emptyList(), 0L, 0L)

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated

        var obsQueryMs = 0L
        val observations = if (deferStartupGraphActuals) {
            emptyList()
        } else {
            val minEpoch = alignedCenter.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val maxEpoch = alignedCenter.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val obsStartMs = System.currentTimeMillis()
            val loaded = repository?.getObservationsInRange(minEpoch, maxEpoch, lat, lon) ?: emptyList()
            obsQueryMs = System.currentTimeMillis() - obsStartMs

            maybeEnqueueHourlyObservationBackfill(
                context = context,
                database = database,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                displaySource = displaySource,
                graphStart = alignedCenter.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS),
                graphEnd = alignedCenter.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS),
                observations = loaded,
                repositoryPresent = repository != null,
            )
            loaded
        }

        val buildHourDataStartMs = System.currentTimeMillis()
        val blendDebugCollector = BlendDebugCollector()
        val hourDataResult = buildHourDataResult(
            hourlyForecasts,
            centerTime,
            numColumns,
            displaySource,
            zoom,
            observations,
            onBlendDebug = { lineProvider -> blendDebugCollector.recordDetailed(lineProvider) },
            smoothedForecasts = smoothedForecasts,
            personalStationWeight = stateManager.getPersonalStationWeight(),
        )
        val hourData = hourDataResult.hours
        val buildHourDataMs = System.currentTimeMillis() - buildHourDataStartMs
        val actualCount = hourData.count { it.isActual }

        val zoneId = ZoneId.systemDefault()
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        var missingCount = 0
        var current = startHour
        val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)
        while (current.isBefore(endHour)) {
            val hourMs = current.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]
            if (forecast == null || forecast.source != displaySource.id) {
                missingCount++
            }
            current = current.plusHours(1)
        }

        if (missingCount > 0) {
            val cooldownMs = 15 * 60 * 1000L
            if (stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, "hourly_gaps", cooldownMs)) {
                stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, "hourly_gaps")
                database.appLogDao().log(
                    "TEMP_GAPS_REFRESH",
                    "widget=$appWidgetId source=${displaySource.id} missing=$missingCount, requesting immediate API update",
                    "INFO"
                )
                WeatherWidgetProvider.triggerImmediateUpdate(
                    context = context,
                    forceRefresh = true,
                    reason = "hourly_gaps"
                )
            }
        }

        if (hourData.isEmpty() && hourlyForecasts.isNotEmpty()) {
            return GraphLoadOutcome.Empty("buildHourDataResult_empty")
        }

        if (!deferStartupGraphActuals) {
            val stationIds = observations
                .filter { matchesObservationSource(it, displaySource) }
                .map { it.stationId }.toSet()
            database.appLogDao().log(
                "IDW_BLEND",
                "source=${displaySource.id} stations=${stationIds.size} [${stationIds.joinToString(",")}] blendedPoints=$actualCount",
            )
            // Permanent, logcat-only traces (Log.v never persists to app_logs) of the blended actual
            // series, tagged with the visual window so consecutive renders of the SAME window can be
            // compared. This is how an oscillating actual line gets caught: identical inputs rendering
            // two different curves show up here as a changed digest / differing points.
            // These graph bugs recur — keep these; do not mark "remove after".
            //
            // The DIGEST comes first and is the authoritative signal: a render emits ~1000 detail lines
            // in one burst, which overruns the 5 MiB logcat ring (the device rejects -G above that), so
            // the per-point lines below are lossy and must never be diffed positionally — a truncated
            // capture looks exactly like a divergence. One digest line per render cannot be split.
            val blendLines = blendDebugCollector.allLines()
            // Row COUNT is not row CONTENT: observations are inserted onConflict=REPLACE against PK
            // (stationId, timestamp), so a re-fetch can overwrite temperatures while sourceRows stays
            // put. These two hashes are what separate the possible causes of a curve that moves on an
            // unchanged window:
            //   inputContentHash (sorted -> order-independent): did the underlying data actually change?
            //   inputOrderHash   (raw query order):             did only the row ORDER change?
            // contentHash same + visibleHash changed => the blend is non-deterministic for fixed input.
            val inputContentHash = observations
                .map { "${it.stationId}@${it.timestamp}=${it.temperature}/${it.qcFailed}" }
                .sorted()
                .joinToString(",").hashCode()
            val inputOrderHash = observations
                .joinToString(",") { "${it.stationId}@${it.timestamp}" }.hashCode()
            // visibleHash covers ONLY the hours actually drawn, so it is the one to compare across
            // renders: two renders of the same window must produce the same visibleHash, and a change
            // means the drawn curve moved. contextHash covers the whole 72h blend context and changes
            // whenever ANY observation arrives anywhere in it — including hours far outside the view —
            // so it is diagnostic colour only and must NOT be used to judge window stability.
            val visibleHash = hourDataResult.hours
                .joinToString(",") { "${it.dateTime}=${it.actualTemperature}/${it.temperature}" }
                .hashCode()
            android.util.Log.v(
                "TEMP_ACTUALS_DIGEST",
                "center=$centerTime aligned=$alignedCenter zoom=$zoom source=${displaySource.id} " +
                    "rows=${observations.size} inputContentHash=$inputContentHash inputOrderHash=$inputOrderHash " +
                    "visibleHours=${hourDataResult.hours.size} visibleHash=$visibleHash " +
                    "contextPoints=${blendLines.size} contextHash=${blendLines.joinToString("\n").hashCode()}",
            )
            blendLines.forEach { line ->
                android.util.Log.v("TEMP_ACTUALS_DUMP", "center=$centerTime zoom=$zoom $line")
            }
            // Every persisted line is stamped with the widget and the render it came from. These
            // lines are THROTTLED samples (typically 8 of ~978 survive per render), so without the
            // stamp two renders' samples are indistinguishable in app_logs — and the same timestamp
            // appearing with two different blended values then reads as blend non-determinism when
            // it is really just two different renders, or two widgets at different centres/zooms.
            // That ambiguity cost a full reconstruction once already; see
            // notes/260719-blend-window-independence-audit.md.
            val blendDebugPrefix =
                "widget=$appWidgetId source=${displaySource.id} aligned=$alignedCenter zoom=$zoom"
            blendDebugCollector.emittedLines()
                .take(MAX_PERSISTED_BLEND_DEBUG_LINES)
                .forEach { line ->
                    // VERBOSE: per-paint blend diagnostics — logcat-only, kept out of app_logs
                    // (this tag fired ~14k rows/3 days on a 5-widget device and is never queried back).
                    database.appLogDao().log("TEMP_ACTUALS_DEBUG", "$blendDebugPrefix $line", "VERBOSE")
                }
            database.appLogDao().log(
                "TEMP_ACTUALS_DEBUG",
                "$blendDebugPrefix summary " + blendDebugCollector.buildSummary(
                    stationCount = stationIds.size,
                    blendedPointCount = actualCount,
                    blendDurationMs = buildHourDataMs,
                ),
                "VERBOSE",
            )
            hourDataResult.blendStats?.let { stats ->
                database.appLogDao().log(
                    "TEMP_ACTUALS_PERF",
                    "widget=$appWidgetId source=${displaySource.id} buildMs=$buildHourDataMs ${stats.summary()}",
                )
            }
        }

        // "+0.4 from yesterday" delta: current fetch-dot temp minus the blended actual at the same clock
        // time 24h earlier. Computed here (not in the renderer) because the raw 72h observation list lives
        // here — when zoomed in, the renderer's windowed hours may not even reach back a day. Null (label
        // hidden) when the fetch dot or a yesterday observation is missing, e.g. navigated into the past.
        val deltaFromYesterday = YesterdayDeltaCalculator.computeDelta(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            displaySourceId = displaySource.id,
            userLat = lat,
            userLon = lon,
            observedAtMs = observedAt,
            currentObservedTemp = lastObservedTemp,
            personalStationWeight = stateManager.getPersonalStationWeight(),
            zoneId = ZoneId.systemDefault(),
        )

        return GraphLoadOutcome.Loaded(
            hours = hourData,
            obsQueryMs = obsQueryMs,
            buildHourDataMs = buildHourDataMs,
            deltaFromYesterday = deltaFromYesterday,
        )
    }

    private fun buildWarningResult(
        appWidgetId: Int,
        displaySource: WeatherSource,
        zoom: ZoomLevel,
        hourlyOffset: Int,
        warning: ApiSourceWarningHelper.SourceWarning,
        lat: Double,
        lon: Double
    ): ResolutionResult {
        return ResolutionResult(
            state = TemperatureWidgetState(
                appWidgetId = appWidgetId,
                numRows = 1, // Fallback for warning
                widthDp = 300, // Fallback
                header = emptyHeaderState(),
                graph = emptyGraphState(),
                warning = TemperatureWidgetState.SourceWarningState(warning),
                displaySource = displaySource,
                zoom = zoom,
                hourlyOffset = hourlyOffset
            ),
            resolveMs = 0L,
            obsQueryMs = 0L,
            buildHourDataMs = 0L,
            renderMs = 0L,
            currentTempResolution = emptyResolution(),
            headerPrecipProbability = null,
            lat = lat,
            lon = lon,
            smoothedForecasts = emptyMap(),
            isNowLineVisible = false,
            isDeltaWindowVisible = false,
        )
    }

    private fun buildEmptyGraphResult(
        appWidgetId: Int,
        displaySource: WeatherSource,
        zoom: ZoomLevel,
        hourlyOffset: Int,
        lat: Double,
        lon: Double,
        smoothedForecasts: Map<Long, Float>
    ): ResolutionResult {
        return ResolutionResult(
            state = TemperatureWidgetState(
                appWidgetId = appWidgetId,
                numRows = 1, // Fallback
                widthDp = 300, // Fallback
                header = emptyHeaderState(),
                graph = emptyGraphState(),
                warning = null,
                displaySource = displaySource,
                zoom = zoom,
                hourlyOffset = hourlyOffset
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
            isDeltaWindowVisible = false,
        )
    }

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
        fetchFailureMessage = null
    )

    private fun emptyGraphState() = TemperatureWidgetState.GraphState(false, null, emptyList(), false)

    private fun emptyResolution() = CurrentTemperatureResolution(null, null, null, false, null, null, false)

}

package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.toHourlyActual
import android.content.Context
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.ObservationPoolDiagnostics
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.DominantBlend
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.WidgetQueryWindows
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomWindow
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

internal object TemperatureGraphHoursLoader {
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 8

    sealed class GraphLoadOutcome {
        data class Empty(val reason: String) : GraphLoadOutcome()
        data class Loaded(
            val hours: List<HourData>,
            val obsQueryMs: Long,
            val buildHourDataMs: Long,
            val dominantStation: DominantBlend? = null,
            val newestObservationMs: Long? = null,
            val observationRowCount: Int = 0,
            val obsPoolDiagnostics: ObservationPoolDiagnostics.Summary? = null,
            val obsWindowStartMs: Long = 0L,
            val obsWindowEndMs: Long = 0L,
        ) : GraphLoadOutcome()
    }

    suspend fun loadGraphHours(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        repository: WeatherRepository?,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: ZoomWindow,
        lat: Double,
        lon: Double,
        useGraph: Boolean,
        smoothedForecasts: Map<Long, Float>,
        sourceMissingFromLoad: Boolean,
    ): GraphLoadOutcome {
        if (!useGraph) {
            // Text mode draws no graph; the header's "from yesterday" comes with the observation.
            return GraphLoadOutcome.Loaded(hours = emptyList(), obsQueryMs = 0L, buildHourDataMs = 0L)
        }

        val alignedCenter = com.weatherwidget.shared.util.WeatherTimeUtils.alignToNearestHourHalfUp(centerTime)

        // Always read: an actuals-free paint draws the past hours' forecast where the observed line
        // belongs, and the swap to the real line reads as a fake actual line (Fold 4, 2026-11-27).
        val obsRead = readObservations(
            context, appWidgetId, database, stateManager, repository, displaySource, alignedCenter, lat, lon,
        )
        val observations = obsRead.rows

        // Hours past the raw observations, from the 18-month archive.
        val archivedTemps = run {
            val readStart = alignedCenter.minusHours(zoom.backHours).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val readEnd = alignedCenter.plusHours(zoom.forwardHours).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            com.weatherwidget.data.model.HourlyActualsHistory.temperatures(
                database.hourlyActualDao().getInRange(readStart, readEnd + 1, lat, lon, displaySource.id)
                    .map { it.toHourlyActual() },
            )
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
            archivedTemps = archivedTemps,
        )
        val hourData = hourDataResult.hours
        val buildHourDataMs = System.currentTimeMillis() - buildHourDataStartMs
        val actualCount = hourData.count { it.isActual }

        logForecastGaps(
            context, appWidgetId, database, stateManager, hourlyForecasts, displaySource, alignedCenter, zoom, lat, lon,
            sourceMissingFromLoad,
        )

        if (hourData.isEmpty() && hourlyForecasts.isNotEmpty()) {
            return GraphLoadOutcome.Empty("buildHourDataResult_empty")
        }

        logBlendDiagnostics(
            appWidgetId, database, observations, displaySource, centerTime, alignedCenter, zoom, hourDataResult,
            blendDebugCollector, actualCount, buildHourDataMs,
        )

        return GraphLoadOutcome.Loaded(
            hours = hourData,
            obsQueryMs = obsRead.queryMs,
            buildHourDataMs = buildHourDataMs,
            dominantStation = hourDataResult.dominantStation,
            newestObservationMs = observations.maxOfOrNull { it.timestamp },
            observationRowCount = observations.size,
            obsPoolDiagnostics = obsRead.diagnostics,
            obsWindowStartMs = obsRead.windowStartMs,
            obsWindowEndMs = obsRead.windowEndMs,
        )
    }

    /** [loadGraphHours]'s observation read and its window. */
    private class ObsRead(
        val rows: List<ObservationEntity>,
        val diagnostics: ObservationPoolDiagnostics.Summary?,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val queryMs: Long,
    )

    /**
     * Reads the graph window's observations and enqueues the hourly backfill. Split out of
     * [loadGraphHours] (2026-11-19): the conditionally assigned `var …Ms = 0L` locals it replaces are
     * the shape whose dex ART rejected (plans/261119-split-oversized-android-suspend-functions.md).
     */
    private suspend fun readObservations(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        repository: WeatherRepository?,
        displaySource: WeatherSource,
        alignedCenter: LocalDateTime,
        lat: Double,
        lon: Double,
    ): ObsRead {
        val minEpoch = alignedCenter.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val maxEpoch = alignedCenter.plusHours(WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val obsStartMs = System.currentTimeMillis()
        if (repository == null) {
            // Not deferred, yet nothing to read with: the graph paints forecast-only, with no
            // actual line or labels. Every caller should hand a repository down; one that does
            // not shows here instead of as an unexplained empty redraw
            // (plans/261106-preemption-settle-null-repository.md).
            database.appLogDao().log(
                "OBS_READ_NO_REPOSITORY",
                "widget=$appWidgetId source=${displaySource.id} center=$alignedCenter",
                "WARN",
            )
        }
        val readApis = ActualsReadScope.apisFor(displaySource)
        val read = repository?.readObservationsInRange(minEpoch, maxEpoch, lat, lon, readApis)
        val loaded = read?.rows ?: emptyList()
        val obsQueryMs = System.currentTimeMillis() - obsStartMs

        maybeEnqueueHourlyObservationBackfill(
            context = context,
            database = database,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            displaySource = displaySource,
            graphStart = alignedCenter.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS),
            graphEnd = alignedCenter.plusHours(WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS),
            observations = loaded,
            repositoryPresent = repository != null,
            observationsLat = lat,
            observationsLon = lon,
        )
        return ObsRead(loaded, read?.diagnostics, minEpoch, maxEpoch, obsQueryMs)
    }

    /** TEMP_GAPS: missing forecast hours in view, and the one gap-fill they may start. */
    private suspend fun logForecastGaps(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        alignedCenter: LocalDateTime,
        zoom: ZoomWindow,
        lat: Double,
        lon: Double,
        sourceMissingFromLoad: Boolean,
    ) {
        val zoneId = ZoneId.systemDefault()
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)
        val missingForecasts = summarizeMissingForecastHours(
            startHour = startHour,
            endHour = endHour,
            zoneId = zoneId,
            forecastsByTime = forecastsByTime,
            displaySource = displaySource,
            nowMs = System.currentTimeMillis(),
        )

        val cooldownMs = 15 * 60 * 1000L
        if (missingForecasts.fillableCount > 0) {
            database.appLogDao().log(
                "TEMP_GAPS",
                "widget=$appWidgetId source=${displaySource.id} ${missingForecasts.diagnosticText()} " +
                    "dataRows=${hourlyForecasts.size} dataLoc=${String.format(Locale.US, "%.5f,%.5f", lat, lon)} " +
                    "sourceMissingFromLoad=$sourceMissingFromLoad",
                "INFO"
            )
            // The one gap-fill: only a fetch that can cover a day in view, that source alone.
            if (!sourceMissingFromLoad) {
                com.weatherwidget.widget.WidgetDayClickCoordinator.fillHourlyGaps(context, appWidgetId, "temp_gaps")
            }
        } else if (missingForecasts.missingCount > 0) {
            // Every gap is older than the live-table boundary. The move's own fetch already filed
            // every elapsed hour the payload carried (HOURLY_HISTORY_BACKFILL), so a forced
            // re-fetch would offer the same hours and store nothing — which is exactly what it did
            // every 15 minutes before this branch existed. Record it, fetch nothing.
            if (stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, "hourly_gaps_elapsed", cooldownMs)) {
                stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, "hourly_gaps_elapsed")
                database.appLogDao().log(
                    "TEMP_GAPS_ELAPSED_ONLY",
                    "widget=$appWidgetId source=${displaySource.id} ${missingForecasts.diagnosticText()} " +
                        "dataRows=${hourlyForecasts.size}, no fetch can fill these",
                    "INFO",
                )
            }
        }
    }

    /** IDW_BLEND and the TEMP_ACTUALS_* diagnostics of a non-deferred [loadGraphHours]. */
    private suspend fun logBlendDiagnostics(
        appWidgetId: Int,
        database: WeatherDatabase,
        observations: List<ObservationEntity>,
        displaySource: WeatherSource,
        centerTime: LocalDateTime,
        alignedCenter: LocalDateTime,
        zoom: ZoomWindow,
        hourDataResult: BuildHourDataResult,
        blendDebugCollector: BlendDebugCollector,
        actualCount: Int,
        buildHourDataMs: Long,
    ) {
        val stationIds = observations
            .filter { matchesObservationSource(it, displaySource) }
            .map { it.stationId }.toSet()
        database.appLogDao().log(
            "IDW_BLEND",
            "source=${displaySource.id} stations=${stationIds.size} [${stationIds.joinToString(",")}] blendedPoints=$actualCount",
        )
        val blendLines = blendDebugCollector.allLines()
        val inputContentHash = observations
            .map { "${it.stationId}@${it.timestamp}=${it.temperature}/${it.qcFailed}" }
            .sorted()
            .joinToString(",").hashCode()
        val inputOrderHash = observations
            .joinToString(",") { "${it.stationId}@${it.timestamp}" }.hashCode()
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
        val blendDebugPrefix =
            "widget=$appWidgetId source=${displaySource.id} aligned=$alignedCenter zoom=$zoom"
        blendDebugCollector.emittedLines()
            .take(MAX_PERSISTED_BLEND_DEBUG_LINES)
            .forEach { line ->
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
}

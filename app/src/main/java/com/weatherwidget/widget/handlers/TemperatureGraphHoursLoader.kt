package com.weatherwidget.widget.handlers

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
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.WidgetQueryWindows
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import com.weatherwidget.widget.ZoomWindow
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

internal object TemperatureGraphHoursLoader {
    private const val TEXT_MODE_DELTA_LOOKBACK_HOURS = 30L
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 8

    sealed class GraphLoadOutcome {
        data class Empty(val reason: String) : GraphLoadOutcome()
        data class Loaded(
            val hours: List<HourData>,
            val obsQueryMs: Long,
            val buildHourDataMs: Long,
            val deltaFromYesterday: Float? = null,
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
        deferGraphActuals: Boolean,
        smoothedForecasts: Map<Long, Float>,
        observedAt: Long?,
        lastObservedTemp: Float?,
        sourceMissingFromLoad: Boolean,
    ): GraphLoadOutcome {
        if (!useGraph) {
            val obsStartMs = System.currentTimeMillis()
            val zoneId = ZoneId.systemDefault()
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val minEpoch = truncated.minusHours(TEXT_MODE_DELTA_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val maxEpoch = truncated.plusHours(2).atZone(zoneId).toInstant().toEpochMilli()
            val observations =
                repository?.getObservationsInRange(
                    minEpoch, maxEpoch, lat, lon, ActualsReadScope.apisFor(displaySource),
                ) ?: emptyList()
            val delta = computeDeltaFromYesterday(
                observations = observations,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                lat = lat,
                lon = lon,
                observedAt = observedAt,
                lastObservedTemp = lastObservedTemp,
                personalStationWeight = stateManager.getPersonalStationWeight(),
            )
            return GraphLoadOutcome.Loaded(
                hours = emptyList(),
                obsQueryMs = System.currentTimeMillis() - obsStartMs,
                buildHourDataMs = 0L,
                deltaFromYesterday = delta,
            )
        }

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated

        var obsQueryMs = 0L
        var obsPoolDiagnostics: ObservationPoolDiagnostics.Summary? = null
        var obsWindowStartMs = 0L
        var obsWindowEndMs = 0L
        val observations = if (deferGraphActuals) {
            emptyList()
        } else {
            val minEpoch = alignedCenter.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val maxEpoch = alignedCenter.plusHours(WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val obsStartMs = System.currentTimeMillis()
            val readApis = ActualsReadScope.apisFor(displaySource)
            val read = repository?.readObservationsInRange(minEpoch, maxEpoch, lat, lon, readApis)
            val loaded = read?.rows ?: emptyList()
            obsPoolDiagnostics = read?.diagnostics
            obsWindowStartMs = minEpoch
            obsWindowEndMs = maxEpoch
            obsQueryMs = System.currentTimeMillis() - obsStartMs

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
        val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)
        val missingForecasts = summarizeMissingForecastHours(
            startHour = startHour,
            endHour = endHour,
            zoneId = zoneId,
            forecastsByTime = forecastsByTime,
            displaySource = displaySource,
        )

        if (missingForecasts.missingCount > 0) {
            val cooldownMs = 15 * 60 * 1000L
            if (!sourceMissingFromLoad &&
                stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, "hourly_gaps", cooldownMs)
            ) {
                stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, "hourly_gaps")
                database.appLogDao().log(
                    "TEMP_GAPS_REFRESH",
                    "widget=$appWidgetId source=${displaySource.id} ${missingForecasts.diagnosticText()} " +
                        "dataRows=${hourlyForecasts.size} dataLoc=${String.format(Locale.US, "%.5f,%.5f", lat, lon)}, " +
                        "requesting immediate API update",
                    "INFO"
                )
                WidgetWorkScheduler.enqueueRedundantImmediateSync(
                    context = context,
                    forceRefresh = true,
                    reason = "hourly_gaps"
                )
            }
        }

        if (hourData.isEmpty() && hourlyForecasts.isNotEmpty()) {
            return GraphLoadOutcome.Empty("buildHourDataResult_empty")
        }

        if (!deferGraphActuals) {
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

        val deltaFromYesterday = computeDeltaFromYesterday(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            lat = lat,
            lon = lon,
            observedAt = observedAt,
            lastObservedTemp = lastObservedTemp,
            personalStationWeight = stateManager.getPersonalStationWeight(),
        )

        return GraphLoadOutcome.Loaded(
            hours = hourData,
            obsQueryMs = obsQueryMs,
            buildHourDataMs = buildHourDataMs,
            deltaFromYesterday = deltaFromYesterday,
            dominantStation = hourDataResult.dominantStation,
            newestObservationMs = observations.maxOfOrNull { it.timestamp },
            observationRowCount = observations.size,
            obsPoolDiagnostics = obsPoolDiagnostics,
            obsWindowStartMs = obsWindowStartMs,
            obsWindowEndMs = obsWindowEndMs,
        )
    }

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
}

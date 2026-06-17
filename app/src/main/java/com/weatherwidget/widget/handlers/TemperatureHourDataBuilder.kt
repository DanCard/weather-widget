package com.weatherwidget.widget.handlers

import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.BlendObservationStats
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.GraphRenderUtils
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.HourDataAssembler
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomLevel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "TemperatureHourDataBuilder"
private const val BLEND_DEBUG_THROTTLE_MS = 50L

internal fun computeSmoothedForecasts(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    smoothIterations: Int = CurrentTemperatureResolver.HEADER_SMOOTH_ITERATIONS,
): Map<Long, Float> {
    return CurrentTemperatureResolver.computeSmoothedForecasts(
        hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
        displaySourceId = displaySource.id,
        smoothIterations = smoothIterations,
    )
}

internal data class SelectedObservationSeries(
    val stationId: String?,
    val stationName: String?,
    val stationType: String?,
    val observations: List<ObservationEntity>,
    val rejectedGroupCount: Int,
)

internal class BlendDebugCollector(
    private val throttleMs: Long = BLEND_DEBUG_THROTTLE_MS,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var lastDetailedEmitMs: Long? = null
    private val emitted = mutableListOf<String>()

    var rawDetailedLines: Int = 0
        private set
    var emittedDetailedLines: Int = 0
        private set
    var suppressedDetailedLines: Int = 0
        private set

    fun recordDetailed(lineProvider: () -> String, alwaysEmit: Boolean = false) {
        rawDetailedLines += 1
        val now = clockMs()
        val shouldEmit =
            alwaysEmit || lastDetailedEmitMs == null || now - lastDetailedEmitMs!! >= throttleMs

        if (shouldEmit) {
            emitted += lineProvider()
            emittedDetailedLines += 1
            lastDetailedEmitMs = now
        } else {
            suppressedDetailedLines += 1
        }
    }

    fun emittedLines(): List<String> = emitted

    fun buildSummary(
        stationCount: Int,
        blendedPointCount: Int,
        blendDurationMs: Long,
    ): String =
        "stations=$stationCount blendedPoints=$blendedPointCount blendMs=$blendDurationMs " +
            "rawDetailedLines=$rawDetailedLines emittedDetailedLines=$emittedDetailedLines " +
            "suppressedDetailedLines=$suppressedDetailedLines throttleMs=$throttleMs"
}

internal data class BuildHourDataResult(
    val hours: List<HourData>,
    val blendStats: BlendObservationStats?,
)

@androidx.annotation.VisibleForTesting
internal fun buildHourDataList(
    hourlyForecasts: List<HourlyForecastEntity>,
    centerTime: LocalDateTime,
    numColumns: Int,
    displaySource: WeatherSource,
    zoom: ZoomLevel = ZoomLevel.WIDE,
    actuals: List<ObservationEntity> = emptyList(),
    onBlendDebug: ((() -> String) -> Unit)? = null,
    smoothedForecasts: Map<Long, Float>? = null,
): List<HourData> =
    buildHourDataResult(
        hourlyForecasts = hourlyForecasts,
        centerTime = centerTime,
        numColumns = numColumns,
        displaySource = displaySource,
        zoom = zoom,
        actuals = actuals,
        onBlendDebug = onBlendDebug,
        smoothedForecasts = smoothedForecasts,
    ).hours

/**
 * Epoch-millis of the per-day "centered" hour used for date footer labels at multi-day (THREE_DAY)
 * zoom: for each local date in `[start, end]` the local-noon hour, clamped into the window so the
 * partial first/last days still get a label at the nearest visible edge. One entry per visible day,
 * matching the hour grid that [buildHourDataResult] walks (its hours are top-of-hour and these
 * representatives are too). Pure, for unit testing.
 */
internal fun dateLabelMillis(start: LocalDateTime, end: LocalDateTime, zone: ZoneId): Set<Long> =
    buildSet {
        var date = start.toLocalDate()
        val lastDate = end.toLocalDate()
        while (!date.isAfter(lastDate)) {
            val noon = date.atTime(12, 0)
            val rep = when {
                noon.isBefore(start) -> start
                noon.isAfter(end) -> end
                else -> noon
            }
            add(rep.atZone(zone).toInstant().toEpochMilli())
            date = date.plusDays(1)
        }
    }

internal fun buildHourDataResult(
    hourlyForecasts: List<HourlyForecastEntity>,
    centerTime: LocalDateTime,
    numColumns: Int,
    displaySource: WeatherSource,
    zoom: ZoomLevel = ZoomLevel.WIDE,
    actuals: List<ObservationEntity> = emptyList(),
    onBlendDebug: ((() -> String) -> Unit)? = null,
    smoothedForecasts: Map<Long, Float>? = null,
): BuildHourDataResult {
    val now = LocalDateTime.now()

    val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)

    val zoneId = ZoneId.systemDefault()
    val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
    val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
    val startHour = alignedCenter.minusHours(zoom.backHours)
    val endHour = alignedCenter.plusHours(zoom.forwardHours)
    val lat = hourlyForecasts.firstOrNull()?.locationLat ?: com.weatherwidget.widget.WeatherWidgetWorker.DEFAULT_LAT
    val lon = hourlyForecasts.firstOrNull()?.locationLon ?: com.weatherwidget.widget.WeatherWidgetWorker.DEFAULT_LON
    val sourceActuals = actuals.filter { matchesObservationSource(it, displaySource) }
    val sourceSpanSummary =
        if (sourceActuals.isEmpty()) {
            "none"
        } else {
            val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            val firstTs = sourceActuals.minOf { it.timestamp }
            val lastTs = sourceActuals.maxOf { it.timestamp }
            val firstLocal = Instant.ofEpochMilli(firstTs).atZone(zoneId).toLocalDateTime().format(formatter)
            val lastLocal = Instant.ofEpochMilli(lastTs).atZone(zoneId).toLocalDateTime().format(formatter)
            "$firstLocal..$lastLocal"
        }
    val actualSeries = ActualTemperatureSeriesBuilder.build(
        hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
        observations = actuals.map { it.toReading() },
        centerTime = centerTime,
        displaySourceId = displaySource.id,
        userLat = lat,
        userLon = lon,
        backHours = zoom.backHours,
        forwardHours = zoom.forwardHours,
        contextLookbackHours = WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS,
        contextLookaheadHours = WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS,
        now = now,
        zoneId = zoneId,
        smoothedForecasts = smoothedForecasts,
        onBlendDebug = onBlendDebug,
    )
    val selectedStationId = actualSeries.selectedStationId
    val blendInputActuals =
        if (selectedStationId != null) {
            sourceActuals.filter { it.stationId == selectedStationId }
        } else {
            sourceActuals
        }
    val stationCount = blendInputActuals.map { it.stationId }.toSet().size
    if (blendInputActuals.isNotEmpty()) {
        onBlendDebug?.invoke {
            val stationBreakdown = blendInputActuals
                .groupBy { it.stationId }
                .entries
                .sortedBy { it.key }
                .joinToString("; ") { (stationId, rows) ->
                    val minTime = Instant.ofEpochMilli(rows.minOf { it.timestamp })
                        .atZone(zoneId)
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    val maxTime = Instant.ofEpochMilli(rows.maxOf { it.timestamp })
                        .atZone(zoneId)
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    "$stationId rows=${rows.size} span=$minTime-$maxTime"
                }
            "window source=${displaySource.id} start=$startHour end=$endHour sourceRows=${blendInputActuals.size} stations=$stationCount breakdown=[$stationBreakdown]"
        }
    } else {
        onBlendDebug?.invoke { "window source=${displaySource.id} start=$startHour end=$endHour sourceRows=0 stations=0" }
    }
    Log.d(
        TAG,
        "buildHourDataList: source=${displaySource.id}, sourceRows=${sourceActuals.size}, " +
            "sourceSpan=$sourceSpanSummary, selectedStation=${selectedStationId ?: "ALL"}, " +
            "blendInputRows=${blendInputActuals.size}, stations=$stationCount, " +
            "blendedPoints=${actualSeries.blendStats?.emittedPointCount ?: 0}, visualWindow=${startHour.format(DateTimeFormatter.ISO_LOCAL_TIME)} to ${endHour.format(DateTimeFormatter.ISO_LOCAL_TIME)}"
    )

    // Narrow widgets space WIDE-zoom hour markers further apart (every 6h vs 4h) so the wider
    // inline <hour><icon><a|p> footer groups don't crowd. Wide widgets keep the default cadence.
    val labelInterval =
        if (zoom == ZoomLevel.WIDE && GraphRenderUtils.isNarrowWidget(numColumns)) {
            HourlyGraphDefaults.NARROW_WIDE_LABEL_INTERVAL
        } else {
            zoom.labelInterval
        }

    // At THREE_DAY zoom the window spans multiple days, where bare "12a/12p" footer labels can't
    // tell you which day a region is. Switch to one date label per day, centered under that day:
    // for each visible local date pick the in-window hour closest to local noon (clamped to the
    // window edges so partial first/last days still get a label). Near zooms keep time-of-day.
    val dateMode = zoom == ZoomLevel.THREE_DAY
    val dateLabelMillis = if (dateMode) dateLabelMillis(startHour, endHour, zoneId) else emptySet()

    // Assemble the graph point list from the shared sub-hourly-inclusive series, so the labeled actual
    // high/low match the daily view exactly (an off-hour observed peak/trough rides on its own point
    // instead of collapsing onto the nearest hour). Platform decoration — footer hour label, weather
    // icon, sun/day-night flags, label cadence — is layered on here; the desktop app calls the same
    // assembler with an identity decorator. topHourOrdinal reproduces the old per-top-of-hour index the
    // WIDE/THREE_DAY label cadence keys off (sub-hourly points don't advance it).
    var topHourOrdinal = 0
    val finalHours = HourDataAssembler.assembleHourData(actualSeries, zoneId) { base, isTopHour, _ ->
        val time = base.dateTime
        if (isTopHour) {
            val hourMs = time.atZone(zoneId).toInstant().toEpochMilli()
            val hourIndex = topHourOrdinal++
            val forecast = forecastsByTime[hourMs]
            if (forecast == null) {
                Log.w(TAG, "buildHourDataList: Missing forecast for $time (ms=$hourMs) source=${displaySource.id}")
            }
            val sunInfo = SunPositionUtils.getSunInfo(time, lat, lon)
            val isNight = sunInfo.isNight
            val isTwilight = sunInfo.phase == SunPhase.TWILIGHT
            val isSunBoundary = sunInfo.isSunBoundary
            val iconRes = forecast?.let {
                WeatherIconMapper.getIconResource(
                    condition = it.condition,
                    isNight = isNight,
                    cloudCover = it.cloudCover,
                    precipProbability = it.precipProbability,
                    isTwilight = isTwilight,
                    isSunBoundary = isSunBoundary,
                )
            }
            val showLabel =
                if (dateMode) {
                    hourMs in dateLabelMillis
                } else when (zoom) {
                    ZoomLevel.WIDE -> hourIndex % labelInterval == 0
                    ZoomLevel.THREE_DAY -> hourIndex % labelInterval == 0
                    ZoomLevel.NARROW -> true
                }
            base.copy(
                label = if (dateMode) formatDateLabel(time.toLocalDate()) else formatHourLabel(time),
                iconRes = iconRes,
                isNight = isNight,
                isTwilight = isTwilight,
                isSunBoundary = isSunBoundary,
                isSunny = iconRes?.let { WeatherIconMapper.isSunny(it) } ?: false,
                isRainy = iconRes?.let { WeatherIconMapper.isPrecipitation(it) } ?: false,
                isMixed = iconRes?.let { WeatherIconMapper.isMixed(it) } ?: false,
                isCurrentHour = time == now.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                showLabel = showLabel,
            )
        } else {
            val subSunInfo = SunPositionUtils.getSunInfo(time, lat, lon)
            base.copy(
                label = formatHourLabel(time),
                iconRes = null,
                isNight = subSunInfo.isNight,
                isTwilight = subSunInfo.phase == SunPhase.TWILIGHT,
                isSunBoundary = subSunInfo.isSunBoundary,
                isSunny = false,
                isRainy = false,
                isMixed = false,
                isCurrentHour = false,
                showLabel = false,
            )
        }
    }

    return BuildHourDataResult(
        hours = finalHours,
        blendStats = actualSeries.blendStats,
    )
}

@androidx.annotation.VisibleForTesting
internal fun selectObservationSeries(
    observations: List<ObservationEntity>,
    displaySource: WeatherSource,
    startHour: LocalDateTime,
    endHour: LocalDateTime,
): SelectedObservationSeries {
    val selected = ActualTemperatureSeriesBuilder.selectObservationSeries(
        observations = observations.map { it.toReading() },
        displaySourceId = displaySource.id,
        startHour = startHour,
        endHour = endHour,
    )
    return SelectedObservationSeries(
        stationId = selected.stationId,
        stationName = selected.stationName,
        stationType = selected.stationType,
        observations = observations.filter { obs -> selected.observations.any { it.stationId == obs.stationId && it.timestamp == obs.timestamp } },
        rejectedGroupCount = selected.rejectedGroupCount,
    )
}

internal fun matchesObservationSource(
    observation: ObservationEntity,
    displaySource: WeatherSource,
): Boolean {
    return observation.api == displaySource.id || observation.api == WeatherSource.GENERIC_GAP.id
}

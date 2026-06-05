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
import com.weatherwidget.widget.GraphRenderUtils
import com.weatherwidget.widget.HourlyGraphDefaults
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.HourData
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomLevel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "TemperatureHourDataBuilder"
private const val BLEND_DEBUG_THROTTLE_MS = 50L

internal const val HEADER_SMOOTH_ITERATIONS = 0

internal fun computeSmoothedForecasts(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    smoothIterations: Int = HEADER_SMOOTH_ITERATIONS,
): Map<Long, Float> {
    val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)
    val sortedTimes = forecastsByTime.keys.sorted()
    val rawTemps = sortedTimes.map { forecastsByTime[it]!!.temperature }
    val smoothedTemps = GraphRenderUtils.smoothValuesPreservingAllExtrema(
        rawTemps,
        iterations = smoothIterations,
    )
    return sortedTimes.mapIndexed { index, time ->
        time to smoothedTemps[index]
    }.toMap()
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
    val hours = mutableListOf<HourData>()
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

    var currentHour = startHour
    var hourIndex = 0

    // 1. Collect top-of-hour forecasts
    while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
        val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
        val forecast = forecastsByTime[hourMs]

        if (forecast == null) {
            Log.w(TAG, "buildHourDataList: Missing forecast for $currentHour (ms=$hourMs) source=${displaySource.id}")
        }

        val isCurrentHour = currentHour == now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val showLabel =
            when (zoom) {
                ZoomLevel.WIDE -> hourIndex % labelInterval == 0
                ZoomLevel.NARROW -> true
            }

        val sunInfo = SunPositionUtils.getSunInfo(currentHour, lat, lon)
        val isNight = sunInfo.isNight
        val isTwilight = sunInfo.phase == SunPhase.TWILIGHT
        val isSunBoundary = sunInfo.isSunBoundary
        if (isTwilight || isSunBoundary) {
            Log.d(TAG, "phase=${sunInfo.phase} boundary=$isSunBoundary hour=$currentHour condition=${forecast?.condition ?: "null"} lat=$lat lon=$lon")
        }
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
        val isSunny = iconRes?.let { WeatherIconMapper.isSunny(it) } ?: false
        val isRainy = iconRes?.let { WeatherIconMapper.isPrecipitation(it) } ?: false
        val isMixed = iconRes?.let { WeatherIconMapper.isMixed(it) } ?: false

        hours.add(
            HourData(
                dateTime = currentHour,
                temperature = smoothedForecasts?.get(hourMs) ?: forecast?.temperature ?: Float.NaN,
                label = formatHourLabel(currentHour),
                iconRes = iconRes,
                isNight = isNight,
                isTwilight = isTwilight,
                isSunBoundary = isSunBoundary,
                isSunny = isSunny,
                isRainy = isRainy,
                isMixed = isMixed,
                isCurrentHour = isCurrentHour,
                showLabel = showLabel,
                isActual = false,
                actualTemperature = null,
                isObservedActual = false,
            ),
        )
        hourIndex++
        currentHour = currentHour.plusHours(1)
    }

    // 2. Inject sub-hourly actuals
    val finalHours = mutableListOf<HourData>()
    val pointsByTime = actualSeries.points.associateBy {
        Instant.ofEpochMilli(it.timeMs).atZone(zoneId).toLocalDateTime()
    }

    for (time in pointsByTime.keys.sorted()) {
        val isTopHour = time.minute == 0 && time.second == 0
        val actualPoint = pointsByTime.getValue(time)

        if (isTopHour) {
            val topHourData = hours.find { it.dateTime == time }
            if (topHourData != null) {
                finalHours.add(
                    topHourData.copy(
                        isActual = actualPoint.isActual,
                        actualTemperature = actualPoint.actualTemp,
                        isObservedActual = actualPoint.isObservedActual,
                    )
                )
            }
        } else {
            val prevTopHour = hours.lastOrNull { !it.dateTime.isAfter(time) }
            val nextTopHour = hours.firstOrNull { it.dateTime.isAfter(time) }
            
            val forecastTemp = if (prevTopHour != null && nextTopHour != null) {
                val prevT = prevTopHour.temperature
                val nextT = nextTopHour.temperature
                if (prevT.isNaN() || nextT.isNaN()) Float.NaN else {
                    val totalSecs = java.time.Duration.between(prevTopHour.dateTime, nextTopHour.dateTime).seconds
                    val elapsedSecs = java.time.Duration.between(prevTopHour.dateTime, time).seconds
                    val fraction = elapsedSecs.toFloat() / totalSecs.toFloat()
                    prevT + (nextT - prevT) * fraction
                }
            } else {
                val prevT = prevTopHour?.temperature
                val nextT = nextTopHour?.temperature
                when {
                    prevT != null && !prevT.isNaN() -> prevT
                    nextT != null && !nextT.isNaN() -> nextT
                    else -> Float.NaN
                }
            }

            val subSunInfo = SunPositionUtils.getSunInfo(time, lat, lon)
            val iconRes: Int? = null

            finalHours.add(
                HourData(
                    dateTime = time,
                    temperature = actualPoint.forecastTemp.takeUnless { it.isNaN() } ?: forecastTemp,
                    label = formatHourLabel(time),
                    iconRes = iconRes,
                    isNight = subSunInfo.isNight,
                    isTwilight = subSunInfo.phase == SunPhase.TWILIGHT,
                    isSunBoundary = subSunInfo.isSunBoundary,
                    isSunny = iconRes?.let { WeatherIconMapper.isSunny(it) } ?: false,
                    isRainy = iconRes?.let { WeatherIconMapper.isPrecipitation(it) } ?: false,
                    isMixed = iconRes?.let { WeatherIconMapper.isMixed(it) } ?: false,
                    isCurrentHour = false,
                    showLabel = false,
                    isActual = actualPoint.isActual,
                    actualTemperature = actualPoint.actualTemp,
                    isObservedActual = actualPoint.isObservedActual,
                )
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

package com.weatherwidget.widget.handlers

import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.ObservationBlender
import com.weatherwidget.util.ObservationBlender.BlendObservationStats
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.GraphRenderUtils
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
    val smoothedTemps = GraphRenderUtils.smoothValuesPreservingGlobalExtrema(
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
    val startMs = startHour.atZone(zoneId).toInstant().toEpochMilli()
    val endMs = endHour.atZone(zoneId).toInstant().toEpochMilli()

    val contextStartMs = alignedCenter.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
    val contextEndMs = alignedCenter.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()

    val lat = hourlyForecasts.firstOrNull()?.locationLat ?: com.weatherwidget.widget.WeatherWidgetWorker.DEFAULT_LAT
    val lon = hourlyForecasts.firstOrNull()?.locationLon ?: com.weatherwidget.widget.WeatherWidgetWorker.DEFAULT_LON
    val sourceActuals = actuals.filter { matchesObservationSource(it, displaySource) }
    val selectedStationId =
        if (displaySource != WeatherSource.NWS) {
            selectObservationSeries(
                observations = sourceActuals,
                displaySource = displaySource,
                startHour = alignedCenter.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS),
                endHour = alignedCenter.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS),
            ).stationId
        } else {
            null
        }
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
    val blendedActualsResult = ObservationBlender.blendObservationSeries(
        observations = blendInputActuals,
        hourlyForecasts = hourlyForecasts,
        displaySource = displaySource,
        userLat = lat,
        userLon = lon,
        startMs = contextStartMs,
        endMs = contextEndMs,
        onBlendDebug = onBlendDebug,
    )
    val blendedActuals = blendedActualsResult.observations
    Log.d(
        TAG,
        "buildHourDataList: source=${displaySource.id}, IDW blend from $stationCount stations, " +
            "blendedPoints=${blendedActuals.size}, visualWindow=${startHour.format(DateTimeFormatter.ISO_LOCAL_TIME)} to ${endHour.format(DateTimeFormatter.ISO_LOCAL_TIME)}"
    )

    val labelInterval = zoom.labelInterval

    var currentHour = startHour
    var hourIndex = 0

    // 1. Collect top-of-hour forecasts
    while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
        val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
        val forecast = forecastsByTime[hourMs]

        if (forecast != null) {
            val isCurrentHour = currentHour == now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val showLabel =
                when (zoom) {
                    ZoomLevel.WIDE -> hourIndex % labelInterval == 0
                    ZoomLevel.NARROW -> true
                }

            val isNight = SunPositionUtils.isNight(currentHour, lat, lon)
            val iconRes = WeatherIconMapper.getIconResource(
                condition = forecast.condition,
                isNight = isNight,
                cloudCover = forecast.cloudCover,
            )
            val isSunny = WeatherIconMapper.isSunny(iconRes)
            val isRainy = WeatherIconMapper.isRainy(iconRes)
            val isMixed = WeatherIconMapper.isMixed(iconRes)

            hours.add(
                HourData(
                    dateTime = currentHour,
                    temperature = smoothedForecasts?.get(hourMs) ?: forecast.temperature,
                    label = formatHourLabel(currentHour),
                    iconRes = iconRes,
                    isNight = isNight,
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
        }
        currentHour = currentHour.plusHours(1)
    }

    // 2. Inject sub-hourly actuals
    val finalHours = mutableListOf<HourData>()
    val allTimes = hours.map { it.dateTime }.toMutableSet()
    val actualMap = mutableMapOf<LocalDateTime, ObservationEntity>()

    // Pre-initialize lastActual from the full blended series to ensure consistency at window boundaries
    var lastActual: Float? = blendedActuals
        .filter { it.timestamp < startMs && it.timestamp <= now.atZone(zoneId).toInstant().toEpochMilli() }
        .lastOrNull()?.temperature

    blendedActuals.forEach { obs ->
        val obsTime = Instant.ofEpochMilli(obs.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        
        if (!obsTime.isBefore(startHour) && !obsTime.isAfter(endHour) && obsTime.isBefore(now)) {
            allTimes.add(obsTime)
            actualMap[obsTime] = obs
        }
    }

    val sortedTimes = allTimes.sorted()

    for (time in sortedTimes) {
        val isTopHour = time.minute == 0 && time.second == 0
        val isPast = time.isBefore(now)
        val actualObservation = actualMap[time]
        val actualTemp = actualObservation?.temperature
        val isRawObservedActual = actualObservation?.condition == "observed"

        if (isTopHour) {
            val topHourData = hours.find { it.dateTime == time }
            if (topHourData != null) {
                finalHours.add(
                    topHourData.copy(
                        isActual = isPast && actualTemp != null,
                        actualTemperature = actualTemp,
                        isObservedActual = isPast && isRawObservedActual,
                    )
                )
            }
        } else {
            val prevTopHour = hours.lastOrNull { !it.dateTime.isAfter(time) }
            val nextTopHour = hours.firstOrNull { it.dateTime.isAfter(time) }
            
            val forecastTemp = if (prevTopHour != null && nextTopHour != null) {
                val totalSecs = java.time.Duration.between(prevTopHour.dateTime, nextTopHour.dateTime).seconds
                val elapsedSecs = java.time.Duration.between(prevTopHour.dateTime, time).seconds
                val fraction = elapsedSecs.toFloat() / totalSecs.toFloat()
                prevTopHour.temperature + (nextTopHour.temperature - prevTopHour.temperature) * fraction
            } else {
                prevTopHour?.temperature ?: nextTopHour?.temperature ?: 0f
            }

            finalHours.add(
                HourData(
                    dateTime = time,
                    temperature = forecastTemp,
                    label = formatHourLabel(time),
                    iconRes = null,
                    isNight = SunPositionUtils.isNight(time, lat, lon),
                    isSunny = false,
                    isRainy = false,
                    isMixed = false,
                    isCurrentHour = false,
                    showLabel = false,
                    isActual = true,
                    actualTemperature = actualTemp,
                    isObservedActual = isRawObservedActual,
                )
            )
        }
    }

    for (i in finalHours.indices) {
        if (finalHours[i].isActual && finalHours[i].actualTemperature != null) {
            lastActual = finalHours[i].actualTemperature
        } else if (finalHours[i].dateTime.isBefore(now)) {
            if (lastActual != null) {
                finalHours[i] =
                    finalHours[i].copy(
                        isActual = true,
                        actualTemperature = lastActual,
                        isObservedActual = false,
                    )
            } else {
                finalHours[i] =
                    finalHours[i].copy(
                        isActual = false,
                        actualTemperature = null,
                        isObservedActual = false,
                    )
            }
        }
    }

    return BuildHourDataResult(
        hours = finalHours,
        blendStats = blendedActualsResult.stats,
    )
}

@androidx.annotation.VisibleForTesting
internal fun selectObservationSeries(
    observations: List<ObservationEntity>,
    displaySource: WeatherSource,
    startHour: LocalDateTime,
    endHour: LocalDateTime,
): SelectedObservationSeries {
    val sourceObservations = observations.filter { matchesObservationSource(it, displaySource) }
    if (sourceObservations.isEmpty()) {
        return SelectedObservationSeries(
            stationId = null,
            stationName = null,
            stationType = null,
            observations = emptyList(),
            rejectedGroupCount = 0,
        )
    }

    val grouped = sourceObservations.groupBy { it.stationId }
    val selectedEntry = grouped.entries.maxWithOrNull(
        compareBy<Map.Entry<String, List<ObservationEntity>>>(
            { entry -> entry.value.map { observationHour(it) }.toSet().size },
            { entry -> entry.value.size },
            { entry -> -entry.value.minOfOrNull { it.distanceKm }!! },
            { entry -> entry.value.maxOf { it.timestamp } },
            { entry -> -entry.key.hashCode() },
        )
    )

    val chosen = selectedEntry?.value.orEmpty().sortedBy { it.timestamp }
    val metadata = chosen.firstOrNull()
    return SelectedObservationSeries(
        stationId = selectedEntry?.key,
        stationName = metadata?.stationName,
        stationType = metadata?.stationType,
        observations = chosen.filter { obs ->
            val obsTime = Instant.ofEpochMilli(obs.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            !obsTime.isBefore(startHour) && !obsTime.isAfter(endHour)
        },
        rejectedGroupCount = (grouped.size - 1).coerceAtLeast(0),
    )
}

internal fun matchesObservationSource(
    observation: ObservationEntity,
    displaySource: WeatherSource,
): Boolean {
    val inferred = ObservationResolver.inferSource(observation.stationId)
    return inferred == displaySource.id || inferred == WeatherSource.GENERIC_GAP.id
}

private fun observationHour(observation: ObservationEntity): LocalDateTime =
    Instant.ofEpochMilli(observation.timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .truncatedTo(java.time.temporal.ChronoUnit.HOURS)

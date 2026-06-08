package com.weatherwidget.data.remote

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Merges NWS gridpoint time-series (sky cover, quantitative precipitation) into the per-hour
 * forecast periods.
 *
 * The NWS *hourly forecast* endpoint omits sky cover and grid-derived precip amounts — those
 * live in the separate *gridpoints* response. Without this merge `cloudCover` (and the grid QPF
 * precip amount) are null on every hourly row, which collapses the cloud-cover graph to a flat
 * zero line.
 *
 * Shared by Android ([NwsForecastMapper]) and the desktop service so both populate identical
 * cloud-cover / precip data.
 */
object NwsHourlyGridMerge {

    private val HOUR_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    /**
     * Returns [rawHourlyPeriods] with `cloudCover` filled from [skyCoverByHour] (keyed
     * `yyyy-MM-dd'T'HH:00` in the system-default zone) and `precipAmountMm` filled from
     * time-weighted overlap with [qpfIntervals]. Empty inputs pass the periods through unchanged.
     */
    fun applyGridpointData(
        rawHourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
        skyCoverByHour: Map<String, Int>,
        qpfIntervals: List<NwsApi.QuantitativePrecipitationInterval>,
    ): List<NwsApi.HourlyForecastPeriod> {
        val withSkyCover = if (skyCoverByHour.isNotEmpty()) {
            rawHourlyPeriods.map { period ->
                val hourKey = runCatching {
                    Instant.ofEpochMilli(period.startTime)
                        .atZone(ZoneId.systemDefault())
                        .format(HOUR_KEY_FORMAT)
                }.getOrNull()
                val cover = hourKey?.let { skyCoverByHour[it] }
                if (cover != null) period.copy(cloudCover = cover) else period
            }
        } else {
            rawHourlyPeriods
        }

        return if (qpfIntervals.isNotEmpty()) {
            withSkyCover.map { period ->
                val gridAmount = resolveGridQpfForHourlyPeriod(period, qpfIntervals)
                if (gridAmount != null) period.copy(precipAmountMm = gridAmount) else period
            }
        } else {
            withSkyCover
        }
    }

    /**
     * Distributes [intervals]' precipitation across the one-hour window starting at
     * [period].startTime, weighting each overlapping interval by its fractional time overlap.
     * Returns null when no interval overlaps the hour.
     */
    fun resolveGridQpfForHourlyPeriod(
        period: NwsApi.HourlyForecastPeriod,
        intervals: List<NwsApi.QuantitativePrecipitationInterval>,
    ): Float? {
        if (intervals.isEmpty()) return null
        val periodEnd = period.startTime + 60 * 60 * 1000L
        val overlapping = intervals.filter { interval ->
            interval.startTime < periodEnd && interval.endTime > period.startTime
        }
        if (overlapping.isEmpty()) return null

        return overlapping.sumOf { interval ->
            val overlapStart = max(period.startTime, interval.startTime)
            val overlapEnd = min(periodEnd, interval.endTime)
            val overlapMs = (overlapEnd - overlapStart).coerceAtLeast(0L)
            if (overlapMs == 0L) {
                0.0
            } else {
                val intervalMs = (interval.endTime - interval.startTime).coerceAtLeast(1L)
                interval.amountMm.toDouble() * overlapMs.toDouble() / intervalMs.toDouble()
            }
        }.toFloat()
    }
}

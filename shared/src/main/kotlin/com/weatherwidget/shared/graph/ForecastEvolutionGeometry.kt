package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TempUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Platform-agnostic geometry + data-prep for the "forecast evolution" history graph (how the
 * forecast for one target day changed across successive fetches/days-ahead).
 *
 * The Android widget draws these primitives with `android.graphics.Canvas`
 * (`ForecastEvolutionRenderer`); the desktop app draws the same primitives with Compose
 * `DrawScope` (`ForecastHistoryWindow`). Keeping bucketing, axis math, the time axis, and the
 * label formatters here guarantees the two surfaces agree.
 */
object ForecastEvolutionGeometry {
    const val SNAPSHOT_BUCKET_HOURS = 4L
    const val MILLIS_PER_HOUR = 60L * 60L * 1000L

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val DATETIME_FORMATTER = DateTimeFormatter.ofPattern("M/d h a", Locale.getDefault())

    /** A single stored forecast snapshot for the target day. */
    data class EvolutionPoint(
        val forecastDate: String,
        val fetchedAt: Long,
        val daysAhead: Int,
        val highTemp: Float?,
        val lowTemp: Float?,
        val source: WeatherSource,
    )

    data class ForecastSample(val temp: Float, val daysAhead: Int, val source: WeatherSource)

    data class ErrorSample(val error: Float, val daysAhead: Int, val fetchedAt: Long, val source: WeatherSource)

    /**
     * Collapses multiple snapshots captured within the same [SNAPSHOT_BUCKET_HOURS] window down to
     * the latest one, after dropping points missing the temperature we're plotting.
     */
    fun bucketize(
        points: List<EvolutionPoint>,
        tempFor: (EvolutionPoint) -> Float?,
    ): List<EvolutionPoint> {
        val bucketMillis = SNAPSHOT_BUCKET_HOURS * MILLIS_PER_HOUR
        return points
            .filter { tempFor(it) != null }
            .groupBy { it.fetchedAt / bucketMillis }
            .mapNotNull { (_, bucketPoints) -> bucketPoints.maxByOrNull { it.fetchedAt } }
    }

    /** All temperatures relevant to axis scaling: forecast points plus any actual reference lines. */
    fun collectTemps(
        points: List<EvolutionPoint>,
        tempFor: (EvolutionPoint) -> Float?,
        actualValue: Float?,
        appActualValue: Float?,
    ): List<Float> {
        val temps = mutableListOf<Float>()
        points.forEach { tempFor(it)?.let { t -> temps.add(t) } }
        actualValue?.let { temps.add(it) }
        appActualValue?.let { temps.add(it) }
        return temps
    }

    fun errorSamples(
        series: List<EvolutionPoint>,
        tempFor: (EvolutionPoint) -> Float?,
        baseline: Float,
    ): List<ErrorSample> =
        series.mapNotNull { point ->
            tempFor(point)?.let { temp ->
                ErrorSample(error = temp - baseline, daysAhead = point.daysAhead, fetchedAt = point.fetchedAt, source = point.source)
            }
        }

    /**
     * Maps fetch timestamps onto the X axis and supplies the time gridline ticks. [divisions]
     * controls label density: the axis has `divisions + 1` evenly spaced ticks. Callers scale it to
     * the available width via [tickDivisionsForWidth] so wider graphs get more time labels.
     */
    class TimeAxis(timestamps: List<Long>, divisions: Int = 4) {
        val minTime: Long = timestamps.minOrNull() ?: 0L
        val maxTime: Long = timestamps.maxOrNull() ?: 0L
        val isSingleTime: Boolean = minTime == maxTime
        val ticks: List<Long> = buildTimeTicks(minTime, maxTime, divisions)

        fun xForTime(timeMs: Long, graphLeft: Float, graphWidth: Float): Float =
            if (isSingleTime) graphLeft + graphWidth / 2f
            else graphLeft + graphWidth * (timeMs - minTime).toFloat() / (maxTime - minTime).toFloat()

        fun formatLabel(tickMs: Long): String = formatTimeLabel(tickMs, minTime, maxTime)
    }

    fun buildTimeTicks(minTime: Long, maxTime: Long, divisions: Int = 4): List<Long> {
        if (minTime == maxTime) return listOf(minTime)
        val d = divisions.coerceAtLeast(1)
        return (0..d).map { idx ->
            minTime + ((maxTime - minTime) * idx / d)
        }
    }

    /**
     * How many time-axis divisions fit a graph of [graphWidthPx], allowing ~[spacingPx] per slanted
     * label, clamped to `[2, maxDivisions]`. Defaults match the Android widget; desktop passes a
     * tighter [spacingPx] and larger [maxDivisions] for denser labels on its bigger window.
     */
    fun tickDivisionsForWidth(graphWidthPx: Float, spacingPx: Float = 78f, maxDivisions: Int = 12): Int =
        (graphWidthPx / spacingPx).roundToInt().coerceIn(2, maxDivisions)

    fun formatTimeLabel(timestampMillis: Long, minTime: Long, maxTime: Long): String {
        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(timestampMillis)
        val minDate = Instant.ofEpochMilli(minTime).atZone(zone).toLocalDate()
        val maxDate = Instant.ofEpochMilli(maxTime).atZone(zone).toLocalDate()
        val formatter = if (minDate == maxDate) TIME_FORMATTER else DATETIME_FORMATTER
        return formatter.format(instant.atZone(zone))
    }

    fun formatAxisLabel(value: Float, useCelsius: Boolean = false): String {
        val displayVal = if (useCelsius) TempUtils.fahrenheitToCelsius(value) else value
        val rounded = displayVal.roundToInt()
        return if (abs(displayVal - rounded) < 0.01f) "${rounded}°"
        else String.format("%.1f°", displayVal)
    }

    fun formatErrorLabel(value: Float, useCelsius: Boolean = false): String {
        val displayVal = if (useCelsius) value / 1.8f else value
        val rounded = displayVal.roundToInt()
        val roundedStr = if (abs(displayVal - rounded) < 0.01f) "${abs(rounded)}"
        else String.format("%.1f", abs(displayVal))
        return when {
            displayVal > 0.05f -> "+${roundedStr}°"
            displayVal < -0.05f -> "-${roundedStr}°"
            else -> "0°"
        }
    }

    fun formatTempLabel(value: Float, useCelsius: Boolean = false): String = TempUtils.formatTemp(value, useCelsius) ?: ""
}

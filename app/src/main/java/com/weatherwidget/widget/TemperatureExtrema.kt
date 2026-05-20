package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.BuildConfig
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object TemperatureExtrema {
    private const val TAG = "TempExtrema"

    data class ExtremaIndices(
        val labelTemps: List<Float>,
        val actualLabelTemps: List<Float>,
        val dailyHighIndex: Int,
        val dailyLowIndex: Int,
        val actualHighIndex: Int,
        val actualLowIndex: Int,
        val forecastHighIndex: Int,
        val forecastLowIndex: Int,
        val pastForecastHighIndex: Int,
        val pastForecastLowIndex: Int,
        val actualEndIndex: Int,
        val significantLocalExtrema: List<Int>,
        val fetchIdx: Int,
    )

    fun compute(
        hours: List<HourData>,
        transitionX: Float?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
        prominenceThreshold: Float,
    ): ExtremaIndices {
        val labelTemps = hours.map { it.temperature }
        val actualLabelTemps = hours.map { h ->
            if (h.isActual) h.actualTemperature ?: h.temperature else h.temperature
        }
        val dailyHighIndex = labelTemps.indices.maxByOrNull { labelTemps[it] } ?: -1
        val dailyLowIndex = labelTemps.indices.minByOrNull { labelTemps[it] } ?: -1

        val actualEndIndex = if (transitionX != null) effectiveActualEndIndex else hours.lastIndex
        val actualIndices = (0..actualEndIndex).filter { it in actualLabelTemps.indices }
        
        Log.d(TAG, "ACTUAL_END_INDEX: $actualEndIndex transitionX=$transitionX")
        Log.d(TAG, "LABEL_TEMPS: $labelTemps")
        val nanIndices = labelTemps.mapIndexedNotNull { i, t -> if (t.isNaN()) i else null }
        if (nanIndices.isNotEmpty()) {
            Log.w(TAG, "NAN_TEMP_INDICES: $nanIndices hours=${nanIndices.map { hours[it].dateTime }}")
        }
        
        val actualHighIndex = actualIndices.maxByOrNull { actualLabelTemps[it] } ?: -1
        val actualLowIndex = actualIndices.minByOrNull { actualLabelTemps[it] } ?: -1

        Log.d(TAG, "ACTUAL_EXTREMA highIdx=$actualHighIndex highTemp=${if (actualHighIndex >= 0) actualLabelTemps[actualHighIndex] else "N/A"} " +
                "lowIdx=$actualLowIndex lowTemp=${if (actualLowIndex >= 0) actualLabelTemps[actualLowIndex] else "N/A"} " +
                "actualIndicesRange=${actualIndices.firstOrNull()}..${actualIndices.lastOrNull()}")

        val forecastStartIndex = if (transitionX != null) effectiveActualEndIndex else 0
        val forecastIndices = (forecastStartIndex..hours.lastIndex).filter { it in labelTemps.indices }
        val forecastHighIndex = forecastIndices.maxByOrNull { labelTemps[it] } ?: -1
        val forecastLowIndex = forecastIndices.minByOrNull { labelTemps[it] } ?: -1

        val hasTransition = transitionX != null
        val pastForecastIndices = if (hasTransition) (0..actualEndIndex).filter { it in labelTemps.indices } else emptyList()
        val pastForecastHighIndex = if (hasTransition) pastForecastIndices.maxByOrNull { labelTemps[it] } ?: -1 else -1
        val pastForecastLowIndex = if (hasTransition) pastForecastIndices.minByOrNull { labelTemps[it] } ?: -1 else -1

        if (forecastHighIndex >= 0 && forecastLowIndex >= 0) {
            val forecastDates = forecastIndices.map { hours[it].dateTime.toLocalDate() }.distinct()
            Log.d(TAG, "FORECAST_EXTREMA highIdx=$forecastHighIndex highTemp=${labelTemps[forecastHighIndex]} " +
                "lowIdx=$forecastLowIndex lowTemp=${labelTemps[forecastLowIndex]} " +
                "forecastDates=$forecastDates forecastRange=$forecastStartIndex..${hours.lastIndex}")
        }

        val localExtrema = findLocalExtremaIndices(labelTemps)
        val significantLocalExtrema = localExtrema.filter { index ->
            val prom = bilateralExtremaProminence(index, labelTemps, localExtrema)
            if (prom < prominenceThreshold) {
                Log.d(TAG, "EXTREMUM_REJECTED idx=$index temp=${labelTemps[index]} prominence=$prom threshold=$prominenceThreshold")
                false
            } else {
                Log.d(TAG, "SIGNIFICANT_EXTREMUM idx=$index temp=${labelTemps[index]} prominence=$prom")
                true
            }
        }

        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1

        return ExtremaIndices(
            labelTemps, actualLabelTemps,
            dailyHighIndex, dailyLowIndex,
            actualHighIndex, actualLowIndex,
            forecastHighIndex, forecastLowIndex,
            pastForecastHighIndex, pastForecastLowIndex,
            actualEndIndex,
            significantLocalExtrema, fetchIdx
        )
    }

    fun findLocalExtremaIndices(temps: List<Float>): List<Int> {
        val extrema = mutableListOf<Int>()
        if (temps.size < 3) return extrema
        var i = 1
        while (i < temps.size - 1) {
            val current = temps[i]; val prev = temps[i - 1]; val next = temps[i + 1]
            if ((current > prev && current > next) || (current < prev && current < next)) extrema.add(i)
            else if (current == next && current != prev) {
                var j = i + 1
                while (j < temps.size - 1 && temps[j] == current) j++
                if (j < temps.size && ((current > prev && current > temps[j]) || (current < prev && current < temps[j]))) extrema.add((i + j) / 2)
                i = j - 1
            }
            i++
        }
        return extrema
    }

    fun bilateralExtremaProminence(index: Int, temps: List<Float>, extrema: List<Int>): Float {
        val current = temps[index]; val extremaSet = extrema.toSet()
        fun maxDelta(step: Int): Float {
            var maxD = 0f; var cursor = index + step
            while (cursor in temps.indices) {
                maxD = max(maxD, abs(temps[cursor] - current))
                if (cursor != index + step && cursor in extremaSet) break
                cursor += step
            }
            return maxD
        }
        val left = maxDelta(-1); val right = maxDelta(1)
        return if (left == 0f || right == 0f) 0f else min(left, right)
    }
}

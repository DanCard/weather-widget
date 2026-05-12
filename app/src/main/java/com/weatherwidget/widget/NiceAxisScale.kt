package com.weatherwidget.widget

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

data class AxisScale(
    val niceMin: Float,
    val niceMax: Float,
    val tickInterval: Float,
    val ticks: List<Float>,
) {
    val range: Float get() = niceMax - niceMin

    fun valueToY(value: Float, graphTop: Float, graphHeight: Float): Float {
        if (range == 0f) return graphTop + graphHeight / 2f
        return graphTop + graphHeight * (1f - (value - niceMin) / range)
    }
}

object NiceAxisScale {
    fun compute(
        rawMin: Float,
        rawMax: Float,
        targetTickCount: Int = 5,
        minRange: Float = 5f,
    ): AxisScale {
        var effectiveMin = rawMin
        var effectiveMax = rawMax

        if (effectiveMax - effectiveMin < minRange) {
            val center = (effectiveMin + effectiveMax) / 2f
            effectiveMin = center - minRange / 2f
            effectiveMax = center + minRange / 2f
        }

        if (effectiveMin == effectiveMax) {
            effectiveMin -= minRange / 2f
            effectiveMax += minRange / 2f
        }

        val rawRange = effectiveMax - effectiveMin
        val rawInterval = rawRange / targetTickCount.coerceAtLeast(1)
        val niceInterval = niceNum(rawInterval, round = true)
        val safeInterval = if (niceInterval > 0f) niceInterval else rawInterval.coerceAtLeast(1f)

        val niceMin = floor(effectiveMin / safeInterval) * safeInterval
        val niceMax = ceil(effectiveMax / safeInterval) * safeInterval

        val ticks = mutableListOf<Float>()
        var tick = niceMin
        while (tick <= niceMax + safeInterval * 0.001f) {
            ticks.add(tick)
            tick += safeInterval
        }

        return AxisScale(
            niceMin = niceMin,
            niceMax = niceMax,
            tickInterval = safeInterval,
            ticks = ticks,
        )
    }

    fun computeSymmetric(
        maxAbsValue: Float,
        targetTickCount: Int = 5,
        minRange: Float = 6f,
    ): AxisScale {
        val bound = maxOf(maxAbsValue, minRange / 2f)
        val scale = compute(-bound, bound, targetTickCount = targetTickCount, minRange = minRange)
        val symmetricMax = maxOf(abs(scale.niceMin), abs(scale.niceMax))
        return compute(-symmetricMax, symmetricMax, targetTickCount = targetTickCount, minRange = minRange)
    }

    private fun niceNum(value: Float, round: Boolean): Float {
        if (value <= 0f) return 1f
        val exponent = floor(log10(value.toDouble())).toFloat()
        val fraction = value / 10f.pow(exponent)
        val niceFraction = if (round) {
            when {
                fraction < 1.5f -> 1f
                fraction < 3f -> 2f
                fraction < 7f -> 5f
                else -> 10f
            }
        } else {
            when {
                fraction <= 1f -> 1f
                fraction <= 2f -> 2f
                fraction <= 5f -> 5f
                else -> 10f
            }
        }
        return niceFraction * 10f.pow(exponent)
    }

    private fun log10(x: Double): Double {
        var result = 0.0
        var value = x
        while (value >= 10.0) { value /= 10.0; result += 1.0 }
        while (value < 1.0 && value > 0.0) { value *= 10.0; result -= 1.0 }
        val ln10 = 2.302585092994046
        var lnValue = 0.0
        var term = (value - 1.0) / (value + 1.0)
        val termSquared = term * term
        var sum = 0.0
        for (i in 0..19) {
            val n = 2 * i + 1
            sum += term / n
            term *= termSquared
        }
        lnValue = 2.0 * sum
        return result + lnValue / ln10
    }
}

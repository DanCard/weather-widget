package com.weatherwidget.util

import android.graphics.LinearGradient
import android.graphics.Shader
import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver

/**
 * Android shader/adapter layer for weather-adaptive forecast rendering.
 *
 * Forecast colors, the forecast-color decision, cloud ratios, and the chance-of-rain split are all
 * single-sourced in `:shared` ([WeatherColors] / [WeatherConditionResolver]). This object keeps only
 * what is genuinely Android-specific: the `LinearGradient` construction and the res-ID → icon-name
 * lookups that feed the shared functions.
 */
object WeatherConditionColors {
    private const val MAX_TRANSITION_FRACTION = 0.12f

    internal data class MixedBarSplit(
        val ratio: Float,
        val topColor: Int,
        val bottomColor: Int,
        val topFraction: Float,
    )

    // Re-export the shared ARGB constants so existing callers/tests keep their names.
    val FORECAST_SUNNY = WeatherColors.FORECAST_SUNNY   // Amber/gold
    val FORECAST_CLOUDY = WeatherColors.FORECAST_CLOUDY // Slate gray
    val FORECAST_RAINY = WeatherColors.FORECAST_RAINY   // Steel blue
    val FORECAST_NIGHT = WeatherColors.FORECAST_NIGHT   // Muted silver
    val FORECAST_TWILIGHT = WeatherColors.FORECAST_TWILIGHT // Warm amber for sunrise/sunset hours
    val OBSERVED = WeatherColors.OBSERVED               // Bright rose pink

    /** Maps weather condition flags to a forecast color. Priority: rainy > night > twilight+sunny > mixed > sunny > cloudy. */
    fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean, isTwilight: Boolean = false): Int =
        WeatherColors.forecastColor(isSunny, isRainy, isMixed, isNight, isTwilight)

    /** Returns the cloud ratio (0.0 = clear, 1.0 = overcast) for mixed-condition icons, or null for non-mixed. */
    fun cloudRatio(iconRes: Int): Float? =
        WeatherIconMapper.iconResToName(iconRes)?.let(WeatherConditionResolver::cloudRatioFromIcon)

    /** Returns a vertical LinearGradient for a mixed-condition bar (gold top -> gray/blue bottom), or null for solid-color bars. */
    fun forecastBarGradient(iconRes: Int, x: Float, topY: Float, bottomY: Float, cloudRatioOverride: Float? = null): LinearGradient? {
        val split = resolveMixedBarSplit(iconRes, cloudRatioOverride) ?: return null

        android.util.Log.d(
            "WeatherConditionColors",
            "forecastBarGradient: icon=$iconRes ratio=${split.ratio} topFraction=${split.topFraction} -> color=${if (split.bottomColor == FORECAST_RAINY) "BLUE" else "GREY"}",
        )

        val stops = gradientStopPositions(split.ratio)
        return LinearGradient(
            x, topY, x, bottomY,
            intArrayOf(split.topColor, split.topColor, split.bottomColor, split.bottomColor),
            stops,
            Shader.TileMode.CLAMP,
        )
    }

    internal fun resolveMixedBarSplit(iconRes: Int, cloudRatioOverride: Float? = null): MixedBarSplit? {
        // Delegate the split math/colors to the shared single source of truth (keeps Android and
        // desktop identical); Android only supplies its resource-ID inputs.
        val ratio = cloudRatioOverride ?: cloudRatio(iconRes)
        val isChanceOfRain = WeatherIconMapper.iconResToName(iconRes)
            ?.let(WeatherConditionResolver::isChanceOfRainIcon) ?: false
        val shared = WeatherColors.mixedBarSplit(ratio, isChanceOfRain) ?: return null
        return MixedBarSplit(
            ratio = shared.ratio,
            topColor = shared.topColorArgb,
            bottomColor = shared.bottomColorArgb,
            topFraction = shared.topFraction,
        )
    }

    internal fun gradientStopPositions(ratio: Float): FloatArray {
        val normalizedRatio = ratio.coerceIn(0f, 1f)
        val goldEnd = (1f - normalizedRatio).coerceIn(0f, 1f)
        val transitionLength = minOf(MAX_TRANSITION_FRACTION, normalizedRatio * 0.5f)
        val greyStart = (goldEnd + transitionLength).coerceIn(goldEnd, 1f)
        return floatArrayOf(0f, goldEnd, greyStart, 1f)
    }
}

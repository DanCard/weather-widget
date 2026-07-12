package com.weatherwidget.shared.util

/**
 * Shared color constants and decision logic for weather-adaptive forecast rendering.
 * Platform-specific gradient/shader code stays in Android/desktop consuming modules.
 *
 * Colors are stored as ARGB hex ints (0xAARRGGBB).
 */
object WeatherColors {
    const val FORECAST_SUNNY: Int = 0xFFF4C542.toInt()   // Amber/gold
    const val FORECAST_CLOUDY: Int = 0xFF8E99A4.toInt()  // Slate gray
    const val FORECAST_RAINY: Int = 0xFF5A8FBF.toInt()   // Steel blue
    const val FORECAST_NIGHT: Int = 0xFFBBBBBB.toInt()   // Muted silver
    const val FORECAST_TWILIGHT: Int = 0xFFFFA726.toInt() // Warm amber
    const val OBSERVED: Int = 0xFFFF7799.toInt()          // Light salmon

    /**
     * Maps weather condition flags to a forecast color.
     * Priority: rainy > night > twilight+sunny > mixed > sunny > cloudy.
     */
    fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean, isTwilight: Boolean = false): Int {
        return when {
            isRainy -> FORECAST_RAINY
            isNight -> FORECAST_NIGHT
            isTwilight && isSunny -> FORECAST_TWILIGHT
            isMixed -> FORECAST_SUNNY
            isSunny -> FORECAST_SUNNY
            else -> FORECAST_CLOUDY
        }
    }

    /**
     * A weather-adaptive forecast bar split into a clear (gold) top and a cloud/rain bottom.
     * Bar height encodes the temperature range; this split encodes the weather: the bottom
     * segment's *height* is the cloud-cover [ratio] and its *color* is rain (blue) vs cloud (grey).
     */
    data class MixedBarSplit(
        val ratio: Float,
        val topColorArgb: Int,     // FORECAST_SUNNY
        val bottomColorArgb: Int,  // FORECAST_RAINY (chance of rain) or FORECAST_CLOUDY
        val topFraction: Float,    // 1 - ratio (height of the clear/top segment)
    )

    /**
     * Base color for the today-column 24h-prior snapshot bar. Rain keeps the blue base
     * ("yesterday they predicted rain" stays visible); anything else — including cloudy —
     * returns null, meaning the platform's bright snapshot yellow. Cloudiness is carried by
     * the adaptive split's grey bottom segment, never by greying the base: a grey base plus
     * a grey split bottom rendered the whole bar as an unreadable solid-grey slab.
     */
    fun snapshotBarOverrideArgb(isRainy: Boolean): Int? = if (isRainy) FORECAST_RAINY else null

    /**
     * Single source of truth for the cloud/rain bar split shared by Android and desktop.
     * Returns null when there's no cloud ratio (the caller should draw a solid bar).
     * Each platform supplies [cloudRatio] (0..1) and [isChanceOfRain] from its own icon model.
     */
    fun mixedBarSplit(cloudRatio: Float?, isChanceOfRain: Boolean): MixedBarSplit? {
        val r = (cloudRatio ?: return null).coerceIn(0f, 1f)
        return MixedBarSplit(
            ratio = r,
            topColorArgb = FORECAST_SUNNY,
            bottomColorArgb = if (isChanceOfRain) FORECAST_RAINY else FORECAST_CLOUDY,
            topFraction = (1f - r).coerceIn(0f, 1f),
        )
    }
}

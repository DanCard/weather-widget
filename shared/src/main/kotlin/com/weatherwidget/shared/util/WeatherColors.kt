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
    const val OBSERVED: Int = 0xFFFF88AA.toInt()          // Bright rose pink

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
     * A rainy bar ([rainDaySplit]) is three segments instead: gold over grey (the sky) over blue,
     * the blue [ratio] being the daytime rain %.
     */
    data class MixedBarSplit(
        val ratio: Float,
        val topColorArgb: Int,     // FORECAST_SUNNY
        val bottomColorArgb: Int,  // FORECAST_RAINY (rainy bar) or FORECAST_CLOUDY
        val topFraction: Float,    // height of the clear/top segment (1 - ratio unless there is a middle)
        /** The top segment is drawn in [topColorArgb]; otherwise in the bar's own colour (e.g. snapshot yellow). */
        val topOverridesBar: Boolean = false,
        /** Height of a cloud-grey segment between the top and the bottom; 0 = two segments. */
        val midFraction: Float = 0f,
        val midColorArgb: Int = FORECAST_CLOUDY,
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

    /**
     * A rainy bar (user, 2026-10-19): blue at the bottom for the daytime rain % (the cloud ratio when
     * unknown), so it reads like the % label above the bar — more rain is always more blue. The cap
     * above it is the day's sky, split by the cloud share: gold (1 - cloud) over grey (cloud), i.e. the
     * dry-day bar squeezed into the cap. No cloud figure (a rain icon carries none) → an all-grey cap.
     * The gold overrides the bar's own colour, which is rain-blue for a rain icon.
     */
    fun rainDaySplit(cloudRatio: Float?, dayPrecipProbability: Int?): MixedBarSplit? {
        val r = (dayPrecipProbability?.let { it / 100f } ?: cloudRatio ?: return null).coerceIn(0f, 1f)
        val cap = 1f - r
        val cloud = (cloudRatio ?: 1f).coerceIn(0f, 1f)
        return MixedBarSplit(
            ratio = r,
            topColorArgb = FORECAST_SUNNY,
            bottomColorArgb = FORECAST_RAINY,
            topFraction = cap * (1f - cloud),
            topOverridesBar = true,
            midFraction = cap * cloud,
        )
    }

    /**
     * Daily-bar rain gate. The daily icon is resolved from the whole-day (24h) precip %, but a daily
     * column's bar stands for the daytime — overnight rain has its own between-column label. So a
     * rain icon only paints the bar blue when the daytime precip % also clears the "chance rain"
     * bar ([WeatherConditionResolver.CHANCE_RAIN_MIN_PRECIP]); e.g. 65% overnight but 15% by day
     * stays cloud-grey. A null [dayPrecipProbability] (unknown) leaves the icon's verdict alone.
     */
    fun isDaytimeRain(dayPrecipProbability: Int?): Boolean =
        dayPrecipProbability == null || dayPrecipProbability >= WeatherConditionResolver.CHANCE_RAIN_MIN_PRECIP

    /**
     * Resolves the weather-adaptive bar split from an icon name and optional cloud cover override.
     * Single source of truth shared by Android and desktop. Pass the column's [dayPrecipProbability]
     * for a daily forecast bar (see [isDaytimeRain]); leave it null for snapshot bars.
     */
    fun resolveMixedBarSplit(
        iconName: String?,
        cloudCoverRatio: Float? = null,
        dayPrecipProbability: Int? = null,
    ): MixedBarSplit? {
        val ratio = cloudCoverRatio ?: iconName?.let(WeatherConditionResolver::cloudRatioFromIcon)
        if (iconName != null && isDaytimeRain(dayPrecipProbability)) {
            if (WeatherConditionResolver.isPrecipitation(iconName) || WeatherConditionResolver.isChanceOfRainIcon(iconName)) {
                return rainDaySplit(ratio, dayPrecipProbability)
            }
        }
        return mixedBarSplit(ratio, isChanceOfRain = false)
    }

    /**
     * Decides whether a bar should draw adaptive segments (gold top + cloud/rain bottom) vs a solid bar.
     * Shared across Android and desktop.
     */
    fun shouldDrawAdaptiveBar(isMixed: Boolean, cloudCoverRatio: Float?): Boolean =
        isMixed || (cloudCoverRatio ?: 0f) > 0f
}

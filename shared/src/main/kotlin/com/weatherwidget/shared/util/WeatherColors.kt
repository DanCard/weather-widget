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
    const val OBSERVED: Int = 0xFFFF88AA.toInt()          // Bright rose pink — actual-value labels
    /** Mercury red: today's thermostat (bar, ghost, bulb) and past days' actual bars. [OBSERVED] is
     *  lightened for text legibility; bars drawn in it read pink (they were #FF3366 until 2026-06). */
    const val MERCURY: Int = 0xFFFF3366.toInt()

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

    /** The "yesterday's forecast" bar's own colour (today's left bar, past days' left bar). */
    const val SNAPSHOT_YELLOW: Int = 0xFFFFFF00.toInt()

    /**
     * Colour of a "yesterday's forecast" bar from its icon: rain keeps blue ("yesterday they
     * predicted rain" stays visible); anything else — including cloudy — the bright
     * [SNAPSHOT_YELLOW]. Cloudiness is the bar's grey share (`AdaptiveBarStrokes`), never a grey
     * base: grey on grey rendered the whole bar as an unreadable solid-grey slab.
     */
    fun snapshotBarArgb(iconName: String?): Int =
        if (iconName != null && WeatherConditionResolver.isPrecipitation(iconName)) FORECAST_RAINY else SNAPSHOT_YELLOW

    /**
     * A daily column's forecast colour from its icon and daytime rain chance, both platforms: a
     * past day's promoted-forecast bar and the forecast high label. Desktop's extra "cloud < 60 %
     * → gold" rule was dropped for Android's (2026-10-23).
     */
    fun dailyForecastArgb(iconName: String?, dayPrecipProbability: Int?): Int {
        val flags = iconName?.let { WeatherConditionResolver.getConditionFlags(it) }
            ?: return FORECAST_CLOUDY
        return forecastColor(
            isSunny = flags.isSunny,
            isRainy = flags.isRainy && isDaytimeRain(dayPrecipProbability),
            isMixed = flags.isMixed,
            isNight = false,
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
}

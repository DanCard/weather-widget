package com.weatherwidget.shared.settings

import com.weatherwidget.data.model.HindcastGuard
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
import kotlinx.serialization.Serializable

/**
 * Shared bounds and control math for an integer Settings slider.
 *
 * One definition drives Android's `SeekBar` (0-based progress, since `min` requires API 26+) and
 * Desktop's Compose `Slider` (`valueRange` and interior `steps`) so the two platforms cannot drift.
 */
data class IntSliderSpec(
    val min: Int,
    val max: Int,
    val default: Int,
) {
    init {
        require(min <= max) { "min ($min) must be <= max ($max)" }
        require(default in min..max) { "default ($default) must be in $min..$max" }
    }

    fun clamp(value: Int): Int = value.coerceIn(min, max)

    /** Android `SeekBar.max` when mapped to `0..(max - min)`. */
    val seekBarMax: Int get() = max - min

    fun toSeekBarProgress(value: Int): Int = clamp(value) - min

    fun fromSeekBarProgress(progress: Int): Int = clamp(progress + min)

    /** Compose `Slider.valueRange`. */
    val composeRange: ClosedFloatingPointRange<Float> get() = min.toFloat()..max.toFloat()

    /** Compose `Slider.steps` (number of discrete stops strictly between [min] and [max]). */
    val composeSteps: Int get() = (max - min - 1).coerceAtLeast(0)
}

/**
 * Cross-platform display settings shared by Android (`WeatherDisplayPreferences`) and Desktop
 * (`DesktopSettings`).
 *
 * Keeps defaults, clamping, weight conversion, and diff formatting in one place while each
 * platform retains its native storage format (`SharedPreferences` on Android, flat `config.json`
 * fields on Desktop).
 */
@Serializable
data class DisplaySettings(
    val narrowZoomSpanHours: Int = NARROW_ZOOM_SPAN.default,
    val multiDayZoomEnabled: Boolean = DEFAULT_MULTI_DAY_ZOOM_ENABLED,
    val personalStationDiscount: Int = PERSONAL_STATION_DISCOUNT.default,
    val hindcastMaxPriorAgeHours: Int = HINDCAST_MAX_PRIOR_AGE.default,
    val headerRainLookaheadHours: Int = HEADER_RAIN_LOOKAHEAD.default,
    val useCelsius: Boolean = false,
    val todayOverlayDelta: Boolean = DEFAULT_TODAY_OVERLAY_DELTA,
    val todayOverlayDominantTemp: Boolean = DEFAULT_TODAY_OVERLAY_DOMINANT_TEMP,
    val showDailyZoomOutButton: Boolean = DEFAULT_SHOW_DAILY_ZOOM_OUT_BUTTON,
    val showFuturePriorForecast: Boolean = DEFAULT_SHOW_FUTURE_PRIOR_FORECAST,
) {
    fun sanitized(): DisplaySettings = copy(
        narrowZoomSpanHours = NARROW_ZOOM_SPAN.clamp(narrowZoomSpanHours),
        personalStationDiscount = PERSONAL_STATION_DISCOUNT.clamp(personalStationDiscount),
        hindcastMaxPriorAgeHours = HINDCAST_MAX_PRIOR_AGE.clamp(hindcastMaxPriorAgeHours),
        headerRainLookaheadHours = HEADER_RAIN_LOOKAHEAD.clamp(headerRainLookaheadHours),
    )

    /** 0% discount -> weight 1.0 (no discount); 100% discount -> weight 0.0 (PWS ignored). */
    fun personalStationWeight(): Double =
        1.0 - PERSONAL_STATION_DISCOUNT.clamp(personalStationDiscount) / 100.0

    /** `field: old -> new` for every display setting that differs from [other]. */
    fun diffFrom(other: DisplaySettings): List<String> = buildList {
        fun add(name: String, current: Any?, previous: Any?) {
            if (current != previous) add("$name: $previous -> $current")
        }
        add("narrowZoomSpanHours", narrowZoomSpanHours, other.narrowZoomSpanHours)
        add("multiDayZoomEnabled", multiDayZoomEnabled, other.multiDayZoomEnabled)
        add("personalStationDiscount", personalStationDiscount, other.personalStationDiscount)
        add("hindcastMaxPriorAgeHours", hindcastMaxPriorAgeHours, other.hindcastMaxPriorAgeHours)
        add("headerRainLookaheadHours", headerRainLookaheadHours, other.headerRainLookaheadHours)
        add("useCelsius", useCelsius, other.useCelsius)
        add("todayOverlayDelta", todayOverlayDelta, other.todayOverlayDelta)
        add("todayOverlayDominantTemp", todayOverlayDominantTemp, other.todayOverlayDominantTemp)
        add("showDailyZoomOutButton", showDailyZoomOutButton, other.showDailyZoomOutButton)
        add("showFuturePriorForecast", showFuturePriorForecast, other.showFuturePriorForecast)
    }

    companion object {
        val NARROW_ZOOM_SPAN = IntSliderSpec(
            min = HourlyZoomRules.MIN_NARROW_SPAN_HOURS,
            max = HourlyZoomRules.MAX_NARROW_SPAN_HOURS,
            default = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
        )
        val PERSONAL_STATION_DISCOUNT = IntSliderSpec(
            min = 0,
            max = 100,
            default = 95,
        )
        val HINDCAST_MAX_PRIOR_AGE = IntSliderSpec(
            min = 0,
            max = HindcastGuard.MAX_PRIOR_AGE_HOURS_LIMIT,
            default = HindcastGuard.DEFAULT_MAX_PRIOR_AGE_HOURS,
        )
        val HEADER_RAIN_LOOKAHEAD = IntSliderSpec(
            min = 1,
            max = 8,
            default = PrecipProbabilityCalculator.VISIBLE_LOOKAHEAD_HOURS.toInt(),
        )

        const val DEFAULT_MULTI_DAY_ZOOM_ENABLED = false
        const val DEFAULT_TODAY_OVERLAY_DELTA = false
        const val DEFAULT_TODAY_OVERLAY_DOMINANT_TEMP = false
        const val DEFAULT_SHOW_DAILY_ZOOM_OUT_BUTTON = false
        const val DEFAULT_SHOW_FUTURE_PRIOR_FORECAST = false
    }
}

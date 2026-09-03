package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Platform-neutral daily day-click routing and hourly offset, shared by Android and desktop.
 */
object DayClickResolver {

    enum class DayTapZone {
        /** Main column body (temp bars / high-low area). */
        MAIN_COLUMN,
        /** Bottom icon band (weather icon + low label row). */
        BOTTOM_ICON,
    }

    enum class DayClickView {
        TEMPERATURE,
        PRECIPITATION,
        CLOUD_COVER,
    }

    /**
     * Lookahead for [routingPrecipProbability]. Deliberately equal to
     * [com.weatherwidget.shared.graph.ZoomStage.WIDE]'s `forwardHours`: a day tap opens WIDE
     * centred on now, so this is exactly the forecast span the graph is about to show. Asking about
     * a longer horizon routes taps to a precipitation graph whose visible window holds no rain.
     */
    const val TODAY_LOOKAHEAD_HOURS = PrecipProbabilityCalculator.VISIBLE_LOOKAHEAD_HOURS

    /** Which figure [routingPrecipProbability] returned, for the click-audit log line. */
    enum class PrecipGateSource { ROLLING_6H, DAILY }

    data class RoutingPrecip(
        val probability: Int?,
        val gateSource: PrecipGateSource,
    ) {
        /** e.g. `34(rolling6h)` / `40(daily)` — appended to CLICK_DAILY and the desktop audit. */
        fun auditText(): String {
            val label = when (gateSource) {
                PrecipGateSource.ROLLING_6H -> "rolling${TODAY_LOOKAHEAD_HOURS}h"
                PrecipGateSource.DAILY -> "daily"
            }
            return "$probability($label)"
        }
    }

    /**
     * The precip probability that [resolveView] should gate a day tap on.
     *
     * For **today** this is the maximum interpolated chance over the next [TODAY_LOOKAHEAD_HOURS] —
     * the span the opened graph actually shows — rather than the whole-day figure, which ignores how
     * much of the day is already past and so sends a 06:00 tap to a precipitation graph whose window
     * ends before the evening rain it was routed for.
     *
     * Every other day keeps [dailyProbability]: "the next 6 hours" is undefined for Friday. So is a
     * today with no usable hourly rows — falling back preserves precipitation routing for a source
     * that has no hourly coverage rather than silently losing it.
     *
     * Note this can only ever route *fewer* taps to precipitation, never more: [resolveView] still
     * requires a rain-indicator icon, and that icon is still derived from the whole day.
     */
    fun routingPrecipProbability(
        targetDay: LocalDate,
        now: LocalDateTime,
        hourly: List<HourlyForecast>,
        displaySourceId: String,
        fallbackSourceId: String,
        dailyProbability: Int?,
    ): RoutingPrecip {
        if (targetDay != now.toLocalDate()) {
            return RoutingPrecip(dailyProbability, PrecipGateSource.DAILY)
        }
        val rolling = PrecipProbabilityCalculator.maxPrecipProbabilityWithin(
            lookaheadHours = TODAY_LOOKAHEAD_HOURS,
            hourlyForecasts = hourly,
            displaySourceId = displaySourceId,
            fallbackSourceId = fallbackSourceId,
            // Null, not dailyProbability: the calculator's own fallback would hand back the
            // whole-day figure while claiming to be a rolling reading. Distinguishing "no hourly
            // data" here keeps the audit line honest about which number gated the tap.
            fallbackDailyProbability = null,
            referenceTime = now,
        )
        return if (rolling == null) {
            RoutingPrecip(dailyProbability, PrecipGateSource.DAILY)
        } else {
            RoutingPrecip(rolling, PrecipGateSource.ROLLING_6H)
        }
    }

    fun resolveView(zone: DayTapZone, iconName: String?, precipProbability: Int?): DayClickView {
        if (iconName == null) return DayClickView.TEMPERATURE
        return when (zone) {
            DayTapZone.MAIN_COLUMN -> {
                if (WeatherConditionResolver.shouldDailyClickShowPrecip(
                        WeatherConditionResolver.isRainIndicator(iconName),
                        precipProbability,
                    )
                ) {
                    DayClickView.PRECIPITATION
                } else {
                    DayClickView.TEMPERATURE
                }
            }
            DayTapZone.BOTTOM_ICON -> when (WeatherConditionResolver.resolveIconHome(iconName)) {
                WeatherConditionResolver.IconHome.PRECIPITATION -> DayClickView.PRECIPITATION
                WeatherConditionResolver.IconHome.CLOUD_COVER -> DayClickView.CLOUD_COVER
                WeatherConditionResolver.IconHome.HOURLY -> DayClickView.TEMPERATURE
            }
        }
    }

    /**
     * Hours from [now] (hour-aligned) to the hourly-graph center for [targetDay].
     * Today returns 0; other days anchor around noon of the target day.
     *
     * For asymmetric windows like [ZoomStage.WIDE] (12h back / 6h forward), the visual center of
     * [centerTime - backHours, centerTime + forwardHours] is centered on noon when centerTime is
     * shifted by (backHours - forwardHours) / 2 (+3h -> 15:00), framing 3am..9pm with noon at 50%.
     */
    fun calculateHourlyOffset(
        now: LocalDateTime,
        targetDay: LocalDate,
        zoomStage: com.weatherwidget.shared.graph.ZoomStage = com.weatherwidget.shared.graph.ZoomStage.WIDE,
    ): Int {
        if (targetDay == now.toLocalDate()) return 0
        val alignedNow = WeatherTimeUtils.alignToNearestHourHalfUp(now)
        val window = zoomStage.window()
        val shiftHours = (window.backHours - window.forwardHours) / 2
        val targetCenter = targetDay.atTime(12, 0).plusHours(shiftHours)
        return Duration.between(alignedNow, targetCenter).toHours().toInt()
    }
}

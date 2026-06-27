package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure logic for the rain labels shown on the daily-forecast graph (both the Android widget and the
 * desktop port). Produces the *text* of the daytime label (drawn on top of each bar) and the
 * nighttime label (tucked between columns); the actual Canvas/Compose drawing stays platform-side.
 *
 * Ported from the Android-only logic that lived in `DailyForecastIconResolver`,
 * `WidgetFormatUtils.formatPrecipAmount`, and `DailyViewLogic.build{Daily,Night}RainLabel` so both
 * platforms render identical strings.
 */
object DailyRainLabels {

    // ── Shared placement constants (consumed by both Android and desktop renderers) ───────────────
    // The drawing math stays platform-specific (Canvas vs Compose), but these dp values are the
    // single source of truth so the two platforms stay visually identical and a tweak is made once.

    /**
     * Gap (dp) between the day rain label's BOTTOM and the high-temp label's TOP. Negative means a
     * slight overlap (the percentage tucks onto the top of the temperature). Each platform applies
     * its own density/scale. IMPORTANT: anchor to the high label's *rendered* top — a wide temp
     * (e.g. "75.6°") is shrunk to fit a narrow column, so its real top is lower than full-size
     * metrics imply; ignoring that leaves a large floating gap.
     */
    const val RAIN_HIGH_TEMP_GAP_DP = -3f

    /** Edge margin (dp) keeping a rain label inside the widget bounds. */
    const val RAIN_LABEL_EDGE_MARGIN_DP = 4f

    // Night rain label tuck (interstitial label sitting in the low-temp band between two columns).
    /** Night label font is this fraction of the day label size. */
    const val NIGHT_SCALE = 0.72f
    const val NIGHT_TUCK_ROOM_MIN_DP = 10f
    const val NIGHT_TUCK_ROOM_MAX_DP = 22f
    const val NIGHT_TUCK_OVERLAP_BASE_DP = 5.0f
    const val NIGHT_TUCK_NUDGE_BASE_DP = 1.5f
    const val NIGHT_TUCK_NUDGE_RANGE_DP = 1.5f

    /** Max precip probability over the daytime (8am–8pm) and nighttime (8pm–8am) windows. */
    data class DayNightPrecip(
        val dayMax: Int?,
        val nightMax: Int?,
    )

    /**
     * Minimum daytime precip probability before a forecast day shows a rain label/icon. Scales with
     * distance so far-out low-confidence drizzle is suppressed: `4 * daysFromToday + 1`.
     */
    fun getMinimumPrecipProbabilityDay(daysFromToday: Int): Int = (4 * daysFromToday) + 1

    /** Nighttime threshold mirrors the daytime one. */
    fun getMinimumPrecipProbabilityNight(daysFromToday: Int): Int = getMinimumPrecipProbabilityDay(daysFromToday)

    /**
     * Formats a precip amount in mm as a compact string. US/GB locales get inches (".08in"),
     * everyone else gets millimeters ("3mm"). Leading zeros are trimmed.
     */
    fun formatPrecipAmount(amountMm: Float): String {
        val country = Locale.getDefault().country.uppercase(Locale.US)
        return if (country == "US" || country == "GB") {
            formatInches(amountMm / 25.4f)
        } else {
            formatMillimeters(amountMm)
        }
    }

    private fun formatInches(amountInches: Float): String {
        val precision = when {
            amountInches < 0.01f -> 3
            amountInches < 0.1f -> 3
            amountInches < 1f -> 2
            else -> 1
        }
        val formatted = String.format(Locale.US, "%.${precision}f", amountInches)
            .trimEnd('0')
            .trimEnd('.')
        return "${formatted.removePrefix("0")}in"
    }

    private fun formatMillimeters(amountMm: Float): String {
        val precision = if (amountMm < 10f) 1 else 0
        val formatted = String.format(Locale.US, "%.${precision}f", amountMm)
            .trimEnd('0')
            .trimEnd('.')
        return "${formatted.removePrefix("0")}mm"
    }

    /**
     * Computes the max precip probability over the daytime (8am–8pm) and nighttime (8pm–8am next
     * day) windows for [targetDate], reading from hourly rows of [displaySourceId] (falling back to
     * [fallbackSourceId] only if the display source has no rows at all).
     */
    fun calculateDayNightPrecipProbabilities(
        hourly: List<HourlyForecast>,
        targetDate: LocalDate,
        displaySourceId: String,
        fallbackSourceId: String = WeatherSource.GENERIC_GAP.id,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DayNightPrecip {
        // Daytime: 8:00 AM to 8:00 PM on the target date.
        val dayStartMs = targetDate.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        val dayEndMs = targetDate.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
        // Nighttime: 8:00 PM on target date to 8:00 AM next day.
        val nightStartMs = dayEndMs
        val nightEndMs = targetDate.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()

        val sourceForecasts = hourly.filter { it.source == displaySourceId }
        val candidates = if (sourceForecasts.isNotEmpty()) {
            sourceForecasts
        } else {
            hourly.filter { it.source == fallbackSourceId }
        }

        val dayMax = candidates
            .filter { it.dateTime in dayStartMs until dayEndMs }
            .mapNotNull { it.precipProbability }
            .maxOrNull()
        val nightMax = candidates
            .filter { it.dateTime in nightStartMs until nightEndMs }
            .mapNotNull { it.precipProbability }
            .maxOrNull()

        return DayNightPrecip(dayMax = dayMax, nightMax = nightMax)
    }

    /**
     * Daytime rain label drawn on top of a day's bar.
     * - Past days: observed amount, or null when none.
     * - Today: amount when day-prob ≥ 95% (and amount known), else day-prob% (if allowed), else null.
     * - Future: suppressed below the distance-scaled threshold; else amount when prob ≥ 99%, else prob%.
     */
    fun buildDailyRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        precipAmountMm: Float?,
        dayPrecipProbability: Int?,
        allowTodayRainChanceLabel: Boolean,
        observedPrecipAmountMm: Float?,
    ): String? {
        if (isPastDate) {
            if (observedPrecipAmountMm != null && observedPrecipAmountMm > 0f) {
                return formatPrecipAmount(observedPrecipAmountMm)
            }
            return null
        }
        if (date == today) {
            if (dayPrecipProbability != null && dayPrecipProbability >= 95 && precipAmountMm != null) {
                return formatPrecipAmount(precipAmountMm)
            }
            if (allowTodayRainChanceLabel && dayPrecipProbability != null && dayPrecipProbability > 0) {
                return "$dayPrecipProbability%"
            }
            return null
        }
        val daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()
        val dayMinProb = getMinimumPrecipProbabilityDay(daysFromToday)
        val dayPrecip = dayPrecipProbability

        if (dayPrecip != null && dayPrecip < dayMinProb) {
            return null
        }
        return when {
            dayPrecip != null && dayPrecip >= 99 && precipAmountMm != null -> formatPrecipAmount(precipAmountMm)
            dayPrecip != null && dayPrecip > 0 -> "$dayPrecip%"
            else -> null
        }
    }

    /**
     * Nighttime rain label tucked between columns.
     * - Past days: observed night amount, or null when none.
     * - Future: night-prob% when above the distance-scaled threshold, else null.
     */
    fun buildNightRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        nightPrecipProbability: Int?,
        observedNightPrecipMm: Float?,
    ): String? {
        if (isPastDate) {
            if (observedNightPrecipMm != null && observedNightPrecipMm > 0f) {
                return formatPrecipAmount(observedNightPrecipMm)
            }
            return null
        }
        val probability = nightPrecipProbability ?: return null
        val daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()
        if (daysFromToday < 0) return null

        val threshold = getMinimumPrecipProbabilityNight(daysFromToday)
        if (probability < threshold) return null

        return "$probability%"
    }
}

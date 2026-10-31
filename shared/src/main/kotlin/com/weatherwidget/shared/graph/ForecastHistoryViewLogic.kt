package com.weatherwidget.shared.graph

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyDayValueResolver
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.TempUtils
import com.weatherwidget.shared.util.TodayLowSettle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Source-agnostic view-state logic for the forecast-history screen, shared between Android
 * (`ForecastHistoryActivity`) and desktop (`ForecastHistoryWindow`). Pure functions only — no DB,
 * no platform types — so both UIs make the same decisions about which graph mode and button label
 * to show and how to look up actuals.
 */
object ForecastHistoryViewLogic {
    private const val TAG = "ForecastHistoryViewLogic"

    const val MAX_HISTORY_DAYS_BACK = 395L // 13 months

    enum class GraphMode { EVOLUTION, ERROR }

    enum class ButtonMode { EVOLUTION, ERROR, TEMPERATURE }

    enum class ActualLookupMode { NONE, SOURCE_SPECIFIC, ANY_SOURCE }

    /** Whether the mode button should launch hourly temperature view rather than toggle graph mode. */
    fun shouldLaunchTemperature(hasDate: Boolean, showTemperatureButton: Boolean): Boolean =
        hasDate && showTemperatureButton

    /** Show the hourly button when viewing today/future without actuals. */
    fun shouldShowTemperatureButton(
        date: LocalDate?,
        hasActualValues: Boolean,
        today: LocalDate = LocalDate.now(),
    ): Boolean = date != null && !date.isBefore(today) && !hasActualValues

    fun resolveButtonMode(showTemperatureButton: Boolean, graphMode: GraphMode): ButtonMode =
        if (showTemperatureButton) ButtonMode.TEMPERATURE
        else if (graphMode == GraphMode.EVOLUTION) ButtonMode.EVOLUTION
        else ButtonMode.ERROR

    fun resolveActualLookupMode(
        date: LocalDate,
        requestedSource: WeatherSource?,
        today: LocalDate = LocalDate.now(),
    ): ActualLookupMode =
        if (!date.isBefore(today)) {
            ActualLookupMode.NONE
        } else if (requestedSource != null) {
            ActualLookupMode.SOURCE_SPECIFIC
        } else {
            ActualLookupMode.ANY_SOURCE
        }

    /**
     * The "Location actual" lines (daily_history computed blend) for one day, and the extreme times
     * [ForecastEvolutionCutoff.points] uses to drop hindcasts.
     */
    data class LocationActuals(
        val high: Float?,
        val low: Float?,
        val highReachedAt: Long?,
        val lowReachedAt: Long?,
    ) {
        /** Show the "Location actual" legend entry: something is drawn. */
        val hasAny: Boolean get() = high != null || low != null

        companion object {
            val NONE = LocationActuals(null, null, null, null)
        }
    }

    /** Whether [locationActuals] needs today's hourly forecast (for the low's settle look-ahead). */
    fun needsTodayHourly(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean = date == today

    /** One `daily_history` row for the viewed day, as both platforms' DAOs return it. */
    data class LocationActualRow(
        val sourceId: String,
        val locationLat: Double,
        val locationLon: Double,
        val high: Float?,
        val low: Float?,
        val highAt: Long?,
        val lowAt: Long?,
    )

    /**
     * Past day: the source's row, both extremes, final. Future: nothing.
     *
     * Today, the source's row supplies each side once it settles, so its line appears when the
     * widget's today column stops printing the forecast (user, 2026-10-13 and 2026-10-31):
     * - **Low:** once [TodayLowSettle] settles it.
     * - **High:** from [DailyDayValueResolver.isHighSettled] (5pm).
     *
     * A settled extreme's time is final, so it also drops later hindcasts, as on a past day.
     *
     * Another source's row is never used: rows far behind belong to inactive sources, and this
     * screen only shows visible ones. See plans/261031-history-of-forecasts-today-high-line.md.
     *
     * [rows] are the day's rows at or near the location; they are collapsed to the nearest site
     * here. [todayHourly] is the source's (plus `GENERIC_GAP`) hourly forecast for
     * today; empty otherwise.
     */
    fun <T> locationActuals(
        date: LocalDate,
        now: LocalDateTime,
        zone: ZoneId,
        lat: Double,
        lon: Double,
        sourceId: String?,
        rows: List<LocationActualRow>,
        todayHourly: List<T>,
        epochMs: (T) -> Long,
        temperature: (T) -> Float,
    ): LocationActuals {
        val today = now.toLocalDate()
        if (date.isAfter(today)) return LocationActuals.NONE

        val site = LocationMatch.selectNearestSite(rows, lat, lon, { it.locationLat }, { it.locationLon })
        val own = site.filter { it.sourceId == sourceId }
            .minByOrNull { TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon) }
        if (date.isBefore(today)) return LocationActuals(own?.high, own?.low, own?.highAt, own?.lowAt)

        val highRow = own?.takeIf { it.high != null && DailyDayValueResolver.isHighSettled(now.hour) }
        val lowRow = own?.takeIf {
            it.low != null && TodayLowSettle.decide(now, lat, lon, zone, it.low, todayHourly, epochMs, temperature).settled
        }
        logTodayOnChange(today, sourceId, highRow, lowRow)
        return LocationActuals(highRow?.high, lowRow?.low, highRow?.highAt, lowRow?.lowAt)
    }

    @Volatile private var lastTodayLog: String? = null

    /** One line per change of decision: the screen reloads often and would otherwise repeat it. */
    private fun logTodayOnChange(
        today: LocalDate,
        sourceId: String?,
        highRow: LocationActualRow?,
        lowRow: LocationActualRow?,
    ) {
        val line = "HISTORY_TODAY_ACTUALS date=$today source=$sourceId high=${highRow?.high} " +
            "highAt=${highRow?.highAt} low=${lowRow?.low} lowAt=${lowRow?.lowAt}"
        if (line == lastTodayLog) return
        lastTodayLog = line
        Log.i(TAG, line)
    }

    /** One high/low pair for the footer card. */
    data class ActualPair(val high: Float, val low: Float)

    /** Which actuals UI the screen shows. One decision for Android and desktop. */
    data class ActualsDisplay(
        val showApiActualLegend: Boolean,
        val showLocationActualLegend: Boolean,
        /** Null hides the row. Past days only: today's values appear as graph labels. */
        val apiFooter: ActualPair?,
        val locationFooter: ActualPair?,
        /** The error graph compares a finished day, so today never qualifies, even after 5pm. */
        val errorGraphAvailable: Boolean,
    ) {
        val showFooterCard: Boolean get() = apiFooter != null || locationFooter != null
    }

    fun actualsDisplay(
        isPast: Boolean,
        apiHigh: Float?,
        apiLow: Float?,
        locationActuals: LocationActuals,
    ): ActualsDisplay {
        fun pair(high: Float?, low: Float?) = if (isPast && high != null && low != null) ActualPair(high, low) else null
        val apiFooter = pair(apiHigh, apiLow)
        val locationFooter = pair(locationActuals.high, locationActuals.low)
        return ActualsDisplay(
            showApiActualLegend = isPast,
            showLocationActualLegend = isPast || locationActuals.hasAny,
            apiFooter = apiFooter,
            locationFooter = locationFooter,
            errorGraphAvailable = apiFooter != null || locationFooter != null,
        )
    }

    fun normalizeSource(rawSource: String?): WeatherSource? =
        WeatherSource.fromDisplaySourceOrNull(rawSource)

    /**
     * Format a directional bias for the accuracy summary (warmer actual → forecast ran "low").
     *
     * @param formatSuffix called with the formatted bias value (e.g. "1.2°") and whether the
     *   forecast ran low; returns the full suffix string. Defaults to English " (1.2° low)".
     *   Android callers pass a lambda that resolves `R.string.bias_low_suffix` /
     *   `R.string.bias_high_suffix` for localization.
     */
    fun formatBias(
        bias: Double,
        useCelsius: Boolean,
        formatSuffix: (biasValue: String, isLow: Boolean) -> String =
            { v, low -> " ($v ${if (low) "low" else "high"})" },
    ): String {
        val displayBias = TempUtils.displayDelta(bias, useCelsius)
        val absBias = kotlin.math.abs(displayBias)
        val threshold = TempUtils.displayDelta(0.5, useCelsius)
        if (absBias < threshold) return ""
        val biasValue = "%.1f°".format(absBias)
        return formatSuffix(biasValue, displayBias > 0)
    }
}

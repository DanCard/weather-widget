package com.weatherwidget.shared.nowcast

import com.weatherwidget.shared.util.HourLabelFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What the next two hours of [NowcastSeries] say, in one of three shapes — or nothing, when it is
 * dry throughout (absence is the default; there is no "no rain" line).
 *
 * Times, never "in 12 min": the data has 15-minute steps and the widget repaints on its own
 * cadence, so a countdown would be wrong between paints.
 */
object NowcastSummary {
    /** mm per 15 min (0.4 mm/h): drizzle counts, model noise does not. */
    const val RAIN_SLOT_MM = 0.1f
    const val HORIZON_MS = 2L * 60 * 60 * 1000

    sealed interface Result {
        /** Dry now; rain from the start of a slot inside the horizon. */
        data class Starts(val atMs: Long) : Result
        /** Raining now; dry from the start of a slot inside the horizon. */
        data class Stops(val atMs: Long) : Result
        /** Raining now and through the whole horizon. */
        data object Continues : Result
    }

    fun summarize(series: NowcastSeries, nowMs: Long): Result? {
        val slots = series.slots
        val nowIndex = slots.indexOfFirst { nowMs >= it.startMs && nowMs < it.endMs }
        if (nowIndex < 0) return null
        val wet = smoothed(slots.map { it.mm >= RAIN_SLOT_MM })
        val horizonEnd = nowMs + HORIZON_MS
        val window = (nowIndex until slots.size).filter { slots[it].startMs < horizonEnd }
        return if (wet[nowIndex]) {
            window.firstOrNull { !wet[it] }?.let { Result.Stops(slots[it].startMs) }
                ?: Result.Continues.takeIf { window.isNotEmpty() && slots[window.last()].endMs >= horizonEnd }
        } else {
            window.firstOrNull { wet[it] }?.let { Result.Starts(slots[it].startMs) }
        }
    }

    /**
     * Hysteresis between refreshes: a lone dry slot inside rain does not end it, then a lone wet
     * slot between dry ones does not start a window. Gaps are filled first, so a shower with one
     * dry quarter in it stays one shower. The whole series is the context, so a wet slot at the
     * 2 h edge is judged by the slot after it.
     */
    internal fun smoothed(raw: List<Boolean>): List<Boolean> {
        val filled = raw.indices.map { i -> raw[i] || (raw.getOrNull(i - 1) == true && raw.getOrNull(i + 1) == true) }
        return filled.indices.map { i -> filled[i] && (filled.getOrNull(i - 1) != false || filled.getOrNull(i + 1) != false) }
    }

    /**
     * The header's compact form beside the rain %: `14:15–` (rain from), `–15:30` (rain until);
     * nothing for [Result.Continues] — the % already says it is raining. No words, so no
     * translations, and it stays narrow enough for the header's width fit.
     */
    fun headerText(
        result: Result?,
        use24HourClock: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? = when (result) {
        is Result.Starts -> formatTime(result.atMs, use24HourClock, zoneId, locale) + "–"
        is Result.Stops -> "–" + formatTime(result.atMs, use24HourClock, zoneId, locale)
        Result.Continues, null -> null
    }

    /** For logs and tooltips: "Rain from 14:15" / "Rain until 15:30" / "Rain for 2+ h". */
    fun describe(
        result: Result?,
        use24HourClock: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? = when (result) {
        is Result.Starts -> "Rain from " + formatTime(result.atMs, use24HourClock, zoneId, locale)
        is Result.Stops -> "Rain until " + formatTime(result.atMs, use24HourClock, zoneId, locale)
        Result.Continues -> "Rain for 2+ h"
        null -> null
    }

    /** "2:15pm" / "2pm" on a 12-hour clock (the hourly view's style), the locale's "14:15" on 24. */
    fun formatTime(atMs: Long, use24HourClock: Boolean, zoneId: ZoneId, locale: Locale): String {
        val time = Instant.ofEpochMilli(atMs).atZone(zoneId).toLocalTime()
        if (use24HourClock) {
            return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
        }
        val (hour, meridiem) = HourLabelFormatter.hourLabelParts(time.hour) // ("2", "p")
        val minutes = if (time.minute == 0) "" else String.format(Locale.US, ":%02d", time.minute)
        return hour + minutes + meridiem + "m"
    }
}

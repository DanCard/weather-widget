package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Hourly forecast past the routine horizon, fetched when a day is tapped.
 *
 * User's rule, 2026-10-09: Google fetches hourly [GoogleWeatherApi.FORECAST_HOURS] (72 h) ahead
 * routinely — the daily icon's noon cloud shading reads those hours — and a tapped day past that on
 * demand. Every `forecast/hours` page (24 h) is a billed request against a per-project daily quota,
 * so the later hours are fetched only when someone opens that day's hourly graph. Every other source
 * returns its whole hourly horizon in one free call and keeps it
 * (`plans/261009-google-hourly-on-demand-past-72h.md`).
 *
 * Google pages by token from the current hour, so reaching day N costs every page before it: next
 * week's Thursday is about 7 requests (the first 3 every routine fetch pays anyway).
 */
object HourlyOnDemand {
    private const val HOUR_MS = 3_600_000L

    /**
     * How far ahead hourly forecasts are loaded and fetched on demand: Google's `forecast/hours`
     * maximum, matching its 10-day daily forecast, so every future daily column can open a full
     * hourly day. Both platforms' hourly loaders read now + this (`WidgetQueryWindows`,
     * `DesktopWeatherRepository.loadCached`); it was 168 h, which left a tapped day 7–8 out with a
     * few hours and an empty graph (user chose 240 h, 2026-10-09).
     */
    const val REACH_HOURS = 240

    /**
     * Hours past the routine window ([extensionStartMs]) count as covering a tapped day only this long
     * after their fetch: routine fetches never refresh them. Older ones stay stored (the daily view
     * reads them) and a tap refetches.
     */
    const val MAX_EXTENSION_AGE_MS = 12 * HOUR_MS

    fun extendsHourly(sourceId: String): Boolean = sourceId == WeatherSource.GOOGLE_WEATHER.id

    /** A sync's deeper Google horizon for a tapped day past 72 h; null on every other sync. */
    data class Request(val sourceId: String, val hours: Int)

    /** The `forecast/hours` horizon a fetch of [sourceId] asks for: [request]'s, else the routine one. */
    fun hoursAhead(sourceId: String, request: Request?): Int =
        request?.takeIf { it.sourceId == sourceId }?.hours ?: GoogleWeatherApi.FORECAST_HOURS

    /**
     * First instant past [sourceId]'s routine window — where on-demand hours begin — or null for a
     * source that keeps its whole horizon.
     */
    fun extensionStartMs(sourceId: String, nowMs: Long): Long? =
        if (extendsHourly(sourceId)) currentHourMs(nowMs) + GoogleWeatherApi.FORECAST_HOURS * HOUR_MS else null

    /**
     * The `forecast/hours` horizon that covers [date] to its last hour, or null when no fetch is
     * needed or none can help: a source that does not extend, a day already covered by
     * [storedHourly] (this source's rows at the site — a row at or past the day's last wanted hour,
     * fetched within [MAX_EXTENSION_AGE_MS] when it lies past the routine window), a past day, or a
     * day starting past [REACH_HOURS].
     */
    fun hoursToCover(
        sourceId: String,
        date: LocalDate,
        zoneId: ZoneId,
        nowMs: Long,
        storedHourly: List<HourlyForecast>,
    ): Int? {
        val extensionStart = extensionStartMs(sourceId, nowMs) ?: return null
        val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val reachEndMs = nowMs + REACH_HOURS * HOUR_MS
        if (dayEndMs <= nowMs || dayStartMs >= reachEndMs) return null
        // The last hour that must be stored: the day's final hour, or the reach's.
        val lastWantedMs = minOf(dayEndMs, reachEndMs) - HOUR_MS
        val covered = storedHourly.any { row ->
            row.dateTime >= lastWantedMs &&
                (row.dateTime < extensionStart || nowMs - row.fetchedAt <= MAX_EXTENSION_AGE_MS)
        }
        if (covered) return null
        val hours = ceil((lastWantedMs - currentHourMs(nowMs)).toDouble() / HOUR_MS).toInt() + 1
        return hours.coerceIn(GoogleWeatherApi.FORECAST_HOURS, REACH_HOURS)
    }

    /**
     * The deeper horizon a tapped day's forced sync asks for (Android's no-hourly follow-up carries
     * the tapped date and the widget's display source): null for no date, an unparseable one, a
     * source that does not extend, or a day no fetch can help.
     */
    fun requestFor(sourceId: String?, dateStr: String?, zoneId: ZoneId, nowMs: Long): Request? {
        if (sourceId == null || dateStr == null) return null
        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return null
        return hoursToCover(sourceId, date, zoneId, nowMs, storedHourly = emptyList())?.let { Request(sourceId, it) }
    }

    /** What to do for the window the hourly view has settled on ([panAction]). */
    sealed interface PanAction {
        /** Fetch [hours] of hourly forecast (Google), which covers through [date], under the "Fetching…" banner. */
        data class Fetch(val date: LocalDate, val hours: Int) : PanAction

        /** [date] is a future day in view with no hourly that no fetch can help: say where the data ends. */
        data class NoDataMessage(val date: LocalDate) : PanAction

        data object Nothing : PanAction
    }

    /**
     * The hourly view settled on [windowStartMs]..[windowEndMs] by ‹ ›, a drag or reopening, not a
     * day tap (`plans/261009-hourly-pan-into-empty-day-fetches.md`). Every day the window shows
     * counts, not only its centre: a window from Wed 3 PM to Thu 7 AM centred on covered Wednesday
     * left Thursday's half blank with no word (Pixel, 2026-10-09).
     *
     * - **Fetch** for the *latest* future day in view that fresh stored hours do not cover (one fetch
     *   from now reaches every earlier day too).
     * - Else **NoDataMessage** for the earliest future day in view with no hourly at all.
     * - Else **Nothing**. Past days never trigger either.
     *
     * [storedHourly]: the display source's rows at the site. [hasHourlyForDay]:
     * `NoHourlyChecker.hasHourlyForDay` over what the view draws.
     */
    fun panAction(
        sourceId: String,
        windowStartMs: Long,
        windowEndMs: Long,
        zoneId: ZoneId,
        nowMs: Long,
        storedHourly: List<HourlyForecast>,
        hasHourlyForDay: (LocalDate) -> Boolean,
    ): PanAction {
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val first = maxOf(java.time.Instant.ofEpochMilli(windowStartMs).atZone(zoneId).toLocalDate(), today)
        val last = java.time.Instant.ofEpochMilli(windowEndMs).atZone(zoneId).toLocalDate()
        if (last.isBefore(first)) return PanAction.Nothing
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        days.asReversed().firstNotNullOfOrNull { day ->
            hoursToCover(sourceId, day, zoneId, nowMs, storedHourly)?.let { PanAction.Fetch(day, it) }
        }?.let { return it }
        return days.firstOrNull { !hasHourlyForDay(it) }?.let { PanAction.NoDataMessage(it) } ?: PanAction.Nothing
    }

    /** A drag or quick ‹ › run ends in one fetch, for where the view comes to rest. */
    const val PAN_SETTLE_MS = 1_000L

    private fun currentHourMs(nowMs: Long): Long = nowMs - Math.floorMod(nowMs, HOUR_MS)
}

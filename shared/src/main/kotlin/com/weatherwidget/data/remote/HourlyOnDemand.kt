package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Hourly forecast for a day the hourly view shows but the store does not cover — fetched when that
 * day is tapped or panned onto. One rule for every source; how far each reaches is data
 * ([HourlyHorizons]), never a source check here
 * (`plans/261009-on-demand-hourly-shared-single-source-fetch.md`).
 *
 * Every source stores 72 h routinely ([HourlyHorizons.ROUTINE_HOURS]; the daily view keeps its far
 * days' noon cloud and rain maxima on the forecast row, `DailyHourlySummaries`), so later hours are
 * fetched only when someone opens that day. Google serves 240 h at a billed page per 24 h; the free
 * sources return their whole horizon in one call and the save keeps it out to the requested day
 * (`performance/261010-daily-view-summaries-instead-of-far-hourly.md`).
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
     * How long a fetch vouches for what it stored. Hours past a source's routine window
     * ([extensionStartMs]) count as covering a day only this long after their fetch, since routine
     * fetches never refresh them. And a source fetched this recently that still does not reach a day
     * cannot be helped by fetching again.
     */
    const val MAX_EXTENSION_AGE_MS = 12 * HOUR_MS

    /** A fetch's deeper horizon for a tapped day's source; null on every other fetch. */
    data class Request(val sourceId: String, val hours: Int)

    /**
     * The horizon a fetch of [sourceId] asks for and stores: what the source stores routinely, or for
     * an on-demand [request] — [request]'s depth where reaching further costs requests (Google), else
     * the source's whole horizon. A free source's one call returns all of it anyway, and keeping it
     * all is what lets [hoursToCover] know a recent on-demand fetch already brought everything.
     */
    fun hoursAhead(sourceId: String, request: Request?): Int {
        val horizon = HourlyHorizons.of(sourceId)
        val requested = request?.takeIf { it.sourceId == sourceId } ?: return horizon.routineHours
        return if (horizon.costsPerExtraDay) requested.hours else horizon.maxHours
    }

    /**
     * First instant past [sourceId]'s routine window — where on-demand hours begin — or null for a
     * source that stores its whole horizon on every fetch.
     */
    fun extensionStartMs(sourceId: String, nowMs: Long): Long? {
        val horizon = HourlyHorizons.of(sourceId)
        return if (horizon.hasOnDemandRange) currentHourMs(nowMs) + horizon.routineHours * HOUR_MS else null
    }

    /**
     * The horizon that covers [date] to its last hour, or null when no fetch is needed or none can
     * help:
     * - a past day, or one starting past the source's [HourlyHorizon.maxHours];
     * - a day [storedHourly] covers (this source's rows at the site: a row at or past the day's last
     *   wanted hour, fetched within [MAX_EXTENSION_AGE_MS] when it lies past the routine window);
     * - a day inside the routine window when the source was fetched within [MAX_EXTENSION_AGE_MS]: its
     *   fresh data simply ends sooner, and fetching again would not change that;
     * - for a source whose extra days are free, any day once an on-demand fetch within
     *   [MAX_EXTENSION_AGE_MS] stored hours past the routine window: that fetch kept the source's whole
     *   horizon ([hoursAhead]), so its data ends where the API's does (NWS: ~150 h).
     */
    fun hoursToCover(
        sourceId: String,
        date: LocalDate,
        zoneId: ZoneId,
        nowMs: Long,
        storedHourly: List<HourlyForecast>,
    ): Int? {
        val horizon = HourlyHorizons.of(sourceId)
        val currentHour = currentHourMs(nowMs)
        val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val maxEndMs = currentHour + horizon.maxHours * HOUR_MS
        if (dayEndMs <= nowMs || dayStartMs >= maxEndMs) return null
        // The last hour that must be stored: the day's final hour, or the source's last.
        val lastWantedMs = minOf(dayEndMs, maxEndMs) - HOUR_MS
        val routineEndMs = currentHour + horizon.routineHours * HOUR_MS
        val covered = storedHourly.any { row ->
            row.dateTime >= lastWantedMs &&
                (row.dateTime < routineEndMs || nowMs - row.fetchedAt <= MAX_EXTENSION_AGE_MS)
        }
        if (covered) return null
        val newestFetchMs = storedHourly.maxOfOrNull { it.fetchedAt }
        val fetchedRecently = newestFetchMs != null && nowMs - newestFetchMs <= MAX_EXTENSION_AGE_MS
        if (lastWantedMs < routineEndMs && fetchedRecently) return null
        val wholeHorizonFresh = !horizon.costsPerExtraDay && storedHourly.any { row ->
            row.dateTime >= routineEndMs && nowMs - row.fetchedAt <= MAX_EXTENSION_AGE_MS
        }
        if (wholeHorizonFresh) return null
        val hours = ceil((lastWantedMs - currentHour).toDouble() / HOUR_MS).toInt() + 1
        return hours.coerceIn(horizon.routineHours, horizon.maxHours)
    }

    /** What to do for the window the hourly view has settled on ([panAction]). */
    sealed interface PanAction {
        /** Fetch [date]'s source ([hours] deep where the source takes a horizon) under the "Fetching…" banner. */
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

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId

/**
 * A source just became primary (Settings enable, or Android's migration): whether its cache can be
 * drawn as is, whether to say "Getting weather from {source}…" while it loads, whether the fetch is
 * forced, and the [TAG] lines — both platforms (plans/261006-source-becomes-primary-fetch-and-banner.md).
 * Was Android's `SourceSwitchFetch` and the desktop popup's source-switch effect: desktop's cache test
 * had no age limit, so a week-old cache counted as drawable and nothing was fetched or shown; the
 * lines carried different fields (audit plans/261023-duplicate-logic-audit-different-names.md §3).
 */
object SourceSwitchFetchRule {
    const val TAG = "SOURCE_SWITCH_FETCH"

    data class Start(val hasCache: Boolean, val banner: Boolean, val forced: Boolean)

    /**
     * The source's forecast was fetched within [LocationChangePaintPolicy.MAX_ADOPTABLE_CACHE_AGE_MS]
     * and today has hourly rows that fresh — the bar a location change sets. [dailyRows], when given,
     * must also hold today's row; Android passes null, its newest row at the site being that fresh.
     */
    fun hasDrawableCache(
        sourceId: String,
        lastForecastFetchMs: Long?,
        hourly: List<HourlyForecast>,
        today: LocalDate,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        dailyRows: Iterable<DailyForecast>? = null,
    ): Boolean {
        val oldest = nowMs - LocationChangePaintPolicy.MAX_ADOPTABLE_CACHE_AGE_MS
        if (lastForecastFetchMs == null || lastForecastFetchMs < oldest) return false
        if (dailyRows != null && !LocationChangePaintPolicy.hasTodayRow(dailyRows, today)) return false
        val own = hourly.filter { it.source == null || it.source == sourceId }
        return LocationChangePaintPolicy.hasFreshHourlyForToday(own, today, nowMs, zone)
    }

    /** No cache: banner (where there is somewhere to show it and a location to fetch) and a forced fetch. */
    fun start(hasCache: Boolean, hasLocation: Boolean, hasSurface: Boolean): Start =
        Start(hasCache = hasCache, banner = !hasCache && hasLocation && hasSurface, forced = !hasCache)

    fun startLine(sourceId: String, trigger: String, start: Start, extra: String = ""): String =
        "source=$sourceId trigger=$trigger cache=${start.hasCache} banner=${start.banner} forced=${start.forced}" +
            if (extra.isNotEmpty()) " $extra" else ""

    /** The fetch ended: its banner is cleared on success and failure alike. */
    fun finishLine(sourceId: String, succeeded: Boolean, reason: String, extra: String = ""): String =
        "action=${if (succeeded) "banner_cleared" else "banner_failed"} reason=$reason source=$sourceId" +
            if (extra.isNotEmpty()) " $extra" else ""

    fun finishLevel(succeeded: Boolean): String = if (succeeded) "INFO" else "WARN"
}

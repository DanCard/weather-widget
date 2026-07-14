package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId

/**
 * Re-derives the frozen `daily_history` rain-chance columns for days already archived with a
 * poisoned value.
 *
 * The freeze path used to compute its day/night max over RAW hourly rows — every GPS-jitter fragment
 * inside the proximity box — so `max` handed the archive whichever neighbouring fragment ran
 * highest (2026-07-13: a 9% fragment beat the real site's 4%, while the hourly graph, which selects
 * a site, drew 5%). [DailyRainLabels.resolveLiveDayNightChanceAtSite] fixes new writes; already-frozen
 * rows need this.
 *
 * **Why it can be re-derived at all, when the whole point of the freeze is to avoid hindcast drift:**
 * `hourly_forecast_history` is append-only and stamps every row with the `fetchedAt` of the snapshot
 * that produced it. Restricting to rows fetched BEFORE the window closed reconstructs exactly what
 * the live table held during the day — the same input the freeze pass saw, minus the fragments it
 * should never have read. It reproduces a past decision rather than making a new one with hindsight;
 * feed it rows fetched after the window closed and it would drift, which is why [dayWindowCloseMs] /
 * [nightWindowCloseMs] are hard cutoffs, not hints.
 */
object FrozenRainChanceRepair {

    /** Day chance freezes at 8pm on the day; night chance at 8am the following day. */
    fun dayWindowCloseMs(date: LocalDate, zoneId: ZoneId): Long =
        date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()

    fun nightWindowCloseMs(date: LocalDate, zoneId: ZoneId): Long =
        date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()

    /**
     * [history] is the raw `hourly_forecast_history` rows for the site's proximity box (all sources,
     * all fragments, all snapshots). Returns null day/night entries when history no longer covers the
     * window — callers MUST keep the existing archived value in that case rather than writing null,
     * since retention ages history out long before daily_history.
     */
    fun rederive(
        history: List<HourlyForecast>,
        displaySourceId: String,
        centerLat: Double,
        centerLon: Double,
        date: LocalDate,
        zoneId: ZoneId,
    ): DailyRainLabels.ResolvedDailyPrecip {
        val day = windowMax(history, displaySourceId, centerLat, centerLon, date, zoneId, dayWindowCloseMs(date, zoneId))
        val night = windowMax(history, displaySourceId, centerLat, centerLon, date, zoneId, nightWindowCloseMs(date, zoneId))
        return DailyRainLabels.ResolvedDailyPrecip(
            dayPrecip = day?.dayMax,
            nightPrecip = night?.nightMax,
        )
    }

    /**
     * Rebuilds the live hourly table as it stood just before [windowCloseMs]: drop snapshots taken
     * after the window closed, then let the site selector pick the freshest surviving row per hour —
     * exactly what the freeze pass would have read had it selected a site.
     */
    private fun windowMax(
        history: List<HourlyForecast>,
        displaySourceId: String,
        centerLat: Double,
        centerLon: Double,
        date: LocalDate,
        zoneId: ZoneId,
        windowCloseMs: Long,
    ): DailyRainLabels.DayNightPrecip? {
        val inWindow = history.filter { it.fetchedAt < windowCloseMs }
        if (inWindow.isEmpty()) return null
        val sited = DailyRainLabels.selectSiteHourly(inWindow, displaySourceId, centerLat, centerLon)
        if (sited.isEmpty()) return null
        return DailyRainLabels.calculateDayNightPrecipProbabilities(
            hourly = sited,
            targetDate = date,
            displaySourceId = displaySourceId,
            zoneId = zoneId,
        )
    }
}

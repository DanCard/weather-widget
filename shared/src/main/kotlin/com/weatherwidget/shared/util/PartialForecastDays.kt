package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch
import java.time.LocalDate

/**
 * What a daily column does with a forecast row that has only a high or only a low. NWS produces
 * both shapes routinely: it stops reporting today's low in the evening, and its last day is often
 * low-only. Shared by Android (`DailyTodayResolver`, `DailyActualsEstimator`,
 * `DailyViewLogic`) and desktop (`DesktopWeatherDao`, `DesktopDailyForecastModel`,
 * `DesktopWeatherRepository`). Desktop used to store such rows with low = high and repair any flat
 * day at read time instead. See plans/261002-share-nws-daily-pipeline-and-partial-days.md.
 */
object PartialForecastDays {

    /**
     * How far behind the newest stored row a complete row may be and still stand in for today's
     * partial one. NWS drops today's low in the evening, so that morning's complete row (hours old)
     * is the replacement meant here. Without a bound a week-old row won: a site first fetched on
     * Oct 2 drew that fetch's 90/74 for Oct 9 over the 69.5 fetched that day (2026-10-09).
     */
    const val COMPLETE_REPLACEMENT_MAX_AGE_MS = 24L * 3_600_000L

    /**
     * Today's partial row is replaced by the newest stored row for the day that has both values,
     * when that row is no more than [COMPLETE_REPLACEMENT_MAX_AGE_MS] older than the newest stored row.
     */
    fun <T> completeReplacement(
        candidates: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? {
        val newestMs = candidates.maxOfOrNull(fetchedAt) ?: return null
        return candidates
            .filter { high(it) != null && low(it) != null }
            .filter { newestMs - fetchedAt(it) <= COMPLETE_REPLACEMENT_MAX_AGE_MS }
            .maxByOrNull(fetchedAt)
    }

    /**
     * The row that stands for today's forecast, shared by Android (`DailyViewLogic`) and desktop
     * (`DesktopWeatherDao.getDailyForecasts`). [batchRow] is today's row in the newest fetch, or
     * null when that fetch has none. Silurian's daily output starts at the current UTC date, so
     * its evening batches skip local today. [storedRows] are the display source's stored rows for
     * today at this site. In order:
     * 1. [batchRow] when it has both values;
     * 2. the newest stored row with both (NWS stops reporting today's low in the evening), when
     *    fetched within [COMPLETE_REPLACEMENT_MAX_AGE_MS] of the newest stored row;
     * 3. the newest one-sided row, [batchRow] first (the column fills the other side from hourly);
     * 4. null, so the climate normal stays the last resort.
     * Desktop once read only the newest batch and drew the normal for Silurian's today
     * (plans/261004-desktop-today-column-climate-normal-when-batch-lacks-today.md).
     */
    fun <T> todayRow(
        batchRow: T?,
        storedRows: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? {
        if (batchRow != null && high(batchRow) != null && low(batchRow) != null) return batchRow
        return completeReplacement(storedRows, high, low, fetchedAt)
            ?: batchRow
            ?: storedRows.filter { high(it) != null || low(it) != null }.maxByOrNull(fetchedAt)
    }

    /**
     * [todayRow] at the anchor site, reaching into the nearest other site only when that leaves
     * today with no temperature at all. A move after [SameDayExtremeCutoff.HIGH_CUTOFF] makes a
     * site whose fetches may never carry today's values: the Fold moved ~1 km at 18:51 and drew the
     * observed range as today's forecast bar, while the old site's 15:21 row held 67.0 / 48.2
     * (2026-10-29). Those values were frozen before the cutoff, so they still stand for today.
     * Same-site rows always win: a nearby town's complete row once replaced a partial one and showed
     * its 84° high (2026-07-30). [storedRows] are the display source's rows for today in the
     * coarse box; the other site's row is bounded by [COMPLETE_REPLACEMENT_MAX_AGE_MS] against
     * the newest of them.
     */
    fun <T> todayRowNearestSite(
        batchRow: T?,
        storedRows: List<T>,
        anchorLat: Double,
        anchorLon: Double,
        latOf: (T) -> Double,
        lonOf: (T) -> Double,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? {
        val isAnchorSite = { row: T -> LocationMatch.sameSite(anchorLat, anchorLon, latOf(row), lonOf(row)) }
        val chosen = todayRow(batchRow, storedRows.filter(isAnchorSite), high, low, fetchedAt)
        if (chosen != null && (high(chosen) != null || low(chosen) != null)) return chosen
        val newestMs = storedRows.maxOfOrNull(fetchedAt) ?: return chosen
        val nearby = storedRows.filter {
            !isAnchorSite(it) && high(it) != null && low(it) != null &&
                newestMs - fetchedAt(it) <= COMPLETE_REPLACEMENT_MAX_AGE_MS
        }
        return LocationMatch.selectNearestSite(nearby, anchorLat, anchorLon, latOf, lonOf)
            .maxByOrNull(fetchedAt) ?: chosen
    }

    /** Today's forecast range: the daily value, else the day's hourly max / min. */
    fun todayForecastRange(
        dailyHigh: Float?,
        dailyLow: Float?,
        todayHourlyTemps: List<Float>,
    ): Pair<Float?, Float?> =
        (dailyHigh ?: todayHourlyTemps.maxOrNull()) to (dailyLow ?: todayHourlyTemps.minOrNull())

    /** A partial future day after [fillPartial]: its values and which of them came from the climate normal. */
    data class FutureValues(
        val high: Float?,
        val low: Float?,
        val isClimateOverlay: Boolean,
        val highIsClimateNormal: Boolean,
    )

    /**
     * A future day missing a value, given the climate normal for its date:
     * - a real low and no high (NWS's last day; OpenWeatherMap's cut-off last day, `OwmDailyCoverage`):
     *   if the source's already-available hourly forecast for that date reaches warmer than the
     *   climate normal's high ([hourlyHigh] > normal.first), the normal is guaranteed too low — use
     *   [hourlyHigh] as a normal forecast high (`highIsClimateNormal = false`, climbing as later
     *   fetches arrive). Otherwise keep the low and take the normal's high, [FutureValues.highIsClimateNormal],
     *   whose bar's top half is drawn green (user, 2026-11-24);
     * - any other partial day takes both values from the normal, [FutureValues.isClimateOverlay];
     * - no normal yet: unchanged (a low-only day draws its low alone until the normals are cached).
     * plans/261124-partial-high-days-shared-row-choice-claude.md
     */
    fun fillPartial(
        high: Float?,
        low: Float?,
        normal: Pair<Float, Float>?,
        hourlyHigh: Float? = null,
    ): FutureValues = when {
        high != null && low != null || normal == null ->
            FutureValues(high, low, isClimateOverlay = false, highIsClimateNormal = false)
        high == null && low != null ->
            if (hourlyHigh != null && hourlyHigh > normal.first) {
                FutureValues(hourlyHigh, low, isClimateOverlay = false, highIsClimateNormal = false)
            } else {
                FutureValues(normal.first, low, isClimateOverlay = false, highIsClimateNormal = true)
            }
        else ->
            FutureValues(normal.first, normal.second, isClimateOverlay = true, highIsClimateNormal = false)
    }

    /**
     * Every future day of [daily] through [fillPartial] with the normal for its date (and the
     * same source's available [hourly] max for that date). Both platforms run this over the rows
     * the daily view shows before building columns (was desktop's repository and Android's
     * `DailyFutureDayResolver`, 2026-10-23; the resolver was deleted 2026-11-24).
     */
    fun fillPartialFutureDays(
        daily: List<com.weatherwidget.data.model.DailyForecast>,
        normals: Map<java.time.MonthDay, Pair<Float, Float>>,
        today: LocalDate,
        hourly: List<com.weatherwidget.data.model.HourlyForecast> = emptyList(),
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): List<com.weatherwidget.data.model.DailyForecast> {
        val hourlyMaxByDateAndSource: Map<Pair<LocalDate, String?>, Float> = buildMap {
            for (h in hourly) {
                val d = java.time.Instant.ofEpochMilli(h.dateTime).atZone(zoneId).toLocalDate()
                if (!d.isAfter(today)) continue
                val key = d to h.source
                val prev = get(key)
                if (prev == null || h.temperature > prev) put(key, h.temperature)
            }
        }
        return daily.map { day ->
            val date = LocalDate.parse(day.date)
            if (!date.isAfter(today) || day.isClimateNormal) return@map day
            val hourlyHigh = hourlyMaxByDateAndSource[date to day.source]
            val values = fillPartial(day.highTemp, day.lowTemp, normals[java.time.MonthDay.from(date)], hourlyHigh)
            when {
                values.isClimateOverlay -> day.copy(highTemp = values.high, lowTemp = values.low, isClimateNormal = true)
                values.highIsClimateNormal -> day.copy(highTemp = values.high, highIsClimateNormal = true)
                values.high != day.highTemp -> day.copy(highTemp = values.high, highIsClimateNormal = false)
                else -> day
            }
        }
    }

    /**
     * Whether a future day has something to draw once [fillPartialFutureDays] ran: a low, with the
     * high real, from the normal, or (before the normals are cached) absent. A day with a high and no
     * low only draws once the normal fills it.
     */
    fun isDrawableFuture(low: Float?): Boolean = low != null
}

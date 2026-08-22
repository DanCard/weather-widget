package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Derives NWS daily high/low from a **dedicated, complete pull** of
 * `api.weather.gov/stations/{id}/observations?start=&end=`, rather than from whatever observation
 * rows happen to be in the database.
 *
 * The distinction is not academic. Measured 2026-08-08 at KNUQ: the endpoint returns ~72 readings
 * per day, but only 17-24 of them survive in storage as API rows — the rest of the day's stored
 * rows are Synoptic readings written by the prefer-newest latest-observation path
 * (`ObservationFallbackPolicy.FETCH_BOTH_ENABLED`). Computing the extreme from the stored API
 * subset under-reported the 08-05 and 08-06 peaks by 1.8 °F, because the retained samples simply
 * miss the afternoon maximum. Density, not window coverage, is what a daily extreme needs, and
 * [StationDailyExtremes]'s coverage guard cannot detect thin sampling.
 *
 * Produces **both** stored actuals for a past day from the same pull: the IDW blend
 * (`computedHighTemp`/`computedLowTemp`, all stations including personal, weighted by the user's
 * discount preference) and the single-station extreme (`apiHighTemp`/`apiLowTemp`, nearest official
 * station only). Every station is fetched — the blend interpolates across all of them, so stopping
 * early would change the answer rather than merely save a request.
 *
 * `/stations/{id}/observations` has no server-side aggregation, so the readings must be pulled and
 * reduced client-side. Nothing is persisted; the pool is discarded after reduction.
 *
 * Requests are issued **one calendar day at a time**. The endpoint caps a response at 500 features
 * and returns the *newest* ones, so a single request spanning several days silently drops the
 * earliest: measured 2026-08-08, `KSJC?start=-7d` returned 500 rows covering only the most recent
 * 3 days, and `KNUQ?start=-7d` hit the cap at 8 partial days. A truncated day is worse than a
 * missing one — it can still satisfy the coverage guard while missing the peak. Per-day windows
 * stay far below the cap (busiest station observed: ~300/day).
 *
 * [StationDailyExtremes] picks which station wins and applies the coverage guard, so the
 * "nearest official station" rule stays in one place.
 */
object NwsDailyExtremesFetch {

    /**
     * The observations endpoint answers reliably about a week back; beyond that a date is simply
     * unrecoverable and its day is left without an actual.
     */
    const val MAX_LOOKBACK_DAYS = 7L

    private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Both stored actuals for a past day, from one day's pull.
     *
     * @param blendHigh IDW blend over **every** station in the pull, personal ones included and
     *   discounted by the user's preference. Backs `computedHighTemp`.
     * @param station nearest OFFICIAL station's raw min/max, or null when none passes the coverage
     *   guard. Backs `apiHighTemp`/`apiLowTemp`. A day can have a blend but no station extreme —
     *   personal stations contribute to the former and are barred from the latter.
     */
    data class DailyActualsFromStations(
        val blendHigh: Float,
        val blendLow: Float,
        val station: StationDailyExtremes.StationDailyExtreme?,
    )

    /**
     * Per-date outcome. [Insufficient] and [Unavailable] must not be conflated: the first means the
     * endpoint answered and simply does not have enough of that day left, which is permanent and
     * worth falling back to our stored observations for; the second means a request failed, which a
     * retry may fix. Treating a network blip as insufficiency would lock a cached value in over a
     * live one that would have worked next cycle.
     */
    sealed interface DayOutcome {
        data class Resolved(val actuals: DailyActualsFromStations) : DayOutcome

        /** Every request succeeded, but the returned readings do not span the day. */
        data object Insufficient : DayOutcome

        /** At least one station request failed, so the pool may be incomplete for the wrong reason. */
        data object Unavailable : DayOutcome
    }

    /**
     * @param datesEpochDayMs UTC-midnight-keyed local dates needing actuals (`daily_history.date`).
     * @param stationIdsNearestFirst candidate stations, nearest first, **including personal ones** —
     *   the blend needs them (weighted by [personalStationWeight]) even though they can never
     *   supply the api actual.
     * @param fetchStationDay pulls ONE station over ONE calendar day, returning readings enriched
     *   with `distanceKm`/`stationType` from the station list (the API response alone carries
     *   neither). Return **null** when the request itself failed and an empty list when it
     *   succeeded with nothing to report — the two lead to different outcomes.
     * @param hourlyForecastsForDay feeds `extrapolateForward`; a complete series has few gaps, so
     *   this rarely matters, but it keeps the blend identical to the live path's math.
     * @return one [DayOutcome] per in-range date. Dates outside the lookback window, today, and the
     *   future are absent and cost no request.
     */
    suspend fun resolveForDates(
        datesEpochDayMs: List<Long>,
        stationIdsNearestFirst: List<String>,
        userLat: Double,
        userLon: Double,
        personalStationWeight: Double,
        zone: ZoneId,
        nowMs: Long,
        hourlyForecastsForDay: suspend (dayStartMs: Long, dayEndMs: Long) -> List<HourlyForecast> = { _, _ -> emptyList() },
        fetchStationDay: suspend (stationId: String, startIso: String, endIso: String) -> List<ObservationReading>?,
    ): Map<Long, DayOutcome> {
        if (datesEpochDayMs.isEmpty() || stationIdsNearestFirst.isEmpty()) return emptyMap()

        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val earliest = today.minusDays(MAX_LOOKBACK_DAYS)
        val dates = datesEpochDayMs
            .distinct()
            .map { it to LocalDate.ofEpochDay(it / 86_400_000L) }
            .filter { (_, date) -> !date.isBefore(earliest) && date.isBefore(today) }
            .sortedBy { (_, date) -> date }
        if (dates.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, DayOutcome>()
        for ((epochMs, date) in dates) {
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val startIso = ISO_INSTANT.format(dayStart)
            val endIso = ISO_INSTANT.format(dayEnd)

            // Every station, not just until one qualifies: the blend is an interpolation across all
            // of them, so short-circuiting would change the answer rather than just save a request.
            val perStation = stationIdsNearestFirst.map { fetchStationDay(it, startIso, endIso) }
            if (perStation.any { it == null }) {
                // A failed request leaves the pool short for a reason a retry may fix. Reporting
                // Insufficient here would let one network blip permanently substitute a cached
                // value for a live one.
                result[epochMs] = DayOutcome.Unavailable
                continue
            }
            val pool = perStation.filterNotNull().flatten()

            // A PARTIAL day must not overwrite a good stored blend. The endpoint's retention is a
            // rolling window from now, so the oldest day in range is sliced off at the current
            // wall-clock hour: measured 2026-08-08 09:00, every station's 2026-08-01 series began
            // at hour 09 and the overnight minimum had aged out. Blending that wrote a low 5.18 °F
            // too warm over a correct value. The station extreme is already protected by
            // StationDailyExtremes' per-station guard; this is the pool-level equivalent for the
            // blend, which has no guard of its own. Personal stations count here — the blend is
            // allowed to rest on them even though the api actual is not.
            if (pool.isEmpty() || !poolCoversDay(pool, dayStart.toEpochMilli(), dayEnd.toEpochMilli(), zone)) {
                result[epochMs] = DayOutcome.Insufficient
                continue
            }

            val blend = blendOverPool(
                pool = pool,
                hourly = hourlyForecastsForDay(dayStart.toEpochMilli(), dayEnd.toEpochMilli()),
                userLat = userLat,
                userLon = userLon,
                personalStationWeight = personalStationWeight,
                dateEpochDayMs = epochMs,
                zone = zone,
                nowMs = nowMs,
            )
            if (blend == null) {
                result[epochMs] = DayOutcome.Insufficient
                continue
            }

            result[epochMs] = DayOutcome.Resolved(
                DailyActualsFromStations(
                    blendHigh = blend.first,
                    blendLow = blend.second,
                    station = StationDailyExtremes.resolve(
                        observations = pool,
                        sourceId = "NWS",
                        dayStartMs = dayStart.toEpochMilli(),
                        dayEndMs = dayEnd.toEpochMilli(),
                        zone = zone,
                    ),
                ),
            )
        }
        return result
    }

    /**
     * True when the pulled readings span both ends of the day, so a blend over them can actually
     * contain the day's high and low. Uses [StationDailyExtremes]' windows so the two guards can't
     * drift apart; unlike that one this is pool-wide and accepts any station type.
     */
    private fun poolCoversDay(
        pool: List<ObservationReading>,
        dayStartMs: Long,
        dayEndMs: Long,
        zone: ZoneId,
    ): Boolean {
        var coversLow = false
        var coversHigh = false
        for (reading in pool) {
            if (reading.timestamp < dayStartMs || reading.timestamp >= dayEndMs) continue
            val hour = Instant.ofEpochMilli(reading.timestamp).atZone(zone).hour
            if (hour in StationDailyExtremes.LOW_WINDOW_START_HOUR until StationDailyExtremes.LOW_WINDOW_END_HOUR) coversLow = true
            if (hour in StationDailyExtremes.HIGH_WINDOW_START_HOUR until StationDailyExtremes.HIGH_WINDOW_END_HOUR) coversHigh = true
            if (coversLow && coversHigh) return true
        }
        return false
    }

    /**
     * Delegates to [ActualsAggregator.aggregate] rather than reimplementing the IDW math, so the
     * history blend and the live blend cannot drift. The only difference between them is the input
     * pool: a complete API series here, the stored observation table there.
     */
    private fun blendOverPool(
        pool: List<ObservationReading>,
        hourly: List<HourlyForecast>,
        userLat: Double,
        userLon: Double,
        personalStationWeight: Double,
        dateEpochDayMs: Long,
        zone: ZoneId,
        nowMs: Long,
    ): Pair<Float, Float>? =
        ActualsAggregator.aggregate(
            observations = pool,
            hourlyForecasts = hourly,
            locationLat = userLat,
            locationLon = userLon,
            zoneId = zone,
            updatedAtMs = nowMs,
            personalStationWeight = personalStationWeight,
        )
            .firstOrNull { it.source == "NWS" && it.date == dateEpochDayMs }
            ?.let { row ->
                // A forecast-only aggregation row (no observations) has null extremes — that is
                // not an actual, so yield no pair rather than a null-poisoned one.
                val high = row.computedHighTemp
                val low = row.computedLowTemp
                if (high != null && low != null) high to low else null
            }
}

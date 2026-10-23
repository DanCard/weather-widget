package com.weatherwidget.data.repository

import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.shared.actuals.NwsDailyExtremesFetch
import com.weatherwidget.shared.actuals.StationDailyExtremes
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.handlers.GraphDataLoader
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the NWS daily actuals that come from the dedicated
 * `/stations/{id}/observations` pull (see [NwsApiDailyActualsFetcher]) and its cached-observation
 * fallback. Extracted from [DailyActualsStore] so that store only owns the live-today read path and
 * the blend recompute, and this store owns the station-derived `apiHighTemp`/`apiLowTemp` columns.
 */
@Singleton
class NwsStationActualsStore @Inject constructor(
    private val dailyHistoryDao: DailyHistoryDao,
    private val observationDao: ObservationDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val appLogDao: AppLogDao,
) {
    /**
     * Returns the NWS daily_history dates in range that still have no station-derived actual.
     * Drives [NwsApiDailyActualsFetcher]; idempotent, so a date drops out once filled.
     */
    suspend fun findNwsDatesMissingStationActuals(
        latitude: Double,
        longitude: Double,
        startMs: Long,
        endMs: Long,
    ): List<Long> =
        dailyHistoryDao
            .getExtremesInRange(startMs, endMs, latitude, longitude)
            // Also catches rows whose VALUES are fine but whose provenance is missing. A repair
            // keyed only on values can never fix a row like that — observed on the Pixel, where
            // 2026-08-06 kept correct api temps with a null actualsSource forever because it was
            // not "missing" by the old predicate.
            .map { it.toDailyHistory() }
            .let(com.weatherwidget.shared.actuals.NwsStationActualsRun::missingDates)

    /** `daily_history` rows at the site in [startMs, endMs] — the station-actuals run's read. */
    suspend fun dailyHistoryInRange(latitude: Double, longitude: Double, startMs: Long, endMs: Long) =
        dailyHistoryDao.getExtremesInRange(startMs, endMs, latitude, longitude).map { it.toDailyHistory() }

    suspend fun upsertDailyHistory(rows: List<com.weatherwidget.data.model.DailyHistory>) =
        dailyHistoryDao.insertAll(rows.map { it.toEntity() })

    /** Our retained observations at the site in [startMs, endMs), every api (station selection is the rule's). */
    internal suspend fun storedObservations(latitude: Double, longitude: Double, startMs: Long, endMs: Long) =
        observationDao.getObservationsInRange(startMs, endMs, latitude, longitude, apis = null).map { it.toReading() }

    /**
     * NWS hourly rows for one day, collapsed to a single coordinate site.
     *
     * The raw proximity-box query spans every cached site in the box, and feeding un-collapsed
     * fragments into a blend is the coordinate-fragmentation bug family that
     * `HourlyProximityQueryAllowlistTest` exists to police — so the read stays behind
     * `GraphDataLoader.unifyToNearestSite`.
     */
    internal suspend fun nwsHourlyForecastsForDay(
        latitude: Double,
        longitude: Double,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<com.weatherwidget.data.model.HourlyForecast> =
        GraphDataLoader.unifyToNearestSite(
            hourlyForecastDao.getHourlyForecasts(dayStartMs, dayEndMs, latitude, longitude),
            latitude,
            longitude,
        )
            .filter { it.source == WeatherSource.NWS.id }
            .map { it.toHourlyForecast() }

    /**
     * Writes station-derived daily extremes onto the NWS rows for the given dates.
     *
     * Applies to every same-date NWS fragment in the proximity box rather than only the nearest.
     * The value describes a *station*, not a coordinate, so it is equally true for each fragment of
     * the same site — and leaving stale fragments unfilled is what let a partial row shadow a
     * complete one in the first place (see ApiActualPicker).
     */
    suspend fun persistNwsDailyActuals(
        latitude: Double,
        longitude: Double,
        actualsByDate: Map<Long, NwsDailyExtremesFetch.DailyActualsFromStations>,
    ) {
        if (actualsByDate.isEmpty()) return
        val now = System.currentTimeMillis()
        val dates = actualsByDate.keys.sorted()

        val toUpsert = dailyHistoryDao
            .getExtremesInRange(dates.first(), dates.last(), latitude, longitude)
            .filter { it.source == WeatherSource.NWS.id }
            .mapNotNull { row ->
                val actuals = actualsByDate[row.date] ?: return@mapNotNull null
                com.weatherwidget.shared.actuals.NwsStationActualsRun.applyPulled(row.toDailyHistory(), actuals, now)?.toEntity()
            }

        if (toUpsert.isEmpty()) return
        dailyHistoryDao.insertAll(toUpsert)
        appLogDao.log(
            "NWS_STATION_ACTUALS",
            "rows=${toUpsert.size} dates=${dates.size} " +
                actualsByDate.entries.sortedBy { it.key }.joinToString(" ") { (date, a) ->
                    "${LocalDate.ofEpochDay(date / WidgetConstants.MS_IN_A_DAY)}=" +
                        "blend[${a.blendHigh}/${a.blendLow}]" +
                        (a.station?.let { "${it.stationId}[${it.high}/${it.low} n=${it.readingCount}]" } ?: "no-station")
                },
            "DEBUG",
        )
    }

    /**
     * Derives the single-station extreme for [date] from our **retained** `observations` rows,
     * for a day the endpoint can no longer serve completely.
     *
     * Same [StationDailyExtremes.resolve] rule as the live pull — nearest official station, same
     * coverage guard, same exclusions — only the pool differs. The stored pool includes Synoptic
     * rows; for KNUQ those are the same ASOS METARs redistributed, and the stored union reproduced
     * the endpoint's extremes exactly on 2026-08-05/06/07, whereas restricting to
     * `isWebFallback = 0` leaves 17-24 of 72 readings and under-reports peaks by 1.8 °F.
     * `actualsSource` discloses the difference rather than hiding it.
     */
    internal suspend fun stationExtremeFromStoredObservations(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StationDailyExtremes.StationDailyExtreme? {
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stored = observationDao
            // Unscoped: StationDailyExtremes.resolve does its own station-level selection over the
            // whole day's pool, and this store feeds the api-actuals column. Left as it was.
            .getObservationsInRange(dayStartMs, dayEndMs, latitude, longitude, apis = null)
            .map { it.toReading() }
        return StationDailyExtremes.resolve(
            observations = stored,
            sourceId = WeatherSource.NWS.id,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            zone = zone,
        )
    }

    /**
     * Writes a cache-derived api actual. Deliberately leaves `computedHighTemp`/`computedLowTemp`
     * alone — the blend already comes from the stored pool via the ordinary recompute, so there is
     * nothing here to improve on and overwriting it would only add churn.
     */
    suspend fun persistCachedStationActuals(
        latitude: Double,
        longitude: Double,
        extremesByDate: Map<Long, StationDailyExtremes.StationDailyExtreme>,
    ) {
        if (extremesByDate.isEmpty()) return
        val now = System.currentTimeMillis()
        val dates = extremesByDate.keys.sorted()

        val toUpsert = dailyHistoryDao
            .getExtremesInRange(dates.first(), dates.last(), latitude, longitude)
            .filter { it.source == WeatherSource.NWS.id }
            .mapNotNull { row ->
                val station = extremesByDate[row.date] ?: return@mapNotNull null
                com.weatherwidget.shared.actuals.NwsStationActualsRun.applyCached(row.toDailyHistory(), station, now)?.toEntity()
            }

        if (toUpsert.isEmpty()) return
        dailyHistoryDao.insertAll(toUpsert)
        appLogDao.log(
            "NWS_STATION_ACTUALS_CACHED",
            "rows=${toUpsert.size} dates=${dates.size} " +
                extremesByDate.entries.sortedBy { it.key }.joinToString(" ") { (date, s) ->
                    "${LocalDate.ofEpochDay(date / WidgetConstants.MS_IN_A_DAY)}=" +
                        "${s.stationId}[hi=${s.high} lo=${s.low} n=${s.readingCount}]"
                },
            "DEBUG",
        )
    }
}

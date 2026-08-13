package com.weatherwidget.data.repository

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
            .filter {
                it.source == WeatherSource.NWS.id &&
                    (it.apiHighTemp == null || it.apiLowTemp == null || it.actualsSource == null)
            }
            .map { it.date }
            .distinct()

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
                val updated = row.copy(
                    // The blend is only ever overwritten by a pull that produced one; a date whose
                    // pull came back empty is absent from the map and never reaches here, so a good
                    // stored blend can't be replaced by a worse one.
                    computedHighTemp = actuals.blendHigh,
                    computedLowTemp = actuals.blendLow,
                    // A day can have a blend but no station extreme — personal stations feed the
                    // former and are barred from the latter. Keep whatever was stored in that case.
                    apiHighTemp = actuals.station?.high ?: row.apiHighTemp,
                    apiLowTemp = actuals.station?.low ?: row.apiLowTemp,
                    apiStationId = actuals.station?.stationId ?: row.apiStationId,
                    apiStationDistanceKm = actuals.station?.distanceKm ?: row.apiStationDistanceKm,
                    actualsSource = DailyActualsSource.NWS_STATION_PULL.storedValue,
                    lastWriter = DailyHistoryWriter.NWS_STATION_PULL.storedValue,
                    updatedAt = now,
                )
                updated.takeIf { it.copy(updatedAt = row.updatedAt) != row }
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
            .getObservationsInRange(dayStartMs, dayEndMs, latitude, longitude)
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
                val updated = row.copy(
                    apiHighTemp = station.high,
                    apiLowTemp = station.low,
                    apiStationId = station.stationId,
                    apiStationDistanceKm = station.distanceKm,
                    actualsSource = DailyActualsSource.CACHED_OBSERVATIONS.storedValue,
                    lastWriter = DailyHistoryWriter.CACHED_STATION_FALLBACK.storedValue,
                    updatedAt = now,
                )
                updated.takeIf { it.copy(updatedAt = row.updatedAt) != row }
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

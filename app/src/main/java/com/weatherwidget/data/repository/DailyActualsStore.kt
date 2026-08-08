package com.weatherwidget.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.shared.actuals.StationDailyExtremes
import com.weatherwidget.shared.actuals.NwsDailyExtremesFetch
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.handlers.GraphDataLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DailyActualsStore"
private const val DAYTIME_COVERAGE_HOUR = 14

@VisibleForTesting
internal fun pastDayLacksAfternoonCoverage(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    today: LocalDate,
    daytimeHour: Int = DAYTIME_COVERAGE_HOUR,
): Boolean {
    if (!date.isBefore(today) || obsTimestampsMs.isEmpty()) return false
    return obsTimestampsMs.none { ms ->
        Instant.ofEpochMilli(ms).atZone(zone).hour >= daytimeHour
    }
}

@Singleton
class DailyActualsStore @Inject constructor(
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val personalStationWeightProvider: PersonalStationWeightProvider,
) {
    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
    ): DailyActualsBySource {
        val activeSources = activeSourceList.toSet()
        if (activeSources.isEmpty()) return emptyMap()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startDate = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastExtremes = dailyHistoryDao
            .getExtremesInRange(startDate, endDate, latitude, longitude)
            .filter { it.source in activeSources }
        val pastActuals = ObservationResolver.extremesToDailyActualsBySource(pastExtremes, latitude, longitude)

        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val contextObs = observationDao
            .getObservationsInRange(
                todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS,
                tomorrowMs,
                latitude,
                longitude,
            )
            .filter { it.stationId != "NWS_BLEND" && it.api in activeSources }
        val activeHourly = hourlyForecasts.filter { it.source in activeSources }
        val todayObs = contextObs.filter { it.timestamp in todayStartMs until tomorrowMs }

        val todayBlendedActuals = ObservationResolver.aggregateObservationsToDailyBySource(
            observations = contextObs,
            hourlyForecasts = activeHourly,
            locationLat = latitude,
            locationLon = longitude,
            personalStationWeight = personalStationWeightProvider.currentWeight(),
        )

        val obsSpanSummary =
            if (todayObs.isEmpty()) {
                "none"
            } else {
                val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                val firstLocal = Instant.ofEpochMilli(todayObs.minOf { it.timestamp })
                    .atZone(zone)
                    .toLocalDateTime()
                    .format(formatter)
                val lastLocal = Instant.ofEpochMilli(todayObs.maxOf { it.timestamp })
                    .atZone(zone)
                    .toLocalDateTime()
                    .format(formatter)
                "$firstLocal..$lastLocal"
            }
        val liveSummary = todayBlendedActuals
            .toSortedMap()
            .entries
            .joinToString("; ") { (source, actualsByDate) ->
                val actual = actualsByDate[today]
                val stationCount = todayObs.count { it.api == source }
                "$source[blendedHigh=${actual?.computedHighTemp},blendedLow=${actual?.computedLowTemp},rows=$stationCount]"
            }
            .ifEmpty { "none" }
        Log.d(
            TAG,
            "getDailyActualsWithLiveToday: date=$today lat=$latitude lon=$longitude " +
                "todayObsRows=${todayObs.size} span=$obsSpanSummary live=[$liveSummary]",
        )

        return ObservationResolver.mergeDailyActualsBySource(
            primary = pastActuals,
            secondary = todayBlendedActuals,
        )
    }

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val cutoffDate = LocalDate.now().minusDays(9)
        var current = startDate
        while (!current.isAfter(endDateInclusive)) {
            if (!current.isBefore(cutoffDate)) {
                recomputeDailyExtremesForDay(latitude, longitude, current, hourlyForecasts)
            } else {
                Log.d(TAG, "recomputeDailyExtremesFromStoredObservations: skipping pruned date $current")
            }
            current = current.plusDays(1)
        }
    }

    internal suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val zone = ZoneId.systemDefault()
        val dateMillis = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val startTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val contextStartTs = startTs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextEndTs = endTs + ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextObs = observationDao.getObservationsInRange(
            contextStartTs,
            contextEndTs,
            latitude,
            longitude,
        )
        val dayObs = contextObs.filter { it.timestamp in startTs until endTs }
        if (dayObs.isEmpty()) return

        val effectiveHourly = hourlyForecasts.ifEmpty {
            GraphDataLoader.unifyToNearestSite(
                hourlyForecastDao.getHourlyForecasts(
                    contextStartTs,
                    contextEndTs,
                    latitude,
                    longitude,
                ),
                latitude,
                longitude,
            )
        }
        val newExtremes = ObservationResolver
            .computeDailyExtremes(
                contextObs,
                effectiveHourly,
                latitude,
                longitude,
                personalStationWeightProvider.currentWeight(),
            )
            .filter { it.date == dateMillis }
        val existingHistory = dailyHistoryDao
            .getExtremesInRange(dateMillis, dateMillis, latitude, longitude)
            .groupBy { it.source }

        // NOTE: apiHighTemp/apiLowTemp are deliberately NOT derived here. The stored observation
        // pool is a mix of NWS API rows and Synoptic rows from the prefer-newest latest path, and
        // its API subset is too thinly sampled to carry a daily peak (measured at KNUQ: 17-24 of
        // the endpoint's 72 readings, under-reporting two days' maxima by 1.8 °F). NWS daily
        // extremes come from a dedicated complete pull instead — see NwsApiDailyActualsFetcher.
        // persistExtremes preserves whatever that writer stored.

        logBlendBreakdown(date, dayObs, newExtremes, latitude, longitude)
        logExtremaWindowDiagnostic(
            date = date,
            zone = zone,
            startTs = startTs,
            endTs = endTs,
            dayObs = dayObs,
            effectiveHourly = effectiveHourly,
            latitude = latitude,
            longitude = longitude,
        )

        persistExtremes(date, newExtremes, existingHistory)
    }

    private suspend fun logBlendBreakdown(
        date: LocalDate,
        dayObservations: List<com.weatherwidget.data.local.ObservationEntity>,
        newExtremes: List<DailyHistoryEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        val perSourceBreakdown = dayObservations
            .groupBy { it.api }
            .mapValues { (_, sourceObservations) ->
                sourceObservations.groupBy { it.stationId }.entries.joinToString(",") { (stationId, observations) ->
                    val distance = observations.first().distanceKm
                    val high = observations.maxOf { it.temperature }
                    val low = observations.minOf { it.temperature }
                    "$stationId(d=${"%.2f".format(distance)}km,hi=$high,lo=$low,n=${observations.size})"
                }
            }
        newExtremes.forEach { new ->
            appLogDao.log(
                "DAILY_HISTORY_BLEND",
                "date=$date src=${new.source} computed_hi=${new.computedHighTemp} computed_lo=${new.computedLowTemp} " +
                    "stations=[${perSourceBreakdown[new.source] ?: "n/a"}] " +
                    "userLat=$latitude userLon=$longitude",
                "VERBOSE",
            )
        }
    }

    private suspend fun persistExtremes(
        date: LocalDate,
        newExtremes: List<DailyHistoryEntity>,
        existingHistory: Map<String, List<DailyHistoryEntity>>,
    ) {
        val toInsert = mutableListOf<DailyHistoryEntity>()
        newExtremes.forEach { new ->
            val fragments = existingHistory[new.source].orEmpty()
            if (fragments.isEmpty()) {
                toInsert.add(new)
                return@forEach
            }
            var changedAny = false
            fragments.forEach { existing ->
                // Build the row we would write, then compare it whole. Enumerating the fields to
                // compare is how precip-only deltas were silently dropped before (see
                // recomputeDailyExtremesForDay's precip gate); comparing the merged candidate
                // against `existing` cannot go stale when a column is added.
                // A past day whose blend came from the NWS station pull is frozen. Without this the
                // ordinary recompute — which runs on widget loads and history-screen opens — would
                // immediately overwrite the API-derived blend with one rebuilt from the stored
                // (part-Synoptic, thinner) pool. Today's row is never frozen; its blend must stay
                // live. CACHED_OBSERVATIONS rows are NOT frozen: their blend already comes from the
                // stored pool, so the recompute is its rightful owner and can keep improving it as
                // observations backfill.
                val freezeBlend = DailyActualsSource.fromStored(existing.actualsSource) ==
                    DailyActualsSource.NWS_STATION_PULL && date.isBefore(LocalDate.now())
                val merged = new.copy(
                    computedHighTemp = if (freezeBlend) existing.computedHighTemp else new.computedHighTemp,
                    computedLowTemp = if (freezeBlend) existing.computedLowTemp else new.computedLowTemp,
                    locationLat = existing.locationLat,
                    locationLon = existing.locationLon,
                    forecastDayPrecipChance = existing.forecastDayPrecipChance,
                    forecastNightPrecipChance = existing.forecastNightPrecipChance,
                    forecastHighTemp = existing.forecastHighTemp,
                    forecastLowTemp = existing.forecastLowTemp,
                    forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                    noonCloudPercent = existing.noonCloudPercent,
                    // Keep the stored api actual only when this recompute produced none — e.g.
                    // Open-Meteo, whose ERA5 values come from persistOpenMeteoPastDayActuals and
                    // have no station behind them, or an NWS day where every official station
                    // failed the coverage guard.
                    apiHighTemp = new.apiHighTemp ?: existing.apiHighTemp,
                    apiLowTemp = new.apiLowTemp ?: existing.apiLowTemp,
                    apiStationId = new.apiStationId ?: existing.apiStationId,
                    apiStationDistanceKm = new.apiStationDistanceKm ?: existing.apiStationDistanceKm,
                    // Must be carried: dropping it both loses the provenance and silently disables
                    // the freeze guard, which reads this very field on the NEXT recompute.
                    actualsSource = new.actualsSource ?: existing.actualsSource,
                    updatedAt = existing.updatedAt,
                )
                if (merged != existing) {
                    changedAny = true
                    appLogDao.log(
                        "DAILY_HISTORY_OVERWRITE",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} " +
                            "high=${existing.computedHighTemp}->${new.computedHighTemp} low=${existing.computedLowTemp}->${new.computedLowTemp} " +
                            "precip=${existing.precipAmountMm}->${new.precipAmountMm} " +
                            "apiHigh=${existing.apiHighTemp}->${merged.apiHighTemp} station=${merged.apiStationId}",
                        "DEBUG",
                    )
                    toInsert.add(merged.copy(updatedAt = new.updatedAt))
                }
            }
            if (!changedAny) {
                appLogDao.log(
                    "DAILY_HISTORY_STABLE",
                    "date=$date src=${new.source} high=${new.computedHighTemp} low=${new.computedLowTemp} fragments=${fragments.size}",
                    "DEBUG",
                )
            }
        }

        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
    }

    internal suspend fun incompletelyCoveredPastDates(
        dayKeyEpochs: Set<Long>,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        today: LocalDate,
    ): Set<Long> =
        dayKeyEpochs.filterTo(mutableSetOf()) { dayKeyEpoch ->
            val date = LocalDate.ofEpochDay(dayKeyEpoch / WidgetConstants.MS_IN_A_DAY)
            if (!date.isBefore(today)) return@filterTo false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val timestamps = observationDao
                .getObservationsInRange(dayStart, dayEnd, latitude, longitude)
                .filter { it.api == WeatherSource.NWS.id && it.stationId != "NWS_BLEND" }
                .map { it.timestamp }
            pastDayLacksAfternoonCoverage(timestamps, date, zone, today)
        }

    private suspend fun logExtremaWindowDiagnostic(
        date: LocalDate,
        zone: ZoneId,
        startTs: Long,
        endTs: Long,
        dayObs: List<com.weatherwidget.data.local.ObservationEntity>,
        effectiveHourly: List<HourlyForecastEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        try {
            val hourlyReadings = effectiveHourly.map { it.toHourlyForecast() }
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            fun probe(observations: List<ObservationReading>, start: Long, end: Long): String {
                val series = ActualTemperatureSeriesBuilder
                    .blendObservationSeries(
                        observations = observations,
                        hourlyForecasts = hourlyReadings,
                        displaySourceId = WeatherSource.NWS.id,
                        userLat = latitude,
                        userLon = longitude,
                        startMs = start,
                        endMs = end,
                        personalStationWeight = personalStationWeightProvider.currentWeight(),
                    )
                    .observations
                    .filter { it.timestamp in startTs until endTs }
                if (series.isEmpty()) return "empty"
                val high = series.maxBy { it.temperature }
                val low = series.minBy { it.temperature }
                return "hi=${"%.2f".format(high.temperature)}@" +
                    "${Instant.ofEpochMilli(high.timestamp).atZone(zone).format(formatter)} " +
                    "lo=${"%.2f".format(low.temperature)}@" +
                    "${Instant.ofEpochMilli(low.timestamp).atZone(zone).format(formatter)} pts=${series.size}"
            }

            val nwsDayObs = dayObs.filter { it.api == WeatherSource.NWS.id }.map { it.toReading() }
            if (nwsDayObs.isNotEmpty()) {
                val dayMs = 24 * 3_600_000L
                val wideObs = observationDao
                    .getObservationsInRange(
                        startTs - dayMs,
                        endTs + dayMs,
                        latitude,
                        longitude,
                    )
                    .filter { it.api == WeatherSource.NWS.id }
                    .map { it.toReading() }
                appLogDao.log(
                    "EXTREMA_WINDOW_DIAG",
                    "date=$date isolated=[${probe(nwsDayObs, startTs, endTs)}] " +
                        "wide=[${probe(wideObs, startTs - dayMs, endTs + dayMs)}]",
                    "DEBUG",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.v(TAG, "Unable to write extrema window diagnostic", e)
        }
    }

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
            .filter { it.source == WeatherSource.NWS.id && (it.apiHighTemp == null || it.apiLowTemp == null) }
            .map { it.date }
            .distinct()

    /**
     * Writes station-derived daily extremes onto the NWS rows for the given dates.
     *
     * Applies to every same-date NWS fragment in the proximity box rather than only the nearest.
     * The value describes a *station*, not a coordinate, so it is equally true for each fragment of
     * the same site — and leaving stale fragments unfilled is what let a partial row shadow a
     * complete one in the first place (see ApiActualPicker).
     */
    /**
     * NWS hourly rows for one day, collapsed to a single coordinate site.
     *
     * Lives here rather than in [NwsApiDailyActualsFetcher] so the raw proximity-box query stays
     * behind `GraphDataLoader.unifyToNearestSite` — the read spans every cached site in the box,
     * and feeding un-collapsed fragments into a blend is the coordinate-fragmentation bug family
     * that `HourlyProximityQueryAllowlistTest` exists to police.
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

    private fun precipChanged(new: DailyHistoryEntity, existing: DailyHistoryEntity): Boolean =
        new.precipAmountMm != existing.precipAmountMm ||
            new.precipDayMm != existing.precipDayMm ||
            new.precipNightMm != existing.precipNightMm

    /**
     * Persists Open-Meteo past_days daily values as apiHighTemp/apiLowTemp in daily_history.
     * When [pastDays] > 0, the /forecast response includes ERA5-based observed values for past
     * dates in the same daily array — these are NOT forecasts.
     */
    suspend fun persistOpenMeteoPastDayActuals(
        latitude: Double,
        longitude: Double,
        dailyForecasts: List<DailyForecast>,
    ) {
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        val pastForecasts = dailyForecasts.filter { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
            date != null && date.isBefore(today)
        }
        if (pastForecasts.isEmpty()) return

        val minDate = pastForecasts.minOf { LocalDate.parse(it.date) }
        val maxDate = pastForecasts.maxOf { LocalDate.parse(it.date) }
        val existing = dailyHistoryDao
            .getExtremesInRange(
                minDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                maxDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                keyLat,
                keyLon,
            )
            .filter { it.source == WeatherSource.OPEN_METEO.id }
        val existingByDate = existing.groupBy { it.date }
            .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }

        val toUpsert = mutableListOf<DailyHistoryEntity>()
        for (day in pastForecasts) {
            val dateEpoch = LocalDate.parse(day.date).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val existingRow = existingByDate[dateEpoch]
            toUpsert.add(
                DailyHistoryEntity(
                    date = dateEpoch,
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = keyLat,
                    locationLon = keyLon,
                    computedHighTemp = existingRow?.computedHighTemp ?: day.highTemp,
                    computedLowTemp = existingRow?.computedLowTemp ?: day.lowTemp,
                    condition = existingRow?.condition ?: day.condition,
                    updatedAt = now,
                    precipAmountMm = existingRow?.precipAmountMm,
                    precipDayMm = existingRow?.precipDayMm,
                    precipNightMm = existingRow?.precipNightMm,
                    forecastDayPrecipChance = existingRow?.forecastDayPrecipChance,
                    forecastNightPrecipChance = existingRow?.forecastNightPrecipChance,
                    forecastHighTemp = existingRow?.forecastHighTemp,
                    forecastLowTemp = existingRow?.forecastLowTemp,
                    forecastPrecipAmountMm = existingRow?.forecastPrecipAmountMm,
                    noonCloudPercent = existingRow?.noonCloudPercent,
                    apiHighTemp = day.highTemp,
                    apiLowTemp = day.lowTemp,
                ),
            )
        }

        if (toUpsert.isNotEmpty()) {
            dailyHistoryDao.insertAll(toUpsert)
            appLogDao.log(
                "METEO_PAST_ACTUALS",
                "dates=${toUpsert.size} min=$minDate max=$maxDate",
                "DEBUG",
            )
        }
    }

}

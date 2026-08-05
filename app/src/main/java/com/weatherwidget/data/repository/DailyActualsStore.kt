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
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.ActualsAggregator
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
                if (
                    new.computedHighTemp != existing.computedHighTemp ||
                    new.computedLowTemp != existing.computedLowTemp ||
                    new.condition != existing.condition ||
                    precipChanged(new, existing)
                ) {
                    changedAny = true
                    appLogDao.log(
                        "DAILY_HISTORY_OVERWRITE",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} " +
                            "high=${existing.computedHighTemp}->${new.computedHighTemp} low=${existing.computedLowTemp}->${new.computedLowTemp} " +
                            "precip=${existing.precipAmountMm}->${new.precipAmountMm}",
                        "DEBUG",
                    )
                    toInsert.add(
                        new.copy(
                            locationLat = existing.locationLat,
                            locationLon = existing.locationLon,
                            forecastDayPrecipChance = existing.forecastDayPrecipChance,
                            forecastNightPrecipChance = existing.forecastNightPrecipChance,
                            forecastHighTemp = existing.forecastHighTemp,
                            forecastLowTemp = existing.forecastLowTemp,
                            forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                            noonCloudPercent = existing.noonCloudPercent,
                            apiHighTemp = existing.apiHighTemp,
                            apiLowTemp = existing.apiLowTemp,
                        ),
                    )
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

    private fun precipChanged(new: DailyHistoryEntity, existing: DailyHistoryEntity): Boolean =
        new.precipAmountMm != existing.precipAmountMm ||
            new.precipDayMm != existing.precipDayMm ||
            new.precipNightMm != existing.precipNightMm

    /**
     * Persists NWS gridpoint daily temperature extremes as apiHighTemp/apiLowTemp in daily_history
     * for past dates. The gridpoint endpoint's maxTemperature/minTemperature are already parsed and
     * plausibility-checked by [NwsApi.parseDailyExtremes] — this just stores them.
     */
    suspend fun persistNwsGridpointActuals(
        latitude: Double,
        longitude: Double,
        extremes: NwsApi.DailyTemperatureExtremes,
    ) {
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val today = LocalDate.now()
        val todayDateStr = today.toString()
        val now = System.currentTimeMillis()

        val dateStrs = (extremes.maxByDate.keys + extremes.minByDate.keys)
            .filter { it < todayDateStr }

        if (dateStrs.isEmpty()) {
            appLogDao.log("NWS_GRIDPOINT_ACTUALS", "skipped (no past dates; maxByDate=${extremes.maxByDate.size} minByDate=${extremes.minByDate.size})", "DEBUG")
            return
        }

        val existing = dailyHistoryDao
            .getExtremesInRange(
                LocalDate.parse(dateStrs.min()).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                LocalDate.parse(dateStrs.max()).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                keyLat,
                keyLon,
            )
            .filter { it.source == WeatherSource.NWS.id }
        val existingByDate = existing.groupBy { it.date }
            .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }

        val toUpsert = mutableListOf<DailyHistoryEntity>()
        for (dateStr in dateStrs) {
            val date = LocalDate.parse(dateStr)
            val dateEpoch = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val maxTemp = extremes.maxByDate[dateStr]
            val minTemp = extremes.minByDate[dateStr]
            if (maxTemp == null && minTemp == null) continue

            val existingRow = existingByDate[dateEpoch]
            toUpsert.add(
                DailyHistoryEntity(
                    date = dateEpoch,
                    source = WeatherSource.NWS.id,
                    locationLat = keyLat,
                    locationLon = keyLon,
                    computedHighTemp = existingRow?.computedHighTemp ?: maxTemp ?: minTemp ?: 0f,
                    computedLowTemp = existingRow?.computedLowTemp ?: minTemp ?: maxTemp ?: 0f,
                    condition = existingRow?.condition ?: "",
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
                    apiHighTemp = maxTemp,
                    apiLowTemp = minTemp,
                ),
            )
        }

        if (toUpsert.isNotEmpty()) {
            dailyHistoryDao.insertAll(toUpsert)
            appLogDao.log(
                "NWS_GRIDPOINT_ACTUALS",
                "dates=${toUpsert.size} min=${dateStrs.minOrNull()} max=${dateStrs.maxOrNull()}",
                "DEBUG",
            )
        }
    }

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

    /**
     * Returns epoch-day-millis for NWS daily_history rows that have null apiHighTemp in the
     * given range. Used to feed [backfillNwsApiActualsFromArchive].
     */
    suspend fun findNwsDatesMissingApiActuals(
        latitude: Double,
        longitude: Double,
        startMs: Long,
        endMs: Long,
    ): List<Long> {
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        return dailyHistoryDao
            .getExtremesInRange(startMs, endMs, keyLat, keyLon)
            .filter { it.source == WeatherSource.NWS.id && it.apiHighTemp == null }
            .map { it.date }
    }
    /**
     * Backfills apiHighTemp/apiLowTemp for NWS past dates using ERA5 archive data. Idempotent —
     * only fills dates where apiHighTemp is null.
     */
    suspend fun backfillNwsApiActualsFromArchive(
        latitude: Double,
        longitude: Double,
        archiveActuals: Map<Long, Pair<Float, Float>>, // epochDayMillis → (highF, lowF)
    ) {
        if (archiveActuals.isEmpty()) return
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val now = System.currentTimeMillis()

        val dateStrs = archiveActuals.keys.sorted()
        val minDate = dateStrs.first()
        val maxDate = dateStrs.last()

        val existing = dailyHistoryDao
            .getExtremesInRange(minDate, maxDate, keyLat, keyLon)
            .filter { it.source == WeatherSource.NWS.id && it.apiHighTemp == null }

        val toUpsert = existing.mapNotNull { row ->
            val temps = archiveActuals[row.date] ?: return@mapNotNull null
            row.copy(
                apiHighTemp = temps.first,
                apiLowTemp = temps.second,
                updatedAt = now,
            )
        }

        if (toUpsert.isNotEmpty()) {
            dailyHistoryDao.insertAll(toUpsert)
            appLogDao.log(
                "NWS_API_ACTUALS_BACKFILL",
                "filled=${toUpsert.size} dates=${dateStrs.size}",
                "DEBUG",
            )
        }
    }
}

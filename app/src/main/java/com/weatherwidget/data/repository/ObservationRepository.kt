package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyExtremeDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.util.ObservationBlender
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ObservationRepository"

// Local hour by which a finished (past) day must have at least one NWS observation for its
// cached daily high to be trustworthy — the daily high typically lands in the early afternoon.
private const val DAYTIME_COVERAGE_HOUR = 14

/**
 * Returns true when [date] is a *past* day whose NWS observations never reach the afternoon
 * ([daytimeHour]+), meaning the warm part of the day went unrecorded (e.g. the device was off)
 * and the cached daily high/low is likely wrong, so the day should be re-fetched. Days with no
 * observations at all are excluded here — those are caught by the daily_extremes row-presence
 * check — as are today and future days, whose coverage is legitimately still incomplete.
 */
@VisibleForTesting
internal fun pastDayLacksAfternoonCoverage(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    today: LocalDate,
    daytimeHour: Int = DAYTIME_COVERAGE_HOUR,
): Boolean {
    if (!date.isBefore(today)) return false
    if (obsTimestampsMs.isEmpty()) return false
    val coversAfternoon = obsTimestampsMs.any { ms ->
        java.time.Instant.ofEpochMilli(ms).atZone(zone).hour >= daytimeHour
    }
    return !coversAfternoon
}

@Singleton
class ObservationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observationDao: ObservationDao,
    private val dailyExtremeDao: DailyExtremeDao,
    private val appLogDao: AppLogDao,
    private val nwsApi: NwsApi,
    private val hourlyForecastDao: HourlyForecastDao,
) {
    internal data class RecentBackfillResult(
        val stationsTried: Int,
        val rowsFetched: Int,
        val affectedDates: Set<LocalDate>,
    )

    private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

    companion object {
        private const val MAX_RETRIES = 5
    }

    internal suspend fun fetchNwsCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
        val gridPoint = nwsApi.getGridPoint(latitude, longitude)
        val stations = getSortedObservationStations(gridPoint.observationStationsUrl ?: "")
        if (stations.isEmpty()) return@coroutineScope null

        val stationsToFetch = stations.take(MAX_RETRIES)
        val closestStation = stationsToFetch.first()
        val otherStations = stationsToFetch.drop(1)
        val fetchStartMs = System.currentTimeMillis()

        val closestDeferred = async {
            val retryDelaysMs = listOf(10_000L, 30_000L)
            var entity = fetchStationObservation(closestStation, latitude, longitude, attempt = 0)
            for ((index, delayMs) in retryDelaysMs.withIndex()) {
                if (entity != null) break
                delay(delayMs)
                entity = fetchStationObservation(closestStation, latitude, longitude, attempt = index + 1)
            }
            entity
        }

        val otherDeferreds = otherStations.map { stationInfo ->
            async { fetchStationObservation(stationInfo, latitude, longitude) }
        }

        val successfulEntities = (listOf(closestDeferred) + otherDeferreds).mapNotNull { it.await() }
        val totalFetchDurationMs = System.currentTimeMillis() - fetchStartMs

        if (successfulEntities.isEmpty()) {
            appLogDao.log("NWS_FETCH_FAIL_ALL", "stationsTried=${stationsToFetch.size} totalMs=$totalFetchDurationMs", "WARN")
            return@coroutineScope null
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, successfulEntities.map { it.toReading() })
            ?: return@coroutineScope null

        val closest = successfulEntities.minBy { it.distanceKm }

        val stationSummary = successfulEntities.joinToString { "${it.stationId}(${it.distanceKm}km)" }
        appLogDao.log("NWS_IDW", "blended=${blendedTemp}°F from ${successfulEntities.size} stations: $stationSummary totalMs=$totalFetchDurationMs")
        Log.d(TAG, "NWS IDW blend: $blendedTemp°F from $stationSummary totalMs=$totalFetchDurationMs")

        CurrentReadingPayload(
            WeatherSource.NWS,
            blendedTemp,
            closest.condition,
            successfulEntities.maxOf { it.timestamp },
        )
    }

    private suspend fun fetchStationObservation(
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        attempt: Int = 0,
    ): ObservationEntity? {
        val observation = try {
            nwsApi.getLatestObservationDetailed(stationInfo.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "null_response"
            appLogDao.log("NWS_STATION_FAIL", "station=${stationInfo.id} attempt=$attempt reason=$reason", "WARN")
            Log.w(TAG, "NWS station ${stationInfo.id} attempt $attempt failed: $reason")
            return null
        }
        if (observation == null) return null

        if (attempt > 0) {
            appLogDao.log("NWS_STATION_RETRY_OK", "station=${stationInfo.id} attempt=$attempt", "INFO")
            Log.d(TAG, "NWS station ${stationInfo.id} succeeded on retry attempt $attempt")
        }
        val obsEntity = buildObservationEntity(observation, stationInfo, latitude, longitude)
        observationDao.insertAll(listOf(obsEntity))
        logCurrentObservationInsert(obsEntity)
        val obsDate = java.time.Instant.ofEpochMilli(obsEntity.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        recomputeDailyExtremesForDay(latitude, longitude, obsDate, emptyList())
        return obsEntity
    }

    private suspend fun logCurrentObservationInsert(obsEntity: ObservationEntity) {
        val nowMs = System.currentTimeMillis()
        appLogDao.log(
            "OBS_CURRENT_INSERT",
            "source=${obsEntity.api} station=${obsEntity.stationId} timestamp=${obsEntity.timestamp} " +
                "fetchedAt=${obsEntity.fetchedAt} temp=${obsEntity.temperature} " +
                "timestampAgeMin=${(nowMs - obsEntity.timestamp) / 60_000L} " +
                "fetchAgeMin=${(nowMs - obsEntity.fetchedAt) / 60_000L}",
            "INFO",
        )
    }

    private suspend fun getSortedObservationStations(stationsUrl: String): List<NwsApi.StationInfo> {
        if (stationsUrl.isEmpty()) return emptyList()

        val stationsKey = "observation_stations_v4_${stationsUrl.hashCode()}"
        val timeKey = "observation_stations_time_v4_${stationsUrl.hashCode()}"
        val cachedStationsString = prefs.getString(stationsKey, null)
        val lastUpdateTimestamp = prefs.getLong(timeKey, 0)

        if (cachedStationsString != null && System.currentTimeMillis() - lastUpdateTimestamp < 86400000) {
            return cachedStationsString.split("|").mapNotNull(NwsApi.Companion::decodeStationInfo)
        }

        val fetchedStations = runCatching { nwsApi.getObservationStations(stationsUrl) }.getOrDefault(emptyList())
        if (fetchedStations.isNotEmpty()) {
            prefs.edit()
                .putString(stationsKey, fetchedStations.joinToString("|", transform = NwsApi.Companion::encodeStationInfo))
                .putLong(timeKey, System.currentTimeMillis())
                .apply()
        }
        return fetchedStations
    }

    internal suspend fun backfillNwsObservationsIfNeeded(latitude: Double, longitude: Double) {
        Log.d(TAG, "backfillNwsObservationsIfNeeded entered for ($latitude, $longitude)")
        val localZone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(localZone)

        val dayMinus2 = now.minusDays(2).toLocalDate()
        val yesterday = now.minusDays(1).toLocalDate()
        val today = now.toLocalDate()
        val currentHour = now.hour
        val dayMinus2Epoch = dayMinus2.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val yesterdayEpoch = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayEpoch = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val requiredDates = buildSet {
            add(dayMinus2Epoch)
            add(yesterdayEpoch)
            if (currentHour >= 2) add(todayEpoch)
        }
        val existingDates =
            dailyExtremeDao.getExtremesInRange(dayMinus2Epoch, todayEpoch, latitude, longitude)
                .filter { it.source == WeatherSource.NWS.id }
                .map { it.date }
                .toSet()
        val missingDates = requiredDates - existingDates
        // A daily_extremes row can exist yet be wrong when that day's observations don't reach the
        // afternoon (device off, partial coverage). Treat those present-but-incomplete past days as
        // needing a re-fetch too — the row-presence check above cannot see this.
        val incompleteDates = incompletelyCoveredPastDates(existingDates, latitude, longitude, localZone, today)
        val datesToBackfill = missingDates + incompleteDates

        Log.d(TAG, "History check: requiredDates=${requiredDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} existingDates=${existingDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} missingDates=${missingDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} incompleteDates=${incompleteDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} hour=$currentHour")

        if (datesToBackfill.isEmpty()) {
            Log.d(TAG, "Skipping backfill: required NWS daily_extremes rows exist with adequate coverage")
            return
        }

        Log.i(TAG, "Backfilling NWS daily_extremes for ${datesToBackfill.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} (missing or incomplete), fetching last ${WeatherConfig.NWS_BACKFILL_DAYS * 24} hours")
        val gridPoint = runCatching { nwsApi.getGridPoint(latitude, longitude) }.getOrNull()
        if (gridPoint == null) {
            Log.e(TAG, "Failed to get grid point for ($latitude, $longitude)")
            return
        }

        val stations = getSortedObservationStations(gridPoint.observationStationsUrl ?: "")
        Log.d(TAG, "Found ${stations.size} stations to try")
        if (stations.isEmpty()) return

        val startTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.minusDays(WeatherConfig.NWS_BACKFILL_DAYS.toLong()).toInstant())
        val endTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
        val remainingDates = datesToBackfill.toMutableSet()

        for (stationInfo in stations.take(MAX_RETRIES)) {
            Log.d(TAG, "Attempting backfill from station ${stationInfo.id}")
            try {
                val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                Log.d(TAG, "Station ${stationInfo.id} returned ${observations.size} observations")
                if (observations.isNotEmpty()) {
                    val entities = observations.map { obs ->
                        buildObservationEntity(obs, stationInfo, latitude, longitude)
                    }
                    observationDao.insertAll(entities)
                    Log.i(TAG, "Successfully backfilled ${entities.size} observations from ${stationInfo.id}")
                    val distinctDays = entities.map { e ->
                        java.time.Instant.ofEpochMilli(e.timestamp).atZone(localZone).toLocalDate()
                    }.distinct()
                    for (day in distinctDays) {
                        recomputeDailyExtremesForDay(latitude, longitude, day, emptyList())
                    }
                    // A date is satisfied only once it has a row AND (for past days) the refetched
                    // observations now cover the afternoon — otherwise keep trying other stations.
                    val rowDates =
                        dailyExtremeDao.getExtremesInRange(dayMinus2Epoch, todayEpoch, latitude, longitude)
                            .filter { it.source == WeatherSource.NWS.id }
                            .map { it.date }
                            .toSet()
                    val stillIncomplete = incompletelyCoveredPastDates(rowDates, latitude, longitude, localZone, today)
                    remainingDates.removeAll(rowDates - stillIncomplete)
                    if (remainingDates.isEmpty()) {
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill from ${stationInfo.id}: ${e.message}")
            }
        }

        if (remainingDates.isNotEmpty()) {
            Log.w(TAG, "Backfill completed but official NWS daily_extremes still missing/incomplete for $remainingDates")
        }
    }

    /**
     * From a set of day keys (epoch-millis at local midnight), returns those *past* days whose
     * stored NWS observations don't reach the afternoon — i.e. their cached daily high/low is
     * likely truncated and the day should be re-fetched. See [pastDayLacksAfternoonCoverage].
     */
    private suspend fun incompletelyCoveredPastDates(
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
            val timestamps =
                observationDao.getObservationsInRange(dayStart, dayEnd, latitude, longitude)
                    .filter { it.api == WeatherSource.NWS.id && it.stationId != "NWS_BLEND" }
                    .map { it.timestamp }
            pastDayLacksAfternoonCoverage(timestamps, date, zone, today)
        }

    internal suspend fun backfillRecentNwsObservations(
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
    ): RecentBackfillResult {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val startTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.minusHours(lookbackHours).toInstant())
        val endTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())

        appLogDao.log(
            "OBS_HOURLY_BACKFILL_START",
            "lat=$latitude lon=$longitude lookbackHours=$lookbackHours start=$startTimeStr end=$endTimeStr",
            "INFO",
        )

        val gridPoint = runCatching { nwsApi.getGridPoint(latitude, longitude) }.getOrNull()
        val observationStationsUrl = gridPoint?.observationStationsUrl
        if (observationStationsUrl.isNullOrBlank()) {
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=missing_gridpoint_or_stations_url",
                "WARN",
            )
            return RecentBackfillResult(stationsTried = 0, rowsFetched = 0, affectedDates = emptySet())
        }

        // Smart-cast to non-null after the isNullOrBlank guard above (was previously two !! assertions).
        val stations = getSortedObservationStations(observationStationsUrl)
        if (stations.isEmpty()) {
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=no_stations",
                "WARN",
            )
            return RecentBackfillResult(stationsTried = 0, rowsFetched = 0, affectedDates = emptySet())
        }

        var totalRows = 0
        val affectedDates = mutableSetOf<LocalDate>()
        val localZone = ZoneId.systemDefault()
        val stationsToTry = stations.take(MAX_RETRIES)

        for (stationInfo in stationsToTry) {
            try {
                val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_STATION",
                    "station=${stationInfo.id} rows=${observations.size} lookbackHours=$lookbackHours",
                    "INFO",
                )
                if (observations.isEmpty()) continue

                val entities = observations.map { obs ->
                    buildObservationEntity(obs, stationInfo, latitude, longitude)
                }
                observationDao.insertAll(entities)
                totalRows += entities.size
                affectedDates += entities.map { entity ->
                    java.time.Instant.ofEpochMilli(entity.timestamp).atZone(localZone).toLocalDate()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_STATION_FAIL",
                    "station=${stationInfo.id} ${e.message}",
                    "WARN",
                )
                Log.w(TAG, "Hourly observation backfill failed for ${stationInfo.id}: ${e.message}")
            }
        }

        if (affectedDates.isNotEmpty()) {
            recomputeDailyExtremesFromStoredObservations(
                latitude = latitude,
                longitude = longitude,
                startDate = affectedDates.min(),
                endDateInclusive = affectedDates.max(),
                hourlyForecasts = emptyList(),
            )
        }

        appLogDao.log(
            "OBS_HOURLY_BACKFILL_DONE",
            "lat=$latitude lon=$longitude stations=${stationsToTry.size} rows=$totalRows affectedDates=${affectedDates.sorted()}",
            "INFO",
        )
        return RecentBackfillResult(
            stationsTried = stationsToTry.size,
            rowsFetched = totalRows,
            affectedDates = affectedDates,
        )
    }

    /**
     * Returns daily actuals for the past 30 days from the DB cache, plus today's actuals
     * computed live from raw observations (never cached, always fresh).
     */
    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
    ): DailyActualsBySource {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // Past days: read from DB cache
        val startDate = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastExtremes = dailyExtremeDao.getExtremesInRange(startDate, endDate, latitude, longitude)
        val pastActuals = ObservationResolver.extremesToDailyActualsBySource(pastExtremes, latitude, longitude)

        // Today: compute live from station observations using IDW blending (matches Hourly Graph)
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayObs = observationDao.getObservationsInRange(todayStartMs, tomorrowMs, latitude, longitude)
            .filter { it.stationId != "NWS_BLEND" }

        val todayBlendedActuals = mutableMapOf<String, Map<LocalDate, ObservationResolver.DailyActual>>()
        activeSourceList.forEach { sourceId ->
            val source = WeatherSource.fromId(sourceId)
            val blendedResult = ObservationBlender.blendObservationSeries(
                observations = todayObs.filter { it.api == sourceId },
                hourlyForecasts = hourlyForecasts,
                displaySource = source,
                userLat = latitude,
                userLon = longitude,
                startMs = todayStartMs,
                endMs = tomorrowMs,
            )
            val blendedObs = blendedResult.observations
            if (blendedObs.isNotEmpty()) {
                val high = blendedObs.maxOf { obs -> obs.temperature }
                val low = blendedObs.minOf { obs -> obs.temperature }
                // Precip mirrors the past-day persisted path: measured-preferred, with NWS
                // forecast-fallback. Without this, today's daily rain label shows only the
                // forecast probability even when daily_extremes already has measured precip.
                val precip = ObservationResolver.resolveDailyPrecip(
                    dayObs = todayObs.filter { it.api == sourceId },
                    sourceHourly = hourlyForecasts.filter { it.source == sourceId },
                    date = today,
                    zone = zone,
                )
                todayBlendedActuals[sourceId] = mapOf(
                    today to ObservationResolver.DailyActual(
                        date = today,
                        highTemp = high,
                        lowTemp = low,
                        condition = "blended",
                        precipAmountMm = precip.total,
                        precipDayMm = precip.day,
                        precipNightMm = precip.night,
                    ),
                )
            }
        }

        val obsSpanSummary =
            if (todayObs.isEmpty()) {
                "none"
            } else {
                val firstTs = todayObs.minOf { it.timestamp }
                val lastTs = todayObs.maxOf { it.timestamp }
                val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                val firstLocal = java.time.Instant.ofEpochMilli(firstTs).atZone(zone).toLocalDateTime().format(formatter)
                val lastLocal = java.time.Instant.ofEpochMilli(lastTs).atZone(zone).toLocalDateTime().format(formatter)
                "$firstLocal..$lastLocal"
            }
        val liveSummary =
            todayBlendedActuals
                .toSortedMap()
                .entries
                .joinToString("; ") { (source, actualsByDate) ->
                    val actual = actualsByDate[today]
                    val stationCount = todayObs.count { it.api == source }
                    "$source[blendedHigh=${actual?.highTemp},blendedLow=${actual?.lowTemp},rows=$stationCount]"
                }
                .ifEmpty { "none" }
        Log.d(
            TAG,
            "getDailyActualsWithLiveToday: date=$today lat=$latitude lon=$longitude " +
                "todayObsRows=${todayObs.size} span=$obsSpanSummary live=[$liveSummary]",
        )

        // Today: use live blender result directly. Do NOT merge with persisted daily_extremes —
        // the persisted row is computed via IDW-of-per-station-max, which is a different algorithm
        // and routinely produces a different (higher) value than the IDW-by-hour blender used by
        // the Hourly Graph. Merging would re-introduce the 73.5° vs 73.1° discrepancy.
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
        var current = startDate
        while (!current.isAfter(endDateInclusive)) {
            recomputeDailyExtremesForDay(latitude, longitude, current, hourlyForecasts)
            current = current.plusDays(1)
        }
    }

    private suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val zone = ZoneId.systemDefault()
        val dateMillis = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val startTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayObs = observationDao.getObservationsInRange(startTs, endTs, latitude, longitude)
        if (dayObs.isEmpty()) return

        // Backfill paths pass no forecasts; load the day's hourly forecasts so sources without
        // measured observation precip (NWS) can fall back to forecast-derived rain in the blend.
        val effectiveHourly = hourlyForecasts.ifEmpty {
            hourlyForecastDao.getHourlyForecasts(startTs, endTs, latitude, longitude)
        }
        val newExtremes = ObservationResolver.computeDailyExtremes(dayObs, effectiveHourly, latitude, longitude)
        val existingExtremes = dailyExtremeDao.getExtremesInRange(dateMillis, dateMillis, latitude, longitude)
            .associateBy { it.source }

        // Per-station breakdown — survives in app_logs so "why did the high jump?" investigations
        // don't need a live logcat capture.
        val perSourceBreakdown = dayObs
            .groupBy { it.api }
            .mapValues { (_, srcObs) ->
                srcObs.groupBy { it.stationId }.entries.joinToString(",") { (sid, obs) ->
                    val d = obs.first().distanceKm
                    val hi = obs.maxOf { it.temperature }
                    val lo = obs.minOf { it.temperature }
                    "$sid(d=${"%.2f".format(d)}km,hi=$hi,lo=$lo,n=${obs.size})"
                }
            }
        newExtremes.forEach { new ->
            val stationsStr = perSourceBreakdown[new.source] ?: "n/a"
            appLogDao.log(
                "DAILY_EXTREME_BLEND",
                "date=$date src=${new.source} computed_hi=${new.highTemp} computed_lo=${new.lowTemp} stations=[$stationsStr]",
                "DEBUG",
            )
        }

        val isToday = date == LocalDate.now()
        val toInsert = mutableListOf<com.weatherwidget.data.local.DailyExtremeEntity>()

        newExtremes.forEach { new ->
            val existing = existingExtremes[new.source]
            when {
                existing == null -> toInsert.add(new)

                isToday -> {
                    // Today: ratchet temps up to protect against transient drops in current
                    // readings. Precip is a full re-sum of the day's data (authoritative, not
                    // incremental), so we always take the new value when it changes.
                    val updatedHigh = maxOf(existing.highTemp, new.highTemp)
                    val updatedLow = minOf(existing.lowTemp, new.lowTemp)
                    val tempChanged = updatedHigh > existing.highTemp || updatedLow < existing.lowTemp
                    if (tempChanged || new.condition != existing.condition || precipChanged(new, existing)) {
                        appLogDao.log("DAILY_EXTREME_UP", "date=$date src=${new.source} high=${existing.highTemp}->${updatedHigh} low=${existing.lowTemp}->${updatedLow} precip=${existing.precipAmountMm}->${new.precipAmountMm}", "DEBUG")
                        toInsert.add(new.copy(highTemp = updatedHigh, lowTemp = updatedLow))
                    } else {
                        appLogDao.log("DAILY_EXTREME_STABLE", "date=$date src=${new.source} high=${existing.highTemp} low=${existing.lowTemp}", "DEBUG")
                    }
                }

                else -> {
                    // Past day: overwrite. Observations are complete, the time-aligned blend is
                    // idempotent, and self-healing migration relies on overwriting stale rows
                    // left by the old per-station-spot-max algorithm.
                    if (new.highTemp != existing.highTemp || new.lowTemp != existing.lowTemp || new.condition != existing.condition || precipChanged(new, existing)) {
                        appLogDao.log(
                            "DAILY_EXTREME_OVERWRITE",
                            "date=$date src=${new.source} high=${existing.highTemp}->${new.highTemp} low=${existing.lowTemp}->${new.lowTemp} precip=${existing.precipAmountMm}->${new.precipAmountMm}",
                            "DEBUG",
                        )
                        toInsert.add(new)
                    } else {
                        appLogDao.log("DAILY_EXTREME_STABLE", "date=$date src=${new.source} high=${new.highTemp} low=${new.lowTemp}", "DEBUG")
                    }
                }
            }
        }

        if (toInsert.isNotEmpty()) {
            dailyExtremeDao.insertAll(toInsert)
        }
    }

    private fun precipChanged(
        new: com.weatherwidget.data.local.DailyExtremeEntity,
        existing: com.weatherwidget.data.local.DailyExtremeEntity,
    ): Boolean =
        new.precipAmountMm != existing.precipAmountMm ||
            new.precipDayMm != existing.precipDayMm ||
            new.precipNightMm != existing.precipNightMm

    suspend fun getRecentObservations(sinceMs: Long): List<ObservationEntity> =
        observationDao.getRecentObservations(sinceMs)

    suspend fun getMainObservationsWithComputedNwsBlend(
        latitude: Double,
        longitude: Double,
        sinceMs: Long,
    ): List<ObservationEntity> = coroutineScope {
        val persistedMainObs = observationDao.getLatestMainObservationsExcludingNws(latitude, longitude, sinceMs)
        val nwsStationObsAll = observationDao.getLatestNwsObservationsByStationAllTime(latitude, longitude, sinceMs)

        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: persistedMainObs=${persistedMainObs.size} nwsStationObsAll=${nwsStationObsAll.size} sinceMs=${sinceMs}")
        nwsStationObsAll.take(10).forEach { obs ->
            Log.d(TAG, "  NWS station ${obs.stationId}: timestamp=${obs.timestamp} fetchedAt=${obs.fetchedAt}")
        }

        val nwsStationObs = nwsStationObsAll.filter { it.timestamp > sinceMs }
        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: after filtering by sinceMs: ${nwsStationObs.size}")

        if (nwsStationObs.isEmpty()) {
            Log.d(TAG, "getMainObservationsWithComputedNwsBlend: no NWS station obs after filter, returning persisted only")
            return@coroutineScope persistedMainObs
        }

        val dedupedNwsObs = nwsStationObs
            .groupBy { it.stationId }
            // groupBy guarantees each value list is non-empty, so maxByOrNull can never be null here.
            .mapValues { it.value.maxByOrNull { it.timestamp }!! }
            .values
            .toList()

        if (dedupedNwsObs.isEmpty()) {
            Log.d(TAG, "getMainObservationsWithComputedNwsBlend: deduped empty, returning persisted only")
            return@coroutineScope persistedMainObs
        }

        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: dedupedNwsObs=${dedupedNwsObs.size}")
        dedupedNwsObs.forEach { obs ->
            Log.d(TAG, "  deduped ${obs.stationId}: timestamp=${obs.timestamp}")
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, dedupedNwsObs.map { it.toReading() })
            ?: return@coroutineScope persistedMainObs

        val closest = dedupedNwsObs.minBy { it.distanceKm }
        val newestTimestamp = dedupedNwsObs.maxOf { it.timestamp }
        val newestFetchedAt = dedupedNwsObs.maxOf { it.fetchedAt }

        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: blendedTemp=${blendedTemp} newestTimestamp=${newestTimestamp} newestFetchedAt=${newestFetchedAt}")

        val syntheticNwsMain = ObservationEntity(
            stationId = "NWS_BLEND",
            stationName = "NWS Blended",
            timestamp = newestTimestamp,
            temperature = blendedTemp,
            condition = closest.condition,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = "BLENDED",
            fetchedAt = newestFetchedAt,
            api = WeatherSource.NWS.id,
        )

        persistedMainObs + syntheticNwsMain
    }

    private fun buildObservationEntity(
        obs: NwsApi.Observation,
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
    ): ObservationEntity {
        val distanceKm = calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f
        return ObservationEntity(
            stationId = stationInfo.id,
            stationName = obs.stationName.ifEmpty { stationInfo.name },
            timestamp = OffsetDateTime.parse(obs.timestamp).toInstant().toEpochMilli(),
            temperature = (obs.temperatureCelsius * 1.8f) + 32f,
            condition = obs.textDescription,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = distanceKm,
            stationType = stationInfo.type.name,
            maxTempLast24h = obs.maxTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            minTempLast24h = obs.minTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            api = WeatherSource.NWS.id,
            precipAmountMm = obs.precipLastHourMm,
        )
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}

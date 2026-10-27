package com.weatherwidget.data.repository

import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.log

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleHistoryRun
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.shared.util.HourlyForecastWrite
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.widget.WidgetStateManager

/**
 * Owns live hourly rows, forecast-history snapshots, and historical-actual backfill.
 */
internal class HourlyForecastStore(
    private val hourlyForecastDao: HourlyForecastDao,
    private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
    private val observationDao: ObservationDao,
    private val widgetStateManager: WidgetStateManager,
    /**
     * The repository's wall clock ([ForecastRepository.clock]), so a test that pins it pins every
     * "now" decision here too — the elapsed-hour drop below used to read the real clock while the
     * rest of the fetch used the pinned one, and a 10:00 "daytime" hour vanished after 11:00.
     */
    private val clock: () -> Long = System::currentTimeMillis,
    private val appLogDao: com.weatherwidget.data.local.AppLogDao,
    /** For the observation write's retired-product cleanup ([writeObservations]). */
    private val dailyHistoryDao: com.weatherwidget.data.local.DailyHistoryDao,
    /**
     * The `:shared` [com.weatherwidget.shared.history.HourlySnapshotRun] label for [sourceId]'s fetch at
     * the site now, or null to save no snapshot ([RoomHourlySnapshotHost] in production). The default —
     * every fetch, under its own time — is for tests.
     */
    private val snapshotLabel: suspend (sourceId: String, latitude: Double, longitude: Double, nowMs: Long) -> Long? =
        { _, _, _, nowMs -> nowMs },
) {
    data class HistoricalActualsWriteSummary(
        val rowCount: Int,
        val replacementCount: Int,
    )

    /**
     * [keepUntilMs]: hours at or past [HourlyOnDemand.KeepUntil.liveMs] are not stored live, at or past
     * [HourlyOnDemand.KeepUntil.snapshotMs] not snapshotted — the fetch's horizon (72 h routinely, a
     * LONG fetch's 8 days live, deeper for an on-demand day). The daily view keeps what it needs from
     * the rest on the forecast row ([com.weatherwidget.shared.util.DailyHourlySummaries]); the caller
     * computes that from the whole download before calling here.
     * `performance/261010-daily-view-summaries-instead-of-far-hourly.md`,
     * `performance/261016-long-fetch-keeps-eight-days-of-live-hourly.md`
     */
    suspend fun saveHourlyEntities(rawEntities: List<HourlyForecastEntity>, keepUntilMs: HourlyOnDemand.KeepUntil? = null) {
        val sample = rawEntities.firstOrNull() ?: return
        saveHourly(rawEntities.map { it.toHourlyForecast() }, sample.locationLat, sample.locationLon, sample.source, keepUntilMs)
    }

    /**
     * [sourceId]'s live rows from [hours]: the `:shared` [HourlyForecastWrite] rule (same as desktop)
     * over this site's stored rows, then the fetch's snapshot if [decideSnapshot] keeps one.
     */
    private suspend fun saveHourly(
        hours: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        keepUntilMs: HourlyOnDemand.KeepUntil?,
    ) {
        val range = HourlyForecastWrite.readRange(hours) ?: return
        val now = clock()
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val stored = siteExactExistingByDateTime(
            hourlyForecastDao.getHourlyForecastsBySource(range.first, range.last, keyLat, keyLon, sourceId),
            keyLat,
            keyLon,
        ).values.map { it.toHourlyForecast() }
        val plan = HourlyForecastWrite.plan(hours, stored, sourceId, now, keepUntilMs)
        plan.trimLine?.let { appLogDao.log(HourlyOnDemand.TRIM_TAG, it, "VERBOSE") }
        if (plan.live.isEmpty()) return
        hourlyForecastDao.insertAll(plan.live.map { it.copy(locationLat = keyLat, locationLon = keyLon).toEntity(keyLat, keyLon) })

        val snapshotAt = decideSnapshot(sourceId, keyLat, keyLon, now) ?: return
        if (plan.snapshot.isEmpty()) return
        hourlyForecastHistoryDao.insertAll(plan.snapshot.map { it.toHistoryEntity(keyLat, keyLon, sourceId, snapshotAt) })
    }

    /**
     * [ForecastPersistRun]'s Room side for [sourceId]'s fetch at this site, on this store's DAOs and
     * snapshot decision. [forecastDao] is [ForecastSnapshotStore.forecastDao].
     */
    fun forecastPersistHost(
        forecastDao: com.weatherwidget.data.local.ForecastDao,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        sourceId: String,
        latitude: Double,
        longitude: Double,
    ) = RoomForecastPersistHost(
        forecastDao, hourlyForecastDao, hourlyForecastHistoryDao, appLogDao, sourceId, latitude, longitude,
    ) { nowMs -> decideSnapshot(sourceId, latitude, longitude, nowMs) }

    /**
     * [sourceId]'s stored hourly rows at this exact site (write-quantized key) in [startMs]..[endMs].
     * Read by Google's one-page fetch ([com.weatherwidget.data.remote.GoogleHourPaging]) to tell
     * whether page 1 changed, and by the daily mapping for the hours a one-page fetch did not return.
     */
    suspend fun storedHourlyForSite(
        latitude: Double,
        longitude: Double,
        sourceId: String,
        startMs: Long,
        endMs: Long,
    ): List<com.weatherwidget.data.model.HourlyForecast> {
        val lat = LocationMatch.quantize(latitude)
        val lon = LocationMatch.quantize(longitude)
        return siteExactExistingByDateTime(
            hourlyForecastDao.getHourlyForecastsBySource(startMs, endMs, lat, lon, sourceId),
            lat,
            lon,
        ).values.sortedBy { it.dateTime }.map { it.toHourlyForecast() }
    }

    suspend fun saveHourlyEntitiesFromShared(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        historicalData: List<HourlyForecast> = hourlyData,
        keepUntilMs: HourlyOnDemand.KeepUntil? = null,
    ): HistoricalActualsWriteSummary {
        // Elapsed hours are dropped (HourlyForecastWrite): `hourly_forecasts` is a forecast archive, and
        // letting a `past_days` payload rewrite past rows would destroy the record of what was predicted.
        // Providers with a distinct actuals-capable historical product pass that series through
        // historicalData. Tomorrow.io passes only its separate five-minute Timeline result; its
        // elapsed hourly forecast rows are never reclassified as observations.
        // The elapsed hours are not lost, though: the caller hands them to [backfillElapsedHistory],
        // which files them as history only where the site has no snapshot yet (same boundary).
        saveHourly(hourlyData, latitude, longitude, sourceId, keepUntilMs)
        return saveHistoricalActuals(historicalData, latitude, longitude, sourceId)
    }

    /** [GoogleHistoryRun]'s Room side at this site. */
    fun googleHistoryHost(latitude: Double, longitude: Double): GoogleHistoryRun.Host =
        RoomGoogleHistoryHost(hourlyForecastHistoryDao, latitude, longitude)

    /** The last [snapshotLabel] decision per source and site: (decided at, label or null). */
    private val recentSnapshotDecisions = java.util.concurrent.ConcurrentHashMap<Triple<String, Double, Double>, Pair<Long, Long?>>()

    /**
     * One snapshot decision per fetch: a second ask within [HourlySnapshotRun.SAME_FETCH_MS] (the
     * [ForecastPersistRun] asks once; a test's direct [saveHourlyEntities] may follow it) reuses it
     * rather than asking (and logging) again. A skip stays a skip.
     */
    private suspend fun decideSnapshot(sourceId: String, latitude: Double, longitude: Double, nowMs: Long): Long? {
        val key = Triple(sourceId, LocationMatch.quantize(latitude), LocationMatch.quantize(longitude))
        recentSnapshotDecisions[key]?.let { (decidedAt, label) ->
            if (nowMs - decidedAt in 0 until com.weatherwidget.shared.history.HourlySnapshotRun.SAME_FETCH_MS) return label
        }
        return snapshotLabel(sourceId, latitude, longitude, nowMs).also { recentSnapshotDecisions[key] = nowMs to it }
    }

    /**
     * The provider history [HistoricalActualsBackfill] files from [hourlyData], written by the shared
     * [com.weatherwidget.shared.observations.ObservationWriteRun]. [tomorrowIoWindowHours]: set for a
     * Tomorrow.io fetch, whose five-minute line is logged even when it files nothing.
     */
    suspend fun saveHistoricalActuals(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        tomorrowIoWindowHours: Int? = null,
    ): HistoricalActualsWriteSummary {
        val readings = HistoricalActualsBackfill.build(
            hourly = hourlyData,
            latitude = latitude,
            longitude = longitude,
            sourceId = sourceId,
            nowMs = clock(),
        )
        if (readings.isEmpty() && tomorrowIoWindowHours == null) return HistoricalActualsWriteSummary(0, 0)
        val written = writeObservations(
            observationDao, dailyHistoryDao, appLogDao, latitude, longitude, readings, tomorrowIoWindowHours, clock(),
        )
        return HistoricalActualsWriteSummary(written.written, written.replacements)
    }

    companion object {
        @VisibleForTesting
        internal fun siteExactExistingByDateTime(
            boxRows: List<HourlyForecastEntity>,
            lat: Double,
            lon: Double,
        ): Map<Long, HourlyForecastEntity> =
            boxRows.filter { it.locationLat == lat && it.locationLon == lon }
                .associateBy { it.dateTime }
    }
}

/**
 * Room side of [ElapsedForecastBackfill.Store]: [sourceId]'s `hourly_forecast_history` at one site,
 * new rows filed under [bucket]. Pinned to desktop's by `ElapsedForecastBackfillStoreContract`
 * (`:shared` testFixtures).
 */
internal class RoomElapsedHistoryStore(
    private val dao: HourlyForecastHistoryDao,
    latitude: Double,
    longitude: Double,
    private val sourceId: String,
    private val bucket: Long,
    private val nowMs: Long,
) : ElapsedForecastBackfill.Store {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun coveredHours(startMs: Long, endMs: Long) =
        dao.getHistoryInRangeForBucketWindow(
            startDateTime = startMs,
            endDateTime = endMs,
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = keyLat,
            lon = keyLon,
            source = sourceId,
        ).asSequence()
            .filter { LocationMatch.sameSite(keyLat, keyLon, it.locationLat, it.locationLon) }
            .map { it.dateTime }
            .toSet()

    override suspend fun insert(rows: List<HourlyForecast>) {
        dao.insertAll(
            rows.map {
                HourlyForecastHistoryEntity(
                    dateTime = it.dateTime,
                    locationLat = keyLat,
                    locationLon = keyLon,
                    temperature = it.temperature,
                    condition = it.condition,
                    source = sourceId,
                    timestampToGroupPredictions = bucket,
                    precipProbability = it.precipProbability,
                    cloudCover = it.cloudCover,
                    cloudCoverLow = it.cloudCoverLow,
                    cloudCoverMid = it.cloudCoverMid,
                    cloudCoverHigh = it.cloudCoverHigh,
                    precipAmountMm = it.precipAmountMm,
                    fetchedAt = nowMs,
                )
            },
        )
    }
}

/**
 * Room side of [GoogleHistoryRun] at one site: Google's history hours on every same-site fragment.
 * Pinned to desktop's by `GoogleHistoryHostContract` (`:shared` testFixtures).
 */
internal class RoomGoogleHistoryHost(
    private val historyDao: HourlyForecastHistoryDao,
    latitude: Double,
    longitude: Double,
) : GoogleHistoryRun.Host {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)
    private val google = WeatherSource.GOOGLE_WEATHER.id

    override suspend fun coveredHistoryHours(startMs: Long, endMs: Long) =
        historyDao.getHistoryInRangeForBucketWindow(
            startDateTime = startMs,
            endDateTime = endMs,
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = keyLat,
            lon = keyLon,
            source = google,
        ).filter { LocationMatch.sameSite(keyLat, keyLon, it.locationLat, it.locationLon) }
            .map { it.dateTime }
}

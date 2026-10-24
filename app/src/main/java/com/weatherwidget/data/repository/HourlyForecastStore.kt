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
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.withQuantizedLocation
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
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
    private val appLogDao: com.weatherwidget.data.local.AppLogDao? = null,
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
        if (rawEntities.isEmpty()) return
        val snapshotUntilMs = keepUntilMs?.snapshotMs ?: Long.MAX_VALUE
        val kept = if (keepUntilMs == null) {
            rawEntities
        } else {
            HourlyOnDemand.trim(rawEntities, keepUntilMs, clock(), rawEntities.first().source) { it.dateTime }
                .also { trim -> trim.logMessage?.let { appLogDao?.log(HourlyOnDemand.TRIM_TAG, it, "VERBOSE") } }
                .live
        }
        if (kept.isEmpty()) return

        val entities = kept.map {
            it.copy(
                locationLat = LocationMatch.quantize(it.locationLat),
                locationLon = LocationMatch.quantize(it.locationLon),
            )
        }
        val minDateTime = entities.minOf { it.dateTime }
        val maxDateTime = entities.maxOf { it.dateTime }
        val sample = entities.first()
        val existingByDateTime = siteExactExistingByDateTime(
            hourlyForecastDao.getHourlyForecastsBySource(
                minDateTime,
                maxDateTime,
                sample.locationLat,
                sample.locationLon,
                sample.source,
            ),
            sample.locationLat,
            sample.locationLon,
        )
        val mergedEntities = entities.map { newlyFetched ->
            mergePreservingNullableFields(
                existingByDateTime[newlyFetched.dateTime],
                newlyFetched,
            )
        }
        // On-demand hours (past the routine 72 h) are always rewritten: their fetchedAt is what keeps
        // them counted as fresh for a tapped day (HourlyOnDemand.MAX_EXTENSION_AGE_MS).
        val extensionStartMs = HourlyOnDemand.extensionStartMs(sample.source, clock())
        val changedEntities = mergedEntities.filter { merged ->
            (extensionStartMs != null && merged.dateTime >= extensionStartMs) ||
                hasMeaningfulHourlyChange(existingByDateTime[merged.dateTime], merged)
        }
        if (changedEntities.isNotEmpty()) {
            hourlyForecastDao.insertAll(changedEntities)
        }

        val snapshotAt = decideSnapshot(sample.source, sample.locationLat, sample.locationLon, clock())
        val historyRows = if (snapshotAt == null) emptyList() else mergedEntities.filter { it.dateTime < snapshotUntilMs }.map { entity ->
            HourlyForecastHistoryEntity(
                dateTime = entity.dateTime,
                locationLat = entity.locationLat,
                locationLon = entity.locationLon,
                temperature = entity.temperature,
                condition = entity.condition,
                source = entity.source,
                timestampToGroupPredictions = snapshotAt,
                precipProbability = entity.precipProbability,
                cloudCover = entity.cloudCover,
                cloudCoverLow = entity.cloudCoverLow,
                cloudCoverMid = entity.cloudCoverMid,
                cloudCoverHigh = entity.cloudCoverHigh,
                precipAmountMm = entity.precipAmountMm,
                fetchedAt = entity.fetchedAt,
            )
        }
        if (historyRows.isNotEmpty()) {
            hourlyForecastHistoryDao.insertAll(historyRows)
        }
    }

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
        val now = clock()
        // Deliberately drops elapsed hours: `hourly_forecasts` is a forecast archive, and letting a
        // `past_days` payload rewrite past rows would destroy the record of what was predicted.
        // Providers with a distinct actuals-capable historical product pass that series through
        // historicalData. Tomorrow.io passes only its separate five-minute Timeline result; its
        // elapsed hourly forecast rows are never reclassified as observations.
        // The elapsed hours are not lost, though: the caller hands them to [backfillElapsedHistory],
        // which files them as history only where the site has no snapshot yet (same boundary).
        val futureData = hourlyData.filter { it.dateTime >= now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS }
        saveHourlyEntities(
            futureData.map {
                HourlyForecastEntity(
                    dateTime = it.dateTime,
                    locationLat = latitude,
                    locationLon = longitude,
                    temperature = it.temperature,
                    condition = it.condition,
                    source = sourceId,
                    precipProbability = it.precipProbability,
                    cloudCover = it.cloudCover,
                    cloudCoverLow = it.cloudCoverLow,
                    cloudCoverMid = it.cloudCoverMid,
                    cloudCoverHigh = it.cloudCoverHigh,
                    precipAmountMm = it.precipAmountMm,
                    fetchedAt = now,
                )
            },
            keepUntilMs,
        )
        return saveHistoricalActuals(historicalData, latitude, longitude, sourceId)
    }

    /**
     * A past day's Forecast History refresh: Google `history/hours` values written over that day's
     * live rows whose forecast went stale ([com.weatherwidget.data.remote.GoogleHistoryRefill]). Live table only, always written
     * (fresh fetchedAt is what marks the hour no longer stale); the history snapshots keep the
     * as-issued forecast.
     */
    suspend fun saveRefillHours(
        refill: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
    ) {
        if (refill.isEmpty()) return
        val now = clock()
        val lat = LocationMatch.quantize(latitude)
        val lon = LocationMatch.quantize(longitude)
        hourlyForecastDao.insertAll(
            refill.map {
                HourlyForecastEntity(
                    dateTime = it.dateTime,
                    locationLat = lat,
                    locationLon = lon,
                    temperature = it.temperature,
                    condition = it.condition,
                    source = sourceId,
                    precipProbability = it.precipProbability,
                    cloudCover = it.cloudCover,
                    cloudCoverLow = it.cloudCoverLow,
                    cloudCoverMid = it.cloudCoverMid,
                    cloudCoverHigh = it.cloudCoverHigh,
                    precipAmountMm = it.precipAmountMm,
                    fetchedAt = now,
                )
            },
        )
    }

    /**
     * Files the payload's already-elapsed hours into `hourly_forecast_history` — only for hours
     * this source has no row for at this site. See [ElapsedForecastBackfill] for why: a fresh
     * install or new location has no earlier fetch to have snapshotted those hours, so the graph's
     * past forecast line is blank for a day while every payload carries the missing values.
     *
     * Never touches `hourly_forecasts`, and never rewrites an existing snapshot: an hour with any
     * history row (in any bucket, on any same-site fragment) is left alone, so on a device in
     * steady state this is a no-op every fetch. Filed under the fetch's [com.weatherwidget.shared.history.HourlySnapshotRun]
     * bucket for the fetch, so the row is what it is — this fetch's forecast for that hour.
     */
    suspend fun backfillElapsedHistory(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        nowMs: Long = clock(),
    ): ElapsedForecastBackfill.Summary? {
        // The fetch's own snapshot decision (decideSnapshot): saved → the same label, skipped → nothing.
        val bucket = decideSnapshot(sourceId, latitude, longitude, nowMs) ?: return null
        return ElapsedForecastBackfill.backfill(
            hourlyData,
            nowMs,
            RoomElapsedHistoryStore(hourlyForecastHistoryDao, latitude, longitude, sourceId, bucket, nowMs),
        )
    }

    /** The last [snapshotLabel] decision per source and site: (decided at, label or null). */
    private val recentSnapshotDecisions = java.util.concurrent.ConcurrentHashMap<Triple<String, Double, Double>, Pair<Long, Long?>>()

    /**
     * One snapshot decision per fetch: the live snapshot decides, and the elapsed-hour backfill that
     * follows within [HourlySnapshotRun.SAME_FETCH_MS] reuses it rather than asking (and logging)
     * again — as desktop passes its label on. A skip stays a skip for the backfill.
     */
    private suspend fun decideSnapshot(sourceId: String, latitude: Double, longitude: Double, nowMs: Long): Long? {
        val key = Triple(sourceId, LocationMatch.quantize(latitude), LocationMatch.quantize(longitude))
        recentSnapshotDecisions[key]?.let { (decidedAt, label) ->
            if (nowMs - decidedAt in 0 until com.weatherwidget.shared.history.HourlySnapshotRun.SAME_FETCH_MS) return label
        }
        return snapshotLabel(sourceId, latitude, longitude, nowMs).also { recentSnapshotDecisions[key] = nowMs to it }
    }

    private suspend fun saveHistoricalActuals(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
    ): HistoricalActualsWriteSummary {
        val historicalObs = HistoricalActualsBackfill.build(
            hourly = hourlyData,
            latitude = latitude,
            longitude = longitude,
            sourceId = sourceId,
            nowMs = clock(),
        ).map { reading ->
            ObservationEntity(
                stationId = reading.stationId,
                stationName = reading.stationName,
                timestamp = reading.timestamp,
                temperature = reading.temperature,
                condition = reading.condition,
                locationLat = reading.locationLat,
                locationLon = reading.locationLon,
                distanceKm = reading.distanceKm,
                stationType = reading.stationType,
                fetchedAt = reading.fetchedAt,
                api = reading.api,
                precipAmountMm = reading.precipAmountMm,
                cloudCover = reading.cloudCover,
                cloudCoverLow = reading.cloudCoverLow,
                cloudCoverMid = reading.cloudCoverMid,
                cloudCoverHigh = reading.cloudCoverHigh,
                cloudBaseLowMeters = reading.cloudBaseLowMeters,
                cloudBaseMidMeters = reading.cloudBaseMidMeters,
                cloudBaseHighMeters = reading.cloudBaseHighMeters,
                cloudEnvelopeBaseMeters = reading.cloudEnvelopeBaseMeters,
                cloudEnvelopeTopMeters = reading.cloudEnvelopeTopMeters,
                cloudVerticalKind = reading.cloudVerticalKind,
            ).withQuantizedLocation()
        }
        if (historicalObs.isEmpty()) return HistoricalActualsWriteSummary(0, 0)
        if (sourceId != com.weatherwidget.data.model.WeatherSource.TOMORROW_IO.id) {
            observationDao.insertAll(historicalObs)
            return HistoricalActualsWriteSummary(historicalObs.size, 0)
        }
        val minTimestamp = historicalObs.minOf { it.timestamp }
        val maxTimestamp = historicalObs.maxOf { it.timestamp }
        val sample = historicalObs.first()
        val existingKeys = observationDao.getObservationsInRange(
            minTimestamp,
            maxTimestamp,
            sample.locationLat,
            sample.locationLon,
            listOf(sourceId),
        ).asSequence()
            .filter {
                it.locationLat == sample.locationLat &&
                    it.locationLon == sample.locationLon
            }
            .map { Triple(it.stationId, it.timestamp, it.api) }
            .toSet()
        val replacementCount = historicalObs.count {
            Triple(it.stationId, it.timestamp, it.api) in existingKeys
        }
        observationDao.insertAll(historicalObs)
        return HistoricalActualsWriteSummary(historicalObs.size, replacementCount)
    }

    companion object {
        @VisibleForTesting
        internal fun hasMeaningfulHourlyChange(
            existing: HourlyForecastEntity?,
            newlyFetched: HourlyForecastEntity,
        ): Boolean {
            if (existing == null) return true
            return existing.temperature != newlyFetched.temperature ||
                existing.condition != newlyFetched.condition ||
                existing.precipProbability != newlyFetched.precipProbability ||
                existing.precipAmountMm != newlyFetched.precipAmountMm ||
                existing.cloudCover != newlyFetched.cloudCover ||
                existing.cloudCoverLow != newlyFetched.cloudCoverLow ||
                existing.cloudCoverMid != newlyFetched.cloudCoverMid ||
                existing.cloudCoverHigh != newlyFetched.cloudCoverHigh
        }

        @VisibleForTesting
        internal fun siteExactExistingByDateTime(
            boxRows: List<HourlyForecastEntity>,
            lat: Double,
            lon: Double,
        ): Map<Long, HourlyForecastEntity> =
            boxRows.filter { it.locationLat == lat && it.locationLon == lon }
                .associateBy { it.dateTime }

        @VisibleForTesting
        internal fun mergePreservingNullableFields(
            existing: HourlyForecastEntity?,
            newlyFetched: HourlyForecastEntity,
        ): HourlyForecastEntity {
            if (existing == null) return newlyFetched
            return newlyFetched.copy(
                cloudCover = newlyFetched.cloudCover ?: existing.cloudCover,
                cloudCoverLow = newlyFetched.cloudCoverLow ?: existing.cloudCoverLow,
                cloudCoverMid = newlyFetched.cloudCoverMid ?: existing.cloudCoverMid,
                cloudCoverHigh = newlyFetched.cloudCoverHigh ?: existing.cloudCoverHigh,
                precipProbability = newlyFetched.precipProbability
                    ?: existing.precipProbability,
                precipAmountMm = newlyFetched.precipAmountMm ?: existing.precipAmountMm,
            )
        }
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

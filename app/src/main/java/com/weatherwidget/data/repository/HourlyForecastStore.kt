package com.weatherwidget.data.repository

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
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
) {
    data class HistoricalActualsWriteSummary(
        val rowCount: Int,
        val replacementCount: Int,
    )

    suspend fun saveHourlyEntities(rawEntities: List<HourlyForecastEntity>) {
        if (rawEntities.isEmpty()) return

        val entities = rawEntities.map {
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
        val prioritySourceIds = widgetStateManager.getActiveDisplaySourceIds()
        val changedEntities = mergedEntities.filter { merged ->
            hasMeaningfulHourlyChange(existingByDateTime[merged.dateTime], merged)
        }
        if (changedEntities.isNotEmpty()) {
            hourlyForecastDao.insertAll(changedEntities)
        }

        val historyRows = mergedEntities.map { entity ->
            HourlyForecastHistoryEntity(
                dateTime = entity.dateTime,
                locationLat = entity.locationLat,
                locationLon = entity.locationLon,
                temperature = entity.temperature,
                condition = entity.condition,
                source = entity.source,
                timestampToGroupPredictions = ForecastHistoryPolicy.timestampToGroupPredictions(
                    entity.fetchedAt,
                    entity.source,
                    prioritySourceIds,
                ),
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

    suspend fun saveHourlyEntitiesFromShared(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        historicalData: List<HourlyForecast> = hourlyData,
    ): HistoricalActualsWriteSummary {
        val now = System.currentTimeMillis()
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
        )
        return saveHistoricalActuals(historicalData, latitude, longitude, sourceId)
    }

    /** Outcome of one [backfillElapsedHistory] call, for the caller's `HOURLY_HISTORY_BACKFILL` log line. */
    data class ElapsedBackfillSummary(
        /** Elapsed hours the payload offered inside the backfill window. */
        val offered: Int,
        /** Of those, hours the site already had a history row for. */
        val covered: Int,
        /** Rows written. */
        val stored: Int,
    )

    /**
     * Files the payload's already-elapsed hours into `hourly_forecast_history` — only for hours
     * this source has no row for at this site. See [ElapsedForecastBackfill] for why: a fresh
     * install or new location has no earlier fetch to have snapshotted those hours, so the graph's
     * past forecast line is blank for a day while every payload carries the missing values.
     *
     * Never touches `hourly_forecasts`, and never rewrites an existing snapshot: an hour with any
     * history row (in any bucket, on any same-site fragment) is left alone, so on a device in
     * steady state this is a no-op every fetch. Filed under the regular [ForecastHistoryPolicy]
     * bucket for the fetch, so the row is what it is — this fetch's forecast for that hour.
     */
    suspend fun backfillElapsedHistory(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ElapsedBackfillSummary {
        val window = ElapsedForecastBackfill.window(nowMs)
        val offered = hourlyData.filter { it.dateTime in window }
        if (offered.isEmpty()) return ElapsedBackfillSummary(0, 0, 0)
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val covered = hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
            startDateTime = offered.minOf { it.dateTime },
            endDateTime = offered.maxOf { it.dateTime } + 1,
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = keyLat,
            lon = keyLon,
            source = sourceId,
        ).asSequence()
            .filter { LocationMatch.sameSite(keyLat, keyLon, it.locationLat, it.locationLon) }
            .map { it.dateTime }
            .toSet()
        val selected = ElapsedForecastBackfill.select(offered, nowMs, covered)
        if (selected.isEmpty()) {
            return ElapsedBackfillSummary(offered.size, covered.size, 0)
        }
        val bucket = ForecastHistoryPolicy.timestampToGroupPredictions(
            nowMs,
            sourceId,
            widgetStateManager.getActiveDisplaySourceIds(),
        )
        hourlyForecastHistoryDao.insertAll(
            selected.map {
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
        return ElapsedBackfillSummary(offered.size, covered.size, selected.size)
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
            nowMs = System.currentTimeMillis(),
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

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
    ) {
        val now = System.currentTimeMillis()
        // Deliberately drops elapsed hours: `hourly_forecasts` is a forecast archive, and letting a
        // `past_days` payload rewrite past rows would destroy the record of what was predicted.
        // The retro-corrected values those rows carry are not thrown away — saveHistoricalActuals
        // re-files them into `observations`, as actuals, which is what they are.
        val futureData = hourlyData.filter { it.dateTime >= now - 3_600_000L }
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
        saveHistoricalActuals(historicalData, latitude, longitude, sourceId)
    }

    private suspend fun saveHistoricalActuals(
        hourlyData: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
    ) {
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
            ).withQuantizedLocation()
        }
        if (historicalObs.isNotEmpty()) {
            observationDao.insertAll(historicalObs)
        }
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

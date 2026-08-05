package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

typealias DailyActualMap = Map<LocalDate, com.weatherwidget.data.model.DailyHistory>
typealias DailyActualsBySource = Map<String, DailyActualMap>

/**
 * Helper for resolving the most recent observed temperature from current temp records.
 */
object ObservationResolver {

    data class ObservedCurrentTemperature(
        val temperature: Float,
        val observedAt: Long,
        val source: String,
        val rowFetchedAt: Long,
    )

    /**
     * Finds the most representative observation for the specified weather source from a list of
     * _MAIN observations. For NWS, prefers the synthetic NWS_BLEND entity (IDW-weighted across
     * multiple stations) so that the daily view and graph view header show the same temperature.
     * Falls back to the most recent single-station observation when no blend is available.
     */
    fun resolveObservedCurrentTemp(
        observations: List<ObservationEntity>,
        displaySource: WeatherSource,
    ): ObservedCurrentTemperature? {
        val filtered = observations.filter {
            it.api == displaySource.id || it.api == WeatherSource.GENERIC_GAP.id
        }
        val maxTs = filtered.maxOfOrNull { it.timestamp }
        val selected = if (maxTs != null) {
            val candidates = filtered.filter { it.timestamp == maxTs }
            candidates.find { it.stationId == "NWS_BLEND" } ?: candidates.first()
        } else null
        Log.d("ObsResolver", "resolveObservedCurrentTemp: stationId=${selected?.stationId} temp=${selected?.temperature} source=${displaySource.id}")
        return selected?.let { obs ->
            ObservedCurrentTemperature(
                temperature = obs.temperature,
                observedAt = obs.timestamp,
                source = obs.api,
                rowFetchedAt = obs.fetchedAt,
            )
        }
    }

    /**
     * Aggregates raw observations into daily highs and lows, grouped by source.
     * Uses time-aligned IDW blending to match what the live widget displayed during the day.
     */
    fun aggregateObservationsToDailyBySource(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        locationLat: Double,
        locationLon: Double,
        personalStationWeight: Double = 1.0,
    ): DailyActualsBySource {
        val local = ZoneId.systemDefault()
        val sharedExtremes = ActualsAggregator.aggregate(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            locationLat = locationLat,
            locationLon = locationLon,
            zoneId = local,
            personalStationWeight = personalStationWeight,
        )

        return sharedExtremes.groupBy { it.source }
            .mapValues { (_, sourceExtremes) ->
                sourceExtremes.associateBy { it.toLocalDate() }
            }
    }

    /**
     * Computes [DailyHistoryEntity] rows from raw observations, ready for dao.insertAll().
     * Groups by (date, source) and uses time-aligned IDW blending so the stored value matches
     * what the live widget displayed during that day.
     */
    fun computeDailyExtremes(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        locationLat: Double,
        locationLon: Double,
        personalStationWeight: Double = 1.0,
    ): List<DailyHistoryEntity> {
        val local = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val sharedExtremes = ActualsAggregator.aggregate(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            locationLat = locationLat,
            locationLon = locationLon,
            zoneId = local,
            updatedAtMs = now,
            personalStationWeight = personalStationWeight,
        )

        return sharedExtremes.map { it.toEntity() }
    }

    /**
     * Converts API-provided daily extreme values embedded in raw observations into
     * [DailyHistoryEntity] rows. Observations without official max/min values are ignored.
     */
    fun officialExtremesToDailyEntities(
        observations: List<ObservationEntity>,
        locationLat: Double,
        locationLon: Double,
    ): List<DailyHistoryEntity> =
        observations
            .filter { it.maxTempLast24h != null && it.minTempLast24h != null }
            .groupBy { obs ->
                val date = Instant.ofEpochMilli(obs.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toEpochDay() * 86_400_000L // UTC midnight epoch millis approximation
                date to obs.api
            }
            .mapNotNull { (key, dayObs) ->
                val (date, source) = key
                val latestOfficialObservation = dayObs.maxByOrNull { it.timestamp } ?: return@mapNotNull null

                DailyHistoryEntity(
                    date = date,
                    source = source,
                    locationLat = locationLat,
                    locationLon = locationLon,
                    computedHighTemp = latestOfficialObservation.maxTempLast24h ?: return@mapNotNull null,
                    computedLowTemp = latestOfficialObservation.minTempLast24h ?: return@mapNotNull null,
                    condition = latestOfficialObservation.condition,
                    updatedAt = latestOfficialObservation.fetchedAt,
                )
            }

    /**
     * Maps a list of [DailyHistoryEntity] to [com.weatherwidget.data.model.DailyHistory] objects.
     */
    fun extremesToDailyActuals(extremes: List<DailyHistoryEntity>): List<com.weatherwidget.data.model.DailyHistory> =
        extremes.map { it.toDailyHistory() }

    /**
     * Maps a list of [DailyHistoryEntity] to a [DailyActualsBySource] map.
     * Picks the extreme row closest to the provided [lat]/[lon] when multiple exist for one date/source.
     */
    fun extremesToDailyActualsBySource(
        extremes: List<DailyHistoryEntity>,
        lat: Double,
        lon: Double,
    ): DailyActualsBySource {
        val local = ZoneId.systemDefault()
        return extremes
            .filter {
                Math.abs(it.locationLat - lat) <= LocationMatch.TOLERANCE_DEG &&
                    Math.abs(it.locationLon - lon) <= LocationMatch.TOLERANCE_DEG
            }
            .groupBy { it.source }
            .mapValues { (_, sourceEntities) ->
                sourceExtremesToDailyActualMap(sourceEntities, lat, lon, local)
            }
    }

    private fun sourceExtremesToDailyActualMap(
        entities: List<DailyHistoryEntity>,
        lat: Double,
        lon: Double,
        local: ZoneId,
    ): DailyActualMap {
        return entities
            .groupBy { it.toDailyHistory().toLocalDate() }
            .mapValues { (_, dateEntities) ->
                dateEntities.minBy {
                    com.weatherwidget.shared.util.TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon)
                }.toDailyHistory()
            }
    }

    /**
     * Merges two daily-actual sets. When dates collide, [primary] wins.
     * Useful for combining historical DB data with live "today" computations.
     */
    fun mergeDailyActualsBySource(
        primary: DailyActualsBySource,
        secondary: DailyActualsBySource,
    ): DailyActualsBySource {
        val allSources = primary.keys + secondary.keys
        return allSources.associateWith { source ->
            val pMap = primary[source] ?: emptyMap()
            val sMap = secondary[source] ?: emptyMap()
            sMap + pMap // Primary wins
        }
    }
}

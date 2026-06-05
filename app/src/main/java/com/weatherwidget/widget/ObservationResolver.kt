package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toDailyExtreme
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

typealias DailyActualMap = Map<LocalDate, com.weatherwidget.data.model.DailyExtreme>
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
    ): DailyActualsBySource {
        val local = ZoneId.systemDefault()
        val sharedExtremes = ActualsAggregator.aggregate(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            locationLat = locationLat,
            locationLon = locationLon,
            zoneId = local
        )

        return sharedExtremes.groupBy { it.source }
            .mapValues { (_, sourceExtremes) ->
                sourceExtremes.associateBy { it.toLocalDate() }
            }
    }

    /**
     * Computes [DailyExtremeEntity] rows from raw observations, ready for dao.insertAll().
     * Groups by (date, source) and uses time-aligned IDW blending so the stored value matches
     * what the live widget displayed during that day.
     */
    fun computeDailyExtremes(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        locationLat: Double,
        locationLon: Double,
    ): List<DailyExtremeEntity> {
        val local = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val sharedExtremes = ActualsAggregator.aggregate(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            locationLat = locationLat,
            locationLon = locationLon,
            zoneId = local,
            updatedAtMs = now
        )

        return sharedExtremes.map { it.toEntity() }
    }

    /**
     * Converts API-provided daily extreme values embedded in raw observations into
     * [DailyExtremeEntity] rows. Observations without official max/min values are ignored.
     */
    fun officialExtremesToDailyEntities(
        observations: List<ObservationEntity>,
        locationLat: Double,
        locationLon: Double,
    ): List<DailyExtremeEntity> =
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

                DailyExtremeEntity(
                    date = date,
                    source = source,
                    locationLat = locationLat,
                    locationLon = locationLon,
                    highTemp = latestOfficialObservation.maxTempLast24h ?: return@mapNotNull null,
                    lowTemp = latestOfficialObservation.minTempLast24h ?: return@mapNotNull null,
                    condition = latestOfficialObservation.condition,
                    updatedAt = latestOfficialObservation.fetchedAt,
                )
            }

    /**
     * Maps a list of [DailyExtremeEntity] to [com.weatherwidget.data.model.DailyExtreme] objects.
     */
    fun extremesToDailyActuals(extremes: List<DailyExtremeEntity>): List<com.weatherwidget.data.model.DailyExtreme> =
        extremes.map { it.toDailyExtreme() }

    /**
     * Maps a list of [DailyExtremeEntity] to a [DailyActualsBySource] map.
     * Picks the extreme row closest to the provided [lat]/[lon] when multiple exist for one date/source.
     */
    fun extremesToDailyActualsBySource(
        extremes: List<DailyExtremeEntity>,
        lat: Double,
        lon: Double,
    ): DailyActualsBySource {
        val local = ZoneId.systemDefault()
        return extremes
            .filter { it.locationLat == lat && it.locationLon == lon }
            .groupBy { it.source }
            .mapValues { (_, sourceEntities) ->
                sourceExtremesToDailyActualMap(sourceEntities, local)
            }
    }

    private fun sourceExtremesToDailyActualMap(
        entities: List<DailyExtremeEntity>,
        local: ZoneId,
    ): DailyActualMap {
        return entities.associate { entity ->
            val extreme = entity.toDailyExtreme()
            extreme.toLocalDate() to extreme
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

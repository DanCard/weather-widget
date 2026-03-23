package com.weatherwidget.widget

import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

typealias DailyActualMap = Map<LocalDate, ObservationResolver.DailyActual>
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

    data class DailyActual(
        val date: LocalDate,
        val highTemp: Float,
        val lowTemp: Float,
        val condition: String,
    )

    /**
     * Finds the latest observation for the specified weather source from a list of _MAIN observations.
     * Uses [inferSource] to match observation stationId prefixes against the display source.
     */
    fun resolveObservedCurrentTemp(
        observations: List<ObservationEntity>,
        displaySource: WeatherSource,
    ): ObservedCurrentTemperature? {
        return observations
            .filter {
                inferSource(it.stationId) == displaySource.id || inferSource(it.stationId) == WeatherSource.GENERIC_GAP.id
            }
            .maxByOrNull { it.timestamp }
            ?.let { obs ->
                ObservedCurrentTemperature(
                    temperature = obs.temperature,
                    observedAt = obs.timestamp,
                    source = inferSource(obs.stationId),
                    rowFetchedAt = obs.fetchedAt,
                )
            }
    }

    /**
     * Aggregates raw timestamped observations into actual daily highs and lows.
     */
    fun aggregateObservationsToDaily(
        observations: List<ObservationEntity>
    ): List<DailyActual> {
        val local = ZoneId.systemDefault()

        return observations
            .groupBy { obs -> Instant.ofEpochMilli(obs.timestamp).atZone(local).toLocalDate() }
            .mapNotNull { (date, obs) ->
                if (obs.isEmpty()) return@mapNotNull null

                val officialHighs = obs.mapNotNull { it.maxTempLast24h }
                val officialLows = obs.mapNotNull { it.minTempLast24h }
                val highTemp = if (officialHighs.isNotEmpty()) officialHighs.max() else obs.maxOf { it.temperature }
                val lowTemp = if (officialLows.isNotEmpty()) officialLows.min() else obs.minOf { it.temperature }

                val mostCommon = obs.map { it.condition }.groupingBy { it }.eachCount().maxByOrNull { it.value }

                DailyActual(
                    date = date,
                    highTemp = highTemp,
                    lowTemp = lowTemp,
                    condition = mostCommon?.key ?: "Unknown"
                )
            }
    }

    /**
     * Infers the WeatherSource id from a stationId prefix.
     * Mirrors TemperatureViewHandler.matchesObservationSource.
     */
    fun inferSource(stationId: String): String = when {
        stationId.startsWith("OPEN_METEO") -> WeatherSource.OPEN_METEO.id
        stationId.startsWith("WEATHER_API") -> WeatherSource.WEATHER_API.id
        stationId.startsWith("SILURIAN") -> WeatherSource.SILURIAN.id
        else -> WeatherSource.NWS.id
    }

    /**
     * Aggregates raw observations into daily highs and lows, grouped by inferred source.
     */
    fun aggregateObservationsToDailyBySource(
        observations: List<ObservationEntity>,
    ): DailyActualsBySource {
        val local = ZoneId.systemDefault()

        return observations
            .groupBy { inferSource(it.stationId) }
            .mapValues { (_, sourceObs) ->
                sourceObs
                    .groupBy { obs -> Instant.ofEpochMilli(obs.timestamp).atZone(local).toLocalDate() }
                    .mapNotNull { (date, dayObs) ->
                        if (dayObs.isEmpty()) return@mapNotNull null

                        val officialHighs = dayObs.mapNotNull { it.maxTempLast24h }
                        val officialLows = dayObs.mapNotNull { it.minTempLast24h }
                        val highTemp = if (officialHighs.isNotEmpty()) officialHighs.max() else dayObs.maxOf { it.temperature }
                        val lowTemp = if (officialLows.isNotEmpty()) officialLows.min() else dayObs.minOf { it.temperature }
                        val mostCommonCondition = dayObs
                            .map { it.condition }
                            .groupingBy { it }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key ?: "Unknown"

                        date to DailyActual(
                            date = date,
                            highTemp = highTemp,
                            lowTemp = lowTemp,
                            condition = mostCommonCondition,
                        )
                    }
                    .toMap()
            }
    }

    /**
     * Computes [DailyExtremeEntity] rows from raw observations, ready for dao.insertAll().
     * Groups by (date, inferred source), applies official 24h extremes with spot-reading fallback.
     *
     * @param observations raw observations for one or more days
     * @param locationLat widget location latitude (stored on the entity for range queries)
     * @param locationLon widget location longitude
     */
    fun computeDailyExtremes(
        observations: List<ObservationEntity>,
        locationLat: Double,
        locationLon: Double,
    ): List<DailyExtremeEntity> {
        val local = ZoneId.systemDefault()
        val now = System.currentTimeMillis()

        val filteredObs = observations.filter { it.stationId != "NWS_MAIN" }

        return filteredObs
            .groupBy { obs ->
                val date = Instant.ofEpochMilli(obs.timestamp)
                    .atZone(local)
                    .toLocalDate()
                    .toEpochDay() * 86400_000L
                date to inferSource(obs.stationId)
            }
            .mapNotNull { (key, dayObs) ->
                if (dayObs.isEmpty()) return@mapNotNull null
                val (date, source) = key

                val officialHighs = dayObs.mapNotNull { it.maxTempLast24h }
                val officialLows = dayObs.mapNotNull { it.minTempLast24h }
                val highTemp = if (officialHighs.isNotEmpty()) officialHighs.max() else dayObs.maxOf { it.temperature }
                val lowTemp = if (officialLows.isNotEmpty()) officialLows.min() else dayObs.minOf { it.temperature }
                val condition = dayObs
                    .map { it.condition }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: "Unknown"

                DailyExtremeEntity(
                    date = date,
                    source = source,
                    locationLat = locationLat,
                    locationLon = locationLon,
                    highTemp = highTemp,
                    lowTemp = lowTemp,
                    condition = condition,
                    updatedAt = now,
                )
            }
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
                    .toEpochDay() * 86400_000L
                date to inferSource(obs.stationId)
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
     * Maps a list of [DailyExtremeEntity] to [DailyActual] objects.
     */
    fun extremesToDailyActuals(extremes: List<DailyExtremeEntity>): List<DailyActual> =
        extremes.map { entity ->
            val date = LocalDate.ofEpochDay(entity.date / 86400_000L)
            DailyActual(
                date = date,
                highTemp = entity.highTemp,
                lowTemp = entity.lowTemp,
                condition = entity.condition,
            )
        }

    /**
     * Maps a list of [DailyExtremeEntity] to a [DailyActualsBySource] map.
     */
    fun extremesToDailyActualsBySource(extremes: List<DailyExtremeEntity>): DailyActualsBySource =
        extremes
            .groupBy { it.source }
            .mapValues { (_, sourceExtremes) ->
                sourceExtremes.associate { entity ->
                    val date = LocalDate.ofEpochDay(entity.date / 86400_000L)
                    date to DailyActual(
                        date = date,
                        highTemp = entity.highTemp,
                        lowTemp = entity.lowTemp,
                        condition = entity.condition,
                    )
                }
            }
}

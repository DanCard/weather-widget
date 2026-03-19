package com.weatherwidget.widget

import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.ZoneId

typealias DailyActualMap = Map<String, ObservationResolver.DailyActual>
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
        val date: String,
        val highTemp: Float,
        val lowTemp: Float,
        val condition: String,
    )

    /**
     * Finds the latest observation for the specified weather source from a list of current temp records.
     * Includes fallback to GENERIC_GAP source.
     */
    fun resolveObservedCurrentTemp(
        currentTemps: List<CurrentTempEntity>,
        displaySource: WeatherSource,
    ): ObservedCurrentTemperature? {
        return currentTemps
            .filter {
                it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id
            }
            .maxByOrNull { it.observedAt }
            ?.let { entity ->
                ObservedCurrentTemperature(
                    temperature = entity.temperature,
                    observedAt = entity.observedAt,
                    source = entity.source,
                    rowFetchedAt = entity.fetchedAt,
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
        val dateFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

        val observationsByDate = observations.groupBy { obs ->
            Instant.ofEpochMilli(obs.timestamp)
                .atZone(local)
                .toLocalDate()
                .format(dateFormatter)
        }

        return observationsByDate.mapNotNull { (date, obs) ->
            if (obs.isEmpty()) return@mapNotNull null

            val officialHighs = obs.mapNotNull { it.maxTempLast24h }
            val officialLows = obs.mapNotNull { it.minTempLast24h }
            val highTemp = if (officialHighs.isNotEmpty()) officialHighs.max() else obs.maxOf { it.temperature }
            val lowTemp = if (officialLows.isNotEmpty()) officialLows.min() else obs.minOf { it.temperature }

            val conditions = obs.map { it.condition }
            val mostCommon = conditions.groupingBy { it }.eachCount().maxByOrNull { it.value }

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
        val dateFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

        return observations
            .groupBy { inferSource(it.stationId) }
            .mapValues { (_, sourceObs) ->
                sourceObs
                    .groupBy { obs ->
                        Instant.ofEpochMilli(obs.timestamp)
                            .atZone(local)
                            .toLocalDate()
                            .format(dateFormatter)
                    }
                    .mapNotNull { (date, dayObs) ->
                        if (dayObs.isEmpty()) return@mapNotNull null

                        val officialHighs = dayObs.mapNotNull { it.maxTempLast24h }
                        val officialLows = dayObs.mapNotNull { it.minTempLast24h }
                        val highTemp = if (officialHighs.isNotEmpty()) officialHighs.max() else dayObs.maxOf { it.temperature }
                        val lowTemp = if (officialLows.isNotEmpty()) officialLows.min() else dayObs.minOf { it.temperature }
                        val mostCommonCondition =
                            dayObs
                                .map { it.condition }
                                .groupingBy { it }
                                .eachCount()
                                .maxByOrNull { it.value }
                                ?.key
                                ?: "Unknown"

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
        val dateFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        val now = System.currentTimeMillis()

        return observations
            .groupBy { obs ->
                val date = Instant.ofEpochMilli(obs.timestamp)
                    .atZone(local)
                    .toLocalDate()
                    .format(dateFormatter)
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
     * Maps a list of [DailyExtremeEntity] to [DailyActual] objects.
     */
    fun extremesToDailyActuals(extremes: List<DailyExtremeEntity>): List<DailyActual> =
        extremes.map { entity ->
            DailyActual(
                date = entity.date,
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
                    entity.date to DailyActual(
                        date = entity.date,
                        highTemp = entity.highTemp,
                        lowTemp = entity.lowTemp,
                        condition = entity.condition,
                    )
                }
            }
}

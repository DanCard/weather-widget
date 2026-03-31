package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SpatialInterpolator
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
            inferSource(it.stationId) == displaySource.id || inferSource(it.stationId) == WeatherSource.GENERIC_GAP.id
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

                val (highTemp, lowTemp) = blendExtremes(obs)

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
     * IDW-blends daily high/low from a list of observations.
     * Groups by stationId first so multiple readings from the same station are aggregated
     * (max extreme / min extreme) before spatial blending across unique stations.
     * Falls back to IDW of spot-temperature max/min, then raw max/min.
     */
    private fun blendExtremes(obs: List<ObservationEntity>): Pair<Float, Float> {
        data class StationData(
            val distanceKm: Float,
            val high: Float,
            val low: Float,
        )
        val byStation = obs.groupBy { it.stationId }.values.map { stObs ->
            val maxExtreme = stObs.mapNotNull { it.maxTempLast24h }.maxOrNull()
            val minExtreme = stObs.mapNotNull { it.minTempLast24h }.minOrNull()
            val maxSpot = stObs.maxOf { it.temperature }
            val minSpot = stObs.minOf { it.temperature }
            
            StationData(
                distanceKm = stObs.first().distanceKm,
                // For each station, the "high" is the max of its official 24h extreme 
                // and any spot readings we've seen today.
                high = maxOf(maxExtreme ?: maxSpot, maxSpot),
                low = minOf(minExtreme ?: minSpot, minSpot),
            )
        }

        val highPairs = byStation.map { it.distanceKm to it.high }
        val lowPairs  = byStation.map { it.distanceKm to it.low }

        val high = SpatialInterpolator.interpolateIDWValues(highPairs)
            ?: obs.maxOf { it.temperature }
        val low = SpatialInterpolator.interpolateIDWValues(lowPairs)
            ?: obs.minOf { it.temperature }
            
        return high to low
    }

    /**
     * Infers the WeatherSource id from a stationId prefix.
     * Mirrors TemperatureViewHandler.matchesObservationSource.
     */
    fun inferSource(stationId: String): String = when {
        stationId.startsWith("OPEN_WEATHER_MAP") -> WeatherSource.OPEN_WEATHER_MAP.id
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

                        val (highTemp, lowTemp) = blendExtremes(dayObs)
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

        val filteredObs = observations.filter { it.stationId != "NWS_BLEND" }

        return filteredObs
            .groupBy { obs ->
                val date = Instant.ofEpochMilli(obs.timestamp)
                    .atZone(local)
                    .toLocalDate()
                    .toEpochDay() * WidgetConstants.MS_IN_A_DAY
                date to inferSource(obs.stationId)
            }
            .mapNotNull { (key, dayObs) ->
                if (dayObs.isEmpty()) return@mapNotNull null
                val (date, source) = key

                val (highTemp, lowTemp) = blendExtremes(dayObs)
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
                    .toEpochDay() * WidgetConstants.MS_IN_A_DAY
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
            val date = LocalDate.ofEpochDay(entity.date / WidgetConstants.MS_IN_A_DAY)
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
                    val date = LocalDate.ofEpochDay(entity.date / WidgetConstants.MS_IN_A_DAY)
                    date to DailyActual(
                        date = date,
                        highTemp = entity.highTemp,
                        lowTemp = entity.lowTemp,
                        condition = entity.condition,
                    )
                }
            }
}

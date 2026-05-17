package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.ObservationBlender
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
     * Runs [ObservationBlender.blendObservationSeries] for one (date, source) bucket and
     * returns the time-aligned high/low. This is the shared core that replaces the legacy
     * per-station-spot-max blend: each blended sample is an IDW combination of stations at
     * a single instant, so taking max/min over the series produces a physically real value
     * (one the live widget would have displayed at some point in the day).
     */
    private fun blendDailyExtremesViaSeries(
        dayObs: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        sourceId: String,
        locationLat: Double,
        locationLon: Double,
        dayStartMs: Long,
        dayEndMs: Long,
    ): Pair<Float, Float>? {
        if (dayObs.isEmpty()) return null
        val source = WeatherSource.fromId(sourceId)
        val result = ObservationBlender.blendObservationSeries(
            observations = dayObs,
            hourlyForecasts = hourlyForecasts,
            displaySource = source,
            userLat = locationLat,
            userLon = locationLon,
            startMs = dayStartMs,
            endMs = dayEndMs,
        )
        val series = result.observations
        if (series.isEmpty()) return null
        val high = series.maxOf { it.temperature }
        val low = series.minOf { it.temperature }
        Log.d(
            "ObsResolver",
            "blendDailyExtremesViaSeries: source=$sourceId stations=${result.stats.stationCount} " +
                "emittedPoints=${result.stats.emittedPointCount} high=$high low=$low",
        )
        return high to low
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

        return observations
            .filter { it.stationId != "NWS_BLEND" }
            .groupBy { it.api }
            .mapValues { (sourceId, obsList) ->
                obsList
                    .groupBy { obs -> Instant.ofEpochMilli(obs.timestamp).atZone(local).toLocalDate() }
                    .mapNotNull { (date, dayObs) ->
                        val dayStartMs = date.atStartOfDay(local).toInstant().toEpochMilli()
                        val dayEndMs = date.plusDays(1).atStartOfDay(local).toInstant().toEpochMilli()
                        val (highTemp, lowTemp) = blendDailyExtremesViaSeries(
                            dayObs = dayObs,
                            hourlyForecasts = hourlyForecasts,
                            sourceId = sourceId,
                            locationLat = locationLat,
                            locationLon = locationLon,
                            dayStartMs = dayStartMs,
                            dayEndMs = dayEndMs,
                        ) ?: return@mapNotNull null

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

        val filteredObs = observations.filter { it.stationId != "NWS_BLEND" }

        return filteredObs
            .groupBy { obs ->
                val date = Instant.ofEpochMilli(obs.timestamp).atZone(local).toLocalDate()
                date to obs.api
            }
            .mapNotNull { (key, dayObs) ->
                val (date, sourceId) = key
                val dayStartMs = date.atStartOfDay(local).toInstant().toEpochMilli()
                val dayEndMs = date.plusDays(1).atStartOfDay(local).toInstant().toEpochMilli()
                val (highTemp, lowTemp) = blendDailyExtremesViaSeries(
                    dayObs = dayObs,
                    hourlyForecasts = hourlyForecasts,
                    sourceId = sourceId,
                    locationLat = locationLat,
                    locationLon = locationLon,
                    dayStartMs = dayStartMs,
                    dayEndMs = dayEndMs,
                ) ?: return@mapNotNull null

                val condition = dayObs
                    .map { it.condition }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: "Unknown"

                DailyExtremeEntity(
                    date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = sourceId,
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
     * Picks the extreme row closest to the provided [lat]/[lon] when multiple exist for one date/source.
     */
    fun extremesToDailyActualsBySource(
        extremes: List<DailyExtremeEntity>,
        lat: Double,
        lon: Double,
    ): DailyActualsBySource =
        extremes
            .groupBy { it.source }
            .mapValues { (_, sourceExtremes) ->
                sourceExtremes
                    .groupBy { LocalDate.ofEpochDay(it.date / WidgetConstants.MS_IN_A_DAY) }
                    .mapValues { (_, dateExtremes) ->
                        // If multiple locations exist for the same day/source, pick the closest one
                        val closest = dateExtremes.minBy { 
                            com.weatherwidget.util.TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon)
                        }
                        DailyActual(
                            date = LocalDate.ofEpochDay(closest.date / WidgetConstants.MS_IN_A_DAY),
                            highTemp = closest.highTemp,
                            lowTemp = closest.lowTemp,
                            condition = closest.condition,
                        )
                    }
            }

    /**
     * Merges per-source daily actuals while preserving the widest known high/low bounds for
     * overlapping dates. Later values win only for metadata like condition text.
     */
    fun mergeDailyActualsBySource(
        primary: DailyActualsBySource,
        secondary: DailyActualsBySource,
    ): DailyActualsBySource =
        (primary.keys + secondary.keys).associateWith { source ->
            mergeDailyActualMap(
                primary[source].orEmpty(),
                secondary[source].orEmpty(),
            )
        }

    private fun mergeDailyActualMap(
        primary: DailyActualMap,
        secondary: DailyActualMap,
    ): DailyActualMap =
        (primary.keys + secondary.keys).associateWith { date ->
            mergeDailyActual(primary[date], secondary[date])
        }.filterValues { it != null }
            .mapValues { (_, actual) -> checkNotNull(actual) }

    private fun mergeDailyActual(
        primary: DailyActual?,
        secondary: DailyActual?,
    ): DailyActual? =
        when {
            primary == null -> secondary
            secondary == null -> primary
            else ->
                DailyActual(
                    date = primary.date,
                    highTemp = maxOf(primary.highTemp, secondary.highTemp),
                    lowTemp = minOf(primary.lowTemp, secondary.lowTemp),
                    condition = secondary.condition.ifBlank { primary.condition },
                )
        }
}

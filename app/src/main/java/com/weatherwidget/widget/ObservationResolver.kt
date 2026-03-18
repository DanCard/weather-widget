package com.weatherwidget.widget

import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.HourlyActualEntity
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

            val highTemp = obs.maxOf { it.temperature }
            val lowTemp = obs.minOf { it.temperature }

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
     * Aggregates source-scoped hourly actuals into daily highs and lows.
     */
    fun aggregateHourlyActualsToDailyBySource(
        actuals: List<HourlyActualEntity>,
    ): DailyActualsBySource {
        return actuals
            .groupBy { it.source }
            .mapValues { (_, sourceActuals) ->
                sourceActuals
                    .groupBy { it.dateTime.substringBefore('T') }
                    .mapNotNull { (date, dayActuals) ->
                        if (dayActuals.isEmpty()) return@mapNotNull null

                        val highTemp = dayActuals.maxOf { it.temperature }
                        val lowTemp = dayActuals.minOf { it.temperature }
                        val mostCommonCondition =
                            dayActuals
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
}

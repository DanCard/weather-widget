package com.weatherwidget.stats

import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.ActualPrecipSource
import com.weatherwidget.shared.graph.DayNightHours
import com.weatherwidget.shared.stats.DailyRainAccuracy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a day-by-day comparison of predicted vs actual rainfall, split into clock-based day
 * (8a-8p) and night (8p-8a) buckets (see [DayNightHours]).
 *
 * Predicted rainfall is reconstructed from the *prior calendar day's* hourly forecast snapshot
 * (mirroring the 1-day-ahead convention in [AccuracyCalculator]); actual rainfall comes from
 * observed hourly precipitation. Bucketing logic is shared with the widget precip graph so the two
 * surfaces always agree.
 */
@Singleton
class RainAccuracyCalculator
    @Inject
    constructor(
        private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
        private val observationDao: ObservationDao,
    ) {
        suspend fun getDailyRainAccuracy(
            source: WeatherSource,
            lat: Double,
            lon: Double,
            days: Int = 30,
            zoneId: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(),
        ): List<DailyRainAccuracy> {
            val endDate = today.minusDays(1)
            val startDate = endDate.minusDays(days.toLong() - 1)

            val results = mutableListOf<DailyRainAccuracy>()
            var date = startDate
            while (!date.isAfter(endDate)) {
                val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val dayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                // Forecast captured during the prior calendar day = "1-day-ahead" prediction.
                val bucketStartMs = date.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val bucketEndMs = dayStartMs

                val predicted = hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
                    startDateTime = dayStartMs,
                    endDateTime = dayEndMs,
                    bucketStart = bucketStartMs,
                    bucketEnd = bucketEndMs,
                    lat = lat,
                    lon = lon,
                    source = source.id,
                )
                val predByHour = latestSnapshotPrecipByHour(predicted, zoneId)

                val observations = observationDao.getObservationsInRange(dayStartMs, dayEndMs, lat, lon)
                val actualByHour = actualPrecipByHour(observations, source, zoneId)

                val (predDay, predNight) = bucketDayNight(predByHour)
                val (actualDay, actualNight) = bucketDayNight(actualByHour)

                if (predDay != null || predNight != null || actualDay != null || actualNight != null) {
                    results.add(
                        DailyRainAccuracy(
                            date = date.toString(),
                            source = source.displayName,
                            predDayMm = predDay,
                            actualDayMm = actualDay,
                            predNightMm = predNight,
                            actualNightMm = actualNight,
                        ),
                    )
                }
                date = date.plusDays(1)
            }
            return results
        }

        // Pure bucketing helpers live in the companion so they're testable without a database
        // (the project has no mocking framework — see testing strategy notes).
        companion object {
            /** Latest-snapshot predicted precip per local hour (rows arrive sorted snapshotBucket DESC). */
            internal fun latestSnapshotPrecipByHour(
                rows: List<HourlyForecastHistoryEntity>,
                zoneId: ZoneId = ZoneId.systemDefault(),
            ): Map<LocalDateTime, Float> =
                rows
                    .groupBy { hourOf(it.dateTime, zoneId) }
                    .mapNotNull { (hour, hourRows) ->
                        val amount = hourRows.maxByOrNull { it.timestampToGroupPredictions }?.precipAmountMm
                            ?: return@mapNotNull null
                        hour to amount
                    }
                    .toMap()

            /** Observed precip per local hour, filtered to [source]'s actual series and summed. */
            internal fun actualPrecipByHour(
                observations: List<ObservationEntity>,
                source: WeatherSource,
                zoneId: ZoneId = ZoneId.systemDefault(),
            ): Map<LocalDateTime, Float> =
                observations
                    .asSequence()
                    .filter { ActualPrecipSource.matches(it, source) }
                    .mapNotNull { obs ->
                        val amount = obs.precipAmountMm ?: return@mapNotNull null
                        hourOf(obs.timestamp, zoneId) to amount
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, amounts) -> amounts.sum() }

            /**
             * Sums [amountByHour] into (day, night) totals. A bucket is null when no hour fell in
             * it, distinguishing "no data captured" from a genuine 0mm (dry but forecast/observed).
             */
            internal fun bucketDayNight(amountByHour: Map<LocalDateTime, Float>): Pair<Float?, Float?> {
                val day = amountByHour.filterKeys { DayNightHours.isDayHour(it) }
                val night = amountByHour.filterKeys { !DayNightHours.isDayHour(it) }
                return day.values.sumOrNull() to night.values.sumOrNull()
            }

            private fun Collection<Float>.sumOrNull(): Float? = if (isEmpty()) null else sum()

            private fun hourOf(epochMs: Long, zoneId: ZoneId): LocalDateTime =
                Instant.ofEpochMilli(epochMs)
                    .atZone(zoneId)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.HOURS)
        }
    }

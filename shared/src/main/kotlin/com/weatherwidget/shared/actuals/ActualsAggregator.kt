package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyExtreme
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Aggregates raw observations into daily highs and lows, grouped by source.
 * Uses time-aligned IDW blending to match what the live widget displayed during the day.
 */
object ActualsAggregator {

    private const val DAY_START_HOUR = 8
    private const val DAY_END_HOUR = 20

    data class DailyPrecip(val total: Float?, val day: Float?, val night: Float?)

    /**
     * Resolves the current observed temperature by blending the latest station observations.
     */
    fun resolveCurrentObservation(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long = System.currentTimeMillis(),
        lookbackHours: Long = 12L,
        lookaheadHours: Long = 3L,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Triple<Float, Long, Long>? {
        val truncatedMs = (nowMs / 3600_000L) * 3600_000L
        val minute = (nowMs % 3600_000L) / 60_000L
        val alignedCenterMs = if (minute >= 30) truncatedMs + 3600_000L else truncatedMs

        val contextStartMs = alignedCenterMs - (lookbackHours * 3600_000L)
        val contextEndMs = alignedCenterMs + (lookaheadHours * 3600_000L)

        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            startMs = contextStartMs,
            endMs = contextEndMs,
            onBlendDebug = null,
        )

        val pastBlended = result.observations.filter { it.timestamp <= nowMs }

        val latestObs = pastBlended
            .filter { it.condition == "observed" }
            .maxByOrNull { it.timestamp }
            ?: pastBlended
                .filter { it.condition == "interpolated" }
                .maxByOrNull { it.timestamp }


        return latestObs?.let { Triple(it.temperature, it.timestamp, it.fetchedAt) }
    }

    /**
     * Aggregates raw observations into daily highs and lows.
     */
    fun aggregate(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        locationLat: Double,
        locationLon: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
        updatedAtMs: Long = System.currentTimeMillis()
    ): List<DailyExtreme> {
        val today = LocalDate.now(zoneId)

        return observations
            .filter { it.stationId != "NWS_BLEND" }
            .groupBy { it.api }
            .flatMap { (sourceId, obsList) ->
                val sourceHourly = hourlyForecasts.filter { it.source == sourceId || it.source == "GENERIC_GAP" }
                obsList
                    .groupBy { obs -> Instant.ofEpochMilli(obs.timestamp).atZone(zoneId).toLocalDate() }
                    .mapNotNull { (date, dayObs) ->
                        val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val dayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                        
                        val (highTemp, lowTemp) = blendDailyExtremesViaSeries(
                            dayObs = dayObs,
                            hourlyForecasts = sourceHourly,
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

                        val precip = resolveDailyPrecip(
                            dayObs, sourceHourly, date, zoneId,
                            allowForecastFallback = !date.isBefore(today),
                        )

                        DailyExtreme(
                            date = date.toEpochDay() * 86_400_000L, // UTC midnight epoch millis approximation
                            source = sourceId,
                            locationLat = locationLat,
                            locationLon = locationLon,
                            highTemp = highTemp,
                            lowTemp = lowTemp,
                            condition = mostCommonCondition,
                            updatedAt = updatedAtMs,
                            precipAmountMm = precip.total,
                            precipDayMm = precip.day,
                            precipNightMm = precip.night,
                        )
                    }
            }
    }

    private fun blendDailyExtremesViaSeries(
        dayObs: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        sourceId: String,
        locationLat: Double,
        locationLon: Double,
        dayStartMs: Long,
        dayEndMs: Long,
    ): Pair<Float, Float>? {
        if (dayObs.isEmpty()) return null
        
        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = dayObs,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = sourceId,
            userLat = locationLat,
            userLon = locationLon,
            startMs = dayStartMs,
            endMs = dayEndMs,
            onBlendDebug = null,
        )
        
        val series = result.observations
        if (series.isEmpty()) return null
        val high = series.maxOf { it.temperature }
        val low = series.minOf { it.temperature }
        return high to low
    }

    fun resolveDailyPrecip(
        dayObs: List<ObservationReading>,
        sourceHourly: List<HourlyForecast>,
        date: LocalDate,
        zone: ZoneId,
        allowForecastFallback: Boolean = true,
    ): DailyPrecip {
        if (dayObs.any { it.precipAmountMm != null }) {
            return DailyPrecip(
                total = dayObs.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum(),
                day = sumDaytimePrecip(dayObs, date, zone),
                night = sumNighttimePrecip(dayObs, date, zone),
            )
        }
        
        if (!allowForecastFallback) {
            return DailyPrecip(total = null, day = null, night = null)
        }

        // Forecast fallback
        val hourlyForDate = sourceHourly
            .filter { Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() == date }
        
        val total = hourlyForDate.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()
        val day = hourlyForDate.filter { 
            Instant.ofEpochMilli(it.dateTime).atZone(zone).hour in DAY_START_HOUR until DAY_END_HOUR 
        }.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()
        val night = hourlyForDate.filter { 
            val hour = Instant.ofEpochMilli(it.dateTime).atZone(zone).hour
            hour < DAY_START_HOUR || hour >= DAY_END_HOUR
        }.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()

        return DailyPrecip(total = total, day = day, night = night)
    }

    private fun sumDaytimePrecip(obs: List<ObservationReading>, date: LocalDate, zone: ZoneId): Float? {
        val daytime = obs.filter {
            val dt = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            dt.toLocalDate() == date && dt.hour in DAY_START_HOUR until DAY_END_HOUR
        }.mapNotNull { it.precipAmountMm }
        return if (daytime.isEmpty()) null else daytime.sum()
    }

    private fun sumNighttimePrecip(obs: List<ObservationReading>, date: LocalDate, zone: ZoneId): Float? {
        val night1 = obs.filter {
            val dt = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            dt.toLocalDate() == date && dt.hour < DAY_START_HOUR
        }.mapNotNull { it.precipAmountMm }

        val night2 = obs.filter {
            val dt = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            dt.toLocalDate() == date && dt.hour >= DAY_END_HOUR
        }.mapNotNull { it.precipAmountMm }

        val all = night1 + night2
        return if (all.isEmpty()) null else all.sum()
    }
}

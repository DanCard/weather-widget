package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object GraphDataLoader {
    internal fun buildGraphQueryWindow(
        centerTime: LocalDateTime,
        zoom: ZoomLevel,
        now: LocalDateTime,
    ): GraphQueryWindow {
        val truncatedCenter = centerTime.truncatedTo(ChronoUnit.HOURS)
        val roundedCenter = if (centerTime.minute >= 30) truncatedCenter.plusHours(1) else truncatedCenter
        val centerStart = roundedCenter.minusHours(zoom.backHours)
        val centerEnd = roundedCenter.plusHours(zoom.forwardHours)

        val nowStart = now.truncatedTo(ChronoUnit.HOURS)
        val nowEnd = nowStart.plusHours(1)
        val overlaps = !nowEnd.isBefore(centerStart) && !nowStart.isAfter(centerEnd)

        return if (overlaps) {
            GraphQueryWindow(centerStart = centerStart, centerEnd = centerEnd, nowStart = null, nowEnd = null)
        } else {
            GraphQueryWindow(centerStart = centerStart, centerEnd = centerEnd, nowStart = nowStart, nowEnd = nowEnd)
        }
    }

    internal data class GraphQueryWindow(
        val centerStart: LocalDateTime,
        val centerEnd: LocalDateTime,
        val nowStart: LocalDateTime?,
        val nowEnd: LocalDateTime?,
    )

    suspend fun loadGraphWindowHourlyForecasts(
        hourlyDao: HourlyForecastDao,
        hourlyHistoryDao: HourlyForecastHistoryDao? = null,
        lat: Double,
        lon: Double,
        centerTime: LocalDateTime,
        zoom: ZoomLevel,
        now: LocalDateTime,
        source: WeatherSource? = null,
    ): List<HourlyForecastEntity> {
        val window = buildGraphQueryWindow(centerTime, zoom, now)
        val zoneId = ZoneId.systemDefault()
        val centerStartMs = window.centerStart.atZone(zoneId).toInstant().toEpochMilli()
        val centerEndMs = window.centerEnd.atZone(zoneId).toInstant().toEpochMilli()

        val centerRows = if (source != null) {
            hourlyDao.getHourlyForecastsBySource(centerStartMs, centerEndMs, lat, lon, source.id)
        } else {
            hourlyDao.getHourlyForecasts(centerStartMs, centerEndMs, lat, lon)
        }.filter {
            Math.abs(it.locationLat - lat) < 0.0001 && Math.abs(it.locationLon - lon) < 0.0001
        }
        val currentRows = if (window.nowStart == null || window.nowEnd == null) {
            centerRows
        } else {
            val nowStartMs = window.nowStart.atZone(zoneId).toInstant().toEpochMilli()
            val nowEndMs = window.nowEnd.atZone(zoneId).toInstant().toEpochMilli()

            val nowRows = if (source != null) {
                hourlyDao.getHourlyForecastsBySource(nowStartMs, nowEndMs, lat, lon, source.id)
            } else {
                hourlyDao.getHourlyForecasts(nowStartMs, nowEndMs, lat, lon)
            }.filter {
                Math.abs(it.locationLat - lat) < 0.0001 && Math.abs(it.locationLon - lon) < 0.0001
            }

            (centerRows + nowRows)
                .distinctBy { "${it.dateTime}|${it.source}|${it.locationLat}|${it.locationLon}" }
                .sortedBy { it.dateTime }
        }

        if (hourlyHistoryDao == null || source == null) {
            return currentRows
        }

        val historyRows = run {
            val sourceId = source.id
            hourlyHistoryDao.getHistoryInRangeForBucketWindow(
                startDateTime = centerStartMs,
                endDateTime = centerEndMs,
                bucketStart = Long.MIN_VALUE,
                bucketEnd = Long.MAX_VALUE,
                lat = lat,
                lon = lon,
                source = sourceId,
            ).map {
                HourlyForecastEntity(
                    dateTime = it.dateTime,
                    locationLat = it.locationLat,
                    locationLon = it.locationLon,
                    temperature = it.temperature,
                    condition = it.condition,
                    source = it.source,
                    precipProbability = it.precipProbability,
                    cloudCover = it.cloudCover,
                    precipAmountMm = it.precipAmountMm,
                    fetchedAt = it.fetchedAt,
                )
            }
        }

        val historyByTime = historyRows.associateBy { it.dateTime }
        val stitched = linkedMapOf<Long, HourlyForecastEntity>()
        for (row in historyRows) {
            stitched[row.dateTime] = row
        }
        for (row in currentRows) {
            val historical = historyByTime[row.dateTime]
            stitched[row.dateTime] = if (historical != null) {
                row.copy(
                    cloudCover = row.cloudCover ?: historical.cloudCover,
                )
            } else {
                row
            }
        }

        return stitched.values.sortedBy { it.dateTime }
    }

    suspend fun loadCurrentTempResolutionHourlyForecasts(
        hourlyDao: HourlyForecastDao,
        lat: Double,
        lon: Double,
        now: LocalDateTime,
    ): List<HourlyForecastEntity> {
        val window = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val zoneId = ZoneId.systemDefault()
        return hourlyDao.getHourlyForecasts(
            window.start.atZone(zoneId).toInstant().toEpochMilli(),
            window.end.atZone(zoneId).toInstant().toEpochMilli(),
            lat,
            lon,
        ).filter {
            Math.abs(it.locationLat - lat) < 0.0001 && Math.abs(it.locationLon - lon) < 0.0001
        }
    }
}

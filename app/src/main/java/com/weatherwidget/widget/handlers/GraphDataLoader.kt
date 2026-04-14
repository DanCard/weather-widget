package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastDao
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

    @VisibleForTesting
    internal fun buildCurrentTempResolutionWindow(
        now: LocalDateTime,
    ): CurrentTempResolutionWindow {
        val truncatedNow = now.truncatedTo(ChronoUnit.HOURS)
        val roundedNow = if (now.minute >= 30) truncatedNow.plusHours(1) else truncatedNow
        return CurrentTempResolutionWindow(
            start = roundedNow.minusHours(12L),
            end = roundedNow.plusHours(2L),
        )
    }

    @VisibleForTesting
    internal data class CurrentTempResolutionWindow(
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    suspend fun loadGraphWindowHourlyForecasts(
        hourlyDao: HourlyForecastDao,
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
        }

        if (window.nowStart == null || window.nowEnd == null) {
            return centerRows
        }

        val nowStartMs = window.nowStart.atZone(zoneId).toInstant().toEpochMilli()
        val nowEndMs = window.nowEnd.atZone(zoneId).toInstant().toEpochMilli()

        val nowRows = if (source != null) {
            hourlyDao.getHourlyForecastsBySource(nowStartMs, nowEndMs, lat, lon, source.id)
        } else {
            hourlyDao.getHourlyForecasts(nowStartMs, nowEndMs, lat, lon)
        }

        return (centerRows + nowRows)
            .distinctBy { "${it.dateTime}|${it.source}|${it.locationLat}|${it.locationLon}" }
            .sortedBy { it.dateTime }
    }

    suspend fun loadCurrentTempResolutionHourlyForecasts(
        hourlyDao: HourlyForecastDao,
        lat: Double,
        lon: Double,
        now: LocalDateTime,
    ): List<HourlyForecastEntity> {
        val window = buildCurrentTempResolutionWindow(now)
        val zoneId = ZoneId.systemDefault()
        return hourlyDao.getHourlyForecasts(
            window.start.atZone(zoneId).toInstant().toEpochMilli(),
            window.end.atZone(zoneId).toInstant().toEpochMilli(),
            lat,
            lon,
        )
    }
}
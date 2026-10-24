package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.graph.PriorDayCloudForecast
import com.weatherwidget.shared.graph.PriorDayCloudRun

/**
 * Room side of [PriorDayCloudRun] at one site: stores the day-ago cloud series in
 * `hourly_forecast_history` under [PriorDayCloudForecast.SOURCE_ID]. Store → read-back
 * (`HourlyForecastHistoryDao.getPriorDayCloudForecast`) is pinned to desktop's by
 * `PriorDayCloudHostContract` (`:shared` testFixtures).
 */
internal class RoomPriorDayCloudHost(
    private val dao: HourlyForecastHistoryDao,
    private val appLogDao: AppLogDao,
    private val latitude: Double,
    private val longitude: Double,
    lastAttempt: PriorDayCloudRun.LastAttempt,
    private val fetch: suspend (pastDays: Int, nowMs: Long) -> Map<Long, Int>,
) : PriorDayCloudRun.Host {
    override var lastAttemptMs by lastAttempt::ms

    override suspend fun fetch(pastDays: Int, nowMs: Long) = fetch.invoke(pastDays, nowMs)

    override suspend fun store(coverByHour: Map<Long, Int>, nowMs: Long) = dao.insertAll(
        coverByHour.map { (hourMs, cover) ->
            HourlyForecastHistoryEntity(
                dateTime = hourMs,
                // Quantized so GPS jitter overwrites instead of fragmenting the site.
                locationLat = LocationMatch.quantize(latitude),
                locationLon = LocationMatch.quantize(longitude),
                // NOT NULL in the schema but meaningless here; only cloud is read back.
                temperature = 0f,
                condition = "prior-run cloud",
                source = PriorDayCloudForecast.SOURCE_ID,
                timestampToGroupPredictions = PriorDayCloudForecast.predictionBucketFor(hourMs),
                // The previous-runs variable is the total column, filed where `cloudCover`
                // means it everywhere else; old LOW-variable rows are read through
                // VisibleCloudCover's band fallback.
                cloudCover = cover,
                cloudCoverLow = null,
                fetchedAt = nowMs,
            )
        },
    )

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}

package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.hourlySummary
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.withHourlySummary
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.shared.util.PredictionDate
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate

/**
 * Room side of [ForecastPersistRun] for one source at one site: every read on the site's exact
 * quantized key, as desktop's `DesktopForecastPersistHost`; pinned to it by `ForecastPersistHostContract`
 * (`:shared` testFixtures). [snapshotLabel] is the fetch's `HourlySnapshotRun` decision
 * ([HourlyForecastStore.snapshotLabelFor] in production).
 */
internal class RoomForecastPersistHost(
    private val forecastDao: ForecastDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val historyDao: HourlyForecastHistoryDao,
    private val appLogDao: AppLogDao,
    override val sourceId: String,
    override val latitude: Double,
    override val longitude: Double,
    private val snapshotLabel: suspend (nowMs: Long) -> Long?,
) : ForecastPersistRun.Host<ForecastEntity> {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun storedHourly(startMs: Long, endMs: Long): List<HourlyForecast> =
        hourlyForecastDao.getHourlyForecastsBySource(startMs, endMs, keyLat, keyLon, sourceId)
            .filter { it.locationLat == keyLat && it.locationLon == keyLon }
            .map { it.toHourlyForecast() }

    override suspend fun writeHourly(rows: List<HourlyForecast>) =
        hourlyForecastDao.insertAll(rows.map { it.copy(locationLat = keyLat, locationLon = keyLon, source = sourceId).toEntity(keyLat, keyLon) })

    override suspend fun snapshotLabel(nowMs: Long) = snapshotLabel.invoke(nowMs)

    override suspend fun writeSnapshot(label: Long, rows: List<HourlyForecast>) =
        historyDao.insertAll(rows.map { it.toHistoryEntity(keyLat, keyLon, sourceId, label) })

    override fun elapsedStore(label: Long, nowMs: Long) =
        RoomElapsedHistoryStore(historyDao, latitude, longitude, sourceId, label, nowMs)

    override suspend fun storedDaily(from: LocalDate, to: LocalDate) =
        storedForecastsAtSite(forecastDao, keyLat, keyLon, sourceId, from, to)

    override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<ForecastEntity>, nowMs: Long) =
        writeForecastPlan(forecastDao, keyLat, keyLon, sourceId, write, restamp, lastConfirmedAt = nowMs, nowMs = nowMs)

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}

/** [sourceId]'s `forecasts` rows dated [from]..[to] on the exact key [keyLat], [keyLon] — [DailyForecastWrite]'s prior rows. */
internal suspend fun storedForecastsAtSite(
    forecastDao: ForecastDao,
    keyLat: Double,
    keyLon: Double,
    sourceId: String,
    from: LocalDate,
    to: LocalDate,
): List<DailyForecastWrite.Stored<ForecastEntity>> =
    forecastDao.getForecastsInRangeBySource(
        startDate = from.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        endDate = to.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        lat = keyLat,
        lon = keyLon,
        source = sourceId,
    ).filter { it.locationLat == keyLat && it.locationLon == keyLon }.map { it.toStored() }

/** A [DailyForecastWrite.Plan]: [restamp] rows take [lastConfirmedAt]; [write] rows are new, dated today. */
internal suspend fun writeForecastPlan(
    forecastDao: ForecastDao,
    keyLat: Double,
    keyLon: Double,
    sourceId: String,
    write: List<DailyForecastWrite.Row>,
    restamp: List<ForecastEntity>,
    lastConfirmedAt: Long,
    nowMs: Long,
) {
    val todayEpoch = PredictionDate.epochMs(nowMs)
    val rows = restamp.map { it.copy(lastConfirmedAt = lastConfirmedAt) } +
        write.map { row ->
            val v = row.values
            ForecastEntity(
                targetDate = row.date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                dateOfPrediction = todayEpoch,
                locationLat = keyLat,
                locationLon = keyLon,
                highTemp = v.highTemp,
                lowTemp = v.lowTemp,
                condition = v.condition,
                nativeDailyIconToken = v.iconToken,
                source = sourceId,
                precipProbability = v.precipProbability,
                daytimePrecipProbability = v.daytimePrecipProbability,
                nighttimePrecipProbability = v.nighttimePrecipProbability,
                precipAmountMm = v.precipAmountMm,
                lastConfirmedAt = lastConfirmedAt,
                fetchedAt = nowMs,
                hindcastHighTemp = v.hindcastHighTemp,
                hindcastLowTemp = v.hindcastLowTemp,
            ).withHourlySummary(v.summary)
        }
    if (rows.isNotEmpty()) forecastDao.insertAll(rows)
}

internal fun ForecastEntity.toStored() = DailyForecastWrite.Stored(
    date = LocalDate.ofEpochDay(targetDate / WidgetConstants.MS_IN_A_DAY),
    values = DailyForecastWrite.Values(
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition,
        iconToken = nativeDailyIconToken,
        precipProbability = precipProbability,
        precipAmountMm = precipAmountMm,
        daytimePrecipProbability = daytimePrecipProbability,
        nighttimePrecipProbability = nighttimePrecipProbability,
        hindcastHighTemp = hindcastHighTemp,
        hindcastLowTemp = hindcastLowTemp,
        summary = hourlySummary,
    ),
    dateOfPrediction = dateOfPrediction,
    lastConfirmedAt = lastConfirmedAt,
    fetchedAt = fetchedAt,
    ref = this,
)

internal fun HourlyForecast.toHistoryEntity(keyLat: Double, keyLon: Double, sourceId: String, label: Long) =
    HourlyForecastHistoryEntity(
        dateTime = dateTime,
        locationLat = keyLat,
        locationLon = keyLon,
        temperature = temperature,
        condition = condition,
        source = sourceId,
        timestampToGroupPredictions = label,
        precipProbability = precipProbability,
        cloudCover = cloudCover,
        cloudCoverLow = cloudCoverLow,
        cloudCoverMid = cloudCoverMid,
        cloudCoverHigh = cloudCoverHigh,
        precipAmountMm = precipAmountMm,
        fetchedAt = fetchedAt,
    )

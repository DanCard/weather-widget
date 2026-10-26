package com.weatherwidget.data.repository

import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.actuals.ReducedSignatureStore
import com.weatherwidget.widget.handlers.GraphDataLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "RoomDailyBlendRecomputeHost"

/** Log tag AND the `setprop log.tag.<...>` switch that turns the extrema window diagnostic on. */
private const val EXTREMA_WINDOW_DIAG_TAG = "EXTREMA_WINDOW_DIAG"

/** Android's [DailyBlendRecomputeRun.Host] over Room; also run by `RoomDailyBlendRecomputeHostContractTest`. */
class RoomDailyBlendRecomputeHost(
    override val lat: Double,
    override val lon: Double,
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val appLogDao: AppLogDao,
    private val personalStationWeightProvider: PersonalStationWeightProvider,
    override val signatures: ReducedSignatureStore,
) : DailyBlendRecomputeRun.Host {
    override fun personalStationWeight() = personalStationWeightProvider.currentWeight()

    // Unscoped: the daily recompute builds history for EVERY source in one pass.
    override suspend fun observations(startMs: Long, endMs: Long) =
        observationDao.getObservationsInRange(startMs, endMs, lat, lon, apis = null).map { it.toReading() }

    override suspend fun hourlyAllSources(startMs: Long, endMs: Long): List<HourlyForecast> =
        GraphDataLoader.unifyToNearestSite(hourlyForecastDao.getHourlyForecasts(startMs, endMs, lat, lon), lat, lon)
            .map { it.toHourlyForecast() }

    override suspend fun observationSignature(startMs: Long, endMs: Long) =
        observationDao.observationSignatureInRange(startMs, endMs, lat, lon)

    override suspend fun dailyHistoryAt(dateKey: Long) =
        dailyHistoryDao.getExtremesInRange(dateKey, dateKey, lat, lon).map { it.toDailyHistory() }

    override suspend fun insert(rows: List<DailyHistory>) = dailyHistoryDao.insertAll(rows.map { it.toEntity() })

    override suspend fun updateBlendIfUnchanged(merged: DailyHistory, newUpdatedAt: Long, expectedUpdatedAt: Long) =
        dailyHistoryDao.updateBlendIfUnchanged(
            date = merged.date,
            source = merged.source,
            locationLat = merged.locationLat,
            locationLon = merged.locationLon,
            computedHighTemp = merged.computedHighTemp,
            computedLowTemp = merged.computedLowTemp,
            computedHighAt = merged.computedHighAt,
            computedLowAt = merged.computedLowAt,
            condition = merged.condition,
            precipAmountMm = merged.precipAmountMm,
            precipDayMm = merged.precipDayMm,
            precipNightMm = merged.precipNightMm,
            lastWriter = merged.lastWriter,
            updatedAt = newUpdatedAt,
            expectedUpdatedAt = expectedUpdatedAt,
        )

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)

    override fun elapsedMs() = SystemClock.elapsedRealtime()

    override suspend fun afterReduce(date: LocalDate, dayObservations: List<ObservationReading>, hourly: List<HourlyForecast>) {
        // OFF BY DEFAULT — enable with:
        //   adb shell setprop log.tag.EXTREMA_WINDOW_DIAG VERBOSE
        // This is a pure diagnostic, but not a cheap one: it issues its OWN +/-24h observation read
        // and runs blendObservationSeries TWICE (isolated window and wide window). Measured
        // 2026-09-06 on the Samsung it cost logDiag=4977ms for a single day and ~7.9s per sync,
        // about a third of the whole actuals recompute, on a path the user is waiting behind.
        // Guarding the WORK (not just the write) is the point — the AppLogDao VERBOSE gate only
        // skips the insert, which is the cheapest part. Same idiom as
        // TemperatureGraphAnnotationRenderer. The diagnostic itself is deliberately kept.
        if (!Log.isLoggable(EXTREMA_WINDOW_DIAG_TAG, Log.VERBOSE)) return
        try {
            val zone = ZoneId.systemDefault()
            val startTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            fun probe(observations: List<ObservationReading>, start: Long, end: Long): String {
                val series = ActualTemperatureSeriesBuilder
                    .blendObservationSeries(
                        observations = observations,
                        hourlyForecasts = hourly,
                        displaySourceId = WeatherSource.NWS.id,
                        userLat = lat,
                        userLon = lon,
                        startMs = start,
                        endMs = end,
                        personalStationWeight = personalStationWeight(),
                    )
                    .observations
                    .filter { it.timestamp in startTs until endTs }
                if (series.isEmpty()) return "empty"
                val high = series.maxBy { it.temperature }
                val low = series.minBy { it.temperature }
                return "hi=${"%.2f".format(high.temperature)}@" +
                    "${Instant.ofEpochMilli(high.timestamp).atZone(zone).format(formatter)} " +
                    "lo=${"%.2f".format(low.temperature)}@" +
                    "${Instant.ofEpochMilli(low.timestamp).atZone(zone).format(formatter)} pts=${series.size}"
            }

            val nwsDayObs = dayObservations.filter { it.api == WeatherSource.NWS.id }
            if (nwsDayObs.isNotEmpty()) {
                val dayMs = 24 * 3_600_000L
                val wideObs = observationDao
                    .getObservationsInRange(
                        startTs - dayMs,
                        endTs + dayMs,
                        lat,
                        lon,
                        // Scoped to exactly what the filter below keeps; see above.
                        setOf(WeatherSource.NWS.id),
                    )
                    .filter { it.api == WeatherSource.NWS.id }
                    .map { it.toReading() }
                appLogDao.log(
                    EXTREMA_WINDOW_DIAG_TAG,
                    "date=$date isolated=[${probe(nwsDayObs, startTs, endTs)}] " +
                        "wide=[${probe(wideObs, startTs - dayMs, endTs + dayMs)}]",
                    "DEBUG",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.v(TAG, "Unable to write extrema window diagnostic", e)
        }
    }
}

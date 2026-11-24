package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.PartialHighDayContract
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.DailyViewLogic
import com.weatherwidget.widget.handlers.GraphDayRequest
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Room half of [PartialHighDayContract]: in-memory Room ([TestDatabase]), the real
 * [RoomForecastPersistHost] and [RoomClimateNormalsHost], and the widget's daily read —
 * [ForecastSnapshotStore.getCachedDataBySource] → [DailyViewHandler.buildWeatherByDate] →
 * [DailyViewLogic] graph and text columns. The desktop half is `DesktopPartialHighDayContractTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomPartialHighDayContractTest : PartialHighDayContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Platform {
        val db = TestDatabase.create().also { databases.add(it) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stateManager = WidgetStateManager(context)
        return object : Platform {
            override fun persistHost(sourceId: String, lat: Double, lon: Double): ForecastPersistRun.Host<*> =
                RoomForecastPersistHost(
                    db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.appLogDao(), sourceId, lat, lon,
                )

            override suspend fun storeNormals(lat: Double, lon: Double, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) =
                RoomClimateNormalsHost(db.climateNormalDao(), db.appLogDao(), fetch = { _, _ -> emptyList() }, onCached = {})
                    .store(ClimateNormals.locationKey(lat, lon), monthlyHigh, monthlyLow)

            override suspend fun rawDaily(lat: Double, lon: Double, sourceId: String, date: LocalDate): List<Raw> {
                val dayMs = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
                return db.forecastDao().getForecastsInRangeBySource(dayMs, dayMs, lat, lon, sourceId)
                    .filter { it.targetDate == dayMs }
                    .sortedByDescending { it.fetchedAt }
                    .map { Raw(it.highTemp, it.lowTemp, it.precipProbability) }
            }

            override suspend fun shownDay(lat: Double, lon: Double, sourceId: String, date: LocalDate, now: LocalDateTime): Shown? {
                val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val source = WeatherSource.fromId(sourceId)
                val gapFiller = ClimateGapFiller(db.climateNormalDao())
                val store = ForecastSnapshotStore(db.forecastDao(), db.appLogDao(), stateManager, gapFiller, clock = { nowMs })
                val today = now.toLocalDate()
                val weatherList = store.getCachedDataBySource(lat, lon, source)
                val weatherByDate = DailyViewHandler.buildWeatherByDate(weatherList, source, today)
                val normals = gapFiller.cachedNormalsByMonthDay(lat, lon)
                val hourly = db.hourlyForecastDao().getHourlyForecastsBySource(
                    nowMs - 72L * 3_600_000L,
                    nowMs + 168L * 3_600_000L,
                    lat,
                    lon,
                    source.id,
                )
                println("android weatherByDate: ${weatherByDate.toSortedMap().map { (d, e) -> "$d ${e.source} ${e.highTemp}/${e.lowTemp}" }}")

                val graph = DailyViewLogic.prepareGraphDayInputs(
                    GraphDayRequest(
                        now = now, centerDate = today, today = today, weatherByDate = weatherByDate,
                        forecastSnapshots = emptyMap(), numColumns = 10, displaySource = source,
                        skipYesterday = false, skipHistory = false, hourlyForecasts = hourly,
                        climateNormals = normals, todayLabel = "Today", centerLat = lat, centerLon = lon,
                        showFuturePriorBar = false, rainLookaheadHours = 3,
                    ),
                ).map { it.renderDay }
                println("android columns: ${graph.map { "${it.date} ${it.solidLineHigh}/${it.solidLineLow} highNormal=${it.highIsClimateNormal} climate=${it.isClimateNormal} gap=${it.isSourceGapFallback}" }}")
                val day = graph.firstOrNull { it.date == date } ?: return null
                if (day.solidLineHigh == null && day.solidLineLow == null) return null
                val text = DailyViewLogic.prepareTextDays(
                    now = now, centerDate = today, today = today, weatherByDate = weatherByDate,
                    hourlyForecasts = hourly, numColumns = 8, displaySource = source,
                    climateNormals = normals, todayLabel = "Today", centerLat = lat, centerLon = lon,
                ).first { it.date == date }
                println("android text $date: hasData=${text.hasData} ${text.highLabel}/${text.lowLabel}")
                return Shown(
                    high = day.solidLineHigh,
                    low = day.solidLineLow,
                    highLabel = text.highLabel,
                    lowLabel = text.lowLabel,
                    highIsClimateNormal = day.highIsClimateNormal,
                    climateDay = day.isClimateNormal || day.isSourceGapFallback,
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

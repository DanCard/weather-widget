package com.weatherwidget.ui

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.DaySourceComparisonHostContract
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room half of [DaySourceComparisonHostContract], through [RoomDaySourceComparisonHost] (the Forecast
 * History screen's reads). The desktop half is `DesktopDaySourceComparisonHostContractTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MediumDuration::class)
class RoomDaySourceComparisonHostContractTest : DaySourceComparisonHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(sources: List<WeatherSource>): Pair<DaySourceComparison.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomDaySourceComparisonHost(
            db.forecastDao(), db.dailyHistoryDao(), db.hourlyForecastDao(), sources,
        ) { _, _, _ -> emptyMap() }
        return host to RoomSeed(db)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }

    private class RoomSeed(private val db: WeatherDatabase) : Seed {
        override fun forecast(row: ForecastSeed, targetDate: Long, dateOfPrediction: Long) = runBlocking {
            db.forecastDao().insertForecast(
                ForecastEntity(
                    targetDate = targetDate, dateOfPrediction = dateOfPrediction,
                    locationLat = row.lat, locationLon = row.lon, highTemp = row.high, lowTemp = row.low,
                    condition = "Cloudy", isClimateNormal = row.isClimateNormal, source = row.source,
                    precipProbability = row.precipProbability, precipAmountMm = row.amountMm,
                    lastConfirmedAt = row.lastConfirmedAt, fetchedAt = row.fetchedAt,
                    noonCloudPercent = row.noonCloud, hourlyDayPrecipMax = row.dayMax,
                    hourlyNightPrecipMax = row.nightMax, hourlyDayPrecipPeakAt = row.peakAt,
                ),
            )
        }

        override fun hourly(row: HourlyForecast) = runBlocking {
            db.hourlyForecastDao().insertAll(
                listOf(
                    HourlyForecastEntity(
                        dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                        temperature = row.temperature, condition = row.condition, source = row.source!!,
                        precipProbability = row.precipProbability, cloudCover = row.cloudCover, fetchedAt = row.fetchedAt,
                    ),
                ),
            )
        }
    }
}

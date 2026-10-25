package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.shared.actuals.DailyHistoryRun
import com.weatherwidget.shared.contract.DailyHistoryHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [DailyHistoryHostContract]; the desktop half is `DesktopDailyHistoryHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomDailyHistoryHostContractTest : DailyHistoryHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<DailyHistoryRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomDailyHistoryHost(
            ApplicationProvider.getApplicationContext<Context>(),
            db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.dailyHistoryDao(), db.appLogDao(),
        )
        return host to RoomSeed(db)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }

    private class RoomSeed(private val db: WeatherDatabase) : Seed {
        override fun forecast(row: DailyHistoryMaintenance.ForecastHistoryRow, dateOfPrediction: Long) = runBlocking {
            db.forecastDao().insertForecast(
                ForecastEntity(
                    targetDate = row.dateMs, dateOfPrediction = dateOfPrediction,
                    locationLat = row.locationLat, locationLon = row.locationLon,
                    highTemp = row.highTemp, lowTemp = row.lowTemp, condition = row.condition,
                    isClimateNormal = row.isClimateNormal, source = row.source, precipAmountMm = row.precipAmountMm,
                    lastConfirmedAt = row.lastConfirmedAt, fetchedAt = row.fetchedAt,
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

        override fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long) = runBlocking {
            db.hourlyForecastHistoryDao().insertAll(
                listOf(
                    HourlyForecastHistoryEntity(
                        dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                        temperature = row.temperature, condition = row.condition, source = row.source!!,
                        timestampToGroupPredictions = snapshotBucketMs,
                        precipProbability = row.precipProbability, cloudCover = row.cloudCover, fetchedAt = row.fetchedAt,
                    ),
                ),
            )
        }
    }
}

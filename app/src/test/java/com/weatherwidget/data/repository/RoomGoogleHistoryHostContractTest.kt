package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.GoogleHistoryRun
import com.weatherwidget.shared.contract.GoogleHistoryHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [GoogleHistoryHostContract]; the desktop half is `DesktopGoogleHistoryHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomGoogleHistoryHostContractTest : GoogleHistoryHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<GoogleHistoryRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomGoogleHistoryHost(db.hourlyForecastHistoryDao(), LAT, LON)
        return host to object : Seed {
            override fun live(row: HourlyForecast) = runBlocking {
                db.hourlyForecastDao().insertAll(
                    listOf(
                        HourlyForecastEntity(
                            dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                            temperature = row.temperature, condition = row.condition, source = row.source!!,
                            precipProbability = row.precipProbability, fetchedAt = row.fetchedAt,
                        ),
                    ),
                )
            }

            override fun history(row: HourlyForecast, bucket: Long) = runBlocking {
                db.hourlyForecastHistoryDao().insertAll(
                    listOf(
                        HourlyForecastHistoryEntity(
                            dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                            temperature = row.temperature, condition = row.condition, source = row.source!!,
                            timestampToGroupPredictions = bucket, precipProbability = row.precipProbability,
                            fetchedAt = row.fetchedAt,
                        ),
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

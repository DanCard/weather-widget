package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.shared.contract.ElapsedForecastBackfillStoreContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [ElapsedForecastBackfillStoreContract]; the desktop half is `DesktopElapsedHistoryStoreContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomElapsedHistoryStoreContractTest : ElapsedForecastBackfillStoreContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<ElapsedForecastBackfill.Store, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val store = RoomElapsedHistoryStore(db.hourlyForecastHistoryDao(), LAT, LON, SOURCE, BUCKET, NOW)
        return store to Seed { row, bucket ->
            runBlocking {
                db.hourlyForecastHistoryDao().insertAll(
                    listOf(
                        HourlyForecastHistoryEntity(
                            dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                            temperature = row.temperature, condition = row.condition, source = row.source!!,
                            timestampToGroupPredictions = bucket, fetchedAt = row.fetchedAt,
                        ),
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

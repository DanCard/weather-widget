package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.HourlySnapshotHostContract
import com.weatherwidget.shared.history.HourlySnapshotRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [HourlySnapshotHostContract]; the desktop half is `DesktopHourlySnapshotHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomHourlySnapshotHostContractTest : HourlySnapshotHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<HourlySnapshotRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomHourlySnapshotHost(
            SOURCE, LAT, LON, db.hourlyForecastHistoryDao(), db.appLogDao(),
            isDisplayed = { true }, viewedRecently = { true }, batteryHigh = { true },
        )
        return host to Seed { row, label ->
            runBlocking {
                db.hourlyForecastHistoryDao().insertAll(
                    listOf(
                        HourlyForecastHistoryEntity(
                            dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                            temperature = row.temperature, condition = row.condition, source = row.source!!,
                            timestampToGroupPredictions = label, fetchedAt = row.fetchedAt,
                        ),
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

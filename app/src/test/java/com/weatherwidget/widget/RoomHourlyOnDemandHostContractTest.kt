package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.HourlyOnDemandRun
import com.weatherwidget.shared.contract.HourlyOnDemandHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [HourlyOnDemandHostContract]; the desktop half is `DesktopHourlyOnDemandHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomHourlyOnDemandHostContractTest : HourlyOnDemandHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<HourlyOnDemandRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomHourlyOnDemandHost(db, SOURCE, LAT, LON, NOW) { error("no network") }
        return host to Seed { row ->
            runBlocking {
                db.hourlyForecastDao().insertAll(
                    listOf(
                        HourlyForecastEntity(
                            dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                            temperature = row.temperature, condition = row.condition, source = row.source!!, fetchedAt = row.fetchedAt,
                        ),
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

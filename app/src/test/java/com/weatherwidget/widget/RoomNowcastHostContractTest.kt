package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.NowcastRefreshHostContract
import com.weatherwidget.shared.nowcast.NowcastRefreshRun
import com.weatherwidget.shared.nowcast.NowcastStore
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [NowcastRefreshHostContract]; the desktop half is `DesktopNowcastHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomNowcastHostContractTest : NowcastRefreshHostContract() {
    @get:Rule val tmp = TemporaryFolder()
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<NowcastRefreshRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomNowcastHost(
            LAT, LON, openMeteoDisplayed = true, usableSourceIds = emptySet(), store = NowcastStore(tmp.newFolder()),
            hourlyForecastDao = db.hourlyForecastDao(), appLogDao = db.appLogDao(), fetch = { error("no network") },
        )
        return host to Seed { row ->
            runBlocking {
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
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

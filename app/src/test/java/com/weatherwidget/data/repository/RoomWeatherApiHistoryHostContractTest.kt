package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.WeatherApiHistoryHostContract
import com.weatherwidget.shared.history.WeatherApiHistoryRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [WeatherApiHistoryHostContract]; the desktop half is `DesktopWeatherApiHistoryHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomWeatherApiHistoryHostContractTest : WeatherApiHistoryHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<WeatherApiHistoryRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val hourlyStore = HourlyForecastStore(
            db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.observationDao(),
            WidgetStateManager(ApplicationProvider.getApplicationContext<Context>()),
            clock = { DAY_START + DAY }, appLogDao = db.appLogDao(),
        )
        val host = RoomWeatherApiHistoryHost(
            LAT, LON, db.observationDao(), db.appLogDao(), hourlyStore,
            fetch = { error("no network") }, recompute = { },
        )
        return host to Seed { row ->
            runBlocking {
                db.observationDao().insertAll(
                    listOf(
                        ObservationEntity(
                            stationId = row.stationId, stationName = row.stationName, timestamp = row.timestamp,
                            temperature = row.temperature, condition = row.condition,
                            locationLat = row.locationLat, locationLon = row.locationLon, fetchedAt = row.fetchedAt, api = row.api,
                        ),
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

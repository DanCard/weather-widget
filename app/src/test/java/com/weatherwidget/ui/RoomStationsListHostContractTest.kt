package com.weatherwidget.ui

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.StationsListHostContract
import com.weatherwidget.shared.observations.StationsList
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [StationsListHostContract]; the desktop half is `DesktopStationsListHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomStationsListHostContractTest : StationsListHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<StationsList.Host<*>, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val host = RoomStationsListHost(db.observationDao(), LAT to LON)
        return host to Seed { stationId, api, timestamp, lat ->
            runBlocking {
                db.observationDao().insertAll(
                    listOf(
                        ObservationEntity(
                            stationId = stationId, stationName = stationId, timestamp = timestamp, temperature = 60f,
                            condition = "Clear", locationLat = lat, locationLon = -122.089, api = api, fetchedAt = timestamp,
                        ),
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

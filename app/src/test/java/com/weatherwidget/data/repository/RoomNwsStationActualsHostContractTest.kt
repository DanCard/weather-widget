package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.NwsStationActualsRun
import com.weatherwidget.shared.contract.NwsStationActualsHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [NwsStationActualsHostContract]; the desktop half is `DesktopNwsStationActualsHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomNwsStationActualsHostContractTest : NwsStationActualsHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<NwsStationActualsRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val store = NwsStationActualsStore(
            db.dailyHistoryDao(), db.observationDao(), db.hourlyForecastDao(), db.appLogDao(), db.hourlyForecastHistoryDao(),
        )
        val host = RoomNwsStationActualsHost(
            LAT, LON, personalStationWeight = 1.0, store = store, appLogDao = db.appLogDao(),
            stations = { error("no network") }, stationDay = { _, _, _ -> error("no network") },
        )
        return host to RoomSeed(db)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }

    private class RoomSeed(private val db: WeatherDatabase) : Seed {
        override fun hourly(row: HourlyForecast) = runBlocking {
            db.hourlyForecastDao().insertAll(
                listOf(
                    HourlyForecastEntity(
                        dateTime = row.dateTime, locationLat = row.locationLat!!, locationLon = row.locationLon!!,
                        temperature = row.temperature, condition = row.condition, source = row.source!!, fetchedAt = row.fetchedAt,
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
                        timestampToGroupPredictions = snapshotBucketMs, fetchedAt = row.fetchedAt,
                    ),
                ),
            )
        }

        override fun observation(row: ObservationReading) = runBlocking {
            db.observationDao().insertAll(
                listOf(
                    ObservationEntity(
                        stationId = row.stationId, stationName = row.stationName, timestamp = row.timestamp,
                        temperature = row.temperature, condition = row.condition,
                        locationLat = row.locationLat, locationLon = row.locationLon, distanceKm = row.distanceKm,
                        stationType = row.stationType, fetchedAt = row.fetchedAt, api = row.api,
                    ),
                ),
            )
        }
    }
}

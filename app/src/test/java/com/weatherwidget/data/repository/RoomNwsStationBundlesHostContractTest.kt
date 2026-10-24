package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.contract.NwsStationBundlesHostContract
import com.weatherwidget.shared.observations.NwsStationBundles
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [NwsStationBundlesHostContract]; the desktop half is `DesktopNwsStationBundlesHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomNwsStationBundlesHostContractTest : NwsStationBundlesHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<NwsStationBundles.Host, Db> {
        val database = TestDatabase.create().also { databases.add(it) }
        val db = object : Db {
            override fun observation(row: ObservationReading) = runBlocking {
                database.observationDao().insertAll(
                    listOf(
                        ObservationEntity(
                            stationId = row.stationId, stationName = row.stationName, timestamp = row.timestamp,
                            temperature = row.temperature, condition = row.condition,
                            locationLat = row.locationLat, locationLon = row.locationLon, fetchedAt = row.fetchedAt, api = row.api,
                        ),
                    ),
                )
            }

            override fun fetchedAts(): Map<Triple<String, Long, Double>, Long> =
                database.openHelper.readableDatabase.query("SELECT stationId, timestamp, locationLat, fetchedAt FROM observations").use { c ->
                    buildMap { while (c.moveToNext()) put(Triple(c.getString(0), c.getLong(1), c.getDouble(2)), c.getLong(3)) }
                }
        }
        return RoomNwsStationBundlesHost(database.observationDao(), database.appLogDao(), LAT, LON) { NOW } to db
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

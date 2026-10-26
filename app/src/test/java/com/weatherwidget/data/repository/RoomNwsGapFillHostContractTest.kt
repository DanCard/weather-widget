package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.contract.NwsGapFillHostContract
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [NwsGapFillHostContract]; the desktop half is `DesktopNwsGapFillHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomNwsGapFillHostContractTest : NwsGapFillHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Pair<NwsGapFillRun.Host, Seed> {
        val db = TestDatabase.create().also { databases.add(it) }
        val backfiller = NwsObservationBackfiller(
            // Network only: the contract never asks for stations or a station window.
            observationSource = mockk(),
            observationDao = db.observationDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
            appLogDao = db.appLogDao(),
            dailyActualsStore = DailyActualsStore(
                db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(),
                PersonalStationWeightProvider { 1.0 },
            ),
        )
        return backfiller.gapFillHost to RoomSeed(db)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }

    private class RoomSeed(private val db: WeatherDatabase) : Seed {
        override fun dailyHistory(row: DailyHistory) = runBlocking { db.dailyHistoryDao().insertAll(listOf(row.toEntity())) }

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

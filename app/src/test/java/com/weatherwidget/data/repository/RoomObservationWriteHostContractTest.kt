package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.contract.ObservationWriteHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [ObservationWriteHostContract]; the desktop half is `DesktopObservationWriteHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomObservationWriteHostContractTest : ObservationWriteHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Db {
        val db = TestDatabase.create().also { databases.add(it) }
        return object : Db {
            override fun host(lat: Double, lon: Double) =
                RoomObservationWriteHost(db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), lat, lon)

            override suspend fun rows() = db.observationDao().getRecentObservations(0L).map { it.toReading() }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

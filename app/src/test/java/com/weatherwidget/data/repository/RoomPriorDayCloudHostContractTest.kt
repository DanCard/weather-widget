package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.PriorDayCloudHostContract
import com.weatherwidget.shared.graph.PriorDayCloudRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [PriorDayCloudHostContract]; the desktop half is `DesktopPriorDayCloudHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomPriorDayCloudHostContractTest : PriorDayCloudHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Db {
        val db = TestDatabase.create().also { databases.add(it) }
        return object : Db {
            override fun host(latitude: Double, longitude: Double, lastAttempt: PriorDayCloudRun.LastAttempt) =
                RoomPriorDayCloudHost(db.hourlyForecastHistoryDao(), db.appLogDao(), latitude, longitude, lastAttempt) { _, _ -> error("no fetch") }

            override suspend fun read(latitude: Double, longitude: Double, startMs: Long, endMs: Long) =
                db.hourlyForecastHistoryDao().getPriorDayCloudForecast(startMs, endMs, latitude, longitude)
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

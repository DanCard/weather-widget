package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.ForecastPersistHostContract
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [ForecastPersistHostContract]; the desktop half is `DesktopForecastPersistHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomForecastPersistHostContractTest : ForecastPersistHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): (String, Double, Double) -> ForecastPersistRun.Host<*> {
        val db = TestDatabase.create().also { databases.add(it) }
        return { source, lat, lon ->
            RoomForecastPersistHost(
                db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.appLogDao(), source, lat, lon,
            ) { LABEL }
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

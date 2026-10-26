package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.remote.SourceFetchTable
import com.weatherwidget.shared.contract.SourceFetchTableHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [SourceFetchTableHostContract]; the desktop half is `DesktopSourceFetchTableHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomSourceFetchTableHostContractTest : SourceFetchTableHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): Db {
        val db = TestDatabase.create().also { databases.add(it) }
        val store = HourlyForecastStore(
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            observationDao = db.observationDao(),
            widgetStateManager = WidgetStateManager(ApplicationProvider.getApplicationContext<Context>()),
            appLogDao = db.appLogDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
        )
        return object : Db {
            override fun host() = RoomSourceFetchHost(
                calls = SourceFetchTable.Calls(nws = { _, _, _ -> RawFetch() }, openMeteo = { _, _, _, _ -> RawFetch() }),
                hourlyStore = store,
                appLogDao = db.appLogDao(),
                needsHistory = { _, _, _ -> false },
                nowcast = { _, _, _ -> },
            )

            override suspend fun writeHourly(sourceId: String, lat: Double, lon: Double, rows: List<HourlyForecast>) =
                RoomForecastPersistHost(
                    db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.appLogDao(), sourceId, lat, lon,
                ) { null }.writeHourly(rows)
        }
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

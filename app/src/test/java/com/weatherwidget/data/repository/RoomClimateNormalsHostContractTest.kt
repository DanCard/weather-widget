package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.ClimateNormalsHostContract
import com.weatherwidget.shared.util.ClimateNormalsRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [ClimateNormalsHostContract]; the desktop half is `DesktopClimateNormalsHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomClimateNormalsHostContractTest : ClimateNormalsHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): ClimateNormalsRun.Host {
        val db = TestDatabase.create().also { databases.add(it) }
        return RoomClimateNormalsHost(db.climateNormalDao(), db.appLogDao(), NO_FETCH)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}

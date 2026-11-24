package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.MetarObservationSource
import com.weatherwidget.shared.contract.StationNetworkHostContract
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.mockk
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room half of [StationNetworkHostContract]; the desktop half is `DesktopStationNetworkHostContractTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class AndroidStationNetworkHostContractTest : StationNetworkHostContract() {
    private lateinit var db: WeatherDatabase

    override fun open(lat: Double, lon: Double): StationNetworkRefreshRun.Host {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        return AndroidStationNetworkHost(
            context = context,
            db = db,
            source = mockk<MetarObservationSource>(relaxed = true),
            widgetStateManager = mockk<WidgetStateManager>(relaxed = true),
            appLogDao = db.appLogDao(),
            lat = lat,
            lon = lon,
        )
    }

    override fun close() = db.close()
}

package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.shared.contract.ViewingForecastHostContract
import com.weatherwidget.test.category.LongDuration
import io.mockk.mockk
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prefs half of [ViewingForecastHostContract]; the desktop half is `DesktopViewingForecastHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class PrefsViewingForecastHostContractTest : ViewingForecastHostContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    override fun open(): Fixture {
        val manager = WidgetStateManager(context)
        manager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        lateinit var fixture: Fixture
        val host = ViewingForecastRefresher.AndroidHost(
            manager, WeatherSource.NWS, isCharging = false, batteryLevel = 85, lat = LAT, lon = LON, nowMs = System.currentTimeMillis(),
            lastSuccessMs = { FetchMetadata.getLastForecastSourceSuccessTime(context, it, LAT, LON) },
            appLogDao = mockk<AppLogDao>(relaxed = true),
            fetchSource = { fixture.fetches += it },
        )
        fixture = object : Fixture(host) {
            override fun recordFetch(sourceId: String, atMs: Long) = manager.markLongFetch(sourceId, LAT, LON, atMs)

            override fun recordCheck(sourceId: String, atMs: Long) =
                FetchMetadata.setLastForecastSourceSuccessTime(context, sourceId, LAT, LON, atMs)
        }
        return fixture
    }
}

package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The forced sync a setup-screen location change enqueues decides, at its start, whether the
 * widgets get "Getting weather for {place}…" or the ordinary cache repaint. The decision used to
 * run on a fire-and-forget IO thread launched from the activity; in Robolectric that thread outlived
 * the test and raced the next one's environment (`lightZ must be a finite positive`), which is why
 * it lives in the worker now.
 */
@Category(LongDuration::class)
class WidgetPaintCoordinatorLocationChangeTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val appLogDao: AppLogDao = mockk(relaxed = true)
    private lateinit var coordinator: WidgetPaintCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearPendingLocationFetch()
        coordinator = WidgetPaintCoordinator(
            context = context,
            weatherRepository = mockk<WeatherRepository>(relaxed = true),
            widgetStateManager = stateManager,
            appLogDao = appLogDao,
            hourlyForecastLoader = mockk(relaxed = true),
            dataBundleLoader = mockk(relaxed = true),
            gpsResampler = mockk(relaxed = true),
        )
    }

    private fun logged(action: String) = coVerify(exactly = 1) {
        appLogDao.insert(match<AppLogEntity> { it.tag == "LOCATION_FETCH_PENDING" && it.message.contains("action=$action") })
    }

    @Test
    fun `no cached row keeps the wait and paints the interstitial`() = runBlocking {
        stateManager.setPendingLocationFetch("Denver")

        coordinator.paintLocationChangeInterstitial("Denver", 39.74, -104.98, hasTodayRowAt = { _, _ -> false })

        assertEquals("Denver", stateManager.getPendingLocationFetch())
        logged("render_interstitial")
    }

    @Test
    fun `a site with today's row clears the wait and paints nothing`() = runBlocking {
        stateManager.setPendingLocationFetch("Home")

        coordinator.paintLocationChangeInterstitial("Home", 37.42, -122.09, hasTodayRowAt = { _, _ -> true })

        assertNull(stateManager.getPendingLocationFetch())
        logged("cached_rows_adopted")
    }

    @Test
    fun `a superseded change paints nothing`() = runBlocking {
        // The user saved Denver, then Chicago before Denver's sync ran: Denver's run must not paint.
        stateManager.setPendingLocationFetch("Chicago")

        coordinator.paintLocationChangeInterstitial("Denver", 39.74, -104.98, hasTodayRowAt = { _, _ -> false })

        assertEquals("Chicago", stateManager.getPendingLocationFetch())
        coVerify(exactly = 0) { appLogDao.insert(match<AppLogEntity> { it.tag == "LOCATION_FETCH_PENDING" }) }
    }
}

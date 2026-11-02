package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.shared.util.ViewingForecastRefresh
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opening Forecast History or Observations holds that one source to the 2-hour limit (user, 2026-11-01). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ViewingForecastScreenOpenRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lat = 37.42
    private val lon = -122.08
    private val hour = 60 * 60_000L
    private var fetches = 0

    @Before
    fun setUp() {
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        mockkObject(BatterySnapshotProvider)
    }

    @After
    fun tearDown() {
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    private fun open(level: Int, charging: Boolean = false) = runBlocking {
        every { BatterySnapshotProvider.snapshot(any()) } returns BatterySnapshot(isCharging = charging, batteryLevel = level)
        ViewingForecastRefresher.onScreenOpen(context, WeatherSource.NWS, lat, lon, "history_open") { fetches++ }
    }

    private fun rows() = runBlocking {
        WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag(ViewingForecastRefresh.LOG_TAG, 5).map { it.message }
    }

    @Test
    fun `a source past 2 hours fetches once, and the cooldown stops a reopen asking again`() {
        WidgetStateManager(context).markLongFetch(WeatherSource.NWS.id, lat, lon, System.currentTimeMillis() - 3 * hour)

        open(level = 85)
        open(level = 85)

        assertEquals(1, fetches)
        assertEquals(1, rows().size)
        assertEquals(true, rows().single().startsWith("source=NWS trigger=history_open ageMin=180"))
    }

    @Test
    fun `a successful check inside 2 hours holds it fresh despite an older marker`() {
        val now = System.currentTimeMillis()
        WidgetStateManager(context).markLongFetch(WeatherSource.NWS.id, lat, lon, now - 3 * hour)
        FetchMetadata.setLastForecastSourceSuccessTime(context, WeatherSource.NWS.id, lat, lon, now - hour)

        open(level = 85)

        assertEquals(0, fetches)
    }

    @Test
    fun `below 70 percent off the charger opening fetches nothing`() {
        WidgetStateManager(context).markLongFetch(WeatherSource.NWS.id, lat, lon, System.currentTimeMillis() - 3 * hour)

        open(level = 60)

        assertEquals(0, fetches)
    }
}

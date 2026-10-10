package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.remote.HourlyWindowPolicy.Window
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/** The desktop's FULL marker is an `app_logs` row, read back per source and site. */
@Category(ShortDuration::class)
class DesktopHourlyWindowTest {
    private val database = DesktopWeatherDatabase(Files.createTempFile("hourly-window-test", ".db")).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val now = System.currentTimeMillis()
    private var charging = true

    private fun window(source: String = "OPEN_METEO", lat: Double = 37.422, lon: Double = -122.084) =
        DesktopHourlyWindow(dao, source, lat, lon) { PowerDetector.PowerState(charging, 50) }

    @Test
    fun `no DB means no window, the unchanged routine fetch`() {
        assertNull(DesktopHourlyWindow(null, "OPEN_METEO", 37.0, -122.0).current(now))
    }

    @Test
    fun `first charging fetch is FULL, then NEAR until a day has passed`() {
        assertEquals(Window.FULL, window().current(now))
        window().markFull()
        assertEquals(Window.NEAR, window().current(now + 60_000L))
        assertEquals(Window.FULL, window().current(now + 25 * 3_600_000L))
    }

    @Test
    fun `on battery is NEAR even when never FULL`() {
        charging = false
        assertEquals(Window.NEAR, DesktopHourlyWindow(dao, "OPEN_METEO", 37.422, -122.084) {
            PowerDetector.PowerState(false, 40)
        }.current(now))
    }

    @Test
    fun `the marker is per source and per site`() {
        window().markFull()
        assertEquals(Window.FULL, window(source = "NWS").current(now))
        assertEquals(Window.FULL, window(lat = 50.45, lon = 30.52).current(now))
    }
}

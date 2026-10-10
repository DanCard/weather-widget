package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.HourlyWindowPolicy.Window
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyWindowPolicyTest {
    private val hour = 3_600_000L
    private val now = 1_000 * hour
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val openMeteo = WeatherSource.OPEN_METEO.id
    private val weatherApi = WeatherSource.WEATHER_API.id

    @Test
    fun `charging with no FULL fetch yet is FULL`() {
        assertEquals(Window.FULL, HourlyWindowPolicy.choose(true, 50, null, now))
    }

    @Test
    fun `charging a day after the last FULL is FULL, just under a day is NEAR`() {
        assertEquals(Window.FULL, HourlyWindowPolicy.choose(true, 50, now - 24 * hour, now))
        assertEquals(Window.NEAR, HourlyWindowPolicy.choose(true, 50, now - 24 * hour + 1, now))
    }

    @Test
    fun `off the charger is NEAR however old the last FULL is`() {
        assertEquals(Window.NEAR, HourlyWindowPolicy.choose(false, 40, null, now))
        assertEquals(Window.NEAR, HourlyWindowPolicy.choose(false, 40, now - 100 * hour, now))
    }

    @Test
    fun `a nearly full battery counts as charging like the forecast cadence`() {
        assertEquals(Window.FULL, HourlyWindowPolicy.choose(false, 95, null, now))
    }

    @Test
    fun `NEAR stores 48 h and FULL stores the routine 72 h`() {
        assertEquals(48, HourlyWindowPolicy.storeHours(openMeteo, Window.NEAR))
        assertEquals(72, HourlyWindowPolicy.storeHours(openMeteo, Window.FULL))
        assertEquals(48, HourlyWindowPolicy.storeHours(google, Window.NEAR))
        assertEquals(72, HourlyWindowPolicy.storeHours(google, Window.FULL))
    }

    @Test
    fun `a source with less than 48 h routine is not stretched`() {
        // WeatherAPI's free plan is 3 days = 72 h, so it is not the case; the cap is minOf(48, routine).
        assertEquals(minOf(48, HourlyHorizons.of(weatherApi).routineHours), HourlyWindowPolicy.storeHours(weatherApi, Window.NEAR))
    }

    @Test
    fun `Google asks 48 h for NEAR and 8 days for FULL, capped by reach`() {
        assertEquals(48, HourlyWindowPolicy.askHours(google, Window.NEAR))
        assertEquals(192, HourlyWindowPolicy.askHours(google, Window.FULL))
    }

    @Test
    fun `free sources ask the routine horizon in FULL, which their API ignores`() {
        assertEquals(72, HourlyWindowPolicy.askHours(openMeteo, Window.FULL))
    }

    @Test
    fun `hoursAhead and askHours without a window are the routine 72 h`() {
        assertEquals(72, HourlyOnDemand.hoursAhead(google, null))
        assertEquals(72, HourlyOnDemand.askHours(google, null, null))
    }

    @Test
    fun `an on-demand request wins over the window`() {
        val request = HourlyOnDemand.Request(google, 120)
        assertEquals(120, HourlyOnDemand.hoursAhead(google, request, Window.NEAR))
        assertEquals(120, HourlyOnDemand.askHours(google, request, Window.FULL))
    }

    @Test
    fun `a FULL Google fetch asks 192 h but stores 72 h`() {
        assertEquals(192, HourlyOnDemand.askHours(google, null, Window.FULL))
        assertEquals(72, HourlyOnDemand.hoursAhead(google, null, Window.FULL))
    }

    @Test
    fun `the marker is valid only at its own site`() {
        val marker = HourlyWindowPolicy.FullMarker.at(37.4220, -122.0841, 12345L)
        assertEquals(12345L, marker.forSite(37.4220, -122.0841))
        assertNull(marker.forSite(50.45, 30.52))
    }

    @Test
    fun `the marker round trips and rejects junk`() {
        val marker = HourlyWindowPolicy.FullMarker.at(37.4220, -122.0841, 12345L)
        assertEquals(marker, HourlyWindowPolicy.FullMarker.decode(marker.encode()))
        assertNull(HourlyWindowPolicy.FullMarker.decode("nope"))
        assertNull(HourlyWindowPolicy.FullMarker.decode(null))
    }
}

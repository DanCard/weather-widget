package com.weatherwidget.data.local

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.math.cos

/** [LocationMatch.sameWeatherSite]: a great-circle radius, so it holds in km at any latitude. */
@Category(ShortDuration::class)
class LocationMatchWeatherSiteTest {
    private val kmPerDegLat = 111.19

    private fun north(lat: Double, km: Double) = lat + km / kmPerDegLat
    private fun east(lat: Double, lon: Double, km: Double) = lon + km / (kmPerDegLat * cos(Math.toRadians(lat)))

    @Test
    fun `the radius is 1 km`() {
        assertEquals(1.0, LocationMatch.WEATHER_SITE_RADIUS_KM, 0.0)
    }

    @Test
    fun `just inside and just outside, north and east, at Kyiv's latitude`() {
        val lat = 50.4495
        val lon = 30.4910
        assertTrue(LocationMatch.sameWeatherSite(lat, lon, north(lat, 0.95), lon))
        assertFalse(LocationMatch.sameWeatherSite(lat, lon, north(lat, 1.05), lon))
        // 0.01 deg of longitude is only ~0.71 km here: a degree box would get this wrong.
        assertTrue(LocationMatch.sameWeatherSite(lat, lon, lat, east(lat, lon, 0.95)))
        assertFalse(LocationMatch.sameWeatherSite(lat, lon, lat, east(lat, lon, 1.05)))
    }

    @Test
    fun `symmetric`() {
        val a = 37.4166 to -122.0889
        val b = 37.4226 to -122.0850
        assertEquals(
            LocationMatch.sameWeatherSite(a.first, a.second, b.first, b.second),
            LocationMatch.sameWeatherSite(b.first, b.second, a.first, a.second),
        )
    }

    @Test
    fun `jitter-sized fragments are also the same weather site`() {
        assertTrue(LocationMatch.sameWeatherSite(37.4168014, -122.0888977, 37.4168434, -122.0889969))
    }
}

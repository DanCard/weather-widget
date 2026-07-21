package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.withQuantizedLocation
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WeatherWidgetWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure tests for the observation-backfill location resolution (plan 260721). These lock in the two
 * fixes that stopped NWS observations being written at Googleplex: SKIP when unanchored, and fetch
 * under the quantized authoritative location otherwise.
 */
@Category(ShortDuration::class)
class HourlyObservationBackfillLocationTest {

    private val defaultLat = WeatherWidgetWorker.DEFAULT_LAT
    private val defaultLon = WeatherWidgetWorker.DEFAULT_LON

    @Test
    fun `null widget location is unanchored`() {
        val resolved = resolveBackfillLocation(null)
        assertEquals(
            BackfillLocation.Unanchored("unanchored_no_widget_location"),
            resolved,
        )
    }

    @Test
    fun `exact default location is unanchored`() {
        val resolved = resolveBackfillLocation(defaultLat to defaultLon)
        assertEquals(BackfillLocation.Unanchored("unanchored_default_location"), resolved)
    }

    @Test
    fun `quantized default is still recognized as unanchored`() {
        // The exact-equality guard's blind spot: the coordinate arrives 3-dp quantized
        // (-122.0841 -> -122.084), so `== DEFAULT_LON` missed it and the fetch ran at HQ. sameSite
        // is quantization-safe and must still classify it as the default.
        val quantizedDefault = LocationMatch.quantize(defaultLat) to LocationMatch.quantize(defaultLon)
        assertTrue(quantizedDefault.second != defaultLon) // proves the value actually changed
        assertEquals(
            BackfillLocation.Unanchored("unanchored_default_location"),
            resolveBackfillLocation(quantizedDefault),
        )
    }

    @Test
    fun `a real location resolves anchored and quantized`() {
        // The real GPS fix from the emulator repro (37.4168/-122.089), far enough from HQ to be a
        // genuinely different site. It must anchor, and its coordinate must be snapped to 3 dp so the
        // fetched rows key the same site every source writes.
        val resolved = resolveBackfillLocation(37.416797637939453 to -122.08899688720703)
        assertEquals(
            BackfillLocation.Anchored(37.417, -122.089),
            resolved,
        )
    }

    @Test
    fun `withQuantizedLocation snaps raw double writes onto the shared grid`() {
        val raw = observationAt(37.416797637939453, -122.08899688720703)
        val snapped = raw.withQuantizedLocation()
        assertEquals(37.417, snapped.locationLat, 0.0)
        assertEquals(-122.089, snapped.locationLon, 0.0)
    }

    @Test
    fun `two nearby raw writes collapse to one key`() {
        // 37.41680 and 37.41684 are the same physical spot ~tens of metres apart; without quantization
        // they are two primary keys and INSERT-REPLACE accumulates fragments instead of overwriting.
        val a = observationAt(37.41680, -122.08900).withQuantizedLocation()
        val b = observationAt(37.41684, -122.08904).withQuantizedLocation()
        assertEquals(a.locationLat, b.locationLat, 0.0)
        assertEquals(a.locationLon, b.locationLon, 0.0)
    }

    @Test
    fun `withQuantizedLocation is idempotent`() {
        val once = observationAt(37.416797637939453, -122.08899688720703).withQuantizedLocation()
        val twice = once.withQuantizedLocation()
        assertEquals(once.locationLat, twice.locationLat, 0.0)
        assertEquals(once.locationLon, twice.locationLon, 0.0)
    }

    private fun observationAt(lat: Double, lon: Double) = ObservationEntity(
        stationId = "S",
        stationName = "S",
        timestamp = 0L,
        temperature = 70f,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = "NWS",
    )
}

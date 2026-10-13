package com.weatherwidget.shared.util

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The NWS current value (both platforms) discounts personal stations by the user's setting, with the
 * actual line's three rules (user, 2026-10-13; plans/261013-shared-current-conditions-fetch.md).
 */
@Category(ShortDuration::class)
class SpatialInterpolatorPersonalDiscountTest {
    private val now = 1_791_900_000_000L

    private fun reading(id: String, km: Float, temp: Float, type: StationType) = ObservationReading(
        stationId = id,
        stationName = id,
        timestamp = now - 5 * 60_000L,
        temperature = temp,
        condition = "Clear",
        locationLat = 37.4,
        locationLon = -122.1,
        distanceKm = km,
        stationType = type,
        api = "NWS",
    )

    private val backyard = reading("AW020", 1f, 80f, StationType.PERSONAL)
    private val airport = reading("KNUQ", 4f, 70f, StationType.OFFICIAL)

    private fun blend(weight: Double, vararg readings: ObservationReading) =
        SpatialInterpolator.interpolateIDW(37.4, -122.1, readings.toList(), weight, now)!!

    @Test
    fun `a nearby personal station no longer sets the value at the default discount`() {
        val undiscounted = blend(1.0, backyard, airport)
        val discounted = blend(0.05, backyard, airport)
        // 1 km vs 4 km: undiscounted the backyard station carries 16/17 of the weight.
        assertEquals(79.41f, undiscounted, 0.01f)
        // 0.05 × 16 = 0.8 against the airport's 1: the airport now leads.
        assertEquals(74.44f, discounted, 0.01f)
    }

    @Test
    fun `at weight zero a personal station is not an input at all`() {
        assertEquals(70f, blend(0.0, backyard, airport), 0.0001f)
    }

    @Test
    fun `within the near-zero radius an official station wins over a closer personal one`() {
        val atHomePws = reading("PWS", 0.01f, 85f, StationType.PERSONAL)
        val atHomeOfficial = reading("KOFF", 0.05f, 71f, StationType.OFFICIAL)
        assertEquals(71f, blend(0.05, atHomePws, atHomeOfficial, airport), 0.0001f)
        // Only a personal station that close: it still wins (better than nothing).
        assertEquals(85f, blend(0.05, atHomePws, airport), 0.0001f)
    }

    @Test
    fun `official stations are unaffected by the discount`() {
        val other = reading("KSJC", 8f, 74f, StationType.OFFICIAL)
        assertTrue(blend(0.05, airport, other) == blend(1.0, airport, other))
    }
}

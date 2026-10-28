package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoCloudDistanceTest {

    @Test
    fun `imperial cloud distance converts miles to rounded integer metres`() {
        assertEquals(1_609, TomorrowIoApi.imperialDistanceToMeters(1.0))
        assertEquals(15_997, TomorrowIoApi.imperialDistanceToMeters(9.94))
    }

    @Test
    fun `missing negative and non-finite cloud distances remain unknown`() {
        assertNull(TomorrowIoApi.imperialDistanceToMeters(null))
        assertNull(TomorrowIoApi.imperialDistanceToMeters(-0.1))
        assertNull(TomorrowIoApi.imperialDistanceToMeters(Double.NaN))
        assertNull(TomorrowIoApi.imperialDistanceToMeters(Double.POSITIVE_INFINITY))
    }

    /**
     * Percentages are read as floats and rounded because `jsonPrimitive.intOrNull` returns null for
     * a fractional value, and a null rain chance renders exactly like a confident 0% — the failure
     * would look like a dry forecast rather than a bug.
     */
    @Test
    fun `percentages round to the nearest integer`() {
        assertEquals(5, TomorrowIoApi.percentOrNull(4.9f))
        assertEquals(0, TomorrowIoApi.percentOrNull(0.4f))
        assertEquals(33, TomorrowIoApi.percentOrNull(32.5f))
        assertEquals(100, TomorrowIoApi.percentOrNull(100f))
    }

    @Test
    fun `out of range percentages clamp and non-finite ones remain unknown`() {
        assertEquals(100, TomorrowIoApi.percentOrNull(140f))
        assertEquals(0, TomorrowIoApi.percentOrNull(-3f))
        assertNull(TomorrowIoApi.percentOrNull(null))
        assertNull(TomorrowIoApi.percentOrNull(Float.NaN))
        assertNull(TomorrowIoApi.percentOrNull(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `cloud cover discount applies 66 percent for high decks at or above 8000m`() {
        assertEquals(34, TomorrowIoApi.discountCloudCoverByHeight(100, 8_000))
        assertEquals(34, TomorrowIoApi.discountCloudCoverByHeight(99, 15_997))
        assertEquals(27, TomorrowIoApi.discountCloudCoverByHeight(78, 15_997))
        assertEquals(26, TomorrowIoApi.discountCloudCoverByHeight(76, 15_933))
        assertEquals(24, TomorrowIoApi.discountCloudCoverByHeight(70, 15_997))
        assertEquals(0, TomorrowIoApi.discountCloudCoverByHeight(0, 15_997))
    }

    @Test
    fun `cloud cover discount applies 33 percent for mid decks between 3000m and 7999m`() {
        assertEquals(67, TomorrowIoApi.discountCloudCoverByHeight(100, 7_999))
        assertEquals(67, TomorrowIoApi.discountCloudCoverByHeight(100, 3_000))
        assertEquals(54, TomorrowIoApi.discountCloudCoverByHeight(80, 4_892))
        assertEquals(38, TomorrowIoApi.discountCloudCoverByHeight(56, 4_828))
    }

    @Test
    fun `cloud cover retains full weight for low decks below 3000m`() {
        assertEquals(100, TomorrowIoApi.discountCloudCoverByHeight(100, 2_999))
        assertEquals(90, TomorrowIoApi.discountCloudCoverByHeight(90, 800))
        assertEquals(68, TomorrowIoApi.discountCloudCoverByHeight(68, 1_625))
    }

    @Test
    fun `cloud cover discount preserves raw when base is missing or null`() {
        assertEquals(99, TomorrowIoApi.discountCloudCoverByHeight(99, null))
        assertNull(TomorrowIoApi.discountCloudCoverByHeight(null, 15_997))
        assertNull(TomorrowIoApi.discountCloudCoverByHeight(null, null))
    }
}

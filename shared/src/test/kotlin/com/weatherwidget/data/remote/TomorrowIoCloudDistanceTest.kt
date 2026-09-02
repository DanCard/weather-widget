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
}

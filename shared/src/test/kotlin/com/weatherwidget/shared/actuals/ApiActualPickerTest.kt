package com.weatherwidget.shared.actuals

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ApiActualPickerTest {

    // Real coordinates from the on-device bug (Pixel, 2026-08-05): the widget passes the
    // quantized data location, so the damaged partial fragment sorts at distance 0 while the
    // complete legacy fragment sits ~20 m away.
    private val quantizedSite = 37.417 to -122.089
    private val legacySite = 37.416831970214844 to -122.08903503417969

    private data class Row(
        val source: String,
        val lat: Double,
        val lon: Double,
        val apiHigh: Float?,
        val apiLow: Float?,
    )

    private fun pick(rows: List<Row>, lat: Double = quantizedSite.first, lon: Double = quantizedSite.second) =
        ApiActualPicker.pickNearestComplete(
            rows = rows,
            lat = lat,
            lon = lon,
            sourceId = "NWS",
            source = { it.source },
            locationLat = { it.lat },
            locationLon = { it.lon },
            apiHigh = { it.apiHigh },
            apiLow = { it.apiLow },
        )

    @Test
    fun `complete legacy fragment beats nearer partial fragment (the missing API actual bug)`() {
        val partial = Row("NWS", quantizedSite.first, quantizedSite.second, apiHigh = 82.0f, apiLow = null)
        val complete = Row("NWS", legacySite.first, legacySite.second, apiHigh = 77.2f, apiLow = 56.1f)

        val picked = pick(listOf(partial, complete))

        assertEquals(complete, picked)
    }

    @Test
    fun `nearest complete fragment wins when several are complete`() {
        val near = Row("NWS", quantizedSite.first, quantizedSite.second, apiHigh = 80f, apiLow = 60f)
        val far = Row("NWS", legacySite.first, legacySite.second, apiHigh = 77.2f, apiLow = 56.1f)

        assertEquals(near, pick(listOf(far, near)))
    }

    @Test
    fun `returns null when every fragment is partial or empty`() {
        val nullLow = Row("NWS", quantizedSite.first, quantizedSite.second, apiHigh = 82.0f, apiLow = null)
        val nullHigh = Row("NWS", legacySite.first, legacySite.second, apiHigh = null, apiLow = 56.1f)
        val empty = Row("NWS", 37.42, -122.09, apiHigh = null, apiLow = null)

        assertNull(pick(listOf(nullLow, nullHigh, empty)))
    }

    @Test
    fun `ignores other sources entirely`() {
        val openMeteo = Row("OPEN_METEO", quantizedSite.first, quantizedSite.second, apiHigh = 75.5f, apiLow = 59.1f)

        assertNull(pick(listOf(openMeteo)))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(pick(emptyList()))
    }
}

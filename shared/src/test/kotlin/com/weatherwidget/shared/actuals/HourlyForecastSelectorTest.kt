package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyForecastSelectorTest {

    private val lat = 37.4168
    private val lon = -122.0889

    private fun fc(
        dateTime: Long,
        temperature: Float,
        source: String = "NWS",
        fetchedAt: Long,
        rowLat: Double? = lat,
        rowLon: Double? = lon,
    ) = HourlyForecast(
        dateTime = dateTime,
        temperature = temperature,
        condition = "Clear",
        source = source,
        fetchedAt = fetchedAt,
        locationLat = rowLat,
        locationLon = rowLon,
    )

    private fun select(rows: List<HourlyForecast>, source: String = "NWS") =
        HourlyForecastSelector.selectForecastsByTime(rows, source, lat, lon)

    @Test
    fun `freshest same-site fragment wins over a day-stale jitter fragment`() {
        // The reported bug: two rows for one hour at ~10 cm-apart coordinates, one fetched today and
        // one stale from yesterday. Freshest must win so every device converges.
        val hour = 1_000L
        val rows = listOf(
            fc(hour, 77f, fetchedAt = 1_000L, rowLat = lat + 0.000001), // stale (yesterday)
            fc(hour, 76f, fetchedAt = 9_000L, rowLat = lat), // fresh (today)
        )
        assertEquals(76f, select(rows).getValue(hour).temperature, 0f)
    }

    @Test
    fun `genuinely different neighbouring marker is excluded`() {
        val hour = 1_000L
        val rows = listOf(
            fc(hour, 76f, fetchedAt = 5_000L, rowLat = lat), // user's site
            fc(hour, 50f, fetchedAt = 9_999L, rowLat = lat + 0.005), // ~0.5km away, fresher but off-site
        )
        // Even though the off-site row is fresher, the same-site filter drops it first.
        assertEquals(76f, select(rows).getValue(hour).temperature, 0f)
    }

    @Test
    fun `display source is preferred over GENERIC_GAP filler at the same hour`() {
        val hour = 1_000L
        val rows = listOf(
            fc(hour, 60f, source = "Generic", fetchedAt = 9_999L),
            fc(hour, 72f, source = "NWS", fetchedAt = 1_000L),
        )
        assertEquals(72f, select(rows, source = "NWS").getValue(hour).temperature, 0f)
    }

    @Test
    fun `does not fall back to GENERIC_GAP when display source absent`() {
        val hour = 1_000L
        val rows = listOf(fc(hour, 60f, source = "Generic", fetchedAt = 1_000L))
        val result = select(rows, source = "NWS")
        org.junit.Assert.assertNull(result[hour])
    }

    @Test
    fun `rows with null coordinates are retained`() {
        val hour = 1_000L
        val rows = listOf(fc(hour, 65f, fetchedAt = 1_000L, rowLat = null, rowLon = null))
        assertEquals(65f, select(rows).getValue(hour).temperature, 0f)
    }
}

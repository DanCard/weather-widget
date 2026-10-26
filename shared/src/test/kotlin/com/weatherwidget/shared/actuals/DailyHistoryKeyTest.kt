package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyHistoryKeyTest {
    private val day = 1_792_800_000_000L

    private fun row(lat: Double, updatedAt: Long, source: String = "NWS") = DailyHistory(
        date = day, source = source, locationLat = lat, locationLon = -122.0888722,
        computedHighTemp = null, computedLowTemp = null, condition = "Clear", updatedAt = updatedAt,
    )

    @Test
    fun `a raw fix is filed under the rounded site`() {
        val q = DailyHistoryKey.quantized(row(37.4166014, 1))
        assertEquals(37.417, q.locationLat, 0.0)
        assertEquals(-122.089, q.locationLon, 0.0)
    }

    @Test
    fun `a rounded row is returned as is`() {
        val r = row(37.417, 1).copy(locationLon = -122.089)
        assertSame(r, DailyHistoryKey.quantized(r))
    }

    /** Desktop 10-17 shape: the blend on a fresh fragment, the frozen forecast on an older one. */
    @Test
    fun `merge fills the newest fragment's gaps from older ones`() {
        val newer = row(37.4166014, 200).copy(computedHighTemp = 73.4f, computedLowTemp = 64.5f, lastWriter = "blend_recompute")
        val older = row(37.417, 100).copy(
            computedHighTemp = 80f, computedLowTemp = 50f,
            forecastHighTemp = 86f, priorForecastHighTemp = 85f, noonCloudPercent = 20,
            apiHighTemp = 84f, apiLowTemp = 55.4f, apiStationId = "KNUQ", actualsSource = "nws_station_pull",
        )

        val merged = DailyHistoryKey.merge(listOf(older, newer))

        assertEquals("base is the newest", 73.4f, merged.computedHighTemp)
        assertEquals("blend_recompute", merged.lastWriter)
        assertEquals(86f, merged.forecastHighTemp)
        assertEquals(85f, merged.priorForecastHighTemp)
        assertEquals(20, merged.noonCloudPercent)
        assertEquals("api actual fills as a unit", "KNUQ", merged.apiStationId)
        assertEquals(84f, merged.apiHighTemp)
        assertEquals("nws_station_pull", merged.actualsSource)
        assertEquals(37.417, merged.locationLat, 0.0)
    }

    @Test
    fun `a half-present group is not mixed with another fragment's`() {
        val newer = row(37.417, 200).copy(apiHighTemp = 70f, apiStationId = "KPAO")
        val older = row(37.4166014, 100).copy(apiHighTemp = 71f, apiLowTemp = 50f, apiStationId = "KNUQ")

        val merged = DailyHistoryKey.merge(listOf(older, newer))

        assertEquals("KPAO", merged.apiStationId)
        assertNull("the low is not borrowed from another station", merged.apiLowTemp)
    }

    @Test
    fun `mergeAll keeps sources and days apart`() {
        val rows = listOf(
            row(37.4166014, 1), row(37.417, 2),
            row(37.4166014, 1, source = "OPEN_METEO"),
            row(37.4166014, 1).copy(date = day + 86_400_000L),
        )
        assertEquals(3, DailyHistoryKey.mergeAll(rows).size)
    }

    @Test
    fun `equal updatedAt prefers the fragment already on the rounded key`() {
        val raw = row(37.4166014, 5).copy(lastWriter = "raw")
        val rounded = row(37.417, 5).copy(locationLon = -122.089, lastWriter = "rounded")
        assertEquals("rounded", DailyHistoryKey.merge(listOf(raw, rounded)).lastWriter)
        assertEquals("rounded", DailyHistoryKey.merge(listOf(rounded, raw)).lastWriter)
    }
}

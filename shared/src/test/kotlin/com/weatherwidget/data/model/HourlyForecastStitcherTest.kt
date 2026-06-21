package com.weatherwidget.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyForecastStitcherTest {

    private val now = 10_000L
    private val lat = 37.0
    private val lon = -122.0

    private fun fc(
        dateTime: Long,
        temperature: Float,
        condition: String,
        cloudCover: Int? = null,
        precipProbability: Int? = null,
        precipAmountMm: Float? = null,
        source: String = "NWS",
        fetchedAt: Long,
        rowLat: Double? = lat,
        rowLon: Double? = lon,
    ) = HourlyForecast(
        dateTime = dateTime,
        temperature = temperature,
        condition = condition,
        cloudCover = cloudCover,
        precipProbability = precipProbability,
        precipAmountMm = precipAmountMm,
        source = source,
        fetchedAt = fetchedAt,
        locationLat = rowLat,
        locationLon = rowLon,
    )

    private fun stitch(current: List<HourlyForecast>, history: List<HourlyForecast>) =
        HourlyForecastStitcher.stitch(current, history, nowMs = now, centerLat = lat, centerLon = lon)

    @Test
    fun `future hour - live row wins and missing cloud cover is backfilled from history`() {
        val future = now + 5_000L
        val current = listOf(fc(future, 70f, "Clear", cloudCover = null, precipProbability = 10, precipAmountMm = 1.2f, fetchedAt = 200L))
        val history = listOf(fc(future, 68f, "Cloudy", cloudCover = 88, precipProbability = 30, precipAmountMm = 3.4f, fetchedAt = 100L))

        val stitched = stitch(current, history)

        assertEquals(1, stitched.size)
        val row = stitched.first()
        assertEquals(70f, row.temperature, 0f) // live wins for future hours
        assertEquals("Clear", row.condition)
        assertEquals(88, row.cloudCover) // backfilled from history
        assertEquals(10, row.precipProbability) // live value kept
        assertEquals(1.2f, row.precipAmountMm!!, 0f)
    }

    @Test
    fun `past hour - original prediction from earliest history wins`() {
        val past = now - 5_000L
        // Live row is NWS's REPLACE-overwritten hindsight revision; history holds the original forecast.
        val current = listOf(fc(past, 76f, "Sunny", cloudCover = 5, fetchedAt = 9_000L))
        val history = listOf(fc(past, 71f, "Cloudy", cloudCover = 60, fetchedAt = 1_000L))

        val stitched = stitch(current, history)

        assertEquals(1, stitched.size)
        val row = stitched.first()
        assertEquals(71f, row.temperature, 0f) // original prediction, not the live revision
        assertEquals("Cloudy", row.condition)
        assertEquals(60, row.cloudCover)
    }

    @Test
    fun `past hour - earliest snapshot supplies temp while a later bucket backfills cloud cover`() {
        val past = now - 5_000L
        val current = listOf(fc(past, 76f, "Sunny", cloudCover = 5, fetchedAt = 9_000L))
        // Earliest snapshot (original) has the temp but dropped skyCover; a later bucket carried it.
        val history = listOf(
            fc(past, 71f, "Clear", cloudCover = null, fetchedAt = 1_000L),
            fc(past, 73f, "Cloudy", cloudCover = 60, fetchedAt = 4_000L),
        )

        val row = stitch(current, history).first()
        assertEquals(71f, row.temperature, 0f) // earliest bucket temp
        assertEquals(60, row.cloudCover) // coalesced from the later bucket
    }

    @Test
    fun `same-site fragments collapse to the freshest, off-site marker is dropped`() {
        val future = now + 5_000L
        val current = listOf(
            // jitter fragment ~10 cm away, stale
            fc(future, 82f, "Hot", fetchedAt = 100L, rowLat = lat + 0.00001, rowLon = lon + 0.00001),
            // same site, fresh
            fc(future, 71f, "Mild", fetchedAt = 9_000L, rowLat = lat, rowLon = lon),
            // genuinely different neighbouring marker (~0.005°), should be excluded entirely
            fc(future, 50f, "Cold", fetchedAt = 9_999L, rowLat = lat + 0.005, rowLon = lon + 0.005),
        )

        val row = stitch(current, emptyList()).first()
        assertEquals(71f, row.temperature, 0f)
    }

    @Test
    fun `cloud cover stays null when neither current nor history has it`() {
        val future = now + 5_000L
        val result = stitch(
            current = listOf(fc(future, 70f, "Clear", fetchedAt = 200L)),
            history = listOf(fc(future, 69f, "Cloudy", fetchedAt = 100L)),
        )
        assertEquals(1, result.size)
        assertNull(result.first().cloudCover)
    }
}

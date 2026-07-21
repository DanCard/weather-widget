package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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
    fun `past hour - latest live forecast wins over older history snapshot`() {
        val past = now - 5_000L
        // Past hours are no longer special-cased: the freshest forecast (the live row) wins, just like
        // future hours. The older history snapshot does not override it.
        val current = listOf(fc(past, 76f, "Sunny", cloudCover = 5, fetchedAt = 9_000L))
        val history = listOf(fc(past, 71f, "Cloudy", cloudCover = 60, fetchedAt = 1_000L))

        val stitched = stitch(current, history)

        assertEquals(1, stitched.size)
        val row = stitched.first()
        assertEquals(76f, row.temperature, 0f) // latest live forecast, not the older snapshot
        assertEquals("Sunny", row.condition)
        assertEquals(5, row.cloudCover)
    }

    @Test
    fun `past hour - live supplies temp while the freshest history bucket backfills cloud cover`() {
        val past = now - 5_000L
        // Live (freshest) has the temp but dropped skyCover; the freshest history bucket carries it.
        val current = listOf(fc(past, 76f, "Sunny", cloudCover = null, fetchedAt = 9_000L))
        val history = listOf(
            fc(past, 71f, "Clear", cloudCover = null, fetchedAt = 1_000L),
            fc(past, 73f, "Cloudy", cloudCover = 60, fetchedAt = 4_000L),
        )

        val row = stitch(current, history).first()
        assertEquals(76f, row.temperature, 0f) // live (freshest) temp
        assertEquals(60, row.cloudCover) // backfilled from history
    }

    @Test
    fun `past hour with no live row falls back to the freshest history snapshot`() {
        val past = now - 5_000L
        // Fully-past days age out of the live table; the latest (freshest) snapshot then represents
        // the most accurate forecast we have for that hour — not the earliest long-range one.
        val history = listOf(
            fc(past, 79f, "Hot", fetchedAt = 1_000L),   // stale 6-day-out prediction
            fc(past, 73f, "Mild", fetchedAt = 8_000L),  // latest revision
        )

        val row = stitch(emptyList(), history).first()
        assertEquals(73f, row.temperature, 0f) // freshest snapshot, not the earliest
        assertEquals("Mild", row.condition)
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

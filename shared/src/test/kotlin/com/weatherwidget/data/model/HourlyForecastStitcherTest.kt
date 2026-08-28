package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        cloudCoverLow: Int? = null,
        cloudCoverMid: Int? = null,
        cloudCoverHigh: Int? = null,
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
        cloudCoverLow = cloudCoverLow,
        cloudCoverMid = cloudCoverMid,
        cloudCoverHigh = cloudCoverHigh,
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

    /**
     * The three cases around the no-same-site fallback, which the test above cannot reach: it always
     * supplies a same-site row, so the off-site marker loses on merit and `ifEmpty` never fires.
     *
     * That gap is why an unbounded fallback survived from 72e5a033 to 2026-08-28. Each case here
     * removes the same-site row so the fallback is actually exercised.
     */
    @Test
    fun `hour with no same-site row borrows from a fragment just outside the same-site box`() {
        val future = now + 5_000L
        // 0.0021 deg: outside SAME_SITE_TOLERANCE_DEG (0.002) by a hair, and a real observed value --
        // HourlyForecastLoader records a fragment at 0.0021832886. Blanking this hour is the
        // regression 72e5a033 fixed, so the bound must still admit it.
        val nearlySameSite = fc(future, 71f, "Mild", fetchedAt = 9_000L, rowLat = lat + 0.0021, rowLon = lon)

        val rows = stitch(listOf(nearlySameSite), emptyList())

        assertEquals("the hour must not go blank", 1, rows.size)
        assertEquals(71f, rows.first().temperature, 0f)
    }

    @Test
    fun `hour with no same-site row is left empty rather than borrowing from another place`() {
        val future = now + 5_000L
        // 0.068 deg of longitude: the real gap between the two device sites on 2026-08-28, ~6 km.
        // Inside the caller's +/-0.1 read box, so an unbounded fallback took it.
        val anotherTown = fc(future, 90f, "Hot", fetchedAt = 9_999L, rowLat = lat, rowLon = lon - 0.068)

        val rows = stitch(listOf(anotherTown), emptyList())

        assertTrue(
            "an hour only another site covers must be dropped, not borrowed: $rows",
            rows.isEmpty(),
        )
    }

    @Test
    fun `a borrowed row does not carry another site's coordinates into the result`() {
        val future = now + 5_000L
        val nearby = fc(future, 71f, "Mild", fetchedAt = 9_000L, rowLat = lat + 0.0021, rowLon = lon)

        val row = stitch(listOf(nearby), emptyList()).first()

        // The row keeps its own coordinates by design (toEntity coalesces `locationLat ?: fallback`),
        // so what matters is that they stay close enough that a downstream firstOrNull() adopting them
        // as the render location cannot move the observation blend to another site. That adoption is
        // exactly what happened on 2026-08-28.
        assertTrue(
            "borrowed coordinates must stay within the fallback bound of the centre",
            kotlin.math.abs(row.locationLat!! - lat) <= HourlyForecastStitcher.NEARBY_FALLBACK_TOLERANCE_DEG &&
                kotlin.math.abs(row.locationLon!! - lon) <= HourlyForecastStitcher.NEARBY_FALLBACK_TOLERANCE_DEG,
        )
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

    @Test
    fun `missing cloud layers are independently backfilled from history`() {
        val future = now + 5_000L
        val row = stitch(
            current = listOf(
                fc(
                    future,
                    70f,
                    "Clear",
                    cloudCover = 90,
                    cloudCoverLow = 5,
                    fetchedAt = 200L,
                ),
            ),
            history = listOf(
                fc(
                    future,
                    69f,
                    "Cloudy",
                    cloudCover = 100,
                    cloudCoverLow = 10,
                    cloudCoverMid = 60,
                    cloudCoverHigh = 95,
                    fetchedAt = 100L,
                ),
            ),
        ).single()

        assertEquals(listOf(90, 5, 60, 95), listOf(row.cloudCover, row.cloudCoverLow, row.cloudCoverMid, row.cloudCoverHigh))
    }
}

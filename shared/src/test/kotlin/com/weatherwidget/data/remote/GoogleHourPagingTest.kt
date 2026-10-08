package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Page 1 (next 24 h) of Google's `forecast/hours` decides whether pages 2–3 are worth two more
 * billed calls (user's design, 2026-10-07).
 */
@Category(ShortDuration::class)
class GoogleHourPagingTest {

    private val hour = 3_600_000L
    private val now = 1_791_400_000_000L / hour * hour
    private val horizonEnd = now + 72 * hour

    private fun hours(from: Int, until: Int, fetchedAt: Long = now - 4 * hour, temp: (Int) -> Float = { 60f + it % 10 }) =
        (from until until).map {
            HourlyForecast(
                dateTime = now + it * hour,
                temperature = temp(it),
                condition = "Clear",
                precipProbability = 10,
                fetchedAt = fetchedAt,
            )
        }

    private val stored = hours(0, 72)
    private val page1 = hours(0, 24, fetchedAt = now)

    @Test
    fun `unchanged first page stops`() {
        val d = GoogleHourPaging.decide(page1, stored, now, horizonEnd)
        assertFalse(d.reason, d.fetchRest)
        assertTrue(d.reason, d.reason.startsWith("unchanged"))
    }

    @Test
    fun `small drift under the tolerances still stops`() {
        val drifted = page1.map { it.copy(temperature = it.temperature + 0.6f, precipProbability = 15) }
        assertFalse(GoogleHourPaging.decide(drifted, stored, now, horizonEnd).fetchRest)
    }

    @Test
    fun `a one and a half degree shift continues`() {
        val shifted = page1.mapIndexed { i, h -> if (i == 10) h.copy(temperature = h.temperature + 1.5f) else h }
        val d = GoogleHourPaging.decide(shifted, stored, now, horizonEnd)
        assertTrue(d.reason, d.fetchRest)
        assertTrue(d.reason, d.reason.startsWith("changed"))
    }

    @Test
    fun `a rain-chance jump continues`() {
        val wetter = page1.mapIndexed { i, h -> if (i == 5) h.copy(precipProbability = 40) else h }
        assertTrue(GoogleHourPaging.decide(wetter, stored, now, horizonEnd).fetchRest)
    }

    @Test
    fun `more than two condition changes continue, two do not`() {
        fun recond(n: Int) = page1.mapIndexed { i, h -> if (i < n) h.copy(condition = "Cloudy") else h }
        assertFalse(GoogleHourPaging.decide(recond(2), stored, now, horizonEnd).fetchRest)
        assertTrue(GoogleHourPaging.decide(recond(3), stored, now, horizonEnd).fetchRest)
    }

    @Test
    fun `no cache continues`() {
        assertTrue(GoogleHourPaging.decide(page1, emptyList(), now, horizonEnd).reason.startsWith("no_cache"))
    }

    @Test
    fun `a tail short of the horizon continues`() {
        val d = GoogleHourPaging.decide(page1, hours(0, 48), now, horizonEnd)
        assertTrue(d.reason, d.fetchRest && d.reason.startsWith("tail_short"))
    }

    /** The 2026-10-08 case: a fetch 4 h after the last finds the tail 4 h short of the new horizon. */
    @Test
    fun `a tail short by the time since the last fetch stops when unchanged`() {
        val d = GoogleHourPaging.decide(page1, hours(0, 68), now, horizonEnd)
        assertFalse(d.reason, d.fetchRest)
        assertTrue(d.reason, d.reason.startsWith("unchanged"))
    }

    @Test
    fun `a tail older than twelve hours continues`() {
        val d = GoogleHourPaging.decide(page1, hours(0, 72, fetchedAt = now - 13 * hour), now, horizonEnd)
        assertTrue(d.reason, d.fetchRest && d.reason.startsWith("tail_old"))
    }

    /** The Android save skips unchanged rows, so one old fetchedAt in the tail must not count as stale. */
    @Test
    fun `tail age is the newest write in the tail`() {
        val mixed = stored.mapIndexed { i, h -> if (i == 60) h.copy(fetchedAt = now - 30 * hour) else h }
        assertFalse(GoogleHourPaging.decide(page1, mixed, now, horizonEnd).fetchRest)
    }

    @Test
    fun `little overlap with what is stored continues`() {
        val sparse = stored.filterIndexed { i, _ -> i >= 20 }
        val d = GoogleHourPaging.decide(page1, sparse, now, horizonEnd)
        assertTrue(d.reason, d.fetchRest && d.reason.startsWith("overlap_low"))
    }
}

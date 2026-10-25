package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** The one live-hourly write rule both platforms store through (`plans/261024-shared-forecast-persist-rules.md`). */
@Category(ShortDuration::class)
class HourlyForecastWriteTest {
    private val h = 3_600_000L
    private val now = 1_792_900_800_000L + 20 * 60_000L
    private val hour0 = now - now % h

    private fun hr(offset: Int, cloud: Int? = 40, pop: Int? = 10, fetchedAt: Long = 0L) =
        HourlyForecast(hour0 + offset * h, 60f, "Clear", precipProbability = pop, cloudCover = cloud, fetchedAt = fetchedAt)

    @Test
    fun `elapsed hours are dropped, the in-progress hour kept`() {
        val plan = HourlyForecastWrite.plan(listOf(hr(-2), hr(-1), hr(0), hr(1)), emptyList(), "OPEN_METEO", now, keep = null)
        assertEquals(listOf(hour0, hour0 + h), plan.live.map { it.dateTime })
    }

    @Test
    fun `a field the reply lacks keeps the stored value, a sent one wins`() {
        val plan = HourlyForecastWrite.plan(
            listOf(hr(1, cloud = null, pop = 70)),
            listOf(hr(1, cloud = 40, pop = 10, fetchedAt = now - h)),
            "OPEN_METEO", now, keep = null,
        )
        assertEquals(40, plan.live.single().cloudCover)
        assertEquals(70, plan.live.single().precipProbability)
    }

    @Test
    fun `every written hour is stamped now, unchanged or not`() {
        val plan = HourlyForecastWrite.plan(listOf(hr(1)), listOf(hr(1, fetchedAt = now - 5 * h)), "GOOGLE_WEATHER", now, keep = null)
        assertEquals(now, plan.live.single().fetchedAt)
        assertEquals("GOOGLE_WEATHER", plan.live.single().source)
    }

    @Test
    fun `the horizon bounds live rows and, shorter, the snapshot`() {
        val keep = HourlyOnDemand.KeepUntil(liveMs = hour0 + 3 * h, snapshotMs = hour0 + 2 * h)
        val plan = HourlyForecastWrite.plan((1..4).map { hr(it) }, emptyList(), "OPEN_METEO", now, keep)
        assertEquals(listOf(hour0 + h, hour0 + 2 * h), plan.live.map { it.dateTime })
        assertEquals(listOf(hour0 + h), plan.snapshot.map { it.dateTime })
    }

    @Test
    fun `nothing to read when the reply is empty`() {
        assertNull(HourlyForecastWrite.readRange(emptyList()))
    }
}

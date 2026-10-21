package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.ZoneId

/**
 * The post-fetch steps both platforms now run from `:shared` ([ForecastHorizonMarkers.mark],
 * [GoogleHistoryRun], [ElapsedForecastBackfill.backfill]); each case pins a behaviour one platform
 * lacked before (plans/261021-android-desktop-shared-code-audit.md §3 B).
 */
@Category(ShortDuration::class)
class SharedFetchStepsTest {
    private val hour = 3_600_000L
    private val now = Instant.parse("2026-10-21T18:00:00Z").toEpochMilli()

    private fun row(dateTime: Long, fetchedAt: Long) = HourlyForecast(
        dateTime = dateTime, temperature = 60f, condition = "Clear", source = "GOOGLE_WEATHER", fetchedAt = fetchedAt,
    )

    @Test
    fun `every horizon mark is logged, LONG marks both cadences`() = runBlocking {
        // Desktop wrote its marker rows but never the FORECAST_HORIZON line Android logs.
        val marks = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val markers = object : ForecastHorizonMarkers {
            override fun lastLongMs(): Long? = null
            override fun lastShortMs(): Long? = null
            override suspend fun markLong(nowMs: Long) { marks += "long" }
            override suspend fun markShort(nowMs: Long) { marks += "short" }
        }
        ForecastHorizonMarkers.mark(markers, "NWS", ForecastHorizonCadence.Horizon.LONG, now) { tag, msg, _ -> logs += "$tag $msg" }
        ForecastHorizonMarkers.mark(markers, "NWS", null, now) { tag, msg, _ -> logs += "$tag $msg" }
        assertEquals(listOf("short", "long", "short"), marks)
        assertEquals(listOf("FORECAST_HORIZON source=NWS horizon=LONG", "FORECAST_HORIZON source=NWS horizon=unjudged"), logs)
    }

    @Test
    fun `a past day's refill writes only its stale hours and logs them`() = runBlocking {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val freshHour = dayStart + 10 * hour
        val staleHour = dayStart + 11 * hour
        val saved = mutableListOf<HourlyForecast>()
        val logs = mutableListOf<String>()
        val run = GoogleHistoryRun(object : GoogleHistoryRun.Host {
            override suspend fun coveredHistoryHours(startMs: Long, endMs: Long) = emptyList<Long>()
            override suspend fun liveHours(startMs: Long, endMs: Long) = listOf(
                row(freshHour, fetchedAt = freshHour - hour),
                row(staleHour, fetchedAt = staleHour - 3 * 24 * hour),
            ).filter { it.dateTime in startMs..endMs }
            override suspend fun saveRefill(rows: List<HourlyForecast>) { saved += rows }
            override suspend fun log(tag: String, message: String, level: String) { logs += tag }
        })

        run.refill(listOf(row(freshHour, now), row(staleHour, now)), day, now)

        assertTrue(saved.none { it.dateTime == freshHour })
        assertTrue(saved.any { it.dateTime == staleHour })
        assertEquals(listOf(GoogleHistoryRefill.LOG_TAG), logs)
    }

    @Test
    fun `an elapsed backfill that offers nothing in the window writes and logs nothing`() = runBlocking {
        val store = object : ElapsedForecastBackfill.Store {
            override suspend fun coveredHours(startMs: Long, endMs: Long): Set<Long> = error("not read")
            override suspend fun insert(rows: List<HourlyForecast>) = error("not written")
        }
        assertNull(ElapsedForecastBackfill.backfill(listOf(row(now + hour, now)), now, store))
    }
}

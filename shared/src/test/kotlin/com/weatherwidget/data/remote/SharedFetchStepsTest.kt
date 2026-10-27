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

/**
 * The post-fetch steps both platforms now run from `:shared` ([ForecastHorizonMarkers.mark],
 * [ElapsedForecastBackfill.backfill]); each case pins a behaviour one platform
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
    fun `every horizon mark is logged, only a judged fetch restarts the cadence`() = runBlocking {
        // Desktop wrote its marker rows but never the FORECAST_HORIZON line Android logs.
        val marks = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val markers = object : ForecastHorizonMarkers {
            override fun lastLongMs(): Long? = null
            override suspend fun markLong(nowMs: Long) { marks += "long" }
        }
        ForecastHorizonMarkers.mark(markers, "NWS", ForecastHorizonCadence.Horizon.LONG, now) { tag, msg, _ -> logs += "$tag $msg" }
        ForecastHorizonMarkers.mark(markers, "NWS", null, now) { tag, msg, _ -> logs += "$tag $msg" }
        assertEquals(listOf("long"), marks)
        assertEquals(listOf("FORECAST_HORIZON source=NWS horizon=LONG", "FORECAST_HORIZON source=NWS horizon=unjudged"), logs)
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

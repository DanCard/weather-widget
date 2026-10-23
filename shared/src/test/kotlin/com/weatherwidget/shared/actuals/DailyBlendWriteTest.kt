package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/** [DailyBlendWrite]: how a recomputed day's blend lands on `daily_history`, both platforms. */
@Category(ShortDuration::class)
class DailyBlendWriteTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 23)
    private val lat = 37.417
    private val lon = -122.089

    private fun row(
        date: LocalDate = today,
        high: Float? = 70f,
        low: Float? = 50f,
        siteLat: Double = lat,
        updatedAt: Long = 1L,
        actualsSource: String? = null,
        apiHigh: Float? = null,
    ) = DailyHistory(
        date = date.toEpochDay() * 86_400_000L, source = WeatherSource.NWS.id, locationLat = siteLat, locationLon = lon,
        computedHighTemp = high, computedLowTemp = low, condition = "Clear", updatedAt = updatedAt,
        actualsSource = actualsSource, apiHighTemp = apiHigh,
    )

    private fun plan(date: LocalDate, new: DailyHistory, existing: List<DailyHistory>) =
        DailyBlendWrite.plan(date, today, listOf(new), existing, lat, lon, dayReadings = emptyList(), zone = zone)

    @Test
    fun aNeighbouringSitesRowIsNeverTheOneWritten() {
        // Samsung 2026-08-22 shape: the box holds another site's row 0.03° away; this site has none.
        val elsewhere = row(siteLat = lat + 0.03, high = 66.5f, low = 57f)
        val p = plan(today, row(high = 72f, low = 51f, updatedAt = 9L), listOf(elsewhere))
        assertTrue(p.updates.isEmpty())
        assertEquals(1, p.inserts.size)
        assertEquals(lat, p.inserts.single().locationLat, 0.0)
        assertEquals(DailyHistoryWriter.BLEND_RECOMPUTE.storedValue, p.inserts.single().lastWriter)
    }

    @Test
    fun anUnchangedRowIsNotRewritten() {
        val stored = row().copy(lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue)
        val p = plan(today, stored.copy(updatedAt = 9L), listOf(stored))
        assertTrue(p.updates.isEmpty())
        assertTrue(p.inserts.isEmpty())
        assertEquals(listOf("DAILY_HISTORY_STABLE"), p.logs.map { it.tag })
    }

    @Test
    fun aChangedRowIsAConditionalUpdateOfTheBlendOnly() {
        val stored = row(updatedAt = 5L, apiHigh = 71f)
        val p = plan(today, row(high = 73f, low = 49f, updatedAt = 9L), listOf(stored))
        val update = p.updates.single()
        assertEquals(5L, update.expectedUpdatedAt)
        assertEquals(9L, update.newUpdatedAt)
        assertEquals(73f, update.merged.computedHighTemp)
        assertEquals("other writers' columns are kept", 71f, update.merged.apiHighTemp)
    }

    @Test
    fun aPastDayFromTheStationPullKeepsItsValues() {
        val yesterday = today.minusDays(1)
        val stored = row(date = yesterday, high = 70f, low = 50f, actualsSource = DailyActualsSource.NWS_STATION_PULL.storedValue)
        val p = plan(yesterday, row(date = yesterday, high = 65f, low = 55f, updatedAt = 9L), listOf(stored))
        // Precip/condition may still move; the frozen high / low may not.
        p.updates.forEach {
            assertEquals(70f, it.merged.computedHighTemp)
            assertEquals(50f, it.merged.computedLowTemp)
        }
    }

}

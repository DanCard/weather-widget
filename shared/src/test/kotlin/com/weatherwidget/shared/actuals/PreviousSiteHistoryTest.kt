package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/**
 * Coordinates are the Pixel 7 Pro trip of 2026-09-24/25: Kyiv (measured Silurian history) → Lviv
 * (forecast-only rows for the same days). Bug report 2026-09-24: "no history when on the move".
 */
@Category(ShortDuration::class)
class PreviousSiteHistoryTest {
    private val today = LocalDate.parse("2026-09-25")
    private val yesterday = today.minusDays(1)
    private val lviv = 49.833 to 24.034
    private val kyiv = 50.45 to 30.49
    private val zhytomyr = 49.975 to 29.512

    private fun row(
        at: Pair<Double, Double>,
        date: LocalDate = yesterday,
        hi: Float? = null,
        lo: Float? = null,
        fHi: Float? = null,
        fLo: Float? = null,
        updatedAt: Long = 1L,
        source: String = "SILURIAN",
    ) = DailyHistory(
        date = date.toEpochDay() * 86_400_000L,
        source = source,
        locationLat = at.first,
        locationLon = at.second,
        computedHighTemp = hi,
        computedLowTemp = lo,
        condition = "x",
        updatedAt = updatedAt,
        forecastHighTemp = fHi,
        forecastLowTemp = fLo,
    )

    private fun fill(local: List<DailyHistory>, donors: List<DailyHistory>) =
        PreviousSiteHistory.fill(
            local = local.groupBy { it.source }.mapValues { (_, r) -> r.associateBy { it.toLocalDate() } },
            candidates = donors,
            lat = lviv.first,
            lon = lviv.second,
            today = today,
        )

    @Test
    fun `local forecast-only yesterday takes the previous site's measured extremes and keeps its own forecast`() {
        val out = fill(
            local = listOf(row(lviv, fHi = 57f, fLo = 48f)),
            donors = listOf(row(kyiv, hi = 61.9f, lo = 44.9f)),
        )["SILURIAN"]!![yesterday]!!
        assertEquals(61.9f, out.computedHighTemp)
        assertEquals(44.9f, out.computedLowTemp)
        assertEquals("forecast overlay stays local", 57f, out.forecastHighTemp)
        assertTrue(out.isActualsBorrowed)
        assertFalse("a local row exists", out.borrowedWithoutLocalRow)
        assertEquals(lviv.first, out.locationLat, 0.0)
        assertEquals(460.0, out.actualsBorrowedFromKm!!, 40.0)
    }

    @Test
    fun `a measured local row is never replaced`() {
        val out = fill(
            local = listOf(row(lviv, hi = 59.5f, lo = 47.8f)),
            donors = listOf(row(kyiv, hi = 61.9f, lo = 44.9f)),
        )["SILURIAN"]!![yesterday]!!
        assertEquals(59.5f, out.computedHighTemp)
        assertFalse(out.isActualsBorrowed)
    }

    @Test
    fun `no local row takes the whole donor row and stays marked missing`() {
        val out = fill(local = emptyList(), donors = listOf(row(kyiv, hi = 61.9f, lo = 44.9f)))["SILURIAN"]!![yesterday]!!
        assertTrue(out.isActualsBorrowed)
        assertTrue(out.borrowedWithoutLocalRow)
    }

    @Test
    fun `only yesterday is borrowed`() {
        val twoDaysAgo = today.minusDays(2)
        val out = fill(
            local = listOf(row(lviv, date = twoDaysAgo, fHi = 58f, fLo = 49f)),
            donors = listOf(row(kyiv, date = twoDaysAgo, hi = 60f, lo = 45f), row(kyiv, date = today, hi = 50f, lo = 40f)),
        )
        val older = out["SILURIAN"]!![twoDaysAgo]!!
        assertNull(older.computedHighTemp)
        assertFalse(older.isActualsBorrowed)
        assertNull("today is never borrowed", out["SILURIAN"]!![today])
    }

    @Test
    fun `nearest measured donor wins, forecast-only donors and in-box rows are ignored`() {
        val out = fill(
            local = listOf(row(lviv, fHi = 57f, fLo = 48f)),
            donors = listOf(
                row(kyiv, hi = 61.9f, lo = 44.9f),
                row(zhytomyr, hi = 53f, lo = 48f),
                row(49.0 to 24.0, fHi = 1f, fLo = 0f), // nearer, but forecast-only
                row(lviv.first + 0.05 to lviv.second, hi = 99f, lo = 98f), // inside the box: a local fragment
            ),
        )["SILURIAN"]!![yesterday]!!
        assertEquals("Zhytomyr is nearer than Kyiv", 53f, out.computedHighTemp)
    }

    @Test
    fun `sources never mix`() {
        val out = fill(
            local = listOf(row(lviv, fHi = 57f, fLo = 48f, source = "OPEN_METEO")),
            donors = listOf(row(kyiv, hi = 61.9f, lo = 44.9f, source = "SILURIAN")),
        )
        assertNull(out["OPEN_METEO"]!![yesterday]!!.computedHighTemp)
    }
}

package com.weatherwidget.widget

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class StartupActualsRefreshTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun row(high: Float, updatedAt: Long = 1L, source: String = "NWS") = DailyHistory(
        date = today.toEpochDay() * 86_400_000L, source = source, locationLat = 37.4, locationLon = -122.1,
        computedHighTemp = high, computedLowTemp = 50f, condition = "x", updatedAt = updatedAt,
    )

    @Test
    fun `equal values written at different times are not a change`() {
        val provisional = mapOf("NWS" to mapOf(today to row(53.88f, updatedAt = 1L)))
        val live = mapOf("NWS" to mapOf(today to row(53.88f, updatedAt = 999L)))

        assertEquals(emptySet<String>(), StartupActualsRefresh.changedSources(provisional, live))
    }

    @Test
    fun `a changed value, a day the live pass adds, or a source it drops is a change`() {
        val provisional = mapOf(
            "NWS" to mapOf(today to row(53f)),
            "SILURIAN" to mapOf(today to row(54f, source = "SILURIAN")),
            "OLD" to mapOf(today to row(1f, source = "OLD")),
        )
        val live = mapOf(
            "NWS" to mapOf(today to row(53.5f)),
            "SILURIAN" to mapOf(today to row(54f, source = "SILURIAN"), today.minusDays(3) to row(60f, source = "SILURIAN")),
        )

        assertEquals(setOf("NWS", "SILURIAN", "OLD"), StartupActualsRefresh.changedSources(provisional, live))
    }

    @Test
    fun `only widgets showing a changed source are repainted`() {
        val provisional = mapOf("NWS" to mapOf(today to row(53f)), "SILURIAN" to mapOf(today to row(54f, source = "SILURIAN")))

        val repaint = StartupActualsRefresh.widgetsToRepaint(
            mapOf(78 to "NWS", 79 to "NWS", 88 to "SILURIAN"), setOf("SILURIAN"), provisional, today,
        )

        assertEquals(listOf(88), repaint.toList())
    }

    /** The provisional paint made no missing-actuals request, so the live render must make it. */
    @Test
    fun `a widget whose provisional map had no today is repainted even when the live one has none either`() {
        val provisional = mapOf("NWS" to mapOf(today.minusDays(1) to row(60f)))

        val repaint = StartupActualsRefresh.widgetsToRepaint(mapOf(78 to "NWS", 90 to "TOMORROW_IO"), emptySet(), provisional, today)

        assertEquals(listOf(78, 90), repaint.toList())
    }

    @Test
    fun `describeChange names the differing fields per date`() {
        val provisional = mapOf("NWS" to mapOf(today to row(53f)))
        val live = mapOf("NWS" to mapOf(today to row(53f).copy(observedFogLevel = 2), today.minusDays(2) to row(60f)))

        assertEquals("2026-11-16:added 2026-11-18:observedFogLevel", StartupActualsRefresh.describeChange(provisional, live, "NWS"))
    }
}

package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure unit coverage for the stations list's empty-state policy: show what the DB has, labelled with
 * its age, rather than a blank list — and never reach across sources to fill it.
 */
@Category(ShortDuration::class)
class StaleObservationFallbackTest {

    private data class Row(val id: String, val timestamp: Long)

    private val now = 1_786_849_738_198L // 2026-08-15 20:08 local, the incident timestamp

    private fun resolve(recent: List<Row>, older: List<Row>) =
        StaleObservationFallback.resolve(recent, older, now) { it.timestamp }

    @Test
    fun `in-window rows win and are not labelled stale`() {
        val recent = listOf(Row("KSJC", now - 600_000L))
        val outcome = resolve(recent, listOf(Row("KSJC", now - 600_000L), Row("KNUQ", now - 4 * 86_400_000L)))

        assertEquals(recent, outcome.rows)
        assertNull("in-window rows must not carry a staleness age", outcome.ageMs)
    }

    @Test
    fun `an empty window falls back to older rows and reports the newest age`() {
        val older = listOf(Row("KSJC", now - 3 * 86_400_000L), Row("KNUQ", now - 9 * 86_400_000L))
        val outcome = resolve(emptyList(), older)

        assertEquals(older, outcome.rows)
        assertEquals(3 * 86_400_000L, outcome.ageMs)
    }

    @Test
    fun `nothing anywhere stays empty and unlabelled`() {
        val outcome = resolve(emptyList(), emptyList())

        assertEquals(emptyList<Row>(), outcome.rows)
        assertNull(outcome.ageMs)
    }

    @Test
    fun `a backwards clock never renders a negative age`() {
        val outcome = resolve(emptyList(), listOf(Row("KSJC", now + 3_600_000L)))

        assertEquals(0L, outcome.ageMs)
    }

    @Test
    fun `age formatting matches the app's abbreviation style`() {
        assertEquals("0min", StaleObservationFallback.formatAge(0L))
        assertEquals("45min", StaleObservationFallback.formatAge(45 * 60_000L))
        assertEquals("59min", StaleObservationFallback.formatAge(59 * 60_000L + 59_000L))
        assertEquals("1h", StaleObservationFallback.formatAge(60 * 60_000L))
        assertEquals("23h", StaleObservationFallback.formatAge(23 * 3_600_000L))
        assertEquals("1d", StaleObservationFallback.formatAge(24 * 3_600_000L))
        assertEquals("3d", StaleObservationFallback.formatAge(3 * 86_400_000L + 3_600_000L))
    }

    @Test
    fun `the recent window is the 24h one the screen queries`() {
        assertEquals(24L * 60L * 60L * 1000L, StaleObservationFallback.RECENT_WINDOW_MS)
    }
}

package com.weatherwidget.data.local

import com.weatherwidget.data.local.HistoryPruneSchedule.Decision
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HistoryPruneScheduleTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun decide(
        ageMs: Long,
        prerequisites: Boolean = true,
        onPower: Boolean = true,
        notInUse: Boolean = true,
    ) = HistoryPruneSchedule.decide(now, now - ageMs, prerequisites, { onPower }, { notInUse })

    @Test
    fun `not due inside a day, whatever the device is doing`() {
        assertEquals(Decision.NOT_DUE, decide(23 * hour))
    }

    @Test
    fun `due runs on power when not in use`() {
        assertEquals(Decision.RUN, decide(25 * hour))
    }

    @Test
    fun `due waits while in use`() {
        assertEquals(Decision.WAITING_FOR_IDLE, decide(25 * hour, notInUse = false))
    }

    @Test
    fun `due waits off power`() {
        assertEquals(Decision.WAITING_FOR_IDLE, decide(25 * hour, onPower = false))
    }

    @Test
    fun `overdue runs while in use`() {
        assertEquals(Decision.RUN, decide(HistoryPruneSchedule.OVERDUE_MS, notInUse = false))
    }

    @Test
    fun `overdue still waits off power`() {
        assertEquals(Decision.WAITING_FOR_IDLE, decide(HistoryPruneSchedule.OVERDUE_MS, onPower = false, notInUse = false))
    }

    @Test
    fun `never run counts as overdue`() {
        assertTrue(HistoryPruneSchedule.isOverdue(now, 0L))
        assertEquals(Decision.RUN, decide(now, notInUse = false))
    }

    @Test
    fun `just under the limit is not overdue`() {
        assertFalse(HistoryPruneSchedule.isOverdue(now, now - HistoryPruneSchedule.OVERDUE_MS + 1))
    }

    @Test
    fun `pending prerequisites beat every other gate, even overdue`() {
        assertEquals(Decision.PREREQUISITES_PENDING, decide(now, prerequisites = false))
    }

    @Test
    fun `a prune that is not due never probes the device`() {
        var probes = 0
        HistoryPruneSchedule.decide(now, now - hour, true, { probes++; true }, { probes++; true })
        assertEquals(0, probes)
    }

    @Test
    fun `an overdue prune does not probe the screen`() {
        var screenProbes = 0
        HistoryPruneSchedule.decide(now, 0L, true, { true }, { screenProbes++; false })
        assertEquals(0, screenProbes)
    }

    @Test
    fun `without device conditions a due prune runs, for Android's constrained worker`() {
        assertEquals(Decision.RUN, HistoryPruneSchedule.decide(now, now - 25 * hour, true))
    }
}

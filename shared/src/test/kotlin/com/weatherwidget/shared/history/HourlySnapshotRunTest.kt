package com.weatherwidget.shared.history

import com.weatherwidget.shared.history.HourlySnapshotRun.Reason
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** User, 2026-10-24: performance/261024-hourly-snapshot-windows-by-view-and-battery.md. */
@Category(ShortDuration::class)
class HourlySnapshotRunTest {
    private val hour = 3_600_000L
    private val now = 1_792_900_000_000L

    private fun decide(displayed: Boolean = false, viewed: Boolean = true, high: Boolean = true, lastAgeH: Double? = null) =
        HourlySnapshotRun.decide(now, displayed, viewed, high, lastAgeH?.let { now - (it * hour).toLong() })

    @Test
    fun `the displayed source saves every fetch, labelled with the fetch time`() {
        assertEquals(now, decide(displayed = true, viewed = false, high = false, lastAgeH = 0.5).labelMs)
    }

    @Test
    fun `a source not viewed in 8 days is never saved`() {
        assertEquals(Reason.NOT_VIEWED, decide(viewed = false, lastAgeH = 48.0).reason)
        assertNull(decide(viewed = false).labelMs)
    }

    @Test
    fun `another viewed source saves once its last snapshot is 11 h old on a charger or high battery`() {
        assertEquals(Reason.RECENT, decide(high = true, lastAgeH = 10.9).reason)
        assertEquals(now, decide(high = true, lastAgeH = 11.0).labelMs)
        assertEquals(now, decide(high = true, lastAgeH = null).labelMs)
    }

    @Test
    fun `below 70 percent the gap is 22 h`() {
        assertEquals(Reason.RECENT, decide(high = false, lastAgeH = 21.9).reason)
        assertEquals(Reason.DUE, decide(high = false, lastAgeH = 22.0).reason)
    }

    /** One fetch is one snapshot: the elapsed-hour backfill a few seconds later joins it, never "recent". */
    @Test
    fun `a call within 5 minutes of the last snapshot reuses its label, for any source`() {
        val last = now - 2 * 60_000L
        assertEquals(last, HourlySnapshotRun.decide(now, displayed = false, viewedRecently = true, batteryHigh = true, lastMs = last).labelMs)
        assertEquals(last, HourlySnapshotRun.decide(now, displayed = true, viewedRecently = true, batteryHigh = true, lastMs = last).labelMs)
        assertEquals(Reason.RECENT, decide(lastAgeH = 6.0 / 60 + 0.01).reason)
    }

    /**
     * No creep: with a 12-hour-ish fetch schedule the kept snapshots stay on that schedule. A ≥ 12 h rule
     * would drop the 12:00 fetch after one that ran at 00:05 and keep only one a day.
     */
    @Test
    fun `fetches every 12 h, a few minutes late, are all kept`() {
        var last: Long? = null
        val fetches = listOf(0L, 12 * hour - 5 * 60_000L, 24 * hour + 3 * 60_000L, 36 * hour - 4 * 60_000L).map { now + it }
        val kept = fetches.filter { t ->
            HourlySnapshotRun.decide(t, false, true, true, last).labelMs?.also { last = it } != null
        }
        assertEquals(fetches, kept)
    }

    private class FakeHost(
        val displayed: Boolean = false,
        val last: Long? = null,
        val throwOnLast: Boolean = false,
    ) : HourlySnapshotRun.Host {
        override val sourceId = "OPEN_METEO"
        var viewedReads = 0
        val logs = mutableListOf<String>()
        override suspend fun isDisplayed() = displayed
        override suspend fun viewedRecently(): Boolean { viewedReads++; return true }
        override suspend fun batteryHigh() = true
        override suspend fun lastSnapshotMs() = if (throwOnLast) throw IllegalStateException("db") else last
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level $message" }
    }

    @Test
    fun `run logs one line, skips the view read for the displayed source, and fails open`() = runBlocking {
        val shown = FakeHost(displayed = true)
        assertEquals(now, HourlySnapshotRun.run(shown, now).labelMs)
        assertEquals(0, shown.viewedReads)
        assertEquals(listOf("HOURLY_SNAPSHOT/VERBOSE source=OPEN_METEO decision=save reason=displayed last=none"), shown.logs)

        val recent = FakeHost(last = now - 3 * hour)
        assertNull(HourlySnapshotRun.run(recent, now).labelMs)
        assertEquals(listOf("HOURLY_SNAPSHOT/INFO source=OPEN_METEO decision=skip reason=recent lastAgeMin=180 gapH=11"), recent.logs)

        val broken = FakeHost(throwOnLast = true)
        assertEquals(now, HourlySnapshotRun.run(broken, now).labelMs)
        assertEquals("HOURLY_SNAPSHOT/WARN", broken.logs.single().substringBefore(' '))
    }
}

package com.weatherwidget.shared.history

import com.weatherwidget.shared.history.HourlySnapshotRun.Reason
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** User, 2026-11-01: plans/261101-every-fetch-saves-an-hourly-snapshot.md. */
@Category(ShortDuration::class)
class HourlySnapshotRunTest {
    private val hour = 3_600_000L
    private val now = 1_792_900_000_000L

    @Test
    fun `every fetch saves under its own time, however recent the last snapshot`() {
        assertEquals(now, HourlySnapshotRun.decide(now, lastMs = null).labelMs)
        assertEquals(now, HourlySnapshotRun.decide(now, lastMs = now - hour / 2).labelMs)
        assertEquals(Reason.FETCH, HourlySnapshotRun.decide(now, lastMs = now - HourlySnapshotRun.SAME_FETCH_MS).reason)
    }

    /** One fetch is one snapshot: the elapsed-hour backfill a few seconds later joins it. */
    @Test
    fun `a call within 5 minutes of the last snapshot reuses its label`() {
        val last = now - 2 * 60_000L
        assertEquals(last, HourlySnapshotRun.decide(now, last).labelMs)
        assertEquals(Reason.SAME_FETCH, HourlySnapshotRun.decide(now, last).reason)
    }

    private class FakeHost(val last: Long? = null, val throwOnLast: Boolean = false) : HourlySnapshotRun.Host {
        override val sourceId = "OPEN_METEO"
        val logs = mutableListOf<String>()
        override suspend fun lastSnapshotMs() = if (throwOnLast) throw IllegalStateException("db") else last
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level $message" }
    }

    @Test
    fun `run logs one verbose line and fails open`() = runBlocking {
        val first = FakeHost()
        assertEquals(now, HourlySnapshotRun.run(first, now).labelMs)
        assertEquals(listOf("HOURLY_SNAPSHOT/VERBOSE source=OPEN_METEO decision=save reason=fetch last=none"), first.logs)

        val recent = FakeHost(last = now - 3 * hour)
        assertEquals(now, HourlySnapshotRun.run(recent, now).labelMs)
        assertEquals(listOf("HOURLY_SNAPSHOT/VERBOSE source=OPEN_METEO decision=save reason=fetch lastAgeMin=180"), recent.logs)

        val broken = FakeHost(throwOnLast = true)
        assertEquals(now, HourlySnapshotRun.run(broken, now).labelMs)
        assertEquals("HOURLY_SNAPSHOT/WARN", broken.logs.single().substringBefore(' '))
    }
}

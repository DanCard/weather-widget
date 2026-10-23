package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.RefreshNotice.Kind
import com.weatherwidget.shared.util.RefreshNotice.NotQueued
import com.weatherwidget.shared.util.RefreshNotice.Queued
import com.weatherwidget.shared.util.RefreshNotice.Reason
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class RefreshNoticeTest {

    @Test
    fun `duration is four seconds`() {
        assertEquals(4_000L, RefreshNotice.DURATION_MS)
    }

    @Test
    fun `gate shows the first notice and one per four seconds`() {
        assertTrue(RefreshNotice.shouldShow(lastShownMs = null, nowMs = 10_000L))
        assertFalse(RefreshNotice.shouldShow(lastShownMs = 10_000L, nowMs = 13_999L))
        assertTrue(RefreshNotice.shouldShow(lastShownMs = 10_000L, nowMs = 14_000L))
    }

    @Test
    fun `gate shows when the clock moved backwards`() {
        assertTrue(RefreshNotice.shouldShow(lastShownMs = 10_000L, nowMs = 5_000L))
    }

    @Test
    fun `merge adds forecast and observations up to both`() {
        assertEquals(Queued(Kind.BOTH), RefreshNotice.merge(Queued(Kind.FORECAST), Queued(Kind.OBSERVATIONS)))
        assertEquals(Queued(Kind.FORECAST), RefreshNotice.merge(Queued(Kind.FORECAST), Queued(Kind.FORECAST)))
        assertEquals(Queued(Kind.BOTH), RefreshNotice.merge(Queued(Kind.BOTH), Queued(Kind.FORECAST)))
    }

    @Test
    fun `merge prefers a queued refresh over a reason for none, either order`() {
        val upToDate = NotQueued(Reason.UP_TO_DATE)
        assertEquals(Queued(Kind.FORECAST), RefreshNotice.merge(upToDate, Queued(Kind.FORECAST)))
        assertEquals(Queued(Kind.FORECAST), RefreshNotice.merge(Queued(Kind.FORECAST), upToDate))
    }

    @Test
    fun `merge of two reasons keeps the more specific`() {
        val quota = NotQueued(Reason.QUOTA, "Google", untilMs = 1L)
        assertEquals(quota, RefreshNotice.merge(NotQueued(Reason.UP_TO_DATE), quota))
        assertEquals(quota, RefreshNotice.merge(quota, NotQueued(Reason.ALREADY_QUEUED)))
        assertEquals(
            NotQueued(Reason.ALREADY_QUEUED),
            RefreshNotice.merge(NotQueued(Reason.UP_TO_DATE), NotQueued(Reason.ALREADY_QUEUED)),
        )
    }

    @Test
    fun `merge with null returns the other`() {
        assertNull(RefreshNotice.merge(null, null))
        assertEquals(Queued(Kind.FORECAST), RefreshNotice.merge(null, Queued(Kind.FORECAST)))
        assertEquals(Queued(Kind.FORECAST), RefreshNotice.merge(Queued(Kind.FORECAST), null))
    }

    @Test
    fun `only up to date counts as up to date`() {
        assertTrue(RefreshNotice.isUpToDate(NotQueued(Reason.UP_TO_DATE, "NWS", ageMs = 1L)))
        assertFalse(RefreshNotice.isUpToDate(NotQueued(Reason.QUOTA, "Google", untilMs = 1L)))
        assertFalse(RefreshNotice.isUpToDate(NotQueued(Reason.ALREADY_QUEUED)))
        assertFalse(RefreshNotice.isUpToDate(Queued(Kind.FORECAST)))
    }

    @Test
    fun `queued text names the kind`() {
        assertEquals("Forecast refresh queued", text(Queued(Kind.FORECAST)))
        assertEquals("Observations refresh queued", text(Queued(Kind.OBSERVATIONS)))
        assertEquals("Forecast and observations refresh queued", text(Queued(Kind.BOTH)))
    }

    @Test
    fun `up to date names the source, subject and age`() {
        assertEquals(
            "Google forecast up to date · 2h 10m ago",
            text(NotQueued(Reason.UP_TO_DATE, "Google", ageMs = 130 * 60_000L)),
        )
        assertEquals(
            "NWS observations up to date · 6m ago",
            text(NotQueued(Reason.UP_TO_DATE, "NWS", subject = Kind.OBSERVATIONS, ageMs = 6 * 60_000L)),
        )
        assertEquals("Forecast up to date", text(NotQueued(Reason.UP_TO_DATE)))
    }

    @Test
    fun `an age under a minute reads one minute, not just now`() {
        assertEquals("1m", RefreshNotice.formatAge(10_000L))
        assertEquals("1m", RefreshNotice.formatAge(-5_000L))
    }

    @Test
    fun `quota names the source and when the block ends`() {
        assertEquals(
            "Google limit reached · next refresh after T=42",
            text(NotQueued(Reason.QUOTA, "Google", untilMs = 42L)),
        )
        assertEquals("Google limit reached", text(NotQueued(Reason.QUOTA, "Google")))
    }

    @Test
    fun `in flight, already queued and no location`() {
        assertEquals("Refresh already in progress", text(NotQueued(Reason.IN_PROGRESS)))
        assertEquals("Refresh already queued", text(NotQueued(Reason.ALREADY_QUEUED)))
        assertEquals("No location to refresh", text(NotQueued(Reason.NO_LOCATION)))
    }

    @Test
    fun `log detail carries kind or reason with its facts`() {
        assertEquals("queued=BOTH", RefreshNotice.logDetail(Queued(Kind.BOTH)))
        assertEquals(
            "none=UP_TO_DATE source=Google subject=FORECAST ageMin=130",
            RefreshNotice.logDetail(NotQueued(Reason.UP_TO_DATE, "Google", ageMs = 130 * 60_000L)),
        )
        assertEquals(
            "none=QUOTA source=Google until=42",
            RefreshNotice.logDetail(NotQueued(Reason.QUOTA, "Google", untilMs = 42L)),
        )
    }

    private fun text(notice: RefreshNotice) = RefreshNotice.text(notice) { "T=$it" }

    @Test
    fun `the gate admits one notice per four seconds and an ending restarts it`() {
        var now = 1_000L
        val gate = RefreshNotice.Gate { now }
        org.junit.Assert.assertTrue(gate.admit())
        now += RefreshNotice.DURATION_MS - 1
        org.junit.Assert.assertFalse(gate.admit())
        gate.restart()
        now += RefreshNotice.DURATION_MS - 1
        org.junit.Assert.assertFalse(gate.admit())
        now += 1
        org.junit.Assert.assertTrue(gate.admit())
    }

    @Test
    fun `log lines`() {
        org.junit.Assert.assertEquals(
            "origin=popup queued=FORECAST shown",
            RefreshNotice.line("popup", RefreshNotice.Queued(RefreshNotice.Kind.FORECAST), "shown"),
        )
        org.junit.Assert.assertEquals(
            "origin=history done=Refreshed shown",
            RefreshNotice.outcomeLine("history", RefreshOutcome.Refreshed),
        )
        org.junit.Assert.assertEquals(
            "ok=false source=NWS ms=12",
            RefreshOutcome.doneLine(false, "source=NWS", 12, RefreshOutcome.Failed),
        )
        org.junit.Assert.assertEquals("WARN", RefreshOutcome.doneLevel(false))
    }
}

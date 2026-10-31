package com.weatherwidget.widget

import com.weatherwidget.data.model.CurrentStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentStatusPolicyTest {

    private val nowMs = 1_000_000_000L

    private fun sampleStatus(updatedAt: Long, temp: Float? = 64.5f, delta: Float? = -0.5f, applied: Float? = 1.2f) = CurrentStatus(
        locationLat = 37.417,
        locationLon = -122.089,
        source = "SILURIAN",
        displayTempF = temp,
        appliedDeltaF = applied,
        deltaFromYesterdayF = delta,
        observedAtMs = updatedAt - 30_000L,
        condition = "Sunny",
        updatedAt = updatedAt,
    )

    private fun sampleSnapshot(temp: Float? = 63.2f, delta: Float? = -0.8f, applied: Float? = 0.5f) = ForecastSnapshot(
        sourceId = "NWS",
        raw = RawFetch(),
        resolved = ResolvedView(
            currentTemp = temp,
            deltaFromYesterday = delta,
            appliedDelta = applied,
        ),
    )

    @Test
    fun `isFresh returns true for status within max age`() {
        val status = sampleStatus(updatedAt = nowMs - 60_000L) // 1 min old
        assertTrue(CurrentStatusPolicy.isFresh(status, nowMs))
    }

    @Test
    fun `isFresh returns true at exact boundary`() {
        val status = sampleStatus(updatedAt = nowMs - CurrentStatusPolicy.MAX_AGE_MS)
        assertTrue(CurrentStatusPolicy.isFresh(status, nowMs))
    }

    @Test
    fun `isFresh returns false for status older than max age`() {
        val status = sampleStatus(updatedAt = nowMs - CurrentStatusPolicy.MAX_AGE_MS - 1L)
        assertFalse(CurrentStatusPolicy.isFresh(status, nowMs))
    }

    @Test
    fun `isFresh returns false for null status`() {
        assertFalse(CurrentStatusPolicy.isFresh(null, nowMs))
    }

    @Test
    fun `isFresh tolerates slight future clock skew but rejects large future timestamps`() {
        val slightFuture = sampleStatus(updatedAt = nowMs + 5_000L) // +5s
        assertTrue(CurrentStatusPolicy.isFresh(slightFuture, nowMs))

        val largeFuture = sampleStatus(updatedAt = nowMs + 30_000L) // +30s
        assertFalse(CurrentStatusPolicy.isFresh(largeFuture, nowMs))
    }

    @Test
    fun `resolve prefers fresh status over snapshot`() {
        val status = sampleStatus(updatedAt = nowMs - 30_000L, temp = 65.0f, delta = 1.0f, applied = 0.5f)
        val snapshot = sampleSnapshot(temp = 60.0f, delta = -2.0f, applied = -1.0f)

        val result = CurrentStatusPolicy.resolve(status, snapshot, nowMs)
        assertEquals(65.0f, result.currentTemp)
        assertEquals(1.0f, result.deltaFromYesterday)
        assertEquals(0.5f, result.appliedDelta)
        assertTrue(result.isFromPublishedStatus)
    }

    @Test
    fun `resolve falls back to snapshot when status is stale`() {
        val staleStatus = sampleStatus(updatedAt = nowMs - 7_200_000L, temp = 53.0f, delta = -4.3f) // 2h stale
        val snapshot = sampleSnapshot(temp = 63.2f, delta = -0.5f, applied = 2.3f)

        val result = CurrentStatusPolicy.resolve(staleStatus, snapshot, nowMs)
        assertEquals(63.2f, result.currentTemp)
        assertEquals(-0.5f, result.deltaFromYesterday)
        assertEquals(2.3f, result.appliedDelta)
        assertFalse(result.isFromPublishedStatus)
    }

    @Test
    fun `resolve falls back to snapshot when status is null`() {
        val snapshot = sampleSnapshot(temp = 63.2f, delta = -0.5f, applied = 2.3f)

        val result = CurrentStatusPolicy.resolve(null, snapshot, nowMs)
        assertEquals(63.2f, result.currentTemp)
        assertEquals(-0.5f, result.deltaFromYesterday)
        assertEquals(2.3f, result.appliedDelta)
        assertFalse(result.isFromPublishedStatus)
    }

    @Test
    fun `resolve returns nulls when both status and snapshot are null`() {
        val result = CurrentStatusPolicy.resolve(null, null, nowMs)
        assertNull(result.currentTemp)
        assertNull(result.deltaFromYesterday)
        assertNull(result.appliedDelta)
        assertFalse(result.isFromPublishedStatus)
    }
}

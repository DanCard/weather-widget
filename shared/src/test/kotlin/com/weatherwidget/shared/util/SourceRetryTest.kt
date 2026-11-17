package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** plans/261112-refresh-failure-reason-and-retry.md */
@Category(ShortDuration::class)
class SourceRetryTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private class FakeHost(
        override var isCharging: Boolean = false,
        override var isScreenOn: Boolean = true,
    ) : SourceRetry.Host {
        val armed = mutableMapOf<String, SourceRetry.Pending>()
        val logs = mutableListOf<String>()

        override fun pending(sourceId: String) = armed[sourceId]

        override suspend fun arm(sourceId: String, retry: SourceRetry.Pending) {
            armed[sourceId] = retry
        }

        override suspend fun log(tag: String, message: String, level: String) {
            println("$tag $message")
            logs += message
        }
    }

    @Test
    fun `transient codes are retried, the rest are not`() {
        listOf("TIMEOUT", "DNS_ERROR", "CONN_REFUSED", "SOCKET_ERROR", "SSL_ERROR", "HTTP_500", "HTTP_503")
            .forEach { assertTrue(it, SourceRetry.isTransient(it)) }
        listOf(null, "HTTP_404", "HTTP_401", "HTTP_429", "QUOTA_DAILY", "NO_COVERAGE", "DATA_RESTRICTED", "ACCESS_ERROR")
            .forEach { assertFalse("$it", SourceRetry.isTransient(it)) }
    }

    @Test
    fun `a source that timed out is retried in 1 min`() = runBlocking {
        val host = FakeHost()
        val at = SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", now)
        assertEquals(now + minute, at)
        assertEquals(SourceRetry.Pending(now + minute, minute, attempt = 1), host.armed["OPEN_METEO"])
        assertTrue(host.logs.single().startsWith("scheduled source=OPEN_METEO code=TIMEOUT"))
    }

    @Test
    fun `consecutive failures wait 1, 2, 5, 10 min`() = runBlocking {
        val host = FakeHost()
        val delays = (0 until 4).map { i ->
            val t = now + i * 15 * minute
            SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", t)!! - t
        }
        assertEquals(listOf(minute, 2 * minute, 5 * minute, 10 * minute), delays)
    }

    @Test
    fun `the fifth failure in a row gets no retry - capped`() = runBlocking {
        val host = FakeHost()
        repeat(SourceRetry.MAX_RETRIES) { i -> SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", now + i * 10 * minute) }
        assertEquals(4, host.armed["OPEN_METEO"]?.attempt)
        assertNull(SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", now + 40 * minute))
        assertTrue(host.logs.last().contains("reason=cap"))
    }

    @Test
    fun `a failure long after the last retry starts a fresh chain`() = runBlocking {
        val host = FakeHost()
        repeat(SourceRetry.MAX_RETRIES) { i -> SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", now + i * 10 * minute) }
        val later = host.armed.getValue("OPEN_METEO").atMs + SourceRetry.STREAK_GAP_MS
        assertEquals(later + minute, SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", later))
        assertEquals(1, host.armed["OPEN_METEO"]?.attempt)
    }

    @Test
    fun `on battery with the screen off no retry is armed`() = runBlocking {
        val host = FakeHost(isCharging = false, isScreenOn = false)
        assertNull(SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", now))
        assertTrue(host.logs.single().contains("reason=battery_screen_off"))
        // A charger with the screen off still retries.
        host.isCharging = true
        assertEquals(now + minute, SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "test", now))
    }

    @Test
    fun `a due retry fires only on a charger or with the screen on`() = runBlocking {
        val logs = mutableListOf<String>()
        val log: suspend (String, String, String) -> Unit = { _, m, _ -> logs += m }
        assertFalse(SourceRetry.shouldFire(isCharging = false, isScreenOn = false, sourceId = "NWS", log = log))
        assertTrue(SourceRetry.shouldFire(isCharging = true, isScreenOn = false, sourceId = "NWS", log = log))
        assertTrue(SourceRetry.shouldFire(isCharging = false, isScreenOn = true, sourceId = "NWS", log = log))
        assertTrue(logs.first().startsWith("fire_skip source=NWS"))
    }

    /** Pixel 2026-11-14 17:18 and 20:19: Open-Meteo timed out while another source was shown, skipped as not_displayed. */
    @Test
    fun `a source not on screen is retried too`() = runBlocking {
        val host = FakeHost()
        assertEquals(now + minute, SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "forecast_loop", now))
        assertEquals(SourceRetry.Pending(now + minute, minute, attempt = 1), host.armed["OPEN_METEO"])
    }

    @Test
    fun `a 404 gets no retry`() = runBlocking {
        val host = FakeHost()
        assertNull(SourceRetry.onFailure(host, "OPEN_METEO", "HTTP_404", "test", now))
        assertTrue(host.logs.single().contains("reason=not_transient"))
    }

    @Test
    fun `only a retry still ahead is shown`() {
        val pending = SourceRetry.Pending(now + minute, 15 * minute)
        assertEquals(now + minute, SourceRetry.retryAtMs(pending, now))
        assertNull(SourceRetry.retryAtMs(pending, now + minute))
        assertNull(SourceRetry.retryAtMs(null, now))
    }

    @Test
    fun `a non-displayed source's chain runs 1, 2, 5, 10 min and caps`() = runBlocking {
        val host = FakeHost()
        var t = now
        val delays = mutableListOf<Long>()
        SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", "request:settings", t)!!.also { delays += it - t }
        repeat(3) {
            t = host.armed.getValue("OPEN_METEO").atMs // each retry fails as it comes due
            SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", SourceRetry.REASON, t)!!.also { delays += it - t }
        }
        assertEquals(listOf(minute, 2 * minute, 5 * minute, 10 * minute), delays)

        t = host.armed.getValue("OPEN_METEO").atMs
        assertNull(SourceRetry.onFailure(host, "OPEN_METEO", "TIMEOUT", SourceRetry.REASON, t))
        assertTrue(host.logs.last().contains("reason=cap"))
    }

    @Test
    fun `a settings refresh failure still gets no retry on battery with the screen off, or for a non-transient code`() = runBlocking {
        val asleep = FakeHost(isCharging = false, isScreenOn = false)
        assertNull(SourceRetry.onFailure(asleep, "OPEN_METEO", "TIMEOUT", "request:settings", now))
        assertTrue(asleep.logs.single().contains("reason=battery_screen_off"))

        val quota = FakeHost()
        assertNull(SourceRetry.onFailure(quota, "OPEN_METEO", "QUOTA_DAILY", "request:settings", now))
        assertTrue(quota.logs.single().contains("reason=not_transient"))
    }

    /** plans/261117-source-retry-due-grace.md: the cadence's 2 min grace made 1- and 2-min retries due at once. */
    @Test
    fun `due grace stays well below the shortest retry delay`() {
        println("DUE_GRACE_MS=${SourceRetry.DUE_GRACE_MS} shortestDelay=${SourceRetry.DELAYS_MS.min()}")
        assertTrue(SourceRetry.DUE_GRACE_MS < SourceRetry.DELAYS_MS.min() / 4)
    }

    @Test
    fun `loop sleeps toward a near retry instead of the one-minute cadence floor`() {
        val cadence = 3 * 60 * minute
        assertEquals("retry 20 s ahead", 20_000L, SourceRetry.loopSleepMs(20_000L, cadence))
        assertEquals("retry after the cadence wake", cadence, SourceRetry.loopSleepMs(5 * 60 * minute, cadence))
        assertEquals("no retry", cadence, SourceRetry.loopSleepMs(null, cadence))
        assertEquals("floor against a spin", SourceRetry.MIN_SLEEP_MS, SourceRetry.loopSleepMs(10L, cadence))
    }
}

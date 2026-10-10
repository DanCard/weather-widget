package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The CLOUD-view repair probe consults [hourlyBackfillCoolingDown] BEFORE loading its 72h
 * observation window, so the pre-check must read the same keys the enqueue path uses — a wrong key
 * here would either never probe or probe on every paint. Since 2026-10-09 the cooldown runs from a
 * COMPLETED backfill (site key), not from the request (plans/261009-observation-backfill-cooldown-starts-when-it-runs.md).
 */
@Category(ShortDuration::class)
class HourlyObservationBackfillCooldownTest {

    private val now = 1_791_600_000_000L

    private fun stateManager(requestedAgoMin: Long?, completedAgoMin: Long?) = mockk<WidgetStateManager>().also {
        every { it.fetchStateNowMs() } returns now
        every { it.missingActualsRequestedAtMs(any(), any()) } returns
            (requestedAgoMin?.let { m -> now - m * 60_000L } ?: 0L)
        every { it.observationBackfillAttemptedAtMs(any()) } returns
            (completedAgoMin?.let { m -> now - m * 60_000L } ?: 0L)
    }

    @Test
    fun `a backfill completed inside the window is cooling down, read from the site key`() = runBlocking {
        val stateManager = stateManager(requestedAgoMin = 12, completedAgoMin = 10)

        assertTrue(
            hourlyBackfillCoolingDown(
                stateManager, appWidgetId = 7, displaySource = WeatherSource.NWS,
                lat = 37.4168205, lon = -122.0890350,
            ),
        )
        verify { stateManager.observationBackfillAttemptedAtMs("37.417_-122.089") }
    }

    /** Emulator 2026-10-09: requested, dropped by a test run, never completed — must not block. */
    @Test
    fun `a recent request with no completion is not cooling down`() = runBlocking {
        assertFalse(
            hourlyBackfillCoolingDown(
                stateManager(requestedAgoMin = 5, completedAgoMin = null), appWidgetId = 7,
                displaySource = WeatherSource.NWS, lat = 37.4168205, lon = -122.0890350,
            ),
        )
    }

    @Test
    fun `cooldown elapsed means not cooling down`() = runBlocking {
        assertFalse(
            hourlyBackfillCoolingDown(
                stateManager(requestedAgoMin = 40, completedAgoMin = 35), appWidgetId = 7,
                displaySource = WeatherSource.NWS, lat = 37.4168205, lon = -122.0890350,
            ),
        )
    }

    @Test
    fun `source key is per-source`() {
        assertEquals(
            "NWS_HOURLY_HISTORY_37.417_-122.089",
            hourlyBackfillSourceKey(WeatherSource.NWS, 37.4168205, -122.0890350),
        )
        assertEquals(
            "OPEN_METEO_HOURLY_HISTORY_37.417_-122.089",
            hourlyBackfillSourceKey(WeatherSource.OPEN_METEO, 37.4168205, -122.0890350),
        )
    }

    /**
     * A move is precisely when the backfill is most needed, and it used to be precisely when the
     * cooldown blocked it: both sites hashed to `NWS_HOURLY_HISTORY`, so a heal at the old site
     * suppressed the new site's for 30 minutes (Samsung 2026-08-22).
     */
    @Test
    fun `a different site gets its own cooldown bucket`() {
        val home = hourlyBackfillSourceKey(WeatherSource.NWS, 37.4168205, -122.0890350)
        val excursion = hourlyBackfillSourceKey(WeatherSource.NWS, 37.4242298, -122.0883022)
        assertNotEquals("a promoted site must be able to heal on its own schedule", home, excursion)
    }

    /**
     * The other direction matters just as much: quantizing to the shared write grid keeps GPS
     * jitter around one spot in a single bucket, so the cooldown still bounds retries and a
     * wobbling fix cannot hammer the API.
     */
    @Test
    fun `gps jitter around one spot shares a cooldown bucket`() {
        val a = hourlyBackfillSourceKey(WeatherSource.NWS, 37.4168205, -122.0890350)
        val b = hourlyBackfillSourceKey(WeatherSource.NWS, 37.4168338, -122.0890052)
        assertEquals("jitter must not reset the cooldown", a, b)
    }
}

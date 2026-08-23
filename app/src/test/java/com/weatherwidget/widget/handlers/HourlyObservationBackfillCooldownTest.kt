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
 * observation window, so the pre-check must be a pure read of the same shared cooldown key the
 * enqueue path uses — a wrong key here would either never probe or probe on every paint.
 */
@Category(ShortDuration::class)
class HourlyObservationBackfillCooldownTest {

    @Test
    fun `cooling down mirrors the shared cooldown read`() = runBlocking {
        val stateManager = mockk<WidgetStateManager>()
        every {
            stateManager.shouldRefreshMissingActuals(any(), any(), any())
        } returns false

        assertTrue(
            hourlyBackfillCoolingDown(
                stateManager, appWidgetId = 7, displaySource = WeatherSource.NWS,
                lat = 37.4168205, lon = -122.0890350,
            ),
        )
        verify(exactly = 1) {
            stateManager.shouldRefreshMissingActuals(7, "NWS_HOURLY_HISTORY_37.417_-122.089", 30 * 60 * 1000L)
        }
    }

    @Test
    fun `cooldown elapsed means not cooling down`() = runBlocking {
        val stateManager = mockk<WidgetStateManager>()
        every {
            stateManager.shouldRefreshMissingActuals(any(), any(), any())
        } returns true

        assertFalse(
            hourlyBackfillCoolingDown(
                stateManager, appWidgetId = 7, displaySource = WeatherSource.NWS,
                lat = 37.4168205, lon = -122.0890350,
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

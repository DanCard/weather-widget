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

        assertTrue(hourlyBackfillCoolingDown(stateManager, appWidgetId = 7, displaySource = WeatherSource.NWS))
        verify(exactly = 1) {
            stateManager.shouldRefreshMissingActuals(7, "NWS_HOURLY_HISTORY", 30 * 60 * 1000L)
        }
    }

    @Test
    fun `cooldown elapsed means not cooling down`() = runBlocking {
        val stateManager = mockk<WidgetStateManager>()
        every {
            stateManager.shouldRefreshMissingActuals(any(), any(), any())
        } returns true

        assertFalse(hourlyBackfillCoolingDown(stateManager, appWidgetId = 7, displaySource = WeatherSource.NWS))
    }

    @Test
    fun `source key is per-source`() {
        assertEquals("NWS_HOURLY_HISTORY", hourlyBackfillSourceKey(WeatherSource.NWS))
        assertEquals("OPEN_METEO_HOURLY_HISTORY", hourlyBackfillSourceKey(WeatherSource.OPEN_METEO))
    }
}

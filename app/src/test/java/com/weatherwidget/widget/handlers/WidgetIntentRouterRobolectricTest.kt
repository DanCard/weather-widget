package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class WidgetIntentRouterRobolectricTest {
    private val lat = 37.42
    private val lon = -122.08

    private fun epochMs(time: LocalDateTime): Long =
        time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun hourly(
        time: LocalDateTime,
        temperature: Float,
        source: WeatherSource = WeatherSource.NWS,
    ) = HourlyForecastEntity(
        dateTime = epochMs(time),
        locationLat = lat,
        locationLon = lon,
        temperature = temperature,
        condition = "Clear",
        source = source.id,
        fetchedAt = epochMs(time.minusMinutes(5)),
    )

    private fun observation(
        time: LocalDateTime,
        temperature: Float,
        stationId: String = "KPAO",
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = epochMs(time),
        temperature = temperature,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 1f,
        stationType = "station",
        api = WeatherSource.NWS.id,
        fetchedAt = epochMs(time),
    )

    @Test
    fun `router action constants match provider action constants`() {
        assertEquals(WeatherWidgetProvider.ACTION_NAV_LEFT, WidgetIntentRouter.ACTION_NAV_LEFT)
        assertEquals(WeatherWidgetProvider.ACTION_NAV_RIGHT, WidgetIntentRouter.ACTION_NAV_RIGHT)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_API, WidgetIntentRouter.ACTION_TOGGLE_API)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_VIEW, WidgetIntentRouter.ACTION_TOGGLE_VIEW)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_PRECIP, WidgetIntentRouter.ACTION_TOGGLE_PRECIP)
        assertEquals(WeatherWidgetProvider.ACTION_CYCLE_ZOOM, WidgetIntentRouter.ACTION_CYCLE_ZOOM)
        assertEquals(WeatherWidgetProvider.ACTION_SET_VIEW, WidgetIntentRouter.ACTION_SET_VIEW)
    }

    @Test
    fun `router set-view extra key matches provider contract`() {
        assertEquals(WeatherWidgetProvider.EXTRA_TARGET_VIEW, WidgetIntentRouter.EXTRA_TARGET_VIEW)
    }

    @Test
    fun `buildRefreshScheduleDecision uses replace for manual refresh`() {
        val now = System.currentTimeMillis()

        val decision = WidgetIntentRouter.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "manual_refresh",
            lastEnqueueForReasonMs = now - 1_000L,
        )

        assertTrue(decision.shouldEnqueue)
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, decision.policy)
        assertEquals("manual_refresh", decision.reason)
        assertNull(decision.skipReason)
    }

    @Test
    fun `buildRefreshScheduleDecision uses keep for stale toggle refresh`() {
        val now = System.currentTimeMillis()

        val decision = WidgetIntentRouter.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "stale_on_toggle_view",
            lastEnqueueForReasonMs = null,
        )

        assertTrue(decision.shouldEnqueue)
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, decision.policy)
        assertEquals("stale_on_toggle_view", decision.reason)
        assertNull(decision.skipReason)
    }

    @Test
    fun `buildRefreshScheduleDecision debounces repeated stale refreshes`() {
        val now = System.currentTimeMillis()

        val decision = WidgetIntentRouter.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "stale_on_toggle_view",
            lastEnqueueForReasonMs = now - 5_000L,
        )

        assertFalse(decision.shouldEnqueue)
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, decision.policy)
        assertEquals("debounced", decision.skipReason)
    }

    @Test
    fun `buildGraphQueryWindow splits into center and now windows when far apart`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 12)
        val centerTime = now.plusDays(7).withHour(8).withMinute(20)

        val window = WidgetIntentRouter.buildGraphQueryWindow(centerTime, ZoomLevel.WIDE, now)

        // centerTime is 08:20 (March 4). WIDE is -12h, +12h.
        // 08:20 - 12h = 20:20 (March 3). Truncated = 20:00.
        // 08:20 + 12h = 20:20 (March 4). Truncated = 20:00.
        assertEquals(LocalDateTime.of(2026, 3, 3, 20, 0), window.centerStart)
        assertEquals(LocalDateTime.of(2026, 3, 4, 20, 0), window.centerEnd)
        assertEquals(LocalDateTime.of(2026, 2, 25, 10, 0), window.nowStart)
        assertEquals(LocalDateTime.of(2026, 2, 25, 11, 0), window.nowEnd)
    }

    @Test
    fun `buildGraphQueryWindow omits now window when it overlaps center window`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 12)
        val centerTime = now.plusHours(1).withMinute(10)

        val window = WidgetIntentRouter.buildGraphQueryWindow(centerTime, ZoomLevel.WIDE, now)

        // centerTime is 11:12. WIDE is -12h, +12h.
        // 11:12 - 12h = 23:12 (prev day). Truncated = 23:00.
        // 11:12 + 12h = 23:12. Truncated = 23:00.
        assertEquals(LocalDateTime.of(2026, 2, 24, 23, 0), window.centerStart)
        assertEquals(LocalDateTime.of(2026, 2, 25, 23, 0), window.centerEnd)
        assertNull(window.nowStart)
        assertNull(window.nowEnd)
    }

    @Test
    fun `buildCurrentTempResolutionWindow is independent of graph zoom and center`() {
        val now = LocalDateTime.of(2026, 4, 4, 10, 37)

        val window = WidgetIntentRouter.buildCurrentTempResolutionWindow(now)

        assertEquals(LocalDateTime.of(2026, 4, 3, 23, 0), window.start)
        assertEquals(LocalDateTime.of(2026, 4, 4, 13, 0), window.end)
    }

    @Test
    fun `resolveGraphStyleCurrentTempFromInputs stays stable across zoom scoped forecast windows when context is fixed`() {
        val now = LocalDateTime.of(2026, 4, 4, 10, 20)
        val observedAt = now.minusMinutes(20)
        val observations = listOf(
            observation(observedAt.minusMinutes(30), 60f, stationId = "STATION_A"),
            observation(observedAt, 62f, stationId = "STATION_A"),
        )
        val fullForecasts = listOf(
            hourly(now.minusHours(2), 58f),
            hourly(now.minusHours(1), 60f),
            hourly(now, 63f),
            hourly(now.plusHours(1), 67f),
            hourly(now.plusHours(2), 70f),
        )

        val wideResult = WidgetIntentRouter.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = fullForecasts,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            now = now,
        )
        val narrowResult = WidgetIntentRouter.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = fullForecasts,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            now = now,
        )

        assertNotNull(wideResult)
        assertNotNull(narrowResult)
        assertEquals(wideResult!!.temperature, narrowResult!!.temperature, 0.001f)
        assertEquals(wideResult.observedAt, narrowResult.observedAt)
    }

    @Test
    fun `resolveGraphStyleCurrentTempFromInputs changes when forecast context is truncated`() {
        val now = LocalDateTime.of(2026, 4, 4, 10, 20)
        val observations = listOf(
            observation(now.minusHours(2), 62f, stationId = "STATION_A"),
            observation(now.minusHours(1), 66f, stationId = "STATION_B"),
        )
        val fullForecasts = listOf(
            hourly(now.minusHours(2), 60f),
            hourly(now.minusHours(1), 61f),
            hourly(now, 66f),
        )
        val truncatedForecasts = listOf(
            hourly(now.minusHours(2), 60f),
        )

        val fullResult = WidgetIntentRouter.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = fullForecasts,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            now = now,
        )
        val truncatedResult = WidgetIntentRouter.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = truncatedForecasts,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            now = now,
        )

        assertNotNull(fullResult)
        assertNotNull(truncatedResult)
        assertTrue(fullResult!!.temperature < truncatedResult!!.temperature)
        assertEquals(epochMs(now.minusHours(1)), fullResult.observedAt)
    }
}

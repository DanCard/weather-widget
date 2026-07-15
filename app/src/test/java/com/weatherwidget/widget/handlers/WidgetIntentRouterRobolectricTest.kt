package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.ZoomLevel
import com.weatherwidget.widget.handlers.CurrentTempResolver
import com.weatherwidget.widget.handlers.GraphDataLoader
import com.weatherwidget.widget.handlers.RefreshScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import com.weatherwidget.data.model.DailyHistory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
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
    fun `action constants have expected string values`() {
        assertEquals(WidgetActions.ACTION_NAV_LEFT, "com.weatherwidget.ACTION_NAV_LEFT")
        assertEquals(WidgetActions.ACTION_NAV_RIGHT, "com.weatherwidget.ACTION_NAV_RIGHT")
        assertEquals(WidgetActions.ACTION_TOGGLE_API, "com.weatherwidget.ACTION_TOGGLE_API")
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, "com.weatherwidget.ACTION_TOGGLE_VIEW")
        assertEquals(WidgetActions.ACTION_TOGGLE_PRECIP, "com.weatherwidget.ACTION_TOGGLE_PRECIP")
        assertEquals(WidgetActions.ACTION_CYCLE_ZOOM, "com.weatherwidget.ACTION_CYCLE_ZOOM")
        assertEquals(WidgetActions.ACTION_SET_VIEW, "com.weatherwidget.ACTION_SET_VIEW")
    }

    @Test
    fun `extra constants have expected string values`() {
        assertEquals(WidgetActions.EXTRA_TARGET_VIEW, "com.weatherwidget.EXTRA_TARGET_VIEW")
    }

    @Test
    fun `buildRefreshScheduleDecision keeps running work for manual refresh`() {
        val now = System.currentTimeMillis()

        val decision = RefreshScheduler.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "manual_refresh",
            lastEnqueueForReasonMs = now - 1_000L,
        )

        assertTrue(decision.shouldEnqueue)
        // KEEP, not REPLACE: a manual refresh must never cancel a running worker (that segfaults ART
        // on debuggable builds); an in-flight sync already produces fresh data.
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, decision.policy)
        assertEquals("manual_refresh", decision.reason)
        assertNull(decision.skipReason)
    }

    @Test
    fun `buildRefreshScheduleDecision uses keep for stale toggle refresh`() {
        val now = System.currentTimeMillis()

        val decision = RefreshScheduler.buildRefreshScheduleDecision(
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

        val decision = RefreshScheduler.buildRefreshScheduleDecision(
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

        val window = GraphDataLoader.buildGraphQueryWindow(centerTime, ZoomLevel.WIDE, now)

        // centerTime is 08:20 (March 4) -> rounded to 08:00. The span is the BLEND CONTEXT
        // (HOURLY_LOOKBACK_HOURS=72 / HOURLY_LOOKAHEAD_HOURS=60), NOT WIDE's visible -12h/+12h:
        // the blend extrapolates stations through the forecast delta across its context, so querying
        // only the visible span starved it and dropped stations. See
        // GraphQueryWindowCoversBlendContextTest.
        // 08:00 - 72h = 08:00 (March 1). 08:00 + 60h = 20:00 (March 6).
        assertEquals(LocalDateTime.of(2026, 3, 1, 8, 0), window.centerStart)
        assertEquals(LocalDateTime.of(2026, 3, 6, 20, 0), window.centerEnd)
        // The split itself is what this test is about: `now` is 7 days before the centre, so even the
        // widened context does not reach it and the now-hour still needs its own query.
        assertEquals(LocalDateTime.of(2026, 2, 25, 10, 0), window.nowStart)
        assertEquals(LocalDateTime.of(2026, 2, 25, 11, 0), window.nowEnd)
    }

    @Test
    fun `buildGraphQueryWindow omits now window when it overlaps center window`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 12)
        val centerTime = now.plusHours(1).withMinute(10)

        val window = GraphDataLoader.buildGraphQueryWindow(centerTime, ZoomLevel.WIDE, now)

        // centerTime is 11:12 -> rounded to 11:00. Span is the blend context (72h back / 60h forward),
        // not WIDE's visible -12h/+12h.
        // 11:00 - 72h = 11:00 (Feb 22). 11:00 + 60h = 23:00 (Feb 27).
        assertEquals(LocalDateTime.of(2026, 2, 22, 11, 0), window.centerStart)
        assertEquals(LocalDateTime.of(2026, 2, 27, 23, 0), window.centerEnd)
        // The now-hour falls inside the centre window, so no separate query is needed. Widening the
        // context makes this the common case — a scrolled-back widget now usually still covers `now`.
        assertNull(window.nowStart)
        assertNull(window.nowEnd)
    }

    @Test
    fun `buildCurrentTempResolutionWindow is independent of graph zoom and center`() {
        val now = LocalDateTime.of(2026, 4, 4, 10, 37)

        val window = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)

        assertEquals(LocalDateTime.of(2026, 4, 3, 23, 0), window.start)
        assertEquals(LocalDateTime.of(2026, 4, 4, 14, 0), window.end)
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

        val wideResult = CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = fullForecasts,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            now = now,
        )
        val narrowResult = CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
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

        val fullResult = CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = fullForecasts,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            now = now,
        )
        val truncatedResult = CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
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

    @Test
    fun `getDailyActuals uses live today actuals instead of persisted today extremes`() = runTest {
        val db = TestDatabase.create()
        try {
            val today = LocalDate.now()
            val obsTime = today.atTime(16, 55)
            val obsTimeMs = epochMs(obsTime)

            db.observationDao().insertAll(
                listOf(
                    ObservationEntity(
                        stationId = "KNEAR",
                        stationName = "Near Station",
                        timestamp = obsTimeMs,
                        temperature = 82.56303f,
                        condition = "Clear",
                        locationLat = lat,
                        locationLon = lon,
                        distanceKm = 1f,
                        stationType = "station",
                        api = WeatherSource.NWS.id,
                        fetchedAt = obsTimeMs,
                    ),
                ),
            )
            db.hourlyForecastDao().insertAll(
                listOf(
                    HourlyForecastEntity(
                        dateTime = obsTimeMs,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = 80f,
                        condition = "Clear",
                        source = WeatherSource.NWS.id,
                        fetchedAt = obsTimeMs,
                    )
                )
            )
            db.dailyHistoryDao().insertAll(
                listOf(
                    DailyHistoryEntity(
                        date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                        source = WeatherSource.NWS.id,
                        locationLat = lat,
                        locationLon = lon,
                        highTemp = 83.40072f,
                        lowTemp = 62.6f,
                        condition = "Clear",
                        updatedAt = obsTimeMs,
                    ),
                ),
            )

            val result = WidgetIntentRouter.getDailyActuals(db, lat, lon)
            val actual = result[WeatherSource.NWS.id]?.get(today)

            assertNotNull("Expected live NWS actual for today", actual)
            assertEquals(
                "SET_VIEW daily actuals must ignore persisted daily_history for today",
                82.56303f,
                actual!!.highTemp,
                0.001f,
            )
        } finally {
            db.close()
        }
    }
}

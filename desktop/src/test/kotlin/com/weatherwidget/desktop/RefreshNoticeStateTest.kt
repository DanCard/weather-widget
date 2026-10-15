package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.RefreshNotice.Kind
import com.weatherwidget.shared.util.RefreshNotice.NotQueued
import com.weatherwidget.shared.util.RefreshNotice.Queued
import com.weatherwidget.shared.util.RefreshNotice.Reason
import com.weatherwidget.test.category.MediumDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The desktop's two-second refresh toast: [RefreshNoticeState] (gate, surfaces, expiry, log rows), the
 * source-cycle decision it shares with the daemon, and the pill in a window.
 * plans/261015-refresh-queued-toast-all-surfaces.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Category(MediumDuration::class)
class RefreshNoticeStateTest {
    @get:Rule
    val compose = createComposeRule()

    private var nowMs = 1_000_000L
    private val logged = mutableListOf<String>()
    private val state = RefreshNoticeState(log = { logged += it }, clock = { nowMs }, formatTime = { "T$it" })

    @Test
    fun `a notice shows on its own surface only and is logged`() {
        state.show(Queued(Kind.FORECAST), RefreshNoticeSurface.SETTINGS, "settings")

        assertEquals("Forecast refresh queued", state.textFor(RefreshNoticeSurface.SETTINGS))
        assertNull(state.textFor(RefreshNoticeSurface.POPUP))
        assertEquals(listOf("origin=settings queued=FORECAST shown"), logged)
    }

    @Test
    fun `a second notice within four seconds is suppressed and logged`() {
        state.show(Queued(Kind.OBSERVATIONS), RefreshNoticeSurface.OBSERVATIONS, "observations")
        nowMs += 500L
        state.show(NotQueued(Reason.IN_PROGRESS), RefreshNoticeSurface.OBSERVATIONS, "observations")

        assertEquals("Observations refresh queued", state.textFor(RefreshNoticeSurface.OBSERVATIONS))
        assertEquals("origin=observations none=IN_PROGRESS suppressed", logged.last())

        nowMs += RefreshNotice.DURATION_MS
        state.show(NotQueued(Reason.IN_PROGRESS), RefreshNoticeSurface.OBSERVATIONS, "observations")
        assertEquals("Refresh already in progress", state.textFor(RefreshNoticeSurface.OBSERVATIONS))
    }

    @Test
    fun `a notice clears at exactly four seconds`() = runTest {
        state.show(Queued(Kind.FORECAST), RefreshNoticeSurface.HISTORY, "history")
        launch { state.expire(state.current!!) }

        advanceTimeBy(RefreshNotice.DURATION_MS - 1)
        runCurrent()
        assertEquals("Forecast refresh queued", state.textFor(RefreshNoticeSurface.HISTORY))
        advanceTimeBy(1)
        runCurrent()
        assertNull(state.current)
    }

    @Test
    fun `an older expiry never clears a newer notice`() = runTest {
        state.show(Queued(Kind.FORECAST), RefreshNoticeSurface.HISTORY, "history")
        val first = state.current!!
        nowMs += RefreshNotice.DURATION_MS
        state.show(Queued(Kind.OBSERVATIONS), RefreshNoticeSurface.OBSERVATIONS, "observations")

        launch { state.expire(first) }
        advanceTimeBy(RefreshNotice.DURATION_MS + 1)
        runCurrent()

        assertEquals("Observations refresh queued", state.textFor(RefreshNoticeSurface.OBSERVATIONS))
    }

    @Test
    fun `source cycle - no fetch yet or older than four hours queues the forecast`() {
        val now = 100_000_000L
        assertEquals(Queued(Kind.FORECAST), sourceCycleNotice(false, null, null, "Google", now))
        assertEquals(
            Queued(Kind.FORECAST),
            sourceCycleNotice(true, now, now - 4 * 3_600_000L, "Google", now),
        )
    }

    @Test
    fun `source cycle - a fresh forecast with old observations queues observations`() {
        val now = 100_000_000L
        assertEquals(
            Queued(Kind.OBSERVATIONS),
            sourceCycleNotice(true, now - 3 * 3_600_000L, now - 3_600_000L, "Google", now),
        )
    }

    @Test
    fun `source cycle - everything fresh is up to date with the forecast's age`() {
        val now = 100_000_000L
        assertEquals(
            NotQueued(Reason.UP_TO_DATE, "Google", ageMs = 3_600_000L),
            sourceCycleNotice(true, now - 60_000L, now - 3_600_000L, "Google", now),
        )
    }

    @Test
    fun `source cycle agrees with the daemon's launch decision`() {
        val now = 100_000_000L
        val forecastAges = listOf(null, 0L, 3_599_999L, 4 * 3_600_000L - 1, 4 * 3_600_000L, 9 * 3_600_000L)
        val obsAges = listOf(null, 0L, 30 * 60_000L, 3 * 3_600_000L)
        for (cache in listOf(true, false)) for (fa in forecastAges) for (oa in obsAges) {
            val lastForecast = fa?.let { now - it }
            val lastObs = oa?.let { now - it }
            val daemon = determineLaunchRefreshAction(
                cache, lastObs, lastForecast, now,
                launchForecastStaleAfterMs(SOURCE_CYCLE_REASON) { null },
            )
            val expected = when (daemon) {
                LaunchRefreshAction.FULL_FORECAST -> Queued(Kind.FORECAST)
                LaunchRefreshAction.OBSERVATIONS -> Queued(Kind.OBSERVATIONS)
                LaunchRefreshAction.NONE -> NotQueued(Reason.UP_TO_DATE, "X", ageMs = fa)
            }
            assertEquals("cache=$cache fa=$fa oa=$oa", expected, sourceCycleNotice(cache, lastObs, lastForecast, "X", now))
        }
    }

    @Test
    fun `refresh origins and config writers map to their window`() {
        assertEquals(RefreshNoticeSurface.SETTINGS, refreshNoticeSurface("settings"))
        assertEquals(RefreshNoticeSurface.HISTORY, refreshNoticeSurface("history"))
        assertEquals(RefreshNoticeSurface.OBSERVATIONS, refreshNoticeSurface("observations"))
        assertEquals(RefreshNoticeSurface.POPUP, sourceCycleNoticeSurface("popup"))
        assertEquals(RefreshNoticeSurface.OBSERVATIONS, sourceCycleNoticeSurface("observations-window"))
        assertNull(sourceCycleNoticeSurface("settings"))
        assertNull(sourceCycleNoticeSurface("location-picker"))
    }

    @Test
    fun `the pill shows the window's notice and nothing for other windows`() {
        state.show(NotQueued(Reason.IN_PROGRESS), RefreshNoticeSurface.SETTINGS, "settings")
        compose.setContent {
            CompositionLocalProvider(LocalRefreshNotice provides state) {
                Box {
                    RefreshNoticePill(RefreshNoticeSurface.SETTINGS)
                    RefreshNoticePill(RefreshNoticeSurface.HISTORY)
                }
            }
        }

        compose.onNodeWithText("Refresh already in progress").assertIsDisplayed()
        compose.onNodeWithTag("refresh_notice_settings").assertIsDisplayed()
        compose.onNodeWithTag("refresh_notice_history").assertDoesNotExist()
    }
}

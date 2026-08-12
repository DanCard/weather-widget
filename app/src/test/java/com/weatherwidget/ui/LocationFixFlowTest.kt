package com.weatherwidget.ui

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Drives [LocationFixFlow] with virtual time. The scenario that motivated the class:
 * an active GPS fix that never resolves (emulator / indoors) must not leave the config
 * screen hanging — the flow has to fall back to the cached fix, then the default, so
 * the widget-add handshake always completes.
 */
@Category(ShortDuration::class)
class LocationFixFlowTest {

    private val flow = LocationFixFlow(activeFixTimeoutMs = 10_000, cachedFixTimeoutMs = 5_000)

    private val activeCoords = LocationFixFlow.Coordinates(37.0, -122.0)
    private val cachedCoords = LocationFixFlow.Coordinates(38.0, -121.0)

    private suspend fun hangForever(): LocationFixFlow.Coordinates? {
        delay(Long.MAX_VALUE / 2)
        return null
    }

    @Test
    fun activeFixResolves_isUsedDirectly() = runTest {
        val outcome = flow.resolve(activeFix = { activeCoords }, cachedFix = { cachedCoords })
        assertEquals(LocationFixFlow.Outcome.Fix(activeCoords, "active"), outcome)
    }

    @Test
    fun activeFixHangs_fallsBackToCachedAfterTimeout() = runTest {
        val outcome = flow.resolve(activeFix = { hangForever() }, cachedFix = { cachedCoords })
        assertEquals(LocationFixFlow.Outcome.Fix(cachedCoords, "cached"), outcome)
        assertEquals(10_000, currentTime) // gave up exactly at the active-fix timeout
    }

    @Test
    fun activeFixReturnsNull_fallsBackToCachedImmediately() = runTest {
        val outcome = flow.resolve(activeFix = { null }, cachedFix = { cachedCoords })
        assertEquals(LocationFixFlow.Outcome.Fix(cachedCoords, "cached"), outcome)
        assertEquals(0, currentTime)
    }

    @Test
    fun activeFixThrows_fallsBackToCached() = runTest {
        val outcome = flow.resolve(
            activeFix = { throw IllegalStateException("provider died") },
            cachedFix = { cachedCoords },
        )
        assertEquals(LocationFixFlow.Outcome.Fix(cachedCoords, "cached"), outcome)
    }

    @Test
    fun bothStagesHang_resolvesToDefaultAfterBothTimeouts() = runTest {
        val outcome = flow.resolve(activeFix = { hangForever() }, cachedFix = { hangForever() })
        assertEquals(LocationFixFlow.Outcome.NoFix, outcome)
        assertEquals(15_000, currentTime) // 10s active + 5s cached, then default
    }

    @Test
    fun bothStagesEmpty_resolvesToDefault() = runTest {
        val outcome = flow.resolve(activeFix = { null }, cachedFix = { null })
        assertEquals(LocationFixFlow.Outcome.NoFix, outcome)
    }
}

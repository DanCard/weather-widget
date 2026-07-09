package com.weatherwidget.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure decision chain for the "Use precise device location" flow: try an active fix,
 * fall back to the cached last fix, then to the hard default — each stage bounded by
 * a timeout so the flow always terminates.
 *
 * The timeouts exist because `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` has no
 * deadline of its own: on hardware without a live GPS stream (emulators, indoors) the
 * task can sit unresolved for 30+ seconds while the config screen shows a disabled
 * button, which reads as "nothing happens" and makes users back out — cancelling the
 * widget-add handshake. A stale cached fix is an acceptable placeholder here: the
 * saved mode is FOLLOW_DEVICE, so the background auto-heal (GpsResampler) replaces it
 * with a real fix later.
 *
 * Kept free of Android/GMS types so plain JUnit can drive it with virtual time.
 */
class LocationFixFlow(
    private val activeFixTimeoutMs: Long = ACTIVE_FIX_TIMEOUT_MS,
    private val cachedFixTimeoutMs: Long = CACHED_FIX_TIMEOUT_MS,
) {
    data class Coordinates(val lat: Double, val lon: Double)

    sealed class Outcome {
        /** Which stage produced the result — logged in the CONFIG GPS_FIX breadcrumb. */
        abstract val source: String

        data class Fix(val coordinates: Coordinates, override val source: String) : Outcome()

        object Default : Outcome() {
            override val source = "default"
        }
    }

    suspend fun resolve(
        activeFix: suspend () -> Coordinates?,
        cachedFix: suspend () -> Coordinates?,
    ): Outcome {
        attempt(activeFixTimeoutMs, activeFix)?.let { return Outcome.Fix(it, "active") }
        attempt(cachedFixTimeoutMs, cachedFix)?.let { return Outcome.Fix(it, "cached") }
        return Outcome.Default
    }

    /** Timeout and provider failures both mean "move to the next stage", never abort. */
    private suspend fun attempt(
        timeoutMs: Long,
        fix: suspend () -> Coordinates?,
    ): Coordinates? =
        try {
            withTimeoutOrNull(timeoutMs) { fix() }
        } catch (e: CancellationException) {
            throw e // outer scope cancelled (screen closed) — don't swallow
        } catch (e: Exception) {
            null
        }

    companion object {
        const val ACTIVE_FIX_TIMEOUT_MS = 10_000L
        const val CACHED_FIX_TIMEOUT_MS = 5_000L
    }
}

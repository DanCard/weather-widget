package com.weatherwidget.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit coverage for [LocationMatch.sameSite] — the fine "same physical site, modulo precision
 * jitter" box used by the in-memory hourly-forecast unification. Regression guard for the bug where a
 * past-day forecast line went blank: morning rows at one GPS precision were dropped by an exact-float
 * filter while afternoon rows sat at a slightly different precision.
 */
class LocationMatchSameSiteTest {

    // The exact coordinates seen on-device for 2026-06-16 (same Mountain View spot, two precisions).
    private val afternoonLat = 37.4168014526367
    private val afternoonLon = -122.088897705078
    private val morningLat = 37.4168434143066
    private val morningLon = -122.088996887207

    @Test
    fun `sub-precision fragments of the same site match`() {
        assertTrue(
            "morning and afternoon fragments are the same site",
            LocationMatch.sameSite(afternoonLat, afternoonLon, morningLat, morningLon),
        )
    }

    @Test
    fun `match is symmetric`() {
        assertTrue(LocationMatch.sameSite(morningLat, morningLon, afternoonLat, afternoonLon))
    }

    @Test
    fun `a genuinely different nearby marker does not match`() {
        // 37.422 vs 37.4168 is ~0.0052° (~0.4 mi) apart — a distinct marker, must stay separate so it
        // can't jitter the smoothing/interpolation.
        assertFalse(
            "37.422 is a different marker, not the same site",
            LocationMatch.sameSite(afternoonLat, afternoonLon, 37.4220, -122.0841),
        )
    }

    @Test
    fun `identical coordinates match`() {
        assertTrue(LocationMatch.sameSite(afternoonLat, afternoonLon, afternoonLat, afternoonLon))
    }

    @Test
    fun `just inside and just outside the tolerance`() {
        val t = LocationMatch.SAME_SITE_TOLERANCE_DEG
        assertTrue(LocationMatch.sameSite(37.0, -122.0, 37.0 + t * 0.9, -122.0 - t * 0.9))
        assertFalse(LocationMatch.sameSite(37.0, -122.0, 37.0 + t * 1.1, -122.0))
    }
}

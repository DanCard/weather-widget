package com.weatherwidget.util

import com.weatherwidget.data.local.ObservationEntity
import org.junit.Assert.*
import org.junit.Test

class SpatialInterpolatorTest {

    private val nowMs = System.currentTimeMillis()

    private fun obs(
        stationId: String,
        temperature: Float,
        distanceKm: Float,
        ageMs: Long = 0L,
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = nowMs - ageMs,
        temperature = temperature,
        condition = "Clear",
        locationLat = 37.0,
        locationLon = -122.0,
        distanceKm = distanceKm,
        api = "NWS",
    )

    // ── Basic cases ─────────────────────────────────────────────────────────

    @Test fun emptyList_returnsNull() {
        assertNull(SpatialInterpolator.interpolateIDW(37.0, -122.0, emptyList(), nowMs))
    }

    @Test fun singleStation_returnsExactTemp() {
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, listOf(obs("A", 72f, 5f)), nowMs)
        assertEquals(72f, result!!, 0.01f)
    }

    @Test fun twoEquidistantStations_returnsAverage() {
        val observations = listOf(obs("A", 60f, 10f), obs("B", 80f, 10f))
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs)
        assertEquals(70f, result!!, 0.1f)
    }

    @Test fun closerStationDominates() {
        // 1 km vs 10 km: w_near = 1/1 = 1.0, w_far = 1/100 = 0.01
        // blend ≈ (70*1.0 + 50*0.01) / 1.01 ≈ 69.8°F — near station dominates
        val observations = listOf(obs("NEAR", 70f, 1f), obs("FAR", 50f, 10f))
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs)
        assertNotNull(result)
        assertTrue("Closer station should dominate; got $result", result!! > 69f)
    }

    // ── Near-zero guard ──────────────────────────────────────────────────────

    @Test fun veryCloseStation_returnsItsTemp() {
        // Station at 0.05 km — below the 0.1 km threshold
        val observations = listOf(obs("SNAP", 68f, 0.05f), obs("FAR", 90f, 20f))
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs)
        assertEquals(68f, result!!, 0.01f)
    }

    @Test fun multipleVeryCloseStations_returnsClosest() {
        val observations = listOf(obs("SNAP_A", 68f, 0.08f), obs("SNAP_B", 75f, 0.05f))
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs)
        // SNAP_B is closer (0.05 km)
        assertEquals(75f, result!!, 0.01f)
    }

    // ── Staleness filtering ──────────────────────────────────────────────────

    @Test fun staleObservation_filtered() {
        val threeHoursAgoMs = 3 * 60 * 60 * 1000L
        val observations = listOf(
            obs("STALE", 100f, 2f, ageMs = threeHoursAgoMs),
            obs("FRESH", 72f, 5f, ageMs = 0L),
        )
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs)
        assertEquals(72f, result!!, 0.01f)
    }

    @Test fun allObservationsStale_returnsNull() {
        val threeHoursAgoMs = 3 * 60 * 60 * 1000L
        val observations = listOf(obs("STALE", 72f, 2f, ageMs = threeHoursAgoMs))
        assertNull(SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs))
    }

    // ── Time-spread filtering ────────────────────────────────────────────────

    @Test fun observationsSpreadOverTwoHours_oldOneDropped() {
        // One observation 90 minutes older than the newest — outside the 1h cohort window
        val ninetyMinMs = 90 * 60 * 1000L
        val observations = listOf(
            obs("NEW", 72f, 5f, ageMs = 0L),
            obs("OLD", 50f, 3f, ageMs = ninetyMinMs),
        )
        // Only "NEW" should survive → exact temp returned
        val result = SpatialInterpolator.interpolateIDW(37.0, -122.0, observations, nowMs)
        assertEquals(72f, result!!, 0.01f)
    }
}

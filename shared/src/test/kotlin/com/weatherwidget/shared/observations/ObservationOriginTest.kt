package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.util.SpatialInterpolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationOriginTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun originAt(ageMs: Long, qcFailed: Boolean = false, web: Boolean = false) =
        ObservationOrigin.of(
            timestampMs = now - ageMs,
            qcFailed = qcFailed,
            isWebFallback = web,
            nowMs = now,
        )

    @Test
    fun `fresh readings report where they came from`() {
        assertEquals(ObservationOrigin.Kind.API, originAt(ageMs = 10 * 60 * 1000L))
        assertEquals(ObservationOrigin.Kind.WEB, originAt(ageMs = 10 * 60 * 1000L, web = true))
    }

    @Test
    fun `a reading just short of the blend cutoff is not yet stale`() {
        assertEquals(ObservationOrigin.Kind.API, originAt(ageMs = 3 * hour - 1))
    }

    @Test
    fun `at and beyond the blend cutoff the origin is stale regardless of where it came from`() {
        assertEquals(ObservationOrigin.Kind.STALE, originAt(ageMs = 3 * hour))
        assertEquals(ObservationOrigin.Kind.STALE, originAt(ageMs = 7 * hour))
        assertEquals(ObservationOrigin.Kind.STALE, originAt(ageMs = 7 * hour, web = true))
    }

    @Test
    fun `QC failure outranks staleness`() {
        assertEquals(ObservationOrigin.Kind.QC_FAILED, originAt(ageMs = 7 * hour, qcFailed = true))
    }

    /**
     * The badge is only honest if it flips exactly when the blend stops using the reading. If the
     * decay window in [SpatialInterpolator] ever changes, this fails rather than letting the stations
     * list quietly claim a station is contributing when it is not.
     */
    @Test
    fun `stale badge coincides with the station carrying no weight in the blend`() {
        fun blendOf(ageMs: Long): Float? = SpatialInterpolator.interpolateIDW(
            userLat = 37.0,
            userLon = -122.0,
            observations = listOf(reading(timestamp = now - ageMs)),
            nowMs = now,
        )

        assertEquals(ObservationOrigin.Kind.API, originAt(ageMs = 3 * hour - 1))
        assertEquals(70f, blendOf(3 * hour - 1))

        assertEquals(ObservationOrigin.Kind.STALE, originAt(ageMs = 3 * hour))
        assertNull(blendOf(3 * hour))
    }

    private fun reading(timestamp: Long) = ObservationReading(
        stationId = "KSJC",
        stationName = "San Jose",
        timestamp = timestamp,
        temperature = 70f,
        condition = "Clear",
        locationLat = 37.0,
        locationLon = -122.0,
        distanceKm = 5f,
        stationType = "OFFICIAL",
        api = "nws",
    )
}

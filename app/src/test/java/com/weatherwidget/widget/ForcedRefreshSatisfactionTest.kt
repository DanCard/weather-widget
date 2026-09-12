package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForcedRefreshSatisfactionTest {
    /** The 18:03:35 toggle sync: fetched at 18:03:46, killed at 18:03:50, re-run at 18:04:05. */
    @Test
    fun `a fetch that succeeded after the request satisfies it`() {
        assertTrue(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 1_000, lastSuccessMs = 11_000))
    }

    @Test
    fun `a fetch from before the request does not`() {
        assertFalse(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 20_000, lastSuccessMs = 11_000))
        assertFalse(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 11_000, lastSuccessMs = 11_000))
    }

    /** Enqueue paths that do not stamp a request time keep their force. */
    @Test
    fun `an unstamped request is never satisfied`() {
        assertFalse(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 0, lastSuccessMs = 11_000))
    }

    // ---- isSatisfiedByCompletedFetch: the under-lock decision in ForecastRepository.getWeatherData ----

    private val santaClara = fetch(lat = 37.3725, lon = -121.9809, target = null, sources = setOf("NWS", "OPEN_METEO", "SILURIAN"), completedAt = 49_600)

    /** 12:09:49.667: hourly_gaps requested 12:09:44.569, the location fetch completed 12:09:49.599. */
    @Test
    fun `a same-site fetch completing after the request satisfies it`() {
        assertTrue(satisfied(requestedAt = 44_569, last = santaClara, lat = 37.3725, lon = -121.9809, target = null))
    }

    /** Emulator 14:46:47: request 24 ms before completion — rows were all stamped earlier, completion was not. */
    @Test
    fun `a request landing just before completion is still satisfied`() {
        assertTrue(satisfied(requestedAt = 49_576, last = santaClara, lat = 37.3725, lon = -121.9809, target = null))
    }

    @Test
    fun `a fetch completing before the request does not`() {
        assertFalse(satisfied(requestedAt = 49_600, last = santaClara, lat = 37.3725, lon = -121.9809, target = null))
        assertFalse(satisfied(requestedAt = 50_000, last = santaClara, lat = 37.3725, lon = -121.9809, target = null))
    }

    /** The Mountain View pipeline finishing must not satisfy a Santa Clara request. */
    @Test
    fun `a fetch for another site does not`() {
        assertFalse(satisfied(requestedAt = 44_569, last = santaClara, lat = 37.4168, lon = -122.0890, target = null))
    }

    @Test
    fun `a targeted request needs its source to have been fetched`() {
        assertTrue(satisfied(requestedAt = 44_569, last = santaClara, lat = 37.3725, lon = -121.9809, target = "OPEN_METEO"))
        // Untargeted fetches skip throttled non-primary sources; "all" does not imply this one.
        assertFalse(satisfied(requestedAt = 44_569, last = santaClara, lat = 37.3725, lon = -121.9809, target = "TOMORROW_IO"))
    }

    /** A toggle-forced single-source fetch does not stand in for a full refresh. */
    @Test
    fun `an untargeted request is not satisfied by a targeted fetch`() {
        val nwsOnly = santaClara.copy(targetSourceId = "NWS", sourceIds = setOf("NWS"))
        assertFalse(satisfied(requestedAt = 44_569, last = nwsOnly, lat = 37.3725, lon = -121.9809, target = null))
        assertTrue(satisfied(requestedAt = 44_569, last = nwsOnly, lat = 37.3725, lon = -121.9809, target = "NWS"))
    }

    @Test
    fun `no prior fetch or an unstamped request is never satisfied`() {
        assertFalse(satisfied(requestedAt = 44_569, last = null, lat = 37.3725, lon = -121.9809, target = null))
        assertFalse(satisfied(requestedAt = 0, last = santaClara, lat = 37.3725, lon = -121.9809, target = null))
    }

    private fun fetch(lat: Double, lon: Double, target: String?, sources: Set<String>, completedAt: Long) =
        ForcedRefreshSatisfaction.CompletedFetch(lat, lon, target, sources, completedAt)

    private fun satisfied(requestedAt: Long, last: ForcedRefreshSatisfaction.CompletedFetch?, lat: Double, lon: Double, target: String?) =
        ForcedRefreshSatisfaction.isSatisfiedByCompletedFetch(requestedAt, last, lat, lon, target)
}

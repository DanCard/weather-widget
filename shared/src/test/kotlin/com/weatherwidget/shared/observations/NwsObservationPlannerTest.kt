package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.experimental.categories.Category
import org.junit.Test

/**
 * Phase 3d of plans/260909-nws-fetch-unification.md: one implementation of the latest-observation
 * merge + cloud-carrier rule for both platforms.
 */
@Category(ShortDuration::class)
class NwsObservationPlannerTest {

    private fun reading(
        ts: Long,
        temp: Float = 70f,
        isWeb: Boolean = false,
        qcFailed: Boolean = false,
        lowCloud: Int? = null,
    ) = ObservationReading(
        stationId = "KNUQ",
        stationName = "Moffett",
        timestamp = ts,
        temperature = temp,
        condition = "Clear",
        locationLat = 37.4,
        locationLon = -122.0,
        api = "NWS",
        isWebFallback = isWeb,
        qcFailed = qcFailed,
        cloudCoverLow = lowCloud,
    )

    private fun merge(
        api: ObservationReading?,
        apiMs: Long?,
        web: List<ObservationReading>,
        useWeb: Boolean,
    ) = NwsObservationPlanner.mergeLatest(
        apiLatest = api,
        apiNewestMs = apiMs,
        webReadings = web,
        useWebForLatest = useWeb,
        isQcFailed = { it.qcFailed },
        observedAtMillis = { it.timestamp },
        hasLowCloud = { it.cloudCoverLow != null },
    )

    @Test
    fun `newer api wins and never carries a cloud carrier`() {
        val api = reading(ts = 2000L, lowCloud = 10)
        val result = merge(api, apiMs = 2000L, web = listOf(reading(1000L, isWeb = true)), useWeb = true)

        assertEquals(api, result.chosen)
        assertTrue(!result.chosenIsWeb)
        assertNull(result.cloudCarrier)
    }

    @Test
    fun `strictly newer web wins and the api row is kept when it has low cloud`() {
        val api = reading(ts = 1000L, lowCloud = 42)
        val web = reading(ts = 2000L, isWeb = true)
        val result = merge(api, apiMs = 1000L, web = listOf(web), useWeb = true)

        assertEquals(web, result.chosen)
        assertTrue(result.chosenIsWeb)
        assertEquals(api, result.cloudCarrier)
        assertEquals(2000L, result.webNewestMs)
        assertEquals(1000L, result.apiNewestMs)
    }

    @Test
    fun `metrics-only tier never selects the web reading`() {
        val api = reading(ts = 1000L, lowCloud = 42)
        val web = reading(ts = 2000L, isWeb = true)
        val result = merge(api, apiMs = 1000L, web = listOf(web), useWeb = false)

        assertEquals(api, result.chosen)
        assertTrue(!result.chosenIsWeb)
        assertNull(result.cloudCarrier)
    }

    @Test
    fun `api row without low cloud is not kept as a carrier`() {
        val api = reading(ts = 1000L, lowCloud = null)
        val web = reading(ts = 2000L, isWeb = true)
        val result = merge(api, apiMs = 1000L, web = listOf(web), useWeb = true)

        assertEquals(web, result.chosen)
        assertNull(result.cloudCarrier)
    }

    @Test
    fun `qc-flagged newest web reading is never chosen`() {
        val api = reading(ts = 1000L)
        val flagged = reading(ts = 3000L, isWeb = true, qcFailed = true)
        val result = merge(api, apiMs = 1000L, web = listOf(flagged), useWeb = true)

        assertEquals(api, result.chosen)
        assertTrue(!result.chosenIsWeb)
    }

    @Test
    fun `no api reading falls back to the web reading`() {
        val web = reading(ts = 2000L, isWeb = true)
        val result = merge(api = null, apiMs = null, web = listOf(web), useWeb = true)

        assertEquals(web, result.chosen)
        assertTrue(result.chosenIsWeb)
        assertNull(result.cloudCarrier)
    }
}

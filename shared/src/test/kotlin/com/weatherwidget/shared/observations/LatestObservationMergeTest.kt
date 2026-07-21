package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Prefer-newest resolution for the latest-observation path (plan 260721). A minimal reading model
 * stands in for `NwsApi.Observation` so the rule can be tested without any platform types.
 */
@Category(ShortDuration::class)
class LatestObservationMergeTest {

    private data class R(val id: String, val ms: Long?, val flagged: Boolean = false)

    private fun merge(api: R?, web: List<R>) =
        LatestObservationMerge.preferNewest(
            apiLatest = api,
            apiNewestMs = api?.ms,
            webReadings = web,
            isQcFailed = { it.flagged },
            observedAtMillis = { it.ms },
        )

    @Test
    fun `web strictly newer wins`() {
        val api = R("api", 1_000_000L)
        val web = R("web", 1_060_000L) // 1 min newer
        val result = merge(api, listOf(web))
        assertEquals(web, result.chosen)
        assertTrue(result.chosenIsWeb)
        assertEquals(1_000_000L, result.apiNewestMs)
        assertEquals(1_060_000L, result.webNewestMs)
    }

    @Test
    fun `api wins when web is older`() {
        val api = R("api", 2_000_000L)
        val web = R("web", 1_500_000L)
        val result = merge(api, listOf(web))
        assertEquals(api, result.chosen)
        assertFalse(result.chosenIsWeb)
    }

    @Test
    fun `exact-timestamp tie goes to the api`() {
        // Same physical METAR; keep the official value. Web's real contribution is fresher stamps.
        val api = R("api", 1_234_000L)
        val web = R("web", 1_234_000L)
        val result = merge(api, listOf(web))
        assertEquals(api, result.chosen)
        assertFalse(result.chosenIsWeb)
    }

    @Test
    fun `newest web reading is flagged so the next-newest usable one is compared`() {
        // The freshest web reading fails QC; it must never be chosen. The newest UNFLAGGED web
        // reading is what competes with the API (KPAO 2026-07-13 bogus 50F case).
        val api = R("api", 1_000_000L)
        val flaggedNewest = R("web-bad", 1_500_000L, flagged = true)
        val usableOlder = R("web-ok", 1_050_000L)
        val result = merge(api, listOf(usableOlder, flaggedNewest))
        assertEquals(usableOlder, result.chosen)
        assertTrue(result.chosenIsWeb)
        assertEquals("metric reports the usable web stamp, not the flagged one", 1_050_000L, result.webNewestMs)
    }

    @Test
    fun `all web flagged falls back to api`() {
        val api = R("api", 1_000_000L)
        val web = listOf(R("w1", 1_500_000L, flagged = true), R("w2", 1_600_000L, flagged = true))
        val result = merge(api, web)
        assertEquals(api, result.chosen)
        assertFalse(result.chosenIsWeb)
        assertNull("no usable web stamp for the metric", result.webNewestMs)
    }

    @Test
    fun `null api latest yields the newest usable web reading`() {
        // The API returned nothing (station empty/failed); web carries the reading.
        val web = listOf(R("w1", 1_400_000L), R("w2", 1_500_000L))
        val result = merge(null, web)
        assertEquals(R("w2", 1_500_000L), result.chosen)
        assertTrue(result.chosenIsWeb)
        assertNull(result.apiNewestMs)
    }

    @Test
    fun `no readings at all yields null`() {
        val result = merge(null, emptyList())
        assertNull(result.chosen)
        assertFalse(result.chosenIsWeb)
        assertNull(result.apiNewestMs)
        assertNull(result.webNewestMs)
    }

    @Test
    fun `web reading with unparseable timestamp is ignored`() {
        // observedAtMillis returns null for a reading whose timestamp did not parse; it cannot be
        // "newest" and must not be chosen over a real API reading.
        val api = R("api", 1_000_000L)
        val web = R("web", null)
        val result = merge(api, listOf(web))
        assertEquals(api, result.chosen)
        assertFalse(result.chosenIsWeb)
        assertNull(result.webNewestMs)
    }
}

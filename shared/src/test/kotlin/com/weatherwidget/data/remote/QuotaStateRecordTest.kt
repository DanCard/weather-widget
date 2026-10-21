package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.QuotaRefusal
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** [QuotaState.record], the one rule both platforms' quota stores are written by. */
@Category(ShortDuration::class)
class QuotaStateRecordTest {
    private val now = 1_000_000L

    private class FakeStore : QuotaState {
        val blocks = mutableMapOf<Pair<String, ForecastProduct>, QuotaState.ProductQuota>()
        val calls = mutableListOf<String>()
        override fun productQuota(sourceId: String, product: ForecastProduct, nowMs: Long) =
            blocks[sourceId to product]?.takeIf { it.untilMs > nowMs }
        override suspend fun recordProductQuota(sourceId: String, product: ForecastProduct, untilMs: Long, detail: String?) {
            blocks[sourceId to product] = QuotaState.ProductQuota(untilMs, 0L, detail)
            calls += "record $product $untilMs"
        }
        override suspend fun clearProductQuota(sourceId: String, product: ForecastProduct) {
            blocks.remove(sourceId to product)
            calls += "clear $product"
        }
        override suspend fun recordPartialRateLimit(sourceId: String, detail: String?) { calls += "partial $detail" }
        override fun partialRateLimit(sourceId: String, nowMs: Long): QuotaState.RateLimit? = null
    }

    private fun refused(until: Long) = RawFetch(quotaRefused = mapOf(ForecastProduct.HOURLY to QuotaRefusal(until, "429")))

    @Test
    fun `a block is recorded once, not again while it stands`() = runBlocking {
        // Android re-recorded (and logged) the same block on every fetch; desktop only on change.
        val store = FakeStore()
        QuotaState.record(store, "GOOGLE_WEATHER", refused(now + 60_000), now)
        QuotaState.record(store, "GOOGLE_WEATHER", refused(now + 60_000), now + 1)
        assertEquals(listOf("record HOURLY ${now + 60_000}"), store.calls)
    }

    @Test
    fun `an answered product ends its block, and only a block in force`() = runBlocking {
        val store = FakeStore()
        QuotaState.record(store, "GOOGLE_WEATHER", RawFetch(), now)
        QuotaState.record(store, "GOOGLE_WEATHER", refused(now + 60_000), now)
        QuotaState.record(store, "GOOGLE_WEATHER", RawFetch(), now + 1)
        assertEquals(listOf("record HOURLY ${now + 60_000}", "clear HOURLY"), store.calls)
    }

    @Test
    fun `a partial rate limit is recorded with the key redacted`() = runBlocking {
        val store = FakeStore()
        QuotaState.record(store, "GOOGLE_WEATHER", RawFetch(partialRateLimitDetail = "GET /v1?key=SECRET 429"), now)
        assertEquals(1, store.calls.size)
        assertEquals(false, store.calls.single().contains("SECRET"))
    }
}

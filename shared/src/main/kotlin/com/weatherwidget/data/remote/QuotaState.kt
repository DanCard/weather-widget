package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.RawFetch

/**
 * The quota refusals the views banner ([QuotaNotice], [RateLimitNotice]), kept where each platform's
 * views can read them: Android prefs (widget process), desktop `app_logs` contract rows (the popup is
 * another process). One store contract, one [record] rule — before this the two platforms re-recorded
 * on different triggers and logged differently (plans/261021-android-desktop-shared-code-audit.md §2 #4).
 */
interface QuotaState {
    /** One forecast product refused until [untilMs]; [sinceMs] is when its banner streak began. */
    data class ProductQuota(val untilMs: Long, val sinceMs: Long, val detail: String?)

    /** A short-window refusal that cut a fetch's hourly pages short, at [atMs]. */
    data class RateLimit(val atMs: Long, val detail: String?)

    /** [sourceId]'s [product] block in force at [nowMs], else null. */
    fun productQuota(sourceId: String, product: ForecastProduct, nowMs: Long): ProductQuota?

    /** Persists a new block (or a new end for the current one); the store logs it. */
    suspend fun recordProductQuota(sourceId: String, product: ForecastProduct, untilMs: Long, detail: String?)

    /** Ends [sourceId]'s [product] block; the store logs it. */
    suspend fun clearProductQuota(sourceId: String, product: ForecastProduct)

    /** Persists a partial rate-limit refusal; the store logs it. */
    suspend fun recordPartialRateLimit(sourceId: String, detail: String?)

    /** [sourceId]'s partial rate-limit refusal while its banner is up at [nowMs], else null. */
    fun partialRateLimit(sourceId: String, nowMs: Long): RateLimit?

    companion object {
        /**
         * What a forecast fetch that answered says about [sourceId]'s quotas: a partial rate limit is
         * recorded; per product, a refusal with a new end is recorded and an answered product ends its
         * block. Repeats of the same block are not re-recorded, so each store logs on change only. The
         * fetch itself is a success — an hours-only refusal must not fail it (user, 2026-10-07).
         */
        suspend fun record(store: QuotaState, sourceId: String, result: RawFetch, nowMs: Long) {
            result.partialRateLimitDetail?.let { store.recordPartialRateLimit(sourceId, ApiKeyRedaction.redact(it)) }
            for (product in ForecastProduct.entries) {
                val refusal = result.quotaRefused[product]
                val current = store.productQuota(sourceId, product, nowMs)
                when {
                    refusal != null && refusal.untilMs != current?.untilMs ->
                        store.recordProductQuota(sourceId, product, refusal.untilMs, ApiKeyRedaction.redact(refusal.detail))
                    refusal == null && current != null -> store.clearProductQuota(sourceId, product)
                }
            }
        }
    }
}

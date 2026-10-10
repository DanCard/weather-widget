package com.weatherwidget.widget

import android.content.SharedPreferences
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import java.time.Clock

/** Owns widget/source cooldowns, current-temperature throttles, and source-health diagnostics. */
internal class WidgetFetchStateStore(
    private val prefs: SharedPreferences,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun shouldRefreshMissingData(
        widgetId: Int,
        sourceId: String,
        refreshType: String,
        cooldownMs: Long,
    ): Boolean =
        cooldownElapsed(
            lastMs = prefs.getLong(missingDataKey(widgetId, sourceId, refreshType), 0L),
            cooldownMs = cooldownMs,
        )

    fun markMissingDataRefreshRequested(widgetId: Int, sourceId: String, refreshType: String) {
        prefs.edit()
            .putLong(missingDataKey(widgetId, sourceId, refreshType), clock.millis())
            .apply()
    }

    /** When this widget last requested a missing-data refresh for [sourceId] (0 = never). */
    fun missingDataRequestedAtMs(widgetId: Int, sourceId: String, refreshType: String): Long =
        prefs.getLong(missingDataKey(widgetId, sourceId, refreshType), 0L)

    /**
     * An observation backfill for [siteKey] started its fetch — stamped before the network call, so
     * success, unreachable, a throw and a kill all count. Site-keyed, not widget-keyed: the fetch is
     * for a place, and every widget there is served by it.
     */
    fun markObservationBackfillAttempted(siteKey: String) {
        prefs.edit().putLong("$KEY_OBS_BACKFILL_ATTEMPTED_PREFIX$siteKey", clock.millis()).apply()
    }

    fun observationBackfillAttemptedAtMs(siteKey: String): Long =
        prefs.getLong("$KEY_OBS_BACKFILL_ATTEMPTED_PREFIX$siteKey", 0L)

    fun nowMs(): Long = clock.millis()

    fun shouldFetchCurrentTempForSource(sourceId: String, minIntervalMs: Long): Boolean =
        cooldownElapsed(
            lastMs = prefs.getLong("$KEY_CURRENT_TEMP_FETCH_PREFIX$sourceId", 0L),
            cooldownMs = minIntervalMs,
        )

    fun markCurrentTempFetched(sourceId: String) {
        prefs.edit()
            .putLong("$KEY_CURRENT_TEMP_FETCH_PREFIX$sourceId", clock.millis())
            .apply()
    }

    fun sourceFailureCount(source: WeatherSource): Int =
        prefs.getInt("$KEY_SOURCE_FAILURE_COUNT_PREFIX${source.id}", 0)

    fun isSourceErrored(source: WeatherSource, threshold: Int): Boolean =
        sourceFailureCount(source) >= threshold

    @Synchronized
    fun recordSourceFetchSuccess(source: WeatherSource) {
        prefs.edit()
            .putInt("$KEY_SOURCE_FAILURE_COUNT_PREFIX${source.id}", 0)
            .remove("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}")
            .remove("$KEY_SOURCE_FAILURE_TIME_PREFIX${source.id}")
            .remove("$KEY_SOURCE_BANNER_SINCE_PREFIX${source.id}")
            .remove("$KEY_SOURCE_FAILURE_DETAIL_PREFIX${source.id}")
            .apply()
    }

    /**
     * [bannerThreshold]: the failure count at which the banner appears. Its staging anchor
     * ([sourceBannerSince]) is set when the count reaches it, and again when the error code changes
     * (new news gets the full pill) — never on a repeat of the same failure, or every scheduled retry
     * would pop it back to full size.
     */
    @Synchronized
    fun recordSourceFetchFailure(
        source: WeatherSource,
        errorCode: String?,
        bannerThreshold: Int,
        detail: String? = null,
    ) {
        val count = sourceFailureCount(source) + 1
        val now = clock.millis()
        val editor = prefs.edit()
            .putInt("$KEY_SOURCE_FAILURE_COUNT_PREFIX${source.id}", count)
            .putLong("$KEY_SOURCE_FAILURE_TIME_PREFIX${source.id}", now)
        val anchored = prefs.contains("$KEY_SOURCE_BANNER_SINCE_PREFIX${source.id}")
        val codeChanged = errorCode != sourceLastErrorCode(source)
        // What the error page shows; credentials are redacted by the caller.
        if (detail.isNullOrBlank()) {
            editor.remove("$KEY_SOURCE_FAILURE_DETAIL_PREFIX${source.id}")
        } else {
            editor.putString("$KEY_SOURCE_FAILURE_DETAIL_PREFIX${source.id}", detail.take(MAX_DETAIL_CHARS))
        }
        if (count == bannerThreshold || (count > bannerThreshold && (codeChanged || !anchored))) {
            editor.putLong("$KEY_SOURCE_BANNER_SINCE_PREFIX${source.id}", now)
        }
        if (errorCode == null) {
            editor.remove("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}")
        } else {
            editor.putString("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}", errorCode)
        }
        editor.apply()
    }

    fun sourceLastErrorCode(source: WeatherSource): String? =
        prefs.getString("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}", null)

    fun sourceLastFailureTime(source: WeatherSource): Long? =
        prefs.getLong("$KEY_SOURCE_FAILURE_TIME_PREFIX${source.id}", -1L).takeIf { it > 0L }

    /** The last failure's message (endpoint, status, response body), for the error-details page. */
    fun sourceLastFailureDetail(source: WeatherSource): String? =
        prefs.getString("$KEY_SOURCE_FAILURE_DETAIL_PREFIX${source.id}", null)

    /** When the banner for the current failure streak first showed (see [recordSourceFetchFailure]). */
    fun sourceBannerSince(source: WeatherSource): Long? =
        prefs.getLong("$KEY_SOURCE_BANNER_SINCE_PREFIX${source.id}", -1L).takeIf { it > 0L }

    /**
     * One forecast product of [source] refused until [untilMs] (a per-product daily quota) while the
     * source as a whole still updates. Kept apart from the source failure streak: an hours-only 429
     * must not put a banner on the daily view (user, 2026-10-07). The first record of a streak
     * anchors the banner staging; repeats keep it.
     */
    @Synchronized
    fun recordProductQuota(source: WeatherSource, product: ForecastProduct, untilMs: Long, detail: String?) {
        val key = productKey(source, product)
        val editor = prefs.edit().putLong("$key$UNTIL", untilMs)
        if (productQuota(source, product, clock.millis()) == null) editor.putLong("$key$SINCE", clock.millis())
        if (detail.isNullOrBlank()) editor.remove("$key$DETAIL") else editor.putString("$key$DETAIL", detail.take(MAX_DETAIL_CHARS))
        editor.apply()
    }

    @Synchronized
    fun clearProductQuota(source: WeatherSource, product: ForecastProduct) {
        val key = productKey(source, product)
        if (!prefs.contains("$key$UNTIL")) return
        prefs.edit().remove("$key$UNTIL").remove("$key$SINCE").remove("$key$DETAIL").apply()
    }

    data class ProductQuota(val untilMs: Long, val sinceMs: Long, val detail: String?)

    /** [source]'s [product] block, or null when none is in force at [nowMs]. */
    fun productQuota(source: WeatherSource, product: ForecastProduct, nowMs: Long): ProductQuota? {
        val key = productKey(source, product)
        val until = prefs.getLong("$key$UNTIL", 0L).takeIf { it > nowMs } ?: return null
        return ProductQuota(until, prefs.getLong("$key$SINCE", nowMs), prefs.getString("$key$DETAIL", null))
    }

    private fun productKey(source: WeatherSource, product: ForecastProduct) =
        "$KEY_PRODUCT_QUOTA_PREFIX${source.id}_${product.name}_"

    fun clearWidget(widgetId: Int, editor: SharedPreferences.Editor) {
        val prefix = "$KEY_MISSING_DATA_REFRESH_PREFIX${widgetId}_"
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
    }

    private fun cooldownElapsed(lastMs: Long, cooldownMs: Long): Boolean {
        if (lastMs == 0L) return true
        val elapsed = clock.millis() - lastMs
        return elapsed < 0L || elapsed >= cooldownMs
    }

    private fun missingDataKey(widgetId: Int, sourceId: String, refreshType: String): String =
        "$KEY_MISSING_DATA_REFRESH_PREFIX${widgetId}_${sourceId}_$refreshType"

    private companion object {
        const val KEY_MISSING_DATA_REFRESH_PREFIX = "widget_missing_data_refresh_"
        const val KEY_OBS_BACKFILL_ATTEMPTED_PREFIX = "obs_backfill_attempted_"
        const val KEY_CURRENT_TEMP_FETCH_PREFIX = "current_temp_fetch_"
        const val KEY_SOURCE_FAILURE_COUNT_PREFIX = "source_fail_count_"
        const val KEY_SOURCE_FAILURE_CODE_PREFIX = "source_fail_code_"
        const val KEY_SOURCE_FAILURE_TIME_PREFIX = "source_fail_time_"
        const val KEY_SOURCE_BANNER_SINCE_PREFIX = "source_fail_banner_since_"
        const val KEY_SOURCE_FAILURE_DETAIL_PREFIX = "source_fail_detail_"
        const val MAX_DETAIL_CHARS = 8_000
        const val KEY_PRODUCT_QUOTA_PREFIX = "source_quota_"
        const val UNTIL = "until"
        const val SINCE = "since"
        const val DETAIL = "detail"
    }
}

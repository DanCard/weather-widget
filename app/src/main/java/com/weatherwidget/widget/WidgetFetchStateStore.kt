package com.weatherwidget.widget

import android.content.SharedPreferences
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
            .apply()
    }

    /**
     * [bannerThreshold]: the failure count at which the banner appears. Its staging anchor
     * ([sourceBannerSince]) is set when the count reaches it, and again when the error code changes
     * (new news gets the full pill) — never on a repeat of the same failure, or every scheduled retry
     * would pop it back to full size.
     */
    @Synchronized
    fun recordSourceFetchFailure(source: WeatherSource, errorCode: String?, bannerThreshold: Int) {
        val count = sourceFailureCount(source) + 1
        val now = clock.millis()
        val editor = prefs.edit()
            .putInt("$KEY_SOURCE_FAILURE_COUNT_PREFIX${source.id}", count)
            .putLong("$KEY_SOURCE_FAILURE_TIME_PREFIX${source.id}", now)
        val anchored = prefs.contains("$KEY_SOURCE_BANNER_SINCE_PREFIX${source.id}")
        val codeChanged = errorCode != sourceLastErrorCode(source)
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

    /** When the banner for the current failure streak first showed (see [recordSourceFetchFailure]). */
    fun sourceBannerSince(source: WeatherSource): Long? =
        prefs.getLong("$KEY_SOURCE_BANNER_SINCE_PREFIX${source.id}", -1L).takeIf { it > 0L }

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
        const val KEY_CURRENT_TEMP_FETCH_PREFIX = "current_temp_fetch_"
        const val KEY_SOURCE_FAILURE_COUNT_PREFIX = "source_fail_count_"
        const val KEY_SOURCE_FAILURE_CODE_PREFIX = "source_fail_code_"
        const val KEY_SOURCE_FAILURE_TIME_PREFIX = "source_fail_time_"
        const val KEY_SOURCE_BANNER_SINCE_PREFIX = "source_fail_banner_since_"
    }
}

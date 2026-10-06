package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.LocationChangePaintPolicy
import java.time.LocalDate
import java.time.ZoneId

/**
 * A source just became primary (enabled at the front — `WeatherSourceOrdering.newlyPrimary`), so
 * every widget now shows it. Until 2026-10-06 nothing fetched it: Settings only reordered the list,
 * and the widget showed the new source's empty graph for 40–80 s until an unrelated sync ran.
 *
 * Now: if the source has no drawable cache here, show "Getting weather from {source}…" over the widget
 * and enqueue an expedited, forced sync targeted at it; the run clears the banner at every exit
 * ([WidgetPaintCoordinator.finishSourceSwitchBanner]). With a fresh cache there is nothing to wait
 * for: no banner, and an unforced targeted sync refreshes only if stale (Google bills per request).
 *
 * The sync still honours the startup cooldown — the case that matters is the debug migration right
 * after an install, inside the cooldown that stops the post-install tap storm. The banner covers it.
 * See plans/261006-source-becomes-primary-fetch-and-banner.md.
 */
object SourceSwitchFetch {
    const val WORK_TAG = "source_switch_fetch"

    /** What [start] did — returned for tests; WorkManager does not expose a request's input. */
    data class Started(val request: OneTimeWorkRequest, val bannerShown: Boolean, val hadCache: Boolean)

    fun message(context: Context, source: WeatherSource): String =
        context.getString(R.string.widget_fetching_source, source.displayName)

    suspend fun start(context: Context, source: WeatherSource, trigger: String): Started {
        val db = WeatherDatabase.getDatabase(context)
        val stateManager = WidgetStateManager(context)
        val location = ActiveLocationResolver.resolve(context, stateManager, db.forecastDao())
        val hasCache = location?.let { (lat, lon) -> hasDrawableCache(context, source, lat, lon) } ?: false
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
        val banner = !hasCache && location != null && ids.isNotEmpty()
        if (banner) {
            FetchBanner.show(context, message(context, source), ids, reason = "clear_source_switch_banner")
        }
        val request = buildRequest(source, forced = !hasCache)
        WorkManager.getInstance(context).enqueue(request)
        db.appLogDao().log(
            "SOURCE_SWITCH_FETCH",
            "source=${source.id} trigger=$trigger cache=$hasCache banner=$banner forced=${!hasCache} " +
                "widgets=${ids.size} location=${location != null}",
            "INFO",
        )
        return Started(request, banner, hasCache)
    }

    /**
     * Expedited only on API 31+, as for the location change (`LocationUpdater.buildForceRefreshRequest`):
     * below it WorkManager runs expedited work as a foreground service this worker has no notification for.
     */
    @VisibleForTesting
    internal fun buildRequest(source: WeatherSource, forced: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
        if (sdkInt >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder
            .addTag(WORK_TAG)
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, forced)
                    .putString(WeatherWidgetWorker.KEY_TARGET_SOURCE, source.id)
                    .putString(WeatherWidgetWorker.KEY_SOURCE_SWITCH_ID, source.id)
                    .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "source_switch_${source.id}")
                    .putLong(WeatherWidgetWorker.KEY_REQUESTED_AT_MS, System.currentTimeMillis())
                    .tagTestModeEnqueue()
                    .build(),
            )
            .build()
    }

    /**
     * Today's daily row and today's hourly rows for [source] near the site, fetched within the
     * adoptable-cache age — the same bar `LocationChangePaintPolicy.hasDrawableCache` sets for a
     * location change. Hourly rows are read through [HourlyForecastLoader], the render's own
     * site-matched path (`HourlyProximityQueryAllowlistTest`), as `WidgetPaintCoordinator.hasDrawableCacheAt` does.
     */
    @VisibleForTesting
    internal suspend fun hasDrawableCache(context: Context, source: WeatherSource, lat: Double, lon: Double): Boolean {
        val db = WeatherDatabase.getDatabase(context)
        val oldest = System.currentTimeMillis() - LocationChangePaintPolicy.MAX_ADOPTABLE_CACHE_AGE_MS
        val latest = db.forecastDao().getLatestForecastBySource(source.id, lat, lon)
        if (latest == null || latest.fetchedAt < oldest) return false
        val zone = ZoneId.systemDefault()
        val hourly = HourlyForecastLoader(context, WidgetStateManager(context))
            .load(lat, lon, sources = listOf(source.id), caller = "source_switch_probe")
            .filter { it.source == source.id && it.fetchedAt >= oldest }
        return LocationChangePaintPolicy.hasHourlyForToday(hourly.map { it.dateTime }, LocalDate.now(zone), zone)
    }
}

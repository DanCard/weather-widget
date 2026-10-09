package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.WidgetIntentActionHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A tapped or panned-to day's hourly, from its source alone, then that widget repainted — for every
 * source (`plans/261009-on-demand-hourly-shared-single-source-fetch.md`).
 *
 * It replaced the forced full sync, which took about 42 s on the Pixel: a 9.6 s `StartupCooldown`
 * deferral, every due source, actuals recompute and repairs, and the all-widgets paint, all before
 * the banner could clear. This one:
 * - is expedited on API 31+;
 * - is not cooled down — the user is watching its banner;
 * - fetches through [WeatherRepository.fetchSourceOnDemand];
 * - settles the banner ([WidgetDayClickCoordinator.completeOnDemand]) and paints that one widget.
 *
 * Unique per widget with REPLACE. A pan waits [HourlyOnDemand.PAN_SETTLE_MS] inside the work
 * (expedited work cannot be delayed), so a run of ‹ › cancels the earlier waits and ends in one fetch.
 */
@HiltWorker
class HourlyOnDemandWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val weatherRepository: WeatherRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // As WeatherWidgetWorker: never fetch for real under a test, nor for a test's job that
            // outlived its process (KEY_ENQUEUED_IN_TESTING).
            if (WeatherDatabase.isTestingMode() || inputData.getBoolean(WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING, false)) {
                return Result.success()
            }
            val widgetId = inputData.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val date = inputData.getString(KEY_DATE) ?: return Result.success()
            val source = WeatherSource.entries.find { it.id == inputData.getString(KEY_SOURCE) } ?: return Result.success()
            val lat = inputData.getDouble(KEY_LAT, Double.NaN)
            val lon = inputData.getDouble(KEY_LON, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) return Result.success()
            val hours = inputData.getInt(KEY_HOURS, 0)
            val settleMs = inputData.getLong(KEY_SETTLE_MS, 0L)
            val enqueuedAtMs = inputData.getLong(KEY_ENQUEUED_AT_MS, System.currentTimeMillis())
            if (settleMs > 0) delay(settleMs)
            val startDelayMs = System.currentTimeMillis() - enqueuedAtMs - settleMs

            val fetchStart = SystemClock.elapsedRealtime()
            val answered = try {
                weatherRepository.fetchSourceOnDemand(
                    lat,
                    lon,
                    source,
                    HourlyOnDemand.Request(source.id, hours).takeIf { hours > 0 },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            val fetchMs = SystemClock.elapsedRealtime() - fetchStart

            val paintStart = SystemClock.elapsedRealtime()
            WidgetDayClickCoordinator.completeOnDemand(context, widgetId, date, lat, lon)
            WidgetIntentActionHandler.renderWidgetFromCache(context, widgetId, weatherRepository)
            val paintMs = SystemClock.elapsedRealtime() - paintStart

            WeatherDatabase.getDatabase(context).appLogDao().log(
                "HOURLY_ON_DEMAND",
                "widget=$widgetId source=${source.id} date=$date hours=$hours answered=$answered " +
                    "startDelayMs=$startDelayMs fetchMs=$fetchMs paintMs=$paintMs",
                "INFO",
            )
            return Result.success()
        }

        companion object {
            private const val KEY_WIDGET_ID = "widget_id"
            private const val KEY_DATE = "date"
            private const val KEY_SOURCE = "source"
            private const val KEY_LAT = "lat"
            private const val KEY_LON = "lon"
            private const val KEY_HOURS = "hours"
            private const val KEY_SETTLE_MS = "settle_ms"
            private const val KEY_ENQUEUED_AT_MS = "enqueued_at_ms"

            fun uniqueName(widgetId: Int) = "hourly_on_demand_$widgetId"

            /**
             * [hours]: the horizon Google asks for (`HourlyOnDemand.hoursToCover`); 0 for a source whose
             * single call returns its whole horizon. [afterPan]: wait [HourlyOnDemand.PAN_SETTLE_MS] first.
             */
            fun enqueue(
                context: Context,
                widgetId: Int,
                date: String,
                sourceId: String,
                lat: Double,
                lon: Double,
                hours: Int,
                afterPan: Boolean,
            ): OneTimeWorkRequest {
                val request = request(widgetId, date, sourceId, lat, lon, hours, afterPan)
                WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(widgetId), ExistingWorkPolicy.REPLACE, request)
                return request
            }

            /**
             * Expedited only on API 31+, as for the location change (`LocationUpdater`): below it
             * WorkManager runs expedited work as a foreground service this worker has no notification for.
             */
            @VisibleForTesting
            internal fun request(
                widgetId: Int,
                date: String,
                sourceId: String,
                lat: Double,
                lon: Double,
                hours: Int,
                afterPan: Boolean,
                sdkInt: Int = Build.VERSION.SDK_INT,
            ): OneTimeWorkRequest {
                val builder = OneTimeWorkRequestBuilder<HourlyOnDemandWorker>()
                if (sdkInt >= Build.VERSION_CODES.S) {
                    builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                return builder
                    .setInputData(
                        Data.Builder()
                            .putInt(KEY_WIDGET_ID, widgetId)
                            .putString(KEY_DATE, date)
                            .putString(KEY_SOURCE, sourceId)
                            .putDouble(KEY_LAT, lat)
                            .putDouble(KEY_LON, lon)
                            .putInt(KEY_HOURS, hours)
                            .putLong(KEY_SETTLE_MS, if (afterPan) HourlyOnDemand.PAN_SETTLE_MS else 0L)
                            .putLong(KEY_ENQUEUED_AT_MS, System.currentTimeMillis())
                            .tagTestModeEnqueue()
                            .build(),
                    )
                    .build()
            }

            @VisibleForTesting
            internal fun dateOf(request: OneTimeWorkRequest): String? = request.workSpec.input.getString(KEY_DATE)

            @VisibleForTesting
            internal fun sourceOf(request: OneTimeWorkRequest): String? = request.workSpec.input.getString(KEY_SOURCE)

            @VisibleForTesting
            internal fun hoursOf(request: OneTimeWorkRequest): Int = request.workSpec.input.getInt(KEY_HOURS, 0)

            @VisibleForTesting
            internal fun settleMsOf(request: OneTimeWorkRequest): Long = request.workSpec.input.getLong(KEY_SETTLE_MS, 0L)
        }
    }

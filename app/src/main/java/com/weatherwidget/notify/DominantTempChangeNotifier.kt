package com.weatherwidget.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.notify.DominantTempWatch
import com.weatherwidget.shared.notify.DominantTempWatchDecision
import com.weatherwidget.shared.notify.DominantTempWatchStrings
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.HourlyForecastLoader
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Android half of the one-shot dominant-station temperature watch.
 *
 * Owns everything platform-specific — the channel, the permission check, the observation/hourly
 * loads — and delegates every decision to [DominantTempWatch] so Android and desktop cannot drift
 * on what counts as a change.
 *
 * **Cost when disarmed is one boolean read.** [check] is called from the sync paths, which run
 * every few minutes; it must not touch the database unless the user actually asked for this.
 */
object DominantTempChangeNotifier {

    private const val TAG = "DOMINANT_TEMP_WATCH"
    private const val CHANNEL_ID = "dominant_temp_change"
    private const val NOTIFICATION_ID = 4201

    /**
     * Evaluates the watch and, when the dominant reading changed, notifies once and disarms.
     *
     * Resolves against the **primary** source and the current location's observations, using the
     * same current-temperature window the widget renders from
     * ([CurrentTemperatureResolver.buildCurrentTempResolutionWindow]) — a different window would
     * select a different "latest" reading than the one on screen, and the notification would
     * disagree with the widget it came from.
     */
    suspend fun check(
        context: Context,
        repository: WeatherRepository,
        stateManager: WidgetStateManager,
        appLogDao: AppLogDao,
        lat: Double,
        lon: Double,
        origin: String,
    ) {
        val store = stateManager.dominantTempWatchPreferences
        if (!store.isArmed()) return

        val displaySource = primarySource(stateManager)
        val now = LocalDateTime.now()
        val zoneId = ZoneId.systemDefault()
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val minEpoch = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = window.end.atZone(zoneId).toInstant().toEpochMilli()

        val observations = repository.getObservationsInRange(minEpoch, maxEpoch, lat, lon)
        val hourly = HourlyForecastLoader(context, stateManager)
            .load(lat, lon, listOf(displaySource.id))

        val details = ActualsAggregator.resolveCurrentObservationDetails(
            observations = observations.map { it.toReading() },
            hourlyForecasts = hourly.map { it.toHourlyForecast() },
            displaySourceId = displaySource.id,
            userLat = lat,
            userLon = lon,
            nowMs = now.atZone(zoneId).toInstant().toEpochMilli(),
            lookbackHours = CurrentTemperatureResolver.RESOLUTION_LOOKBACK_HOURS,
            lookaheadHours = CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS,
            personalStationWeight = stateManager.getPersonalStationWeight(),
        )

        val decision = DominantTempWatch.evaluate(
            state = store.load(),
            dominant = details?.dominantContribution?.contribution,
            useCelsius = stateManager.useCelsius(),
            strings = DominantTempWatchStrings(
                title = context.getString(R.string.notify_dominant_temp_title),
                bodyFormat = context.getString(R.string.notify_dominant_temp_body_format),
                bodyStationChangedFormat =
                    context.getString(R.string.notify_dominant_temp_body_station_changed_format),
            ),
        )

        when (decision) {
            is DominantTempWatchDecision.Idle -> Unit
            is DominantTempWatchDecision.Hold ->
                appLogDao.log(
                    TAG,
                    "hold reason=${decision.reason} origin=$origin source=${displaySource.id} " +
                        "obs=${observations.size}",
                    "DEBUG",
                )
            is DominantTempWatchDecision.Capture -> {
                store.save(decision.state)
                appLogDao.log(
                    TAG,
                    "baseline station=${decision.state.baselineStationId} " +
                        "tempF=${decision.state.baselineTempF} origin=$origin source=${displaySource.id}",
                    "INFO",
                )
            }
            is DominantTempWatchDecision.Fire -> {
                // Disarm FIRST. A notification that throws (or is dropped for a missing permission)
                // must still be one-shot: leaving it armed would re-fire on every sync until it
                // happened to succeed, which is the opposite of what the setting promises.
                store.save(decision.state)
                val posted = notify(context, decision.title, decision.body)
                appLogDao.log(
                    TAG,
                    "fired posted=$posted body=\"${decision.body}\" origin=$origin source=${displaySource.id}",
                    "INFO",
                )
            }
        }
    }

    /**
     * The source this alert is about.
     *
     * "Primary" in this app means the *displayed* source (`getActiveDisplaySourceIds`), not simply
     * the top of the configured order — tapping a widget's API indicator changes what is on screen
     * without reordering anything. That is a per-widget value though, and this notification is a
     * single app-wide message, so it is usable only when every installed widget agrees. When they
     * disagree (or none is installed) there is no single displayed source and the configured primary
     * is the only defensible answer.
     */
    private fun primarySource(stateManager: WidgetStateManager): com.weatherwidget.data.model.WeatherSource {
        val displayed = stateManager.getActiveDisplaySourceIds()
        return displayed.singleOrNull()
            ?.let { com.weatherwidget.data.model.WeatherSource.fromId(it) }
            ?: stateManager.getPrimarySource()
    }

    /** @return false when the OS-level permission is missing, so the log can say why nothing appeared. */
    private fun notify(context: Context, title: String, body: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_thermometer)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notify_dominant_temp_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notify_dominant_temp_channel_description)
            },
        )
    }
}

package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.WeatherWidgetApp
import com.weatherwidget.data.repository.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/** Rebinds complete widget bodies after the launcher drops RemoteViews on a package replacement. */
@dagger.hilt.android.AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: WeatherRepository

    @VisibleForTesting
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Paints through the same batch as `onUpdate` — one shared data load for every widget and the
     * startup fast path — rather than the per-widget interaction repaint this used to call. On the
     * Pixel 7 Pro after an install the interaction path cost 15.9 s / 9.7 s / 7.9 s for three
     * widgets, sequentially, on a cold interpreter (the third widget was untouchable for 36 s), and
     * Pixel's `onUpdate` then arrived and painted them all again. The batch loads once, and
     * [StartupPaintClaims] makes the later `onUpdate` yield
     * (`performance/260910-post-install-cold-start-storm.md`).
     */
    @VisibleForTesting
    internal var rebindAllWidgets: suspend (Context, WeatherRepository) -> Unit =
        { context, weatherRepository ->
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids =
                appWidgetManager
                    .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                    .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                    .toIntArray()
            if (ids.isNotEmpty()) {
                WidgetStartupCoordinator(weatherRepository).updateWidgets(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = ids,
                    startupToken = WidgetPerfLogger.newToken("package-replaced"),
                    onUpdateStartMs = SystemClock.elapsedRealtime(),
                    claimVia = STARTUP_CLAIM_VIA,
                )
            }
        }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.w(TAG, "Ignoring unexpected action=${intent.action}")
            return
        }

        Log.i(TAG, "Package replaced; rebinding all widgets from cache")
        WeatherWidgetApp.logFirstTriggerOnce("PackageReplacedReceiver:${intent.action}")
        BroadcastAsyncRunner.launch(
            context = context,
            pendingResult = goAsync(),
            scope = scope,
            caller = TAG,
        ) {
            rebindAllWidgets(context, repository)
        }
    }

    internal companion object {
        const val TAG = "PackageReplacedReceiver"
        const val STARTUP_CLAIM_VIA = "package_replaced"
    }
}

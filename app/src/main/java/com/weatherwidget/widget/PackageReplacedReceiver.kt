package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.WeatherWidgetApp
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.WidgetIntentRouter
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

    @VisibleForTesting
    internal var renderAllWidgetsFromCache: suspend (Context, WeatherRepository?) -> Unit =
        { context, weatherRepository ->
            WidgetIntentRouter.renderAllWidgetsFromCache(context, weatherRepository)
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
            renderAllWidgetsFromCache(context, repository)
        }
    }

    private companion object {
        const val TAG = "PackageReplacedReceiver"
    }
}

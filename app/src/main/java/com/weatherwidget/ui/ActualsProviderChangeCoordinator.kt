package com.weatherwidget.ui

import android.content.Context
import android.util.Log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler

/**
 * Owns the durable follow-up required when a display source's actuals provider changes.
 *
 * This deliberately does not launch a coroutine from the observations activity: the user can close
 * that short-lived screen while a provider fetch is still in flight. WorkManager owns the fresh-data
 * fetch, while a separate UI-only request makes already-cached rows visible immediately.
 */
internal object ActualsProviderChangeCoordinator {
    const val REASON = "actuals_provider_changed"
    private const val TAG = "ActualsProviderChange"

    fun apply(
        context: Context,
        widgetStateManager: WidgetStateManager,
        displaySource: WeatherSource,
        chosenProvider: WeatherSource,
    ) {
        val defaultProvider = ActualsProviderResolver.defaultProviderFor(displaySource)
        // Keep the preference absent when the user returns to the default, so a future default
        // change is not silently pinned by an old explicit value.
        widgetStateManager.setActualsProvider(
            displaySource,
            chosenProvider.takeIf { it != defaultProvider },
        )

        val appContext = context.applicationContext
        WidgetWorkScheduler.enqueueUiRepaint(appContext, reason = REASON)
        WidgetWorkScheduler.enqueueRequiredImmediateSync(
            context = appContext,
            forceRefresh = true,
            reason = REASON,
            targetSourceId = displaySource.id,
        )
        Log.i(
            TAG,
            "Provider changed displaySource=${displaySource.id} provider=${chosenProvider.id}; " +
                "queued cache repaint and required refresh",
        )
    }
}

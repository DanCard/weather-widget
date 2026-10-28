package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Dedicated receiver for user-initiated widget interactions (button taps, zoom, navigation).
 *
 * Runs exclusively on [WidgetInteractionDispatcher.dispatcher] and is declared as an independent
 * manifest receiver so Android's ActivityManagerService dispatches user taps immediately without
 * waiting on background maintenance or multi-widget cache repaints.
 */
@AndroidEntryPoint
class WidgetInteractionReceiver : BaseWidgetActionReceiver() {
    override val tag: String = TAG

    @VisibleForTesting
    override var scope: CoroutineScope = CoroutineScope(SupervisorJob() + WidgetInteractionDispatcher.dispatcher)

    override val actionHandlers: Map<String, (Context, Intent, Long) -> Unit> = interactionHandlers

    companion object {
        const val TAG = "WidgetInteractionRcvr"
    }
}

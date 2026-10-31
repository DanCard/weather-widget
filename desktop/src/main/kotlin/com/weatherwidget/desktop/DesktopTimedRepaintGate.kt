package com.weatherwidget.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.TimedRepaintGate

private const val TAG = "DesktopTimedRepaintGate"

/** The UI process's [TimedRepaintGate]. */
internal val desktopTimedRepaintGate = TimedRepaintGate(System::currentTimeMillis)

/**
 * Whether the UI fallback tick may reload the cache now. A resume always reloads: the data may be
 * hours old, and that is what the tick exists to catch. A refused tick is retried by the next one.
 */
internal suspend fun fallbackReloadAdmitted(
    resumed: Boolean,
    gate: TimedRepaintGate = desktopTimedRepaintGate,
): Boolean = resumed || gate.admit(TimedRepaintGate.Trigger.DESKTOP_FALLBACK) { tag, message ->
    Log.i(TAG, "$tag $message")
}

/** Reports presses and scrolls without consuming them, so the content under it still gets them. */
internal fun Modifier.observeTimedRepaintInteractions(
    gate: TimedRepaintGate = desktopTimedRepaintGate,
): Modifier = pointerInput(gate) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                gate.interactionObserved()
            }
        }
    }
}

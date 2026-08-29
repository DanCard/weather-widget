package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.di.RepositoryEntryPoint

/**
 * Receiver that triggers widget updates when the user unlocks the screen.
 *
 * Always does UI-only update from cache for instant feedback.
 * If charging and data is stale, also triggers background data fetch.
 *
 * Also the event-driven half of device following. The periodic worker resamples location only on
 * a *full* sync ([FullSyncPipeline.run]), and the refreshes these events enqueue
 * are all `currentTempOnly`/`uiOnly` kinds — precisely the ones that gate excludes. So without the
 * calls below, plugging in and unlocking never notice that the device has moved, and a stale saved
 * location persists until the next full sync (60 min plugged, up to 480 min on low battery).
 *
 * **The manifest half of this receiver does not fire.** All three actions declared in the manifest
 * (`ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, `USER_PRESENT`) are *implicit* broadcasts,
 * and an app targeting API 26+ is not delivered those through a manifest-declared receiver unless
 * the action is on the framework exemption list. **Measured**, 2026-08-18: three days of `app_logs`
 * across a Pixel 7 Pro and a Samsung fold hold zero `POWER_CONNECTED_EVENT` and zero
 * `UNLOCK_REFRESH_POLICY` rows, and a genuine system-dispatched `ACTION_POWER_CONNECTED` reached
 * neither device's receiver with the process alive. `exported="true"` (tried in 260413) was never
 * the gate. The manifest entry is kept because it costs nothing and still works wherever the
 * broadcast *is* delivered. The plug-in path is carried by [PowerConnectedJobService], a
 * JobScheduler charging constraint. See `plans/260818-power-connected-broadcast-never-delivered.md`.
 *
 * **The runtime half does fire, and is why the class is named for the screen.**
 * [registerScreenReceiver] registers an instance for [Intent.ACTION_SCREEN_ON] when the process
 * starts, which is the only way to receive it — **verified in platform source**, not measured:
 *
 * > You *cannot* receive this through components declared in manifests, only by explicitly
 * > registering for it with `Context.registerReceiver()`.
 * > — `android/content/Intent.java`, `ACTION_SCREEN_ON`
 *
 * The system sends `SCREEN_ON`/`SCREEN_OFF` with `FLAG_RECEIVER_REGISTERED_ONLY`, so the dispatcher
 * skips manifest components: no install warning, no runtime error, it simply never fires. **Do not
 * "fix" the dead unlock path by adding `SCREEN_ON` to the manifest** — that is a different
 * mechanism from the implicit-broadcast restriction above, and the entry would do nothing.
 * `ScreenOnReceiverManifestTest` guards this.
 *
 * Note the two grades of evidence deliberately kept apart above: the `SCREEN_ON` rule is documented
 * platform behaviour; the `USER_PRESENT` silence is an observation on two devices, not traced to a
 * documented allowlist. Both are good enough to act on; they are not the same kind of claim.
 *
 * Coverage is partial by construction — a runtime receiver lives and dies with the process, and this
 * app's process is not persistently alive. It is one layer among several
 * (`plans/260828-detect-the-move-when-the-user-is-looking.md`), not the mechanism.
 *
 * [ACTION_SCREEN_OFF][Intent.ACTION_SCREEN_OFF] is registered by the same call, and for a saving
 * that measurement showed was real rather than theoretical: 114 `CURR_FETCH_WORK_STATE` rows on a
 * Samsung fold read `decision=enqueue_delayed … interactive=false`, i.e. the current-temp loop kept
 * scheduling itself for a screen nobody was looking at. [handleScreenOff] has always intended to
 * stop that and, being manifest-only, never once fired.
 *
 * Cancelling is safe because several paths re-arm the loop — `UIUpdateReceiver`,
 * `OpportunisticUpdateJobService`, `WidgetRefreshCoordinator`, `PowerConnectedRefresh` — so a
 * cancelled loop cannot be stranded off.
 *
 * Worth knowing while reading that code: despite the name, `scheduleNextChargingUpdate` is **not**
 * gated on charging. It reads `isCharging` and uses it only in its log line; the decision function
 * never sees it. That is why the loop runs on battery at all.
 */
class ScreenOnReceiver : BroadcastReceiver() {
    /**
     * Dispatcher used for background operations.
     * Can be overridden in tests to provide synchronous execution.
     */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /**
     * Resolved through Hilt at call time rather than field-injected, so this stays a plain
     * BroadcastReceiver that tests can construct directly. Overridable as a test seam.
     */
    @VisibleForTesting
    internal var resampleLocation: suspend (Context, String) -> Unit = { ctx, trigger ->
        EntryPointAccessors
            .fromApplication(ctx.applicationContext, RepositoryEntryPoint::class.java)
            .gpsResampler()
            .maybeResample(ctx, trigger)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> handlePowerConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected(context)
            Intent.ACTION_USER_PRESENT -> handleUserPresent(context)
            Intent.ACTION_SCREEN_OFF -> handleScreenOff(context)
            Intent.ACTION_SCREEN_ON -> handleScreenOn(context)
            else -> return
        }
    }

    private fun handlePowerConnected(context: Context) {
        // Shared with PowerConnectedJobService, which is the entry point that actually fires on
        // this device family; see PowerConnectedRefresh for why this receiver does not.
        val outcome = PowerConnectedRefresh.run(context)
        Log.d(TAG, "Power connected - plug-in refresh result=${outcome.result} elapsed=${outcome.elapsedMs}ms")

        // Independent of the current-temp debounce inside run(): putting the phone on the charger
        // is the moment it has most likely just finished moving.
        resampleLocationAsync(context, trigger = "power_connected")

        logPowerConnectedEvent(context, outcome)
    }

    private fun handlePowerDisconnected(context: Context) {
        // Re-enqueue with the off-charger interval (BatteryFetchStrategy tiers).
        Log.d(TAG, "Power disconnected - rescheduling periodic forecast worker")
        WidgetWorkScheduler.schedulePeriodicSync(context)
        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)
        NonPrimaryObservationScheduler.cancel(context)
    }

    private fun handleUserPresent(context: Context) {
        val battery = getBatteryState(context)
        val uiOnly = WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
            isCharging = battery.isCharging,
        )
        Log.d(TAG, "Screen unlocked - charging=${battery.isCharging}, battery=${battery.batteryLevel}%, uiOnly=$uiOnly")

        resampleLocationAsync(context, trigger = "user_present")

        val providerIntent =
            Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_REFRESH
                if (uiOnly) {
                    putExtra(WidgetActions.EXTRA_UI_ONLY, true)
                }
            }
        context.sendBroadcast(providerIntent)

        if (battery.isCharging) {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = context,
                reason = "screen_unlock_charging",
                opportunistic = false,
            )
            NonPrimaryObservationScheduler.scheduleNextUpdate(
                context = context,
                isScreenInteractive = true,
            )
        } else {
            CurrentTempUpdateScheduler.cancel(context)
            NonPrimaryObservationScheduler.cancel(context)
        }

        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                WeatherDatabase.getDatabase(context).appLogDao().log(
                    "UNLOCK_REFRESH_POLICY",
                    "charging=${battery.isCharging} battery=${battery.batteryLevel}% uiOnly=$uiOnly",
                )
                UIUpdateScheduler(context).scheduleNextUpdate()


            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule next update on screen on", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * The display lit up. Cheapest possible response: a passive location read, nothing else.
     *
     * This is the earliest signal the app can get that the user is present — earlier than
     * [Intent.ACTION_USER_PRESENT], which needs an unlock, and earlier than a widget paint. It is
     * reachable ONLY because [registerScreenReceiver] registers this class at runtime; see the class
     * KDoc for why a manifest entry cannot work.
     *
     * Deliberately does not repaint or fetch. Screen-on fires dozens of times a day and most of them
     * change nothing; the resample is rate-limited and will enqueue its own refresh if the device
     * actually moved.
     */
    private fun handleScreenOn(context: Context) {
        Log.d(TAG, "Screen on - resampling location")
        resampleLocationAsync(context, trigger = "screen_on")
    }

    private fun handleScreenOff(context: Context) {
        val battery = getBatteryState(context)
        if (battery.isCharging) {
            Log.d(TAG, "Screen turned off while charging - keeping current-temp loop running, but canceling non-primary loop")
            NonPrimaryObservationScheduler.cancel(context)
        } else {
            Log.d(TAG, "Screen turned off on battery - canceling current-temp loop")
            CurrentTempUpdateScheduler.cancel(context)
            NonPrimaryObservationScheduler.cancel(context)
        }
    }

    /**
     * Passive `lastLocation` read + same-site comparison — no GPS power-up, no network, and no
     * Samsung "app got your precise location" notice (see [GpsResampler]).
     *
     * The rate limit is [GpsResampler.maybeResample]'s, not a local one. This used to keep its own
     * debounce against its own prefs key, which meant two throttles with different windows guarding
     * the same operation — the coupling that
     * `plans/260828-detect-the-move-when-the-user-is-looking.md` removed. Screen-on fires dozens of
     * times a day, so a limit is still essential; it just lives in one place.
     *
     * Best-effort: a resample failure must never take down the refresh the event came for.
     */
    private fun resampleLocationAsync(context: Context, trigger: String) {
        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                resampleLocation(context, trigger)
            } catch (e: Exception) {
                Log.w(TAG, "GPS resample failed (trigger=$trigger)", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun logPowerConnectedEvent(
        context: Context,
        outcome: PowerConnectedRefresh.Outcome,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                PowerConnectedRefresh.writeLog(context, outcome, source = "broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist POWER_CONNECTED_EVENT log", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun getBatteryState(context: Context): BatterySnapshot =
        BatterySnapshotProvider.snapshot(context)

    companion object {

        /**
         * Registers an instance for [Intent.ACTION_SCREEN_ON]. Call once per process, from
         * `Application.onCreate`; the registration dies with the process, which is the coverage limit
         * described in the class KDoc.
         *
         * `RECEIVER_NOT_EXPORTED` because this listens only to a protected system broadcast — the
         * flag is required at API 34+ and is the correct answer here regardless.
         */
        fun registerScreenReceiver(context: Context) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(ScreenOnReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(ScreenOnReceiver(), filter)
            }
        }

        private const val TAG = "ScreenOnReceiver"
        private const val PREFS_NAME = PowerConnectedRefresh.PREFS_NAME

        @VisibleForTesting
        internal const val KEY_LAST_RESAMPLE_MS = "last_gps_resample_ms"
    }
}

# Taps are not blocked by refreshes or timed repaints

Status: approved 2026-10-31; implemented.

## Problem

On 2026-10-31 at 12:38 on the Fold 4, switching widget 345 to the cloud graph took 12.4 s. The
cloud paint was not slow: `CloudCoverViewHandler totalMs=161–216`, `INTERACTION_E2E e2e=377–468ms`.
The tap was waiting to be delivered.

| Time | Event |
|---|---|
| 12:37:53.1 | `ACTION_DAY_CLICK` reached the app; the install killed the process 250 ms later |
| 12:37:59.3 | UI-only `ACTION_REFRESH` for widget 345 (`REFRESH_TIMING total=3533ms`) |
| 12:38:00.7 | adb `debug.REFRESH` became a user-initiated refresh of all 4 widgets |
| 12:38:01.0 | Cloud tap: touch-up, then `Received BROADCAST` 6 ms later; no `onReceive` |
| 12:38:03.6, 12:38:06.2 | Two repeat taps queued behind it |
| 12:38:13.365 | `Direct cache repaint target=all` finished |
| 12:38:13.430 | First tap reached `onReceive`, 12.4 s after it was sent |
| 12:38:23–26 | One banner expiry ran 4 repaint workers: 13 widget paint attempts, 4 pushed |

## Root causes

1. **`goAsync()` is held through the repaint.** `WidgetActionReceiver` releases early only when
   `!userInitiated`. Its own comment gives the reason: "so AMS does not queue user interaction
   taps". User-initiated refreshes have the same problem.
2. **Timed repaints ignore whether the user is interacting.** `ui_update_alarm`,
   `clear_refresh_notice`, and probably the startup follow-up (Open question 1) start regardless.
   Existing preemption only cancels tracked jobs for *other* widgets, once a tap has been received.
   The 12:37:59 refresh held widget 345's lock and the next refresh waited 2,246 ms for it. A tap
   would wait the same way.
3. **One banner schedules one repaint per widget.** `FetchBanner.show` calls
   `enqueueDelayedUiRepaint` for each id, but `buildUiRequest` takes no widget id. Each worker
   repaints every widget, so a 4-widget banner does 4 passes for one change.

Blind spot: `INTERACTION_E2E queue=` starts at `onReceive`, so it reported 5–10 ms for this stall.
Android gives the app no send time on these broadcasts, so this plan does not try to fix the metric.

## Who hits this

The all-widget stall needed `debug.REFRESH` soon after an install. In the widget UI the only
user-initiated `ACTION_REFRESH` sender found is the error pill, which repaints one widget.
Causes 2 and 3 affect normal use: they run on every `ui_update_alarm` or banner expiry.

## Shared-code audit

| Logic | Home | Why |
|---|---|---|
| "May a timed repaint run now?": quiet window, active interaction, defer cap | `:shared` | Platform-neutral rule |
| `TIMED_REPAINT_*` log lines | `:shared` | Runner owns logging, per AGENTS.md |
| Which triggers are gated | `:shared` | `Trigger` enum owned by the runner |
| Settle delay (now `DEFAULT_SETTLEMENT_DELAY_MS`) | `:shared` | One "user has settled" number |
| Interaction tracking and deferred repaint | Host per platform | System state |
| `goAsync()` early finish | `:app` | No broadcast queue on desktop |
| Banner repaint coalescing | `:app` | Desktop's notice is Compose state and has no repaint worker |

`:app` must not gain its own gate logic beyond the Host.

## Changes

### 1. Release the broadcast for every refresh (`:app`)

In `WidgetActionReceiver`, remove the `if (!userInitiated)` condition so `finishOnce` runs right
after `scope.launch`. Update the comment. This must ship first: the app cannot defer for a tap it
has not received.

Trade-off: if Android kills the process mid-refresh, the refresh and its sync request can be lost.
Passive refreshes already accept this, and the process has just handled a user action.

### 2. Add `TimedRepaintGate` (`:shared`)

New `shared/.../util/TimedRepaintGate.kt`, a runner with a Host, modelled on `ViewingRefreshPolicy`
and the existing `*Run` classes.

```kotlin
object TimedRepaintGate {
    const val QUIET_MS = 3_000L        // replaces DEFAULT_SETTLEMENT_DELAY_MS
    const val MAX_DEFER_MS = 30_000L   // a busy user cannot starve repaints indefinitely

    enum class Trigger { UI_TICK, NOTICE_EXPIRY, STARTUP_FOLLOW_UP, DESKTOP_FALLBACK }

    interface Host {
        fun nowMs(): Long
        fun activeInteractions(): Int
        fun lastInteractionMs(): Long?
        fun firstDeferredAtMs(trigger: Trigger): Long?
        fun setFirstDeferredAtMs(trigger: Trigger, atMs: Long?)
        fun log(tag: String, message: String)
    }

    /** Returns true to repaint now, false if the caller should defer. */
    fun admit(trigger: Trigger, host: Host): Boolean
}
```

As built, the interaction and deferral state moved into `:shared` too: `TimedRepaintGate(clock)` with
`interactionStarted/Ended/Observed()` and `admit(trigger, log)`, so a platform supplies only a clock
and a logger. A user-initiated refresh also counts as an interaction, and a tap extends a pending
settlement (`WidgetPreemptionSettler.extendIfPending`).

Rules:

- Defer while an interaction is active, or within `QUIET_MS` of the last one.
- Once a trigger has been deferred for `MAX_DEFER_MS`, it runs anyway.
- A resume, location change, or user refresh is never a `Trigger`, so it is never gated.

### 3. Wire the gate into Android (`:app`)

- Count active interactions in `BaseWidgetActionReceiver.launchForWidget` (increment on launch,
  decrement in `finally`) and record the last interaction time in-process.
- `WeatherWidgetWorker`: when `uiOnlyRefresh` and the reason is `ui_update_alarm` or
  `clear_refresh_notice`, call `admit` before loading data. If refused, call
  `setPaintOwed(true)`, pass all widget ids to `WidgetPreemptionSettler.recordPreempted`, and
  return success.
- Startup follow-up: call `admit` after its delay, and on refusal record the widget the same way.
- Never cancel a running worker. Deferral happens only when a worker starts, and it re-enqueues
  nothing. This respects AGENTS.md rule 6.

Banner hiding is unaffected: `FetchBanner.expire` hides the banner separately from the repaint.

### 4. Coalesce banner repaints (`:app`)

Change `FetchBanner.show` to enqueue one delayed UI repaint per call instead of one per id. Check
the other `FetchBanner.show` callers and the `delayedUiWorkName` per-widget naming first, since
some other path may rely on it.

### 5. Wire the gate into desktop (`:desktop`)

In the fallback loop at `DesktopUiApplication.kt:490`, call `admit(DESKTOP_FALLBACK)` when
`!resumed`. On refusal, `continue` without updating `lastReloadMs`, so the next tick retries.
Resume reloads bypass the gate. `.data-updated` reloads are event-driven, so they are not gated.

Add a window-level pointer-press hook as the interaction source. Desktop clicks do not wait behind
this reload, so this step is for consistency and prevents the model swapping mid-interaction. No
desktop slowdown has been measured.

## Testing

Unit tests:

1. `TimedRepaintGateTest` (shared): active interaction, inside and outside `QUIET_MS`, the
   `MAX_DEFER_MS` cap, clock going backwards, and deferral state clearing after a run.
2. `WidgetActionReceiver` Robolectric test: a user-initiated refresh finishes the pending result
   while `repaintFromCache` is suspended. This must fail on the old code.
3. Worker test: a gated reason records owed paint and settlement and does no paint; an ungated
   UI-only reason still paints.
4. `FetchBanner` test: 4 ids enqueue 1 delayed repaint.
5. Desktop test: a deferred tick leaves `lastReloadMs` unchanged, and a resumed tick bypasses the
   gate.

Device verification on the Fold 4:

1. Send `debug.REFRESH`, then tap a view straight away. Touch-up to `onReceive` should be under
   100 ms, compared with 12.4 s here. Use the method in `Measuring` below.
2. Trigger a banner, then count paints at expiry: one worker pass, compared with 13 attempts here.
3. Tap repeatedly across a `ui_update_alarm`. Expect `TIMED_REPAINT_DEFERRED`, then one repaint
   after the taps settle.
4. Compare `INTERACTION_E2E` with a warm process to check for regressions.

Desktop: run the app, click during a fallback tick, and confirm the deferred log line and a later
reload.

Then run `./scripts/staggered-tests.sh`.

## Measuring

`INTERACTION_E2E` cannot show this bug. Compare these in logcat instead:

- `InputReader` touch-up
- `ActivityManager Received BROADCAST ... ACTION_*`
- `WidgetInteractionRcvr onReceive`

## Documentation

Update `arch/hourly-forecast-update-cadence.md` (interaction preemption section) with the gate, its
triggers, and the broadcast-release rule. Add the measuring note to `arch/operations.md`.

## Results

Gate: 6,468 JVM tests and 109 instrumented tests on each of 2 emulators passed. The receiver test
fails with the old passive-only release restored.

Fold 4, freshly installed, two `debug.REFRESH` broadcasts 1.5 s apart:

- The second reached `WidgetActionReceiver` 26 ms after it was sent, with the first refresh's
  repaint still running (12.4 s before this change).
- One banner ran one repaint worker with 4 widget paint attempts, 0 pushed (4 workers and 13
  attempts before).

Emulator, 14 toggles at 1 s spacing across a banner expiry: `TIMED_REPAINT_DEFERRED
trigger=NOTICE_EXPIRY`, then `WIDGET_PREEMPTION_SETTLE` about 3 s after the last toggle.

Not exercised on a device: `TIMED_REPAINT_FORCED`, the `ui_update_alarm` and startup follow-up
deferrals, and the desktop deferral (covered by unit tests; the desktop app was restarted on the
new build).

## Open questions

1. **What sent the 12:37:59 refresh?** `scheduleStartupFullGraphRefresh` is the likely sender, but
   its 200 ms delay does not match the 4 s gap from the 12:37:55.4 fast path. The likely explanation
   is that its broadcast queued behind the `APPWIDGET_UPDATE_OPTIONS` receivers. It is gated, and now
   writes `STARTUP_FULL_GRAPH_REFRESH`. No such row appeared after the 12:51 install, when no widget
   was in the startup graph path, so this stays open until an install with a temperature graph.
2. **Why did widget 392's refresh take 4.9 s?** There are about 3.7 s of unlogged work before its
   `updateWidget`. After change 1 this no longer blocks taps, so it is out of scope, but it remains
   a slow paint.
3. **What sent the adb `debug.REFRESH`?** No script in `scripts/*.sh` sends it.

## Out of scope

- In-flight background paints of the tapped widget are not cancelled.
- Faster cold-process paints.
- Lost taps during an install.

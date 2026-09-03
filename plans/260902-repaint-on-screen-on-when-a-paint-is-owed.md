# Repaint on screen-on when a paint is already owed

**Status:** ✅ Implemented 2026-09-02
**Follows:** [`260818-widget-repaint-gate-data-watermark.md`](260818-widget-repaint-gate-data-watermark.md)
**Goal:** close the last leg of the screen-off paint skip. The debt is recorded and honoured; nothing
delivers it on the one signal that says the user is now looking.

---

## 1. The symptom, measured

Reported on the Samsung fold, 2026-09-02: the widget's hourly temperature graph showed an older
dominant-station reading than the Current Observations screen, with the **screen on**, and stayed
that way across an app switch.

| Time | Event |
|---|---|
| 16:27:21 | Last TEMPERATURE paint — `DOMINANT_STATION … KNUQ rawTemp=75.2` (the 16:15 reading) |
| 16:43:49 | `charging_loop` fetch (`interactive=false`) inserts `KNUQ 16:35 = 73.4` |
| 16:43:55 | `WIDGET_PAINT_SKIP reason=screen_off` → `setPaintOwed(true)` |
| **16:48:33** | **`GPS_RESAMPLE outcome=same_site trigger=screen_on`** — screen on, receiver fires, handler runs |
| ~16:48:40 | Observations activity queries the DB live → **73.4** ✓ |
| ~16:49:00 | Back to the home screen → widget still **75.2** |
| 16:49:41 | `ui_update_alarm` paint → `DOMINANT_STATION … rawTemp=73.399994` |

**Visible divergence: 16:48:33 → 16:49:41 = 68 seconds.**

The number that matters is the screen-on gap, not the 6-minute data-to-paint latency. The user slept
through the first five minutes of that; they only ever see the part after the display lights up.
Quoting the larger number describes the pipeline, not the complaint.

## 2. What is *not* wrong

Ruled out against the DB and logs before touching anything:

- **Not a blend or row-selection disagreement.** Both surfaces read the same `observations` rows.
  `OBS_CURRENT_INSERT … station=KNUQ timestamp=1788392100000 temp=73.399994` is a single row that
  both would select; they differ only in *when* they read.
- **Not the bitmap gate.** `f1dfa107` fixed that: `GraphRepaintGate` returns
  `Decision(true, "paint_owed")` ahead of every other check, so the eventual paint does rebuild the
  bitmap rather than being gated out by an unchanged temp string.
- **Not coordinate fragmentation.** `TemperatureViewHandler` `headerState` logs
  `configuredLoc=37.41674,-122.08884 dataLoc=37.41674,-122.08884` on both paints.
- **Not a dead signal.** See below — this is the correction that redirects the fix.

## 3. Root cause: the signal arrives and is not read

`ScreenOnReceiver` is **runtime-registered** for `ACTION_SCREEN_ON` by
`registerScreenReceiver(context)` from `Application.onCreate`. Only the *manifest* half
(`USER_PRESENT`, `ACTION_POWER_CONNECTED`) is undeliverable. `handleScreenOn` demonstrably ran at
16:48:33 — the `GPS_RESAMPLE trigger=screen_on` row is the receipt.

It then declines to repaint, deliberately:

> *"Deliberately does not repaint or fetch. Screen-on fires dozens of times a day and most of them
> change nothing; the resample is rate-limited and will enqueue its own refresh if the device
> actually moved."*

That default is correct and stays. Its blind spot is narrow: it treats every screen-on as equally
uninformative, while the app has **already computed the discriminator**.
`WidgetStateManager.isPaintOwed()` was set at 16:43:55 and means exactly *"new data landed while
nobody was looking, and the pixels on screen predate it."* A screen-on with that flag set is not one
of the dozens that change nothing.

**So this is an unread signal, not a missing one.** The signal arrived 68 s before the repaint; the
flag authorising that repaint had been set 5 minutes earlier. The two never met.

### 3a. Why the process-liveness caveat is weak here

A runtime receiver dies with its process, which is the standing limit on this class of fix. It bites
much less than usual in this case: **the debt is incurred by a fetch, and the fetch is what keeps the
process warm.** The process that owes a paint is normally still alive to hear `SCREEN_ON`. Confirmed
in this trace — pid `13576` fired both the 16:48:33 resample and the 16:49:41 paint.

Coverage is therefore partial by construction but well-correlated with the failure it addresses.
This is one layer, not the mechanism; `ui_update_alarm` remains the backstop.

## 4. The change

In `ScreenOnReceiver.handleScreenOn`, request a **repaint from cache** — not a fetch — and only
when a paint is owed.

**The data is already in the DB; nothing needs re-fetching.** That is the premise of the whole bug:
the fetch succeeded, the rows are stored, and the Observations screen renders them correctly. The
only thing missing is that the widget's bitmap was never redrawn from them. So this must be a redraw
and nothing more.

`WidgetActions.ACTION_REFRESH` is the codebase's name for the widget repaint entry point, not for a
data fetch — a misleading name inherited here rather than introduced.
`WidgetRefreshCoordinator.refresh` always calls `repaintFromCache(...)`, and reaches the network only
when `WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)` returns true.
That is `!uiOnlyRequested && isDataStale`, so with `EXTRA_UI_ONLY = true` the fetch branch is
**unreachable regardless of staleness**. The broadcast reduces to a cache repaint.

```
handleScreenOn(context):
    resampleLocationAsync(context, trigger = "screen_on")     // unchanged
    if (WidgetStateManager(context).isPaintOwed()):
        sendBroadcast(WidgetActionReceiver, ACTION_REFRESH, EXTRA_UI_ONLY = true)
        log SCREEN_ON_PAINT_DEBT outcome=refresh_requested
```

Design notes:

- **Reuses the existing repaint path.** `handleUserPresent` already sends exactly this broadcast; it
  is only ever dead because `USER_PRESENT` is not delivered. No new mechanism.
- **`uiOnly = true` is load-bearing, not decorative.** It is what makes the fetch branch
  unreachable. Without it, `isDataStale` could be true at screen-on and this would enqueue a real
  network sync — turning screen-on into a fetch trigger and breaking the rule that the battery
  cadence, not the display, bounds fetch cost.
- **No new rate limit.** `isPaintOwed()` is self-limiting: it is set only by a screen-off paint skip
  and cleared by the next successful render, so it cannot fire twice without an intervening fetch.
  Adding a debounce would be a second throttle guarding one operation — the coupling
  `260828-detect-the-move-when-the-user-is-looking.md` removed.
- **Does not clear the flag.** `WidgetPaintCoordinator` clears it only once a render actually
  launched (guarding against the 30 s per-widget throttle swallowing the debt). Clearing it here
  would reintroduce exactly that bug.
- **Cost when idle:** one `SharedPreferences` boolean read per screen-on, on an already-loaded prefs
  file. The expensive path stays behind the flag.

## 5. Tests

Added to the existing `ScreenOnReceiverTest` rather than a new file: it already wires the
`ioDispatcher` / `resampleLocation` seams, the `TestDatabase`, and the prefs reset, and these cases
belong with the other `onReceive` behaviours. `WidgetStateManager(context)` is constructible
directly under Robolectric, so the tests set the *real* flag rather than mocking it — which also
makes them 2-class integration tests by the repo's definition.

One trap found while writing them: the paint debt lives in `widget_state_prefs`, which the existing
`setUp` did not clear (it clears only `screen_on_receiver_prefs`). Without adding that reset, a test
that sets the debt leaks it into every later test in the class — including the "no paint owed" case,
which would then pass for the wrong reason.

1. **Debt set → refresh requested.** `paintOwed = true`, deliver `ACTION_SCREEN_ON`, assert a
   broadcast with `ACTION_REFRESH` and `EXTRA_UI_ONLY = true` was sent.
2. **No debt → no refresh.** `paintOwed = false`, deliver `ACTION_SCREEN_ON`, assert **no** refresh
   broadcast. This is the regression guard on the "dozens of times a day" default.
3. **Resample still runs in both cases** — the new branch must not displace the existing behaviour.
4. **Flag survives the trigger.** `paintOwed` still true after `handleScreenOn`; only a real render
   clears it.

Integration, Robolectric (2+ classes, per the repo's definition):

5. **`ScreenOnReceiver` → `WidgetActionReceiver` → paint.** Seed a screen-off skip so the debt is
   set, deliver `ACTION_SCREEN_ON`, assert `WIDGET_PAINT_OWED action=force_rebuild` is logged and
   the temperature view repaints. Prove it fails with the change reverted.

Guard already in place: `ScreenOnReceiverManifestTest` stops anyone "fixing" this by adding
`SCREEN_ON` to the manifest, which would silently do nothing.

## 6. Verified on device (Samsung fold, 2026-09-02 17:07)

Debt set, screen powered off and on with the process alive:

```
17:07:22 | GPS_RESAMPLE         | outcome=skipped_cooldown trigger=screen_on
17:07:22 | SCREEN_ON_PAINT_DEBT | outcome=refresh_requested uiOnly=true
17:07:22 | REFRESH_DECISION     | uiOnlyRequested=true ... isDataStale=false targetWidget=all
17:07:23 | DOMINANT_STATION     | widget=345 source=NWS reason=text_ok station=KNUQ
17:07:24 | DOMINANT_STATION     | widget=349 source=NWS reason=text_ok station=KNUQ
17:07:25 | WIDGET_PAINT_OWED    | action=force_rebuild widgets=3
```

**Screen-on → repaint: ~1 s, against the 68 s in §1.** `widget_paint_owed` is absent from
`widget_state_prefs.xml` afterwards — cleared by the render, not by the receiver, as intended.

Two independent confirmations in the same trace:

- **No fetch.** `isDataStale=false` with `uiOnlyRequested=true`: the repaint ran and the network
  branch never engaged. The redraw is the entire operation.
- **The negative case holds.** An earlier `GPS_RESAMPLE trigger=screen_on` at 17:03:54, with no debt
  pending, produced **no** `SCREEN_ON_PAINT_DEBT` row. Ordinary screen-ons stay as cheap as before.

Reproduction note for next time: `KEYCODE_SLEEP` on this fold does **not** make
`PowerManager.isInteractive` false (always-on display), so it produces no `SCREEN_ON`/`SCREEN_OFF`
pair. `input keyevent 26` does. And the runtime receiver dies with the process — verify
`pidof com.weatherwidget` is non-empty before concluding the branch did not fire.

## 6a. Original verification steps

After install, on the Samsung fold:

1. Let a `charging_loop` fetch land with the screen off — confirm `WIDGET_PAINT_SKIP reason=screen_off`.
2. Turn the screen on. Expect, within seconds: `GPS_RESAMPLE trigger=screen_on`, then
   `SCREEN_ON_PAINT_DEBT outcome=refresh_requested`, then `WIDGET_PAINT_OWED action=force_rebuild`
   and a `DOMINANT_STATION` row carrying the new reading.
3. Confirm the gap between the screen-on row and the `DOMINANT_STATION` row is seconds, not the
   ~68 s measured above.
4. Confirm screen-ons **without** a pending fetch log no `SCREEN_ON_PAINT_DEBT` row at all.

## 7. Out of scope

- The screen-off skip itself. Not painting into pixels nobody can see is correct.
- The `ui_update_alarm` cadence. It stays the backstop for the screen-on-with-dead-process case.
- `USER_PRESENT` / the manifest half. Dead, documented, and not on this path.

# Widget stuck on layout defaults after reinstall — force full push for unbacked partials

## Symptom (2026-07-16, emulator-5556)

After installing the app, the widget header bound correctly (`74.8° -0.4`, `NWS`, gear) but the
body showed the raw `widget_weather.xml` defaults — `Today` / `--°` / `--°` over an empty area.
Data was present and renders logged success, yet the body never bound.

## Key finding: unbacked partial push in a fresh process

`--°` / `--°` / `Today` are the XML defaults, not stale data (see
[widget_defaults_mean_unbound_layout]). The reinstall spawned a new process and the framework
dropped its per-widget RemoteViews cache (it resets on reboot AND provider-package update). The
first paint in the new process was a **partial** push, which `partiallyUpdateAppWidget` is
documented to ignore until the widget has received one full `updateAppWidget` — so it was silently
dropped and the defaults remained.

Proof from `app_logs` (pid changed across the reinstall):

```
17:21:45  widget=2 caller=TEMPERATURE       push=full    pid=8146  unbackedPartial=false   (old process, good body)
          ── reinstall: pid 8146 dies, framework drops RemoteViews cache ──
17:22:49  widget=2 caller=TEMPERATURE_HEADER push=partial pid=8865  unbackedPartial=true    (dropped → placeholder)
```

## Root cause

Two paths emit that first-in-process partial:

- **TEMPERATURE uiOnly**: `GraphRepaintGate` returned `header_only_live` because `getLastGraphRender`
  (renderMs/displayedTemp) is **persisted** and `SystemClock.elapsedRealtime()` counts from boot, so
  the pre-reinstall render still looked recent and the temp was unchanged → header-only partial.
- **DAILY**: worker repaints push partial by design.

`WidgetPushDispatcher` already computed and logged `unbackedPartial=true` but was "diagnostic only"
— the force-full remediation planned in `260714-widget-partial-push-stale.md` was never implemented.

## Fix

Completes the 260714 plan. Invariant: **the first push per widget per process must be a full
`updateAppWidget` carrying a complete body**, or the framework drops it.

1. `WidgetPushDispatcher.push(..., bodyComplete = true)` — promote a complete-body unbacked partial
   to a full update (`shouldPromoteToFull = bodyComplete && isUnbackedPartial(...)`). Fixes DAILY and
   any full-body partial pusher for free. Exposes `hasFullPushedThisProcess(id)`.
2. `TemperatureViewHandler.updateHeaderCurrentTemp` passes `bodyComplete = false` — a header-only
   RemoteViews leaves the body at XML defaults, so promoting it would blank the body. It must never
   be promoted.
3. `TemperatureViewHandler` uiOnly gate is now `if (uiOnly && backedThisProcess)` — while unbacked,
   skip the header-only shortcut and render the full body (whose partial push the dispatcher then
   promotes to full).

Costs one full render per process lifetime; steady-state header-only partials resume once backed, so
the Samsung anti-flash behaviour is preserved for all but the first push per widget per process.

## Verification

- Unit tests for `shouldPromoteToFull` (complete-body unbacked → promote; header-only unbacked →
  not; backed → not; full → not). `WidgetPushDispatcherTest` + `GraphRepaintGate` tests pass.
- Live on emulator-5556: the reinstall IS the reproduction trigger. New process pid 9825's first
  push for both widgets logged `push=full ... unbackedPartial=false`, and the widget rendered the
  full 7-day forecast (Wed 84/64.3, Thu 79.5/58.9 with actual overlay, Fri–Tue). Placeholder gone.

See [widget_unbacked_partial_placeholder_after_reinstall], [widget_worker_partial_push],
[remoteviews_visibility_is_sticky].

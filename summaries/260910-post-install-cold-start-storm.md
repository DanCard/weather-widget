# Post-install cold start: background work waits for the user

**Date:** 2026-09-10
**Plan:** `performance/260910-post-install-cold-start-storm.md`

## What happened

Pixel 7 Pro after every `installDebug`: ~16 s to first paint, taps slow for over a minute. The
device's `app_logs` showed a cold (debuggable, JIT-less) process running, in the same second, the
WorkManager re-run of a forced sync the install had killed (50 s), an opportunistic fetch, and two
full cache paints of every widget — while the user's taps drained one per 8 s broadcast watchdog.

## What changed

- **`StartupCooldown`**: 30 s from process start, extended to 10 s after the last user-facing
  trigger, keyed on a user-facing trigger having been seen (a job-started process has no cooldown —
  the 2026-08-19 trap). `WeatherWidgetWorker.doWork` defers every non-UI-only run inside it to a
  single serial lane (`weather_widget_startup_deferred`, coalesced by signature), logged
  `SYNC_DEFERRED_STARTUP`.
- **`FORCED_REFRESH_SATISFIED`**: forced requests carry their request time; one whose target was
  fetched after that runs unforced. The killed-and-retried sync is the case.
- **`StartupPaintClaims`** + `PackageReplacedReceiver` painting through the `onUpdate` batch: one
  cache paint per widget at startup, on the fast path, whichever trigger arrives first.
- **`ReducedSignatureStore`** persisted (prefs): settled days stay settled across process death.

## Verification

Unit 2174/0 (`:app`), new tests 19. Pixel run 2: first paint +13.8 s, ten taps at 1.0–1.2 s
during the cooldown, sync started 10 s after the last tap and unforced, settled-day recompute
18.8 s → 0.36 s. Details and the run-1 finding that redirected the rebind are in the plan.

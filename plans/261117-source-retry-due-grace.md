# Desktop SOURCE_RETRY fires 1- and 2-min retries immediately; stale retry docs

Status: proposed, awaiting approval (2026-11-17)

## Evidence

Desktop `app_logs`, 2026-11-17 (local time):

```
00:43:57.237 SOURCE_RETRY scheduled source=OPEN_METEO code=TIMEOUT trigger=launch:source_change attempt=1/4 delayMin=1
00:43:57.296 SOURCE_RETRY fire source=OPEN_METEO charging=true screenOn=true      <- 59 ms later
00:44:14.054 SOURCE_RETRY scheduled ... trigger=source_retry attempt=2/4 delayMin=2
00:44:14.096 SOURCE_RETRY fire ...                                                <- 42 ms later
00:44:30.863 SOURCE_RETRY scheduled ... attempt=3/4 delayMin=5
```

2026-11-16 21:44:40 shows the same pattern: attempts 1 and 2 fire 250 ms and 24 ms after being armed. Attempt 3
(due 21:50:18) fired at 21:49:14, 64 s early, from the main loop after a wake. Attempts 1–3 all ran within
the same ~33 s stall (three 16.6 s timeouts in a row). A fetch started when the observations panel opened at
00:45:29 then succeeded in ~2 s. The 1/2/5/10-min spacing (`SourceRetry.DELAYS_MS`) is effectively
0/0/3/8 min on desktop.

## Root cause

Desktop decides whether a retry is due with `ForecastSchedule.DUE_GRACE_MS`
(= `ForecastHorizonCadence.DUE_GRACE_MS` = **120 s**):

- `DaemonRuntime.collectDueSourceRetries` → `DesktopSourceRetryState.isDue(sourceId, nowMs, ForecastSchedule.DUE_GRACE_MS)`
- `awaitLaunch(..., graceMs = ForecastSchedule.DUE_GRACE_MS)` (settle path, `LaunchRefreshJobs.kt`)

That grace is meant for cadences measured in hours, where "a wake that lands a moment early" is two minutes.
For a retry 60 s or 120 s ahead, `nowMs >= atMs - 120 s` is already true when it is armed, so the settle
loop fires it right away. `ForecastLoopStartTest` passes `graceMs = 5_000L` explicitly, so the tests never
use the production value. That is why they did not catch this.

Android is unaffected: `WidgetWorkScheduler.enqueueSourceRetrySync` uses WorkManager `initialDelay =
delayMs` with no grace check.

## Proposed fix (Claude proposal)

1. **Shared rule:** add `SourceRetry.DUE_GRACE_MS = 5_000L` in `:shared`, with KDoc saying why the forecast
   cadence grace does not apply (it must stay well below the shortest `DELAYS_MS` entry). Add a
   `SourceRetryTest` case asserting `DUE_GRACE_MS < DELAYS_MS.min() / 4`.
2. **Desktop call sites:** `collectDueSourceRetries` and the `awaitLaunch` default `graceMs` use
   `SourceRetry.DUE_GRACE_MS`.
3. **Main-loop sleep floor for retries:** `sleepMs = retryInMs.coerceIn(ForecastSchedule.MIN_SLEEP_MS, …)`
   would make a retry that is less than 60 s away (for example after an early wake) up to ~59 s late once
   the grace is 5 s. Use a 1 s floor when the sleep comes from a retry. This cannot spin:
   `earliestSourceRetry` returns only future retries, and any retry within the grace was already consumed on
   that wake.

## Docs and comments (#4)

- `arch/forecast-and-observation-cadence.md` § Failed-fetch retry: drop the "displayed source … or
  Settings → Refresh data (`onFailure(requested = true)`)" wording. `onFailure` has no `requested`
  parameter, and any enabled source's transient failure arms a retry (the `SourceRetry` KDoc, 2026-11-15).
  Add the 5 s due-grace rule.
- `OpenMeteoApi` companion KDoc: replace "within the 15 s budget" with the real values: HTTPS attempt
  8 s with no retry, HTTP attempt 12 s with `HttpRequestRetry`. Link to the cadence doc rather than
  repeating the numbers.
- `WidgetWorkScheduler.enqueueSourceRetrySync` KDoc: "a displayed source's failed fetch" → "a source's
  failed fetch".

## Tests

- **Integration (real SQLite + loop wiring):** a desktop test that arms a retry through
  `DesktopSourceRetryHost` on a real `DesktopWeatherDao`, then runs `awaitLaunch` in virtual time with its
  **default** grace, reading `nextRetryAtMs` through `DesktopSourceRetryState` and firing through
  `isDue`/`consume`. Assert that a 1-min retry fires at ≥ 55 s, not at 0. It must fail on the current code.
- `ForecastLoopStartTest`: add a case using the default grace with a 60 s retry (fails today: fires at 0).
- Extract the retry-sleep computation as a pure function and unit-test the floor: 20 s ahead → 20 s;
  past or none → cadence sleep.

## Verification

1. `./scripts/unit-tests.sh` (read the per-module summary).
2. `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
3. Live: on desktop, toggle to Open-Meteo with networking blocked for that host (or wait for a natural
   timeout). Confirm `SOURCE_RETRY fire` lands ≈1, 2, 5 and 10 min after each `scheduled` row.

## Out of scope

- Open-Meteo HTTPS 8 s cap vs the observed 8.6 s success: wait for `OPEN_METEO_TIMEOUT_PROBE` data.
- Evicting pooled connections after a timeout: needs logging to confirm connection reuse first.

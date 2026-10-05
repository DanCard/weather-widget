# Desktop: wake refresh stalls ~30 s on the 7-day observation-window pull

Follows `plans/260611-…`, `plans/260702-…` (resume detection, network-restore kick, warm-up
banner). Those fixed *starting* the catch-up; this is about how long the catch-up *takes*.

## Symptom

After wake the desktop shows stale data for ~50 s on roughly 3 of 8 wakes (2026-10-04),
~15–20 s on the rest.

## Evidence (2026-10-04, `weather.db` app_logs + `/tmp/weather-widget-desktop.log`)

Wake → `REFRESH_ENTER` → `REFRESH` (seconds after wake):

| Wake | start | done | 7-day window outcome |
|---|---|---|---|
| 23:26:00 | 10.6 | 49.1 | 5/5 `EOFException: Invalid chunk … ended unexpectedly` at +30 s |
| 18:39:59 | 11.4 | 50.3 | 4 ok in ~3 s, AW020 `HttpRequestTimeoutException` at +30 s |
| 14:55:05 | 10.0 | 46.2 | KPAO ok in 3 s, 4 × `Invalid chunk` at +30 s |
| 17:31:36 | 11.2 | 19.9 | 5/5 ok in ~3 s |
| 17:02:40 | 11.3 | 20.2 | 5/5 ok in ~3 s |

- The ~10 s to start is the designed hold-off (resume 15–25 s, superseded by the NM kick 3–5 s).
- Forecast (points/hourly/daily/gridpoints, incl. a 245 KB body) and the METAR batch finish by
  +2.5 s. Then `network_usage` is silent for 29–30 s.
- Failures appear only at wake. Every non-wake full refresh since daemon start (08:02) succeeded.
- Normal cost of these pulls: 1–2.5 s for ~2 MB (KNUQ 2.07 MB, KSJC 1.94 MB, curl now).
- In two of the three slow wakes, some streams finished in 3 s while the others hung. So the link
  isn't slow; individual streams stall silently and wait out `socketTimeoutMillis`/
  `requestTimeoutMillis` = 30 s.
- `HttpRequestRetry` never retried them: `.body()` reads the body after the send pipeline that
  the retry plugin wraps. Those 7-day windows were **lost**, not just slow.

## Root cause

1. `fetchObservationBundles` awaits every station's 7-day window before anything is persisted or
   published. One stalled best-effort history pull holds the forecast (in hand at +2.5 s), the
   latest readings and the current temp until the 30 s timeout fires.
2. A stalled body costs the full 30 s timeout and is not retried.
3. Unconfirmed: *why* streams stall only right after wake. Candidates are connections pooled
   before suspend (the CIO idle reaper runs on the monotonic clock, which is frozen in s2idle) or
   mt7925 Wi‑Fi state just after resume. The fix below does not depend on which one it is.

## Proposed fix (desktop only; Android's OkHttp path is unaffected by this evidence)

1. **Publish before the history tail.** Split the full refresh: persist forecast + latest/METAR
   readings and `notifyDataUpdated()` first. Then run the 7-day window pulls as a follow-up phase
   that persists and notifies again. The UI is current at about +3 s from fetch start, whatever
   the history pulls do.
2. **Bound and retry the window pull.** Give the window request its own shorter timeout (~12 s;
   the observed max is 2.5 s) and one retry on a body-read `IOException`/timeout. A stall then
   costs about 12 s in the background tail and usually recovers the data.
3. **Fresh connection pool on wake.** Recreate the `DesktopWeatherService` HTTP client in the
   resume/network kick, before the catch-up fetch, so no pre-suspend socket is reused. This is
   cheap. If stalls stop entirely, that confirms the pooled-socket hypothesis; log a
   `WAKE_CLIENT_RESET` row so it can be checked later.

## Tests

- Pure/unit: refresh with a fake service whose window call suspends forever. Assert that forecast
  + `REFRESH`/current temp are persisted and the notify fires before the window phase completes.
- Window-pull retry: fake engine (Ktor `MockEngine`) that fails the body once, then succeeds.
  Assert a single retry and that the data is stored.
- Timeout: a `MockEngine` that never completes the body. Assert failure at about the configured
  bound, not 30 s.
- Live: suspend/resume several times; expect `REFRESH` ≤ ~15 s after wake and no 30 s gap in
  `network_usage`.

## Outcome (implemented 2026-10-04, all three)

- `WeatherApiClient.fetchForecast(recentObservationsOnly)` and
  `DesktopWeatherRepository.refreshWithOutcome(deferObservationWindow = true)`: NWS pulls the
  90-min window, and `REFRESH` ends `obsWindow=deferred`. `refreshObservationWindow()` then stores
  the 7-day pull and recomputes extremes (`OBS_WINDOW_REFRESH rows=… ms=…`, WARN + `-1` on
  failure). Only the daemon's `runLaunchRefresh` FULL_FORECAST branch defers. The periodic loop
  and the UI keep the one-shot path.
- `withStallRetry` (`StallRetry.kt`): 15 s bound, one retry on a timeout or `IOException`. A
  timeout surfaces as `SocketTimeoutException`, never a cancellation. `OBS_WINDOW_RETRY` row.
- Resume and network kicks call `restartWithFreshClients` (`WAKE_CLIENT_RESET`) →
  `startFetchLoops`. The replaced client closes after `REPLACED_CLIENT_CLOSE_GRACE_MS` (60 s)
  instead of immediately.
- Tests: `StallRetryTest` (5, virtual time; a mutation leaking `TimeoutCancellationException`
  fails 2) and `DeferredObservationWindowTest` (5, real DB). Deviation: no Ktor `MockEngine` test,
  because the engine runs on real time. The helper is tested pure instead.
- Live (`nmcli networking off/on`): `REFRESH` 2.3 s after `REFRESH_ENTER`, history +2.4 s
  (1728 rows). **A real suspend/resume is still unverified.** On the next wakes, check for
  `OBS_WINDOW_RETRY` rows and whether the 30 s `network_usage` gap still appears; if it never does
  after `WAKE_CLIENT_RESET`, that supports the pooled-socket hypothesis.
- Unrelated, pre-existing on HEAD at 23:4x local: 3 failures in
  `DesktopSnapshotDisplayedRainChanceTest` / `DesktopWeatherDaoTest` (time-of-day dependent).

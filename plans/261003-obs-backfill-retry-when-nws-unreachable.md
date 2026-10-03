# Observation backfill: retry 10 s → 1 m → 4 m when NWS was unreachable

## Symptom (emulator-5554, 2026-10-03)

Hourly graph dipped to 61° (00:00–02:00) where desktop/other devices did not. The NWS blend was
`single_station` AW020 for those hours: KNUQ/KSJC/LOAC1 NWS history ended Oct 2 08:55 after a ~19 h
idle, and the repair failed:

```
03:20:48 OBS_HOURLY_BACKFILL_REQ  ... outcome=enqueued
03:21:05 OBS_HOURLY_BACKFILL_START lookbackHours=72
03:21:57 NWS_GRIDPOINT_FAIL error=HttpRequestTimeoutException ... request_timeout=30000 ms
03:21:57 OBS_HOURLY_BACKFILL_FAIL reason=no_stations
03:21:58+ OBS_HOURLY_BACKFILL_SKIP reason=cooldown   (every repaint)
```

## Root cause

- The 30-min cooldown (`HOURLY_BACKFILL_COOLDOWN_MS`) gates the three render-path callers only
  (TemperatureGraphHoursLoader, CloudCoverViewHandler, DailyHistoryBackfillCoordinator). Its purpose
  (plan 260319) is "does not enqueue on every render". That stays.
- Nothing else retries: `handleObservationBackfillWork` returns `Result.success()` regardless of
  outcome, and `stationsForLocation` maps a gridpoint/station-list exception to `emptyList()`,
  indistinguishable from a genuinely station-less site. A transient timeout therefore costs ≥30 min.

## Fix (Android `:app`)

1. **Distinguish unreachable from empty.** `RecentBackfillResult` gains `unreachable: Boolean` —
   true when the station lookup threw (gridpoint or station-list fetch failed with no cached list),
   or when every station fetch failed with a network exception. A successful empty answer stays
   `false`.
2. **Worker-owned retry, explicit schedule.** In `handleObservationBackfillWork`, if
   `result.unreachable && attempt < 3`, enqueue a copy of the request with `attempt + 1` and
   `initialDelay = RETRY_DELAYS_MS[attempt]` where `RETRY_DELAYS_MS = [10 s, 60 s, 240 s]`; log
   `OBS_HOURLY_BACKFILL_RETRY attempt=… delayMs=…`. Return `Result.success()`.
   - Not `Result.retry()`: WorkManager backoff is linear or doubling only; 10 s → 1 m → 4 m is not
     expressible.
   - Enqueued under the same unique name `WORK_NAME_OBSERVATION_BACKFILL` with
     `APPEND_OR_REPLACE` from inside the running worker, so it starts only after this run completes,
     never overlaps it, and a repaint request meanwhile is still deduplicated by `KEEP`. The
     scheduler's "no APPEND" rule is about render bursts; here exactly one successor is appended,
     at most 3 times.
   - Keeps the `CONNECTED` network constraint.
   - Attempt number carried in input data (`KEY_OBSERVATION_BACKFILL_ATTEMPT`, default 0).
   - The delay schedule is a pure function (`backfillRetryDelayMs(attempt): Long?`, null = give up).
3. **Repaint cooldown unchanged** (30 min, per widget/source/site).

## Out of scope

- Using Synoptic rows of the same station to fill NWS-blend gaps (option b).
- Daily backfill stopping after the first station that covers the dates (option c).
- Desktop: check separately whether its refresh path has the same "failure ends as success" gap.

## Tests

- Pure: `backfillRetryDelayMs(0..2)` = 10 s / 60 s / 240 s; `(3)` = null.
- `stationsForLocation` / backfill result: gridpoint exception → `unreachable=true`; empty station
  list returned successfully → `unreachable=false`; cached list on refresh failure → not unreachable.
- Worker decision: unreachable + attempt<3 → retry enqueued with next attempt/delay; unreachable +
  attempt=3 → no retry; rows fetched → no retry.
- Emulator: break network (`adb shell svc wifi disable; svc data disable`) during a backfill, re-enable,
  confirm `OBS_HOURLY_BACKFILL_RETRY attempt=1 delayMs=10000` then a successful
  `OBS_HOURLY_BACKFILL_RESULT rows>0` and the 61° dip gone.

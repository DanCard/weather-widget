# Observation backfill: test-enqueued work leak, self-sustaining loop, and serial chaining

Date: 2026-08-20
Module: `:app`

## Problem

On the emulator, temperature-actuals backfill was slow and repeatedly fetched **Austin, TX**
(30.267, −97.743) data despite the device sitting in Mountain View.

Evidence gathered from the running emulator (production `weather_database`, WorkManager's
`androidx.work.workdb`, and shared_prefs):

- **4,744** NWS observation rows filed at 30.267/−97.743, 5 Texas stations, in the *production* DB.
- A Texas station-list cache entry (`observation_stations_v4_-1331096597`) in *production*
  `weather_prefs`.
- Austin write bursts at 08-18 20:27 & 21:00, 08-20 09:57, 08-20 15:22 — aligned with instrumented
  test runs (`weather_database_test_default`, `active_weather_location_test_default.xml` and
  `migration-test-db` all stamped 2026-08-20 15:24).
- Production `active_weather_location.xml` untouched since 07-28, still Mountain View — so the
  production *preferences* were never corrupted.
- Six backfill jobs enqueued between 15:24:23 and 15:29:14, running strictly serially.
- Per-backfill duration: emulator **6–8 s**, Pixel 14–15 s, Samsung 7–24 s — the emulator is not
  intrinsically slow.

Austin appears in the repo in exactly one place:
`app/src/androidTest/.../LocationUpdaterIntegrationTest.kt` — the instrumented-test location fixture.

### Root causes

1. **Testing mode does not reach WorkManager.** `WeatherDatabase.isTestingMode()` redirects the
   database and (via `SharedPreferencesUtil`) the preference files, but WorkManager is process-wide
   and persists to `no_backup/androidx.work.workdb`. A backfill enqueued during an instrumented test
   carries its coordinates in the WorkSpec input and **outlives the test**.
   `WeatherWidgetWorker.doWork()` does guard on testing mode, but it checks when the job *runs* — by
   then the test process is gone and the flag is off, so the job executes for real against the
   production database.

2. **The backfill decision and the backfill fetch read different locations.**
   `evaluateHourlyBackfillNeed` judges coverage from the observation list the *renderer* loaded
   (scoped to the render location), while `maybeEnqueueHourlyObservationBackfill` fetches at
   `getStoredWidgetLocation(...)`. When the two disagree, the fetched rows land at site B while the
   check keeps reading site A, so `no_nws_observations` never becomes false and the request repeats.
   Every Austin run logged `reason=no_nws_observations` even though Austin already held 4,744 rows.

3. **Duplicate backfills chain instead of collapsing.**
   `enqueueRequiredObservationBackfill` uses `enqueueUniqueWork(..., APPEND_OR_REPLACE, ...)`. A
   pending job makes the next one *append*, so a burst becomes a serial queue of identical
   5-station × 72 h fetches. That is the observed slowness: the California actuals sat behind a
   queue of Texas fetches.

## Fixes

### 1. The testing-mode taint travels with the job

- New input key `WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING`, stamped at enqueue time when
  `WeatherDatabase.isTestingMode()` is true.
- `doWork()` drops any job carrying it **regardless of the current testing-mode flag**, logging
  `SYNC_SKIP_TEST_ENQUEUED`. The taint is what matters, not the state of the process that happens to
  run the job.
- Stamped in `WidgetWorkScheduler` (central for most requests) **and** in `LocationUpdater`'s two
  direct `WorkManager.enqueue*` calls, which bypass the scheduler entirely and are one of the doors
  the Austin work came through.

**Why not simply no-op every `WidgetWorkScheduler` enqueue in testing mode:** it would break
`WidgetWorkSchedulerApi30IntegrationTest`, which needs a real enqueue to reach `SUCCEEDED`, and it
would miss `LocationUpdater`'s direct enqueues. A flag that rides in the WorkSpec closes every door
and leaves enqueue behaviour observable.

### 2. Evaluate coverage at the site we are about to fetch

- `maybeEnqueueHourlyObservationBackfill` gains `observationsLat` / `observationsLon`; both call
  sites (`TemperatureStateResolver`, `DailyViewHandler`) already hold them.
- New pure helper `backfillSiteMismatchReason(fetch, obsLat, obsLon)`: when the fetch site is outside
  `LocationMatch.TOLERANCE_DEG` (±0.1°, the same box the observation query used) of the site the
  observations were loaded at, skip with `location_mismatch`.
- Rationale: the decision is only meaningful when the observations being judged could contain the
  fetch site's rows. Fetching anyway writes rows the renderer will never read — exactly how 4,744
  orphan Austin rows accumulated — and guarantees the request repeats forever.

### 3. Collapse duplicate backfill requests

- `enqueueRequiredObservationBackfill`: `APPEND_OR_REPLACE` → `KEEP`.
- Safe for the 30-minute `shouldRefreshMissingActuals` cooldown: `KEEP` drops the new request
  precisely because an equivalent one is already pending, so the work still happens.
- `WidgetWorkScheduler`'s class kdoc states the APPEND_OR_REPLACE principle for "required
  follow-ups"; it needs an explicit carve-out for this idempotent, self-describing work.

## Verification

### Unit tests

`HourlyObservationBackfillLocationTest` (extend):
- fetch site == observation site → no mismatch reason.
- fetch site within the ±0.1° box (a few blocks) → no mismatch.
- fetch site in another state (Austin vs Mountain View) → `location_mismatch`.
- boundary: exactly `TOLERANCE_DEG` away → still no mismatch (`<=`, matching the SQL `BETWEEN`).
- non-finite observation coordinates → treated as a mismatch, never a silent fetch.

`WidgetWorkSchedulerCollisionTest` (update):
- rename/repoint `new observation history repair survives an older active repair` → a second
  backfill request while one is pending is **collapsed**, leaving exactly one WorkSpec.
- an unrelated required follow-up (`WORK_NAME_ONE_TIME`, `WORK_NAME_CURRENT_TEMP`) still uses its
  existing policy — proves the carve-out is scoped to the backfill name only.

New `WorkerTestModeTaintTest`:
- input built while testing mode is on carries `KEY_ENQUEUED_IN_TESTING`.
- input built while it is off does not.
- `WorkInput.from` surfaces the flag so `doWork()` can drop on it.

### Manual

- Emulator: run the instrumented suite, then confirm no `OBS_HOURLY_BACKFILL_RUN` at a
  test-fixture location follows it, and that `SYNC_SKIP_TEST_ENQUEUED` rows appear instead.

## Follow-up (not in this change)

- The 4,744 Austin observation rows and the Texas station-cache key already in the emulator's
  production DB are pre-existing pollution; they need a targeted `DELETE`, which requires the
  user's consent (CLAUDE.md: never clear app data without it).

## Status — implemented 2026-08-20

All three fixes shipped.

### Files

- `WeatherWidgetWorker.kt` — `KEY_ENQUEUED_IN_TESTING`; `doWork()` drops tainted jobs and logs
  `SYNC_SKIP_TEST_ENQUEUED`.
- `WorkTestModeTaint.kt` (new) — `Data.Builder.tagTestModeEnqueue()`.
- `WidgetWorkScheduler.kt` — stamps all four request builders; backfill policy → `KEEP`; kdoc
  carve-out.
- `LocationUpdater.kt`, `CurrentTempUpdateScheduler.kt`, `FullSyncPipeline.kt` — stamp the direct
  `WorkManager.enqueue*` doors that bypass the scheduler.
- `HourlyObservationBackfill.kt` — `backfillSiteMismatchReason()`, the guard, and
  `observationsLat/Lon` parameters.
- `TemperatureStateResolver.kt`, `DailyViewHandler.kt` — pass the load location.

### Deviations from the plan

- **`QUANTIZE_SLACK_DEG`.** The boundary test caught a real edge the plan missed: the fetch
  coordinate is 3-dp quantized while the observation coordinate is raw, so an exact-boundary case
  lands a rounding step outside the box (37.417 + 0.1 quantizes to 37.517 — delta
  0.1000000000000014) and was rejected over a difference far below the precision either value
  carries. The comparison now allows half a write-quantum, erring toward "same site": this guard is
  meant to catch another *town*.
- **No `WorkInput` field for the taint.** `doWork()` reads `inputData` directly and returns before
  `WorkInput.from` — parsing a job that is about to be dropped is wasted work.
- **The scope-guard test moved.** `WeatherWidgetProviderEnqueuePolicyTest` already asserts policies
  per work name via mockk, which is a more direct check than queue shape; the redundant
  queue-shape version in `WidgetWorkSchedulerCollisionTest` was dropped rather than kept passing.

### Results

- `:app:testDebugUnitTest` — full suite green. New/updated: 5 site-agreement cases in
  `HourlyObservationBackfillLocationTest` (16 total), `WorkTestModeTaintTest` (4),
  `WidgetWorkSchedulerCollisionTest` (2), `WeatherWidgetProviderEnqueuePolicyTest` (9).
  The site-agreement guard was proved able to fail by neutering the comparison (2 failures).
- `:shared:test`, `:desktop:test` — green.
- **End-to-end on the emulator.** Ran `LocationUpdaterIntegrationTest` (3 passed), then inspected the
  production DB:
  - two `SYNC_SKIP_TEST_ENQUEUED` rows — `reason=location_changed` and `reason=unspecified`, i.e.
    both of `LocationUpdater`'s direct-enqueue doors, the ones a scheduler-only guard would have
    missed;
  - Austin observation rows **4,811 → 4,811**: no new Texas data;
  - backfill decisions now read `coverage_ok latest_gap_min=7 max_gap_min=10` at the real location —
    no `no_nws_observations` loop and no chain.

  The same test run before this change produced Austin `OBS_HOURLY_BACKFILL_RUN` rows and ~1,900 new
  Texas observation rows.

### Still outstanding

The 4,811 pre-existing Austin rows and the Texas station-cache key (`observation_stations_v4_-1331096597`)
remain in the emulator's production DB. They need a targeted `DELETE`; not done, pending user consent.

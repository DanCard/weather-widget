# Test work must not block a real sync

Date: 2026-11-01. Status: implemented for `WidgetWorkScheduler.enqueueFullSync` and the lookups of its names.
The other unique-work calls still share names with test mode (step 3 stopped there). On the foldable the real
sync ran about 3 minutes after the test run ended, not the 90 seconds targeted; the delay was not investigated.

## Goal

After an instrumented test run, a stale emulator fetches on its own, within about 90 seconds of the run
ending. Work queued by a test can no longer stop real work from being queued.

User's report: "I started emulator. I thought it would update. Last update was 9 am for silurian api",
then "new emulator started: hasn't updated".

## Evidence

The app's startup path (`WidgetStartupCoordinator.checkStalenessAndFetch`) already handles a stale
forecast. On a normal `installDebug` at 13:11 on `emulator-5554` it logged `SYNC_START
reason=on_update_stale` and fetched 16 seconds later.

The misses, on `emulator-5556` at 13:24 (logcat):

| Time | Event |
|---|---|
| 13:24:47.886 | PID 18846, the test process: `Full sync enqueued name=weather_widget_startup_delayed policy=KEEP reason=on_update_stale delayMs=79161` |
| 13:24:49.029 | `TestRunner: run finished: 115 tests, 0 failed` |
| 13:24:52.357 | PID 19045, the real app: `Data stale ageMinutes=181; delayed startup fetch 82142ms` |
| 13:24:52.363 | Real app enqueues the same name with `policy=KEEP` and `delayMs=82142` |
| 13:25:08.898 | `SYNC_SKIP_TEST_ENQUEUED: Dropped work enqueued under testing mode. reason=on_update_stale` |

Afterwards the foldable's newest forecast is still from 10:23, and its next wake is still about 14:21.

`SYNC_SKIP_TEST_ENQUEUED reason=on_update_stale` is in `emulator-5554`'s `app_logs` at 09:04, 09:08,
09:34, 09:52, 11:38, 13:06 and 13:16. The 13:06 row is minutes before the first report.

Who started each test run is not recorded. The 13:24 run was not started from this session.

Phones do not run instrumented tests (AGENTS.md rule 8), so they do not hit this.

## Root cause

`WorkTestModeTaint` marks work queued in test mode, because the WorkManager queue outlives the test
process, and `WeatherWidgetWorker` drops marked work when it runs. The mark is deliberate; its KDoc
explains that tests assert scheduling happened, so the work must still exist.

The defect is the interaction with unique work. A marked request still holds the production unique name
until it runs. With `KEEP`, a real request under that name is discarded, and the marked one then runs and
does nothing. The real request is lost, not delayed.

## Change

Test-mode work gets its own unique names, the way `SharedPreferencesUtil` already gives test mode its own
preference files.

1. Add one helper in `:app`, beside the mark in `WorkTestModeTaint.kt`: in test mode it returns
   `"${uniqueName}_test"`, otherwise the name unchanged.
2. Route the names through it in `WidgetWorkScheduler.enqueueFullSync`, which covers the startup sync.
3. Inventory the other `enqueueUniqueWork` calls, in `WidgetWorkScheduler`, `CurrentTempUpdateScheduler`,
   `NonPrimaryObservationScheduler`, `FullSyncPipeline`, `HourlyOnDemandWorker`, `LocationUpdater`,
   `SettingsActivity` and `HistoryPruneWorker`. Route each one that can queue from a test process and whose
   request carries the mark. If that grows well past the scheduler, stop and report before going wider.
4. Every lookup or cancel of a routed name must use the helper too, including
   `WidgetWorkScheduler.delayedPolicy`, or a lookup in test mode reads the wrong queue.
5. Keep the mark and the worker's guard unchanged: they stop a leftover test job doing real work.

Android only. Desktop has no WorkManager queue and no instrumented tests.

### Not changing

1. `d95c3ec2` stays, for plugging in with the screen already on when the wake was planned under a longer
   interval. Revert it if the live check below shows it does not work.
2. No new startup check: the existing one is correct once it is not blocked.
3. `KEEP` on the startup sync stays. It correctly merges repeated widget updates.

## Tests

1. A test that fails on today's code: queue the startup sync in test mode, leave test mode, queue it again,
   and assert a real unmarked request is pending. Prefer Robolectric with WorkManager's test initializer. It
   needs a way to set test mode and clear it, so check how `WeatherDatabase` sets that flag first.
2. A helper test: the name changes only in test mode.
3. A test that a looked-up name matches the enqueued one in test mode.
4. Existing JVM tests may read queue names while test mode is on. Check for these first: `DueWakeSchedulingTest`,
   `CurrentTempUpdateSchedulerTest`, `CurrentTempRunNowQueueIntegrationTest`, `PreUnplugFetchSchedulerTest`.
   They should use the helper rather than a copied name.

Then `./scripts/unit-tests.sh`, confirming the long tests ran, and `./scripts/emulator-tests.sh`.

## Verification

1. **The reported case:** on an emulator with a forecast older than 2 hours, run `./scripts/emulator-tests.sh`.
   Expect `on_update_stale` to run with no `SYNC_SKIP_TEST_ENQUEUED`, then a `FETCH_PLAN` and fresh
   `fetchedAt` rows, within about 90 seconds of `run finished`. A forecast is only stale on its own after 2
   hours, so this check spends real fetches and must wait until one is old enough.
2. **Normal install:** `installDebug` on a stale emulator still fetches, as at 13:11.
3. **Leftover jobs:** a job already queued under the old name when this ships still runs and is dropped once.
   The next run behaves correctly, so this needs no migration.
4. **Worker rule:** no change uses `REPLACE` on immediate work (AGENTS.md rule 6).

## Separate check: `d95c3ec2` live

`emulator-5556`'s wake is planned for about 14:21 and `emulator-5554`'s for about 17:14. The next temperature
check on each, expected near 13:35, should log `FORECAST_DUE_WAKE … trigger=charging_screen_on`. This has not
been seen on a device yet.

## Docs

1. Add a line to `arch/testing-strategy.md` that test-mode work uses separate unique names.
2. Add the startup path's behaviour to the wakes section of `arch/hourly-forecast-update-cadence.md` if it is
   not already clear there.

## Risks

1. A test that looks up a production name directly would stop finding its work. The emulator tests will show
   this.
2. `_test` work left pending stays in the WorkManager database until it runs. It runs and is dropped, as today.

## Out of scope

1. Who starts test runs, or changing the test scripts.
2. Changing the startup delay of up to about 80 seconds.
3. Reviewing why the `REFRESH` debug command forces a fetch when its doc says it only syncs stale sources.

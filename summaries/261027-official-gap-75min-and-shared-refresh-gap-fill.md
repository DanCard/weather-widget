# Official-station gap at 75 min, and Refresh runs the shared NWS gap fill on both platforms

## User Prompts

1. "Desktop: Why is cloud covers actuals so far behind current time?"
2. "Why not 60 minutes?"
3. "go ahead with 75"
4. "Can I trigger a full refresh through the UI, or should we add a way to trigger through the UI, or should we add a debug hook to desktop like debugReceiver to trigger?"
5. "yes, make Refresh run the gap fill on both platforms.  I added tomorrow.io and it triggered the backfill."
6. "Did we maximize shared code between android and desktop?"
7. "yes, do both"
8. "Should always maximize shared code.  there is no reason from a user perspective to have different code for desktop and android"
9. "write session summary to summaries/"

## Problem & Root Cause

1. **Symptom:** at 07:11 on 2026-10-27 the desktop cloud-actuals line stopped at about 01:00. The
   graph showed only a dot near 06:00 and a 93% piece at about 06:50.
2. **Evidence:**
   - The desktop was suspended from 00:40 to 06:55 (`WAKE_EVENT reason=resume:logind`).
   - The wake fetch pulled only recent readings: the NWS METAR text feed plus the Synoptic fallback
     windows. That left official rows at 00:35 KNUQ, 03:15 KNUQ and 05:53/05:55 KSJC, then 06:35 onward.
   - At 06:55:42 the gap fill logged `OBS_WINDOW_REFRESH source=NWS need=none`.
3. **Root cause:** `dayHasOfficialGap` (added in 6cb0094b) refilled a day only when official rows
   were more than **3 h** apart. The two stray wake readings split the 6 h night into gaps of
   2 h 40 m and 2 h 38 m, so the day counted as complete.
4. **No manual recovery:** Refresh could not repair the hole on either platform.
   - Desktop's Settings → Refresh pulled only a recent NWS window. The daily pass ran only from a
     launch, wake or network-restored refresh with a stale forecast (4 h cadence).
   - Android's widget Refresh on fresh data stopped at `STALE_REFRESH_SKIP fresh_data`.
5. **Divergence found during review:** the source check differed between platforms.
   - Android's sync filled when any *visible* source read NWS.
   - Desktop's wake filled only when the *displayed* source did.
   - Each platform also logged the result under its own tag.

## Changes

1. **Threshold 3 h → 75 min** (`shared/.../actuals/PastDayCoverage.kt`, `MAX_OFFICIAL_GAP_MS`).
   - 75 min is one hourly METAR cycle plus 15 min for a late report.
   - Measured on 2026-10-14 to 2026-10-25: normal days' largest combined official gap was at most
     60 min. One day hit exactly 60, so 60 min had no headroom. The trailing "up to now" check would
     also fire on any late METAR, and the daily pass has no cooldown.
2. **Shared entry `NwsGapFillRun.dailyForSources`** (`shared/.../observations/NwsGapFillRun.kt`).
   This is now the only way any trigger runs the daily pass. It owns:
   - the source check: `ActualsFeedPolicy.requiresFeed(NWS, sourcesServed, …)`, meaning any visible
     source that reads NWS, on both platforms;
   - one summary line, `NWS_DAILY_BACKFILL_RESULT reason=… need=… rows=… stations=… unreachable=…
     remaining=…`.

   Callers:
   - Android `FullSyncPipeline`: `reason=full_sync`. Its own `requiresFeed` check was removed.
   - Android's daily mode of the observation-backfill worker: `reason=user_refresh:widget`.
   - Desktop launch/wake: `reason=launch:<reason>`, and `launch_retry:<reason>` for the retry.
   - Desktop Settings → Refresh: `reason=user_refresh:settings`.
3. **Desktop Refresh** (`DesktopUiApplication.requestFullRefresh`).
   - After `repo.refresh()`, it runs `refreshObservationWindow(effectiveSources, reason)` inside the
     same `UserRefreshRunner`, and reloads the cached view when rows were stored.
   - `refreshObservationWindow` now takes `sourcesServed` and `reason`. The displayed-source check
     and the `OBS_WINDOW_REFRESH` summary are gone; only the exception log keeps that tag.
4. **Android Refresh on fresh data** (`WidgetRefreshCoordinator`, `WidgetRefreshPolicy`,
   `WidgetWorkScheduler`, `WeatherWidgetWorker`, `WorkInput`).
   - `shouldRunHistoryGapFillAfterRefresh(uiOnly, isDataStale)` is true only for a non-UI-only tap on
     fresh data. A stale tap's sync already runs the shared entry.
   - The coordinator enqueues `enqueueHistoryGapFill` on its own work name,
     `WORK_NAME_HISTORY_GAP_FILL`, with `KEEP` and a network constraint.
   - A new `KEY_OBSERVATION_BACKFILL_DAILY` mode makes the existing observation-backfill worker run
     the shared entry. It repaints from cache only if rows were stored.
   - Startup-cooldown deferral: the coalescing signature includes the daily flag, and a parked daily
     fill does not carry the `observation_backfill` tag. Before this, it could merge into a parked
     recent-pass backfill and be lost, or read as a pending recent repair.
5. **Plumbing:** `NwsObservationBackfiller`, `ObservationRepository` and `WeatherRepository`
   `.backfillNwsObservationsIfNeeded` take `sourcesServed` and `reason` and return
   `NwsGapFillRun.Result?`.
6. **Docs:** `arch/hourly-forecast-update-cadence.md` covers the 75 min rule, the shared entry and its
   check, Refresh as a trigger on both platforms, and the one result log line.

## Tests

1. **Shared `PastDayCoverageTest`:**
   - new case: stray wake readings must not hide a night asleep (the 2026-10-27 timestamps);
   - new case: one late hourly METAR is not a gap;
   - the boundary tests moved to 75 min.

   With the 3 h value restored temporarily, 3 of 13 failed.
2. **Shared `NwsGapFillRunTest`:**
   - fixtures moved from readings every 2 h to hourly readings, the shape of real station history;
   - new cases for `dailyForSources`: no visible NWS reader means no request and no log line; an NWS
     reader that is visible but not displayed runs the pass and logs the exact result line.
3. **Desktop `DeferredObservationWindowTest`:**
   - updated for the new signature and log tag;
   - new case: Open-Meteo displayed with NWS visible fills 72 rows. The old displayed-source check
     would have filled 0.
4. **Android:**
   - `WidgetRefreshPolicyTest` covers the fresh-tap rule.
   - `ObservationBackfillAttemptIntegrationTest` has two new cases: daily mode runs the daily pass,
     not the recent pass, and leaves the recent cooldown alone; a parked daily fill neither merges
     with nor reads as a parked recent backfill. With the scheduler change reverted, the second case
     failed.
   - `NwsObservationBackfillerTest` and `FullSyncPipelineUiOnlyGateTest` were updated for the new
     signature.
5. **Full JVM suite:** `./scripts/unit-tests.sh` passed 6,167 tests after the Refresh change and
   6,170 after the shared-entry change.

## Runtime Verification

1. **Desktop, 75 min rule:** adding Tomorrow.io in Settings caused a visible-sources change and
   `FULL_FORECAST`, which logged `OBS_WINDOW_REFRESH need=[2026-10-27] rows=513` (KSJC 500 rows).
   - Today then had no official gaps over 75 min.
   - The cloud line filled the night at 0% from about 01:00 to 05:00.
   - The remaining 05:00–06:45 break is real data: KSJC rows from 04:50 to 05:53 carry no sky cover.
2. **Desktop Refresh:** Settings → Refresh Data logged `OBS_WINDOW_REFRESH need=none rows=0 ms=12`.
   That was the first Refresh build, before the log tag was unified.
3. **Android Refresh** (Medium_Phone_API_36 emulator, installed with `adb -s emulator-5554`; the two
   physical phones were not touched): a fresh `debug.REFRESH` logged `STALE_REFRESH_SKIP fresh_data`,
   then `OBS_DAILY_GAP_FILL_ENQUEUE`, then `OBS_DAILY_GAP_FILL_RESULT need=none rows=0`. That was the
   first Refresh build, before the shared entry.
4. **Pending:** after the shared-entry change, rebuild desktop and reinstall on the emulator, then
   confirm both log `NWS_DAILY_BACKFILL_RESULT reason=user_refresh:…`.

## Notes

1. **Undiagnosed:** KNUQ and KPAO returned 1 row each, through the web fallback, during the
   2026-10-27 backfill, while KSJC returned 500.
2. Not committed.

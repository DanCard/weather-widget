# Daily View Fails to Load Incomplete Historical NWS Actuals — Gap-Aware Backfill Fix

## Summary
- Investigated an emulator report that historical *actual* temps weren't loading for the NWS API in
  the daily forecast view.
- Root cause (confirmed from device DB, not source-reading): a past day (`2026-05-31`) had a
  `daily_extremes` row with a **wrong high of 54°** because the emulator was powered off during that
  day's afternoon, so only overnight observations (`00:00–05:20`, 123 rows) existed and the daily
  high/low was recomputed from that truncated slice.
- The real defect: three independent "do we need to fetch data?" gates exist, and **two of them are
  presence-only** — they treat a *present-but-incomplete* past-day actual as "done." Only the hourly
  temperature graph's gate is observation-coverage (gap) aware, which is why visiting the temp screen
  healed the day but the daily view never did.
- User chose the broader scope ("Daily view + self-heal"). Implemented both:
  - **Part 1** — daily view now triggers the proven gap-aware observation backfill (the same path the
    temperature graph uses).
  - **Part 2** — the background full-fetch gate (`backfillNwsObservationsIfNeeded`) is now
    coverage-aware, so it re-fetches present-but-incomplete past days without any widget interaction.
- Verified live on emulator-5554: May 31 high **54° → 80.1°**, observation coverage **123 → 553 rows**
  (full day). User confirmed "emulator has correct info."

## User Prompts (verbatim, in order)
1. "emulator: failing to load historical actual temps for NWS api"
2. "After I went to temp screen, history actual temps did get retrieved.  So the issue is that they
   need to be retrieved from the daily forecast view, not just the hourly temperature graph."
3. (AskUserQuestion — Fix scope) "Daily view + self-heal (Recommended)"
4. "emulator has correct info"
5. "Should we fix: Invalid ID 0x00000000 lines are pre-existing Robolectric stderr noise"
6. "write a detailed session log to session-logs/ dir"

## Evidence Collected
- Devices connected: Pixel 7 Pro (`2A191FDH300PPW`), Samsung `SM-F936U1` (`RFCT71FR9NT`), two
  emulators (`emulator-5554`, `emulator-5556`). Targeted `emulator-5554` throughout (where the bug
  reproduced); used `ANDROID_SERIAL=emulator-5554` for install to avoid disturbing physical phones.
- `logcat` (emulator-5554): every recent `SYNC_START` was `uiOnly=true`/`currentOnly=true`; every
  `SYNC_PERF` line showed `backfill=0ms`. No app errors/exceptions (the only `E`/`W` lines were
  Facebook app + system services). `WIDGET_ACTUAL: date=2026-05-31 src=NWS low=49.78` — low present.
- Screenshot (`/tmp/ww_emu.jpg`): widget showed **Sun (May 31): 54.1° / 49.8°**, an obvious anomaly
  between Sat 72° and Today 82°.
- Pulled `weather_database` via `run-as` and queried with local `sqlite3`:
  - `daily_extremes` (NWS): `2026-05-31` = `high 54.06 / low 49.78`, `updatedAt 2026-06-01 11:44`
    (recomputed *today*). Neighbor `2026-05-30` = `72.0 / 53.0`, computed on its own day → correct.
  - `observations` (api='NWS'): **2026-05-31 had only 123 rows spanning `00:00–05:20`, max 55.4°**,
    all `fetchedAt 2026-05-31 05:34`. Every other day had `00:00–23:55` full-day coverage
    (380–570 rows). The 54° high was simply the warmest overnight reading.
- Confirmed the three-gate analysis by reading the code:
  - `ObservationRepository.backfillNwsObservationsIfNeeded` (full fetch) checked only whether a
    `daily_extremes` **row exists** for required dates (row present → skip).
  - `MissingDataRefreshHelper.computeMissingDataRefreshes` (daily view) checks
    `dailyActuals[date] == null` (non-null → skip).
  - `HourlyObservationBackfill.evaluateHourlyBackfillNeed` (temp graph) checks **observation gaps** →
    enqueues `backfillRecentNwsObservations`, which fetches missing obs **and recomputes**
    `daily_extremes` for affected dates (`ObservationRepository.kt` ~326-331). Only gap-aware path.

## Changes Made
- **Part 1 — daily view triggers gap-aware backfill**
  (`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`):
  - Added `maybeBackfillIncompleteHistory(...)`, called once in `updateWidget` (covers both graph and
    text render paths). It loads the recent observation window
    (`now − DEFAULT_OBSERVATION_BACKFILL_HOURS (72h)` .. `now`) via `repository.getObservationsInRange`
    and delegates to the existing `maybeEnqueueHourlyObservationBackfill(...)` — reusing its gap
    evaluation, the shared `NWS_HOURLY_HISTORY` cooldown key (so the daily view and temp screen never
    double-fetch), worker enqueue, and post-fetch recompute.
  - Extracted a pure, testable gate `shouldProbeHistoryBackfill(displaySource, centerDate, today)`:
    NWS-only and `centerDate` within `HISTORY_BACKFILL_VISIBLE_DAYS = 3` (skip when navigated past the
    NWS history horizon, to keep the daily render hot path cheap).
- **Part 2 — background full-fetch self-heals**
  (`app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`):
  - Added pure `pastDayLacksAfternoonCoverage(obsTimestampsMs, date, zone, today, daytimeHour=14)`:
    a *past* day with no NWS observation at/after local hour 14 (afternoon, when the high lands) is
    treated as incomplete. Empty days and today/future are excluded.
  - Added private `incompletelyCoveredPastDates(...)` (queries per-day obs and applies the predicate).
  - `backfillNwsObservationsIfNeeded` now backfills `missingDates + incompleteDates`, and the
    multi-station loop's completion check is **coverage-aware** (a date is satisfied only once it has a
    row AND, for past days, the refetched obs reach the afternoon) instead of row-presence-only.
- **Tests**
  - `app/src/test/.../data/repository/PastDayCoverageTest.kt` (6 cases, ShortDuration): overnight-only
    flagged; afternoon coverage / exactly-at-cutoff not flagged; empty/today/future not flagged.
  - `app/src/test/.../widget/handlers/DailyHistoryBackfillGateTest.kt` (5 cases, ShortDuration):
    NWS today/boundary probe; beyond-horizon and non-NWS skip; future navigation probes.

## Verification
- `./gradlew testDebugUnitTest` (targeted): `PastDayCoverageTest` 6/6, `DailyHistoryBackfillGateTest`
  5/5, `TemperatureViewHandlerActualsTest` (existing backfill decision tests) all pass,
  `DailyViewHandlerTest` + `ObservationRepository*` suites pass. (Existing daily tests pass
  `repository = null`, so Part 1's helper is a no-op there — no regression surface.)
- Full app compiled clean; `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug` succeeded.
- Live end-to-end (emulator-5554, which exhibited the bug): cleared logcat, sent
  `am broadcast -a com.weatherwidget.ACTION_REFRESH -p com.weatherwidget` (a **daily-view render**, no
  temp screen). Re-pulled DB:
  - `daily_extremes` NWS `2026-05-31`: **high 54.06° → 80.1°**, low 49.3°.
  - `observations` NWS `2026-05-31`: **123 rows (00:00–05:20) → 553 rows (00:00–23:55)**, max 85°.
  - Screenshot `/tmp/ww_fixed.jpg`: widget shows Sun (May 31) **80.1° / 49.3°**, bar consistent with
    neighbors. User confirmed.
- Changes not committed (left to user).

## Deferred / Open
- **`Invalid ID 0x00000000.` stderr line in `DailyViewHandlerTest`.** One benign line per test, no
  stack trace, emitted by the Robolectric/Android framework when a `RemoteViews`/resource op gets view
  ID `0` during the shadowed render. Pre-existing (present on `main` before this change), all tests
  PASS, no effect on assertions or build. Recommended leaving it (orthogonal scope, low payoff). Noted
  the one caveat: it *could* indicate a binder applying an action against a view absent in a layout
  variant (same family as `remoteviews_visibility_is_sticky`); offered a time-boxed ~15-min
  confirmation if desired. User has not yet decided.

## Notes
- Plan file: `/home/dcar/.claude/plans/happy-kindling-moler.md`.
- Memory recorded: `daily_view_incomplete_actuals_backfill` — "actual present" ≠ "actual complete";
  the three data-need gates and which one was coverage-aware; the two-part fix. Linked to
  `historical_actuals_provenance` and `nws_past_rain_measured_only`.
- Key lesson: a presence check (`row exists` / `actual != null`) silently passes for data that exists
  but is *incomplete*. The durable pattern for observation-derived extremes is **coverage-aware**
  detection (the hourly graph already had it); both the daily view and the background fetch now use it.
- Tooling reminders reaffirmed: with multiple devices connected, scope `adb` with `-s` and `gradle`
  installs with `ANDROID_SERIAL` to avoid touching physical phones; clear a stale
  `app/build/kspCaches/debug` if KSP throws `FileNotFoundException: .../symbols`; every unit test must
  declare exactly one duration `@Category`.

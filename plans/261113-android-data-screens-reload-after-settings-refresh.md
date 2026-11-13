# Android data screens do not reload after Settings → Refresh data

## Evidence (Fold 4 `RFCT70G352Y`, 2026-11-13; device DB pulled with `scripts/backup_databases.py`)

1. Timeline (`app_logs`, logcat):
   - 10:25:24 `ForecastHistoryActivity` opened; `Found 32 snapshots for 2026-11-13` (its only load).
   - 10:25:32 gear tapped → `START … SettingsActivity` (History stays on the back stack).
   - 10:25:34 `SYNC_START force=true target=all reason=settings_manual_refresh`; `FETCH_PLAN` lists all
     seven sources, `NET_FETCH_COMPLETE durationMs=2959`, `SYNC_SUCCESS Weather=140`, widgets repainted,
     `REFRESH_TOAST done=Refreshed shown` at 10:25:39.
   - 10:25:40 History back on top (`scheduleTopResumedActivityChanged onTop=true`). No further
     `ForecastHistoryActivity: Found … snapshots` line.
2. Stored data is fresh: `forecasts.lastConfirmedAt` for 2026-11-13 is 10:25:35–36 for all seven sources;
   `hourly_forecasts` newest `fetchedAt` is 10:25:35–36 for all.
3. Screenshot at 10:27: "All sources for Fri Nov 13" Age column reads `1h` (Meteo `3h`) for every source.
   Those are the pre-refresh ages (the Fold's last full fetch was 79 min earlier; `FETCH_PLAN` showed
   OPEN_METEO `every=10h/3h`). After the refresh every Age should read ~`1m`. User report: "I hit refresh
   all on fold-4 and nothing seemed to happen."

## Root cause

`ForecastHistoryActivity` loads in `onCreate` and on its own taps (day arrows, source switch, its ↻
button via `refreshViewedSource`). It has no `onResume` and no stored-data-changed hook, and its gear
button starts `SettingsActivity` on top of it. A refresh run from Settings therefore updates the
database and the widgets, but the History screen underneath keeps the rows it loaded before.

Same shape on the other screens that start Settings and have no `onResume`
(audit of `app/src/main/java/com/weatherwidget/ui/*Activity.kt`):

| Screen | Starts Settings | `onResume` |
|---|---|---|
| `ForecastHistoryActivity` | yes | no |
| `WeatherObservationsActivity` | yes | no |
| `SourceErrorDetailsActivity` | yes | no |
| `MainActivity` | yes | yes (already reloads) |

`StatisticsActivity` and `MonthsViewActivity` are reached from History and do not start Settings; out of
scope unless the audit in step 1 finds another path to a refresh.

## Proposed fix

`StoredDataVersion.process` (`:shared`) is bumped by every shared write runner (`ForecastPersistRun`,
`CurrentConditionsRun`, `NwsGapFillRun`) in the app process, and the fetch worker and the activities
share that process (both pid 15480 in the logcat).

1. `:shared`: a small `StoredDataSeen` helper (pure): holds the version a screen last loaded at;
   `markLoaded()` records `StoredDataVersion.process.current()` *before* the load starts (the rule
   `VersionedTtlCache` uses, so a write landing mid-load is not missed) and `changedSinceLoad()` says
   whether it moved. No Android types.
2. `ForecastHistoryActivity`: `markLoaded()` at the start of each load (`loadData`, which also runs
   `loadSourceComparison`, and `loadAccuracySummary`); `onResume` reloads through the same functions when
   `changedSinceLoad()`. A resume with no change reloads nothing (no flicker, no extra DB work). The first
   `onResume` after `onCreate` must not double-load.
3. `WeatherObservationsActivity`: same, with its own load entry point; `SourceErrorDetailsActivity`: reload
   its failure list the same way. Confirm during implementation that each shows data a refresh changes;
   drop the screen from the change if it does not.
4. Logging: one sparse `SCREEN_RELOAD screen=<name> reason=stored_data_changed` at INFO (not per resume).
5. Desktop parity (AGENTS.md: same behavior on both platforms): desktop windows reload on the daemon's
   `notifyDataUpdated` signal. Verify that History of Forecasts and Observations on desktop show new Age
   values after a Settings → Refresh data; if they do not, fix that in the same change.
6. Docs: a line in the living document that covers data-screen refresh (`arch/hourly-forecast-update-cadence.md`,
   "Refresh notices"/user-refresh section) that a screen reloads on return when stored data changed.

Not in scope: reloading a screen that stays on top while a background worker lands data (no
`onResume` fires); a live observer is a larger change and nobody reported it. Say so in the summary.

## Tests

1. JVM (`:shared`): `StoredDataSeen`: unchanged → no reload; bump after `markLoaded` → reload; a bump
   during a load (version read before it) → still reload; after reloading → no reload again.
2. Robolectric (`:app`, History): load, bump `StoredDataVersion.process`, `onResume` → the load runs again
   (assert via the shown snapshot count / log), no bump → it does not. Must fail on the current code.
3. Emulator (`./scripts/emulator-tests.sh`, never `connectedDebugAndroidTest`): only if a test needs real
   rendering; otherwise verify by hand below.

## Verification

1. Emulator (or the Fold): open History → gear → Settings → Refresh data → back. The Age column
   changes from the old values to ~`1m` without touching the screen; capture before/after screenshots and
   the `SCREEN_RELOAD` line. Repeat for Observations.
2. Desktop: the same flow in the desktop app (step 5).

## Questions

None blocking. If you want screens to also reload while they stay on top when a background fetch lands,
say so and I will plan the observer separately.

## Implemented 2026-11-13

1. `:shared` `StoredDataSeen` (pure): `markLoaded()` before a load reads, `changedSinceLoad()`; `LOG_TAG`/`logMessage`.
2. `ForecastHistoryActivity`: `markLoaded()` at the top of `loadData`; `onResume` reloads (`loadData` +
   `loadAccuracySummary`) and logs `SCREEN_RELOAD screen=history reason=stored_data_changed` only when
   stored data changed since the last load.
3. `SourceErrorDetailsActivity`: `markLoaded()` in `onCreate`; `onResume` calls `recreate()` when changed.
4. **Dropped: `WeatherObservationsActivity`.** Audit during implementation: it already collects Room flows
   (`observeLatestFetchedAt`, `observeLatestCurrentObservationFetchLogAt`) under `repeatOnLifecycle(STARTED)`,
   so it re-collects and reloads when it returns from Settings.
5. **Desktop: no change.** `ForecastHistoryWindow`'s load is keyed on `dataUpdateCount` (the daemon's
   data-updated signal), so it already reloads.
6. Docs: `arch/hourly-forecast-update-cadence.md` (screen-under-Settings bullet).
7. Tests: `StoredDataSeenTest` (6); `HistoryReloadOnReturnRoboTest` (open and a quiet return log nothing; a
   return after `StoredDataVersion.process.bump` reloads once; the next return reloads nothing) — fails on
   the old activity. JVM gate 6899 passed.
8. Verified on the Fold 4 (debug build installed over the existing one, data kept): History (Sat Nov 14) →
   gear → Settings → Refresh data → back: Age column `19m` → `0m` for every source, snapshots 39 → 40,
   logcat `SCREEN_RELOAD screen=history reason=stored_data_changed`. The widget's view state (switched to
   Saturday by the tap used to open History) was put back with its home button.
9. Not verified: the Source Error Details reload on a device (covered only by its existing Robolectric
   test, which still passes).

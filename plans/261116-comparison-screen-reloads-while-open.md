# Forecast Comparison does not reload when a fetch lands while it is open

## Evidence (Fold 4 `RFCT70G352Y`, 2026-11-16, logcat pid 11712)

1. Screenshot 16:01, Forecast Comparison for Tue Nov 17: "All sources" Age column reads `3h` for Google
   and Meteo and `0m` for the other five.
2. Timeline:
   - 15:55:44 Settings → Refresh data: `SETTINGS_REFRESH partition fetch=TOMORROW_IO,GOOGLE_WEATHER,
     OPEN_METEO,WEATHER_API,SILURIAN,OPEN_WEATHER_MAP skip_fresh=NWS(37s)`.
   - **15:55:45.541** back on Forecast Comparison: `Found 25 snapshots for 2026-11-17`, then
     `SCREEN_RELOAD screen=history reason=stored_data_changed`. This was the screen's last load.
   - 15:55:46 Google landed (`GOOGLE_REQUEST … status=200`, `parsed daily=10 hourly=192`).
   - 15:55:59 `FETCH_METEO_FAIL … Request timeout has expired`; `SOURCE_RETRY … delayMin=1`.
   - 15:56:59 Open-Meteo retry (`reason=source_retry`) landed: `parsed 23 dates`, `552 hourly`.
   - 15:57:03 widgets repainted (`origin=WORKER_FETCH`). There was no later `Found … snapshots` or
     `SCREEN_RELOAD` line up to 16:01.
3. So the data was fresh. The screen, which stayed in the foreground the whole time, read it 0.5 s
   before Google's rows and 75 s before Open-Meteo's retry were written.

## Root cause

`ForecastComparisonActivity` reloads in `onCreate`, on its own taps (day arrows, source button, ↻), and
in `onResume` when `StoredDataSeen.changedSinceLoad()`
(plans/261113-android-data-screens-reload-after-settings-refresh.md). Nothing watches for a write while
the screen stays resumed. The 11-13 plan left this out of scope ("a live observer is a larger change and
nobody reported it"). Today's report is that case. A Settings refresh makes it likely because the user
goes back to the screen before the slower sources finish, and a source retry lands a minute or more later.

Other screens:

| Screen | While open |
|---|---|
| Android Forecast Comparison | **stale** (this bug) |
| Android Source Error Details | stale. It `recreate()`s only in `onResume` |
| Android Weather Observations | live. It collects Room flows under `repeatOnLifecycle(STARTED)` |
| Desktop `ForecastComparisonWindow` / `ObservationsWindow` | live. Their `LaunchedEffect` is keyed on `dataUpdateCount`, bumped on every cache reload |

So this is an Android-only gap, and desktop already behaves the way we want.

## Proposed fix

1. `:shared` `StoredDataVersion`: also expose the counter as a `StateFlow<Long>` (`changes`), set inside
   `bump()`. Same process-local scope as today. The `AtomicLong` stays the source for `current()`, so
   existing callers and `VersionedTtlCache` are unchanged.
2. `:shared` `StoredDataSeen`: add `reloads(debounceMs): Flow<Unit>`. It emits once when `changes` moves
   past the version seen at `markLoaded()`, waiting for `debounceMs` of quiet first. A Refresh data writes
   about 7 sources plus actuals over several seconds, so the screen should reload once for the batch, not 7+
   times. Proposed debounce: 1.5 s. This is a pure Flow, so it can be tested in JVM with virtual time.
3. `ForecastComparisonActivity`: replace the `onResume` override with one collector:
   `lifecycleScope.launch { repeatOnLifecycle(RESUMED) { dataSeen.reloads(1500).collect { reload() } } }`.
   - On resume the StateFlow emits its current value at once. That keeps the 11-13 return-from-Settings
     behavior and still reloads nothing when nothing changed. One mechanism handles both cases.
   - It is RESUMED-scoped, so a screen sitting under Settings does no work. It catches up on return.
   - `reload()` = `loadData(...)` + `loadAccuracySummary(...)` and the existing `SCREEN_RELOAD` INFO line,
     extended with a `trigger=resume|live` field. Still one line per reload.
4. Load ordering: `loadData` / `loadSourceComparison` / `loadAccuracySummary` each start a new
   `lifecycleScope.launch` and never cancel the previous one. With live reloads, two loads can overlap
   and an older one can paint last. Keep one `Job` per loader and cancel the previous one before starting
   the next. This also fixes the existing race between a fast day-arrow tap and a slow load.
5. Scroll/selection: a live reload must not jump the user. Check during implementation that `displayData`
   keeps the scroll position, the sort column (`sourceCompareRows` sort), and the graph mode. Preserve
   whatever does not survive.
6. `SourceErrorDetailsActivity`: same collector. It currently `recreate()`s; for a live update, call its
   load function instead if that is simple. Otherwise keep `recreate()` only on the resume path and
   leave out the live path, saying so in the summary.
7. Docs: rewrite the "A screen under Settings reloads on return" bullet in
   `arch/hourly-forecast-update-cadence.md` to say these screens reload when stored data changes, both on
   return and while open (debounced). Desktop and Observations are unchanged.

Not in scope: desktop (already live), and cross-process writes (Android's worker and activities share
one process; desktop uses the daemon signal).

## Tests

1. JVM `:shared` `StoredDataSeenTest` (`runTest` virtual time):
   - no bump → `reloads` emits nothing;
   - one bump after `markLoaded` → one emission after the debounce;
   - 7 bumps 300 ms apart → exactly one emission, 1.5 s after the last;
   - a bump between `markLoaded` and the read → still emits (existing rule);
   - after `markLoaded()` again with no further bump → no emission.
2. Robolectric `HistoryReloadOnReturnRoboTest`: extend it with a case where the activity stays resumed,
   `StoredDataVersion.process.bump(...)` runs, the clock advances past the debounce, and the load runs
   again (one `SCREEN_RELOAD … trigger=live`). It must fail on the current code. The existing
   return-from-Settings cases must still pass (`trigger=resume`).
3. Robolectric ordering test for step 4: start a slow load, then a fast one. Only the fast one's rows
   are displayed.

## Verification

1. Fold 4 (installDebug over the existing build, data kept): open Forecast Comparison and tap ↻ (a
   single-source fetch) without leaving the screen. That source's Age goes to `0m` on its own within
   about 2 s, and logcat shows `SCREEN_RELOAD … trigger=live`.
2. Settings → Refresh data → back right away. The Age column updates once more after the late sources
   land, including a delayed `source_retry`. Count the `SCREEN_RELOAD` lines (one per batch).
3. Leave the screen open for 10 minutes while charging. UI-only repaints do not bump the version, but
   `CurrentConditionsRun` does, and the charging loop runs it about every 5 minutes. Expect about one
   reload per current-temp fetch. Note the time each takes (`Found … snapshots` → painted). If that churn
   shows on screen or costs more than ~1 s, narrow the trigger to forecast writes: give
   `StoredDataVersion` a separate forecast counter bumped by `ForecastPersistRun` and `NwsGapFillRun`,
   since current conditions never change this screen's rows.
4. Desktop: confirm the Forecast Comparison window updates Age during a Refresh with no code change.

## Questions

1. Debounce of 1.5 s. Is that OK, or would you rather see each source update as it lands (no debounce,
   up to about 8 reloads per Refresh data)?
2. Should the trigger start narrow, with forecast writes only (a second counter), instead of reacting to every
   stored write including the 5-minute charging current-temp fetch? I'd start broad and narrow only if
   verification step 3 shows churn.

## Decisions (user, 2026-11-16)

1. No debounce: reload on every write the screen has not loaded at.
2. Trigger stays broad (every stored write); narrow only if churn shows.

## Implemented 2026-11-16

1. `:shared` `StoredDataVersion`: backed by a `MutableStateFlow` (`changes`); `bump` = `updateAndGet { +1 }`.
2. `:shared` `StoredDataSeen.reloads()`: `RESUME` for the value present on collection, `LIVE` for later
   writes; nothing before the first load; `seen` is `@Volatile`. `logMessage(screen, trigger)`.
3. `ForecastComparisonActivity`: `onResume` replaced by `watchStoredData()` (`repeatOnLifecycle(RESUMED)`
   collecting `reloads()` → `loadData` + `loadAccuracySummary`, `SCREEN_RELOAD … trigger=<resume|live>`).
   `dataJob` / `comparisonJob` / `accuracyJob` cancel the previous load; `loadData` now rethrows
   `CancellationException` instead of logging it as an error.
4. **Source Error Details unchanged**: it reloads by `recreate()`, which on a live write (every
   charging-loop current-temp fetch) would collapse an expanded response. It still reloads on return.
5. Docs: `arch/hourly-forecast-update-cadence.md` bullet rewritten.
6. Tests: `StoredDataSeenTest` +7 (13 total). `HistoryReloadOnReturnRoboTest` +3 (live, paused, newer-load
   cancels older). Each test now destroys its activities: a resumed leftover reloaded on the next test's
   bump. On the old activity, 3 of 4 fail (live: 0 reloads; ordering: Jan 1 painted after Jan 2).
   `unit-tests.sh`: 7064 passed.
7. Fold 4 (installDebug, data kept), screen open on Mon Nov 16 with no navigation:
   - ↻ → `SCREEN_RELOAD … trigger=live` at 16:12:43.701, NWS Age `17m` → `0m`;
   - no tap: the charging-loop current-temp fetch (`CURR_FETCH_SOURCE_RESULT` 16:13:45.259) →
     `SCREEN_RELOAD … trigger=live` 16:13:45.258, `Found 38 snapshots` 9 ms later. That is the expected
     current-temp churn, one reload per 10-minute charging pass, cheap; not narrowed.
8. Not verified on device: the Pixel 7 Pro screen (installed only); desktop (no desktop code change, its
   windows already reload on `dataUpdateCount`).

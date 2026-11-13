# Android History of Forecasts did not reload after Settings → Refresh data

Plan: `plans/261113-android-data-screens-reload-after-settings-refresh.md`

## Result

Back from Settings → Refresh data, Android's History of Forecasts now reloads and shows the refreshed data.
Verified on the Fold 4: the Age column went from `19m` to `0m` for every source on return.

## Symptom and cause

1. User: "I hit refresh all on fold-4 and nothing seemed to happen."
2. The refresh did run (Fold logs, 10:25:34–39): all seven sources fetched in ~3 s, `SYNC_SUCCESS`, widgets
   repainted, "Refreshed" toast. `forecasts.lastConfirmedAt` was 10:25:35–36 for every source.
3. History of Forecasts, opened at 10:25:24, still showed Age `1h`/`3h` at 10:27. Its gear starts
   `SettingsActivity` on top of it, and it loads only in `onCreate` and on its own taps, with no `onResume`
   and no stored-data-changed hook. The refresh stored rows the screen never read.

## Changes

1. `:shared` `StoredDataSeen`: remembers the `StoredDataVersion.process` value at each load (taken before
   the load reads, so a write landing mid-load still counts), says whether stored data changed since.
2. `ForecastHistoryActivity`: `onResume` reloads only when stored data changed; logs one
   `SCREEN_RELOAD screen=history reason=stored_data_changed` INFO line per reload.
3. `SourceErrorDetailsActivity`: `recreate()` on resume when stored data changed.
4. Docs: `arch/hourly-forecast-update-cadence.md`.

## Not changed, and why

1. `WeatherObservationsActivity`: already re-collects its Room flows under `repeatOnLifecycle(STARTED)`.
2. Desktop: its History window reloads on `dataUpdateCount` from the daemon.
3. A screen that stays on top while a background fetch lands: no `onResume` fires; not reported, a live
   observer would be a separate plan.

## Tests

1. `StoredDataSeenTest` (shared, 6 cases).
2. `HistoryReloadOnReturnRoboTest`: open and a no-change return log nothing; a return after a bump reloads
   once; the next return reloads nothing. Fails on the old activity.
3. Full JVM gate: 6899 passed.
4. Device: Fold 4 flow above, with before/after screenshots and the `SCREEN_RELOAD` logcat line.

## Open

1. `SourceErrorDetailsActivity` reload not exercised on a device.
2. Not committed.

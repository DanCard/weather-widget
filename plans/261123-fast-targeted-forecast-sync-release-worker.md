# Release `FullSyncPipeline` Immediately After `paint_first` on Targeted Forecast Syncs

## Evidence

Pixel 7 Pro (`2A191FDH300PPW`) live `app_logs` on `2026-11-23 15:47:23–15:47:36` while toggling `GOOGLE_WEATHER → OPEN_METEO → SILURIAN`:

1. **`15:47:23.257`** — `GOOGLE_WEATHER` finished `paint_first` (`worker_paint_done`) and showed `"Refreshed"`.
2. **`15:47:24.079`** — User tapped to `OPEN_METEO` (`VIEWING_FORECAST ageMin=215`), queuing `target=OPEN_METEO` on `weather_widget_full_sync`.
3. **`15:47:23.4` → `15:47:30.3` (6.2 s blocked queue):** `GOOGLE_WEATHER`'s worker stayed alive running `runPostPaintBackfillsAndActuals()` (`SYNOPTIC_FETCH` 227 rows + 6-source `recomputeActuals` + `PRIOR_FORECAST_FREEZE`), holding the serial `weather_widget_full_sync` WorkManager queue so `OPEN_METEO` could not even **start** for 6.2 s.
4. **`15:47:30.7` → `15:47:31.3` (0.66 s):** `OpenMeteoApi.getForecast` answered in **661 ms**.
5. **`15:47:30.5` → `15:47:34.9` (4.4 s contention):** `GOOGLE_WEATHER`'s `post_fetch_actuals` UI repaint ran concurrently while `OPEN_METEO` was persisting its 552 hourly rows + 71 backfill rows (`FORECAST_FETCH ms=5009`), causing the user to give up waiting and toggle away to `SILURIAN`.

## Root Cause

`FullSyncPipeline.onFetchSuccess` ran `runPostPaintBackfillsAndActuals()` **inline inside the same `WeatherWidgetWorker` invocation** after `paint_first`. That caused two problems:
1. A single-source toggle/viewing forecast fetch (`viewing_forecast_stale`, `stale_on_toggle_api`, `source_retry`) triggered heavy observation backfills (`Synoptic` 227 rows, `METAR`, `NWS`) and a full 6-source `recomputeActuals` that belong to routine syncs and observation loops, not a single-source forecast toggle.
2. Keeping the worker alive for 6+ seconds after `paint_first` blocked the next source toggle in the serial `weather_widget_full_sync` queue and triggered a competing `post_fetch_actuals` repaint right on top of the next source's DB writes.

## Proposed Fix

### 1. Forecast-only fast path for targeted viewing/toggle/retry syncs (`FullSyncPipeline.kt`, `:app`)

Define a clear predicate (pure helper in `:shared` or `FullSyncPipeline` companion, unit-tested):
- `isForecastOnlyTargetedSync(input)`: true when `!input.uiOnlyRefresh && input.forceRefresh && input.targetSourceId != null` and `input.currentTempReason` is a targeted forecast fetch (`viewing_forecast_stale`, `stale_on_*`, `source_retry`, `widget_tap_to_refresh` — i.e., **not** `manual_refresh` / all-sources Settings refresh).
- On `isForecastOnlyTargetedSync`:
  1. Fetch the target source's forecast (`getWeatherData`), load snapshots + hourly (`runBackfills = false`), read cached actuals (`recompute = false`), and call `paintAll()` (`SYNC_STAGE stage=paint_first`).
  2. **Skip `runPostPaintBackfillsAndActuals()`** and return `Result.success()` **immediately**, releasing the `weather_widget_full_sync` WorkManager slot in ~1.5–2.5 s.
  - Observations and daily actuals remain owned by the observation loop (`charging_screen_on_catchup` / `charging_loop`), routine background syncs, and explicit Settings → "Refresh data" / `manual_refresh`.

### 2. Keep routine syncs and Settings "Refresh data" (`allSources` / `manual_refresh`) unchanged

- Routine background syncs (`forceRefresh == false`) and Settings → "Refresh data" (`targetSourceId == null`) still run the full observation backfills (`NWS`, `METAR`, `Synoptic`) and `recomputeActuals` as before.

### 3. Execution model (serial vs. parallel)

- Consecutive source toggles (`Google → Meteo → Silurian`) continue to run **serially** on `weather_widget_full_sync` (`APPEND_OR_REPLACE`) so two forecast writes never fight over SQLite or overwrite each other's paints—but because each worker releases immediately after `paint_first` (~1.5–2.5 s instead of 8–10 s), the queue drains ~4× faster with zero competing `post_fetch_actuals` repaint storms.

### 4. Living Documentation Updates

- `arch/forecast-and-observation-cadence.md`: update the `paint_first` note to state that targeted single-source forecast syncs (`viewing_forecast_stale`, `stale_on_*`, `source_retry`, `widget_tap_to_refresh`) paint from cached actuals and release the worker immediately without running observation backfills or full-history actuals recompute.

## Tests

1. **`:app` unit/Robolectric test:**
   - Test `FullSyncPipeline.isForecastOnlyTargetedSync(...)`: returns `true` for targeted `viewing_forecast_stale`, `stale_on_toggle_api`, `source_retry`, `widget_tap_to_refresh`; returns `false` for routine sync (`forceRefresh = false`), `uiOnlyRefresh = true`, and untargeted `manual_refresh` / Settings refresh (`targetSourceId = null`).
2. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Verification

1. On Pixel 7 Pro / Fold 4, toggle across 2–3 stale sources (`Google → Meteo → Silurian`) and inspect `app_logs`:
   - Confirm each `SYNC_START target=<SOURCE>` starts within ~1–2 s of the previous `worker_paint_done` (no 6 s `SYNOPTIC_FETCH` / `recomputeActuals` tail).
   - Confirm zero competing `post_fetch_actuals` UI repaints during source cycling.

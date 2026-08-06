# Missing "API actual" in Forecast History + desktop history-button wrong day

*2026-08-06*

User prompts:

1. "Why is API actual missing on 'history of forecasts' for yesterday on samsung. Feel free to view
   logs and or take screenshot. Add logging if that would help."
2. "also missing on emulator" / "API actual also missing on pixel." / "API actual missing on desktop"
3. "bug in desktop: when in hourly temperature graph view, and I click the button for forecast
   history, it takes me to wrong forecast history day … wednesday's hourly graph → thursday"
4. "Desktop still missing api actual for yesterday." → user refreshed → "ok I refreshed and now I
   see api actual"

## Root cause (evidence-first, per protocol)

Investigated via pulled `weather_database` from Samsung (RFCT71FR9NT), Pixel (2A191FDH300PPW) and
emulator, `app_logs`, Pixel logcat, and the desktop `weather.db`:

1. **Write-side (root):** shortly after midnight the NWS gridpoint response still carries
   yesterday's `maxTemperature` window (82.0°F) while yesterday's `minTemperature` window has
   rolled off the forecast array. `DailyActualsStore.persistNwsGridpointActuals` upserted
   `apiHighTemp = maxTemp, apiLowTemp = minTemp` unconditionally, and Room's REPLACE wrote a row
   with `apiLowTemp = null`. Identical `82.0/null` NWS rows for Aug 5 found on all three Android
   devices and on desktop, all written 00:01–00:05 local.
2. **Read-side (aggravator, Android):** `DailyViewHandler` passes the *quantized* data location
   into the history intent, so `ForecastHistoryActivity.resolveApiActual`'s nearest-fragment pick
   landed on the partial row and only *then* checked nulls — the complete legacy fragment
   (77.2/56.1, un-quantized pre-`LocationMatch.quantize` key) ~20 m away was ignored. Pixel logcat
   confirmed: `No API actual available for NWS` while a complete fragment existed. The
   fragment-consolidation migrations (47→48, 49→50) never covered `daily_history`, so legacy
   fragments persist.
3. **Backfill gap:** the ERA5 backfill (`findNwsDatesMissingApiActuals` /
   `backfillNwsApiActualsFromArchive`, and desktop's `backfillNwsApiActualsFromObservations`) only
   treated `apiHighTemp == null` as missing, so partial rows were never repaired.
4. **Desktop history-button bug:** `onOpenHistory()` carried no date and `ForecastHistoryWindow`
   always opened at `LocalDate.now()`, ignoring the hourly graph's viewed day. Android parity
   reference: `TemperatureTouchTargets.setupHistoryShortcut` passes `centerTime.toLocalDate()`.

## Fixes

1. **Read-side:** new pure `ApiActualPicker.pickNearestComplete` in `:shared`
   (`shared/.../actuals/ApiActualPicker.kt`) — the nearest fragment *with a complete api pair*
   wins; used by `ForecastHistoryActivity.resolveApiActual` (mirrors desktop's existing pick).
   Improved the "no actual" log to include fragment/partial counts.
2. **Android write-side:** `persistNwsGridpointActuals` never writes null over a stored value and
   coalesces missing api fields from same-date fragments (self-heals the quantized row from the
   legacy one on the next fetch); log line now reports `incomplete=` count.
3. **ERA5 backfill (both platforms):** null high *or* low counts as incomplete; fills only the
   missing field, preserving a real gridpoint value.
4. **Desktop write-side parity:** `DesktopWeatherRepository.persistNwsApiActuals` /
   `persistOpenMeteoApiActuals` now merge against the existing row (also stops clobbering
   computed/forecast columns through the full-row REPLACE in `upsertDailyHistory`); both made
   `internal` for testability.
5. **Desktop history navigation:** viewed hourly center date threaded through `WidgetHeader` →
   `WidgetPopup` → `Main` → `ForecastHistoryWindow` (new `initialDate` param; `targetDate` state
   keyed on `showRequestId` so each fresh open re-seeds while prev/next nav is untouched), plus a
   debug log of the seed date and `hourlyOffset`.

## Verification

1. New tests (19): `ApiActualPickerTest` (5, :shared Short), `NwsGridpointActualsStoreTest` (8,
   :app Long, real Room DB), `DesktopApiActualsMergeTest` (5, :desktop Medium), and a regression
   test in `ForecastHistoryActualsVisibilityTest` driving the activity with a partial-nearest +
   complete-legacy fragment pair (mocks assigned *after* `controller.setup()` because
   `@AndroidEntryPoint` re-injects during `onCreate`; reload triggered via the prev-day button).
2. Full `:app:testByDurationDebugUnitTest`, `:shared:testByDurationShared`,
   `:desktop:testByDurationDesktop` all green.
3. **Desktop (live):** after a manual refresh the user confirmed the API actual is visible; DB
   shows `82.0/56.1` (gridpoint high preserved, ERA5 low filled).
4. **Emulator:** row repaired to `82.0/56.1` by a natural sync on the fixed build (forced-sync
   attempts via `cmd jobscheduler run` hit stale job ids; the periodic worker ran on its own).
5. **Phones:** fixed build installed via `installDebug`; the read-side fix surfaces the existing
   complete fragment (77.2/56.1) immediately, no sync needed.

Residual note: the `82.0` high itself is what NWS's gridpoint returned for Aug 5 (a stale forecast
window); "API actual" legitimately reflects NWS's own number, while the blended "Location actual"
remains the observed-truth reference. Desktop Open-Meteo api actuals for Aug 5 fill in at the next
Open-Meteo source refresh (its last pre-midnight refresh had no past_days coverage of Aug 5).

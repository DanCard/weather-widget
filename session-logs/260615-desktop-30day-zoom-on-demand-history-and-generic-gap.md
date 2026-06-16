# Desktop 30-day hourly zoom-out, on-demand history, and the GENERIC_GAP-in-history unwind

## Summary
Started as "the desktop hourly graph only zooms out 7 days — can we increase that?" and unwound, over
many rounds of visual feedback, into: a 30-day zoom range, an on-demand history fetch with a toast,
slanted date labels, and a deep chain of **deep-history correctness bugs** — culminating in the
discovery (and the user's directive) that the `GENERIC_GAP` ('Generic') Open-Meteo filler was leaking
into *history* when it is supposed to be **future-only**.

The arc:
1. **Zoom range 30d back / 7d forward.** Raised `MAX_BACK_HOURS 144→720`, `MAX_FORWARD_HOURS 24→168`.
   The geometric zoom curve is pinned at both ends, so moving the far endpoint **rescaled the whole
   curve** — had to re-derive `DEFAULT_ZOOM_FACTOR` (0.42→0.304) to keep the default ~12h view, and
   update the stage-factor tests.
2. **On-demand history fetch + toast.** `DesktopWeatherRepository.ensureHistory()` runs in the UI
   process's own repository (no daemon IPC); a `LaunchedEffect(zoomFactor, hourlyOffset)` in
   WidgetPopup fires it when the window reaches past cached data, with a "Fetching older data…" toast.
3. **Actuals deep source = NWS station obs** (user's choice over Open-Meteo reanalysis). Parameterized
   the NWS observation window (`fetchObservationHistory`).
4. **Slanted date labels** when the multi-day footer crowds — drop the weather icon and slant −38°
   like the forecast-history x-axis. First attempt drew them off the bottom of the canvas; fixed by
   lifting the pivot baseline by the rotation's downswing (`maxLabelWidth * sin(slant)`).
5. **"Actuals line doesn't reach the left."** First cause: `ACTUALS_CONTEXT_LOOKBACK_HOURS` was a
   fixed 144h (6 days) — scaled it with `backHours`. Then the user re-reported it at deep pan: the
   real ceiling is that **NWS observations only exist ~19 days back** (the API is not an archive —
   500-row cap, ~7-day retention; our depth is *accumulated* over runtime). Added a "No NWS station
   data (API limit)" caption in the gap.
6. **Pink decimal label floating in the no-data gap.** `TemperatureExtrema.compute` built per-day
   *actual* extrema from `(0..actualEndIndex)` without checking `isActual`, so forecast-only gap
   points fell back to their forecast temp and got tagged `ACTUAL_HIGH` (pink, decimal). Fixed with an
   `isActual` gate — which then OOB-crashed the Android renderer (forecast-only widget → `-1` index),
   fixed with a `>= 0` guard in `checkRedundantPairSuppression`.
7. **"How did the forecast sprout a tenth?"** The historical forecast line beyond ~13 days was
   **Open-Meteo `Generic` data** (decimals), not the real whole-degree NWS forecast (which NWS doesn't
   retain). User directive: **GENERIC_GAP is future-only, never history.** Gated `getHourlyHistory` to
   admit `Generic` only for `dateTime > now`, removed the past-`Generic` write paths (ensureHistory +
   one-time backfill), cleaned 741 stale rows, rewrote the backfill test class to pin the new contract.
8. **"Fetching older data" toast when no older data exists.** After removing the Open-Meteo backfill,
   the on-demand fetch can only add recent obs, never older. Changed the toast to compare the oldest
   loaded timestamp before/after the probe and say **"Reached end of stored history"** when it didn't
   move back.

End state: full `:app` + `:shared` + `:desktop` unit suites green. Desktop app rebuilt and running.
Two temporary diagnostics (`ActualLineDiag`, `GapLabelDiag`) left in **on purpose** (user: "don't
remove diagnostic, there are plenty of issues remaining"). One larger rework deferred (user: "I'm
tired. Don't care.").

## Prompts (verbatim, in order)
1. `Desktop: Zoom out on hourly graphs limited to 7 days?  What do you think of trying to increase that?`
2. (AskUserQuestion back/forward span) → `30 days` back, `Grow to 7 days` forward
3. `How is data going to be gathered.  I suggest fetch data on demand.  Perhaps provide a toast message when data isn't available and saying it is being retrieved?`
4. (AskUserQuestion deep actuals) → `Extend NWS station obs too`
5. (plan approved)
6. `The dates at bottom overlap sometimes. When they overlap I suggest:\n1) remove weather icon\n2) angle the date same or similar to what is done in history of forecasts.`
7. `The slants look like they are drawn below the screen`
8. `Actuals graph line doesn't extend all the way to the left side.  Add logging if this isn't easy to diagnose.`
9. (re-sent same prompt after first fix attempt) `Actuals graph line doesn't extend all the way to the left side.  Add logging if this isn't easy to diagnose.`
10. (AskUserQuestion deep-actuals gap → asked to clarify) → `Does NWS offer historical data for this?`
11. `I'm wondering what ideas you have for putting text on the graph saying actual NWS data is not available via API?`
12. (AskUserQuestion caption style) → `Caption in the gap`, plus: `Besides adding a caption in the gap the other issue that forecast hourly history is labeled in actual temperature color and has a tenth of digit which is unusual.`
13. `I'm not sure about saying "should use whole degrees".  It is fine if it uses tenth of degree, the issue is when they were forecasted they were a whole degree, why all of a sudden is a tenth of degree available, but when originally forecasted there was no tenth of degree?`
14. `Take a screenshot.  See where the actual line ends and further back in history there is no actual line?  What is labeled?`
15. `don't remove diagnostic, there are plenty of issues remaining`
16. `Forecast hourly history has a tenth of digit.  Originally forecasted as a whole number.  How did forecast sprout a tenth?  Some forecast high temps are not labeled.`
17. `generic gap should never affect history, it is only for future when api doesn't forecast out far enough.`
18. (AskUserQuestion past-pan scope) → `I'm tired.  Don't care.  Whatever you recommend or wish to do.`
19. `3 failed. [TemperatureGraphRendererFetchDotTest ...]`
20. `When scrolling to history there is a popup "Fetching older data", but older data doesn't exist.  Maybe the popup should say: "reached end of stored history"`
21. `write a session log to session-logs/ dir`

## What was built / changed

### 1. Zoom range → 30d back / 7d forward — `DesktopGraphUtils.kt`
- `MAX_BACK_HOURS 144→720`, `MAX_FORWARD_HOURS 24→168`.
- `DEFAULT_ZOOM_FACTOR 0.42→0.304`, re-derived from the rescaled geometric curve
  (`z = ln(12/MIN)/ln(MAX/MIN)`) so the default view stays ~12h back.
- Updated `DesktopGraphZoomTest.kt`: max zoom-out (720/168) and `THREE_DAY` canonical factor (0.74→0.54).

### 2. On-demand history fetch + toast — `DesktopWeatherRepository.kt`, `DesktopWeatherService.kt`, `Main.kt`
- `ensureHistory(neededBackHours)` — mutex + depth-guarded; after the GENERIC_GAP unwind it now ONLY
  extends NWS station obs (`needsDeeperHistory` gated to NWS).
- `DesktopWeatherService.fetchObservationHistory(historyDays)` + parameterized `fetchObservationBundles`.
- `Main.kt`: `onNeedHistory` callback (runs in UI-process repository), `LaunchedEffect(zoomFactor,
  hourlyOffset)` trigger in WidgetPopup, transient toast overlay (first transient UI on desktop).
- Toast wording fix (final prompt): compares `oldestLoadedMs(forecast)` before/after the probe →
  "Reached end of stored history" when nothing older was actually pulled.

### 3. Slanted, icon-less crowded date labels — `DesktopGraphUtils.drawHourlyFooterStrip`
- `footerLabelsWouldOverlap()` detects collision (text + gap + icon footprint vs day spacing).
- When crowded: drop icons, slant −38° (`ForecastEvolutionStyle.X_LABEL_SLANT_DEG`), baseline lifted
  by `maxLabelWidth * sin(slant)` so the rotated lower-left corner stays on-canvas.

### 4. Actuals-line left extent + gap caption — `TemperatureGraph.kt`
- `contextLookbackHours = maxOf(ACTUALS_CONTEXT_LOOKBACK_HOURS, backHours + EDGE_PAD)` (was fixed 144h)
  so the pink line spans the full visible window where obs exist.
- Gap caption ("No NWS station data" / "(API limit)") centered in the no-actuals region, shown only
  when the visible left edge predates the first observation.

### 5. Pink-label-in-gap fix — `shared/.../TemperatureExtrema.kt` + `TemperatureLabelResolver.kt`
- `actualIndices = (0..actualEndIndex).filter { … && hours[it].isActual }` — forecast-only gap points
  no longer counted as actual extrema (aligns the label gate with the actual-line draw gate).
- `checkRedundantPairSuppression` guards `extrema.actualHighIndex/actualLowIndex >= 0` before indexing
  `actualLabelTemps` — fixes the OOB crash for forecast-only widgets (the 3 Android test failures).

### 6. GENERIC_GAP made future-only — `shared/.../DesktopWeatherDao.kt`, `DesktopWeatherRepository.kt`
- `getHourlyHistory`: `(source = ? OR (source = 'Generic' AND dateTime > now))` + new `nowMs` param.
- Removed the `ensureHistory` Open-Meteo→Generic write and the one-time launch backfill (both seeded
  past 'Generic'). Deleted 741 stale past-`Generic` rows from the live DB.
- Rewrote `DesktopBackfillIntegrationTest.kt` to pin the new contract (refresh never backfills
  Open-Meteo; getHourlyHistory excludes past Generic, includes future Generic).

## Key findings (data-backed)
- **NWS forecast = whole °F; NWS observations = decimal** (Celsius→F, e.g. 19°C = 66.2°F), further
  decimalized by multi-station IDW blend. So pink decimals are real obs; the suspicious *forecast*
  decimals were Open-Meteo `Generic` masquerading as history.
- **NWS observations API is not an archive**: `/observations?start&end` caps at **500 rows/response**
  and stations retain **~7 days**; paging back returns nothing. Our ~19 days of obs are *accumulated*
  over runtime, growing toward the 30-day retention cap. So NWS cannot backfill deep actuals on demand.
- **Real NWS history depth**: ~13 days of forecast snapshots + ~19 days of obs (this DB, this moment).

## Diagnostics left IN (per user request)
- `ActualLineDiag` (TemperatureGraph) — actual-line extent vs window, obs range, gap hours.
- `GapLabelDiag` (TemperatureGraph) — every placed label's `role:text@x%`.
- Both go to the desktop **autostart log file** (`~/.local/state/weather-widget/autostart-*.log`),
  NOT the `app_logs` DB table (shared `Log.i` routes to the file).

## Deferred (user: "I'm tired. Don't care.")
The graph is **forecast-curve-centric**, which breaks when panned past forecast data:
- `points.ifEmpty` shows the wrong recent dates instead of the panned-to window.
- The actual line won't render without forecast points to anchor `xAtTime`.
- Past per-day **forecast** highs go unlabeled (`forecastStartIndex = effectiveActualEndIndex` when the
  now/transition boundary is off-screen-right).
These three share one root (the now-anchored actual/forecast partition) and want a coordinated
windowing rework, not three patches. Captured in memory `desktop-zoom-curve-rescale`.

## Testing
- `:app:testDebugUnitTest`, `:shared:test`, `:desktop:test` — all green.
- New/updated: `DesktopGraphZoomTest` (720/168/0.54), `DesktopBackfillIntegrationTest` (future-only
  Generic contract), `TemperatureGraphRendererFetchDotTest` (passes after the `-1` guard).
- Visual verification via forced wide-zoom screenshots (config zoomFactor edit → relaunch → restore).

## Not committed
All changes are uncommitted working-tree edits (last commit `f60f0dfd`). No commit was requested.

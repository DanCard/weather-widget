# Future days: optional "forecast 24 h ago" bar

User request (2026-11-10): "Daily forecast view: For future forecasts, I wonder what it would look
like if we added previous 24 hour forecast bar? Maybe have a setting in settings for this."

## Evidence

**Today and past columns already draw a left "yesterday's forecast" slot**
(`PriorDayForecast`, plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md). Future
columns draw one centred bar.

- `DailyColumnBuilder.build` already fills `snapshotHigh/Low` for a future column. It uses the
  newest older fetch (`displaySnapshot`), which is not a 24 h-old pick. Nothing draws it:
  - Android `DailyBarRenderer` calls `drawSnapshotBar` only from the today and past paths.
  - Desktop `DailyForecastGraph` draws the snapshot bar only in the `isToday` and `isPast` branches.
  - `DailyAxisTemps.drawn` leaves the future snapshot out of the y-axis on purpose.
- The data is already loaded on both platforms, so no new queries or schema are needed.
  - Android `WidgetDataBundleLoader.fetchForecastSnapshots` uses `getAllForecastsInRange`, which
    returns every fetch from yesterday through the horizon.
  - Desktop `loadDailySnapshots` returns every fetch from 14 days back through the horizon.
  - Forecast rows are kept for 32 days.
- Real data (Samsung Fold backup `backups/20261110_084925_sm-f936u1_RFCT70G352Y`) compares the
  newest row with the newest row first seen more than 24 h ago, for days +1 to +7:

  | source | day | now | 24 h ago | Δ high/low |
  |---|---|---|---|---|
  | OPEN_METEO | 11-13 | 64/58 | 74/59 | −10.1 / −1.6 |
  | OPEN_METEO | 11-14 | 64/56 | 71/58 | −6.8 / −2.6 |
  | NWS | 11-13 | 67/53 | 70/51 | −3.0 / +2.0 |
  | OWM | 11-14 | 62/52 | 58/55 | +4.1 / −2.5 |
  | SILURIAN | 11-12 | 68/52 | 64/52 | +3.6 / +0.7 |

  Most other days moved by less than 1°. The last horizon day has no row old enough (NWS 11-17,
  Tomorrow.io 11-14, WeatherAPI 11-12), so it would get no prior bar.

## Proposal

### 1. Shared rule (`:shared`)

- Add `PriorDayForecast.selectAsOf(candidates, cutoffMs, …)`. It takes the newest row first seen
  before `cutoffMs = now − 24 h`, judged per side, using the same "usable" and "not collapsed"
  filters as `select`. Refactor `select`'s private `side()` so both functions share one
  implementation.
  - The anchor slides with now. Today's fixed anchors (06:00 and 16:00 on D−1) are still in the
    future for days after tomorrow, so they cannot be used here.
  - When tomorrow becomes today at midnight, its left bar switches to today's fixed anchors. That
    can move the bar by one fetch. This is accepted and documented.
- **No fallback.** If no row qualifies, the slot stays empty. A stand-in would just repeat the
  current forecast and add no information.
- **Dashed** when the pick was last confirmed more than 24 h before the cutoff
  (`PriorDayForecast.isStale`). This is the same cue today's bar uses.
- Real forecasts only. A climate-normal or GENERIC_GAP future column never gets a prior bar.
- In `DailyColumnBuilder`, add a `showFuturePriorBar: Boolean` input and pass it down from the
  setting.
  - When it is on, a future column's `snapshotHigh/Low`, `snapshotIconName`,
    `priorBarRainPercent` and `snapshotIsStale` come from the `selectAsOf` pick.
  - When it is off, they are null. This also removes the unused `displaySnapshot` fill.
  - Add a `futurePriorDrawn` flag (or derive it as `!isPast && !isToday && snapshotHigh != null`)
    so renderers branch on one value.
- `DailyAxisTemps.drawn`: count a drawn future prior bar in the y-axis.
- Add a shared geometry constant: future columns with a prior bar use the past-column width
  (`TodayColumnHighlight.PAST_TRIPLE_WIDTH_SCALE`).
  - The prior bar sits in the **left slot** (`centre − pastTripleOffset`), and the current forecast
    stays **centred**.
  - Every column then reads the same way: the left slot is always the forecast from a day earlier,
    and the centre is always the main value.
  - Temperature and rain labels stay anchored on the centre bar, so the label-placement engine
    needs no new anchor.

### 2. Rendering (both platforms)

- Android `DailyBarRenderer`: for a future column with a prior bar, call `drawSnapshotBar` at the
  left slot (barType `FUTURE_PRIOR`), then draw the centre bar at the narrower width.
- Desktop `DailyForecastGraph`: same change in the `else` branch, using `drawAdaptiveBar` with
  `snapshotBarArgb` and `standInDash`.
- Rain-label collision: `DailyForecastRainLabelRenderer` and desktop (line ~620) gate
  `snapshotBarTop` on `isToday || isPast`. Extend that gate to drawn future prior bars, so a taller
  prior bar pushes the % label up.
- Leave text mode (`textHigh/textLow`) and tap zones unchanged.

### 3. Setting (both platforms, default **off**)

- Label: **"Show forecast from 24 h ago on future days"**. Description: "Adds a narrow bar beside
  each future day showing what was forecast a day earlier."
- Android: add a `SwitchCompat` `future_prior_forecast_switch` in `activity_settings.xml`, in the
  same section as `daily_zoom_out_header_switch`. Add
  `WidgetStateManager.showFuturePriorForecast()` and its setter. The switch calls
  `repaintWidgets()`. Add strings and the 19 locale translations, because locale parity requires
  them.
- Desktop: add a `TodayOverlayToggleRow` next to "Show zoom-out button in header", and add a
  `settings.showFuturePriorForecast` field to the config. Changing it repaints.
- Thread the flag into `DailyViewLogic` (Android) and `DesktopDailyForecastModel` (desktop), and
  from there into `DailyColumnBuilder.build`.

### 4. Logging

- Add a `Log.v` per future column to the existing per-render daily trace: date, picked high/low,
  each side's `fetchedAt`, and stale. Render traces stay VERBOSE.

## Tests

- `:shared` `PriorDayForecastTest`, for `selectAsOf`:
  - the newest row first seen before now − 24 h wins;
  - a row first seen after the cutoff is ignored, and there is no fallback;
  - sides are picked independently;
  - a collapsed row is skipped;
  - a stale pick is dashed;
  - a re-stamped `lastConfirmedAt` keeps a pick fresh.
- `:shared` `DailyColumnBuilderTest`:
  - setting off: a future column has no snapshot (this fails on old code, which filled
    `displaySnapshot`);
  - setting on: values come from the 24 h-old fetch, not the newest older one. The fixture has three
    fetches at 2 h, 20 h and 30 h old, and must pick the 30 h one. The old `displaySnapshot` would
    pick the 20 h one.
  - climate-normal days get no bar.
- `DailyAxisTemps` test: a drawn future prior bar widens the axis, and an undrawn one does not.
- Update the parity fixtures (`DailyParity`) with a setting-on case.
- Robolectric `DailyBarRenderer` test: `onBarDrawn` reports a `FUTURE_PRIOR` bar at the left slot,
  and the dashed flag carries through. Geometry is asserted in dp, because Robolectric has no font
  engine.
- Desktop Compose settings test: the toggle exists and persists.

## Verification

1. Run `./scripts/unit-tests.sh`.
2. On an emulator, turn the setting on and take a screenshot of the daily view (widget page +1). Turn
   it off and check the view returns to the current look. Run the renderer logcat `FUTURE_PRIOR`
   trace.
3. In the desktop app, do the same with the toggle, and check the Open-Meteo 11-13 column shows a
   74° prior beside 64°.
4. Update `arch/ARCHITECTURE.md`, or the daily-view section that describes the triple bar, with the
   future-column rule.

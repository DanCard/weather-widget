# Blend tab — "observed" dot is forecast-extrapolated

**Date:** 2026-08-03
**Reported:** "samsung: observed temp on hourly graph doesn't seem to match station list
temperatures" — then confirmed on emulator and desktop.
**Plan:** `plans/260803-blend-extrapolation-table-tab.md`

## The bug reported

The graph's observed dot is **not** an interpolation of station readings — it is an IDW blend of
*forecast projections* of them, which is why it can read above every station in the list. Both
reported numbers were reproduced exactly from the databases before anything was changed: desktop
**65.4** (vs. max station 65.0) and Samsung **65.9** (vs. max 64.4).

Desktop @ 08:20, `personalStationWeight = 0.05` (95% discount, the default):

| station | type | km | last read | raw | fed to blend | kind | age | share |
|---|---|---|---|---|---|---|---|---|
| AW020 | PERSONAL | 2.22 | 08:20 | 65.0 | 65.00 | observed | 0m | 9.8% |
| KNUQ | OFFICIAL | 3.82 | 08:15 | 64.4 | 64.90 | extrapolated | 5m | 64.6% |
| KPAO | OFFICIAL | 6.06 | 07:47 | 64.4 | **67.05** | extrapolated | 33m | 21.6% |
| KSJC | OFFICIAL | 15.91 | 08:05 | 64.4 | 65.90 | extrapolated | 15m | 3.5% |
| LOAC1 | PERSONAL | 8.33 | 07:10 | 55.0 | 59.50 | extrapolated | 70m | 0.4% |

Three things compound, all in `ActualTemperatureSeriesBuilder`:

1. `extrapolateForward` carries a stale station to the target time by the **forecast's** change over
   the gap. With NWS ramping 63°→69° across that hour, 33-min-stale KPAO entered the blend at 67.05°
   — 2.65°F no thermometer measured.
2. `timeDecayFactor` decays only linearly over 3h, so that station still carried 82% of full weight.
3. `DEFAULT_PERSONAL_STATION_DISCOUNT = 95` pushed AW020 — nearest, and the only station reporting at
   the target minute — *below* a stale official station.

Net: **~90% of the "observed" dot's weight was forecast-extrapolated**, yet the point is still
flagged `isObservedActual = true`, because `hasObserved` is set when *any single* contributor had a
literal reading.

Second, independent divergence: the stations list's own "NWS Blended" row comes from a **different
function** — `SpatialInterpolator.interpolateIDW` (raw latest readings, 1h cohort-spread filter, no
PWS discount, no extrapolation). It read 64.8 while the graph said 65.4 in the same minute.

**Decision (user): change none of that math, and leave the PWS discount alone. Make the
extrapolation visible in-app instead.**

## What was built

A **Blend tab** — first tab and default on desktop and Android — showing the current blended point:

```
station   type   km     last read   age    raw    fed to blend   weight
AW020     P      2.24   13:30       5m     84.0   84.17 E        12.7%
KNUQ      O      3.83   13:15       20m    80.6   81.27 E        79.0%
KPAO      O      6.03   10:47       168m   71.6   80.85 E         2.4%
LOAC1     P      8.34   13:10       25m    91.0   91.84 E         0.8%
KSJC      O      15.95  13:35       0m     89.6   89.60 R         5.1%
```

- Sorted nearest-first, matching the Observations tab so the two read side by side.
- **`age`** is the staleness of that station's reading at the blended timestamp, in minutes — the
  quantity that drives the extrapolation, and the one thing `last read` alone does not make obvious.
  Minutes throughout (never `2h48m`): weight hits zero at 180 min, so a bare number stays comparable
  against that cliff. KPAO above is 168m stale and still contributing.
- One-letter codes with a legend underneath (`O`/`P` station type, `R`/`I`/`E` real / interpolated /
  extrapolated). Spelled-out words made the columns wider than the window at the enlarged font and
  every cell wrapped mid-word, destroying the alignment.
- `E` rows tinted amber; header line flags `outside station range` in red.
- Hairline rules between rows (7% white on desktop, `#1F1F22` on Android), soft enough not to compete
  with the value colouring. Android uses `TableLayout`'s own divider machinery, so there are no
  spacer views to keep in sync with the row list.
- Rows tap through to the station's NWS time-series page — same affordance as the Observations tab
  (verified: Firefox opened).
- Current point only; no history. Proportional font on both platforms, ~2x body size.

### Key design decision

The table is computed by the **same** `blendObservationSeries` call that moves the graph, via a new
opt-in `captureBreakdowns` parameter — render paths pass 0 and pay nothing. The weight arithmetic was
extracted into one `computeWeightedBlend` that both the blend and the table read from, so the "weight
share" column cannot drift from the number on screen. Re-deriving it in UI code would have created a
*third* independent implementation — precisely the defect class this tab exists to expose.

Android needed real `TableLayout` columns rather than preformatted text once a proportional font was
requested: fixed-width text cannot hold alignment without a monospace face, and real rows also
restored the per-station tap target.

## Files

- `shared/.../actuals/ActualTemperatureSeriesBuilder.kt` — `BlendContribution`, `BlendBreakdown`,
  `captureBreakdowns`, `computeWeightedBlend`, `rawTemperature` on `ResolvedStationValue`
- `shared/.../actuals/BlendTableFormatter.kt` (new) — pure row model, `LEGEND`, `renderText()`
- `desktop/.../BlendTableView.kt` (new) + `ObservationsWindow.kt` — third tab, `loadBlendTables`
- `app/.../ui/WeatherObservationsActivity.kt` + `activity_weather_observations.xml` — third tab,
  `renderBlendTable`, `blendRowView`
- `shared/.../actuals/BlendBreakdownCaptureTest.kt` (new)

## Verification

- `BlendBreakdownCaptureTest` — 9 shared tests pinning the 08:20 case: blended 65.39, each station's
  raw/resolved/weight-share, per-station `age`, `outsideStationRange`, and that capture is
  observationally inert.
- Full suites green: **622 shared**, desktop, and **1761 app** unit tests.
- Two guards caught real problems, both fixed properly rather than waived:
  - the hourly-proximity allowlist — the Android query now goes through
    `GraphDataLoader.unifyToNearestSite`, so a neighbouring site fragment cannot feed the table
    forecasts the graph never used;
  - locale parity — 5 new strings translated into all 19 locales.
- Installed on all attached devices, desktop restarted, both verified on screen against live data.

## Known gaps

- The desktop's own "NWS Blended" row in the Observations tab still comes from the separate
  `SpatialInterpolator` path, so it will keep disagreeing with the Blend tab by a few tenths.
  Unifying the two blend implementations is untouched.
- The Android table fits without scrolling on the fold's inner screen at 20sp; on a narrow phone the
  last column may still need a horizontal swipe (the view scrolls both ways).
- Counterfactuals recorded in the plan: holding the last reading flat (64.42 / 64.31) or dropping the
  PWS discount (64.98 / 64.43) each brings the dot back inside the station spread. No blend-math
  change was made.

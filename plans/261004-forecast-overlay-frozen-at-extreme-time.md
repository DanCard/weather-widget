# Past-day forecast overlay: the last forecast before the extreme happened

## Problem

The yellow past-day bar (`daily_history.forecastHighTemp/LowTemp`) keeps the **last forecast fetched
before local midnight at the END of the day** (`DailyHistoryFreeze.overlayWindowOpen`). Fetches after
the high and low have already happened keep overwriting it — those are hindcasts, not forecasts.

Oct 3 2026, Mountain View, Open-Meteo: the high came ~16:15–16:47 (KNUQ 91.4°, KPAO 93.2°), the low
~05:15–06:47. The stored overlay is 89° / 58° from the **22:25** fetch. Open-Meteo documents its past
hours as "stitching the first hours of each successive model run … initialised from real
measurements" — the response carries no marker, so the app has to draw the line itself.

## User's rule (2026-10-04)

> I find it useful when forecast is updated, even an hour before the high is reached. I don't find it
> useful when it is updated after.

So each value stays live until the thing it predicts has happened:

- **forecast high** = the last valid forecast fetched **at or before the time the actual high was
  reached**; **forecast low** likewise for the low. The two may come from different fetches —
  that is the rule, not a defect.
- **"Reached"** = the first time the blended actual line (the line the widget draws, not one station)
  comes within **0.5 °F** of the day's max (min), so a flat afternoon does not keep the forecast
  open after the heat has effectively peaked.
- **Today stays live** (unchanged): whether today's high has happened is only known afterwards.
  The rule is applied once the day is over.
- **All sources**, both platforms, through `:shared`.
- **Rain chance is out of scope** (its 8 pm / 8 am windows are a separate question).

## Design

1. **Record when the extremes happened.** `ActualsAggregator.blendDailyExtremesViaSeries` already
   builds the day's blended series to take max/min; it also returns `highAt`/`lowAt` (first point
   within `EXTREME_REACHED_TOLERANCE_F` = 0.5). New nullable `daily_history` columns
   `computedHighAt`, `computedLowAt` (epoch ms), written wherever `computedHighTemp/LowTemp` are and
   following the same freeze/merge rules. Android Room 71→72 (ADD COLUMN ×2); desktop
   add-column-if-missing.
2. **Settle past days.** New pure `ForecastOverlaySettle` in `:shared`: for a past `daily_history`
   row with a known `computedHighAt`, pick the newest valid forecast row for that (date, source,
   site) with `fetchedAt ≤ computedHighAt` and take its high; same for the low. Validity is per
   field (not a climate normal / GENERIC_GAP, value present, not high==low) — NWS drops the low from
   later batches, so requiring both would push the high pick back before dawn.
   Run from `DailyHistoryMaintenance` after every fetch over the forecast rows it already loads
   (31-day lookback) — which is also the 30-day recompute. Idempotent; reruns if late observations
   move the extreme time.
3. **No pre-extreme fetch → leave the existing value** (e.g. the site was first fetched after the
   high). Never erase a frozen value (existing monotone rule).
4. Older than the forecast table's 30-day retention: unchanged.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | Extreme time: first point within 0.5 °F of max/min; plateau; exact tie | unit |
| 2 | Settle: high from last fetch ≤ highAt; low from last fetch ≤ lowAt; different fetches allowed | unit |
| 3 | Settle: per-field validity (null low in later NWS batches; high==low; climate normal; GENERIC_GAP) | unit |
| 4 | Settle: other-site rows ignored; no pre-extreme fetch keeps existing; today untouched | unit |
| 5 | Aggregator emits highAt/lowAt from the blended series | integration (aggregator + series builder) |
| 6 | Android: Room 71→72 migration keeps rows, new columns null | integration (Room) |
| 7 | Android: actuals store → maintenance → settled overlay end to end (Oct 3 shape) | integration (Robolectric) |
| 8 | Desktop: schema add + DAO round-trip of the new columns; settle via repository maintenance | integration |

Mutation check each.

## Implementation notes

- Android must read **every** stored fetch (`getAllForecastsInRange`); `getForecastsInRange` returns
  only each day's newest batch — exactly the post-extreme hindcast. Caught by the Robolectric test.
- A day frozen by the NWS station pull before v72 has no times; the recompute's times are adopted
  (values stay frozen), so retained NWS history settles too. An existing time wins.
- The shared DDL constant is not extended (the v19 rebuild and Room 64→65 replay it); the columns are
  added by desktop v25 / Room 71→72 and, on desktop, on a brand-new database only.

## Verification (2026-10-04)

- Tests: `ForecastOverlaySettleTest` 10, `ActualsAggregatorExtremeTimeTest` 1,
  `SettlePastForecastOverlaysTest` 1 (Robolectric + Room), `DailyActualsStoreCrossSiteTest` +2,
  `DesktopSettleForecastOverlayTest` 3 (SQLite, repository, v24→v25), instrumented
  `migrate71To72` (not run here — emulator suite). Full Android unit suite 4,544 passed;
  `:shared`, `:desktop` green.
- Mutation check (run): settle ignoring fetch time; aggregator dropping times; Android recompute
  dropping times; desktop DAO dropping times; frozen rows not adopting times; Android reading only
  the newest batch — each fails its test.
- **Live desktop** (v25 applied): Oct 3 Open-Meteo 89/58 (22:25 hindcast) → **88.9/59.3** (15:47 /
  06:21 fetches; high reached 16:15, low 06:47). OpenWeatherMap's bad stored low 78 → 61.3. NWS Oct 3
  unchanged at 93/63 — correct: NWS last revised at Oct 2 13:20, before both extremes.
- **Open question:** rows with no overlay at all were filled from whatever fetch preceded the extreme,
  however old — Sep 28 Silurian got a forecast fetched Sep 14 (user abroad; no newer fetch at this
  site). A lead-time cap would leave such a day empty instead.

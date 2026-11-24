# Partial-high future days: climate-normal high, green top half, one shared row choice

Combines `261124-partial-high-days-climate-normal-top-half-neon.md` (scope, shared fill) and
`261124-owm-partial-trailing-day-climate-high-opus.md` (OWM coverage tiers, split-bar sky, Android
row-swap finding). Moves the Android-only row choice into `:shared` rather than patching it.

## Problem

Desktop, OWM, Sunday 2026-11-29 showed a high of 57 (stored 57.5) at 33.645,-111.911; every other
source has 72–75° (NWS 75/53, Google 73.8/50.2, Open-Meteo 72.0/60.4, Silurian 72.1/46.4). Same
shape at 37.417,-122.089: OWM 56.2/54.2 vs 60–65°.

Evidence (desktop DB, OWM fetches 2026-11-24 08:35, both sites):
- OWM hourly rows for Sunday stop at 07:00 local. The free `/2.5/forecast` is 40 × 3 h slots from
  fetch time, so the last date is cut off at a different hour on every fetch.
- `OpenWeatherMapApi.buildDailyForecasts` (`:shared`, lines 207–211) takes max/min over whatever
  slots fall on each date → pre-dawn "high", near-identical low, rain 5% (rain arrives later:
  NWS 81%, Open-Meteo 68% at the Mountain View site).
- Monday 11/30 has no OWM row at all.

## Current behaviour (audited)

| Step | Android | Desktop |
|---|---|---|
| Row per date | `DailyViewHandler.buildWeatherByDate` (app-only; also used by `MonthsViewActivity`): after today+2 a row missing high **or** low is **swapped for the `GENERIC_GAP` row** | display-source rows as read |
| Partial fill | `PartialForecastDays.fillPartialFutureDays` via `DailyViewLogic.shownDailyRows` (never reached for the swapped rows) | `fillPartialFutureDays` in `appendClimateNormalGaps` |
| Partial-day result | whole-climate gap row (`isSourceGapFallback`, green bar) | both values from normals, `isClimateNormal` (green clear share only) |
| NWS terminal low-only day | `DailyFutureDayResolver` recomputes last NWS date; `DailyViewLogic` `hasData` ×2 | `isTerminalLowOnlyNwsFutureDay` inside `fillPartialFutureDays` |

So the same partial row already renders differently on the two platforms, and the exemption exists
twice.

## User decisions (2026-11-24)

- A future day without a real high keeps its real low, takes the **climate-normal high**, and its
  bar's **top half is green**.
- Applies to **every** partial-high future day (OWM cut-off day, NWS terminal low-only day, any other
  source), not only a source's last day.
- Maximize `:shared`: no parallel per-platform rule.

## Fix

### 1. OWM daily coverage tiers (`:shared` `OpenWeatherMapApi.buildDailyForecasts`)

First date in the batch (today) unchanged — `PartialForecastDays.todayRow` and the same-day cutoffs
own it. For every later date, by `maxHour` = latest slot's local hour:

| `maxHour` | Covers | Row |
|---|---|---|
| < 5 | neither the morning low nor the afternoon high | omitted → climate gap row fills the date |
| 5–14 | morning low, not afternoon high | `highTemp = null`, `lowTemp = min` |
| ≥ 15 | both | unchanged |

The bounds tolerate all three 3 h UTC alignments (low slot 05/06/07, high slot 15/16/17).
Pure function `OwmDailyCoverage.classify(maxHour)` so it is unit-testable without JSON.
Logged once per batch: `OWM_PARTIAL_DAY date=… maxHour=… action=OMIT|LOW_ONLY` (`Log.d`).

A LOW_ONLY day keeps its parsed rain chance and amount (user, 2026-11-24): they may rise through the
day as later fetches cover more of it, which is accepted.

### 2. One shared row choice (`:shared` `DailyColumnSource`)

New generic `DailyColumnSource.chooseRow(rows, displaySourceId, date, today, source, high, low)`
(accessor lambdas, like `PartialForecastDays.todayRow`), moved from `buildWeatherByDate`:
- display-source row with **both** values → it;
- display-source row with a **low but no high** → it (never swapped; step 3 fills the high);
- after today+2: display-source row missing its low, or absent → complete `GENERIC_GAP` row, else the
  display-source row;
- today … today+2: display-source row only.

Android `buildWeatherByDate` becomes grouping + `chooseRow`. Desktop's read path calls the same
function over its rows (today it only appends gaps for uncovered dates, so behaviour changes only
for the partial-low swap, which now matches Android).

### 3. Partial-high fill (`:shared` `PartialForecastDays`)

- `fillPartialFutureDays`: future row with `high == null && low != null` and a normal for its
  `MonthDay` → `highTemp = normal.high`, real `lowTemp`, `isClimateNormal = false`,
  new `highIsClimateNormal = true` on `DailyForecast` (in-memory only; DB keeps `highTemp NULL` — no
  schema change, no migration).
- Rows missing only the low: unchanged (both from normals, `isClimateNormal`).
- Delete `isTerminalLowOnlyNwsFutureDay`, `futureFromNormals`'s terminal parameter, and
  `app/.../DailyFutureDayResolver.kt`. `DailyViewLogic`'s two `hasData` checks become "the filled
  shared row has a high and a low" (new `PartialForecastDays.isDrawableFuture(row)`), shared with
  desktop's equivalent check.
- No normal cached yet → the row stays low-only; the column draws as an NWS terminal day does today
  (low only) until `ClimateNormalsRun` caches and repaints.

### 4. Column + bar (`:shared` `DailyColumnBuilder`, `AdaptiveBarStrokes`, `WeatherColors`)

- `DailyColumn.highIsClimateNormal` from the shown row.
- `WeatherColors.CLIMATE_NORMAL_GREEN = 0xFF34C759`; Android `DailyBarRenderer.COLOR_GAP_FALLBACK`
  and desktop `COLOR_GAP_FALLBACK` both point at it.
- `AdaptiveBarStrokes.plan(…, climateTopHalf: Boolean = false)`: top 50 % of the ink green; the
  forecast sky (clear · cloud · rain shares) scaled into the bottom 50 %, with the existing
  `BLEND_STEPS` edge at the midpoint. The platforms already draw the returned strokes in order, so
  their only change is passing `column.highIsClimateNormal`.
- Labels: the high prints the normal with its tenth (climate values keep the tenth); the low keeps
  the provider rounding (`printsWholeDegrees` split per label in `DailyColumn`).

### 5. Stored bad rows

None deleted. Daily reads take the newest row per day, so the next OWM fetch's LOW_ONLY row (or its
omission + gap) replaces the stored 57.5 row. Verification waits for that fetch (or a user-triggered
Settings → Refresh data).

## Tests

All fail on the current code unless noted.
- `OwmDailyCoverageTest`: `maxHour` 2 → OMIT, 7 → LOW_ONLY, 14 → LOW_ONLY, 15 → FULL, 17 → FULL.
- `OpenWeatherMapApiTest`: JSON whose last date ends 07:00 → high null, low 54.4; ends 02:00 → no row;
  first date unchanged even if it ends early; all three 3 h UTC slot alignments of the zone swept
  (from the gemini plan).
- `DailyColumnSourceTest.chooseRow`: low-only row after today+2 is kept (old Android swapped it);
  high-missing-low row swapped for gap; today…+2 never gap.
- `PartialForecastDaysTest`: low-only non-terminal day (OWM) and terminal NWS day both → normal high,
  real low, `highIsClimateNormal`; low-missing row unchanged; no normal → unchanged.
- `AdaptiveBarStrokesTest`: `climateTopHalf` → green covers exactly the top half; a 40 % rain day
  keeps its blue share at 20 % of the bar.
- `DailyParity` fixture: a LOW_ONLY OWM day builds the same `DailyColumn` on both platforms.
- **Integration: `PartialHighDayContract`** (`:shared` testFixtures), same pattern as
  `ForecastPersistHostContract`: one abstract test, two halves that only open a database and run their
  platform's real read path.
  - Halves: `DesktopPartialHighDayContractTest` (`:desktop`, SQLite on `Files.createTempFile`,
    deleted in `@After`, ShortDuration) and `RoomPartialHighDayContractTest` (`:app`, Robolectric,
    `TestDatabase.create()` = in-memory Room, LongDuration). Neither touches
    `DesktopDbPaths.defaultDbPath()` or a device.
  - Abstract hooks: `open()` → `ForecastPersistRun.Host` + `storeNormals(monthlyHigh, monthlyLow)` +
    `storeGapRow(date)` (Android: a `GENERIC_GAP` row, as `ClimateGapFiller` writes; desktop: no-op) +
    `rawDaily(date, source)` (plain SQL) + `shownColumn(date, nowMs)` (desktop:
    `DesktopWeatherRepository.loadCached` → `DailyColumnBuilder`; Android: `buildWeatherByDate` →
    `DailyViewLogic.prepareGraphDayInputs`), returned as a common `(high, low, highLabel, lowLabel,
    highIsClimateNormal, isClimateNormal, precip)` — labels as each platform prints them.
  - Arrange: fixed clock 2026-11-24 08:35, zone `America/Phoenix` (no DST); OWM `/2.5/forecast`
    fixture of 40 slots in the real 3 h alignment, served by Ktor `MockEngine` (no network) to the real
    `OpenWeatherMapApi`; Nov/Dec normals 74.4/51.1 and 63.6/43.0 (the stored Scottsdale values).
  - Cases:
    1. **Cut-off Sunday** (last slot 05:00): raw row `highTemp NULL`, low = fixture min; shown column
       high = `ClimateNormals.expandMonthlyToDaily(...)[11-29].first`, real low, `highIsClimateNormal`,
       not `isClimateNormal`, rain chance as parsed; high label prints the tenth, low label whole
       degrees (`DailyColumn.printsWholeHighDegrees` / `printsWholeLowDegrees`, from the gemini plan). Fails today on both halves (raw high 57.5; Android
       also shows the gap row).
    2. **Later fetch covers the afternoon** (second persist at 14:00 reaching Sunday 17:00): newest row
       wins, real high, flag false — proves no DB cleanup is needed.
    3. **Slots end 02:00**: no OWM row for the date; whole climate column (`isClimateNormal`).
    4. **No normals cached**: low-only row stays, column draws low only, no flag.
  - Each case `println`s raw rows and the shown column (kept as diagnostics).

"Real" here means the real SQLite engine and DAO code, not the user's database: no test opens
`~/.local/share/weather-widget/weather.db` or a device DB (existing suites follow the same pattern).

## Docs

- `arch/forecast-and-observation-cadence.md` § Climate normals (future-day fallback): shared row
  choice, partial-high rule, OWM coverage tiers. Link from `arch/ARCHITECTURE.md` if it describes the
  daily row choice; `rg` `arch/` for "terminal", "low-only", "GENERIC_GAP" and dedupe.

## Verification

1. `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
2. Desktop, OWM, Scottsdale site, after the next OWM fetch: Sunday bar green top half, normal high
   label (tenth), OWM low; DB row `highTemp NULL`. Screenshot.
3. Emulator widget (NWS, and OWM if selectable): last NWS day shows normal high + green top half;
   OWM cut-off day same. Screenshot + `DailyViewLogic` logcat.

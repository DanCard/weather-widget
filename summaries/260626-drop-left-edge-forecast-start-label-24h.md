# Fix: drop the left-edge forecast START label when it duplicates a pixel-near forecast HIGH

## Issue

In the **24-hour temperature view** (emulator), the left edge stacked three labels: `75°` (forecast
daily HIGH), `74°` (actual high, pink), and `73°` (forecast **START** boundary). The `73°` START was
redundant noise — it sits ~2° below the forecast HIGH on the **same** (forecast) line, only a few
pixels away, adding nothing the `75°` already conveys. (The right-side `73.1°` is the fetch-dot
current-temp label — a separate, intentionally-drawn reserved element — and was explicitly out of
scope.)

## Root cause

`checkRedundantPairSuppression` (shared `TemperatureLabelResolver.kt`, START/END branch) already
suppresses a boundary label when a stronger label is BOTH pixel-near and value-near. From the live
`TempLabelResolver`/`TempLabelEngine` logs the pair WAS pixel-near — `START idx=0` (displayed `73`)
and `HIGH idx=28 val=75.0` are ~2h apart in a 24h span ≈ 49px, inside the 64px `REDUNDANT_PAIR_PX`
budget. The only thing keeping START alive was the **value gate**: same-series forecast targets were
compared with the strict raw `abs(diff) < 2f`, and 73-vs-75 is ~2.0 — it just missed.

(The earlier 3-day fix, [`left_edge_start_pixel_redundancy`], suppressed START via an *actual* per-day
high 0.3° away; here the redundant neighbor is a *forecast* extreme exactly 2° away.)

## Fix

One file, `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`:

- Added `private const val SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES = 2`.
- In the `START, END ->` branch, the **forecast-target** value test now compares **displayed/rounded**
  values with a `≤ 2°` tolerance:
  `abs(labelTemps[idx].roundToInt() - labelTemps[tIdx].roundToInt()) <= SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES`.
  The **actual-target** test is unchanged (raw `< 2f`).

Splitting the gate by series is the crux: forecast-vs-forecast boundary duplicates drop at ≤2° (how
the user reads "73 ≈ 75"), while forecast-vs-actual pairs — different series the user compares side by
side — keep both labels. Shared code, so Android and desktop both benefit.

### Why existing tests still pass

- `TemperatureLeftEdgeStartOrderTest` — START 64 paired with actual 66.9 (cross-series, 2.9°): actual
  gate unchanged; nearest forecast extreme (HIGH 90) is pixel-far → retained.
- 3-day `TemperatureLabelSuppressionTest` — main case suppresses via the *actual* per-day high
  (unchanged path); control START 71 vs forecast HIGH 75 is a rounded diff of 4 > 2 → still retained.

## Tests

Added `twentyFourHourForecastStartHours(highMinusStart)` + two cases to
`TemperatureLabelSuppressionTest.kt` (25 hourly points, `widthPx=584`, actual line 13° below the
forecast so only the forecast path is exercised):
- START 73, 2° under a pixel-near forecast HIGH 75 → **START suppressed**.
- START 72, 3° under the HIGH → **START retained** (proves the gate is `≤ 2`, not open-ended).

## Verification

- `./gradlew :shared:test --tests "com.weatherwidget.shared.graph.*"` — new cases pass; existing
  left-edge/pairing/suppression tests stay green.
- `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`, forced a cold start, screenshotted the 24h
  view: the left edge now shows `75°` + `74°` only — the `73°` START is gone. Forecast HIGH/LOW and
  the actual labels are intact.

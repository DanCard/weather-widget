# Per-day actual high/low labels on the hourly temperature graph

## Context

On the emulator's 3-day hourly graph view, **today's actual low is not labeled**. Confirmed
with live logs + screenshot (not just code reading):

- The pink actual line spans Tue→Thu. The only actual-low label rendered is **55.6°** on the
  Tue/Wed overnight valley (the globally coldest point).
- Today's (Thu 11) overnight valley shows only the **gray forecast "63°"** — no pink actual low.
- Logs: `ACTUAL_EXTREMA highIdx=349 lowIdx=227 actualIndicesRange=0..576`, i.e. **one** actual
  high and **one** actual low for the entire multi-day actual region.

**Root cause** — `TemperatureExtrema.compute()` (`shared/.../graph/TemperatureExtrema.kt:52-53`)
computes a single global `actualHighIndex` / `actualLowIndex` over the whole actual region
`0..actualEndIndex`. In a multi-day view that region contains several daily valleys/peaks, but
only the single global coldest/warmest gets the `ACTUAL_LOW`/`ACTUAL_HIGH` role. The forecast
curve already gets per-day labels for free via `significantLocalExtrema` (prominence-filtered →
`LOCAL` role); the actual series has no equivalent.

(Today's actual *high* currently *looks* labeled — 92.1° pink — but that's coincidental: it's the
`ACTUAL_END` "current temp" label at NOW, not a per-day actual high.)

**Intended outcome (user-chosen scope):** label **each visible day's** actual low AND actual high
in pink, bringing the actual series to parity with the forecast series. Showing both a gray
forecast low and a pink actual low at the same valley is the existing, desired design (see the
Tue/Wed valley: 55° gray + 55.6° pink).

## Changes (all in `:shared`)

### 1. `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`
- Add two fields to `ExtremaIndices`: `actualDailyHighIndices: List<Int>`, `actualDailyLowIndices: List<Int>`.
- In `compute()`, after `actualIndices` is built, group by calendar day and take per-day extrema:
  ```kotlin
  val actualByDay = actualIndices.groupBy { hours[it].dateTime.toLocalDate() }
  val actualDailyHighIndices = actualByDay.values.mapNotNull { d -> d.maxByOrNull { actualLabelTemps[it] } }.sorted()
  val actualDailyLowIndices  = actualByDay.values.mapNotNull { d -> d.minByOrNull { actualLabelTemps[it] } }.sorted()
  ```
  Keep the existing global `actualHighIndex`/`actualLowIndex` (still used by redundancy checks).
  Add a `Log.d(TAG, "ACTUAL_DAILY ...")` line mirroring the existing `ACTUAL_EXTREMA` log.

### 2. `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
- `buildPotentialAnchors()` (~line 331-332): replace the two single-index actual anchors with a
  `forEach` over `actualDailyHighIndices` / `actualDailyLowIndices`.
- `resolveExtremaRole()` (~line 314-315): change the single-index equality checks to membership:
  `idx in extrema.actualDailyHighIndices -> ACTUAL_HIGH`, `idx in extrema.actualDailyLowIndices -> ACTUAL_LOW`.
  Keep them AFTER the `dailyHigh`/`dailyLow`/`START`/`END` cases (so the global daily extreme still
  wins its index; the coincidence is handled by step 3).
- Generalize the coincident-injection so a per-day actual extreme that lands on the **same index**
  as the global daily HIGH/LOW (which wins in `resolveExtremaRole`) still gets its own pink label:
  - Rename/loop `addCoincidentActualHigh()` to iterate `actualDailyHighIndices`.
  - Add a mirrored `addCoincidentActualLow()` iterating `actualDailyLowIndices` against a
    `FORECAST_LOW_ROLES = {LOW, FORECAST_LOW, PAST_FORECAST_LOW}` set; same "only when the
    formatted values differ" guard. Call it next to the high one (~line 223).

No placement-engine changes: `TemperatureLabelEngine` already styles/stacks any number of
`ACTUAL_HIGH`/`ACTUAL_LOW` anchors (pink `#FF3366`), and per-day actual anchors are non-`LOCAL`
explicit anchors so they're immovable through `filterDenseLabelCandidates`.

### 3. Tests — `shared/src/test/.../graph/TemperatureLabelSuppressionTest.kt`
- Add a multi-day test: actual region covering two days with two distinct overnight valleys (e.g.
  day-1 low colder than day-2 low); assert an `ACTUAL_LOW` candidate exists at **each** day's
  minimum index (the regression: previously only the global-coldest day's low appeared).
- Mirror for two daily actual highs.
- Verify existing single-day tests (`ACTUAL_HIGH/LOW retained near daily high/low`, coincident-high
  dedup) still pass — the per-day path must collapse to the existing behavior when there's one day.

## Verification

1. Unit tests:
   `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.TemperatureLabelSuppressionTest"`
   (and the full `:shared` suite to catch `LogTest` etc.).
2. Build + install: `./gradlew installDebug`.
3. On the emulator (`emulator-5554`), trigger a widget redraw (ACTION_REFRESH broadcast) and
   capture: `adb -s emulator-5554 exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`.
   Confirm today's overnight valley now shows a pink actual-low label alongside the gray forecast low.
4. Check logs: `adb -s emulator-5554 logcat -d | grep -E "ACTUAL_DAILY|LabelAccepted: role=ACTUAL"`
   — expect multiple `ACTUAL_LOW` / `ACTUAL_HIGH` accepted lines, one per visible day.

## Notes / risk
- Pure `:shared` change; desktop has its own simpler label reimpl and doesn't construct
  `ExtremaIndices`, so it's unaffected (the only constructor is `TemperatureExtrema.compute`).
- Keep the temporary `Log.d` breadcrumbs for a few days of on-device monitoring, then remove
  (consistent with prior label-pipeline cleanups).

# Today Column: Track Actual High/Low After Time Cutoffs

## Context

In the **daily forecast view**, the "today" column shows a triple bar — yesterday's
snapshot (yellow), the observed/mercury bar (red, with a ghost line to the peak so far),
and the full‑day API forecast (blue dashed). The two prominent **numbers** above/below the
column are currently *blends* of forecast and actual:

- **High number** = `effectiveHighForLabel()` → `max(observed, forecastHigh, ghost)`
- **Low number**  = `min(observed low, forecastLow [, snapshotLow on desktop])`

Because of these blends, the forecast value keeps "winning" the headline even after the
real extreme for the day has already occurred. By **9am** the overnight low is essentially
locked in, and by **5pm** the daytime high is essentially locked in — so continuing to
show the forecast number is misleading. The user wants the today column to **track the
actual** once that extreme is settled:

1. **After 9am** → low number tracks the **actual** observed low (not forecast low).
2. **After 5pm** → high number tracks the **actual** observed high (not forecast high).

### Decisions (confirmed with user)
- **Keep the blue dashed forecast bar** for visual comparison — only the prominent
  high/low **number** retargets to actual. Bar geometry is untouched.
- **Fall back to forecast** when no actual observation exists yet at the cutoff (so the
  column is never blank); switch to actual as soon as observations arrive.
- Cutoffs are **local clock time** (device-local), only applied to the **today** column.

## Approach

Put the gate in the **shared** `DailyDayValueResolver` (used by both Android and desktop)
so the rule is defined once. Add a local‑hour parameter; past the cutoff, drop the forecast
contribution for that extreme unless no actual exists.

### 1. Shared resolver — the heart of the change
File: `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyDayValueResolver.kt`

- Add constants: `ACTUAL_LOW_CUTOFF_HOUR = 9`, `ACTUAL_HIGH_CUTOFF_HOUR = 17`.
- Extend `effectiveHighForLabel(...)` with `nowHour: Int? = null`:
  - non‑today → unchanged (`solidHigh`).
  - today, `nowHour >= 17` **and** an actual high exists (`max(solidHigh, ghostHigh)` non‑null)
    → return that actual high (forecast excluded).
  - otherwise → current behavior `max(solidHigh, forecastHigh, ghostHigh)` (fallback path
    covers "no actual yet").
- Add a mirror `effectiveLowForLabel(isToday, solidLow, forecastLow, nowHour: Int? = null)`:
  - today, `nowHour >= 9` **and** `solidLow != null` → return `solidLow` (forecast excluded).
  - otherwise → `min(solidLow, forecastLow)` (current behavior / fallback).

Both functions are pure; the `nowHour == null` default preserves every existing caller and
test until they opt in.

### 2. Android call sites
- `app/.../widget/handlers/DailyViewLogic.kt` (~line 425): replace
  `bottomStackLow = min(solidLineLow, dashedLineLow)` with
  `DailyDayValueResolver.effectiveLowForLabel(isToday=true, solidLineLow, dashedLineLow, nowHour)`.
  `now` (a `LocalDateTime`) is already in scope — derive `nowHour = now.hour`.
- `app/.../widget/DailyForecastGraphRenderer.kt`:
  - Add `nowHour: Int? = null` to `DayData` (set from `DailyViewLogic` for the today row).
  - `DayData.effectiveHigh()` (line 196) passes `nowHour` to `effectiveHighForLabel`.
  - The high number/anchor (lines 833, 835, 976) and low number (line 679 →
    `resolveBottomStackLow`) then flow from the gated values automatically. The dashed
    forecast bar (lines 933–952) is **not** gated — it still draws `dashedLineHigh..dashedLineLow`.

### 3. Desktop call sites (parity)
- `desktop/.../DesktopDailyForecastModel.kt`: thread the local hour into `DesktopDailyDay`
  (add `nowHour: Int`; `build()`/`buildDay()` already have `now`).
- `desktop/.../DailyForecastGraph.kt`:
  - High (line 200): pass `nowHour` to the shared `effectiveHighForLabel`.
  - Low (line 159): replace the inline `min(solidLow, forecastLow, snapshotLow)` with
    `effectiveLowForLabel(isToday=true, solidLow, forecastLow = min(forecastLow, snapshotLow), nowHour)`
    — folding `snapshotLow` into the comparison candidate preserves desktop's current
    pre‑cutoff value while reusing the shared gate.

## Files to modify
- `shared/.../util/DailyDayValueResolver.kt` — gate + new low resolver (core)
- `app/.../widget/handlers/DailyViewLogic.kt` — gated `bottomStackLow`, pass `nowHour`
- `app/.../widget/DailyForecastGraphRenderer.kt` — `DayData.nowHour`, gated `effectiveHigh()`
- `desktop/.../DesktopDailyForecastModel.kt` — `DesktopDailyDay.nowHour`
- `desktop/.../DailyForecastGraph.kt` — gated high + low numbers

## Tests
- `shared/.../util/DailyDayValueResolverTest.kt` — add cases:
  - before 9am: low = `min(actual, forecast)`; after 9am: low = actual even when forecast is lower.
  - before 5pm: high = `max(actual, forecast, ghost)`; after 5pm: high = actual even when forecast is higher.
  - fallback: past cutoff but no actual → forecast still used (not null).
  - `nowHour == null` → unchanged legacy behavior.
- Run: `./gradlew testDebugUnitTest --tests "*DailyDayValueResolverTest*"` and the existing
  `DailyActualsEstimator`/renderer suites to confirm no regressions.

## Verification (end‑to‑end)
1. Build + install: `./gradlew installDebug`; add the daily‑view widget.
2. Use a device whose actual high underran the forecast (or temporarily lower the cutoff
   constants for a manual check) and confirm **after 5pm** the today high number shows the
   observed peak while the blue dashed forecast bar is still drawn above it.
3. Confirm **after 9am** the today low number shows the observed low; the dashed bar remains.
4. Pull a screenshot per CLAUDE.md (`screencap -p` → `convert` to JPG) and eyeball that the
   retargeted number doesn't collide awkwardly with the still‑drawn forecast bar.
5. Desktop parity: `scripts/buildStart.sh`, open the daily view, confirm identical behavior.
</content>

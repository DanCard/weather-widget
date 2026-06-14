# Daily view: dual actual/forecast high label for past days

## Context

In the **daily forecast view**, each past day already draws two bars: the **actual**
observed high/low (hot-pink `OBSERVED` bar) and the **forecast** that was predicted for
that day (the offset overlay bar — adaptive color on Android, yellow on desktop). But only
**one** high-temperature *text label* is drawn, so when the forecast missed badly (e.g.
yesterday's forecast was 8° off) the user can see two bars but only one number.

Goal: when a past day's forecast high differs enough from the actual high — **and there is
room to draw both labels without too much overlap** — draw **both** high-temp labels:
- **Actual high** in the thermostat color (`OBSERVED` hot pink `#FF3366`).
- **Forecast high** in the **same color as the forecast bar** it labels (adaptive forecast
  color on Android, yellow on desktop).

Two font-size refinements ride along:
- When showing **two** high labels for a past day, shrink that label font a bit (~0.85×).
- Whenever **any** daily-view temp label shows **tenths** (a decimal, e.g. `72.4°`),
  auto-shrink that label by 10%.

Slight overlap is acceptable. This is a labeling/rendering change only — the actual and
forecast highs are already available at the render layer on both platforms; no data
plumbing changes are needed.

## Decisions (from user)
- **Trigger = room-based** with a meaningfulness floor: show both labels when
  `|actualHigh − forecastHigh| ≥ 5°` **and** the two label boxes don't overlap too much
  (allow slight overlap). The room test is the primary gate; 5° is a floor so we never
  stack two near-identical numbers.
- **Forecast label color = match the forecast bar** (Android: adaptive
  `WeatherConditionColors.forecastColor(...)`; desktop: `Color.Yellow`).
- Applies to **past days only** ("prior day high"); today/future unchanged.

## Approach

### 1. Shared, testable decision helper (`:shared`)
New file `shared/src/main/kotlin/com/weatherwidget/shared/graph/DualHighLabel.kt`:

```kotlin
object DualHighLabel {
    const val MIN_DIFF_DEG = 5f
    // Fraction of label height the two boxes may overlap and still count as "enough room".
    const val MAX_OVERLAP_FRACTION = 0.5f

    /** Both label Y's are the *top* of each label box (already includes the above-bar offset). */
    fun showBoth(
        actualHigh: Float?, forecastHigh: Float?,
        actualLabelTopY: Float, forecastLabelTopY: Float,
        labelHeightPx: Float,
    ): Boolean {
        if (actualHigh == null || forecastHigh == null) return false
        if (abs(actualHigh - forecastHigh) < MIN_DIFF_DEG) return false
        val gap = abs(actualLabelTopY - forecastLabelTopY)
        return gap >= labelHeightPx * (1f - MAX_OVERLAP_FRACTION)
    }
}
```

Because the bars are also horizontally offset, the two labels sit at different X, so this
vertical-gap test is conservative (real overlap is less). Pure function → unit-testable
with plain JUnit; add `DualHighLabelTest.kt` covering: below floor, above floor with/without
room, null inputs. (Matches the codebase's shared-pure-function + plain-JUnit pattern, e.g.
`LocationMatch`, `DailyRainLabels`.)

### 2. Tenths-aware font shrink (both platforms)
A label shows tenths iff its formatted string contains `'.'` (driven by
`TempUtils.formatTemp`, which only emits a decimal when the value isn't within 0.01 of an
integer). Add a tiny shared helper next to the decision (or in `TempUtils`):

```kotlin
fun hasTenths(label: String) = label.contains('.')   // → caller multiplies font size by 0.9
```

Apply at every daily-view temp-label draw site (high **and** low) on both renderers:
final font size = baseSize × (twoHighLabels ? 0.85 : 1.0) × (hasTenths ? 0.9 : 1.0).

### 3. Android — `app/.../widget/DailyForecastGraphRenderer.kt`
High-label block is `drawDayBars()` ~lines 790–806 (single label today). Forecast overlay
bar already computes `fHighY` and `condColor` at ~759–768. Changes:
- For a **past** day with `dashedLineHigh != null`, compute the actual-high label top-Y
  (existing formula at 805) and the forecast-high label top-Y (same formula but anchored at
  `fHighY`, centered at `forecastX = centerX + layout.forecastBarOffset`).
- Call `DualHighLabel.showBoth(...)` with `pastTempTextPaint` font metrics for
  `labelHeightPx`.
- **If show both:** draw the actual label at `centerX` in `COLOR_OBSERVED_RED` and the
  forecast label at `forecastX` in `condColor` (the same value used for the overlay bar at
  764). Use a font scaled by 0.85 (two labels), times 0.9 if that label has tenths.
- **Else:** keep the current single label (actual high, `pastTempTextPaint`, white), still
  applying the tenths 0.9× shrink.
- Implement via a small helper that clones a base text paint and sets `color` + `textSize`
  (Paint is mutable), or add forecast/observed text paints to `PaintSet`. Keep
  `formatTempLabel` (1049) as-is.

### 4. Desktop — `desktop/.../DailyForecastGraph.kt`
Past-day branch draws forecast bar (yellow) at `centerX + tripleOffset` and actual bar at
`centerX` (~136–144). The single high label (~154–166) currently uses
`max(solidHigh, forecastHigh, ghostHigh, snapshotHigh)` — replace for past days:
- Measure actual-high label (`day.solidHigh`) at `centerX` and forecast-high label
  (`day.forecastHigh`) at `centerX + tripleOffset`; compute both top-Y via existing
  `highY - textHeight - 3f*scale`.
- `DualHighLabel.showBoth(...)`:
  - **show both:** actual in `COLOR_OBSERVED`, forecast in `Color.Yellow` (matches the bar),
    font `12f * scale * 0.85 * (hasTenths ? 0.9 : 1)`.
  - **else:** current single label, with the tenths 0.9× shrink added.
- Low label (~167–176) and other day types: just add the tenths 0.9× shrink; logic unchanged.

`formatTemp` (292) unchanged — callers inspect the returned string for `'.'`.

## Files
- **New:** `shared/src/main/kotlin/com/weatherwidget/shared/graph/DualHighLabel.kt`
- **New test:** `shared/src/test/.../DualHighLabelTest.kt`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` (high-label
  draw + tenths shrink on all temp labels)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` (high-label
  draw + tenths shrink on all temp labels)
- Possibly `shared/.../util/TempUtils.kt` (add `hasTenths` helper) — optional.

## Reused existing code
- Forecast vs actual data already at render layer: Android `DayData.dashedLineHigh` /
  `solidLineHigh`; desktop `DesktopDailyDay.forecastHigh` / `solidHigh`.
- Colors: `WeatherConditionColors.forecastColor(...)` + `COLOR_OBSERVED_RED` (Android);
  `COLOR_OBSERVED` + `Color.Yellow` (desktop); shared `WeatherColors`.
- `TempUtils.formatTemp` for the tenths signal.

## Verification
- **Unit:** `./gradlew testDebugUnitTest --tests "*DualHighLabelTest*"` (floor, room,
  null). Optionally a renderer assertion that a past day with a ≥5° miss draws two high
  labels (assert `drawText` count / distinct colors, not color values — per the
  renderer-test-colors-are-zero note).
- **Desktop, live:** `scripts/buildStart.sh` (rebuild + restart), open the daily view, find a
  past day with a known forecast miss (yesterday's ~8°). Confirm: hot-pink actual number +
  yellow forecast number, both legible, slight overlap only. Confirm a small-miss day still
  shows one label. Confirm a decimal-valued day's label is visibly smaller.
- **Android, live:** `./gradlew installDebug`; on a connected device add/resize the daily
  widget; per CLAUDE.md capture a screenshot via
  `adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`. Check the same
  past day across the connected devices (different sizes → different pixels/degree → exercises
  the room test). Pull DB if a day's forecast snapshot is missing.
- Watch for: forecast label colliding with the day-name row or rain label on cramped widgets
  (room test should suppress); ensure single-label past days are visually unchanged except
  the tenths shrink.

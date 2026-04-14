# Fix: Today's thermometer collapses when current temp drops below overnight low

## Context

On Samsung and Pixel physical devices, the "Today" thermometer bar shows the
low temperature equal to the high. On the emulator it renders correctly. On
Samsung, the user observed it flash correctly for ~2 seconds, then go bad.

## Root cause (confirmed from live logs)

Live log from Pixel (2026-04-14 16:32):

```
DailyEstimator: today: actual.high=63.82 actual.low=60.53 currentTemp=59.463333
observedHigh=59.463333 observedLow=59.463333 ... forecastHigh=65.0 forecastLow=48.0
```

Both `observedHigh` and `observedLow` have collapsed onto `currentTemp` (59.46)
even though `actual.high` (63.82) and `actual.low` (60.53) are both present in
the database.

**The bug is on line 66**, not line 68:

```kotlin
// DailyActualsEstimator.kt:66
val observedHigh = currentTemp ?: actual?.highTemp
// DailyActualsEstimator.kt:68
val observedLow  = listOfNotNull(actual?.lowTemp, currentTemp).minOrNull()
```

When the current temperature drops *below* the day's recorded low (cold front,
evening cooling, etc.):

1. Line 68 correctly updates `observedLow` downward via the min-fold →
   `min(60.53, 59.46) = 59.46`. Good.
2. Line 66 also sets `observedHigh = currentTemp = 59.46`, unconditionally
   overwriting the real daily peak of 63.82. Bad.

Both endpoints collapse to `currentTemp`, and the thermometer renders as a
zero-length bar.

**Emulator is fine** because there `currentTemp=63.44` sits *above*
`actual.low=44.996` — the cold-front condition never triggers. This is why the
bug looked device-specific but is really weather-specific: it will fire on any
device whenever the current temp dips below the recorded daily low.

**Why Samsung showed "flash correct then go bad":** the first render happened
before `currentTemp` was interpolated, so line 66 fell through to
`actual?.highTemp` (63.82) → correct bar. The second render, once `currentTemp`
was populated at 59.46 (below `actual.low`), collapsed both endpoints onto it.

## Why the original diagnosis was wrong

The first pass of this plan assumed `actual.lowTemp == null` on physical
devices. The Pixel log disproves that: `actual.low=60.53`. The problem is
asymmetric handling of currentTemp — line 68 folds it into a `min`, line 66
does *not* fold it into a `max`. The fix is to make line 66 symmetric.

## Fix

### File: `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`

Replace line 66:

```kotlin
// Before
val observedHigh = currentTemp ?: actual?.highTemp

// After
val observedHigh = listOfNotNull(actual?.highTemp, currentTemp).maxOrNull()
```

Verification against real data:

**Pixel (bug repro) — `actual.high=63.82, actual.low=60.53, currentTemp=59.46`:**
- `observedHigh = max(63.82, 59.46) = 63.82` ✓ (was 59.46)
- `observedLow  = min(60.53, 59.46) = 59.46` ✓ (unchanged)
- Bar spans 63.82 → 59.46 (~4.4°F) — visible, correct.

**Emulator (working) — `actual.high=63.28, actual.low=44.996, currentTemp=63.44`:**
- `observedHigh = max(63.28, 63.44) = 63.44` (unchanged — was 63.44)
- `observedLow  = min(44.996, 63.44) = 44.996` (unchanged)
- No regression.

**Null cases:**
- `actual = null, currentTemp = 70`: `observedHigh = 70`, `observedLow = 70`
  (same as before — single-point bar is acceptable when no recorded range).
- `actual.highTemp = 75, currentTemp = null`: `observedHigh = 75` (was 75 via
  the `?:` fallback — unchanged).

## Line 68 is left alone

Line 68 already handles its symmetric case correctly: it folds `currentTemp`
into a `min` against `actual.lowTemp`, so if the current reading dips below
the recorded low, `observedLow` tracks the new minimum. No change needed.

## Files touched

- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt` — 1-line
  change on line 66. No renderer, handler, or DAO changes.

## Logging

The existing `Log.d("DailyEstimator", ...)` on lines 81–84 already captures
`actual.high`, `actual.low`, `currentTemp`, `observedHigh`, `observedLow`,
`forecastHigh`, `forecastLow`, and `source`. That log is what surfaced the
real root cause in this investigation. **Leave it in place** — no additions.

## Verification

1. **Unit test** (`DailyActualsEstimator` is a pure Kotlin `object`, no Android
   deps — plain JVM test, no Robolectric):
   - Case A (regression repro): `actual.highTemp=63.82, actual.lowTemp=60.53,
     currentTemp=59.46` → assert `observedHigh == 63.82` and `observedLow == 59.46`.
   - Case B (emulator happy path): `actual.highTemp=63.28, actual.lowTemp=44.996,
     currentTemp=63.44` → assert `observedHigh == 63.44` and `observedLow == 44.996`.
   - Case C (null actuals): `actual=null, currentTemp=70f` → assert
     `observedHigh == 70f` and `observedLow == 70f`.
   - Run: `./gradlew testDebugUnitTest --tests "*DailyActualsEstimator*"`

2. **Device smoke test** (Pixel is currently showing the bug per live log):
   - `./gradlew installDebug`
   - `adb -s 2A191FDH300PPW logcat -c && adb -s 2A191FDH300PPW logcat -s DailyEstimator`
   - Tap the widget to force a daily-view render; confirm the log line now
     shows `observedHigh=63.82` (or whatever the day's recorded high is),
     not collapsed to currentTemp.
   - Screenshot: `adb -s 2A191FDH300PPW exec-out screencap -p > /tmp/s.png &&
     convert /tmp/s.png /tmp/s.jpg` → read `/tmp/s.jpg` and confirm today's
     thermometer bar shows a visible high-to-low span.

3. **Emulator regression check**: same procedure on `emulator-5554`;
   the log line should be unchanged (`observedHigh == currentTemp` still holds
   because currentTemp > actual.high in the emulator's data).

## Out of scope

- Investigating *why* `actual.low=60.53` on Pixel at 16:32 while the current
  temp is 59.46. That could be a genuine cold front (valid) or a data-freshness
  issue in the observation pipeline (separate bug). Either way, the renderer
  should handle it gracefully — which this fix ensures.
- Uncommitted rain-label debug logging in `DailyForecastGraphRenderer.kt` and
  `DailyViewLogic.kt` — unrelated, leave as-is.

# Reduce minor-overlap budget for hourly graph temp labels

## Context

On the emulator's hourly temperature graph, the low-temperature label "58.8" and the
adjacent "58" label overlap significantly. The label-placement engine intentionally tolerates
a "minor" vertical overlap before it bothers to displace/flip a label, governed by a single
shared constant `MINOR_OVERLAP_HEIGHT_RATIO = 0.45f` (overlap up to 45% of label height is
allowed). At 0.45 the two labels are allowed to sit too close.

The rationale for tolerating *some* overlap: a label rect is built from the full font
ascent/descent, so a small vertical overlap is usually empty leading (whitespace), not glyph
ink. Lowering the ratio makes labels separate sooner, at the cost of slightly more
displacement/flips in tight regions (e.g. the NOW valley).

**Decision (user):** reduce the budget to **0.30** (a third less allowed overlap).

## Change

### 1. The constant (the actual fix)
`shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphLabelPlacementUtils.kt:294`
```kotlin
const val MINOR_OVERLAP_HEIGHT_RATIO = 0.45f   // -> 0.30f
```
This single constant feeds `shouldAllowMinorOverlap()` (line 314), which is used for **both**
label-on-label overlap (`allowMinorLabelOverlap`) and hard-bound / fetch-dot value-label
overlap (`allowMinorHardOverlap`) in `TemperatureLabelEngine.kt` (lines 324, 350, 640, 646).
No other code change is required. Applies to both Android and desktop (shared module).

New budget at the test labelHeight of 12px: `0.30 × 12 = 3.6px` (was `0.45 × 12 = 5.4px`).

### 2. Shared test fixture — keep in lockstep
`shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelHardBoundMinorOverlapTest.kt`
- The `side-offset minor hard overlap …` test (line 121) pins `overlapPx = 4f` and asserts the
  label stays flush at its anchor. 4px now **exceeds** the 3.6px budget, so reduce it to
  `overlapPx = 3f` (a genuine sub-budget minor overlap under the new ratio).
- Update the header KDoc (lines 16, 39) that cites `MINOR_OVERLAP_HEIGHT_RATIO=0.45` / "budget =
  0.45*12 = 5.4px" to reflect `0.30` / `3.6px`.
- The major-overlap test (`overlapPx = 12f`, line 163) and head-on test still behave correctly
  (12px > 3.6px still blocks; head-on still fails the side-only check).

### 3. App-side unit test — keep in lockstep
`app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererLabelPlacementTest.kt`
- `shouldAllowMinorOverlap allows eligible roles within threshold` (lines 25-27) asserts
  overlaps of `4.1f` / `4.2f` are allowed at `labelHeight = 12f`. These now exceed 3.6px and
  must drop below it — change to e.g. `3.0f` (and `3.4f` for variety), still asserting `true`.
- `shouldAllowMinorOverlap rejects overlap above threshold …` (line 32) uses `5.5f` → already
  `false` under the new budget, no change strictly needed. Optionally tighten one example to
  `3.7f` to assert the new just-over-budget boundary rejects.

## Verification

1. Unit tests (fast, no device):
   - `./gradlew :shared:test --tests "*TemperatureLabelHardBoundMinorOverlapTest"`
   - `./gradlew testDebugUnitTest --tests "*TemperatureGraphRendererLabelPlacementTest"`
   Both must stay green (they pin the new 3.6px budget).
2. Build & install on the emulator: `./gradlew installDebug`, then trigger a refresh
   (`adb shell am broadcast -a com.weatherwidget.ACTION_REFRESH`) and open/resize the widget to
   the hourly graph view.
3. Screenshot and confirm the "58.8" low label and the "58" label no longer visibly overlap:
   ```bash
   adb exec-out screencap -p > /tmp/screenshot.png && convert /tmp/screenshot.png /tmp/screenshot.jpg
   ```
   Read `/tmp/screenshot.jpg`. Watch the NOW-valley region for any newly-introduced leader lines
   or above-curve flips that look worse than the overlap we fixed; if 0.30 over-corrects, 0.35 is
   the fallback (but 0.35 > 0.333 keeps the original test fixtures).
4. (Optional) desktop parity check, since the constant is shared:
   `scripts/buildStart.sh` then inspect the hourly graph.

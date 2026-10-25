# Single Column Slot for Detailed Today Overlay (7 Columns on Pixel 7 Pro)

## User Request
> Take a look at pixel 7 pro. I'd like to see what it looks like with 7 columns versus 6. Are two columns reserved for today column? If yes, maybe just reserve 1 column width.
> yes (to implementing Option A: 7 columns with 1.25x Today weighting)

---

## Background & Root Cause
1. `LargeTodayOverlayPolicy.kt` previously subtracted 1 from `availableColumns` whenever the detailed Today overlay was enabled (`displayColumns = if (enabled) availableColumns - 1 else availableColumns`).
2. `DailyLargeTodayOverlayPolicy.TODAY_SLOT_SPAN` was set to `2`, reserving 2 launcher grid slots for Today.
3. However, `WeightedColumnLayout` on Canvas and Compose renders Today with a `1.25f` weight (`LARGE_TODAY_WIDTH_MULTIPLIER = 1.25f`), not a 2.0x width. This meant a whole day of forecast was dropped while the extra 0.75 slot was distributed among the other days.
4. On Pixel 7 Pro (373dp width, 7 available slots), this forced 6 columns (Today + 4 or 5 days depending on offset).

---

## Implementation Details

### 1. `:shared` module
- `LargeTodayOverlayPolicy.kt`:
  - `displayColumns` returns `availableColumns` directly instead of `availableColumns - 1`.
- Update tests in `LargeTodayOverlayPolicyTest.kt`.

### 2. `:app` module
- `DailyLargeTodayOverlayPolicy.kt`:
  - `const val TODAY_SLOT_SPAN = 1` (or remove extra slot insertion if span is 1).
  - Update `slots(...)` so Today spans 1 slot without offset shifting.
- `DailyViewHandler.kt`:
  - Candidate columns check: previously `overlayCandidateColumns = (numColumns - 1).coerceAtLeast(1)` because `displayColumns` subtracted 1. With `displayColumns = numColumns`, `overlayCandidateColumns` is simply `numColumns`.
- `DailyClickHandlerFactory.kt`:
  - `visibleSlotCount` now simply equals `numColumns`.
- Update tests in `DailyLargeTodayOverlayPolicyTest.kt`, `DailyViewHandlerTest.kt`, `DailyTapZoneCoverageTest.kt`, etc.

### 3. `:desktop` module
- Verify `DesktopDailyForecastModel.kt` and tests reflect `displayColumns = availableColumns`.

### 4. Verification
- Run `./scripts/unit-tests.sh` to ensure all JVM tests pass in `:shared`, `:desktop`, and `:app`.
- Deploy to Pixel 7 Pro (`./gradlew installDebug`) and take screenshots of Widget 88 to verify 7-column layout with the detailed Today overlay.

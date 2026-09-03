# Header Rain Chance %: Switch Rolling Window to Next 6 Hours

**Date:** 2026-09-03
**Status:** Complete

## Problem & Motivation

The widget header across Android (`:app`) and Linux Desktop (`:desktop`) previously computed precipitation probability using a rolling 8-hour window (`LOOKAHEAD_HOURS = 8L` via `PrecipProbabilityCalculator.getNext8HourPrecipProbability` and `isNext8HourPrecipPredominantlyNight`).

However, the today-column tap routing gate was unified to use a rolling **6-hour window** (`TODAY_LOOKAHEAD_HOURS = 6L` in `DayClickResolver`), matching `ZoomStage.WIDE.window().forwardHours` (the actual span displayed when opening the graph).

Using an 8-hour window for the header caused a mismatch:
- If rain was forecast at +7 hours, the header displayed a prominent rain chance (e.g., `60%`), but tapping today's column routed to the temperature graph because the visible 6-hour forecast window had 0% rain.
- Switching both the header precipitation lookahead and the predominantly-night detection to 6 hours brings the entire application into consistency: header display, tap routing gate, and wide graph window all share the identical 6-hour forward span.

## Summary of Changes

### 1. Shared Logic (`:shared`)
- **`PrecipProbabilityCalculator.kt`**:
  - Added the single `VISIBLE_LOOKAHEAD_HOURS = 6L` policy constant.
  - Added `getNext6HourPrecipProbability(...)` and `isNext6HourPrecipPredominantlyNight(...)` as the primary functions.
  - Removed semantically misleading eight-hour compatibility aliases.
  - Added paired `HeaderPrecipitation` resolution so probability and nighttime weighting use one
    normalized, source-selected series.
- **`DailyRainLabels.kt`**:
  - Centralized cross-platform probability/night header font scaling.
- **`DayClickResolver.kt`**:
  - References the shared visible-window constant; a parity test ties it to the wide graph window.

### 2. Android Widget (`:app`)
- **`HeaderPrecipCalculator.kt`**:
  - Reduced the Android adapter to Room-model conversion, shared paired resolution, and dp sizing.
- **Resolvers & Handlers**:
  - `DailyHeaderResolver.kt`: Resolves probability, night weighting, and text size once.
  - `DailyGraphRenderer.kt`: Consumes the resolved `HeaderState.precipTextSizeDp` rather than
    recalculating policy.
  - `TemperatureStateResolver.kt`: Uses `getNext6HourPrecipProbability`.
  - `PrecipViewHandler.kt`: Uses `getNext6HourPrecipProbability`.
  - `CloudCoverViewHandler.kt`: Uses `getNext6HourPrecipProbability`.
- **`DailyViewLogic.kt`**:
  - Uses the horizon-neutral `todayPrecipProbability` parameter with no compatibility overload.

### 3. Linux Desktop Companion (`:desktop`)
- **`DesktopHeaderPrecipitation.kt`**:
  - Extracted header precipitation orchestration from `Main.kt`.
  - Delegates paired weather resolution and font scaling to `:shared`; Compose receives a resolved
    desktop model.

### 4. Tests & Parity
- **`PrecipProbabilityCalculatorTest.kt`**: Verified `getNext6HourPrecipProbability` delegates to a 6-hour horizon and excludes rain spikes at +7 hours.
- **`PrecipProbabilityCalculatorNightTest.kt`**: Added a fixture that is daytime over six hours but
  nighttime over eight hours, plus exact-end exclusion coverage.
- **`HeaderPrecipCalculatorTest.kt`**: Keeps only thin Android adapter and sizing checks.
- **`HeaderPrecipitationArchitectureTest.kt`**: Guards resolved-state consumption and removal of
  false eight-hour Android APIs.
- **`DesktopHeaderPrecipitationResolverTest.kt`**: Verifies daily/night scaling, hourly scaling,
  and zero-probability hiding through the extracted desktop adapter.

## Verification
- **Unit & Robolectric Tests**: `./gradlew test` passed across `:shared`, `:desktop`, and `:app`.
- **Build & style**: `./gradlew ktlintCheck assembleDebug :desktop:createDistributable` passed.
- **Emulator**: Installed the APK only on `emulator-5554`, rendered widget 59 in daily mode, and
  observed `precipProbability=6` plus a successful `WIDGET_PAINT` in logcat. The screenshot showed
  the matching 6% header. Restored the widget's recorded source, view, offset, and zoom afterward.
- **Boundary evidence**: Current live weather did not provide the six-versus-eight discriminator;
  the deterministic shared boundary tests cover that distinction. Desktop was verified by resolver
  tests and distributable build, not a launched GUI session.

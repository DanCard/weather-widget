# Hourly Zoom Window: 9h Symmetric WIDE and 36h/12h TWO_DAY

**Date:** 2026-09-03  
**Plan:** [plans/260903-hourly-zoom-9h-symmetric.md](../plans/260903-hourly-zoom-9h-symmetric.md)

## Problem

When tapping on the "Today" column from the daily forecast view to navigate into the hourly view:
1. The default hourly zoom level (`ZoomStage.WIDE`) was asymmetric: **12 hours back and 6 hours forward** (18 hours total).
2. The user requested centering the view around `now` with **9 hours back and 9 hours forward** (symmetric 18-hour window).
3. The multi-day zoom stage (`ZoomStage.TWO_DAY`) was previously 42 hours back and 6 hours forward (48 hours total). To ensure desktop forward-hours monotonicity when WIDE's forward hours moved from 6 to 9, TWO_DAY was updated to **36 hours back and 12 hours forward** (48 hours total, 12h forward).
4. The header precipitation lookahead and today-tap routing gate were intentionally kept at 6 hours (`VISIBLE_LOOKAHEAD_HOURS = 6L`), decoupling them from WIDE zoom forward hours.

---

## Changes

### 1. `:shared` Module
- **[`ZoomStage.kt`](shared/src/main/kotlin/com/weatherwidget/shared/graph/ZoomStage.kt)**:
  - `ZoomStage.WIDE`: Updated `backHours = 9`, `forwardHours = 9` (18 hours total).
  - `ZoomStage.TWO_DAY`: Updated `backHours = 36`, `forwardHours = 12` (48 hours total).
- **Tests**:
  - `ZoomStageTest.kt` & `ZoomWindowTest.kt`: Updated assertions for WIDE (9 back / 9 forward) and TWO_DAY (36 back / 12 forward).
  - `DayClickResolverTest.kt`: Updated expected noon-centered offsets for tomorrow and past days (now shifting by 0h instead of +3h asymmetry), and confirmed decoupling from lookahead tests.

### 2. `:desktop` Module
- **[`DesktopGraphUtils.kt`](desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopGraphUtils.kt)**:
  - Updated `DEFAULT_ZOOM_FACTOR = 0.256f` corresponding to 9h forward at `forwardHours(z) = 2 * (720 / 2)^z`.
- **[`SettingsWindow.kt`](desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt)**:
  - Updated description string: `"Adds a 48-hour level — 36 hours back, 12 hours forward."`
- **Tests**:
  - `DesktopGraphZoomTest.kt`: Updated canonical factors (`TWO_DAY` ~0.49f) and forward hours assertions (`TWO_DAY` = 12h forward).

### 3. `:app` Module
- **[`strings.xml`](app/src/main/res/values/strings.xml)**:
  - Updated `hourly_zoom_two_day_description`: `"Adds a 48-hour level — 36 hours back, 12 hours forward."`
- **[`CloudCoverViewHandler.kt`](app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt)**:
  - Added optional `now: LocalDateTime = LocalDateTime.now()` to `buildCloudHourDataList` (matching `PrecipViewHandler`) to allow deterministic time injection in tests.
- **Tests**:
  - `ZoomCycleRoboTest.kt`: Updated two-day span test (36/12), 13 touch-zone mapped offsets `[-9, -7, -6, -4, -3, -1, 0, 2, 3, 5, 6, 8, 9]`, and base offset extra calculations.
  - `WeatherWidgetProviderRobolectricTest.kt`: Updated zone 5 mapped offset from `-2` to `-1`.
  - `WidgetStateManagerTest.kt`: Updated `ZoomWindow` parameter assertions for WIDE (9 back / 9 forward).
  - `HourlyZoomCenteringRoboTest.kt`: Updated selected hour position in WIDE window to index 9 (center of 19 marks).
  - `DailyViewHandlerTest.kt` & `DailyHistoryClickIntentRoboTest.kt`: Updated tomorrow offset (+24 vs +27) and past-day offset (-74 vs -71) reflecting zero asymmetry shift.
  - `CloudCoverViewHandlerTest.kt`: Passed deterministic `now` parameter into `buildCloudHourDataList`.

---

## Verification

- **Shared Tests**: `./gradlew :shared:test` passed (1,468 tests green).
- **Desktop Tests**: `./gradlew :desktop:test` passed (373 tests green).
- **App Tests**: `./gradlew :app:testDebugUnitTest` passed (2,115 tests green).
- **Full Test Suite**: `./gradlew test` passed cleanly across all modules.
- **Builds**: `./gradlew :desktop:createDistributable assembleDebug` built successfully with zero errors.

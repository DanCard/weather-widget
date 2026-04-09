# Precipitation Graph Body-Zone Zoom Test Plan

**Date:** 2026-04-09
**Bug:** Tapping the precipitation graph body navigates to cloud cover instead of zooming in
**Root cause:** `PrecipViewHandler.setupZoomTapZones()` used icon-dependent routing for body zones — when weather icons are non-rainy (cloudy, clear, fog), `DayClickHelper.resolveHourlyBottomRowAction()` returns `CLOUD_COVER` instead of `null` (zoom). Since most hours have non-rainy icons, nearly every body zone navigated away.
**Fix:** Body zones now always send `ACTION_CYCLE_ZOOM`. Only bottom-row footer zones use icon-dependent routing.

## Test Files

### 1. Robolectric Unit Test
**File:** `app/src/test/java/com/weatherwidget/widget/handlers/PrecipTouchRoutingRoboTest.kt`
**Category:** `MediumDuration`
**Pattern:** Follows `TemperatureTouchRoutingRoboTest` — renders `PrecipViewHandler.updateWidget()`, captures `RemoteViews` via mockk slot, applies to FrameLayout, clicks zones, inspects broadcast intents via `shadowOf(app).broadcastIntents`.

**Test cases:**
1. `wide precipitation graph routes all body zone taps to zoom` — WIDE zoom, mixed non-rainy icons. All 13 `graph_hour_zone_*` zones produce `ACTION_CYCLE_ZOOM` with valid `EXTRA_ZOOM_CENTER_OFFSET`.
2. `narrow precipitation graph routes all body zone taps to zoom` — Same with NARROW zoom.
3. `precipitation graph bottom footer zones still route by icon type` — At least one footer zone sends `ACTION_SET_VIEW`, proving bottom-row icon routing unchanged.
4. `text mode hides graph touch overlays` — 1-row dimensions, all graph zones `GONE`.

### 2. Instrumented Integration Test
**File:** `app/src/androidTest/java/com/weatherwidget/widget/handlers/PrecipTouchRoutingInstrumentedTest.kt`
**Pattern:** Follows `CloudCoverTouchRoutingInstrumentedTest` — extends `IsolatedIntegrationTest`, inserts hourly data with non-rainy icons, clicks body zone, polls `stateManager` for state transitions.

**Test cases:**
5. `body zone tap zooms precipitation graph without changing view mode` — PRECIPITATION + WIDE → click body → NARROW, view stays PRECIPITATION.
6. `body zone tap on narrow zoom cycles back to wide` — PRECIPITATION + NARROW → click body → WIDE.

## Key Design Decisions
- Sample data uses non-rainy icons (Cloudy, Clear, Partly Cloudy) — the exact scenario that was broken
- Robolectric validates intent structure (action + extras), instrumented validates state transitions (zoom level) — complementary coverage
- No pure-function extraction needed — the fix removed routing logic entirely, so testable surface is PendingIntent wiring

## Run Commands
```bash
# Robolectric
./gradlew testMediumDebugUnitTest --tests "*.PrecipTouchRoutingRoboTest"

# Instrumented
./scripts/emulator-tests.sh -c com.weatherwidget.widget.handlers.PrecipTouchRoutingInstrumentedTest
```

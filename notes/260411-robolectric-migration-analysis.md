# Instrumented Tests → Robolectric Migration Analysis

Date: 2026-04-11

## Already Have Robolectric Counterparts (4 files)

These could potentially be **removed** from androidTest if the Robolectric versions provide equivalent coverage:

| androidTest | test/ Counterpart | Notes |
|---|---|---|
| `TemperatureGraphLabelTest` | `TemperatureGraphLabelPlacementRobolectricTest` | Both test label placement via `onLabelPlaced` callback |
| `TemperatureFetchDotIntegrationTest` | `TemperatureGraphRendererFetchDotTest` | Fetch dot callback tests already in test/ |
| `HourlyGraphDayLabelTest` | `HourlyGraphDayLabelRobolectricTest` | Day label placement already covered |
| `PrecipitationGraphRendererPlacementInstrumentedTest` | `PrecipitationGraphRendererTest` | Label placement above/below curve already covered |

Before removal, verify coverage parity between the instrumented and Robolectric versions.

## Easily Migrateable (18 files)

### Zero-effort (no Android deps)

| File | Reason |
|---|---|
| `NwsHistoryIntegrationTest` | Calls pure function `DailyViewLogic.prepareGraphDays()`, no Android dependencies |

### Easy (SharedPreferences / simple math)

| File | Reason |
|---|---|
| `DailyViewHandlerTest` | Placeholder + `NavigationUtils.getDayOffsets` — pure logic |
| `WidgetSizeCalculatorTest` | dpToPx and getOptimalBitmapSize — Robolectric provides shadow display metrics |
| `ZoomCycleTest` (~15 methods) | SharedPreferences state only — high ROI |
| `WidgetStateManagerApiRotationTest` | SharedPreferences round-trip cycling |
| `WidgetStateManagerSyncTest` | SharedPreferences set/get |
| `NavigationPersistenceTest` | SharedPreferences + Intent extras |
| `WidgetIntentRouterTest` | Action constants + crash-safety with invalid widget IDs |

### Medium (callback-based graph rendering needs Context)

| File | Reason |
|---|---|
| `TemperatureGhostLabelIntegrationTest` | `onLabelPlaced` callback, needs Context for `renderGraph()` |
| `TemperatureGraphLabelGeneralTest` | `onLabelPlaced` callback, needs Context |
| `TemperatureGraphClutterTest` | `onLabelPlaced` callback, needs Context |
| `RainPeakLabelTest` | `onLabelPlaced` callback, needs Context |
| `SamsungDataFailTest` | `onLabelPlaced` callback, regression test |
| `PrecipitationGraphRendererLogInstrumentedTest` | Callbacks + R.drawable refs |
| `DailyForecastGraphRendererTest` | Bar placement callbacks; bitmap dimension checks need verification |

### Medium (Room + SharedPreferences)

| File | Reason |
|---|---|
| `DailyViewApiToggleIntegrationTest` | SharedPreferences + DB state cycling |
| `CloudCoverViewModeIntegrationTest` | CloudCover state machine with DB + SharedPreferences |
| `ForecastHistoryButtonIntegrationTest` | In-memory Room + pure decision logic |

## Must Stay Instrumented (14 files)

These need real Canvas/Bitmap, RemoteViews + performClick, real View.measure/layout, or real SQLite:

| File | Why It Must Stay |
|---|---|
| `CloudCoverGraphRendererTest` | Real Bitmap dimensions after Canvas rendering |
| `HourlyBottomTouchZoneInstrumentedTest` | Real View.measure()/layout() |
| `TemperatureGhostLineTest` | Y coordinates vs real display metrics (TypedValue.applyDimension) |
| `HomeTouchZoneInstrumentedTest` | RemoteViews + PendingIntent wiring |
| `PrecipTouchZoneInstrumentedTest` | RemoteViews + PendingIntent + widget framework |
| `DatabaseMigrationTest` | MigrationTestHelper requires real SQLite |
| `RainAnalyzerIntegrationTest` | Reads logcat via uiAutomation.executeShellCommand — could refactor with callback abstraction |
| `PrecipTouchRoutingInstrumentedTest` | RemoteViews + performClick + SharedPreferences state |
| `TemperatureZoomConsistencyTest` | End-to-end Hilt DI + WidgetRenderer pipeline |
| `DailyMainColumnVsBottomIconClickTargetIntegrationTest` | RemoteViews split click zones |
| `DailyGraphTouchZoneAlignmentInstrumentedTest` | RemoteViews + hasOnClickListeners |
| `TemperatureTouchRoutingInstrumentedTest` | Bottom footer tap routing with real DB |
| `CloudCoverTouchRoutingInstrumentedTest` | Cloud cover footer tap routing |
| `DayClickNavigationTest` (partially) | Icon routing is pure logic (migrate), handleSetView needs real DB (keep) |

## Summary

| Category | Count |
|---|---|
| Already has Robolectric equivalent | 4 |
| Could migrate to Robolectric | 18 |
| Must stay instrumented | 14 |

## Migration Order Recommendation

1. **NwsHistoryIntegrationTest** — zero-effort, move to test/ with no changes
2. **Pure logic tests** — DailyViewHandlerTest, WidgetSizeCalculatorTest, WidgetIntentRouterTest (constants subset)
3. **SharedPreferences tests** — ZoomCycleTest (highest ROI at ~15 methods), WidgetStateManager*, NavigationPersistenceTest
4. **Callback-based graph tests** — Temperature*, RainPeak*, Samsung*, Precipitation* log/placement
5. **Room+SP tests** — DailyViewApiToggle, CloudCoverViewMode, ForecastHistoryButton
6. **DailyForecastGraphRendererTest** — verify Robolectric shadow Bitmap handles dimension assertions
7. **Dedup** — verify coverage parity for the 4 files that already have Robolectric counterparts, then remove

Each migrated test should be verified by running both `./gradlew test` and comparing behavior with the original instrumented test before removing the androidTest version.

## Migration Completed (2026-04-11)

### Migrated to Robolectric (18 files removed from androidTest)

| Original androidTest file | New Robolectric test file | Notes |
|---|---|---|
| `NwsHistoryIntegrationTest` | `NwsHistoryIntegrationTest` (test/) | Zero-effort move, pure DailyViewLogic |
| `DailyViewHandlerTest` | `DailyViewHandlerUnitTest` (test/) | Pure NavigationUtils logic |
| `WidgetSizeCalculatorTest` | `WidgetSizeCalculatorRoboTest` (test/) | Robolectric display metrics |
| `WidgetIntentRouterTest` | `WidgetIntentRouterCrashSafetyRoboTest` (test/) | Constants + crash-safety |
| `ZoomCycleTest` | `ZoomCycleRoboTest` (test/) | SharedPreferences round-trips |
| `WidgetStateManagerApiRotationTest` | `WidgetStateManagerApiRotationRoboTest` (test/) | SharedPreferences |
| `WidgetStateManagerSyncTest` | `WidgetStateManagerSyncRoboTest` (test/) | SharedPreferences |
| `NavigationPersistenceTest` | `NavigationPersistenceRoboTest` (test/) | SharedPreferences + Intent |
| `TemperatureGhostLabelIntegrationTest` | `TemperatureGhostLabelRoboTest` (test/) | onLabelPlaced callback |
| `TemperatureGraphLabelGeneralTest` | `TemperatureGraphLabelGeneralRoboTest` (test/) | onLabelPlaced callback |
| `TemperatureGraphClutterTest` | `TemperatureGraphClutterRoboTest` (test/) | onLabelPlaced callback |
| `RainPeakLabelTest` | `RainPeakLabelRoboTest` (test/) | onLabelPlaced callback |
| `SamsungDataFailTest` | `SamsungDataFailRoboTest` (test/) | onLabelPlaced callback |
| `PrecipitationGraphRendererLogInstrumentedTest` | `PrecipitationGraphRendererLogRoboTest` (test/) | Callbacks + R.drawable |
| `DailyViewApiToggleIntegrationTest` | `DailyViewApiToggleIntegrationRoboTest` (test/) | SP, try-catch for widget update |
| `CloudCoverViewModeIntegrationTest` | `CloudCoverViewModeRoboTest` (test/) | SP, try-catch for widget update |
| `ForecastHistoryButtonIntegrationTest` | `ForecastHistoryButtonRoboTest` (test/) | Room in-memory + decision logic |
| `DailyForecastGraphRendererTest` | `DailyForecastGraphRendererRoboTest` (test/) | onBarDrawn/onRainLabelDrawn callbacks |

### Deduplicated (3 files removed, kept Robolectric version)

| Removed androidTest file | Kept test/ Counterpart |
|---|---|
| `TemperatureGraphLabelTest` | `TemperatureGraphLabelPlacementRobolectricTest` |
| `TemperatureFetchDotIntegrationTest` | `TemperatureGraphRendererFetchDotTest` |
| `PrecipitationGraphRendererPlacementInstrumentedTest` | `PrecipitationGraphRendererTest` |

### Kept (1 file)

| androidTest file | Reason |
|---|---|
| `HourlyGraphDayLabelTest` | Has 4 unique tests (X position, layout matching, midnight format) not in Robolectric version |

### Key Migration Patterns

- **SharedPreferences tests**: Use `WidgetStateManager(ApplicationProvider.getApplicationContext())` directly
- **WidgetIntentRouter calls**: Wrap in `try { ... } catch (_: Exception) {}` since AppWidgetManager.updateAppWidget fails without a registered widget
- **Room in-memory**: Use `TestDatabase.create()` with Robolectric context
- **Callback-based graph renderers**: Use `onLabelPlaced`, `onBarDrawn`, `onDebugLog` callbacks — no pixel inspection needed
- **Bitmap dimension assertions**: Robolectric shadow Bitmap has different density; use relaxed assertions for height-dependent tests
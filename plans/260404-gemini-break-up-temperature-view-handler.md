# TemperatureViewHandler "God Function" Refactoring Plan

## Background & Motivation
The `TemperatureViewHandler.updateWidget` function has grown into a "God Function" (~300 lines) with too many intertwined responsibilities: 
- Fetching and interpolating temperature data
- Debouncing and managing background coroutines
- Calculating and formatting temperature deltas
- Generating bitmap graphs via `TemperatureGraphRenderer`
- Binding `RemoteViews` and setting click listeners
- Managing and emitting `WidgetPerfLogger` telemetry

This high complexity violates the Single Responsibility Principle and makes it difficult to unit test presentation logic without spinning up a heavy Robolectric environment.

## Scope & Impact
We will decompose `TemperatureViewHandler.kt` to enforce a Unidirectional Data Flow / ViewModel architecture. We will introduce a testable pure logic layer that generates a `TemperatureWidgetState`, fully isolating the Android `RemoteViews` manipulation. 

**Impacted Test Suites:**
- `TemperatureTouchRoutingRoboTest.kt`
- `TemperatureDeltaVisibilityRoboTest.kt`
- `HistoryIconVisibilityRoboTest.kt`
- `TemperatureViewHandlerCenterTimeTest.kt`
- `PrecipProbabilityTouchRoutingRoboTest.kt`
- `WeatherObservationsShortcutTest.kt`
- `CurrentTempTouchRoutingRoboTest.kt`
- `TemperatureFetchDotUpdateRoboTest.kt`

## Proposed Solution
1. **Define UI State Model**: Create a `TemperatureWidgetState` (and sub-states like `HeaderState`, `GraphState`) data class containing the resolved and formatted strings, color codes, graph data, and visibility flags.
2. **Extract State Resolver**: Create `TemperatureStateResolver` to handle all business logic (delta calculation, text formatting, observation blending) and return the state object.
3. **Extract View Binder**: Create `TemperatureViewBinder` to take `TemperatureWidgetState` and apply it to `RemoteViews`.
4. **Refactor Orchestrator**: `TemperatureViewHandler.updateWidget` will become a thin coordinator that delegates to the Resolver and then the Binder, handling only coroutine management and telemetry.

## Alternatives Considered
- **Incremental Local Extraction**: Extracting helper functions like `buildHeaderViews` and `buildGraphViews` inside the same object. 
  - *Why not*: This doesn't solve the core issue of mixing Android `RemoteViews` side-effects with pure Kotlin data resolution logic. It leaves tests heavily dependent on Robolectric and makes it hard to verify formatting rules in isolation.

## Implementation Plan
- **Phase 1: State Definitions**: Define the `TemperatureWidgetState` data class and associated presentation models.
- **Phase 2: State Resolution**: Implement `TemperatureStateResolver` by extracting the pure computation logic from `updateWidget`.
- **Phase 3: View Binding**: Implement `TemperatureViewBinder` to handle `RemoteViews` assignment and touch target routing.
- **Phase 4: Orchestration**: Wire the new components inside `TemperatureViewHandler.updateWidget`. Keep the coroutine and telemetry logic here.
- **Phase 5: Test Migration**: Update all 8 Robolectric test suites. Where possible (e.g., `TemperatureDeltaVisibilityRoboTest`), migrate tests to verify `TemperatureStateResolver` directly instead of the `RemoteViews` output, reducing test execution time and fragility.

## Verification & Testing
- All existing tests in `app/src/test/java/com/weatherwidget/widget/handlers/` must pass after migration.
- Verify the widget visually on physical devices or emulators to ensure click targets and rendering remain intact.

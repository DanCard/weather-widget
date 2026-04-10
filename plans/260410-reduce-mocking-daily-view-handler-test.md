# Reduce Mocking in DailyViewHandlerTest.kt

## Context

`DailyViewHandlerTest.kt` has 35 tests. A blanket `mockkObject(RainAnalyzer)` in `@Before` affects all of them, even though only 6 tests actually stub `getRainSummary`. There's also a dead `WidgetStateManager` mock and a `CurrentTemperatureResolver` object mock that can be replaced with output verification. The project philosophy is "no mocking framework — prefer pure function extraction."

## Change 1: Inject `rainSummaryProvider` lambda into DailyViewLogic

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`

Add a defaulted lambda parameter to both `prepareTextDays` (line 46) and `prepareGraphDays` (line 201):

```kotlin
rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = RainAnalyzer::getRainSummary
```

Replace direct `RainAnalyzer.getRainSummary(...)` calls at lines 100 and 338 with `rainSummaryProvider(...)`.

No changes needed in `DailyViewHandler.kt` or other callers — they use the default.

## Change 2: Remove RainAnalyzer mocking from tests

**File**: `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerTest.kt`

- Remove `mockkObject(RainAnalyzer)` from `@Before` (line 67)
- Remove `unmockkObject(RainAnalyzer)` from `@After` (line 72)
- Remove 5x `every { RainAnalyzer.getRainSummary(...) } returns null` stubs (lines 81, 105, 426, 462, 581) — the real function returns null for `emptyList()` inputs
- Replace the `every { ... } answers { ... }` in the rain test (line 138) with a `rainSummaryProvider` lambda:
  ```kotlin
  rainSummaryProvider = { _, date, _, _ ->
      when (date) {
          today.plusDays(1) -> "9am"
          today.plusDays(2) -> "10am"
          else -> null
      }
  }
  ```
- Clean up unused imports (`mockkObject`, `unmockkObject`, `RainAnalyzer`)

## Change 3: Remove dead WidgetStateManager mock

**File**: `DailyViewHandlerTest.kt`

Delete the `stateManager` mock and its 4 `every` stubs (lines 1389-1393) from the `DailyViewHandler uses provided lastObservedTemp` test. This mock is never injected — `updateWidget` creates its own `WidgetStateManager(context)` internally.

## Change 4: Restructure `lastObservedTemp` test to verify output

**File**: `DailyViewHandlerTest.kt`

Instead of mocking `CurrentTemperatureResolver.resolve` and verifying it was called with the right parameter:
- Set up `AppWidgetManager` with proper dimensions (copy pattern from other `updateWidget` tests)
- Let the real `CurrentTemperatureResolver.resolve` run
- Capture `RemoteViews`, inflate, and assert the displayed current temperature reflects the provided `lastObservedTemp`
- Remove `mockkObject(CurrentTemperatureResolver)` and `unmockkObject(CurrentTemperatureResolver)`

## What stays

- `mockk<AppWidgetManager>()` in `updateWidget` tests — framework boundary, provides dimensions and captures RemoteViews
- `mockkStatic(WorkManager::class)` in 2 tests — system boundary for verifying work enqueueing

## Net result

- 3 mock objects eliminated (`RainAnalyzer`, `CurrentTemperatureResolver`, `WidgetStateManager`)
- ~25 lines of mock setup/teardown removed
- 6 `every { ... }` stubs removed
- Remaining mocks are justified framework/system boundaries

## Verification

After each change, run:
```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewHandlerTest"
```

Also run related test files that call `prepareTextDays`/`prepareGraphDays` to confirm no regressions:
```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest"
```

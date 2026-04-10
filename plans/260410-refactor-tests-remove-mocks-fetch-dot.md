# Refactor Tests to Remove Mocks - FetchDot

## Problem

Tests in `TemperatureGraphRendererFetchDotTest.kt` heavily mock Android classes (`Context`, `Canvas`, `Bitmap`, `Paint`). This is brittle and provides false confidence - mocks test how we *think* the platform works, not how it actually behaves.

## Analysis

### Pure Functions Already Present

The following functions in `TemperatureGraphStyle.kt` are already pure and testable:

| Function | Lines | Tests |
|----------|-------|-------|
| `tempToY(temp, graphTop, graphHeight, minTemp, tempRange)` | 273-275 | Y-position calculation |
| `tempToColor(temp)` | 40-47 | Color based on temperature |
| `formatTemp(value)` | 57-64 | Temperature formatting |
| `formatAgeLabel(ageMinutes, hoursSpanHours)` | 66-73 | Age label formatting |

### Current Test Coverage

`TemperatureGraphRendererFetchDotTest.kt` (290 lines, 9 tests):
- Tests renderGraph behavior with fetch dots
- Mocks Canvas.drawCircle to verify call count and Y positions
- Uses debug callbacks (`FetchDotDebug`) to verify internal state

## Completed Work

### Step 1: Created Pure Unit Tests for TemperatureGraphStyle

Created `app/src/test/java/com/weatherwidget/widget/TemperatureGraphStyleTest.kt`:
- 25 tests covering `tempToY`, `tempToColor`, `formatTemp`, `formatAgeLabel`
- No mocks, no Android dependencies
- All tests pass

### Step 2: Remaining Work (Future)

The mocked tests in `TemperatureGraphRendererFetchDotTest.kt` still exist. Options:

1. **Keep as-is**: The tests verify Canvas interaction which is different from pure function testing
2. **Simplify**: Remove tests that duplicate `TemperatureGraphStyleTest` coverage
3. **Extract more**: Create `FetchDotLayoutUtils` for `shouldDrawFetchDot` logic

## Files Created

- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphStyleTest.kt` (117 lines, 25 tests)

## Test Results

```
773 tests completed, all passed
```
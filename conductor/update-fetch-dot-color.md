# Plan - Simple Color Verification for Fetch Dot Labels

Reposition the "Last Fetch Dot" labels for better clarity: move the staleness indicator (age) underneath the dot and the value (temperature/probability) to the side. Update their color to match the actual temperature line yellow (#F4C542).

## Objective
The current fetch dot labels use white. This plan updates them to use yellow (#F4C542) and implements a simple, non-mocking-heavy test by extending the existing `FetchDotDebug` structure to report the colors used.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**
- **`app/src/test/java/com/weatherwidget/widget/TemperatureFetchDotColorTest.kt`** (New)

## Implementation Steps

### 1. Update FetchDotDebug Data Class
- In `TemperatureGraphRenderer.kt`, add `valueColor: Int? = null` and `stalenessColor: Int? = null` to `FetchDotDebug`.

### 2. Update TemperatureGraphRenderer Logic
- Update `onFetchDotResolved` call to pass `valueTextPaint.color` and `stalenessTextPaint.color`.

### 3. Create Simple Unit Test
- Create `app/src/test/java/com/weatherwidget/widget/TemperatureFetchDotColorTest.kt`.
- This test will:
  - Mock `Context` and basic `Paint`/`Canvas` (relaxed) just to allow the renderer to run.
  - Call `renderGraph` with a sample observation.
  - Use the `onFetchDotResolved` callback to capture the `FetchDotDebug`.
  - Assert that `debug.valueColor == Color.parseColor("#BBF4C542")`.
  - Assert that `debug.stalenessColor == Color.parseColor("#88F4C542")`.

### 4. Cleanup
- Revert the complex `match` and `slot` logic in `TemperatureGraphRendererStalenessTest.kt` to keep it focused on staleness logic, not colors.

## Verification & Testing

### Automated Testing
- Run the new `TemperatureFetchDotColorTest`.
- Run all existing tests.

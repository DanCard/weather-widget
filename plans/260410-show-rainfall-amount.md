# Plan: Display Rainfall Amount on Precipitation Graph

## Context

The user wants to show **rainfall amount** (e.g., ".44in 8a-12p") as a text annotation on the precipitation chance graph when rain probability is 99% or higher. Starting with NWS API data only.

**Good news:** The app already fetches NWS grid QPF (quantitative precipitation forecast) data, interpolates it into hourly amounts, and stores it as `precipAmountMm` in `HourlyForecastEntity`. The data just isn't surfaced in the graph yet. Additionally, `DailyViewLogic.kt` already has locale-aware formatting functions (inches for US/GB, mm otherwise) that we can reuse.

## Implementation Steps

### 1. Extract formatting to shared utility
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/WidgetFormatUtils.kt`

Move these 3 private functions from `DailyViewLogic.kt` (lines 419-447) into `WidgetFormatUtils.kt` as `internal` functions:
- `formatPrecipAmount(amountMm: Float): String`
- `formatInches(amountInches: Float): String`
- `formatMillimeters(amountMm: Float): String`

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- Remove the 3 private functions, call `formatPrecipAmount()` from `WidgetFormatUtils` instead

### 2. Add `precipAmountMm` to `PrecipHourData`
**File:** `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt` (line 22-33)

```kotlin
data class PrecipHourData(
    ...existing fields...
    val precipAmountMm: Float? = null,  // NEW
)
```

### 3. Pass `precipAmountMm` through from PrecipViewHandler
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt` (line 617-629)

Add one line to the `PrecipHourData` constructor call:
```kotlin
precipAmountMm = forecast.precipAmountMm,
```

### 4. Aggregate and render rain amount text on graph
**File:** `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`

**4a. Find contiguous 99%+ rain periods:**
- Walk through `hours` list, find contiguous blocks where `precipProbability >= 99`
- Sum `precipAmountMm` across each block (skip nulls)
- Only emit periods where total > 0
- Record start/end indices and hour labels

**4b. Render text annotation for each period:**
- Format: `".44in 8a-12p"` (amount + time range)
- Position: horizontally centered over the period, vertically in the gradient fill area below the curve
- Paint: white, bold, ~10dp, with shadow for readability
- Basic collision check against existing labels; skip if overlapping

### 5. Verification
- Build: `./gradlew installDebug`
- Check existing unit tests still pass: `./gradlew testDebugUnitTest`
- Visual test: place widget on emulator, check graph during rainy forecast periods
- Check that DailyViewLogic rain labels still work after the refactor

## Key Files
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt` — graph renderer (add data field + rendering)
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt` — data pipeline (pass precipAmountMm)
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — extract formatting functions
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetFormatUtils.kt` — shared formatting home

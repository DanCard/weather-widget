# Fix Daily View Sizing Inconsistencies

## Objective
Address UI rendering inconsistencies between devices (e.g., Samsung vs. Pixel) by making vertical bars wider, increasing the size of forecast labels, and reducing the size of the header text while ensuring consistent scaling units (DP vs SP).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Responsible for rendering the daily forecast graph, including bar widths and label sizes.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`: Responsible for the daily view layout, including the top header text (current temperature and date).

## Implementation Steps

### 1. `DailyForecastGraphRenderer.kt`
- **Wider Vertical Bars:**
  - Update `barWidth` base calculation from `5.5f` to `8.0f`.
  - Update `tripleBarWidth` base calculation from `3.5f` to `5.0f`.
- **Larger Forecast Labels:**
  - Update `tempLabelHeight` base calculation from `18f` to `22f`.

### 2. `DailyViewHandler.kt`
- **Consistent Text Sizes & Units:**
  - Rename `HEADER_DATE_TEXT_SIZE_SP` to `HEADER_DATE_TEXT_SIZE_DP`.
  - Change the value of `HEADER_DATE_TEXT_SIZE_DP` from `26f` to `22f`.
  - Change the value of `CURRENT_TEMP_TEXT_SIZE_DP` from `26f` to `22f`.
  - Update all usages of `HEADER_DATE_TEXT_SIZE_SP` to use `HEADER_DATE_TEXT_SIZE_DP`.
  - Ensure the header date text size is applied using `TypedValue.COMPLEX_UNIT_DIP` instead of `COMPLEX_UNIT_SP`.
  - Update `textWidthPx` to accept a `unit` parameter or assume DP for the header date calculation to ensure accurate width measurements during layout placement.

## Verification & Testing
- Run emulator tests (`./scripts/emulator-tests.sh`) to verify no regressions in touch targets or layout.
- Launch the widget on an emulator and visually verify the new sizes and consistency between the header and graph labels.
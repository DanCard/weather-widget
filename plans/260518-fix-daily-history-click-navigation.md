# Plan: Fix Daily Forecast History Navigation

## Objective
Reproduce and fix the issue where clicking a historical day (3 days back) in the daily forecast view fails to navigate to the hourly temperature graph.

## Background
Currently, `WeatherWidgetProvider.hasHourlyDataForDate` only checks for the presence of hourly forecasts. For historical days, the app may only have observations, which are sufficient to render the hourly temperature graph. The daily view shows these days if observations exist, but clicking them fails because the validation check is too strict.

## Proposed Changes

### 1. Verification (Robolectric Test)
Create a new Robolectric test `app/src/test/java/com/weatherwidget/widget/handlers/DailyHistoryClickIntentRoboTest.kt` to verify that `DailyClickHandlerFactory` builds the correct intent for historical days.

**Test Scenario:**
- GIVEN: A daily forecast view for 3 days ago.
- WHEN: Building the click intent for that day.
- THEN: The intent should have `EXTRA_TARGET_VIEW` set to `TEMPERATURE` (since history routing is disabled) and `EXTRA_HOURLY_OFFSET` set to approximately -72.

### 2. Verification (Integrated Test)
Keep the integrated test `app/src/androidTest/java/com/weatherwidget/widget/handlers/DailyHistoryClickIntegrationTest.kt` to reproduce the failure in the full app context (where `hasHourlyDataForDate` blocks navigation).

### 3. Implementation
Modify `WeatherWidgetProvider.hasHourlyDataForDate` to check the `observations` table for past dates if no hourly forecasts are found.

### 4. Verification
Run both Robolectric and Integrated tests to confirm the fix.

## Implementation Steps

### Phase 1: Robolectric Verification
1. Create `app/src/test/java/com/weatherwidget/widget/handlers/DailyHistoryClickIntentRoboTest.kt`.
2. Run the unit test: `./gradlew test --tests com.weatherwidget.widget.handlers.DailyHistoryClickIntentRoboTest`.

### Phase 2: Integrated Reproduction Test
1. Run `DailyHistoryClickIntegrationTest.kt` using `./scripts/emulator-tests.sh` and confirm failure.

### Phase 3: Fix
1. Update `WeatherWidgetProvider.hasHourlyDataForDate` in `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`.
   - Add a check for observations if the target date is in the past.

### Phase 4: Final Verification
1. Run all tests again and confirm success.

# Objective
Investigate and fix missing Saturday forecast data on the Samsung device in the Daily Forecast view.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`

# Implementation Steps
1. Add diagnostic logging in `DailyViewLogic.kt` inside `prepareTextDays` and `prepareGraphDays`.
2. The logging should output the date, `weatherByDate` contents for that date, `hasData` status, and high/low temps. This will help determine if the data is missing from the database (null `weather`) or being filtered out by UI logic.
3. Add a log statement tracking the contents of `weatherByDate` keys passed to these functions.

# Verification & Testing
1. Review the code to ensure logging does not crash or cause performance issues.
2. User will observe logs on the Samsung device using `adb logcat -s DailyViewLogic`.
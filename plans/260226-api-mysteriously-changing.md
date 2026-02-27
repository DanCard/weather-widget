# Plan: Add logging for API source order changes

## Context
The user's API priority order (NWS, WeatherAPI, Meteo) keeps mysteriously changing to (NWS, Meteo, WeatherAPI). Currently there is **no logging** when the source order is modified — the app_logs DB on Samsung is empty. We need to add logging to `setVisibleSourcesOrder` and related paths so the next occurrence can be diagnosed.

There is no smoking-gun bug in the code — `setVisibleSourcesOrder` is only called from `SettingsActivity` (checkbox, up, down). However, one subtle risk: `SettingsActivity.allSources` (line 84) is hardcoded as `[NWS, OPEN_METEO, WEATHER_API]`, which means unchecking+rechecking a source appends it in that order, not the user's preferred order.

## Changes

### 1. Add logging to `WidgetStateManager.setVisibleSourcesOrder()`
**File:** `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`

- Inject `AppLogDao` into `WidgetStateManager`
- In `setVisibleSourcesOrder()`, log the old and new order with tag `SOURCE_ORDER`
- Use a coroutine scope (or fire-and-forget) since `AppLogDao.log()` is a suspend function
- Also log in `migrateApiPreferenceIfNeeded()` if migration actually runs

### 2. Add logging to `SettingsActivity` source changes
**File:** `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`

- Log which action triggered the change: "checkbox toggled [source] on/off", "moved [source] up/down"
- This gives us the UI action context alongside the WidgetStateManager log

### 3. Add logcat logging (non-DB) as lightweight fallback
Since the DB logging requires a suspend context, also add `Log.d("SOURCE_ORDER", ...)` directly in `setVisibleSourcesOrder()` so it shows up in logcat even if DB logging fails.

## Key files
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` — add logging to `setVisibleSourcesOrder()` and `migrateApiPreferenceIfNeeded()`
- `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt` — add action-context logging
- `app/src/main/java/com/weatherwidget/data/local/AppLogEntity.kt` — existing `AppLogDao.log()` extension

## Verification
1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Open Settings, change API source order, check app logs screen shows the changes
3. `adb logcat -s SOURCE_ORDER` to verify logcat output

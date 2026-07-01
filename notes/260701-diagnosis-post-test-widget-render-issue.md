# Diagnosis and Options: Post-Test Widget Rendering Issues

## Background
Following the execution of Android instrumented tests on an emulator (via `./scripts/emulator-tests.sh`), the weather widget on the home screen occasionally became "stuck" showing the default XML layout (`--° --°` / loading spinner) instead of rendering its graphical weather charts.

---

## Technical Diagnosis

The issue stems from a mismatch between the **app process lifetime** and the **launcher widget host's view state** after test runs:

1. **Process-Scoped Paint Cache**: In [WidgetRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt), the daily view uses an in-memory set to skip redundant, expensive redraws during lightweight UI-only updates:
   ```kotlin
   private val fullyPaintedDailyWidgetIds: MutableSet<Int> =
       java.util.concurrent.ConcurrentHashMap.newKeySet()
   ```
2. **Test Process Preservation**: Gradle runs tests and may install/re-run packages while reusing or preserving the active app process. During this, the widget ID (e.g., `53`) gets painted and stored in `fullyPaintedDailyWidgetIds`.
3. **Launcher Discard**: The launcher widget host resets the widget views (re-inflating the base layout defined in XML), discarding the custom painted bitmap.
4. **Stranded Update Skip**: When a subsequent UI-only update is scheduled, `shouldSkipDailyUiOnlyRepaint` checks the cache, sees that widget `53` was "already painted" in this process instance, and skips redrawing. The widget remains blank/loading indefinitely.

---

## Options for Resolution

### Option 1: Clean Process & Waking Broadcast in Test Harness (Implemented)
Update the test runner script (`scripts/emulator-tests.sh`) to perform clean recovery actions at the end of runs:
- **`am force-stop com.weatherwidget`**: Kills the app process, cleanly resetting the in-memory `fullyPaintedDailyWidgetIds` set.
- **`am broadcast ... -f 0x00000020`**: Broadcasts `ACTION_REFRESH` with the `FLAG_INCLUDE_STOPPED_PACKAGES` flag, ensuring the stopped app wakes up to execute a fresh, full widget paint.

*Pros*: Zero production code footprint; keeps the optimization active in production while resolving the developer-facing post-test bug.

---

### Option 2: Reset Paint Cache in App Lifecycle Callbacks (Production Fix)
Expose a helper to invalidate a widget's daily paint cache and trigger it in the widget provider's lifecycle callbacks:
- **`WidgetRenderer.clearDailyPainted(id)`**
- Invoke it in [WeatherWidgetProvider.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt) during:
  - **`onUpdate`**: Called when the widget is created, restored, or updated by the system (e.g., on launcher boot/restart).
  - **`onAppWidgetOptionsChanged`**: Called when the widget size is adjusted.

*Pros*: Robust; self-heals in production if launcher crash/re-inflation occurs while the app process is alive.

---

### Option 3: Remove the Daily Skip Optimization Entirely (Production Fix)
Since rendering the static daily graph takes less than 2 milliseconds of CPU time, we could remove the daily paint-skipping logic completely.

*Pros*: Simplifies code; eliminates the entire class of cached/stale state bugs.
*Cons*: Increases CPU cycles slightly during high-frequency alarm ticks (still negligible).

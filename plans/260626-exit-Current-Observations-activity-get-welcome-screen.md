# Samsung: closing Current Observations lands on the Welcome screen

## Context

On Samsung (One UI Home), tapping **Close** (or back / title) in the **Current Observations**
activity takes the user to the **"Welcome to Weather Widget"** screen (`MainActivity`). It should
return to the home screen instead — `MainActivity` should never appear from this path.

**Root cause (structural, not the click handler):**
- `WeatherObservationsActivity` (`AndroidManifest.xml:61-64`) declares **no `taskAffinity`** and no
  `launchMode`, so it inherits the app's default affinity — the *same task* as `MainActivity`, which
  owns the `MAIN`/`LAUNCHER` "Welcome" screen (`AndroidManifest.xml:21-29`).
- The widget launches it with `FLAG_ACTIVITY_NEW_TASK`
  (`TemperatureTouchTargets.kt:setupWeatherStationsShortcut`, ~line 288). On Samsung this brings the
  existing app task (rooted at `MainActivity`) forward / reuses it, so the three `finish()` calls
  (`WeatherObservationsActivity.kt:113-115`) reveal `MainActivity` underneath instead of home.
- Same family as the logged [[samsung_dead_zone_launches_mainactivity]] / `MAIN_LAUNCH` fallback.

This change adds a regression test **and** fixes the root cause.

## Fix

Give `WeatherObservationsActivity` its own task so `FLAG_ACTIVITY_NEW_TASK` can never attach it to
`MainActivity`'s task. In `AndroidManifest.xml:61-64`, add a distinct empty task affinity:

```xml
<activity
    android:name=".ui.WeatherObservationsActivity"
    android:exported="false"
    android:taskAffinity=""
    android:label="Current Observations" />
```

Notes:
- `taskAffinity` only takes effect when a launch uses `FLAG_ACTIVITY_NEW_TASK` — i.e. only the widget
  shortcut path. In-app launches (without that flag) stay in the caller's task regardless, so other
  navigation is unaffected.
- An empty affinity puts the activity in a task not shared with any affinity-based activity; finishing
  returns to the previous foreground task (the launcher home), not `MainActivity`.
- The `finish()` click handlers (`:113-115`) are left as-is — they are correct once the task is
  isolated. (Optional, not planned: `android:excludeFromRecents="true"` to also keep it out of
  recents; omitted to keep the change minimal.)

## Test (regression, Robolectric)

Add two tests to the existing
`app/src/test/java/com/weatherwidget/ui/WeatherObservationsActivityRobolectricTest.kt` (reuses its
`@Config(sdk=[35])` + `LongDuration` setup, in-memory `TestDatabase`, and the `launchActivity()`
helper). Robolectric cannot replay Samsung's multi-task launcher fallback, so the test asserts the
*invariant* that makes the fallback impossible plus the close-handler contract.

**Test 1 — task isolation (the structural root-cause guard; fails before the manifest fix):**
```kotlin
@Test
fun `observations activity does not share a task with the welcome MainActivity`() {
    val pm = context.packageManager
    val obsInfo = pm.getActivityInfo(
        ComponentName(context, WeatherObservationsActivity::class.java), 0)
    val mainInfo = pm.getActivityInfo(
        ComponentName(context, MainActivity::class.java), 0)
    assertNotEquals(
        "Observations must live in its own task so closing it cannot reveal the Welcome screen",
        mainInfo.taskAffinity,
        obsInfo.taskAffinity,
    )
}
```

**Test 2 — Close finishes without launching anything (mirrors the user's action):**
```kotlin
@Test
fun `clicking Close finishes the activity without launching MainActivity`() {
    launchActivity().onActivity { activity ->
        activity.findViewById<Button>(R.id.close_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Close must finish the activity", activity.isFinishing)
        assertNull(
            "Close must not start another activity (the Welcome screen)",
            shadowOf(activity).nextStartedActivity,
        )
    }
}
```

New imports needed in the test file: `android.content.ComponentName`, `android.widget.Button`,
`org.junit.Assert.assertNotEquals` (`MainActivity` is same package; `assertNull`/`assertTrue`/
`shadowOf`/`Looper` already imported).

## Files to modify

- `app/src/main/AndroidManifest.xml` — add `android:taskAffinity=""` to the
  `WeatherObservationsActivity` declaration.
- `app/src/test/java/com/weatherwidget/ui/WeatherObservationsActivityRobolectricTest.kt` — add the two
  tests + imports.

## Verification

1. Run the test class:
   `./gradlew testDebugUnitTest --tests "com.weatherwidget.ui.WeatherObservationsActivityRobolectricTest"`
   — both new tests pass with the manifest fix; Test 1 fails if the fix is reverted (confirms it
   guards the right thing).
2. Manual (Samsung, optional): open the widget's Current Observations screen, tap Close — should
   return to the home screen, not the "Welcome to Weather Widget" screen. Cross-check `app_logs` shows
   no new `MAIN_LAUNCH` row after closing.

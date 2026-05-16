# Session Log: Home Button Intercepted by Sticky `dual_touch_zone`

**Date**: Friday, May 15, 2026

## Problem Statement

On the attached Pixel 7 Pro physical device, when viewing the temperature graph and tapping the home icon in the top-center header:

1. The widget briefly transitions to the daily forecast view.
2. The widget then reverts to the temperature graph view.

Expected behavior: tapping the home button should leave the widget on the daily forecast view.

This is the second incident of the same root-cause class as the `260514-emulator-home-button-and-missing-api-icons.md` session — a sticky-visibility leak in RemoteViews. This time it manifests on a physical Pixel 7 Pro (not the emulator) because `dual_touch_zone` is layout-visible on a wide widget, overlaps `home_touch_zone`, and is declared later in the FrameLayout so it intercepts the tap.

## Conversation History & Prompts

### Prompt 1
> "On attached pixel pro 7, on temperature graph screen, I hit the home button, goes to daily forecast view, then returns to temperature graph screen. Should stay on daily forecast view. Review logs, and logging if helpful."

**Action**: Launched an Explore subagent to map the view-mode/home-button flow. In parallel, ran `adb devices` to confirm the Pixel 7 Pro was attached. Three devices were listed (USB Pixel, TLS-mirrored Pixel, Samsung Fold) — targeted the Pixel via `adb -s 2A191FDH300PPW`.

Key findings from exploration:

- `ViewMode` enum in `WidgetStateManager.kt:16-25` (DAILY, TEMPERATURE, PRECIPITATION, CLOUD_COVER)
- View mode persisted per-widget in SharedPreferences via `KEY_VIEW_MODE_PREFIX` (`WidgetStateManager.kt:67`)
- Home button setup in `TemperatureTouchTargets.kt:230-256` (`setupHomeShortcut`), binds `R.id.home_icon`, `R.id.home_touch_zone`, `R.id.home_touch_zone_inline` to `ACTION_SET_VIEW` with `EXTRA_TARGET_VIEW=DAILY`
- Handler chain: `onReceive` → `handleSetViewAction` (`WeatherWidgetProvider.kt:769-788`) → `WidgetIntentRouter.handleSetView` → `handleSetViewInternal` (`WidgetIntentRouter.kt:588-642`), which writes `setViewMode(DAILY)` to prefs *before* the render call

Pulled recent logcat (`adb logcat -d`) and saw an earlier `ACTION_TOGGLE_DUAL_BARS` event at 22:10:14 — widget 74 was in TEMPERATURE mode, the dual-bars handler rendered DAILY at 22:10:15.730, then a UI-only worker re-rendered TEMPERATURE at 22:10:18. That timeline exactly matched the symptom the user described.

Started a fresh background logcat capture filtered to widget tags so the user could reproduce live:

```bash
adb -s 2A191FDH300PPW logcat -v time -s WeatherWidgetProvider:V WidgetIntentRouter:V \
  WidgetRenderer:V WidgetStateManager:V HomeShortcut:V DailyViewHandler:V \
  TemperatureViewHandler:V WIDGET_LIFECYCLE:V WIDGET_PAINT:V CLICK_DAILY:V \
  > /tmp/widget_home_bug.log
```

---

### Prompt 2
> "I reproduced it"

**Action**: Read `/tmp/widget_home_bug.log`. The captured action was `com.weatherwidget.ACTION_TOGGLE_DUAL_BARS`, not `ACTION_SET_VIEW`. The sequence:

```
22:12:36.422  onReceive: action=com.weatherwidget.ACTION_TOGGLE_DUAL_BARS
22:12:36.430  handleToggleDualBars: widget=74 showTwoBars=true
22:12:37.013  DailyViewHandler.updateWidget [START] widgetId=74
22:12:37.142  WIDGET_PAINT widget=74 caller=DAILY state=data
22:12:37.146  triggerUiOnlyUpdate: Enqueueing UI-only worker (reason=dual_bars_toggle)
22:12:38.874  worker_paint_start uiOnly=true
22:12:38.880  WidgetRenderer.updateWidgetInternal: widget=74 viewMode=TEMPERATURE
22:12:39.225  WIDGET_PAINT widget=74 caller=TEMPERATURE state=data
```

Diagnosis: the user tapped where the home icon is shown, but a *different* view (`dual_touch_zone`) intercepted the tap. The dual-bars handler renders DAILY as a preview, then schedules a UI-only worker that re-reads the actual stored view mode (TEMPERATURE) and re-renders it.

Grepped for `ACTION_TOGGLE_DUAL_BARS` binding: `TemperatureTouchTargets.kt:179` (`setupDualToggle`) binds the action to `R.id.dual_touch_zone`. Read the layout XML:

| Zone | Width | Position | Range (on 373dp widget) |
|------|-------|----------|-------------------------|
| `home_touch_zone` (line 1492) | 48dp × 80dp | `top|center_horizontal`, `marginTop=-4dp` | x ∈ [162.5, 210.5], y ∈ [-4, 76] |
| `dual_touch_zone` (line 1558) | 80dp × 50dp | `top|end`, `marginEnd=150dp`, `marginTop=-4dp` | x ∈ [143, 223], y ∈ [-4, 46] |

`dual_touch_zone` fully overlaps the top 50dp of `home_touch_zone` and is declared AFTER `home_touch_zone` in the XML — in a `FrameLayout`, later children draw on top, so they intercept taps in any overlap region.

---

### Prompt 3 (interrupted earlier message)
> "dual touch zone should not be temperature view"

**Action**: User confirmed the diagnosis. Read the binders to verify which one(s) set `dual_touch_zone` VISIBLE and which (incorrectly) leave it sticky:

- `DailyViewHandler.kt:600-603` — sets `R.id.dual_touch_zone` to VISIBLE when `showDualButton && !isIconWidth`
- `TemperatureViewBinder.kt` — never touches it
- `PrecipViewHandler.kt` — never touches it
- `CloudCoverViewHandler.kt` — never touches it
- `HeaderRemoteViewsBinder.kt:84` (`hideIconWidthControls`) — sets it GONE, but only called for narrow `isIconWidth` widgets

Conclusion: in any wide-widget non-daily view, `dual_touch_zone` retains the VISIBLE state from the prior DAILY render with dual-bars enabled. This matches the existing `remoteviews_visibility_is_sticky` memory note exactly — a missing-reset, not a logic bug.

Plan-mode exited unexpectedly mid-investigation (the system surfaced an "Exited Plan Mode" sysreminder). Continued the diagnosis and presented the root cause + proposed three-line fix in the chat.

---

### Prompt 4
> "apply the fix"

**Action**: Added `views.setViewVisibility(R.id.dual_touch_zone, View.GONE)` to three non-daily binders, alongside their existing header-reset visibility calls:

- `TemperatureViewBinder.kt:32` (just after `header_date_center`/`header_date_right` GONE)
- `PrecipViewHandler.kt:77`
- `CloudCoverViewHandler.kt:130`

Each got a one-line comment: `// Reset sticky visibility from DailyViewHandler — dual_touch_zone is only meaningful in DAILY view.`

Built and installed on all attached devices (`./gradlew installDebug -q` → "Installed on 3 devices").

---

### Prompt 5
> "Should we add a test so that home button goes to daily forecast view?"

**Action**: Plan-mode re-entered. Explored existing test patterns:

- `DualToggleTouchZoneRoboTest.kt` — Robolectric tests over `widget_weather.xml` layout + `setupDualToggle` PendingIntent assertions
- `HistoryIconVisibilityRoboTest.kt` — mocks `AppWidgetManager`, captures `RemoteViews` via `slot()`, applies to a `FrameLayout`, asserts visibility
- `CurrentTempTouchRoutingRoboTest.kt:255` — uses `reapply()` instead of `apply()` — the only test in the codebase that exercises the sticky-state path
- `TemperatureTouchRoutingRoboTest.kt` — uses `shadowApp.broadcastIntents` to verify that clicking a view broadcasts the expected intent action

Key realization: a plain `apply()` test passes whether or not the binder emits the explicit `setViewVisibility(GONE)` action — because the XML default for `dual_touch_zone` is `gone`, a fresh inflate already matches the expected state. To catch the sticky-state regression, the test must:

1. `apply()` to a fresh root
2. Manually set `dual_touch_zone.visibility = VISIBLE` (simulating prior DAILY state)
3. `reapply()` the same RemoteViews
4. Assert `dual_touch_zone.visibility == GONE`

If the RemoteViews contains the explicit GONE action (post-fix), reapply resets the visibility. If not (pre-fix), the sticky VISIBLE survives.

Proposed two test additions:

- New: `DualTouchZoneStickyVisibilityRoboTest.kt` — three reapply-based regression tests (one per non-daily handler).
- Edit: `TemperatureTouchRoutingRoboTest.kt` — add a positive test that clicking `home_icon` and `home_touch_zone` broadcasts `ACTION_SET_VIEW` with `EXTRA_TARGET_VIEW=DAILY`. (Note: this part never broke; it covers future regressions if someone accidentally rebinds the home icon.)

---

### Prompt 6
> "add both"

**Action**: Wrote both tests.

- **`DualTouchZoneStickyVisibilityRoboTest.kt`** — three `@Test` methods, one for each of `TemperatureViewHandler.updateWidget`, `PrecipViewHandler.updateWidget`, `CloudCoverViewHandler.updateWidget`. All share `assertReapplyResetsDualTouchZone(views)` helper that does the four-step apply→mutate→reapply→assert pattern.
- **`TemperatureTouchRoutingRoboTest.kt`** — added one `@Test` titled `home icon broadcasts ACTION_SET_VIEW with target DAILY`. It iterates over `R.id.home_icon` and `R.id.home_touch_zone`, calls `performClick()` on each, drains `shadowApp.broadcastIntents`, and asserts:
  - `intent.action == WidgetActions.ACTION_SET_VIEW`
  - `intent.getStringExtra(EXTRA_TARGET_VIEW) == ViewMode.DAILY.name`
  - `intent.getIntExtra(EXTRA_APPWIDGET_ID, -1) == appWidgetId`

Ran both:

```
./gradlew testDebugUnitTest \
  --tests "com.weatherwidget.widget.handlers.DualTouchZoneStickyVisibilityRoboTest" \
  --tests "com.weatherwidget.widget.handlers.TemperatureTouchRoutingRoboTest.home icon broadcasts ACTION_SET_VIEW with target DAILY"
```

Result: **all 4 tests PASSED**. `BUILD SUCCESSFUL in 15s`.

---

### Prompt 7
> "yes" (in response to: should I save a memory tying together the sticky-visibility class of bug + the reapply test pattern?)

**Action**:

- Updated `remoteviews_visibility_is_sticky.md` — appended a "Second incident (2026-05-15)" section describing the `dual_touch_zone` instance, the layout-overlap mechanism, and a link to the new test-pattern memory.
- Created `reapply_test_pattern.md` — documents the four-step apply→mutate→reapply→assert technique, references `DualTouchZoneStickyVisibilityRoboTest.kt` and `CurrentTempTouchRoutingRoboTest.kt:255` as canonical examples, and flags that the older "no mocking framework" line in `testing-strategy.md` is stale (widget tests use MockK+Robolectric extensively).
- Added a line to `MEMORY.md` indexing the new test-pattern memory.

---

### Prompt 8
> "write a detailed session log to session-logs/ dir, include all prompts and your summary note"

**Action**: This file.

## Root Cause

`R.id.dual_touch_zone` is set to `View.VISIBLE` by `DailyViewHandler.kt:600-603` when the "show two bars on daily" setting is enabled and the widget is wide enough. None of the three non-daily binders (`TemperatureViewBinder`, `PrecipViewHandler`, `CloudCoverViewHandler`) ever set it back to GONE. Because `RemoteViews` updates are deltas — visibility set by one render persists into the next — the touch zone stays VISIBLE forever once enabled in daily mode, across all subsequent view-mode changes.

On a wide widget (~373dp), `dual_touch_zone` (80dp wide, `top|end`, `marginEnd=150dp`) overlaps `home_touch_zone` (48dp wide, `top|center_horizontal`). Because it is declared later in the `FrameLayout`, it draws on top and intercepts taps in the overlap region. A user tapping the visible home icon hits `dual_touch_zone`, which fires `ACTION_TOGGLE_DUAL_BARS` instead of `ACTION_SET_VIEW(DAILY)`. The dual-bars handler renders DAILY as a preview, then schedules a UI-only worker that re-reads the stored view mode (TEMPERATURE) and re-renders it — producing the "daily flashes then reverts" symptom.

## Why It Manifests Only Sometimes

- Only reproduces when the user has enabled "show two bars on daily" *and* visited DAILY mode (so `dual_touch_zone` was set VISIBLE).
- Only on wide widgets (~373dp+). Narrow widgets call `hideIconWidthControls`, which already includes `dual_touch_zone` GONE.
- The XML default is `visibility="gone"`, so on a fresh install or after a launcher cache flush, the bug temporarily doesn't reproduce.

## Files Changed

### Source (fix)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewBinder.kt:32` — added `setViewVisibility(R.id.dual_touch_zone, View.GONE)` with comment
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt:77` — same
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt:130` — same

### Tests (new + extended)
- `app/src/test/java/com/weatherwidget/widget/handlers/DualTouchZoneStickyVisibilityRoboTest.kt` (new, 175 lines) — three reapply-based regression tests
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureTouchRoutingRoboTest.kt` — added `home icon broadcasts ACTION_SET_VIEW with target DAILY` test

### Memory (knowledge capture)
- `~/.claude/projects/.../memory/remoteviews_visibility_is_sticky.md` — appended 2026-05-15 incident
- `~/.claude/projects/.../memory/reapply_test_pattern.md` (new) — test technique documentation
- `~/.claude/projects/.../memory/MEMORY.md` — indexed new memory

## Verification

- `./gradlew testDebugUnitTest --tests "...DualTouchZoneStickyVisibilityRoboTest" --tests "...TemperatureTouchRoutingRoboTest.home icon broadcasts..."` → 4/4 PASS
- `./gradlew installDebug` → "Installed on 3 devices"
- Manual: tapping the home icon in temperature graph on the Pixel 7 Pro should now route to `ACTION_SET_VIEW(DAILY)` and leave the widget on daily forecast.

## Summary Note

This was a recurrence of the bug class first documented on 2026-05-14 in `remoteviews_visibility_is_sticky`. The lesson is structural, not incidental: any view whose visibility varies per view-mode must be explicitly set by every binder that participates in the mode rotation. XML defaults do not save you — they are overwritten on the first render and never restored.

Two pieces of knowledge added to memory:

1. The bug-class memory (`remoteviews_visibility_is_sticky.md`) now records that `dual_touch_zone` joined `api_source`/`settings_icon`/`weather_icon` in this category, and that the failure mode includes layout-overlap interception, not just disappearing controls.
2. A new test-pattern memory (`reapply_test_pattern.md`) captures the `apply → mutate → reapply → assert` technique that distinguishes "test of XML defaults" from "test of binder behavior." Without `reapply()`, a sticky-state test silently passes against a broken binder. The pattern was already present once in the codebase (`CurrentTempTouchRoutingRoboTest.kt:255`) but undocumented; it should now be the default approach for any new visibility/state-reset test.

The bug-class memory also notes the layout-overlap mechanism so that future bugs of the form "tap on X fires Y's intent" can short-circuit to a layout audit, not just a binding audit.

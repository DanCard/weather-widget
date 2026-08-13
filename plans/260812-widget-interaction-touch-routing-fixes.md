# Implementation Plan — Widget Interaction & Touch Routing Review Fixes

Date: 2026-08-12
Source review: `plans/260812-widget-interaction-touch-routing-review.md`

Fixes are implemented in independently verifiable phases. Each phase pauses for user verification.

## Phase 1 — High: serialize day-click state mutations under the per-widget mutex (review finding #1)

The two-phase day-click flow (`WidgetDayClickCoordinator`) mutates `WidgetStateManager`
(`setTransientMessage`, `setZoomLevel(WIDE)`) outside `WidgetInteractionCoordinator`'s mutex,
bypassing the invariant every other interaction path honors.

Changes:
1. Route `ACTION_DAY_CLICK` and `ACTION_NO_HOURLY_REFRESH_COMPLETE` through
   `WidgetIntentRouter.handleDayClick` / `handleRefreshComplete`, which wrap the whole transition
   in `runInteraction` (`DAY_CLICK` / `NO_HOURLY_COMPLETE` tags).
2. Inside `WidgetDayClickCoordinator.navigateToHourlyView`, replace the inner
   `WidgetIntentRouter.handleSetView(...)` with a lock-free `WidgetIntentActionHandler.setView(...)`
   call — the outer `runInteraction` already holds the mutex, so re-entering it would deadlock.
3. Remove the now-redundant `setZoomLevel(appWidgetId, ZoomStage.WIDE)` for the precipitation
   branch — `WidgetIntentActionHandler.setView` already resets zoom to WIDE whenever the previous
   mode is DAILY (always true on the day-click path).
4. Update the two breadcrumb tests to assert `DAY_CLICK_RENDER_OK` / `DAY_CLICK_FAIL` instead of
   `SET_VIEW_RENDER_OK` / `SET_VIEW_FAIL`:
   - `app/src/test/.../WeatherWidgetProviderDayTapSourceGapRoboTest.kt`
   - `app/src/androidTest/.../DayTapSourceGapInstrumentedTest.kt`

Verification: `:app` Robolectric batch for the day-click/router tests.

## Phase 2 — Medium: dead code removal (findings #2, #6)

- Remove `WidgetRequestCodes.dualToggle` (no callers).
- Remove the dead `showHistory` branch: `DayClickHelper.shouldShowHistory` is hardcoded `false`, so
  `WidgetDayClickCoordinator.navigateToHistory` and the `showHistory` branch are unreachable via
  normal day taps (history is reachable only via the dedicated icon, which sets `showHistory=true`
  directly). Delete the dead branch/extras or document the intentional split.

## Phase 3 — Medium: localization + dead-zone UX (finding #4)

- Move the hardcoded English toasts ("No additional history available", "No more forecast
  available", "Dead zone tapped") into `res/values/strings.xml`.
- Make the `widget_root` dead-zone catch-all silent (no user-visible toast); keep it as a
  no-op/VERBOSE-log absorber.

## Phase 4 — Medium: log severity + guard dedupe (finding #5)

- Downgrade `WidgetActionReceiver.handleCycleZoom`'s `Log.e` to `Log.d`/`Log.w` (benign
  stale-PendingIntent case) and dedupe the DAILY-mode guard with
  `WidgetIntentActionHandler.cycleZoom`.

## Phase 5 — Low: documentation/comments (findings #3, #7, #8, #9, #10)

- Document the division between `WidgetUpdateTracker` and `WidgetActionJobRegistry`.
- Note `WidgetInteractionCache.loadMutexes` non-eviction and lazy entry expiry.
- Guard/comment `WidgetRequestCodes` `id * 10000` int-overflow bound.
- Document that `runInteraction` swallows exceptions after logging (best-effort render).
- Comment the three overlapping coalescing mechanisms (onUpdate debounce, tracker cancel, resize
  debounce).

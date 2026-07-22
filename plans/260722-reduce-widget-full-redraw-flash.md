# Reduce distracting full widget redraws before considering cache-drop recovery

**Date:** 2026-07-22  
**Status:** Proposed — no implementation approved  
**Scope:** Android `RemoteViews` delivery. Make ordinary widget refreshes visually stable on the
Samsung launcher, then make an evidence-based decision about the separate launcher-cache-drop
hole described in `plans/260722-widget-unbound-body-launcher-cache-drop.md`.

## User-facing outcome

The widget must not visibly tear down and redraw during normal background refreshes. A full
`updateAppWidget()` is allowed only when it is genuinely needed to establish or replace the
widget hierarchy (placement/startup, resize, or an explicit interaction), and never as a routine
periodic workaround for an uncertain launcher state.

This plan explicitly rejects cache-drop option A (make every complete-body repaint full): that
would reintroduce the exact Samsung flash removed by the 2026-07-11 worker partial-push change.

## Known facts and constraints

- `updateAppWidget()` makes Samsung's launcher replace/re-inflate the widget tree; that is the
  distracting flash. `partiallyUpdateAppWidget()` patches a backed tree in place.
- Worker-driven data paints already pass `partialPush = true` through
  `WeatherWidgetWorker.updateAllWidgets()` to `WidgetRenderer` and the four view handlers. Keep
  that behaviour. It was implemented specifically to stop the prior ~20-minute flash.
- Full paths still exist and are intentional in some places: provider `onUpdate`, startup,
  resize, user interactions, loading/error/warning fallbacks, and the first complete-body paint
  of a new process. Their current cadence and overlap are not yet measured on the release build
  carrying the new `WIDGET_PUSH` breadcrumbs.
- The 2026-07-11 investigation deliberately left an `onUpdate` followed by `ACTION_REFRESH`
  full-paint pair alone because the refresh was the blank-widget self-heal. Do not simply remove
  that second path without proving that one canonical full has already bound the widget.
- The launcher-cache-drop test proves an app-side delivery hole, but does not prove it caused the
  basketball incident. The first observed recovery after a long gap must not be made full merely
  because it is convenient.
- The Samsung already runs a debug build. Use a compatible debug update that installs in place,
  preserving its signing identity, widget instances, and app data while delivering telemetry.

## Non-goals

- Do not change the desired TEMPERATURE now-line behaviour or its UI-only cadence.
- Do not make all complete-body pushes full, force periodic fulls, or add a timer-based cache
  recovery heuristic in this work.
- Do not infer launcher cache state from `AppWidgetManager`; Android exposes no such signal.
- Do not treat a Robolectric/FakeLauncher result as proof of Samsung launcher behaviour.

## Plan

### 1. Make every visible full delivery attributable

**Why:** Existing `WIDGET_PUSH` rows now preserve every dispatcher full, but an on-device flash
must be traceable to the initiating path, not merely to a handler such as `DAILY` or
`TEMPERATURE`. Direct `updateAppWidget()` fallback branches also bypass the dispatcher breadcrumb.

1. Inventory every `updateAppWidget()` / `WidgetPushDispatcher.push()` call and classify it as
   one of: `PROVIDER_ON_UPDATE`, `WORKER_FETCH`, `WORKER_CACHE`, `UI_ONLY`, `ACTION_REFRESH`,
   `USER_INTERACTION`, `RESIZE`, `LOADING`, `ERROR`, or `DEGENERATE_DATA`.
2. Thread a small delivery-origin value from the entry point through `WidgetRenderer` and the
   handlers to `WidgetPushDispatcher.push()`. Include it in both `WIDGET_PAINT` and `WIDGET_PUSH`
   rows, alongside requested/effective `partial|full` and any promotion reason.
3. Route direct full fallback branches through the dispatcher where their existing dependencies
   permit it; otherwise emit the same `WIDGET_PUSH` schema immediately before the direct call.
   Preserve their existing full-delivery semantics in this instrumentation change.
4. Add pure message-format tests and a dispatcher test for requested mode versus effective mode,
   so a promoted initial partial is reported as `effective=full`, not as an ambiguous partial.

**Files expected to change:**

- `app/src/main/java/com/weatherwidget/widget/WidgetPushDispatcher.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/{WidgetViewHandler,DailyViewHandler,TemperatureViewHandler,PrecipViewHandler,CloudCoverViewHandler,WidgetIntentRouter}.kt`
- `app/src/test/java/com/weatherwidget/widget/WidgetPushDispatcherTest.kt`

### 2. Capture an on-device baseline before changing delivery policy

1. Build and install the attribution breadcrumbs as a compatible debug update over the existing
   Samsung debug app. Verify the update retains its widget instances and app data.
2. When a redraw is noticed, record its approximate wall-clock time and whether it followed
   unlock, an hourly boundary, a tap/resize, a location change, charging, or a return from idle.
3. Pull `app_logs` after at least several ordinary refresh cycles and each reported redraw.
   Group full deliveries by `widget`, `origin`, `caller`, `pid`, and time gap; correlate them
   with `WIDGET_LIFECYCLE` sequence markers and the user-observed time.
4. Produce a short cadence table: full pushes per widget/day, fulls by origin, and clusters of
   two or more fulls within 10 seconds. This is the decision input for the next step.

**Decision gate:** Do not alter a full path until the baseline identifies it as an actual visible
source. If every reported flash maps to one required full at provider startup, pause here and
discuss the acceptable recovery tradeoff rather than broadening partial delivery blindly.

### 3. Remove redundant full-paint sequences while retaining one recovery full

Implement only the reductions supported by the baseline. The likely first target is the known
provider `onUpdate` plus queued `ACTION_REFRESH` pair, but it must be validated against the new
origin trace first.

1. Give an `onUpdate`-initiated startup paint and its follow-up refresh a shared per-widget
   sequence/token in `WidgetUpdateTracker` (or the existing startup-token mechanism).
2. If a successful complete-body full has already bound that widget in the same sequence, make
   the redundant follow-up cache repaint partial or suppress it when its rendered input is
   unchanged. Keep exactly one full for the sequence.
3. If the first paint fails, is cancelled, is loading/error-only, has different layout/options, or
   has not completed, retain the follow-up full. This preserves the self-heal role documented in
   the prior flash plan.
4. Apply the same rule to any other measured clustered full pair. Do not merge unrelated user
   interactions, resize events, or meaningful data/error transitions simply because they occur
   close together.
5. Keep normal `WORKER_FETCH`, `WORKER_CACHE`, and `UI_ONLY` complete-body repaints partial once
   the process has established a backing full, as they are today.

**Likely files:**

- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetUpdateTracker.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`

### 4. Lock the delivery contract with tests

Add tests at the decision seams rather than trying to simulate GPS, Doze, or a real Samsung
launcher.

1. Extend the existing partial/full capture tests in `DailyViewHandlerTest` (and equivalent
   coverage where needed) to assert that routine worker and UI-only data paints use
   `partiallyUpdateAppWidget()` after a backing full.
2. Add a sequence test for `onUpdate` followed by `ACTION_REFRESH`:
   - healthy startup delivers at most one full for a widget;
   - the follow-up is partial or skipped only after that full succeeds;
   - a failed/cancelled/unbound first paint still permits a recovery full.
3. Retain the first-process complete-body promotion test: an unbacked partial must still become
   one full, because this is a hierarchy-establishment case rather than periodic redraw policy.
4. Keep `LauncherCacheDropRecoveryTest` as a characterization test. Do not invert its known-defect
   assertion in this change; this plan is about eliminating unnecessary flashes, not choosing a
   cache-drop heuristic.
5. Add a test that each direct loading/error/warning delivery records its origin and effective
   full mode, so future fallback paths cannot become invisible sources of redraws.

### 5. Verify both behavior and the visual result

1. Run the relevant targeted unit/Robolectric tests while iterating, then run
   `./gradlew testDebugUnitTest` before release.
2. Build and install the compatible debug update through the normal device-preserving path.
3. On the Samsung, observe a DAILY widget through: ordinary background fetch/cache refreshes,
   unlock/hourly `onUpdate`, a manual refresh, resize, and a new app process. The expected result
   is seamless worker refreshes and no double flash; one intentional full is acceptable only at
   the classified hierarchy-establishment event.
4. Pull the post-release logs and compare them with the baseline table. Confirm that every
   observed flash has a traceable allowed origin and that redundant full clusters are gone.

## Acceptance criteria

- No normal `WORKER_FETCH`, `WORKER_CACHE`, or `UI_ONLY` repaint performs a full replacement when
  the widget is already backed in the process.
- A healthy `onUpdate`/refresh sequence causes no more than one full replacement per widget.
- The Samsung no longer shows the prior periodic or double redraw during ordinary use.
- All full deliveries are attributable in `app_logs` by widget, origin, caller, requested mode,
  effective mode, and promotion reason when applicable.
- Initial-process, resize, and error-recovery behavior remain safe: no blank/unbound widget is
  introduced to suppress a flash.
- Cache-drop options A, B, and C remain unshipped. Reconsider them only after an on-device trace
  captures an actual unbound occurrence and after this plan has established the visual
  baseline.

## References

- `plans/260711-daily-widget-periodic-redraw-flash.md` — prior diagnosis and the partial worker
  repaint implementation.
- `plans/260722-widget-unbound-body-launcher-cache-drop.md` — deterministic cache-drop defect,
  current breadcrumbs, and the evidence limitations around the basketball incident.
- `app/src/test/java/com/weatherwidget/widget/LauncherCacheDropRecoveryTest.kt` — delivery-model
  characterization, not an on-device launcher simulation.

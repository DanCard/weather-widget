# Multi-Widget Render Prioritization and Interaction Cancellation

**Date:** 2026-10-28  
**Goal:** Prevent secondary and off-screen widgets from consuming CPU/disk resources during user interaction, and ensure the actively viewed widget renders first during batch updates.

---

## 1. Problem & Architecture Context

On multi-widget devices (such as the Samsung Galaxy Z Fold 4 with 4 widgets across cover and main screens, or devices with multiple home-screen pages), all placed widgets are repainted during:
1. Periodic worker syncs (`WidgetPaintCoordinator.updateAllWidgets`)
2. Screen-on / unlock events when a paint is owed (`ScreenOnReceiver` -> `ACTION_REFRESH`)
3. Cache refreshes (`WidgetIntentActionHandler.renderAllWidgetsFromCache`)

### Bottlenecks Observed:
1. **Arbitrary Order:** `AppWidgetManager.getAppWidgetIds()` returns widget IDs in arbitrary (creation) order. If the user is looking at Widget 345, but Widget 392 is first in the array, the system renders Widget 392 first while the user-facing widget waits in queue.
2. **Resource Competition During Interaction:** When a user taps a widget (e.g. toggling API sources, navigating days, cycling views), ongoing background render jobs for *other* widgets continue to run concurrently, competing for CPU time, memory allocations, and bitmap generation bandwidth.
3. **Android AppWidget Constraints:**
   - The Android Launcher / `AppWidgetHost` does not provide visibility or scroll-into-view callbacks to widget providers.
   - We cannot completely stop updating secondary widgets while on battery (e.g., "only update when idle and charging"), because swiping to another home-screen page or unfolding a foldable would display frozen, hours-stale weather until the user manually taps.
   - Therefore, the correct approach is **aggressive prioritization and preemption**: prioritize the active widget, cancel background work for secondary widgets when interaction occurs, and settle secondary widgets only after interaction quiesces.

---

## 2. Proposed Solution Architecture

### Phase 1: Track Active / Most Recently Interacted Widget (`WidgetStateManager`)
1. Add persistence/memory tracking in `WidgetStateManager`:
   - `fun setLastInteractedWidgetId(appWidgetId: Int)`
   - `fun getLastInteractedWidgetId(): Int?`
2. Update `BaseWidgetActionReceiver` to record `appWidgetId` on any valid user touch event (`launchForValidWidget`).

### Phase 2: Priority-Ordered Batch Rendering (`WidgetPaintPrioritizer`)
1. Create a dedicated prioritization helper:
   ```kotlin
   object WidgetPaintPrioritizer {
       fun prioritize(
           context: Context,
           appWidgetIds: IntArray,
           lastInteractedId: Int?,
       ): IntArray
   }
   ```
2. **Prioritization Rules:**
   - **Primary Slot (Index 0):** The `lastInteractedId`, if present in `appWidgetIds`.
   - **Display Awareness (Foldables):** On foldables (`smallestScreenWidthDp >= 600`), prioritize widgets whose minimum width options (`AppWidgetManager.getAppWidgetOptions(id)`) match the current display mode (inner vs. cover).
   - **Remaining Widgets:** Ordered stably afterwards.
3. Integrate into:
   - `WidgetPaintCoordinator.updateAllWidgets`
   - `WidgetIntentActionHandler.renderAllWidgetsFromCache`

### Phase 3: Preemptive Cancellation of Other Widgets on Interaction (`WidgetUpdateTracker`)
1. Extend `WidgetUpdateTracker`:
   - Add `cancelOtherJobs(activeAppWidgetId: Int, typesToCancel: Set<JobType> = setOf(JobType.UI_PAINT, JobType.BACKGROUND_SYNC)): List<Int>`
   - Tracks which widget IDs had background paints cancelled.
2. Hook into `BaseWidgetActionReceiver.launchForValidWidget`:
   - When an interactive action begins on `appWidgetId`:
     - Call `WidgetUpdateTracker.cancelOtherJobs(appWidgetId)`.
     - Log cancellation event: `WIDGET_INTERACTION_PREEMPTION active=$appWidgetId cancelled=[...]`.
3. Safe Rescheduling / Debounce:
   - Cancelled widgets must not be left stranded in an inconsistent state.
   - Mark `paintOwed = true` or schedule a low-priority debounce job (3 seconds after last interaction) to complete their repaint from cache once the user is done tapping.

---

## 3. Implementation Steps

1. **`WidgetStateManager.kt`**:
   - Add `LAST_INTERACTED_WIDGET_ID` preference key and accessor methods.
2. **`WidgetUpdateTracker.kt`**:
   - Add `cancelOtherJobs(activeAppWidgetId: Int)`.
   - Add tracking for cancelled widgets to enable clean settlement.
3. **`WidgetPaintPrioritizer.kt`**:
   - Create class with unit tests validating ordering under various widget sets and foldable configurations.
4. **`WidgetPaintCoordinator.kt` & `WidgetIntentActionHandler.kt`**:
   - Apply `WidgetPaintPrioritizer.prioritize` before iterating over `appWidgetIds`.
5. **`BaseWidgetActionReceiver.kt`**:
   - Invoke `WidgetStateManager.setLastInteractedWidgetId(appWidgetId)` and `WidgetUpdateTracker.cancelOtherJobs(appWidgetId)` on interaction launch.
   - Enqueue a deferred repaint for cancelled widgets once interaction concludes.

---

## 4. Verification Plan

1. **Unit Tests:**
   - `WidgetUpdateTrackerTest`: Test cancellation of other widgets' `UI_PAINT` and `BACKGROUND_SYNC` jobs while preserving the active widget's job.
   - `WidgetPaintPrioritizerTest`: Verify that the active widget is placed first, foldable geometry sorting behaves as expected, and unknown/invalid IDs are handled safely.
   - `BaseWidgetActionReceiverTest`: Verify that user interaction triggers preemption and records the last interacted ID.

2. **Robolectric Integration Test (`WidgetInteractionPrioritizationIntegrationTest`):**
   - Place multiple widgets (e.g., Widget A and Widget B) in the test environment.
   - **Scenario 1 (Prioritization):** Set Widget B as `lastInteractedWidgetId`. Trigger a batch cache refresh (`renderAllWidgetsFromCache`) and assert that Widget B is rendered before Widget A.
   - **Scenario 2 (Preemption & Cancellation):** Start an asynchronous, long-running background paint on Widget B and Widget C. Fire an `ACTION_TOGGLE_API` broadcast for Widget A to `WidgetInteractionReceiver`. Assert that:
     1. Widget B and C background jobs are cancelled via `WidgetUpdateTracker`.
     2. Widget A's interaction renders successfully without interruption.
     3. Cancelled widgets are scheduled/tracked for clean settlement after interaction ceases.

3. **Full Suite Gate:**
   - `./scripts/unit-tests.sh`: Ensure all JVM tests across `:app`, `:shared`, and `:desktop` pass.

4. **Live Device Verification (Samsung Galaxy Z Fold 4 `SM-F936U1`):**
   - Install debug build.
   - Verify logcat for `WIDGET_INTERACTION_PREEMPTION` when tapping while a screen-on refresh is running.
   - Confirm interaction latency remains < 900 ms with zero CPU competition from other widgets.
   - Confirm secondary widgets settle cleanly within 3 seconds after interaction ends.

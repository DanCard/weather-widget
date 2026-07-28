# WidgetIntentRouter Code Review Fixes

## Goal

Address the three confirmed review findings in `WidgetIntentRouter` without changing widget behavior
outside interaction ordering, API-toggle fetch cooldowns, and batch repaint failure handling.

## Findings and Changes

1. Serialize state-changing widget interactions per widget ID.
   - Route navigation, view/source/precipitation toggles, zoom, explicit view changes, resize, and
     cache-first repaints through one per-widget coroutine `Mutex`.
   - Keep different widget IDs independent.
   - Re-throw coroutine cancellation instead of converting it into a normal handler return.
   - Drop a widget's process-local mutex when the launcher deletes that widget ID.

2. Track successful forecast checks independently from row-content timestamps.
   - Add a per-source forecast-success timestamp to `FetchMetadata`.
   - Record it only after a forecast provider returns a non-empty successful result.
   - Use the newer of that timestamp and the newest cached row timestamp for API-toggle cooldowns,
     preserving compatibility with existing installations whose metadata predates this change.
   - Continue forcing a refresh when daily/hourly rows or required future coverage are missing.

3. Isolate cache-first repaints per widget.
   - Catch and persist non-cancellation failures for one widget, then continue repainting later IDs.
   - Keep cancellation terminal for the whole operation.

## Verification

1. Pure coroutine tests prove same-widget serialization and cross-widget independence.
2. Pure batch-loop tests prove one failed widget does not suppress later widgets and cancellation
   still propagates.
3. Source refresh-policy tests cover an unchanged successful fetch advancing the cooldown.
4. Fetch metadata tests cover source isolation and persistence.
5. Run focused router/metadata tests, Kotlin compilation, and the relevant duration lane.

## Results

1. `:app:compileDebugKotlin` and `:app:compileDebugUnitTestKotlin` passed.
2. Focused Short tests passed (15 tests across source-refresh and router-execution coverage).
3. Focused Long Robolectric tests passed for metadata, API toggling, and router behavior.
4. The repository-level Medium test passed, proving a successful provider fetch writes the
   source/site cooldown timestamp.
5. `:app:assembleDebug` passed; the APK installed in place on `Medium_Phone_API_36`.
6. Launcher evidence for widget 2 showed ordered NWS then Open-Meteo handler/render completion, with
   the final widget surface displaying Open-Meteo and no `AndroidRuntime` crash.

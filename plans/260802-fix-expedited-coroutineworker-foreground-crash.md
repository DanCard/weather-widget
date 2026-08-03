# Fix Android 11 Expedited CoroutineWorker Crash

Status: implemented and locally verified; release monitoring pending

Date: 2026-08-02

Crashlytics issue: `40eee8972883fa5f74e5f4c0a49dcf43`

## Goal

Stop the Android 11 `java.lang.IllegalStateException: Not implemented` crash without weakening the
existing unique-work collision guarantees or adding a recurring foreground-service notification for
normal widget refreshes.

## Implementation Result

Implemented on 2026-08-02:

1. Removed expedited status from both zero-delay `WeatherWidgetWorker` request builders in
   `WidgetWorkScheduler`.
2. Preserved every existing unique-work name, input, delay, network constraint, and collision
   policy.
3. Extended `WeatherWidgetProviderEnqueuePolicyTest` to assert that immediate full-sync,
   required-follow-up, and UI-repaint requests are ordinary work with zero delay.
4. Added `WidgetWorkSchedulerApi30IntegrationTest`, which executes the production UI-repaint
   request through WorkManager on Android 11.
5. Installed the API 30 Google APIs x86_64 system image and created
   `Medium_Phone_API_30`. The verified emulator remains running as `emulator-5558`.

The release-only Play crawler and Crashlytics monitoring gates remain pending because they require
a new uploaded version; no upload, issue closure, commit, or push was requested or performed.

## Observed Incident

The selected Crashlytics event is direct runtime evidence, not a source-only inference:

1. Fatal exception: `java.lang.IllegalStateException: Not implemented`.
2. Blamed method: `androidx.work.CoroutineWorker.getForegroundInfo$suspendImpl`
   (`CoroutineWorker.java:100`).
3. Event time: 2026-08-02 04:32:36 PDT (`2026-08-02T11:32:36Z`).
4. App version: `26073001`.
5. Device: x86_64 `OnePlus8Pro`, Android 11.
6. Session age at failure: 103 seconds.
7. Crashed thread: `DefaultDispatcher-worker-3`.
8. The session was launched by `androidx.test.tools.crawler.stubapp`, so the selected event came
   from the Google Play automated crawler rather than a physical OnePlus device.
9. The seven-day issue view reports 45 events affecting 18 Crashlytics installation IDs, all on
   Android 11 and all reported as OnePlus devices. Versions `26073001` and `26080101` account for
   19 and 17 events respectively.

The last relevant breadcrumbs immediately before the crash were:

1. `CURR_FETCH_WORK_ENQUEUED reason=screen_unlock_charging` at 11:32:35.863Z.
2. `REFRESH_DECISION uiOnlyRequested=false ... isDataStale=true` at 11:32:36.002Z.
3. No `SYNC_START` for the crashing request, which is consistent with WorkManager failing while
   preparing foreground execution before `WeatherWidgetWorker.doWork()` begins.

## Root Cause

Before this fix, `WeatherWidgetWorker` extended `CoroutineWorker` without overriding
`getForegroundInfo()`, while its zero-delay scheduler paths were expedited:

1. `WeatherWidgetWorker.kt:33-44` declares the worker and enters directly into `doWork()`.
2. The pre-fix `WidgetWorkScheduler.enqueueFullSync()` marked every zero-delay full-sync request
   expedited at `WidgetWorkScheduler.kt:270-280`.
3. The pre-fix `WidgetWorkScheduler.buildUiRequest()` also marked every zero-delay UI repaint
   expedited at `WidgetWorkScheduler.kt:293-308`.
4. On Android versions before Android 12, WorkManager implements expedited work with a foreground
   service and calls `CoroutineWorker.getForegroundInfo()` to obtain its notification.
5. The base implementation throws `IllegalStateException("Not implemented")` when it has not been
   overridden. `RUN_AS_NON_EXPEDITED_WORK_REQUEST` only describes quota fallback; it does not make
   an in-quota expedited request safe on Android 11.

The observed trigger is consistent with this path:

```text
ScreenOnReceiver ACTION_USER_PRESENT while charging
  -> WidgetActionReceiver ACTION_REFRESH
  -> WidgetRefreshCoordinator.refresh(uiOnly = false)
  -> stale-data decision
  -> enqueueRedundantImmediateSync(reason = "refresh_action_stale")
  -> enqueueFullSync(initialDelayMs = 0)
  -> setExpedited(...)
  -> Android 11 WorkManager requests foreground information
  -> CoroutineWorker default throws before doWork()
```

The defect predates the provider extraction: the expedited calls existed in the former
`WeatherWidgetProvider` and were moved into `WidgetWorkScheduler` by commit `6788d009`. The
extraction made scheduling ownership clearer but did not introduce the underlying API mismatch.

## Selected Repair

Make all `WeatherWidgetWorker` requests ordinary WorkManager requests. In this codebase,
"immediate" will continue to mean zero initial delay, not WorkManager's expedited execution class.

This is preferred over implementing `getForegroundInfo()` for the following reasons:

1. The same worker handles frequent alarm-driven UI repaints, opportunistic repaints, automatic
   missing-data repairs, startup fetches, and user-triggered full refreshes. An implementation of
   `getForegroundInfo()` would require a visible foreground-service notification whenever these
   expedited requests run on Android 8-11.
2. Interactive widget refreshes already render cached data directly in
   `WidgetRefreshCoordinator.refresh()` before scheduling any network follow-up.
3. Unit changes already use the direct `ACTION_REFRESH` cache-render path because expedited work
   can be delayed by quota or Doze. Expedited WorkManager is therefore not the user-visible repaint
   guarantee.
4. Current-temperature-only work created by `CurrentTempUpdateScheduler` is already ordinary
   constrained WorkManager work, demonstrating that the scheduling architecture does not require
   expedited status for every zero-delay request.
5. Removing expedited status fixes the pre-Android-12 crash without adding notification channels,
   foreground-service types, notification copy, or notification-permission UX.

Do not add a dummy `getForegroundInfo()` implementation or suppress the exception. WorkManager
requires a real notification for the foreground-service path, so a placeholder would replace this
crash with another platform-contract violation.

## Behavior Invariants

The implementation must preserve these existing contracts:

1. Immediate work retains zero initial delay.
2. Network constraints and all request input fields remain unchanged.
3. Redundant full syncs retain `ExistingWorkPolicy.KEEP`.
4. Required full-sync follow-ups retain `ExistingWorkPolicy.APPEND_OR_REPLACE`.
5. Immediate UI repaint retains `APPEND_OR_REPLACE`.
6. Delayed startup and delayed per-widget repaint work retain their current distinct unique-work
   lanes and delays.
7. Periodic work retains `ExistingPeriodicWorkPolicy.UPDATE`.
8. No running-capable work changes to `REPLACE`; the documented ART cancellation/native-crash
   invariant remains intact.
9. `WeatherWidgetWorker.doWork()` behavior, fetch freshness, source selection, callbacks, and
   rendering behavior remain unchanged.

## Implementation Steps

### 1. Add regression assertions before changing request construction

Extend `WeatherWidgetProviderEnqueuePolicyTest` to capture the enqueued request, not only verify its
unique-work name and collision policy.

Add assertions covering both request builders:

1. `enqueueUiRepaint()` produces `workSpec.expedited == false` and an initial delay of zero.
2. `enqueueRedundantImmediateSync()` produces `workSpec.expedited == false` and an initial delay of
   zero.
3. `enqueueRequiredImmediateSync()` and `enqueueRequiredNoHourlyFollowUp()` also remain
   non-expedited, proving that required `APPEND_OR_REPLACE` work does not bypass the contract.
4. The existing delayed-request tests continue to assert their nonzero delay and collision lane.

Keep these assertions in the scheduler-policy test because `WidgetWorkScheduler` owns execution
class, input construction, unique names, and collision policies. Do not put the contract in an
unrelated worker test.

### 2. Remove expedited execution from the scheduler

In `WidgetWorkScheduler.kt`:

1. Remove `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` from
   `enqueueFullSync()` when `initialDelayMs == 0`.
2. Remove the corresponding call from `buildUiRequest()`.
3. Remove the now-unused `OutOfQuotaPolicy` import.
4. Retain `setInitialDelay()` only when the requested delay is positive.
5. Add a short KDoc/comment at the common request-construction boundary explaining that zero-delay
   work is intentionally ordinary because `WeatherWidgetWorker` has no foreground-notification
   contract and the interactive cache path renders directly.

Do not alter callers or rename the public scheduler methods in this focused fix. Their "Immediate"
names describe caller intent and zero delay; a broad rename would add churn without changing the
runtime contract.

### 3. Audit every `WeatherWidgetWorker` construction site

Search the complete Android production source for:

```text
setExpedited
OneTimeWorkRequestBuilder<WeatherWidgetWorker>
```

Verify that:

1. No `WeatherWidgetWorker` request remains expedited anywhere, including code outside
   `WidgetWorkScheduler`.
2. Independent requests in `SettingsActivity`, `CurrentTempUpdateScheduler`,
   `NonPrimaryObservationScheduler`, `LocationUpdater`, and the worker's debug-refresh path remain
   ordinary.
3. There is no other `CoroutineWorker` subclass paired with an expedited request and no
   `getForegroundInfo()` implementation elsewhere that would make the audit ambiguous.

This source audit is required because fixing only the Crashlytics trigger would leave another
pre-Android-12 path capable of producing the same fatal exception.

### 4. Add an Android 11 runtime regression

Add one focused instrumented test under `app/src/androidTest` that executes a zero-delay
`WeatherWidgetWorker` UI-repaint request on API 30 and waits for terminal `WorkInfo` state.

The test should:

1. Use `WidgetWorkScheduler.enqueueUiRepaint()` so it exercises the production builder.
2. Record the returned work ID.
3. Wait with a bounded timeout for `SUCCEEDED`.
4. Fail if the work enters `FAILED`/`CANCELLED`, never reaches a terminal state, or the process
   dies.
5. Avoid network dependence by using the UI-only request path.
6. Remain an instrumented `androidTest`; the unit-test duration-category validator does not apply
   to this source set.

At implementation start, the local SDK had only the API 36 system image. The implementation
installed an API 30 Google APIs x86_64 image and created a dedicated `Medium_Phone_API_30` AVD for
this test. Keep the emulator running afterward, consistent with project test policy.

Run the test through the emulator-only harness:

```bash
./scripts/emulator-tests.sh \
  -e Medium_Phone_API_30 \
  -c com.weatherwidget.widget.WidgetWorkSchedulerApi30IntegrationTest
```

Before relying on that command, ensure no different emulator is already being selected by the
harness's existing-emulator shortcut. If necessary, target the API 30 emulator explicitly with
`adb -s` and the instrumentation runner rather than shutting down or repurposing the user's API 36
emulator.

### 5. Reproduce the Crashlytics trigger on API 30

After the focused instrumented test passes, exercise the real trigger:

1. Install the debug APK on `Medium_Phone_API_30`.
2. Verify the emulator reports SDK 30 and record manufacturer/model properties rather than
   inferring identity from its serial.
3. Put the emulator into a charging state with the screen interactive.
4. Ensure the app has stale or missing forecast data.
5. trigger the explicit app-UID `ACTION_REFRESH` path used by the project's emulator recovery, or
   reproduce unlock via `ScreenOnReceiver`.
6. Confirm the breadcrumb chain reaches `REFRESH_DECISION ... isDataStale=true` and schedules the
   `refresh_action_stale` full sync.
7. Confirm the process remains alive, `WeatherWidgetWorker.doWork()` reaches `SYNC_START`, and
   WorkManager records a normal terminal result.
8. Confirm logcat contains no `CoroutineWorker.getForegroundInfo`,
   `IllegalStateException: Not implemented`, or fatal `AndroidRuntime` entry for the package.
9. Capture a widget screenshot after the refresh and verify it still renders from cache while the
   network follow-up proceeds.

This is the acceptance gate for the fix. API 36 alone cannot prove it because Android 12 and newer
use the platform expedited-job path and do not exercise WorkManager's pre-S foreground-service
compatibility behavior.

## Verification Matrix

### Focused JVM/Robolectric checks

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.weatherwidget.widget.WeatherWidgetProviderEnqueuePolicyTest \
  --tests com.weatherwidget.widget.WidgetWorkSchedulerCollisionTest
```

Acceptance criteria:

1. Immediate full-sync and UI-only requests are non-expedited with zero delay.
2. Existing `KEEP`, `APPEND_OR_REPLACE`, distinct-lane, and periodic `UPDATE` assertions pass.
3. Required no-hourly callback input remains present after collision-policy coverage.

### Broader Android checks

```bash
./gradlew :app:testByDurationDebugUnitTest
./gradlew :app:assembleDebug
```

If release delivery is requested, also run the minified release smoke lane because WorkManager
constructs workers reflectively:

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

Install and exercise the minified APK before calling it release-ready; compilation and R8 success
alone do not prove worker creation or execution.

### Static audit

```bash
rg -n "setExpedited|getForegroundInfo|OneTimeWorkRequestBuilder<WeatherWidgetWorker>" app/src/main
git diff --check
```

Acceptance criteria:

1. No production request for `WeatherWidgetWorker` is expedited.
2. No notification/foreground-service workaround was introduced.
3. Only the scheduler, focused tests, API-30 regression, and this plan are changed.

### Post-release evidence

1. Upload a build with a new version code to the same Play testing lane that produced the crawler
   event.
2. Require the Play pre-launch report to complete on its Android 11/OnePlus profile without this
   exception.
3. In Crashlytics, filter issue `40eee8972883fa5f74e5f4c0a49dcf43` by the fixed version.
4. Close the issue only after the fixed version has no new matching events; do not close it merely
   because local tests pass.

### Completed local evidence

1. Before the scheduler edit, the new contract assertions failed in the four expedited cases and
   passed in the delayed/collision cases, proving that they detect the defect.
2. After the edit, `WeatherWidgetProviderEnqueuePolicyTest` and
   `WidgetWorkSchedulerCollisionTest` passed together (11 tests).
3. `:app:testByDurationDebugUnitTest`, `:app:assembleDebug`, and
   `:app:assembleDebugAndroidTest` completed successfully.
4. `WidgetWorkSchedulerApi30IntegrationTest` passed on SDK 30 in 0.756 seconds. Logcat recorded the
   production request, `WeatherWidgetWorker` execution, and
   `WM-WorkerWrapper: Worker result SUCCESS` without a foreground-info exception.
5. A fresh, non-test app process received the explicit `ACTION_REFRESH` while charging with empty
   data. It logged `REFRESH_DECISION ... isDataStale=true`, enqueued request
   `9c931a7d-a8c4-4ad9-92be-980e6b871ad8` with reason `refresh_action_stale`, reached `SYNC_START`,
   completed the network/data/render pipeline, logged `SYNC_SUCCESS`, and finished with WorkManager
   `SUCCESS`. The process remained alive at PID 6574 until the subsequent instrumentation run.
6. The existing `AddWidgetIntegrationTest` passed on API 30 in 5.938 seconds, proving bind, full
   paint, and resize delivery still work on the affected platform generation.
7. Logcat contained no `CoroutineWorker.getForegroundInfo`, `IllegalStateException: Not
   implemented`, or fatal `AndroidRuntime` entry for the app during either regression path.

## Out of Scope

1. Changing `KEEP`/`APPEND_OR_REPLACE`/`UPDATE` collision policy.
2. Cancelling or clearing existing WorkManager databases.
3. Refactoring `WeatherWidgetWorker` fetch/render responsibilities.
4. Adding foreground-service notifications or notification permission UX.
5. Changing fetch cadence, battery gates, freshness policy, or source selection.
6. Treating all 18 Crashlytics installation IDs as confirmed human users; the selected report is
   explicitly crawler-generated.

## Completion Criteria

The fix is complete only when all of the following are true:

1. Every `WeatherWidgetWorker` request is non-expedited.
2. Focused scheduler-policy and collision tests pass.
3. The full Android unit-test buckets and debug assembly pass.
4. The API 30 instrumented worker reaches `SUCCEEDED` without process death.
5. The charging/unlock stale-refresh path reaches `SYNC_START` and preserves widget rendering on
   API 30.
6. No `CoroutineWorker.getForegroundInfo` / `Not implemented` fatal entry appears in API 30
   logcat.
7. The fixed release passes the corresponding Google Play pre-launch crawler check.
8. Crashlytics records no new event for this issue on the fixed version before the issue is closed.

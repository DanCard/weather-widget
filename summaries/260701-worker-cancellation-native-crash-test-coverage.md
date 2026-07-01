# Test coverage for the "never cancel a running WeatherWidgetWorker" fix

## Context

The Samsung "dead widget" was a native `SIGSEGV`: WorkManager cancelling an in-flight
`WeatherWidgetWorker` (via `ExistingWorkPolicy.REPLACE`) resumes the cancelled coroutine
continuation and segfaults the ART interpreter (worst on debuggable/no-AOT builds). The fix converts
every *immediate/running-capable* enqueue from `REPLACE` to `KEEP`/`APPEND_OR_REPLACE`; only
*delayed/not-yet-running* work keeps `REPLACE`. See AGENTS.md → "NEVER cancel a running
WeatherWidgetWorker".

The crash itself is an on-device runtime fault — **not reproducible in JVM/Robolectric**. So the
testable target is not the bug but its **preventable precondition**: *no immediate enqueue uses
`REPLACE`.* Testing the policy constant is cheap, deterministic, and catches the exact regression
that would bring the crash back.

## New test added

**`app/src/test/java/com/weatherwidget/widget/WeatherWidgetProviderEnqueuePolicyTest.kt`** — guards
the highest-churn, previously-unguarded provider enqueue paths (Robolectric + mockk static
`WorkManager`):

1. `triggerUiOnlyUpdate` immediate `_ui` → `APPEND_OR_REPLACE` (the #1 cancellation source).
2. `triggerUiOnlyUpdate` delayed `_ui_delayed` → `REPLACE` allowed (no live coroutine to cancel).
3. `triggerImmediateUpdate` → `KEEP`.

## Already covered (assertions updated in the same change)

- `UIUpdateReceiverTest` → `_ui` `APPEND_OR_REPLACE`.
- `CurrentTempUpdateSchedulerTest` → `enqueueImmediateUpdate` `APPEND_OR_REPLACE` + the charging-loop
  `REPLACE_IMMEDIATE → APPEND_OR_REPLACE` mapping (`scheduleNextChargingUpdate ... successor from
  running worker`).
- `WidgetIntentRouterRobolectricTest` → `buildRefreshScheduleDecision` `manual_refresh` → `KEEP`.

## Deliberately NOT written (with rationale)

1. **NonPrimaryObservationScheduler policy-mapping test** — its `when(action)` mapping is line-for-line
   identical to `CurrentTempUpdateScheduler`'s (already tested); the existing NonPrimary tests only
   exercise the pure `decideLoopWork` decision, and reaching the enqueue would need charging-battery
   setup the test file intentionally avoids. Covered by symmetry; low ROI, real flake cost.
2. **The native crash itself** — a runtime ART-interpreter segfault; unit-untestable by nature. The
   on-device fast-refresh repro loop (`DEBUG_FAST_FULL_REFRESH_SECONDS`) already served that role.
3. **`ProcessExitLogger` de-dup cursor** — would require a mocked `ApplicationExitInfo`, which has no
   public constructor. Its pure formatting/classification is already covered by
   `ProcessExitLoggerTest`.
4. **SettingsActivity / OpportunisticUpdateJobService `_ui` sites** — lower-frequency copies of paths
   now guarded by the provider/receiver tests; not worth a test-per-call-site sprawl.

## Design note

The mapping logic is centralized enough (`decideChargingLoopWork` + the shared `_ui` unique name)
that guarding the two churn hubs — the provider's UI/immediate enqueues and the charging-loop
successor mapping — covers the realistic regression surface without a test per call site.

## Verification

`WeatherWidgetProviderEnqueuePolicyTest` passes; full `./gradlew testDebugUnitTest` green. Flake
note: running many scheduler test classes together throws `FileSystemAlreadyExistsException` (shared
jimfs) — run classes in isolation.

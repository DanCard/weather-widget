# Battery-first screen-unlock refresh policy

**Date:** 2026-07-24
**Status:** Implemented and verified; unrelated Long-suite liveness hang noted
**Scope:** Android widget behavior when the phone is unlocked while not charging.

## Decision

An unplugged screen unlock should always repaint from cached data and should never initiate a
forecast network fetch, regardless of battery percentage.

Unlocking the phone is not an explicit request for fresh weather. Although the device is already
awake, a network request still consumes radio, CPU, and battery. The existing 30% threshold also
undermines the more conservative scheduled-fetch tiers:

1. At 30–50% battery, periodic forecast fetches are disabled, but unlock can currently trigger one.
2. At 51–70% battery, the scheduled primary-source interval is eight hours, but an unlock can
   currently reduce the effective interval to approximately one hour.
3. At 71–79% battery, the scheduled interval is four hours, but unlock can again reduce it to the
   primary-source stale threshold.

## Implementation result

1. `WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock` now depends only on charging state.
2. Every unplugged `ACTION_USER_PRESENT` broadcast carries `EXTRA_UI_ONLY=true`.
3. The obsolete 30% unlock threshold and its dedicated helper were removed.
4. Battery percentage remains in the sparse unlock-policy log for diagnostics only.
5. Charging unlock, manual refresh, periodic tiers, power-connected behavior, and passive location
   handoff are unchanged.

The revised rule is simpler:

| Trigger | Charging | Forecast network allowed? |
|---|---:|---:|
| Screen unlock | No | No; cache-only repaint |
| Screen unlock | Yes | Yes, subject to existing stale-data decision |
| Manual refresh | Either | Yes |
| Periodic worker | No | Existing battery-tier policy |
| Power connected | Yes | Preserve existing behavior |

## Prior behavior and cause

Before implementation, `ScreenOnReceiver.handleUserPresent` asked
`WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(isCharging, batteryLevel)`.

The prior policy returned network-capable mode whenever:

1. the phone is charging; or
2. the phone is unplugged and battery is at least 30%.

The receiver sends `ACTION_REFRESH` without `EXTRA_UI_ONLY` in those cases. The provider then:

1. checks whether any visible source is stale;
2. considers the first visible/primary source stale after 60 minutes; and
3. calls `triggerImmediateUpdate(forceRefresh = true)` when stale.

Therefore, the network call is not unconditional on every unlock, but once the stale threshold is
crossed it bypasses the longer off-charger cadence.

This behavior was introduced as an “opportunistic” fetch: use a moment when the phone is already
awake rather than causing a separate wakeup. It was a product-policy choice, not an Android
requirement.

## Goals

1. Make every unplugged unlock cache-only.
2. Retain the immediate cached widget repaint on unlock.
3. Retain cached current-temperature interpolation and UI scheduling.
4. Preserve network-capable refresh when charging.
5. Preserve explicit/manual refresh behavior on battery.
6. Preserve the existing scheduled primary-source tiers:
   1. battery above 70%: four hours;
   2. battery above 50%: eight hours; and
   3. battery at or below 50%: no routine scheduled forecast fetch.
7. Avoid adding any worker cancellation, new work chain, alarm, wakeup, or location request.

## Non-goals

1. Do not change the periodic forecast intervals.
2. Do not change current-temperature interpolation frequency.
3. Do not disable the cache-only repaint on unlock; it helps repair stale launcher presentation
   without using the network.
4. Do not change manual refresh behavior.
5. Do not change the passive GPS/location-candidate handoff.
6. Do not make power connection force a full forecast fetch as part of this change.
7. Do not alter charging current-temperature or non-primary-observation loops.

## Power-connected behavior

The user considers a charging-triggered fetch reasonable, but that is not the current full-forecast
behavior:

1. `ACTION_POWER_CONNECTED` reschedules the periodic forecast worker at charging cadence.
2. It enqueues a debounced lightweight current-temperature refresh.
3. It does not immediately force a full forecast fetch.

This plan preserves that behavior. Adding a full forecast fetch on power connection would be a
separate product change with its own stale-data and debounce decision. It is unnecessary to solve
the unlock battery issue.

## Implementation plan

### Phase 1: make the policy battery-first

1. Change `WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock` so its result depends only on charging:
   1. charging → network-capable;
   2. not charging → UI-only.
2. Remove the `batteryLevel` parameter from this policy function because battery percentage no
   longer changes unlock behavior.
3. Update `ScreenOnReceiver.handleUserPresent` to call the simplified policy.
4. Continue reading battery level for the sparse `UNLOCK_REFRESH_POLICY` diagnostic if it remains
   useful; do not use it to authorize network work.
5. Keep the existing `ACTION_REFRESH` broadcast:
   1. add `EXTRA_UI_ONLY=true` whenever unplugged;
   2. omit it when charging.

### Phase 2: remove the obsolete 30% unlock exception

1. Search for all uses of:
   1. `MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH`; and
   2. `shouldAllowOpportunisticFetchOnBattery`.
2. If they remain exclusive to screen-unlock policy, remove them from `BatteryFetchStrategy`.
3. Remove their isolated unit tests.
4. Do not apply the removed threshold to unrelated opportunistic current-temperature jobs without
   separate evidence; those paths have their own policies.

### Phase 3: keep the cache-only recovery path intact

Verify that unplugged unlock still reaches `WeatherWidgetProvider.handleRefreshAction` and:

1. calls `WidgetIntentRouter.renderAllWidgetsFromCache`;
2. schedules the next cache/UI update;
3. does not call `triggerImmediateUpdate`;
4. does not enqueue a full `WeatherWidgetWorker`;
5. does not enqueue a current-temperature network worker; and
6. leaves the last useful location body visible.

This is important because “no network on unlock” must not become “do nothing on unlock.”

### Phase 4: update documentation

Update:

1. `notes/260321-battery-optimization-overview.md`; and
2. `notes/260525-forecast-fetch-frequency.md`.

The documentation should state:

1. unplugged unlocks always use cached data;
2. the 30% unlock exception no longer exists;
3. manual refresh remains network-capable;
4. charging unlock remains network-capable; and
5. the periodic 4-hour/8-hour/no-fetch tiers are unchanged.

Avoid implying that `OpportunisticUpdateJobService` performs full forecast fetches. Its normal
widget work is cache/UI and lightweight current-temperature handling, not the screen-unlock
forecast path being removed here.

## Test plan

### Pure policy tests

Update `WidgetRefreshPolicyTest`:

1. charging unlock is network-capable;
2. unplugged unlock is always UI-only; and
3. stale data cannot turn an unplugged unlock into a network fetch.

Battery level is deliberately absent from the policy API. Reintroducing a “healthy battery”
exception would therefore require an explicit production and test API change rather than silently
changing a threshold.

### Receiver tests

Strengthen `ScreenOnReceiverTest`:

1. unplugged `ACTION_USER_PRESENT` sends `ACTION_REFRESH` with `EXTRA_UI_ONLY=true`;
2. the result is the same at high and low battery levels;
3. charging `ACTION_USER_PRESENT` sends a network-capable refresh;
4. unplugged unlock continues cancelling charging-only current-temperature and non-primary loops;
5. charging unlock continues scheduling those loops; and
6. `UNLOCK_REFRESH_POLICY` records `uiOnly=true` while unplugged.

### Provider/WorkManager regression

Add or extend a Robolectric test covering the receiver-to-provider decision:

1. seed stale primary forecast data;
2. deliver an unplugged unlock refresh with `EXTRA_UI_ONLY=true`;
3. verify a cache render occurs;
4. verify no full forecast `OneTimeWorkRequest` is enqueued; and
5. verify no worker cancellation occurs.

Use mocked WorkManager only where necessary. Prefer a pure scheduling decision seam if the existing
provider test infrastructure cannot reliably observe the queue.

### Existing behavior regression

Retain tests proving:

1. a manual stale refresh can enqueue a full forecast worker;
2. a charging stale refresh can enqueue a full forecast worker;
3. periodic fetch intervals are unchanged; and
4. power-connected refresh debounce remains unchanged.

Every JVM test class must retain exactly one duration category.

## Verification

1. Run focused policy and receiver tests:

   ```bash
   ./gradlew :app:testDebugUnitTest \
     --tests com.weatherwidget.widget.WidgetRefreshPolicyTest \
     --tests com.weatherwidget.widget.ScreenOnReceiverTest \
     --tests com.weatherwidget.widget.BatteryFetchStrategyTest
   ```

2. Run provider scheduling and non-cancellation regression tests:

   ```bash
   ./gradlew :app:testDebugUnitTest \
     --tests com.weatherwidget.widget.WeatherWidgetProviderEnqueuePolicyTest
   ```

3. Run the affected duration buckets:

   ```bash
   ./gradlew :app:testShortDebugUnitTest :app:testLongDebugUnitTest
   ```

4. Build the debug APK:

   ```bash
   ./gradlew :app:assembleDebug
   ```

5. On an emulator, verify two controlled cases without inspecting either physical phone:
   1. unplugged unlock repaints from cache and creates no full forecast work; and
   2. charging unlock preserves the existing network-capable stale-refresh behavior.

6. Leave the emulator running after verification.

### Verification results

1. The focused policy, receiver, battery-tier, and provider enqueue-policy tests passed.
2. `:app:testShortDebugUnitTest` passed, including duration-category validation.
3. `:app:assembleDebug` passed.
4. `ScreenUnlockBatteryPolicyInstrumentedTest` passed 2/2 on both connected emulators. Neither
   physical phone was targeted, and both emulators were left running.
5. The complete `:app:testLongDebugUnitTest` bucket was attempted. Its test worker became blocked in
   an SSL handshake inside the pre-existing network-dependent
   `ApiKeySignupUrlLivenessTest.signupUrlsAreLive`; the run was stopped after the exact stack was
   captured. The affected Long-duration `ScreenOnReceiverTest` passed in the focused suite.

## Acceptance criteria

1. No unplugged screen unlock can initiate a full forecast network fetch at any battery percentage.
2. Unplugged unlock still repaints the complete widget from cached data.
3. Charging unlock remains network-capable.
4. Manual refresh remains network-capable.
5. Periodic forecast intervals and battery thresholds remain unchanged.
6. No new worker cancellation, active GPS request, wakeup, or recurring job is introduced.
7. Documentation no longer describes a 30% screen-unlock network exception.
8. Focused tests, the affected duration-category coverage, debug build, and controlled emulator
   verification pass.

## Expected trade-off

While unplugged, weather may remain older until the next periodic fetch or an explicit refresh.
That is intentional. In particular, a location candidate discovered during travel may take longer
to promote if the app is not opened and the user does not manually refresh. The benefit is that
routine phone unlocks no longer override the battery-first forecast cadence.

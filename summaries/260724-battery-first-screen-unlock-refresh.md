# Battery-first screen-unlock refresh policy

**Date:** 2026-07-24
**Plan:** `plans/260724-battery-first-screen-unlock-refresh.md`

## Outcome

Unplugged screen unlocks now always repaint the widget from cached data and cannot initiate a full
forecast network fetch at any battery percentage. Charging unlocks remain network-capable when
forecast data is stale.

Manual refresh, periodic battery tiers, power-connected behavior, passive location handoff, and
worker cancellation policies are unchanged.

## What changed

1. `WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock` now depends only on charging state:
   1. unplugged means cache-only; and
   2. charging means network-capable, subject to the existing stale-data check.
2. `ScreenOnReceiver` sends `EXTRA_UI_ONLY=true` for every unplugged `ACTION_USER_PRESENT`.
3. The obsolete 30% opportunistic forecast-fetch threshold and helper were removed from
   `BatteryFetchStrategy`.
4. Battery percentage remains in the sparse `UNLOCK_REFRESH_POLICY` diagnostic log, but it no
   longer authorizes network work.
5. Battery and forecast-frequency documentation now describes the battery-first unlock behavior.

No new work chain, cancellation path, alarm, wakeup, active location request, or GPS polling was
introduced.

## Regression coverage

1. Policy tests prove:
   1. unplugged unlock is UI-only;
   2. stale data cannot turn it into a network fetch; and
   3. charging unlock remains network-capable.
2. `ScreenOnReceiverTest` verifies the actual refresh broadcast carries `EXTRA_UI_ONLY=true` while
   unplugged, omits it while charging, and records `uiOnly=true` in the diagnostic log.
3. Provider enqueue-policy tests retain the `KEEP`/non-cancellation guarantees for immediate widget
   work.
4. `ScreenUnlockBatteryPolicyInstrumentedTest` verifies both policy branches on a real Android
   runtime.

## Verification

1. Focused policy, receiver, battery-tier, and provider enqueue-policy tests passed.
2. `:app:testShortDebugUnitTest` and duration-category validation passed.
3. `:app:assembleDebug` passed.
4. The instrumented test passed 2/2 on each connected emulator. Neither physical phone was
   targeted, and both emulators were left running.

The complete Long-duration bucket was also attempted. Its worker became blocked in an SSL
handshake inside the pre-existing network-dependent
`ApiKeySignupUrlLivenessTest.signupUrlsAreLive`, so that run was stopped after capturing the stack.
The affected Long-duration `ScreenOnReceiverTest` passed in the focused suite.

# Code Review follow-up — L1 (centralize the sticky battery read)

**Source:** `plans/260812-code-review-refresh-coordination.md` (L1 — duplicated sticky-battery-read idiom).

The `context.registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` + level/charging read
is repeated in ~10 places, with two divergent `batteryLevel` fallbacks (`100` in
`ScreenOnReceiver`, `-1` elsewhere) and two divergent level normalizations (some scale-normalize,
most don't).

## Change

1. **`BatterySnapshotProvider`** (new): single owner of the sticky read, returning a
   `BatterySnapshot(isCharging, batteryLevel)`. Level is scale-normalized (0..100), unknown → `-1`.
2. **`BatteryStatePolicy`**: add a pure `batteryLevelPercent(rawLevel, scale)` and have
   `isEffectivelyCharging(Intent)` use it (fixes the latent `EXTRA_LEVEL`-without-`EXTRA_SCALE` bug
   behind the `>= 100` "full" check).
3. Route all call sites through `BatterySnapshotProvider`:
   `WidgetWorkScheduler`, `WidgetRefreshCoordinator` (x2), `WeatherWidgetWorker.measureDeviceContext`,
   `UIUpdateScheduler`, `CurrentTempUpdateScheduler`, `NonPrimaryObservationScheduler`,
   `ScreenOnReceiver`, `OpportunisticUpdateJobService`.
4. **`UIUpdateReceiver`**: the battery read there is dead (the `isCharging` result is never used) —
   delete it.
5. Collapse the two now-redundant private types (`ScreenOnReceiver.BatteryState`,
   `OpportunisticPowerState`) into `BatterySnapshot`.

Behaviour change is limited to: the unknown-battery fallback in `ScreenOnReceiver` becomes `-1`
(was `100`, log-only), and level is now consistently scale-normalized.

## Tests

- Add pure `batteryLevelPercent` cases to `BatteryStatePolicyTest`.
- Existing `OpportunisticUpdateJobServiceTest` / `ScreenOnReceiverTest` continue to exercise the
  provider (registerReceiver / `BatteryStatePolicy` mocks are respected).

## Verify

`./gradlew :app:assembleDebug :app:testShortDebugUnitTest` plus the affected Long tests.

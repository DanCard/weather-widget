# Code review follow-up — L1 (centralize the sticky battery read)

**Date:** 2026-08-12 · **Plan:** [plans/260812-code-review-followup-l1.md](../plans/260812-code-review-followup-l1.md)
**Target:** L1 from `plans/260812-code-review-refresh-coordination.md`.

The last remaining low-priority finding worth acting on. The `registerReceiver(null,
ACTION_BATTERY_CHANGED)` + level/charging read was copy-pasted across ~10 sites with two divergent
level fallbacks (`100` vs `-1`) and two divergent normalizations (some scale-normalized, most not).

---

## What shipped

| # | Change |
|---|---|
| 1 | `BatterySnapshotProvider` (new) — single owner of the sticky read, returns `BatterySnapshot(isCharging, batteryLevel)` with scale-normalized level (unknown → `-1`) |
| 2 | `BatteryStatePolicy.batteryLevelPercent(rawLevel, scale)` (new, pure) + `isEffectivelyCharging(Intent)` now normalizes against `EXTRA_SCALE` |
| 3 | Routed 9 call sites through the provider (`WidgetWorkScheduler`, `WidgetRefreshCoordinator` ×2, `WeatherWidgetWorker`, `UIUpdateScheduler`, `CurrentTempUpdateScheduler`, `NonPrimaryObservationScheduler`, `ScreenOnReceiver`, `OpportunisticUpdateJobService`) |
| 4 | Deleted dead code — `UIUpdateReceiver`'s unused battery read |
| 5 | Collapsed two redundant private types (`ScreenOnReceiver.BatteryState`, `OpportunisticPowerState`) into `BatterySnapshot` |

**Verified:** `:app:assembleDebug`, `:app:testShortDebugUnitTest`, `:app:testLongDebugUnitTest`,
and the full `:app:testByDurationDebugUnitTestFresh` (all buckets) green.

---

## Notes

- **The latent scale bug was real, not cosmetic.** `BatteryStatePolicy.isEffectivelyCharging`
  previously compared raw `EXTRA_LEVEL` against `100` without dividing by `EXTRA_SCALE`; on a device
  with a non-100 scale the "full battery counts as charging" heuristic would misread. Consolidation
  let the fix land once instead of once per call site.
- **The fallback change is deliberately narrow.** `ScreenOnReceiver`'s unknown-battery fallback
  moved `100 → -1`, but that value is log-only there (its branches key off `isCharging`), so no
  behaviour changed; the unified `-1` = "unknown" is the conservative choice the other sites already
  used.
- **Test seams respected.** `ScreenOnReceiverTest` mocks `BatteryStatePolicy.isEffectivelyCharging`
  (the single-arg overload) and `OpportunisticUpdateJobServiceTest` mocks `Context.registerReceiver`;
  both still intercept correctly because the provider delegates to those exact seams.

## Still open (deferred)

L2 (DI inconsistency), L3 (residual magic numbers), L4 (in-memory `lastRenderMs`) were assessed as
not worth the churn — see the earlier assessment.

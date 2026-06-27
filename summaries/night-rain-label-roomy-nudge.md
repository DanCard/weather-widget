# Night rain-chance label: roomy right+down nudge

**Date:** 2026-06-26

## Summary

The night rain-chance label now reads its own room: **tight columns keep the snug tuck
under the low temp exactly as before, and roomy columns shift the label ~2.5dp right and
down** so it sits as its own label rather than hugging the 56.8°. The offset scales smoothly
with `roomFraction = 1 - tightFraction`, so there's no abrupt threshold.

## Changes

- **Shared constants** (`shared/util/DailyRainLabels.kt`): `NIGHT_TUCK_ROOMY_RIGHT_DP` /
  `NIGHT_TUCK_ROOMY_DOWN_DP` = 2.5dp — single source of truth for both platforms.
- **Desktop renderer** (`desktop/DailyForecastGraph.kt`): `roomyRightPx` folded into
  `shiftedCenterX` (flows through edge-fit + collision checks); `roomyDownPx` added to the
  final top-Y, including the bottom-clear guard.
- **Android renderer** (`app/widget/DailyForecastRainLabelRenderer.kt`): right nudge
  *subtracted* from `hNudgePx` (the fit subtracts that value, so subtracting again moves
  rightward); down nudge added to `resolvedBaseline` *after* the collision snap so it layers
  on top.

## Design notes

- Folding the right-nudge into the *input* of each platform's horizontal-fit step (not the
  output) means the existing edge-margin clamping and reduced-scale fallback still protect the
  rightmost interstitial label from running off-screen.
- Applying the down-nudge *after* the collision snap is deliberate: the snap is collision
  avoidance that should always win; the roomy offset is a cosmetic layer on top of whatever
  final position the snap chose.

## Verification

- Desktop compiles; all 13 night-label unit tests pass
  (`./gradlew :app:testDebugUnitTest --tests "*DailyForecastGraphRendererTest*"`).
- Distributable rebuilt and desktop app restarted on the new build (`scripts/buildStart.sh`).

## Follow-ups (optional)

- **Tune the magnitude**: 2.5dp was the read of "a couple of pixels" — adjust
  `NIGHT_TUCK_ROOMY_RIGHT_DP`/`DOWN_DP` to taste.
- **Test seam**: the roomy-scaling math is inline in the draw methods. Could be extracted into
  a small pure shared function and unit-tested like `resolveNightCollision`.

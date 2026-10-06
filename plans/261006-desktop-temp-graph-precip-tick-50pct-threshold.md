# Desktop temperature graph: rain ticks only at >= 50%

## Problem
The desktop hourly temperature graph (2-day view) showed a row of faint blue ovals along the
bottom. `drawCloudAndPrecipOverlays` (`desktop/.../TemperatureGraph.kt`) drew a tick for any
`precipSignal > 0`, floored to 2 dp tall with round caps. NWS reports dry hours at 1-2%
(DB: 2026-10-04 17:00 → 2026-10-05, `precipProbability=1`, `precipAmountMm=0.0`, "Clear"), so
every hour got a floor-height oval.

Android's hourly temperature renderer has no such overlay (desktop-only, commit 9e5e66be).

## Change (user's call: minimum of 50%)
Draw the tick only when `precipSignal >= MIN_PRECIP_TICK_SIGNAL` (0.5). `precipSignal` is
`max(probability/100, mm/6)`, so the gate is >= 50% chance or >= 3 mm. Cloud tint unchanged.

## Verification
Rebuild + restart desktop (`scripts/buildStart-desktop.sh`), screenshot the 2-day view: the dots
under the 63.2° label are gone.

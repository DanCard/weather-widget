# Daily view: rain % above the highest temperature label (emulator + desktop)

**Date:** 2026-07-06

## The bug
On a past day the daily view can print two high-temp labels — the observed actual
(thermostat pink) and the forecast overlay (yellow). Since warmer temps draw higher on the
graph, when the forecast ran warmer than reality its label sits *above* the actual. Both
renderers anchored the rain % to the actual high only, so "15%" wedged between the two numbers
instead of clearing the top one (seen on the "Sun" / yesterday column).

## Fix

**Android** (`DailyForecastGraphRenderer.kt`): extracted the dual-high decision into a shared
`resolveHighLabelPlan()` that both the draw loop and the rain-label resolvers now use. It exposes
an `anchorHigh`/`anchorBaseline` that is the *topmost* (warmer) of the two labels when both are
shown, and the headline `effectiveHigh` otherwise. `resolveHighLabelBaseline` and
`resolveHighLabelDrawScale` now return/measure against that anchor.

**Desktop** (`DailyForecastGraph.kt`): the dual-high branch now sets
`highLabelTopAtCenter = minOf(aY, fY)` so the rain % clears whichever label is higher.

The single-label case (most days) is unchanged — it still anchors to the headline high, so only
the dual-label past days move.

## Verification
- Both modules compile (`:app:compileDebugKotlin`, `:desktop:compileKotlin`).
- Daily / rain-label / dual-high unit tests pass.
- Installed to emulator; fix verified visually by the user.

## Notes
- The bug was an anchor mismatch, not a drawing bug: `effectiveHighForLabel` returns the actual
  for past days by design (headline shouldn't keep showing an over-prediction), which is right for
  the *single* label but wrong for *clearing both* labels.
- The dual-high decision was previously duplicated between the draw loop and the rain resolver's
  inputs; consolidating into `resolveHighLabelPlan` means "which labels exist / where are they" is
  answered once, so the anchor can't drift from what's drawn.
- Pre-existing unrelated working-tree changes (`desktop/.../LogList.kt`,
  `ObservationsWindow.kt`) were left untouched.

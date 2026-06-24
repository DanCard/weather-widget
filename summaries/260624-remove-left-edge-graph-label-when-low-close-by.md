# Fix: two stacked actual labels at left edge of hourly graph

## Issue

Two pink "actual" labels stacked at the left edge of the hourly graph (`60.8°` over `58.9°`).
Only one should appear.

## Root cause

The leftmost visible day was a thin ~2-hour partial sliver descending into the overnight low.
After the existing shoulder-drop, that day retained only a per-day *high* at the boundary start
index (idx 0) — which qualified as a "local max" purely because the left-edge point has no left
neighbor (`isActualLocalMax`'s `leftOk` auto-trues). It wasn't a real peak, just the warm point
where observation begins, falling straight into the genuine nearby low. The existing
`degenerateLowDrops` (only fires on identical rounded values) and `shoulderDrops` (only collapses
same-type extrema) both missed it.

## Fix

Added `boundaryHighDrops` in `shared/.../graph/TemperatureExtrema.kt`, alongside the existing
shoulder/degenerate drops. It removes a per-day high at `actualStartIndex` when the curve descends
weakly-monotonically into the nearest cooler retained low with no real peak between. It is
**asymmetric by design** — a boundary *low* (coldest-at-edge) is never dropped, preserving the
deliberate `actual_low_left_edge_label` behavior. Because it lives in `:shared`, both Android and
the desktop port inherit it.

## Verification

- 3 new tests (1 drop + 2 guards) pass; full `:shared` suite green.
- Runtime logs: `BOUNDARY_HIGH_DROPPED idxs=[0]`, only one `ACTUAL_LOW` label accepted.
- Screenshot confirms a single pink label at the left edge.

## Note

This is the third sibling in a family of "this isn't a real per-day extreme" filters, all
co-located in `TemperatureExtrema.kt`: `shoulderDrops` (cross-day same-type valley/peak straddling
midnight), `degenerateLowDrops` (high and low round to the same displayed value), and now
`boundaryHighDrops` (a descending-sliver boundary high). Keeping them together — rather than
scattering suppression across the resolver and renderers — is what made this fix a ~12-line
addition with full Android/desktop parity for free.

## Files changed

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt` — the fix.
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureExtremaIncompleteDayTest.kt` — 3 new tests.

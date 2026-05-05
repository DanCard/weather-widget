# Preserve Rain End Labels And Backfill Midpoint Labels

## Summary
Keep the precipitation graph's right-edge probability label from being dropped during dense-candidate filtering, and add a center probability label when only the two edge anchors would otherwise remain.

## Key Changes
- Treat precipitation graph edge anchors (`0` and `lastIndex`) as mandatory during dense-label filtering so narrow or low-variance graphs still retain a visible end label.
- Keep the existing midpoint backfill behavior, but apply it after filtering based on the final retained edge-only state instead of only the exact pre-placement `[0, lastIndex]` candidate list.
- Only add the midpoint label when the center value is positive and meaningfully different from both retained edge labels after smoothing.
- Preserve current placement direction rules for the end label: rising right edge prefers above, falling right edge prefers below.

## Public/API/Type Changes
- No public API changes.
- Reuse the existing `immovableIndices` hook in `GraphLabelPlacementUtils` from `PrecipitationGraphRenderer`.

## Test Plan
- Add precipitation renderer coverage for end-label retention in a low-variance edge-heavy signal.
- Add precipitation renderer coverage for rising and falling end-label placement direction.
- Add precipitation renderer coverage for center-label backfill when only edge anchors survive.
- Add precipitation renderer coverage that skips midpoint backfill when the center value is zero or duplicates an edge value.
- Run the focused precipitation renderer test suite.

## Assumptions
- Readability still matters, but edge labels are more important than fully aggressive decluttering on the precipitation graph.
- The extra midpoint label is only wanted when the final retained probability labels would otherwise be exactly the two edge anchors.

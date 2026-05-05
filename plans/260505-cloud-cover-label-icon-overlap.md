# Prevent cloud-cover label/icon overlap

## Summary
- Tighten cloud-cover percent label placement so labels never remain below the curve when that placement overlaps an hourly weather icon.
- Preserve the existing candidate selection and above/below fallback order; only the icon-collision acceptance rule changes.

## Implementation
- Update `CloudCoverGraphRenderer` to reject below-label placements that intersect `drawnIconBounds`, including low-value edge labels such as `3%`.
- Keep the alternate above-placement retry so the label still renders when the below slot is blocked by an icon.
- Leave label-to-label collision handling and bottom-overflow behavior unchanged.

## Tests
- Update the pure JVM helper test to reflect that cloud-cover labels no longer allow icon overlap.
- Add a Robolectric renderer regression test that verifies a right-edge low cloud label is placed above when an hourly icon occupies the bottom row below it.

## Assumptions
- The fix applies to any cloud-cover percent label, not just the final visible label.

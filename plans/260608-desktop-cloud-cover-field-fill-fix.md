# Desktop Hourly Cloud Cover Field Fill

## Summary
Fix the desktop hourly stitching path so it preserves the canonical NWS hourly row but fills missing nullable fields from hourly history, especially `cloudCover`. This restores the desktop cloud-cover graph when the live row exists but arrived without that field.

## Key Changes
- Change the shared hourly merge helper to merge by hour and coalesce missing nullable fields from history instead of replacing whole rows.
- Keep current hourly values authoritative for temperature and condition.
- Use the same stitched hourly result in the desktop cache/read path that feeds the cloud-cover graph and other hourly-based desktop summaries.
- Add a lightweight diagnostic log or assertion-friendly counter so the repair path is visible when history is needed.

## Test Plan
- Unit test the merge helper with:
  - current row present and history row present,
  - current `cloudCover == null` and history `cloudCover != null`,
  - current values still win for non-null fields,
  - rows remain deduplicated and time-sorted.
- Desktop repository test:
  - seed the DB with a current NWS hourly row missing `cloudCover`,
  - seed matching hourly history with `cloudCover`,
  - verify `loadCached()` returns the filled value.
- Regression check for the all-null case:
  - when neither row has `cloudCover`, the result stays null.

## Assumptions
- Historical rows are a repair source for missing nullable hourly fields, not a replacement for the canonical live row.
- The first fix target is desktop visibility; Android only changes if it already depends on the same shared helper.

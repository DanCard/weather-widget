# Fix Android Hourly Cloud Cover Stitching When Graph Window Overlaps Now

## Summary
Remove the Android graph-loader early return that skips history stitching whenever the graph window overlaps the current hour. The loader should always try to repair missing hourly `cloudCover` from `hourly_forecast_history` while keeping current rows authoritative for all other fields.

## Key Changes
- Keep the current hourly forecast rows as the primary source for graph rendering.
- Always apply the history merge when `hourly_forecast_history` is available and a source is selected, even if the graph window overlaps `now`.
- Preserve the existing behavior for callers without history access by returning the current rows unchanged.
- Leave Room schema, entity definitions, and write paths unchanged.

## Test Plan
- Extend the existing regression test to cover the overlap-with-now case and prove missing `cloudCover` is repaired from history.
- Add a non-overlap regression check so the history stitch still works when the graph window is entirely in the past or future.
- Keep the DAO round-trip assertion so the test still proves `hourly_forecast_history.cloudCover` is persisted and read back correctly.

## Assumptions
- The persistence layer is healthy; the bug is in the Android read path, not in Room serialization.
- Desktop behavior remains unchanged.
- The fix should be read-only and should not affect canonical hourly storage.

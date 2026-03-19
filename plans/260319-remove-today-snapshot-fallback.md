# Remove Today Snapshot Fallback And Backfill Missing History

## Summary
- Change the `Today` triple-bar behavior so the yellow bar is shown only when there is a real historical forecast for today from the active source.
- Do not fall back to `Generic` or climate data for the yellow today bar.
- If the prior forecast is missing, omit the yellow bar for the current render and schedule a background backfill attempt.

## Key Changes
- Keep intended semantics:
  - Yellow = prior real forecast for today
  - Orange = observed/actual-so-far for today
  - Blue = current/full-day forecast for today
- Use historical forecast rows for the daily snapshot path instead of the latest-only collapsed query.
- In `DailyViewLogic.prepareGraphDays()`:
  - search today snapshot rows only from the active source
  - require both `highTemp` and `lowTemp`
  - select the most recent row older than `now - 24h`
  - return `null` snapshot values if none exists
- Remove `Generic` fallback from today snapshot selection.
- When `snapshotHigh` and `snapshotLow` are missing, draw only orange and blue for today.
- Log snapshot hit/miss details and schedule a non-forced backfill when the yellow snapshot is missing.

## Data Flow
- Keep latest-only forecast rows for the main widget weather display.
- Feed historical forecast rows into the daily graph snapshot path.
- Reuse the existing one-time worker enqueue path for backfill, with a distinct reason and debounce key.

## Test Plan
- Verify today snapshot selection uses the most recent complete prior forecast from the active source older than `now - 24h`.
- Verify incomplete latest rows do not replace an older valid snapshot.
- Verify `Generic` rows are never used for the today snapshot.
- Verify missing today snapshot leaves `snapshotHigh` and `snapshotLow` null.
- Verify repeated renders do not enqueue duplicate snapshot backfill work inside the cooldown window.
- Regression case:
  - latest NWS row = `89 / null`
  - older NWS row = `91 / 59`
  - `Generic` row = `62 / 48`
  - expected yellow bar = `91 / 59`

## Assumptions
- Scope is limited to the yellow today snapshot bar.
- Past-day comparison fallback behavior remains unchanged.
- Omitting yellow is preferable to showing approximate climate data.

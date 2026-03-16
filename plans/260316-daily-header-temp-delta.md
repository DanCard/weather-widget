# Add Daily Header Temperature Delta

## Summary
- Surface the existing `current_temp_delta` badge in daily mode using `CurrentTemperatureResolver.appliedDelta`.
- Keep the meaning identical to hourly mode: observed current temperature minus interpolated hourly estimate.
- Preserve current header priority by hiding the delta whenever the daily precip badge is visible.

## Implementation Changes
- Update `DailyViewHandler` to format and color the delta badge the same way hourly mode does.
- Show the badge only when `abs(delta) >= 0.1` and header precip is not visible.
- Continue hiding the badge when current temperature is unavailable.

## Test Plan
- Add a Robolectric test that verifies daily mode shows a positive delta badge when precip is absent.
- Add a Robolectric test that verifies daily mode hides the delta badge when precip is visible.

## Assumptions
- Daily mode should expose the same header-level delta semantics as hourly mode.
- This change does not add a daily graph overlay or reinterpret the delta against daily highs or lows.

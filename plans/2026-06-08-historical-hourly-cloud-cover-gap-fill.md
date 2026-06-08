# Historical Hourly Cloud Cover Gap Fill

## Summary
Add a provenance-safe hourly gap fill path for the past 72 hours on both Android and desktop. The UI should prefer canonical NWS hourly rows, then fall back to stored hourly-history snapshots for missing hours so charts stay complete without masking source regressions.

## Key Changes
- Add a shared “stitched hourly range” read path that merges `hourly_forecasts` with `hourly_forecast_history` for the requested window.
- Use the stitched read path anywhere the app/desktop renders the past-3-day hourly cloud-cover view.
- Keep canonical live fetch/storage unchanged: the current NWS hourly table remains the primary source of truth.
- Log when the stitched path had to fill gaps so missing NWS data stays visible during debugging.
- Preserve provenance by keeping backfilled data separate from canonical rows rather than overwriting them.

## Test Plan
- Verify a gap in the current hourly table is filled from hourly history for the past 72 hours.
- Verify canonical hourly rows still win when both current and history rows exist for the same hour.
- Verify desktop and Android both render the same stitched 72-hour window shape.
- Verify missing-hour logging fires when history is required to complete the view.

## Assumptions
- “Backfill” here means filling the user-facing hourly history view from stored snapshots first, not silently rewriting live NWS data.
- If a future source-level repair job is added, it should remain separate from this read-time stitching path.

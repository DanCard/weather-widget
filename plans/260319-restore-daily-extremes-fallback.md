# Restore Daily Extremes Fallback For Stations Without Official Max/Min

## Summary
- Restore hybrid `daily_extremes` generation: prefer official provider-supplied max/min values when present, otherwise compute daily highs/lows from stored observations.
- Repair already-missing recent days by recomputing `daily_extremes` from existing observation rows before widget/history reads.

## Implementation Changes
- Update `ObservationResolver` so the primary `daily_extremes` builder uses official values with observed-temperature fallback.
- Switch `ObservationRepository` write paths back to full-day recomputation after live observation inserts and historical backfill inserts.
- Add a stored-observation recompute path exposed through `WeatherRepository`, and call it before widget/history reads so gaps like two days ago recover without waiting for a fresh station payload.
- Update `DailyExtremeEntity` documentation to match the restored hybrid behavior.

## Test Plan
- Verify `ObservationResolver.computeDailyExtremes` prefers official max/min when available.
- Verify `ObservationResolver.computeDailyExtremes` falls back to spot readings when official extremes are missing.
- Verify per-source grouping still produces separate rows for NWS and non-NWS observation sources.

## Assumptions
- Existing raw observations for missing recent dates are still present in the database and can be re-aggregated.
- It is acceptable for early-in-day rows to be overwritten as more observations arrive for the same date/source/location.

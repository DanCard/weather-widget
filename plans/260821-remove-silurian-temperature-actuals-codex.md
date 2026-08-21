# Remove Silurian Temperature Actuals

## Verified problem

- Silurian's `include_past=true` data comes from `/forecast/hourly`; the API does not expose a
  current observation or historical analysis product through that response.
- `HistoricalActualsBackfill` currently relabels elapsed Silurian forecast hours as
  `SILURIAN_MAIN` observations.
- Those synthetic rows reach every temperature-truth consumer: Android and desktop actual curves,
  current-observation/delta resolution, daily extrema, accuracy baselines, and widget repaint
  watermarks. Existing cached rows would continue to leak into those paths if only the writer were
  disabled.

## Implementation

1. Declare Silurian forecast-only for temperature actuals and historical provenance.
2. Stop creating new `SILURIAN_MAIN` observation rows.
3. Reject cached legacy Silurian rows at the shared temperature-series boundary.
4. Apply the same capability gate to Android's direct current-temperature and watermark paths and
   desktop's direct current-condition/timestamp selection.
5. Keep Silurian hourly/daily forecasts and forecast interpolation unchanged.

## Database impact

No schema or migration change is required. Existing `SILURIAN_MAIN` rows remain harmless because
all temperature-actual readers ignore them; normal observation retention can remove them later.

## Validation

- Shared regression tests: no new Silurian backfill rows; cached Silurian rows produce no actual
  series/current observation/daily extrema; Open-Meteo behavior remains intact.
- Android regression tests: cached Silurian rows produce neither a current observed temperature nor
  a graph repaint watermark.
- Desktop repository regression test: cached Silurian rows do not supply observed condition/time or
  an observation correction to the interpolated forecast.
- Run focused tests, all module test suites, build/install, and verify on an emulator when available.

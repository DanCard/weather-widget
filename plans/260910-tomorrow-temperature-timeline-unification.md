# Fix the Tomorrow.io temperature jump with one actuals pipeline

**Date:** 2026-09-10
**Status:** proposed; implementation requires approval
**Scope:** the confirmed Tomorrow.io timeline defect only, on Android and desktop

## Goal

Make Tomorrow.io follow the same temperature-actuals scheme as the other providers:

```text
provider response
  -> provider-specific mapping to ObservationReading
  -> shared timeline normalization
  -> ActualTemperatureSeriesBuilder
  -> HourDataAssembler
  -> Android or desktop renderer
```

Network endpoints cannot be identical because station providers and gridded providers expose
different products. The common contract begins after each response is mapped to
`ObservationReading`. From that point onward, provider name must not change how observation times
are selected or how the temperature graph is assembled.

## Confirmed defect

On the Pixel 7 Pro, Tomorrow.io had these two stored samples:

| Product | Observed at | Temperature |
|---|---:|---:|
| Realtime | 09:34 | 72.70 F |
| Recent history | 10:00 | 75.60 F |

Both timestamps round into the 10:00 `CloudHourBucket`. The current
`TomorrowIoActuals.preferRealtimeWithinHour` keeps the older realtime sample solely because it is
labeled realtime, and discards the newer history sample. When the next realtime response arrived
at 10:30 with 77.07 F, the selected anchor jumped from 72.70 F to 77.07 F.

The shared graph path already preserves native sub-hourly observation points for other providers.
Tomorrow.io's hour-bucket override is therefore the inconsistency, not a requirement of the graph.

## Timeline rules for every provider

Add one shared temperature-observation normalizer and apply it before blending:

1. Use the provider's observation timestamp as the timeline position. Do not replace it with the
   fetch time or rounded hour.
2. Preserve native timestamps for physical station feeds and providers with one actuals product.
3. When one logical feed has overlapping products, resolve only exact-timestamp duplicates before
   graphing. Tomorrow.io realtime and recent history remain distinct at distinct native timestamps.
4. For an exact timestamp collision, keep the row fetched most recently. If `fetchedAt` also ties,
   prefer realtime as the final product tie-break.
5. Sort deterministically by observation time and logical station id so input/database order cannot
   affect the result.
6. Preserve physical station identities for NWS, METAR, and Synoptic. Treat Tomorrow.io realtime
   and recent history as two products from one logical `Tmrw` feed, not two stations to blend.
7. Send the normalized rows through the existing `ActualTemperatureSeriesBuilder` and
   `HourDataAssembler` with no provider-specific graph thinning.

Temperature observations do not use `CloudHourBucket`. The 09:34 realtime and 10:00 history rows
both remain on the graph. A later history revision can replace a realtime row only when both carry
the exact same observation timestamp.

## Implementation

1. Add a small shared `ObservationTimelineNormalizer` under `shared/.../observations/`.
   Its default logical key is the real station id; its Tomorrow.io mapping aliases the realtime and
   recent-history ids to the existing `Tmrw` logical id.
2. Move Tomorrow.io's in-memory merge rules into that normalizer. Remove
   `preferRealtimeWithinHour` and `forTemperatureSeries`; keep `TomorrowIoActuals` only for product
   ids and response mapping. Persisted rows retain their original product ids for diagnostics.
3. In `ActualTemperatureSeriesBuilder`, pass every provider's filtered rows through the normalizer
   once before blending. For providers other than Tomorrow.io this should be behavior-preserving:
   distinct station/timestamp rows remain unchanged.
4. Change Android `ObservationResolver` and `DesktopWeatherRepository` to use the same normalized
   timeline when selecting the latest condition/observation. Remove both direct Tomorrow.io
   hour-bucket branches.
5. Keep Android and desktop renderers unchanged. They already share
   `ActualTemperatureSeriesBuilder` and `HourDataAssembler`; this change fixes their common input.
6. Do not rewrite the API clients in this change. Each client may fetch differently, but it must
   preserve `timestamp`, `fetchedAt`, provider id, and product/station identity when producing
   `ObservationReading`.

## Tests

Add focused shared tests for:

1. Pixel regression: 09:34 realtime = 72.70 F and 10:00 history = 75.60 F both retain their native
   timestamps.
2. The latest selected point before the 10:30 fetch is 75.60 F, not 72.70 F.
3. After 10:30 realtime = 77.07 F arrives, all three distinct points remain in timestamp order.
4. Same logical feed plus same observation timestamp deduplicates; newer `fetchedAt` wins, with
   realtime as the final deterministic tie-break.
5. Reversing input order produces identical output.
6. NWS/METAR-style rows from different physical stations at the same timestamp all survive.
7. Equivalent normalized inputs from Tomorrow.io and another single-feed provider produce the same
   `ActualTemperatureSeriesBuilder` and `HourDataAssembler` point sequence.

Update or replace the existing `TomorrowIoActualsTest` expectation that realtime deletes a distinct
history timestamp merely because the two occupy one hour bucket. Add focused Android
`ObservationResolver` and desktop repository tests for latest-condition selection from the
normalized Tomorrow.io timeline.

## Verification

1. Run the focused normalizer, `TomorrowIoActuals`, actual-series, graph-assembler, and desktop
   repository tests.
2. Run `./scripts/staggered-tests.sh --install`.
3. On Pixel `2A191FDH300PPW`, reproduce the delayed-realtime case and capture the relevant database
   rows, `CURR_TEMP_RESULT` logs, and a screenshot.
4. Confirm the graph contains the 10:00 history point before the 10:30 realtime fetch and that the
   current value no longer catches up directly from the older 09:34 anchor.
5. Confirm Android and desktop produce the same ordered actual points from the same fixture.

## Acceptance criteria

1. The Pixel fixture retains 72.70 F at 09:34 and 75.60 F at 10:00 while waiting for the 10:30
   realtime response.
2. An older observation can never suppress a newer observation merely because it is labeled
   realtime.
3. All providers use the same normalization, blending, and graph-assembly stages after mapping to
   `ObservationReading`.
4. Provider-specific code is limited to fetching, mapping, logical-feed identity, and an exact-tie
   preference; it cannot apply a separate graph timeline.
5. Android and desktop focused tests, the full suite, and Pixel runtime verification pass.

## Explicitly out of scope

- Choosing one canonical current-temperature provider.
- Making different providers report the same temperature.
- Changing personal-station weighting.
- Changing `fetchedAt` freshness behavior.
- Changing forecast interpolation, graph styling, settings, or historical accuracy scoring.
- Committing or pushing without an explicit request.

# Populate observed vertical-cloud fields — v3

**Date:** 2026-08-27
**Status:** Proposed — awaiting implementation approval
**Scope:** Provider parsing and observation population on shared, Android, and desktop paths. New
graph series and rendering remain deferred.

## Goal

Populate the flat observed-cloud columns introduced by Room 69 / desktop schema 23 without adding
tables, indexes, or additional database columns. Preserve each provider's semantics so a later graph
can distinguish model bands, cumulative reported layers, and total-cover envelopes.

## Evidence collected

1. Open-Meteo's current endpoint returned `cloud_cover`, `cloud_cover_low`, `cloud_cover_mid`, and
   `cloud_cover_high`, all in percent at a 15-minute timestamp. The current app request asks for and
   parses only total and low.
2. Tomorrow.io realtime and hourly Timeline responses returned `cloudCover` and `cloudBase`; the
   configured sample returned `cloudBase=9.94` with `units=imperial`. Tomorrow.io documents
   `cloudBase` and `cloudCeiling` as distance fields, so imperial values must be converted from miles
   to whole metres. The app currently drops both fields.
3. A live Synoptic query returned `cloud_layer_2_set_1d` for KSFO and layer 2 plus layer 3 for KSJC.
   The current parser reads only `cloud_layer_1_set_1d` when raw METAR is absent. Synoptic reports
   `height_agl` in metres by default.
4. The live Synoptic sample contained reported cloud bases up to 6.1 km. Provider documentation also
   supports multiple aviation layers. The older blanket statement that METAR-derived high cloud is
   impossible is therefore too strong: high layers will be rare, but an explicitly reported layer
   must be retained rather than discarded.

## Mapping contract

### Provider bands: Open-Meteo

1. Request and parse current total, low, middle, and high cover.
2. Copy all four values through Android and desktop current-observation writers.
3. Extend `HistoricalActualsBackfill` to preserve middle/high cover from Open-Meteo sub-hourly
   analysis rows.
4. Set `cloudVerticalKind=PROVIDER_BANDS` whenever any low/middle/high band is present.
5. Leave every height/envelope field null; Open-Meteo supplies band percentages, not observed bases.

### Cumulative layers: NWS, Aviation Weather, and Synoptic

1. Round each finite, non-negative provider base to the nearest whole metre before classification.
2. Use graph-aligned bands: low `<3000 m`, middle `3000..<8000 m`, high `>=8000 m`.
3. Within each band, retain the maximum cumulative METAR amount. On equal amounts, retain the lowest
   reported base at which that amount occurs.
4. Store the selected amount and its paired base in the corresponding cover/base columns.
5. An explicit clear code produces low cover `0` with a null base; middle/high remain null.
6. Preserve the existing conservative handling of a cloudy layer with an unreadable base: it may
   contribute to low cover, but its representative base remains null. Do not infer middle/high.
7. Set `cloudVerticalKind=CUMULATIVE_LAYERS` when the report yields any interpreted band value.
8. Keep `cloudCover` null. METAR layers are cumulative local sky-condition reports, not an
   independent total-column percentage.
9. Parse Synoptic layer arrays 1, 2, and 3 when raw METAR is absent, including documented `thin
   scattered`, `thin broken`, `thin overcast`, and `thin obscured` descriptions.
10. Missing/unrecognised bands remain null, never zero.

This intentionally changes the existing METAR low cutoff from 2 km to Open-Meteo's documented 3 km
boundary. It makes the stored actual and forecast bands comparable; earlier repository measurements
found only a 0.2-point MAE change. Because the existing cloud graph already reads `cloudCoverLow`,
this small threshold alignment is the only visible behavior change in this phase and requires runtime
graph verification.

### Total envelope: Tomorrow.io

1. Request `cloudBase` and `cloudCeiling` alongside `cloudCover` on both realtime and hourly Timeline
   calls.
2. Convert finite, non-negative imperial miles to metres and round to the nearest integer.
3. Carry envelope values through `TomorrowIoRealtimeReading` and through transport-only optional
   fields on `HourlyForecast`; do not add envelope columns to the much larger hourly forecast tables.
4. Map the elapsed Timeline slice into persisted observation envelope fields through
   `HistoricalActualsBackfill`, so recent history and realtime use the same observation schema.
5. Set `cloudVerticalKind=TOTAL_ENVELOPE` when base or ceiling is present. If only total cover is
   present, keep kind `NONE`: that row has cloud cover but no vertical representation.
6. Leave low/middle/high band percentages null; Tomorrow.io does not supply independent band cover.

## Shared implementation

1. Replace the low-only `MetarSkyCover` result with a tested vertical profile while retaining
   `lowPercent` as a compatibility wrapper where useful.
2. Update `NwsObservationMapper` and `MetarObservationMapper` to populate the profile and kind.
3. Extend Synoptic's derived-layer fallback to all three arrays and thin-condition codes.
4. Extend Open-Meteo `CurrentReading` and request/parser fields.
5. Add transport-only envelope fields to `HourlyForecast` and `TomorrowIoRealtimeReading`.
6. Extend `HistoricalActualsBackfill` and `TomorrowIoActuals.toObservation` with the new fields/kinds.

## Platform wiring

1. Android `CurrentTempRepository` copies Open-Meteo bands/kind and Tomorrow.io envelope/kind.
2. Desktop `DesktopWeatherService` does the same for observation-only and full-refresh paths.
3. Existing reading/entity mappings persist the fields; no database migration is needed.

## Tests

1. Pin METAR band boundaries, rounding, cumulative maximum/tie selection, clear reports, unknown
   heights, unknown amount codes, and rare explicit high layers.
2. Pin NWS and Aviation Weather mapper output including representative bases and kind.
3. Pin Synoptic layer 1/2/3 fallback and thin-condition decoding.
4. Pin Open-Meteo current request/parser and historical band preservation.
5. Pin Tomorrow.io realtime and Timeline mile-to-metre conversion, null envelope handling, and kind.
6. Pin Android and desktop current-observation writers with mock provider responses.

## Verification

1. Run focused shared, Android, and desktop tests, then all three short buckets.
2. Run the affected fresh Android long bucket if current-observation integration coverage is Long.
3. Install on the emulator and query `observations` after provider-specific refreshes:
   - Open-Meteo rows contain low/middle/high with `kind=10` and null heights.
   - NWS/METAR/Synoptic rows contain only explicitly reported bands/bases with `kind=20`.
   - Tomorrow.io rows contain total plus available envelope values with `kind=30`.
4. Inspect the hourly cloud graph and renderer diagnostics on the emulator because the 2 km to 3 km
   METAR low-band alignment can alter the already-visible low actual curve.
5. Run `git diff --check` and leave changes uncommitted until explicitly requested.

## Deferred phase

1. Selecting and blending separate low/middle/high observed series.
2. Drawing representative bases or Tomorrow.io envelopes.
3. Visual styling that distinguishes provider bands from cumulative layers.
4. Any new graph controls or schema/index changes.

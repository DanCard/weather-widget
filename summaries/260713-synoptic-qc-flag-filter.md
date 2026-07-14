# Synoptic QC-flag filter for web-fallback observations

## Problem

Desktop showed 67.8° when the real temperature was ~71°. KPAO's NWS feed was stale, so the
shared Synoptic web fallback supplied its readings — and Synoptic served a bad observation:
10.0 °C (50.0 °F) at 2026-07-13 20:47 local, between sane readings of 22–23 °C. The IDW blend
(`SpatialInterpolator.interpolateIDW`) filters on staleness/time only, never on value, so the
50° pulled the NWS blend from ~73.8 to 70.7 and the resolver's observed anchor to 68.22,
producing `delta=-2.70` and a display ~3°F low. Android shares the identical path
(`ObservationRepository` → shared `SynopticApi`), so both platforms were exposed.

Re-querying Synoptic with QC enabled showed their own quality-control system already flags
this exact reading: `air_temp` failed check **105 — SynopticLabs Spatial Value Check**
(neighbor comparison), and the same record's humidity failed the range check. The app simply
never requested QC data.

## Change — mark, don't drop (user request: show QC failures in the stations list)

QC-flagged readings are kept end-to-end, marked `qcFailed`, excluded from ALL temperature
math, and rendered in both stations UIs as "failed QC check" with the temperature replaced
by "—".

1. `SynopticApi.fetchSynopticObservations` — request `qc=on`, `qc_checks=all`, `qc_flags=on`.
2. `SynopticApi.parseSynopticTimeseries` — read `STATION[0].QC.air_temp_set_1` (parallel
   array; non-null entry = failed check IDs) and set `qcFailed` on the reading (kept, logged).
   All-flagged is still `Success` (data was learned), consumers find no usable latest.
3. `NwsApi.Observation.qcFailed` + `ObservationReading.qcFailed` model fields.
4. Blend guards: `SpatialInterpolator.interpolateIDW` and
   `ActualTemperatureSeriesBuilder.blendObservationSeries` filter `!qcFailed` (covers current
   temp, actual line, and daily extremes on both platforms).
5. Desktop: bundle `latest` = last non-flagged Synoptic reading (flagged ones flow to
   historical → stored → UI); `qcFailed` column in desktop schema v11
   (`addColumnIfMissing`), DAO insert/reads; `ObservationsWindow` shows "failed QC check"
   in #FF3366 and "—" for the temp.
6. Android: Room v55→56 migration adds `observations.qcFailed`; `ObservationRepository`
   stores the flagged latest (marked, `OBS_QC_FLAGGED` app-log) but anchors on the last
   non-flagged reading; `WeatherObservationsActivity` shows `station_origin_qc_failed`
   (translated in all 20 locales) in #FF3366 and "—" for the temp.
7. Tests, one per layer:
   - `FetchOutcomeTest` (shared JVM) — parser marks flagged readings; all-flagged is Success;
     absent QC block marks nothing.
   - `SpatialInterpolatorTest` (app JVM) — flagged reading excluded from IDW; all-flagged → null.
   - `ActualTemperatureSeriesBuilderTest` (shared JVM) — flagged reading excluded from the
     blended series (the path that anchors the displayed current temp).
   - `WeatherObservationsActivityRobolectricTest` — flagged row renders "OFFICIAL (Failed QC
     check)" in #FF3366 with "—" for the temp; clean sibling unaffected.
   - `WeatherDatabaseMigrationTest` (instrumented, emulator) — 55→56 preserves rows, defaults
     qcFailed=0, accepts 1.
   - Skipped deliberately: repository-level fakes for `fetchStationObservation` (no mocking
     framework; thin glue) and desktop Compose UI tests (no harness exists).

## Non-goals

- No value-based outlier rejection in `SpatialInterpolator` (upstream QC covers the path that
  actually failed; a local median guard remains a possible future hardening).
- NWS's own per-field `qualityControl` codes on api.weather.gov observations are a separate,
  similar opportunity — not touched here.

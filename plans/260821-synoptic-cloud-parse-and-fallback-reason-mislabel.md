# Synoptic web fallback: cloud data discarded, and a timeout logged as "empty"

Date: 2026-08-21
Modules: `:shared`, `:app`

## Problem

On the medium emulator (`emulator-5556`, Mountain View), every NWS actual-cloud value came from
**KSJC at 15.9 km**, while **KNUQ at 3.8 km** — the nearest official station — contributed none.

```
station   rows   cloudCoverLow   isWebFallback
KSJC      834    514             0
KNUQ      198    0               198  (100%)
KPAO       38    0                38  (100%)
```

The backfill log said KNUQ had no data:

```
10:01:07  NWS_HISTORY_FETCH_FAIL        station=KNUQ error=HttpRequestTimeoutException:Request timeout has expired
10:01:07  OBS_HOURLY_SYNOPTIC_FALLBACK  station=KNUQ reason=empty
10:01:08  OBS_HOURLY_BACKFILL_STATION   station=KNUQ rows=199 web=true
```

It has plenty. Fetching the exact URL the app built, from the host:

```
fractional (as the app sends)  KNUQ  http=200  1.6s  features=196
truncated to seconds           KNUQ  http=200  1.8s  features=196
```

So this is neither a station outage nor the fractional-seconds trap recorded in
`nws_observations_fractional_seconds` — that memory predicts 0 features for the fractional form and
it returned 196 (see Follow-up).

### Root causes

1. **A caught exception is relabelled as a data condition.**
   `NwsObservationSource.fetchHistorical` catches the timeout and returns `emptyList()`
   (`NwsObservationSource.kt:264-272`). `ObservationFallbackPolicy.fallbackReason(count)` then sees
   `0` and renders `"empty"` — which reads as "the station reported nothing" when the truth is "the
   request never completed". The timing is unambiguous: `requestTimeoutMillis = 30_000`
   (`AppModule.kt:119`), KNUQ began right after AW020 finished at 10:00:35 and failed at 10:01:07.

   The same laundering hits stations past the fallback tier with no fallback to soften it. KSJC
   timed out at 10:01:55 and logged `OBS_HOURLY_BACKFILL_STATION station=KSJC rows=0 web=false` —
   indistinguishable from a station that genuinely returned nothing.

2. **The Synoptic fallback discards cloud data it already receives.**
   `parseSynopticTimeseries` (`SynopticApi.kt:55-95`) reads `date_time`, `air_temp_set_1`,
   `weather_summary_set_1d` / `weather_condition_set_1d` and the QC flags, then builds
   `NwsApi.Observation` **without** `cloudLayers`. The app's *existing* request — no added
   parameters — already returns 19 variables including the raw METAR:

   ```
   date_time              air_temp  cl_layer1  ceiling  metar
   2026-08-21T14:35:00Z   16.0      124.0      365.76   KNUQ 211435Z AUTO 35003KT 10SM OVC012 16/13 …
   2026-08-21T15:55:00Z   17.0      144.0      426.72   KNUQ 211555Z AUTO 36004KT 10SM OVC014 17/13 …
   2026-08-21T17:15:00Z   18.0      153.0      457.2    KNUQ 211715Z AUTO 36008KT 10SM BKN015 18/13 …
   ```

   `metar_set_1`, `cloud_layer_1_code_set_1`, `cloud_layer_1_set_1d` and `ceiling_set_1` are on the
   wire on every row and are dropped on the floor. This is an unparsed field, **not** a policy.

3. **Two comments assert the opposite, and one of them is load-bearing.**
   - `HourlyObservationBackfill.metarCloudGapReason` kdoc: *"Synoptic web-fallback rows are
     temperature-only by policy, so neither can ever satisfy the check and must not keep it
     firing."* This justifies `!it.isWebFallback` in the filter — so KNUQ's 198 rows are invisible to
     the cloud-sparsity check, which reads a comfortable `cloudBuckets=42 officialBuckets=66`
     (repair fires only below 33) and declines to repair anything, while the curve is fed from
     15.9 km away.
   - `NwsObservationMapper.toReading`: *"Synoptic web-fallback readings are never METARs."* They
     carry `metar_set_1`; `isMetar` is therefore wrongly false on every web row, which matters to
     `MetarCloudBlender`'s METAR-over-ASOS preference (c3347274).

The three compound: the timeout is invisible because it is labelled `empty`, the fallback that
covers for it throws cloud away, and the health check that would notice excludes exactly those rows
on a false premise.

## Fixes

### 1. Keep "failed" distinguishable from "absent"

- `ObservationFallbackPolicy.fallbackReason(apiObservationCount: Int, apiFetchFailed: Boolean)`:
  `apiFetchFailed -> "fetch_failed"`, then `count == 0 -> "empty"`, else `"stale"`.
- `fetchHistorical` tracks whether the API call threw and passes it, appending the exception's simple
  name: `reason=fetch_failed:HttpRequestTimeoutException`.
- `fetchAndStoreStation` adds the same marker to the station line, so the KSJC case reads
  `station=KSJC rows=0 web=false apiFetchFailed=true` rather than a bare `rows=0`.
- **Diagnostics only — no behaviour change.** `shouldUseWebFallback` already treats a failed fetch as
  stale (`newestObservationMs == null`), so the fallback still fires exactly when it does today.
  Only the label changes. Keeping the old two-value signature as an overload is not worth it; there
  are two call sites.

### 2. Parse cloud out of the Synoptic response

- New pure helper in `:shared`, `MetarRawSkyParser.layersFrom(raw: String): List<NwsApi.CloudLayer>`:
  - Cut the report at ` RMK ` first — the remarks section repeats sky-like tokens and must not be
    read as layers.
  - `(FEW|SCT|BKN|OVC|VV)(\d{3})` → `CloudLayer(amount, baseMeters = hundreds * 100 * 0.3048)`.
  - Bare `CLR|SKC|NCD|CAVOK` → a single clear layer, base null.
  - `///` height and unknown amounts → drop the layer rather than guess; an unrecognised amount must
    never read as clear (same rule `MetarSkyCover` already enforces).
  - Empty/blank/`M` (Synoptic's missing sentinel) → `emptyList()`, i.e. "not reported".
- `parseSynopticTimeseries` reads `metar_set_1`, sets `cloudLayers = MetarRawSkyParser.layersFrom(...)`
  and `isMetar = raw.isNotBlank()`.
- **Nothing downstream changes.** `NwsObservationMapper.toReading` already maps
  `cloudLayers → cloudCoverLow` via `MetarSkyCover.lowPercent`, and `NwsObservationSource.toEntity`
  already carries `cloudCoverLow` and `isMetar` into `ObservationEntity`. Populating `cloudLayers` at
  the source is the whole change.
- **Why the raw METAR rather than `cloud_layer_N_code_set_1`:** the coded form decodes cleanly
  (`124` → 1200 ft, code 4 = OVC; `153` → 1500 ft, code 3 = BKN) but needs its own code table and a
  per-layer variable sweep, and it cannot set `isMetar` honestly. The raw string is the same data the
  NWS API path already consumes, so both paths end up expressing sky condition the same way.
- Correct the `toReading` comment; `isMetar` now means what it says on both paths.

### 3. Let web-fallback rows count toward cloud health

- Drop `!it.isWebFallback` from `metarCloudGapReason`'s filter and rewrite the kdoc to state the real
  reason for the remaining exclusion: PERSONAL stations have no ceilometer and report
  `cloudLayers: []` on every report, so they can never satisfy the check.
- **Ordering matters — this must land after fix 2**, or the check starts counting rows that are still
  structurally cloudless and fires forever.
- Expected one-off consequence: web rows already stored with `cloudCoverLow = NULL` drag
  `cloudBuckets` down, trip the repair once, and the 72 h re-fetch re-parses them under REPLACE.
  **This did not happen — see Deviations.** The measure is bucket-level, so KSJC alone keeps the
  ratio healthy.

## Verification

### Unit tests

New `MetarRawSkyParserTest` (`:shared`):
- `OVC012` → one layer, OVC, 365.76 m (±0.01).
- `FEW010 SCT020 BKN040` → three layers in order, heights 304.8 / 609.6 / 1219.2 m.
- `CLR` and `SKC` → one clear layer, base null.
- `VV002` → VV at 60.96 m (obscured sky, mapped to 100 by `MetarSkyCover`).
- Remarks stripped: `… OVC012 16/13 A3005 RMK AO2 SLP176 BKN999` → one layer, not two. **This is the
  test that fails without the ` RMK ` cut** — prove it does before fixing.
- `///` height and an unknown amount → layer dropped, not guessed.
- blank / `M` → `emptyList()`.

Extend `FetchOutcomeTest` (already drives `parseSynopticTimeseries` with fixture JSON):
- fixture with `metar_set_1` → parsed observations carry `cloudLayers` and `isMetar = true`.
- fixture without `metar_set_1` (older/mesonet station) → `cloudLayers` empty, `isMetar = false`,
  temperature still parsed. Guards against a station that legitimately has no METAR.
- a row whose `air_temp_set_1` is null is still skipped — the existing contract, unchanged.

Extend `ObservationFallbackPolicyTest`:
- `fallbackReason(0, apiFetchFailed = true)` → `"fetch_failed"`.
- `fallbackReason(0, apiFetchFailed = false)` → `"empty"`.
- `fallbackReason(12, apiFetchFailed = false)` → `"stale"`.
- `shouldUseWebFallback` unchanged for a null newest — pins that fix 1 is diagnostics-only.

Extend `NwsObservationMapperTest`:
- a Synoptic-shaped reading with METAR layers and `isWebFallback = true` maps to a non-null
  `cloudCoverLow` — the assertion that would have caught this whole class of bug.

New cases in `HourlyObservationBackfillLocationTest` (or a sibling next to
`HourlyObservationBackfillCooldownTest`, whichever the file layout prefers):
- web-fallback rows carrying cloud now **count** toward `cloudBuckets`.
- PERSONAL rows still excluded even when they somehow carry a value.
- all-official-cloudless → still fires `metar_cloud_sparse`.

### Integration test

`NwsObservationSourceTest` already covers this class; add a case spanning
`NwsObservationSource` + `SynopticApi` + `NwsObservationMapper` (three classes, so a genuine
integration test by the standing definition): a stubbed NWS call that **throws**, a Synoptic fixture
carrying METARs → the stored entities are web-fallback rows with non-null `cloudCoverLow`, and the
logged reason is `fetch_failed`, not `empty`. This is the emulator failure reproduced in a test.

### Manual

On `emulator-5556`, after install:
- force a backfill and confirm KNUQ rows land with `cloudCoverLow` populated
  (`SELECT stationId, sum(cloudCoverLow IS NOT NULL) FROM observations WHERE isWebFallback=1`).
- confirm any timeout now logs `reason=fetch_failed:…` rather than `reason=empty`.
- confirm the cloud blend's dominant station moves from KSJC (15.9 km) to KNUQ (3.8 km).

## Follow-up (not in this change)

- **The 30 s timeout itself.** The same request takes 1.6 s from the host and times out on the
  emulator. Worth measuring whether this is emulator NAT/DNS or a real NWS slow path before touching
  `requestTimeoutMillis` — raising a timeout to hide a network problem is the wrong repair, and the
  fallback already covers the outcome.
- **`nws_observations_fractional_seconds` may be stale.** The fractional form returned 196 features
  today. The Android backfiller still emits fractional seconds via `DateTimeFormatter.ISO_INSTANT`
  and is working. Re-verify before relying on that memory again; the desktop truncation it prompted
  is harmless either way.
- **Cloud from `ceiling_set_1`.** Synoptic returns a ceiling height even for rows without a usable
  METAR. Not needed once fix 2 lands, but it is a second source if a station's METAR is absent.

## Status — implemented 2026-08-21

All three fixes shipped.

### Files

- `ObservationFallbackPolicy.kt` — `fallbackReason(count, apiFetchFailed)`; a failure outranks the
  row count.
- `NwsObservationSource.kt` — captures the thrown exception's simple name, threads it through the
  three return paths, and appends it to the reason (`fetch_failed:HttpRequestTimeoutException`).
  `HistoricalStationObservations` gains `apiFailure`.
- `NwsObservationBackfiller.kt` — station line carries `apiFetchFailed=…` so a station past the
  fallback tier is not a bare `rows=0`.
- `MetarRawSkyParser.kt` (new, `:shared`) — raw METAR → `List<CloudLayer>`.
- `SynopticApi.kt` — reads `metar_set_1`, sets `cloudLayers` and `isMetar`.
- `NwsObservationMapper.kt` — corrected the "never METARs" comment.
- `HourlyObservationBackfill.kt` — `metarCloudGapReason` counts web-fallback rows;
  `officialApiRows` → `officialRows`.

### Deviations from the plan

- **`///` height keeps the layer.** The plan said to drop unreadable heights alongside unknown
  amounts. Wrong call: `MetarSkyCover.lowPercent` deliberately admits unknown-height layers because
  dropping them hides real low cloud. `BKN///` now yields `BKN` with a null base. Unknown *amounts*
  still never match, so they can never read as clear.
- **The regex boundary had to change.** `\b` cannot fire after `///` (no word character on either
  side), so `BKN///` silently failed to match while `BKN012` matched. The trailing edge is now
  `(?![A-Z0-9])`. Caught by the test, not by review.
- **An existing test pinned the old behaviour.** `HourlyObservationBackfillLocationTest`'s
  `NWS cloud check ignores web-fallback rows` asserted the exclusion and repeated the false premise
  in its comment. Inverted to `…counts web-fallback rows and reports them when cloud-less`, plus a
  companion for the satisfied case — not deleted, because the case is still worth pinning.
- **`HistoricalStationObservations.apiFailure` was not in the plan.** The station log line lives in
  a different class from the fetch, so the failure had to be carried rather than re-derived.
- **Fix 3 does not surface a single degraded station, and the plan implied it would.** The predicted
  one-off repair trip did not occur. Measured immediately after install: admitting KNUQ's and KPAO's
  rows moved the ratio from 42/66 to **44/72** — still healthy — because the measure is bucket-level
  ("did *any* official station report cloud this hour") and KSJC covers nearly every bucket alone.
  So counting web rows is correct and lets them contribute once they carry cloud, but it detects a
  broken *series*, never one station that has stopped contributing. The kdoc now says so.

### Results

- `:shared:test` green (incl. 11 new `MetarRawSkyParserTest`, 3 new Synoptic cases in
  `FetchOutcomeTest`, 3 new `ObservationFallbackPolicyTest`, 1 new `NwsObservationMapperTest`).
- `:desktop:test` green.
- `:app:testDebugUnitTest` — 2018 tests green, incl. new `HourlyObservationBackfillCloudGapTest` (9)
  and the `NwsObservationSourceTest` integration case spanning `NwsObservationSource` +
  `ObservationFallbackPolicy` + `MetarRawSkyParser` + `NwsObservationMapper`.
- Both key tests proved able to fail: neutering the ` RMK ` cut fails
  `remarks are not read as layers`; neutering `apiFetchFailed` fails the integration test with
  `reason=empty:IOException`.
- Installed on `emulator-5556`; backfill decisions remain `coverage_ok`, no loop, no chaining.

### Still outstanding

Live confirmation that a Synoptic-sourced row lands with `cloudCoverLow` populated on device. The
fetch cooldown (60 min plugged in, last fetch 09:59) had not elapsed by the end of the session, so no
new observation rows were written under the fixed build. The parse itself is covered by tests and by
a direct Synoptic query; what remains unproven on device is only the wiring.

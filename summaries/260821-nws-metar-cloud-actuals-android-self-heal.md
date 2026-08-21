# NWS METAR cloud actuals: why Android showed nothing, and the self-healing repair

**Date:** 2026-08-21
**Commit:** uncommitted (working tree)
**Files:** `MetarCloudBlender.kt`, `HourlyObservationBackfill.kt`, `CloudCoverViewHandler.kt`
(app/shared), `HourlyObservationBackfillLocationTest.kt`, `MetarCloudBlenderTest.kt`
**Plan:** `plans/260820-nws-metar-cloud-cover-idw-blend.md` (status: shipped)

## Problem

The NWS METAR cloud-cover actuals (IDW blend over the 5 nearest stations, plan 260820) worked on
desktop from the first run but drew **no actual curve on Android** — emulator or phones — even
though the new code was installed and running.

## Evidence chain

1. `CLOUD_SERIES src=NWS ... actual=0 inWindow=0 stationsWithLayers=1 stationsSkipped=4` — the
   blend ran, found the 5 stations, one carried cloud, yet emitted zero hours.
2. DB pull: of ~4,600 NWS observation rows, exactly **1** (KSJC, written post-upgrade) carried
   `cloudCoverLow`. Every historical row was filed by the old build with `cloudCoverLow=NULL`.
3. The current-observation updater writes ~1 row/station/cycle, so coverage grew at a crawl — and
   rows whose hours were already past stayed null forever.
4. `OBS_HOURLY_BACKFILL_SKIP reason=coverage_ok` — the 72h observation repair explicitly declined
   to run. Its verdict is **temperature-only**: sky condition rides the same `/observations`
   payload, but nothing in the coverage check ever looked at it.
5. Desktop never noticed because its refresh **rewrites the whole observation window every cycle**
   with REPLACE — post-upgrade rows picked up cloud on the next refresh. Android's repair machinery
   had no equivalent path: the 72h hourly backfill gates on temperature gaps, the daily backfill
   gates on daily-extreme completeness (satisfied).

In short: the feature was correct; the *data* needed a re-parse, and Android had no trigger that
could see the need for one.

## Fix — automatic repair whenever cloud data is missing (no one-time flag)

### 1. `metar_cloud_sparse` branch in `evaluateHourlyBackfillNeed`

After temperature coverage passes (`coverage_ok`), compute per-hour buckets (round-to-nearest, the
blender's own rule) over non-QC, non-web-fallback, `OFFICIAL`-station rows. If fewer than half of
the buckets an official station reports into carry any cloud value, request the same 72h repair —
REPLACE re-parses the same payload with `cloudLayers`, no new HTTP calls.

Basis restrictions so the trigger cannot fire forever in locations that can never satisfy it:

1. `PERSONAL` stations (PWS, no ceilometer — `cloudLayers: []` on every report).
2. Synoptic web-fallback rows (temperature-only by policy).
3. QC-failed rows.

Steady-state safety: with ~25-30% of reports partial, a healthy series leaves cloud in ≈95%+ of
multi-report buckets; the half-way threshold only trips when the series is genuinely broken
(pre-feature rows, fresh location, dead feed). A permanently cloudless official station retries at
the shared 30-minute cooldown, matching the existing temperature-gap repair posture.

### 2. The cloud view can heal itself

The coverage probe only ran from the temperature/daily view handlers — a widget parked in CLOUD
view would never trigger the repair for its own missing curve. `CloudCoverViewHandler.updateWidget`
now runs the same decision over the same 72h window, NWS-only, behind the shared cooldown key.

### 3. Cloud-carrier preference in `MetarCloudBlender`

~25-30% of official METARs are partial reports that omit `cloudLayers`. When the report nearest
the hour omitted sky condition but another report of the same station in the bucket carried one,
the hour now uses the carrier instead of emitting nothing. The fallback stays inside the ±30-minute
bucketing tolerance the round-to-hour rule already accepts. Counted as `Stats.shadowedBuckets`
(visible as `shadowed=N` in `CLOUD_SERIES`).

### 4. `METAR_BLEND_DROPPED` diagnostic

A WARN inside the blend, gated on exactly the pathological state (cloud-carrying readings entered,
zero hours left). Dumps per-station row/cloud/QC counts and the per-bucket selection, so the next
silent failure of this class is self-describing. Added while diagnosing this issue; the running
build's first healthy render made it unnecessary and it did not fire again.

## Verification (emulator-5554)

```
00:36:18  OBS_HOURLY_BACKFILL_REQ  reason=metar_cloud_sparse cloudBuckets=2 officialBuckets=72
00:36:35  OBS_HOURLY_BACKFILL_RUN  lookbackHours=72  lat=37.417 lon=-122.089
00:36:37  OBS_HOURLY_BACKFILL_STATION station=AW020 rows=421
00:37:01  OBS_HOURLY_BACKFILL_STATION station=KNUQ  rows=188
00:37:02  OBS_HOURLY_BACKFILL_STATION station=KPAO  rows=40 web=true   (stale → Synoptic)
00:37:02  OBS_HOURLY_BACKFILL_STATION station=LOAC1 rows=71
00:37:04  OBS_HOURLY_BACKFILL_STATION station=KSJC  rows=500
00:37:49  CLOUD_SERIES actual=15 stationsWithLayers=2 shadowed=3 blendWidth=[w2=15h]
```

Screenshots after: solid actual curve over past hours under NWS on both emulators and both phones.
Confirmed visually by the user on Pixel, Samsung, and both emulators.

Tests: `MetarCloudBlenderTest` (+3: carrier preference, all-partial bucket, rescued-station blend)
and `HourlyObservationBackfillLocationTest` (+4: sparse-cloud trigger, PWS-only, healthy cloud,
web-fallback exclusion) — all green. Determinism and the pre-existing temperature-gap decisions
unchanged.

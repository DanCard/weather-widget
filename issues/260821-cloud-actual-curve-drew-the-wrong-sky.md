# Issue: the cloud actual curve drew the wrong sky

**Date:** 2026-08-21
**Reported from:** Samsung Galaxy Fold (`SM-F936U1`), CLOUD_COVER view, source NWS, Mountain View
**Fixed in:** `5400e3f3` (defects 1–3), plus a follow-up commit for defect 4
**Status:** all 4 defects fixed.

## Symptom

Two complaints, one morning, which turned out to be four separate defects:

1. *"first part of cloud cover actual line missing"* — the 1a–5a graph drew its solid actual curve
   starting at **2a**. The left-most hour was simply absent.
2. *"the zero is wrong, it is 100% cloud cover near me"* — the curve reported clear/partly-cloudy
   hours while every station within 6 km was reporting overcast.

Both looked like missing or bad upstream data. Neither was: in every case the correct value was
either already in the database or already in the API payload we had fetched.

---

## Defect 1 — the read window was narrower than the bucketing rule (FIXED)

### Cause

Cloud actuals bucket observations by **round-to-nearest hour** (`CloudHourBucket.indexOf`). That is
deliberate and load-bearing — a METAR is an *instantaneous* reading, and flooring instead once
dropped KPAO (which reports at `:47`) almost entirely. Rounding gives every report a **±30 minute
reach**.

The row read, however, was a raw timestamp filter over the *visible* window:

```sql
WHERE timestamp >= :startTs AND timestamp < :endTs
```

These are two different windows that look like the same window. A report in the half-hour **before**
the first hour mark rounds *into* that hour but fails the filter, so the graph's leading hour could
only ever be served by a report at or after the mark — half the tolerance the rounding rule
promises.

### Evidence

KSJC's reports for the 1a–5a window, with their buckets:

| Report | Buckets into | Inside `timestamp >= 01:00`? |
|---|---|---|
| **00:30** | **01:00** | **no — missed by exactly 30 min** |
| 02:05 | 02:00 | yes |
| 02:20 | 02:00 | yes |
| 03:10 | 03:00 | yes |
| 03:50 | 04:00 | yes |

The 00:30 row was in the database the whole time. Only the query dropped it.

### Fix

`MetarCloudBlender.fromSiteRows` now owns the read range, taking a `readSiteRows` lambda and calling
it with `CloudHourBucket.readStartMs` / `readEndMs`. **Read padded, emit unpadded.**

- The pad is exactly `TOLERANCE_MS` (half an hour), never a full hour: an hour would drag whole
  extra hour marks — and the synthetic `<SOURCE>_MAIN` rows that sit on them — into the read. The
  non-NWS branch re-bounds to the unpadded window for the same reason.
- The pad lives in the **shared seam, not the two DAOs**. `fromSiteRows` exists precisely so Android
  and desktop cannot disagree about which rows back an hour; both DAOs had this bug, and leaving the
  pad in them would re-open the divergence that function was built to close. It is `suspend` now
  because Room's read is.

---

## Defect 2 — `getLatestObservation` never parsed `cloudLayers` (FIXED)

### Cause

`NwsApi.getLatestObservation` is a hand-rolled **partial copy** of `parseObservationProperties` —
both live in the same companion object — and it simply omitted the field. `Observation.cloudLayers`
therefore defaulted to `emptyList()`, which `MetarSkyCover` correctly reads as *"not reported"*.

Every station reached by the live "latest" path stored **no sky condition whatsoever**. Only the
historical `?start=&end=` path, which calls the real parser, carried cloud.

### Evidence

Per-station rows on the device, 8-hour window:

| Station | Distance | Rows | With cloud | Served by |
|---|---|---|---|---|
| KNUQ | 3.8 km | 23 | **0** | live "latest" path |
| KPAO | 6.1 km | 2 | 1 | mixed |
| KSJC | 15.9 km | 14 | **14** | historical path |

Two code paths, not two skies. Meanwhile the payload had it all along:

```
GET /stations/KNUQ/observations/latest
  cloudLayers: [{"amount":"OVC","base":{"unitCode":"wmoUnit:m","value":400}}]
```

`OVC` at 400 m — 100% low cloud, discarded at parse time.

### Fix

`getLatestObservation` now calls `parseCloudLayers(props["cloudLayers"])`.

The duplication with `parseObservationProperties` is flagged in a comment but **deliberately not
unified**: doing so would also start populating precip and 24h min/max on this path, which touches
rain accounting and does not belong in a cloud fix. See [Follow-ups](#follow-ups).

---

## Defect 3 — the Synoptic swap discarded the NWS row's sky (FIXED)

### Cause

`LatestObservationMerge.preferNewest` takes the web reading when it is strictly newer. Synoptic
republishes 20–60 minutes ahead of the NWS API, so KNUQ resolved `chosen=web` on essentially every
fetch — and `SynopticApi` carries **no sky condition at all** (zero mentions of cloud in the file).

A decision made about *temperature freshness* silently destroyed the station's cloud, because
`preferNewest` compares one field but swaps the **whole row**.

### Evidence

```
station=KNUQ tier=use apiNewestMs=… webNewestMs=… deltaMin=40
  apiTempC=16.0 webTempC=17.0 webQcFailed=false chosen=web
```

The tell in the stored rows is `isWebFallback=1` together with a lowercase `condition` — Synoptic
writes `overcast` where NWS writes `Cloudy`. Every KNUQ row bore both marks.

### Fix

`LatestStationObservation.cloudCarrier` stores the NWS API row **alongside** the chosen Synoptic row
(its own timestamp keeps it a distinct primary key), logged as `OBS_CLOUD_CARRIER`.

Both this and Defect 2 were required — parsing cloud alone cannot help a station whose row is always
replaced.

### Verified on-device

```
09:20:53  OBS_CLOUD_CARRIER station=KNUQ cloudLow=100
          reason=web_won_on_freshness_but_carries_no_sky
```

| Station | Distance | Low cloud | IDW weight (∝ 1/d²) |
|---|---|---|---|
| **KNUQ** | **3.8 km** | **100** | **~17× KSJC** |
| KPAO | 6.1 km | 100 | ~6.8× KSJC |
| KSJC | 15.9 km | 100 | 1× |

The blend is now driven by the station nearest the user instead of resting entirely on one 15.9 km
away.

---

## Defect 4 — the official METAR was never selected (FIXED)

### Cause

`api.weather.gov/stations/{id}/observations` interleaves **two different instruments** in one array,
distinguishable only by `rawMessage`:

| Feed | Cadence | `rawMessage` | What it measures |
|---|---|---|---|
| METAR | `:53` + specials | populated | Official sky cover — a **30-minute rolling** ceilometer assessment |
| ASOS 5-minute | every `:00/:05/:10…` | **empty** | **Instantaneous, single-point** sample directly overhead |

`MetarCloudBlender` picks each station's contribution with
`minByOrNull { abs(it.timestamp - hourMs) }` — nearest to the hour mark. The 5-minute feed publishes
a row **exactly on the mark** (distance 0); the METAR sits at `:53`, 7 minutes away. So for any
station publishing 5-minute data the METAR can **never** be selected. A blender named for METARs is
not using one.

### Why the 5-minute feed is the wrong reading

`CLR` in that feed arrives with `base: 3810` m — ≈12,500 ft, the ceilometer's **detection ceiling**.
It means *"nothing overhead at this instant"*, not *"the sky is clear"*. Under scattered or broken
cloud the beam has a cloud over it one sample and not the next, so the feed flips `CLR`↔`SCT`
(0↔44) minute to minute while the station's own METAR steadily reads `SCT040`.

Measured at KSJC over the 00:00–05:05 window: **60 of 66 samples were `OVC`**, with isolated `BKN`
dips at 00:30 and 03:50. Those two momentary dips are exactly what the graph drew as real hourly
dips at 1a and 4a.

### Fix

The METAR is now preferred whenever one falls in the bucket. Three pieces:

1. **`NwsApi` parses `rawMessage`** into `Observation.isMetar` in *both* parsers. `rawMessage`
   non-empty is the only reliable discriminator — minute-of-hour cannot do it, because KSJC and
   KPAO report at `:53`/`:47` but **KNUQ's METARs land on `:15`/`:35`/`:55`**, multiples of five and
   indistinguishable by timestamp from 5-minute rows.
2. **`observations.isMetar` persists it** — Room `MIGRATION_63_64` (v64) and desktop
   `SCHEMA_VERSION = 18`, both `INTEGER NOT NULL DEFAULT 0`.
3. **`MetarCloudBlender` prefers it per station.** Cloud-carrying rows are filtered first, then the
   METAR class wins if non-empty, then nearest-to-the-hour decides *within* the chosen class. The
   preference selects a class, not a row.

Deliberate properties of the rule:

- **Per-station, not global.** One station having a METAR must not suppress another that only has
  5-minute rows; blend width is unaffected.
- **A partial METAR does not blank the hour.** The carrier filter runs before the preference, so a
  METAR that omitted sky condition yields to a cloud-carrying 5-minute row rather than dropping the
  station.
- **Existing rows read `false` and behave exactly as before.** The column is backfilled as 0 rather
  than guessed at: minute-of-hour cannot re-derive it and the raw payloads are long gone. The
  preference fades in as fresh rows arrive instead of writing a wrong guess into history.
- **New `metarPreferred=` counter** in the `CLOUD_SERIES` stats line. Near zero at an airport
  station means `rawMessage` is not arriving and the curve has quietly reverted to instantaneous
  samples.

### Considered and rejected

Aggregating within the bucket (median or modal cover across a station's samples) would blunt the
flicker even where no METAR exists, but it trades away the "instantaneous reading plotted at an hour
mark" premise the code documents. Preferring the METAR keeps that premise and simply picks the
*right* instantaneous-ish reading. Worth revisiting only if a station is found that publishes
5-minute rows and no METAR.

---

## Cross-cutting lessons

- **A rounding rule creates a read-side obligation.** Whenever a series buckets by rounding rather
  than flooring, the query range and the emission window are *different windows*, and the query one
  must be at least as wide as the rounding reaches. Flooring hides this; switching to
  round-to-nearest silently created a requirement nobody propagated. Same family as the existing
  note in `blend_window_gates_emission_not_math`.
- **A hand-written copy will drift, and the drift is silent.** This bit three times in one
  investigation: Defect 1 (two DAOs), Defect 2 (two parsers), and — while fixing Defect 4 — a
  field-by-field `ObservationReading` → `ObservationEntity` conversion in
  `NwsObservationSource.toEntity` that quietly dropped the new `isMetar` field. Every one of them
  was duplication held together by a comment, and every one failed by *omission*, which no compiler
  catches. The third was caught only because the round-trip test asserted a value rather than a
  code path — see [Regression tests](#regression-tests-added).
- **Check what else rides on the row.** `preferNewest` compares timestamps to answer a question
  about temperature, then swaps everything else along with it.
- **"Not reported" must never be spelled `0`.** `MetarSkyCover` gets this right, and it is why
  Defect 2 presented as a *missing* curve rather than a confidently wrong one — which is the
  strictly better failure mode.

## Follow-ups

1. **Unify `getLatestObservation` with `parseObservationProperties`.** It would remove the class of
   bug behind Defect 2 outright, but it also starts populating `precipLastHourMm` and 24h min/max on
   the live path. `DailyActualsStore` sums precip across observations, so this needs its own
   assessment of rain-total impact.
2. **Investigate the live path's row density.** The live path stores ~1 row per station per fetch
   while the history backfill stores dense 5-minute rows. With 60 `OVC` samples available, an hourly
   value should not be decided by whichever single flicker happened to be persisted.

## Regression tests added

| Test | Pins |
|---|---|
| `MetarCloudBlenderTest` — half-hour-before case | Drives `fromSiteRows` through a range-filtering fake reader and asserts the **requested range**, so shrinking it back to the bare window fails |
| `NwsCloudActualsRoundTripTest` — half-hour-before case | The same through real Room — the blender always bucketed correctly; it was the *query* that dropped the row |
| `CloudHourBucketTest` | The pad equals the tolerance and never spans another hour mark |
| `NwsApiTest` — latest-observation cloud | KNUQ's real `/observations/latest` payload reaches the blend as 100% low cloud |
| `NwsApiCloudLayersParseTest` — rawMessage | METAR / 5-minute / field-absent payloads map to the right `isMetar` |
| `MetarCloudBlenderTest` — 5 METAR-preference cases | METAR beats an on-the-mark sample; nearest-to-mark still decides among METARs; no-METAR and partial-METAR buckets still contribute; the preference stays per-station |

Each was confirmed to **fail with its fix reverted**, not merely to pass with it.

The Room round-trip test earned its keep during defect 4: it failed with `expected:<75> but was:<0>`
because `NwsObservationSource.toEntity` was silently dropping `isMetar` on the way to the database.
Every unit test around it passed — the parser set the field, the blender preferred it — and only a
test that wrote through the real entity conversion and read back a *value* could see the gap.

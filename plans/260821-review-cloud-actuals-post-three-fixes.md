# Code Review: cloud-actuals arc after the "three silent losses" fix (commits 83832382, 5400e3f3)

**Date:** 2026-08-21
**Scope:** the whole dozen-commit cloud-cover-actuals arc, but focused on what is *new* since
`plans/260821-review-cloud-actuals-feature-arc.md` and its implementation
(`plans/260821-implement-cloud-review-findings.md`): commit `83832382` (the implementation pass)
and `5400e3f3` (the three-silent-losses fix), plus its `issues/260821-cloud-actual-curve-drew-the-wrong-sky.md`.

## Verdict

The prior review's findings (A1–A4, B1–B5, C1–C4) are genuinely implemented and the shared seam
now holds: both DAOs delegate to `MetarCloudBlender.fromSiteRows`, both renderers draw
`CloudSeriesBuilder`'s low-preferred `forecastCover`, the source gate is one shared property, the
palette/watermark/observation-mapper/hour-bucket are all lifted into `:shared`. That work is good
and I am **not** re-opening it.

The three-silent-losses fix is also correct and the `read padded / emit unpadded` decision is the
right call (a rounding rule creates a read-side obligation — the pad *has* to live next to the
rounding rule, i.e. in `CloudHourBucket`/`fromSiteRows`, not in the two DAOs).

What remains are four structural items, two of which the issues doc already named as follow-ups and
two of which are *new* side effects of the fix itself. None is a correctness bug in the happy path;
all are places where the next change can silently drift the same way this one did.

---

## Findings (prioritised)

### F1. The two NWS observation parsers are still duplicated — the class of bug behind Defect 2 (high)

`NwsApi.getLatestObservation` (line ~308) is still a hand-rolled partial copy of
`parseObservationProperties` (line ~93), differing by *omission*: `stationName`,
`maxTempLast24hCelsius`, `minTempLast24hCelsius`, `precipLastHourMm`, and previously `cloudLayers` +
`isMetar`. The issues doc's own "Follow-up 1" is to unify them, and its "cross-cutting lessons"
section already diagnosed the general rule: *a hand-written copy drifts by omission, and no compiler
catches it*.

The stated reason not to unify — "it would start populating precip and 24h min/max on the live
path" — is real but is not actually a reason to keep two parsers. It is a reason to make the parse
complete and the *projection* explicit:

```kotlin
// one parser, one source of truth for field semantics
val obs = parseObservationProperties(props, stationId)

// live path: deliberately not persisting these (rain accounting / daily extremes)
obs.copy(precipLastHourMm = null, maxTempLast24hCelsius = null, minTempLast24hCelsius = null)
```

That keeps exactly one place that knows what each JSON field means, and moves the "don't touch rain
accounting" decision to a named, documented projection. Add a round-trip test that asserts every
field of a real `/observations/latest` payload survives, mirroring the KNUQ fixture already in
`NwsApiTest`.

### F2. `cloudCarrier` is a full observation row, not a cloud-scoped one (high — unassessed side effect)

`NwsObservationSource.fetchLatest` (line ~193) documents the carrier as *"Keep the API row for its
cloud alone"*, but `cloudCarrierEntity = toEntity(apiObservation, …)` (line ~200) builds a complete
`ObservationEntity` — temperature, `precipAmountMm`, `maxTempLast24h`, `minTempLast24h`, the lot —
and `NwsCurrentObservationUpdater.fetchAndStoreStation` (line ~138) `insertAll`s it into the same
`observations` table as every other row.

So the fix's blast radius is wider than "cloud": the NWS row that was previously *discarded* on a
Synoptic win is now persisted in full, and it participates in **temperature blends, current-temp
resolution, and daily-extreme reads** — none of which were part of the change's intent and none of
which were assessed. (For "latest" reads the Synoptic row is still newer and likely still wins, but
daily-extreme and `maxTempLast24h` reads now see a second NWS row they didn't before.)

Two clean shapes, in order of preference:

1. **Field-aware merge instead of row swap.** `LatestObservationMerge.preferNewest` answers a
   *temperature* question and swaps the *whole row* (the issues doc's "check what else rides on the
   row"). If it returned a merged value — temperature from the fresher source, sky condition from the
   NWS row — the carrier row and its table churn disappear entirely.
2. **Cloud-scope the carrier** if the merge is too invasive right now: store the carrier with
   `temperature`/`precipAmountMm`/`maxTempLast24h`/`minTempLast24h` nulled (or a dedicated marker) so
   it cannot leak into non-cloud consumers, and make the comment true.

At minimum: verify the temperature and daily-extreme reads are unaffected (or intended) and fix the
"for its cloud alone" comment so it matches the code.

### F3. Four hand-written field-by-field observation mappings remain the top silent-loss site (medium)

The `isMetar` field was dropped in exactly this kind of copy and caught only by a value-asserting
round-trip test. Today there are four parallel copies of the same ~18-field shape:

- `NwsObservationMapper.toReading` — `NwsApi.Observation` → `ObservationReading` (shared)
- `NwsObservationSource.toEntity` — `ObservationReading` → `ObservationEntity` (app)
- `ObservationEntity.toReading` / `DesktopObservationEntity.toReading` / `.toEntity` (app + shared)

Each is a place where the *next* column (`isMetar`-style) will be added to one and silently dropped
by the others. The cheap, durable guard is a **field-parity test**: reflect over
`ObservationReading` vs `ObservationEntity` and `ObservationReading` vs `DesktopObservationEntity`
and fail when their property name/type sets diverge. That converts the entire class of bug from
"wait for a wrong value on a phone" to a red unit test on the next schema touch.

### F4. Live path stores ~1 row/station/fetch, so the hourly cloud value rides one flicker (medium — issues follow-up 2)

The METAR-preference (Defect 4) now correctly picks the METAR *when it is in the bucket*, but the
live "latest" path persists a single row per station per fetch, while the dense 5-minute series only
arrives via the 72h backfill. At a station publishing 5-minute rows, the one row that happens to be
"latest" is still a single instantaneous sample. The issues doc already flagged the two candidate
fixes (fetch a small recent window on the live path, or aggregate within a bucket — median/modal) and
deferred both. Worth deciding deliberately rather than leaving it, because the `metarPreferred=`
counter will read near zero at exactly the stations where the curve is still riding flicker, and that
signal is easy to misread as "healthy".

### F5. Minor polish (low)

- `CloudWatermarkPlacement` lives inside `CloudCoverGraphPalette.kt` — a file named "palette"
  containing two unrelated objects. The repo's graph folder is one-concept-per-file; split it.
- `MetarCloudBlender` now mixes a pure `blend` with a `suspend` I/O orchestration (`fromSiteRows`).
  The split is clean and the KDoc is excellent, but the name undersells the object: it owns read-range
  padding, source-branch selection, and blending. Consider `CloudActualReader` (or at least a header
  line) so the next person doesn't assume it is pure.

---

## What is already clean (do not re-open)

- Read-pad-in-shared / emit-unpadded, `CloudHourBucket` as the single rounding source of truth.
- METAR preference as a *class* (not a row) selection, per-station, with the carrier filter running
  first so a partial METAR never blanks an hour.
- "Not reported" is never spelled `0` (`MetarSkyCover` returns null; the empty `cloudLayers` is the
  honest failure mode).
- `CloudSeriesBuilder` is now genuinely shared — Android routes through it and no longer re-implements
  the pairing; `cloudCoverLow` survives the loaders and the stitcher.
- Source gating, palette, watermark search, observation mapper, and prior-run column choice are all
  single-source in `:shared`.

## Recommended order of work

1. **F2** — confirm the carrier's temperature/extremes exposure is intended or scope it; this is the
   only one with unassessed *data* consequences.
2. **F1** — unify the parsers behind one parse + an explicit projection; add the KNUQ round-trip test.
3. **F3** — the field-parity test (cheap, prevents the next `isMetar`).
4. **F4** — decide the live-path density question; at minimum record the decision.
5. **F5** — opportunistic.

---

## Implementation (260821) — F1–F3, F5 done; F4 deferred

### F1 corrected: the "second parser" was dead code, so it was deleted, not unified

While implementing, I found that `NwsApi.getLatestObservation` (the `/observations/latest` endpoint)
has **no production caller** — `grep` shows only `NwsApiTest` references it. Both platforms' live
path uses `getLatestObservationDetailedResult` (`/observations?limit=N` → `selectValidObservation` →
`parseObservationProperties`), which *already* parsed `cloudLayers` and `isMetar` since the METAR
commits. So the "two parsers drifted" duplication was really "one live parser + one dead partial
copy", and the three-silent-losses commit's Defect 2 fix (adding `cloudLayers` to the dead method)
targeted a path nothing in production exercises. The load-bearing production fix for KNUQ's missing
cloud was Defect 3's `cloudCarrier`, not the parse change.

Action: deleted `getLatestObservation` and its `NwsApiTest` case (the KNUQ `/observations/latest`
fixture's assertion — cloudLayers → 100% low — is already covered by `NwsApiCloudLayersParseTest` on
the real parser). One parser remains. This supersedes the issues doc's "Follow-up 1 (unify)".

### F2 — documented, not refactored

The `cloudCarrier` is a full observation row **deliberately**: the cloud value must keep the API
row's own timestamp so it buckets to the API observation hour (a field-aware merge into the web row
would re-timestamp it 20–60 min later, into the wrong hour). And the fields my review flagged as
"unassessed leakage" — temperature, 24h extremes, precipitation — are the API's own values that the
web swap was discarding *intermittently*; Synoptic carries none of them (`SynopticApi` sets all three
null/empty). The temperature never wins a "latest" read (the web row is strictly newer), and the
`(stationId, timestamp, locationLat, locationLon)` primary key means a historical backfill REPLACEs
rather than duplicates. So no double-count, and the "leak" is a restoration. The KDoc on
`LatestStationObservation.cloudCarrier` and the inline comment in `fetchLatest` were rewritten to say
exactly this instead of "for its cloud alone".

### F3 — field-parity tests added

`shared/.../data/local/desktop/ObservationFieldParityTest.kt` (Reading ↔ DesktopObservationEntity)
and `app/.../data/local/ObservationFieldParityTest.kt` (Reading ↔ ObservationEntity) reflect over the
backing fields and assert the name+type sets match, with a non-empty guard so the reflection cannot
pass vacuously. Next time a column is added to one side, this is a red test instead of a device bug.

### F4 — deferred (unchanged)

The live-path row-density question (issues follow-up 2) is still open; it needs a product decision
(small recent window on the live path vs in-bucket aggregation), not a mechanical edit.

### F5 — split `CloudWatermarkPlacement` into its own file

Moved out of `CloudCoverGraphPalette.kt` into `shared/.../graph/CloudWatermarkPlacement.kt` (same
package, no import changes).

### Verification

- `./gradlew :shared:testShortShared` — green (includes the new shared parity test).
- `./gradlew :desktop:compileKotlin` — green.
- `./gradlew :app:testShortDebugUnitTest --tests ObservationFieldParityTest --tests NwsApiTest` —
  green; `NwsApiTest` still passes with the dead-method test removed.


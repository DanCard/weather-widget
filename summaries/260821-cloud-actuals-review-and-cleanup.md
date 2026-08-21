# Cloud actuals: post-arc review findings and cleanup

**Date:** 2026-08-21
**Plan:** `plans/260821-review-cloud-actuals-post-three-fixes.md`
**Scope:** the dozen-commit "actual cloud cover % on the hourly cloud graph" arc, reviewed after
the "three silent losses" fix (`5400e3f3`) and the METAR-preference fix (`c3347274`).

## What this was

A structural review of the cloud-cover-actuals feature arc, following the earlier review
(`plans/260821-review-cloud-actuals-feature-arc.md`) and its implementation
(`plans/260821-implement-cloud-review-findings.md`). The prior findings (A1–A4, B1–B5, C1–C4) were
already implemented; this pass found four remaining structural items and acted on three of them.

## Key discovery: the "second parser" was dead code

The three-silent-losses commit fixed Defect 2 by adding `cloudLayers` parsing to
`NwsApi.getLatestObservation` (the `/observations/latest` endpoint), on the theory that the live
path used it. It did not: a full `grep` shows **only a test** called `getLatestObservation`. Both
platforms' live path uses `getLatestObservationDetailedResult` (`/observations?limit=N` →
`selectValidObservation` → `parseObservationProperties`), which already parsed `cloudLayers` and
`isMetar`. So the "two parsers drifted" duplication was actually "one live parser + one dead partial
copy", and the parse fix targeted a path nothing in production exercises. The load-bearing fix for
KNUQ's missing cloud was Defect 3's `cloudCarrier`, not the parse change.

## Changes

### F1 — delete the dead parser (was "unify")

Removed `NwsApi.getLatestObservation` and its `NwsApiTest` case. The KNUQ `/observations/latest`
fixture's assertion (cloudLayers → 100% low) is already covered by `NwsApiCloudLayersParseTest` on
the real parser. One parser remains. Supersedes the issues doc's "Follow-up 1 (unify)".

### F2 — `cloudCarrier` documented, not refactored

The `cloudCarrier` stores the **full** NWS API row, deliberately, not a cloud-only stub:

- the cloud value must keep the API row's own timestamp so it buckets to the API observation hour
  (a field-aware merge into the web row would re-timestamp it 20–60 min later, into the wrong hour);
- its temperature / 24h extremes / precipitation are the API's own values that the web swap was
  discarding intermittently — `SynopticApi` populates all three as null/empty;
- the temperature never wins a "latest" read (the web row is strictly newer), and the
  `(stationId, timestamp, locationLat, locationLon)` primary key makes a historical backfill REPLACE
  rather than duplicate, so nothing is double-counted.

The KDoc on `LatestStationObservation.cloudCarrier` and the inline comment in `fetchLatest` were
rewritten to say this instead of the misleading "for its cloud alone".

### F3 — field-parity tests

`ObservationReading <-> ObservationEntity` (app) and `ObservationReading <->
DesktopObservationEntity` (shared) are hand-written field-by-field copies, and a new column dropped
by omission is exactly how `isMetar` slipped once. Two reflection tests now pin the field name+type
sets (boxed-vs-primitive encodes nullability), with a non-empty guard so reflection can't pass
vacuously. Next drift is a red test, not a device bug.

- `app/src/test/java/com/weatherwidget/data/local/ObservationFieldParityTest.kt`
- `shared/src/test/kotlin/com/weatherwidget/data/local/desktop/ObservationFieldParityTest.kt`

### F5 — `CloudWatermarkPlacement` split into its own file

Moved out of `CloudCoverGraphPalette.kt` into `shared/.../graph/CloudWatermarkPlacement.kt` (same
package, no import changes).

### F4 — deferred

The live-path row-density question (issues follow-up 2) is a product decision — small recent window
on the live path vs in-bucket aggregation — not a mechanical edit. Left open.

## Verification

- `./gradlew :shared:testShortShared` — green (includes the new shared parity test).
- `./gradlew :desktop:compileKotlin` — green.
- `./gradlew :app:testShortDebugUnitTest --tests ObservationFieldParityTest --tests NwsApiTest` —
  green; both parity tests assert non-vacuously, and `NwsApiTest` passes with the dead-method test
  removed.

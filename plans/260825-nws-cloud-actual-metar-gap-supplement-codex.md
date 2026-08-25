# NWS cloud actual continuity from supplemental METAR rows

Status: implemented and verified, 2026-08-25

## Runtime evidence

1. The affected device is the Samsung SM-F936U1 and the active widget uses NWS in the wide cloud
   view.
2. Its visible NWS actual series contained 79 candidate timestamps. With the shared 30-minute
   bridge from `72abb17f`, those formed segments of 39 points, 1 point, and 39 points because the
   NWS rows had 80-minute and 40-minute holes.
3. The same site and window already contained first-class `api='METAR'` reports inside both holes.
   Using the union of compatible NWS and METAR station reports produced 95 timestamps with a
   maximum 20-minute step.
4. `MetarCloudBlender.fromSiteRows` resolves NWS to `providerApi='NWS'`, and `blend` consequently
   rejects every independently stored `api='METAR'` row. The renderer then correctly splits the
   genuinely missing NWS-only intervals; this is not a Samsung Canvas defect.

## Approved fix

1. When the resolved actuals provider is NWS, allow same-site METAR rows to supplement its station
   cloud blend. METAR is another transport for the same measured airport reports, not model data.
2. Deduplicate same-station/same-timestamp rows before blending. Prefer a usable cloud-carrying row,
   then the primary NWS copy, with an explicit total order so query order cannot affect the curve.
3. Keep every other provider branch and synthetic-row gate unchanged. In particular, an NWS row
   must not enter a source whose resolved actuals provider is METAR, and forecast/model rows remain
   excluded.
4. Add shared regression coverage for the live gap shape and deterministic duplicate selection,
   plus Android Room and desktop SQLite round trips proving both platform paths use the shared rule.
5. Run focused tests, affected suites/builds, then install and verify the Samsung graph from live
   logs, database timestamps, and a screenshot.

## Schema and migration impact

None. Both transports already coexist in `observations` because `api` is part of the primary key;
the change is confined to source-aware read/blend behavior.

## Implementation outcome

1. `MetarCloudBlender.fromSiteRows` now supplies `api='METAR'` as an explicit supplemental
   transport only when the resolved actuals provider is NWS. Stored provenance remains unchanged.
2. Same-station/same-timestamp transport duplicates are collapsed before blending with a total,
   query-order-independent preference: usable cloud carrier first, then the primary provider.
3. Shared, Android Room, and desktop SQLite regressions reproduce and close the NWS-only gap while
   retaining unrelated-provider exclusion and genuine-gap splitting.
4. Focused tests passed. Full `:shared:test`, `:desktop:test`, and `:app:testDebugUnitTest` passed;
   `assembleDebug` and `:desktop:createDistributable` also passed.
5. The debug APK was installed on Samsung SM-F936U1. The affected NWS wide cloud widget logged
   `actual=97`, up from the pre-fix `actual=79`, and the screenshot confirmed the solid pink actual
   curve is continuous through the former 1:15-3:15 AM break. The user independently confirmed it
   looks good with the widget on NWS.

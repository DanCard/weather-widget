# Observation location fragmentation fix (3 root causes)

**Date:** 2026-07-21
**Plan:** `plans/260721-observation-location-fragmentation-fix.md`
**Status:** Implemented, tested, verified live on emulator.

## Symptom

Emulator "Current observations" activity listed nothing for NWS, despite 6,564 observation rows in
the DB with fresh NWS readings. The device never moves, yet `observations` held one physical location
under **four** coordinate keys. `WeatherObservationsActivity.loadObservations` scopes to the device
location and collapses the box result with `LocationMatch.selectNearestSite`, which correctly dropped
the HQ cluster where the fresh NWS rows lived → NWS filtered to empty.

## Root causes (all position-independent — a stationary emulator reproduces them)

- **A. Inconsistent write precision.** `observations` writers persisted the coordinate at different
  precisions — raw double (`37.416797…`) vs 3-dp quantized (`37.417`). Same spot, two keys;
  `selectNearestSite` splits at `SAME_SITE_TOLERANCE_DEG = 0.002°`.
- **B. `DEFAULT_LAT/LON` fallback.** The `dataList.firstOrNull()?.locationLat ?: DEFAULT_LAT` idiom
  wrote NWS obs at Googleplex whenever the box query was empty (`reason=no_nws_observations`),
  ~0.7 km from the real fix. Self-perpetuating: the empty box was *caused by* the fragmentation.
- **C. Guard defeated by quantization.** The prior fix's sentinel
  `lat == DEFAULT_LAT && lon == DEFAULT_LON` was exact-equality against `-122.0841`, but the
  coordinate arrived 3-dp quantized to `-122.084`, so `==` silently missed it and the fetch ran at HQ.

## Fix

**C + B (unified at the enqueuer choke point)** — new pure `resolveBackfillLocation(getWidgetLocation)`
in `HourlyObservationBackfill.kt`:
- `null` widget location → skip `unanchored_no_widget_location`
- `LocationMatch.sameSite`-to-default → skip `unanchored_default_location` (quantization-safe,
  replaces `==`)
- else fetch under the **quantized authoritative widget location** — the possibly-defaulted `lat`/`lon`
  params were removed from the signature; both callers (`TemperatureStateResolver`, `DailyViewHandler`)
  updated.

**A** — `ObservationEntity.withQuantizedLocation()` applied at every observation write boundary:
`buildObservationEntity`, the `NWS_BLEND` synthetic row, `CurrentTempRepository.insertCurrentObservation`,
and `ForecastRepository` historical. All sources now key one physical site identically.

## Files changed

- `app/.../widget/handlers/HourlyObservationBackfill.kt` — `BackfillLocation` sealed type +
  `resolveBackfillLocation`; guard/fetch rewritten; `lat`/`lon` params removed.
- `app/.../widget/handlers/TemperatureStateResolver.kt`, `DailyViewHandler.kt` — drop `lat`/`lon` args.
- `app/.../data/local/ObservationEntity.kt` — `withQuantizedLocation()` extension.
- `app/.../data/repository/ObservationRepository.kt` — quantize in `buildObservationEntity` + blend.
- `app/.../data/repository/CurrentTempRepository.kt` — quantize in `insertCurrentObservation`.
- `app/.../data/repository/ForecastRepository.kt` — quantize historical obs.
- `app/src/test/.../HourlyObservationBackfillLocationTest.kt` — 7 pure tests (green).

## Verification (emulator, live)

| Before | After |
|--------|-------|
| NWS obs at `37.422` (HQ), dropped by `selectNearestSite` → list empty | All 5 NWS stations at `37.417` (device key) → list populates |
| Backfill logged `lat=37.422 lon=-122.084` | HQ cluster frozen since pre-fix `09:55`; all post-fix writes → `37.417` |
| 4 coordinate clusters | All 3 sources converge on one key |

No migration shipped — heals on next fetch (as planned); old `37.422` rows age out under 1-month
retention and are dropped by `selectNearestSite` on read meanwhile.

## Notes / follow-ups

- **Build gotcha:** the first `installDebug` reused a cached incremental compile, so the running app
  had the earlier fetch-first change but NOT Fix A (DB showed raw `37.416797`). `--rerun-tasks` proved
  a stale build, not a code bug. Lesson: when an on-device result contradicts a passing unit test,
  suspect the build first.
- **Residual edge:** a user genuinely within ~200 m of Googleplex is treated as unanchored (using a
  real place as the sentinel). A "location explicitly set" boolean pref would remove it — deferred.
- **Related:** memory `hourly_backfill_default_location_fragment` (updated with this recurrence +
  hardening). This is a separate branch from the fetch-first change
  (`plans/260721-fetch-both-nws-web-prefer-newest.md`); both are uncommitted in the working tree.
- **Open:** pre-existing compiler warnings (unrelated files: `!!`-on-non-null, always-true conditions)
  — not addressed here; harmless but a candidate cleanup pass.

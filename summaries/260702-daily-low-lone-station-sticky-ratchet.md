# Daily view yesterday's low 49° vs hourly 54.5°: lone-station blend leak + sticky ratchet (2026-07-02)

## Symptom

Samsung fold daily view showed Wed Jul 1's actual low as 49° — pink actual bar plunging well below
every neighboring day (56-57°) — while the Pixel showed 54.3° and the Samsung's own hourly view
showed 54.5°. All on NWS. Requirement: hourly and daily must agree.

## Diagnosis (device DB + app_logs)

Three stacked bugs:

1. **Lone-station blend leak (shared, both platforms).** The stored `daily_extremes` low
   (48.9919967651367) was byte-identical to station LOAC1's 05:10 AM reading — "LOS ALTOS",
   PERSONAL-class but `api=NWS` (so the PWS weight discount didn't apply), 8.3 km out in the hills.
   In `ActualTemperatureSeriesBuilder.blendObservationSeries`, candidate timestamps are the union of
   all stations' observation times, and a station contributes only if it has coverage within a 3h
   interpolation/extrapolation reach. At the 05:47 recompute LOAC1 had just 3 pre-dawn readings that
   no other station's reach covered — a single-candidate IDW blend collapses to that station's raw
   value, and `ActualsAggregator` takes `series.minOf` as the daily low. Officials (KNUQ/KPAO/KSJC)
   never went below 55.4°; nearest station AW020 (2.2 km) min 54.0°.
   Log: `DAILY_EXTREME_BLEND … computed_lo=48.991997 … LOAC1(d=8.32km,lo=48.991997,n=3)`.

2. **Sticky min-ratchet (Android-only).** `ObservationRepository.recomputeDailyExtremesForDay` had a
   today-only `high=maxOf(old,new)`, `low=minOf(old,new)` merge ("protect against transient drops").
   Every later correct recompute (54.52 from all 5 stations at 21:50) was discarded:
   `DAILY_EXTREME_UP low=48.991997->48.991997`. Since recomputes re-derive from the full day's
   *stored* observations, a real transient dip persists in the obs table and reappears in every
   re-blend — the ratchet could only preserve values that stopped being reproducible from data,
   i.e. exactly the erroneous ones. Desktop never had the ratchet (plain INSERT OR REPLACE).

3. **Location-fragment miss.** The after-midnight past-day recompute *did* heal the value (54.52)
   but wrote it under the Google-HQ default coords (37.422, -122.0841); the widget displays the
   fragment nearest its own GPS coords (37.417, -122.089), which stayed at 48.99. Recompute location
   and display location can disagree for hours (morning fetches resolved default coords).

Why hourly was right: the hourly pink line re-blends observations at render time over
now-complete overnight data (`HOURLY_DAY_EXTREMA lo=54.52@06:10 n=324`); the daily view reads the
poisoned `daily_extremes` row verbatim for past days.

## Fix

- **Dominant-station guard in the shared blender**
  (`shared/.../actuals/ActualTemperatureSeriesBuilder.kt`): a candidate timestamp covered by exactly
  ONE station is emitted only if that station is the source's **dominant** station for that local
  calendar day (most readings; ties → smaller min distance, then stationId). A single-station day is
  trivially dominant, so one-station sources (OPEN_METEO_MAIN, SILURIAN_MAIN) and legitimate solo
  coverage are unaffected. Placed at emission (not in extrema selection) so the daily bar, hourly
  line, HOURLY_DAY_EXTREMA, current-temp resolver, and yesterday-delta all obey one rule — hourly
  and daily agree by construction. Per-local-day dominance is window-independent, preserving the
  existing daily/hourly extrema-parity invariant. Skips logged via `onBlendDebug` and counted as
  `loneSkipped=` in `BlendObservationStats`. `zoneId` threaded through `ActualsAggregator` and
  `YesterdayDeltaCalculator`.

- **Ratchet removed** (`app/.../data/repository/ObservationRepository.kt`): unconditional
  overwrite-if-changed for today and past days alike, matching desktop. **Log tag
  `DAILY_EXTREME_UP` is gone** — only `DAILY_EXTREME_OVERWRITE` / `DAILY_EXTREME_STABLE` remain
  (grep-target note for future investigations).

- **All-fragment heal** (same function): `existingExtremes` had been `associateBy { it.source }`
  (collapses fragments) and wrote only at the recompute coords. Now `groupBy` + overwrite EVERY
  location fragment of that (date, source) in the ±0.1° `LocationMatch` box, keyed to each
  fragment's own coords. Valid because every coordinate in the box reads the identical observation
  rows, so the blended extremes are the same for all fragments. `DAILY_EXTREME_OVERWRITE` now logs
  `at=<lat>,<lon>` per fragment; STABLE logs `fragments=N`.

## Verification

- New `shared/src/test/.../ActualsLoneStationGuardTest.kt` (5 plain-JUnit tests): LOAC1-scenario
  repro (lone sparse station can't set the daily low; 3 skips counted), hourly-build/daily-aggregate
  parity with suppression active (the user requirement encoded as a test), re-emission with IDW
  dilution once neighbors gain coverage, dominant-station solo coverage preserved (guards against a
  bare "require ≥2 candidates" rule), single-station source unaffected.
- Full `:shared`, `:app:testDebugUnitTest`, `:desktop:test` suites pass.
- On-device: installed, `ACTION_REFRESH`; Samsung app_logs show
  `DAILY_EXTREME_OVERWRITE date=2026-07-01 src=NWS at=<each GPS fragment> low=48.991997->54.52334`;
  all four `daily_extremes` fragments now 54.5233; screenshot shows Wed low 54.5° matching the
  hourly view. User confirmed on device.

## Deferred

Fragment-key unification itself (default vs GPS markers are intentionally distinct per
`LocationMatch`); the all-fragment heal makes stale fragments self-correct on every recompute, so
unification is no longer load-bearing.

# Fix: daily view shows yesterday's low as 49° (hourly correctly shows 54.5°)

## Context

On the Samsung fold, the daily forecast view shows Wed Jul 1's actual low as 49° while the
hourly view on the same device (and other devices) shows 54.5°. Requirement: **hourly and
daily must agree**.

Diagnosis (from device DB `backups/20260702_042128_sm-f936u1_RFCT71FR9NT` + app_logs + code trace):

1. **Blend leak (shared)** — `DAILY_EXTREME_BLEND` at 05:47 Jul 1 computed lo=48.991997 =
   station LOAC1's raw 05:10 reading (PERSONAL-class "LOS ALTOS", 8.3 km, only n=3 readings;
   officials KNUQ/KPAO/KSJC never below 55.4°, nearest AW020 min 54.0°). In
   `ActualTemperatureSeriesBuilder.blendObservationSeries`, candidate timestamps are the
   union of all stations' obs times; a station that is the **sole** contributor at an
   instant (others outside the 3h interpolation reach) gets 100% IDW weight. Daily low =
   `series.minOf` (`ActualsAggregator.blendDailyExtremesViaSeries`). No coverage guard.
2. **Sticky ratchet (Android-only)** — `ObservationRepository.recomputeDailyExtremesForDay`
   isToday branch (app/.../ObservationRepository.kt:673-686): `low=minOf(old,new)`,
   `high=maxOf(old,new)`. Later correct recomputes (54.52 at 21:50, log `DAILY_EXTREME_UP
   low=48.991997->48.991997`) could never raise the low. Desktop has no ratchet (plain
   INSERT OR REPLACE, DesktopWeatherDao.kt:220-260).
3. **Why hourly is right**: hourly re-blends observations at render time via the same shared
   builder over now-complete overnight data (`HOURLY_DAY_EXTREMA lo=54.52@06:10 n=324`);
   the daily view reads the poisoned `daily_extremes` row verbatim for past days
   (`getDailyActualsWithLiveToday`, ObservationRepository.kt:496-560).
4. **Location fragmentation (contributing, deferred)**: the after-midnight recompute healed
   the value (54.52) but under the Google-HQ default coords (37.422,-122.0841); display picks
   the nearest fragment = the widget's GPS row (37.417,-122.089, still 48.99).

## Fix (2 changes + 1 deferral)

### 1. Remove the Android today-only min/max ratchet
`app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt:665-704`
(`recomputeDailyExtremesForDay`): delete `isToday` and its branch; collapse `when` to
`existing == null -> insert` / `else -> overwrite-if-changed` (current past-day branch,
unchanged, keeps `DAILY_EXTREME_OVERWRITE`/`DAILY_EXTREME_STABLE` logs). Update comment.

Why overwrite is safe for today: every recompute re-derives from the full day's *stored*
observations (deterministic blend) — a real transient dip persists in the obs table and
reappears in every re-blend, so the ratchet only preserves values that are *no longer
reproducible from data*, i.e. exactly the erroneous ones. Today's persisted row isn't even
displayed (today is computed live, lines 552-559). Also restores desktop parity.
Note in commit: `DAILY_EXTREME_UP` log tag disappears (grep target in past investigations).

### 2. Lone-station guard in the shared blend
`shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt`
(`blendObservationSeries` ~206-321, `BlendObservationStats` ~29-40):

**Rule**: a candidate timestamp whose blend has exactly **one** contributing station is
emitted only if that station is the source's **dominant station for that local calendar
day** (most readings that day; ties → smaller min distanceKm, then stationId). A day with
one reporting station is trivially dominant → single-station sources (SILURIAN_MAIN,
OPEN_METEO_MAIN) unaffected.

Why here (not in extrema selection): the emitted series feeds *everything* — daily bar
(`ActualsAggregator`), hourly pink line + `HOURLY_DAY_EXTREMA` (`build()`), current-temp
resolver, `YesterdayDeltaCalculator`. Filtering only the daily path would leave the 49° dip
visible in the hourly line — recreating the divergence inverted. Emitted points
(`ObservationReading`) don't carry stationCount, so post-hoc filtering would need extra
plumbing anyway. Why "dominant" not "require ≥2": a bare ≥2 rule guts days where one good
station covers hours alone (two-station outage case, day edges). Per-local-day dominance is
window-independent, preserving the hard-won invariant that daily and hourly derive identical
extrema (comment at lines 246-250 + existing parity tests).

Implementation:
- Add `zoneId: ZoneId = ZoneId.systemDefault()` param (replaces debug-only local at line 243);
  thread from `ActualsAggregator.aggregate`/`blendDailyExtremesViaSeries`, `build()`,
  `resolveCurrentObservation`, `YesterdayDeltaCalculator.computeDelta` (has zoneId already).
- Precompute `dominantStationByDay: Map<LocalDate, String>` from `filtered` after `byStation`
  (line 239).
- In the candidate loop, destructure `(stationId, stationObs)` to track the sole contributor;
  before emission: if `candidates.size == 1 && byStation.size > 1 && sole != dominant(day)`
  → skip + count + lazy `onBlendDebug` line ("skip t=… lone_station=… non-dominant …").
- Add `loneStationSkippedCount` to `BlendObservationStats` + `summary()` (defaulted field;
  existing consumers unaffected).

Both platforms inherit automatically (Android `ObservationResolver.computeDailyExtremes`,
desktop `DesktopWeatherRepository.recomputeDailyExtremes`, hourly `build()`).

### 3. Defer location-fragment unification
`LocationMatch.kt` intentionally keeps default-vs-GPS markers (≥0.005°) distinct. With the
ratchet gone, each fetch cycle recomputes and displays under the *same* resolved coords, and
both fragments read the same obs (±0.1° box) → same value either way. Residual stale-fragment
exposure is pre-existing, transient, and only became visible because the ratchet froze a bad
value. Optional follow-up idea (not in this change): past-day recompute writes under all
fragment keys in the 0.1° box.

## Tests (plain JUnit, :shared — no mocking framework)

New `shared/src/test/kotlin/com/weatherwidget/shared/actuals/ActualsLoneStationGuardTest.kt`
(helpers copied from `ActualTemperatureSeriesBuilderTest.kt`, zone America/Los_Angeles).
LOAC1 fixture: SPARSE d=8.3km, 3 readings 04:47-05:47 @ ~49°F; NEAR d=2.2km + OFFICIAL_1
d=4km hourly 09:00-20:00, min 54 (no pre-9am obs → sole coverage pre-dawn):

1. lone sparse station cannot set the daily low when other stations report that day
   (aggregate lowTemp ≥ 54; `loneStationSkippedCount > 0`)
2. hourly build and daily aggregate agree on the low when a lone station is suppressed
   (the user requirement encoded as a parity test)
3. sparse station blends instead of standing alone once neighbors gain coverage
   (add NEAR 04:00+06:30 readings → 05:47 candidate re-emitted, IDW-diluted ≈54)
4. dominant station keeps solo coverage when the other station is mostly offline
   (no-gap regression vs the rejected bare-≥2 rule)
5. single station source still yields daily extremes

Android side: after ratchet removal the branch is trivial ("insert if changed") — note the
future seam (pure `dailyExtremeChanged()` next to `precipChanged`) in the commit; verify via
`DAILY_EXTREME_OVERWRITE` logs + on-device. Run `./gradlew :shared:test :app:testDebugUnitTest`
(+ compile :desktop tests for API source-compat) — existing parity tests
(`single-day build reproduces daily aggregate high and low exactly`, `hourly per-day extrema
match the daily aggregate across a multi-day window`, personal-station-weight tests) are the
regression net.

## Sequencing

1. Shared guard + stats + zoneId threading → `:shared:test`
2. New guard tests
3. Android ratchet removal → `:app:testDebugUnitTest`
4. Desktop compile/tests (no code change expected)
5. On-device verification

## On-device verification (Samsung RFCT71FR9NT, no `pm clear`)

1. `./gradlew installDebug` (adb install -r semantics; DB preserved).
2. Trigger a fetch cycle (widget refresh or periodic worker) — recompute covers
   today-2..yesterday (WeatherWidgetWorker.kt:~363).
3. app_logs: expect `DAILY_EXTREME_OVERWRITE date=2026-07-01 src=NWS … low=48.991997->54.5x`
   and `DAILY_EXTREME_BLEND … computed_lo≈54.5`.
4. Cross-check `HOURLY_DAY_EXTREMA` lo for Jul 1 == daily computed_lo.
5. Re-query `daily_extremes` at GPS coords (37.417,-122.089) → low ≈54.5; screenshot daily
   view (via device-file screencap + convert to JPG) — Wed low should read ~54/55°, matching
   hourly.
6. Verify before Jul 10 (obs pruned after 9 days; recompute skips pruned dates); if the fetch
   cycle resolved default coords, re-trigger with GPS available.

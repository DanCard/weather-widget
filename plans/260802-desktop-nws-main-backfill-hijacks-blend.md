# Desktop current temp runs hot: `NWS_MAIN` backfill hijacks the observation blend

Status: **fixed and verified 2026-08-02**

## Problem

Desktop showed ~80.5 °F at 18:08 on 2026-08-02 while every nearby station read lower
(AW020 2.2 km = 79.0, KNUQ 3.8 km = 77.0, KPAO 6.1 km = 73.4). At 18:09:09 a refresh
landed and the display snapped **80.45 → 75.84** in one step.

Android (Pixel + emulator, same location) was unaffected.

### Root cause chain

1. **15:26:56** — desktop logged
   `SOURCE_FALLBACK: NWS unavailable, substituting Open-Meteo: Channel was closed`.
   `DesktopWeatherService.fetchForecast` routes an NWS failure to
   `fetchOpenMeteoForecastWithActuals()`, which calls `withHistoricalActuals(result, weatherSource)`
   — deliberately labeling the backfill with the **display** source (`NWS`), so the actual line
   still renders on fallback (`DesktopWeatherService.kt:152-168`).

2. That wrote Open-Meteo hourly temperatures into `observations` as
   `stationId='NWS_MAIN'`, `api='NWS'`, **`distanceKm = 0`**
   (`HistoricalActualsBackfill.build`, `distanceKm = 0f`), covering hours through 15:00.

3. `ActualTemperatureSeriesBuilder.blendCandidateTemperature` has a near-zero-distance override:

   ```kotlin
   val veryClose = eligible.filter { it.distanceKm <= NEAR_ZERO_KM && timeDecayFactor(it.ageMs) > 0f }
   if (veryClose.isNotEmpty()) { ... return pick.temperature }   // wins OUTRIGHT
   ```

   `NEAR_ZERO_KM = 0.1`, and the synthetic row is `stationType = "OFFICIAL"`, so `NWS_MAIN`
   wins outright and **all five real stations are discarded**. The returned value is *not*
   attenuated by staleness — decay only gates eligibility.

4. `resolveStationValueAt` → `extrapolateForward` carried the newest `NWS_MAIN` hour
   (15:00 = 83.2 °F, itself an Open-Meteo *forecast* value) forward by the forecast delta.
   At 17:50 that yields **81.37** — exactly the `obs=81.37` in the `CURR_TEMP_RESULT` log.

5. `CurrentTemperatureResolver` anchored the display to that fake observation
   (`delta = 81.37 - 85.17 = -3.80`), so for ~3 hours the desktop's "current temperature"
   was **the forecast curve offset by a stale forecast-derived delta** — with zero real
   station input. Today's forecast ran ~8 °F hot, so the display ran hot.

6. At age `BLEND_MAX_AGE_MS` (3 h) exactly, `timeDecayFactor` hit 0, `NWS_MAIN` dropped out,
   the real IDW blend took over → `obs=76.61`, `delta=-8.39`, display 75.84. Hence the snap.

### Why Android is immune

Android has **zero** `NWS_MAIN` rows ever (only `OPEN_METEO_MAIN`, `SILURIAN_MAIN`,
`TOMORROW_IO_MAIN`, `WEATHER_API_MAIN`). Its NWS path supplies real station readings and
never mints an NWS-labeled backfill.

### The rule already exists — in the wrong place

`ObservationSourceMatcher` (used by the **stations-list UI** on both platforms) already states
the correct rule and implements it:

> Two kinds of rows are synthetic ... and must never masquerade as a station under NWS:
> the internal IDW blend (`NWS_BLEND`), and the historical-actuals backfill (`NWS_MAIN`).

But the **blend math** has its own weaker copy,
`ActualTemperatureSeriesBuilder.matchesObservationSource` (line 590), which excludes only
`NWS_BLEND` — not `NWS_MAIN`. Two predicates, same name, different rules.

## What changed

Rather than filtering the backfill row out of the blend entirely (the first idea), the fix
changes its **priority**. Excluding it outright would have destroyed the fallback it exists for —
an actual line during a total NWS station outage. Ranking it last preserves that and still
guarantees a real reading always wins.

1. **`ObservationSourceMatcher.isSyntheticBackfillStation(stationId, sourceId)`** (new) — the one
   place that decides whether a row is a `<SOURCE>_MAIN` backfill, so the blend math and the
   stations-list UI cannot drift apart again.

2. **`ActualTemperatureSeriesBuilder.blendCandidateTemperature`** — candidates now carry an
   `isSynthetic` flag, and the blend ranks real readings strictly above synthetic ones:

   ```kotlin
   val realCandidates = eligible.filter { !it.isSynthetic && timeDecayFactor(it.ageMs) > 0f }
   val ranked = if (realCandidates.isNotEmpty()) realCandidates else eligible
   ```

   Both the near-zero override and the IDW loop consume `ranked`. The `timeDecayFactor > 0`
   term matters: without it, real-but-fully-decayed candidates would shadow a usable backfill
   and the blend would return null instead of falling back.

3. **Diagnostic** — `BlendObservationStats.syntheticDeprioritizedCount` plus a single VERBOSE
   `ActualTempSeries` line naming the deprioritised station and how many candidate timestamps it
   lost. Emitted once per blend, not per timestamp, and only when a backfill actually competed
   with real stations — silent on the normal path. This was the missing breadcrumb that made the
   original diagnosis require a DB dig.

4. **Purged** the 672 `NWS_MAIN` rows (dating back to 2026-06-18 — six weeks of accumulated
   fallback events, not just today's). Verified first that they carried **zero unique coverage**:
   every one had a real NWS station reading within ±3 h, so removing them cannot leave a gap in
   the actual line. Backup taken before the delete. Other sources' `*_MAIN` rows left untouched
   (1959 Open-Meteo, 1289 Silurian, 123 WeatherAPI).

### Not bugs (checked, leaving alone)

- Display sitting ~2 °F below the stored `NWS_BLEND` row: the stored row is computed with
  `personalStationWeight = 1.0`, the display path with the user setting
  (`personalStationDiscount = 95` default on **both** platforms → weight 0.05), which
  discounts AW020. Intended, and identical on Android.
- `KSJC` repeating 87.8 across 5-minute buckets: forward-filled METAR, correctly handled.

## Verification

`ActualsSyntheticBackfillPriorityTest` (new, `shared`) — 4 cases built from the field data:

| Case | Asserts |
|---|---|
| `NWS_MAIN` + real stations, same timestamp | blend follows the stations (< 79.5), not the 83.2 backfill; `syntheticDeprioritizedCount == 1` |
| Stale `NWS_MAIN` (15:00/16:00) carried forward vs fresh stations at 18:00 | 18:00 blend follows the stations — the exact field shape |
| `OPEN_METEO_MAIN` alone under `OPEN_METEO` | still returns 83.2 — forecast-only sources unaffected |
| `NWS_MAIN` alone under `NWS` | still returns 83.2 — outage fallback preserved |

**Proved the test can fail**: reverting just the `ranked` substitution makes cases 1 and 2 fail
and leaves 3 and 4 green, i.e. the tests bite on exactly the changed behaviour.

- `:shared:test` — 616 tests, 0 failures.
- `:app:testDebugUnitTest` — 1761 tests, 0 failures.
- **Live cross-platform check after restart** — desktop 18:22 `display=74.92 obs=75.92 delta=-8.25`
  vs Pixel 18:14 `display=75.39 obs=76.60 delta=-8.40`. Agreement within ~0.5 °F, explained by the
  8-minute offset on a falling curve. Before the fix desktop read 80.45 against Android's ~75.4.
- Diagnostic correctly silent post-purge (nothing left to deprioritise).

## Follow-ups

- Consider whether the desktop's NWS→Open-Meteo fallback should label the backfill
  `OPEN_METEO` and let the actual line render from that source, rather than minting
  NWS-labeled synthetic rows at all.

# Code Review: Cloud-cover actuals feature arc (commits d1046583..106e8fd9 + working tree)

**Date:** 2026-08-21
**Scope:** ~9 commits from `d1046583` (plans) through `106e8fd9` (NWS METAR actuals) plus the
uncommitted working-tree changes, spanning `:shared`, `:app`, and `:desktop`.

## What the arc builds

1. Split forecast vs actual cloud curves on the hourly cloud graph (Open-Meteo).
2. Frozen "day-ago" forecast curve from Open-Meteo Previous Runs API (`cloud_cover_low_previous_day1`).
3. Low-layer (`cloudCoverLow`) as the quantity the graph draws, replacing total-column.
4. Cloud actuals filed into `observations` via `HistoricalActualsBackfill` with a settling gate
   (`CloudActualSettling`, ultimately 0-lag with a well-documented why).
5. NWS cloud actuals derived at read time from METAR sky condition (`MetarSkyCover` → `MetarCloudBlender`
   IDW blend), with a self-healing 72h repair probe (`metarCloudGapReason`).
6. Non-fatal `cloudLayers` parsing (working tree) + cooldown pre-check for the CLOUD-view probe
   (working tree) + desktop log slimming (working tree).

## Verdict

The `:shared` core is genuinely excellent: pure, deterministic (TOTAL-order sorting with a dedicated
test), evidence-driven (every magic number carries its measurement), and honestly nullable
("not reported" is never 0). The weakness is at the **platform seams**: several decisions that the
shared layer claims to own are in fact re-implemented, bypassed, or gated differently per platform.
The single most consequential example: *neither platform actually draws the low layer on the live
forecast curve*, which was the documented point of commit f9a05d26.

Working-tree changes verified: `:shared:testShortShared`,
`:app:testShortDebugUnitTest --tests HourlyObservationBackfillLocationTest`,
`:app:testDebugUnitTest --tests NwsCloudActualsRoundTripTest` — all pass. The three changes
correctly close items 1–3 of `plans/260821-review-nws-metar-cloud-actuals-commit.md`.

---

## A. Correctness / consistency findings

### A1. The live forecast curve draws the TOTAL column on both platforms — the low-layer intent of f9a05d26 is unfinished (high)

`CloudSeriesBuilder.visibleCloudCover()` (`cloudCoverLow ?: cloudCover`) documents the rule and its
reason ("the live curve must use it too, or the curve steps at 'now' whenever there is cirrus
overhead" — measured 2026-08-20: total 83–99% vs low 4–13%). But:

1. **Desktop** (`desktop/.../CloudCoverGraph.kt:105`):
   `val rawCloudValues = points.map { it.cloudCover?.toFloat() ?: 0f }` — total column, never low.
   The `CloudSeriesBuilder.build()` output is consumed only for `actualByTime` and `frozenByTime`;
   its `forecastCover` (which applies `visibleCloudCover()`) is discarded. The live curve, the
   non-frozen past fallback, and the smoothing/labels all run off `rawCloudValues`.
2. **Android** (`app/.../CloudCoverViewHandler.kt:722,737`): `if (forecast?.cloudCover != null)` and
   `cloudCover = frozen ?: forecast.cloudCover` — same story, plus hours with a null total but a
   present low are dropped entirely (desktop would draw them).

Net effect on both platforms: **future hours draw total; frozen-past and actual draw low.** Under
Open-Meteo with thin cirrus this produces a cliff exactly at "now" (e.g. 90% → 10%) — the precise
artifact f9a05d26's docs say must not happen. Under NWS the past forecast curve is unfrozen total
(grid sky cover) compared against a low-layer METAR actual — the curves diverge for physical
(layer-definition) reasons, not forecast-error reasons.

Fix shape: make both renderers take the live value from the series (`CloudPoint.forecastCover`) or
at minimum apply `visibleCloudCover()` where they read `cloudCover` today. For NWS the total is the
only forecast product available — that residual mismatch should be documented where the curves are
paired, since `ACTUAL_LABEL_MIN_DIVERGENCE` will happily label it.

### A2. Android's loader pipeline silently drops `cloudCoverLow` (high)

Three independent loss points:

1. `HourlyForecastEntity.kt:50` — `HourlyForecast.toEntity()` maps `cloudCover` but omits
   `cloudCoverLow`. Both `GraphDataLoader` (`:162`) and `HourlyForecastLoader` (`:86`) end in this
   conversion, so every stitched row the widget renders has low = null even when the DB has it.
2. `GraphDataLoader.kt:136-147` — the manual `HourlyForecastHistoryEntity` → `HourlyForecastEntity`
   mapping for history rows also omits `cloudCoverLow`.
3. `HourlyForecastStitcher.kt:65-69,127-131` — field coalescing covers `cloudCover`,
   `precipProbability`, `precipAmountMm` but not `cloudCoverLow`, so a live row missing low never
   inherits it from history (desktop compensates with its own raw-SQL coalesce at
   `DesktopWeatherDao.kt:832-838`; Android has no equivalent).

Today this is masked because `buildCloudHourDataList` only reads `cloudCover` anyway (A1), but it is
a landmine for exactly the fix A1 requires, and it makes `HourlyForecastEntity.cloudCoverLow` a
write-only field on the render path. Notably `GraphDataLoader` returns *raw* DAO entities (low
intact) when `hourlyHistoryDao == null || source == null`, and stitched entities (low dropped)
otherwise — the same widget gets different field fidelity depending on which branch ran.

### A3. Actual-curve source gating diverges between platforms (medium)

`CloudCoverViewHandler.cloudActualsSupported` gates the actual curve to `OPEN_METEO || NWS`.
But `HistoricalActualsBackfill` files cloud for every source whose
`historicalDataKind.preservesHistoricalPrecipitation` is true — that includes SILURIAN,
TOMORROW_IO, and WEATHER_API. Desktop reads `getCloudActuals(..., weatherSource)` unconditionally
(`DesktopWeatherRepository.kt:172`, wired into the composable at `Main.kt:1211-1212`). So for those
sources the synthetic cloud rows are written on both platforms, rendered on desktop only, and dead
weight on Android. Either the Android gate is wrong (should match the write side's provenance rule)
or desktop's is too generous — pick one rule and put it in `:shared` (e.g. a
`WeatherSource.filesSyntheticCloudActuals` derived from `historicalDataKind`).

### A4. Null-cloud hours: desktop draws 0%, Android omits + diagnoses (medium, pre-existing)

Desktop's `?: 0f` (CloudCoverGraph.kt:105, predates this arc — commit `889a9643`) draws a missing
cloud hour as clear sky. Android drops the hour and renders the "Cloud data missing for N of M hrs"
diagnostic. `CloudSeriesBuilderTest` even pins the intended semantics ("hours without cloud data
are omitted, never zeroed") — only the builder obeys it. Same data state, two different truths
depending on platform.

---

## B. Structural findings

### B1. `CloudSeriesBuilder` is not actually shared — its doc claims it is (high)

Its KDoc says "Shared by the Android widget and the desktop app so the two cannot disagree about
which value lands on which curve." Only desktop calls `build()`; Android re-implements the pairing
inline in `buildCloudHourDataList` (`CloudCoverViewHandler.kt:718-757`) with different filter
semantics (A1/A2). This is the drift the object was created to prevent, and it has already drifted.
Recommendation: have `buildCloudHourDataList` call `CloudSeriesBuilder.build` (it already receives
both maps) and decorate the returned `CloudPoint`s with label/icon presentation. That one change
fixes A1-Android, A2's symptom, and B1 together.

### B2. Cross-platform duplicates held together by "must match" comments (medium)

1. `ObservationDao.getCloudActuals` (app) vs `DesktopWeatherDao.getCloudActuals` (shared) —
   byte-identical two-branch algorithm (NWS blend vs synthetic-station pin), joined only by a
   "Mirrors Android's..." comment. The branch selection can move into `MetarCloudBlender` (e.g.
   `fromRows(rows, sourceId)`), leaving each DAO only its site-collapsed row read.
2. NWS observation mapping: `NwsObservationSource.toEntity` (app) vs
   `DesktopWeatherService.toReading` (desktop) — same unit conversion, blank-name fallback, and
   METAR-cloud rule ("same rule as ... §3"). A shared mapper in `:shared` next to `MetarSkyCover`
   would delete the cross-reference comments.
3. Colour constants: `CloudCoverGraphStyle` vs `CloudCoverGraph.kt:33-43` — four duplicated ARGB
   values with "Must match" comments (and the lightness-ratio doc already disagrees: 1.6 vs 2.0).
   Move to a shared `CloudCoverGraphPalette` in `:shared`.
4. `ACTUAL_LABEL_MIN_DIVERGENCE = 8` declared twice with matching comments.
5. Watermark placement: ~40-line candidate-window algorithm duplicated verbatim
   (`CloudCoverGraphRenderer.kt:403-465` vs `CloudCoverGraph.kt:263-303`) including the same
   constants (divisor 5, min 3, max 6, fractions .5/.65/.35). The shared-geometry culture of this
   repo (ValueLabelEngine, HourlyTimelineGeometry) is the pattern to follow.

### B3. Hour-bucket rule duplicated (low)

`Math.round(ts / 3_600_000.0)` appears in `MetarCloudBlender.blend`, in
`metarCloudGapReason` (HourlyObservationBackfill.kt:214), and in the blender's diagnostic dump. The
round-to-nearest-hour rule is load-bearing (the KPAO :47 case) and deserves one shared function
(e.g. `CloudHourBucket.of(tsMs)`) so a future change cannot fix one copy and not the others.

### B4. `HistoricalActualsBackfill.build` duplicate gate lookup (low)

`keepCloud` and `keepHistoricalPrecip` are two names for the same expression, with
`WeatherSource.fromId(sourceId)` evaluated twice. If cloud's provenance gate is intentionally "same
as precip", say it once (`val keepProviderHistory = ...`) — or, better, give
`HistoricalDataKind` an explicit `preservesHistoricalCloud` so the next quantity doesn't silently
inherit precip's semantics.

### B5. Dead smoothing knob (low)

`CloudCoverViewHandler.smoothingIterationsFor` (NARROW gets one fewer iteration) is called only by
its test; `updateWidget` passes `zoom.smoothIterations` directly (CloudCoverViewHandler.kt:570).
Either wire it in or delete it and its test — as-is it silently promises a behaviour the render
never performs.

---

## C. Architectural observations

### C1. Prior-day rows overload the `cloudCover` column (medium)

Both writers (`ForecastFetchCoordinator.kt:80`, `DesktopWeatherDao.upsertSyntheticCloudSeries`) store
the Previous-Runs **low-layer** percent in the `cloudCover` (total-column) field, with
`cloudCoverLow` left null, rationalized by "only cloudCover is read back". Everywhere else in the
schema `cloudCover` means total. Any future code that reads prior-run rows with the standard
`cloudCoverLow ?: cloudCover` preference gets the right answer only by accident, and anything that
trusts `cloudCover` as total is wrong. Since `cloudCover` is nullable everywhere and the rows already
carry marker values (`temperature = 0f`, `condition = "prior-run cloud"`), storing the value in
`cloudCoverLow` is strictly more truthful and costs one migration-free column swap (rows are
REPLACE-upserted and rebuild on next fetch).

### C2. Write-time vs read-time derivation asymmetry is justified — keep it documented (positive)

Open-Meteo actuals are filed write-time (synthetic row), NWS actuals are derived read-time (blend).
The asymmetry is well-reasoned (no station can extrapolate an instantaneous sky reading) and
thoroughly documented at each seam. No change requested.

### C3. Self-heal design is sound; its probe now correctly avoids hot-path cost (positive)

The working-tree cooldown pre-check (`hourlyBackfillCoolingDown`) closes the "72h read on every
paint" issue cleanly by reusing the same cooldown key, and `metarCloudGapReason`'s OFFICIAL-only,
QC-excluded basis with the `cloudBuckets * 2 < officialBuckets` threshold is well guarded against
unsatisfiable loops. No new cancel-by-name WorkManager paths (AGENTS.md trap respected).

### C4. Test coverage: strong core, untested seams (medium)

Shared coverage is exemplary (KPAO :47 bucketing, TOTAL-order determinism, carrier preference, QC
exclusion, settling windows, source isolation). But every finding in section A lives at a platform
seam — precisely the layer with no tests: no Android test exercises `cloudCoverLow` through
`buildCloudHourDataList`, no test pins the live-curve value source on either platform, and no test
compares the two platforms' source gating. When A1/A2 are fixed, add: (1) an Android test that a
low-only row draws, (2) a test that the live/future point value equals `visibleCloudCover()`, and
(3) a shared test pinning which sources produce synthetic cloud actuals.

---

## Recommended order of work

1. **A2 + B1 together** — route Android through `CloudSeriesBuilder` and stop dropping
   `cloudCoverLow` in the loaders (one change, three findings).
2. **A1** — draw `visibleCloudCover()` on both live curves; document the NWS total-vs-low residual.
3. **A3** — one shared rule for which sources have an actual cloud curve.
4. **B2.1 / B2.2** — lift the duplicated DAO branch and observation mapping into `:shared`.
5. **C1** — move prior-run values to the `cloudCoverLow` column at the next schema touch.
6. **A4, B2.3–B2.5, B3, B4, B5** — opportunistic cleanups.

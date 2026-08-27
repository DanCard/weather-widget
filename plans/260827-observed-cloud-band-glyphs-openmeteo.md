# Draw observed mid/high cloud bands as pink glyph curves — Open-Meteo only

**Date:** 2026-08-27
**Status:** Proposed — awaiting implementation approval
**Scope:** Open-Meteo (`CloudVerticalKind.PROVIDER_BANDS`) only. `CUMULATIVE_LAYERS` (NWS /
Synoptic / METAR) and `TOTAL_ENVELOPE` (Tomorrow.io) are deliberately out of scope; see
"Deferred" below.

Follows `260827-observed-cloud-provider-population-v3-chatgpt.md`, which populated the observed
columns, and `260826-cloud-layer-glyph-curves.md`, which shipped the forecast `m`/`h` glyphs.

## Goal

Give the mid and high cloud bands the same forecast-vs-actual treatment the low band already has:
a grey glyph curve for what was predicted and a pink glyph curve for what happened, on the same
axis, in the same graph. No new view, no per-source graph variant.

## Evidence collected

1. **Only Open-Meteo forecasts the bands.** Over the last 2 days of `hourly_forecasts`, band
   columns are populated for `OPEN_METEO` (387/420 rows) and for nobody else: NWS, Silurian,
   Tomorrow.io and OpenWeatherMap have 0 band rows each and forecast a single total number. An
   observed band glyph on those sources has nothing to be graded against.
2. **The observed bands are already being written.** `HistoricalActualsBackfill` files Open-Meteo
   sub-hourly analysis rows into `observations` with `cloudCoverMid`/`cloudCoverHigh` and
   `cloudVerticalKind = PROVIDER_BANDS`.
3. **The bands are already being drawn — in the wrong colour.** Both renderers read mid/high
   straight off the live hourly row (`CloudCoverViewHandler.kt:837`, `CloudCoverGraph.kt:170`).
   For a *past* hour that row has already been retro-corrected by later Open-Meteo runs, so today's
   grey glyphs on the left of the NOW line are observed values wearing the forecast's colour. This
   is precisely the confusion `PriorDayCloudForecast`'s header documents for the low band.
4. **BLOCKER — the Previous Runs band variables return all nulls.** Probed live 2026-08-27 at
   three locations (Mountain View, Berlin, New York), `past_days` 2 and 7:

   | variable | non-null |
   |---|---|
   | `cloud_cover_previous_day1` | 192/192 |
   | `cloud_cover_low_previous_day1` | **0/192** |
   | `cloud_cover_low_previous_day2` | **0/192** |
   | `cloud_cover_mid_previous_day1` | **0/72** |
   | `cloud_cover_high_previous_day1` | **0/72** |

   The API *accepts* all three band names and returns their keys, so these are valid variables that
   the server is not currently populating. Only the total column has data.
5. **That blocker is already a live production bug on the low band.** `OpenMeteoApi
   .PREVIOUS_RUNS_VARIABLE` is `cloud_cover_low_previous_day1`. The newest
   `OPEN_METEO_PRIOR24` row in `weather.db` was fetched **2026-08-20 20:26** — seven days ago, and
   765 rows on that one day only. The frozen forecast curve has been silently dead for a week.
   It fails invisibly *by design*: nulls are correctly omitted rather than zeroed, so
   `CloudSeriesBuilder` falls back to the live row with `isFrozen = false` and the graph still
   draws a continuous, plausible-looking curve. Nothing in `app_logs` mentions it.
6. **We already snapshot the bands ourselves.** `hourly_forecast_history` under
   `source = 'OPEN_METEO'` captures ~76 prediction buckets per day at every lead time, and has
   carried `cloudCoverMid`/`cloudCoverHigh` since 2026-08-26 (72 rows/day and rising). A snapshot
   taken ~24 h before a target hour is available for every hour the app was running.

## The decision this forces

The frozen band forecast cannot come from the Previous Runs API. It must come from **our own
`hourly_forecast_history` snapshots** (evidence 6): per target hour, the `OPEN_METEO` snapshot
whose `timestampToGroupPredictions` is nearest to `dateTime − PriorDayCloudForecast.LEAD_MS`.

Trade-off, stated plainly: Previous Runs backfills a complete series regardless of whether the app
was running; our snapshots have holes wherever the app was off ~24 h before the hour. That is
acceptable because the existing `isFrozen` machinery already exists to say so — a missing snapshot
falls back to the live value and is marked not-frozen, and the render declines to imply an accuracy
comparison it cannot make. It is also the only option: the API has no band data to give.

**Do not** fall back to `cloud_cover_previous_day1` (total). `OpenMeteoApi:38-45` documents why
comparing a total-column forecast against a visible-layer actual is wrong, with measurements.

## Proposed implementation

### 1. Shared — carry the bands through `CloudSeriesBuilder`

Mid/high currently bypass the builder entirely. Route them through it so they get the same
frozen-vs-observed resolution as the low band.

1. Extend `CloudPoint` with `forecastMid`, `forecastHigh`, `actualMid`, `actualHigh`, all nullable,
   plus `isFrozenBands: Boolean` — separate from `isFrozen`, because the low band's frozen snapshot
   and the bands' can be present independently (the low band has a week of Previous Runs history
   that the bands do not).
2. `CloudSeriesBuilder.build` gains `priorBands: Map<Long, BandPair>` and
   `retroBands: Map<Long, BandPair>`. Same `currentHourStart` rule: the frozen prediction only for
   elapsed hours, the actual ungated (an observation is a measurement, not a projection).
3. `MetarCloudBlender.fromSiteRows` returns the observed bands from the Open-Meteo branch. Gate on
   `row.cloudVerticalKind == CloudVerticalKind.PROVIDER_BANDS`, **not** on a source id — the kind is
   the data property that makes a band percentage meaningful, and gating on it keeps the rule
   truthful if another provider later reports bands. `CUMULATIVE_LAYERS` and `TOTAL_ENVELOPE` rows
   yield no bands here; that is what keeps this change Open-Meteo-only without hardcoding it.
4. New `PriorDayBandForecast` object beside `PriorDayCloudForecast`, holding the
   nearest-bucket-to-lead selection rule as a pure function over
   `(targetHourMs, availableBuckets) -> Long?`.

### 2. Read paths

1. **Android** — `CloudCoverViewHandler`: read the band snapshots via a new
   `HourlyForecastHistoryDao` query (`source = 'OPEN_METEO'`, site-collapsed, window-bounded,
   returning bucket + bands), and pass both new maps into `CloudSeriesBuilder.build`. Replace
   `midCover = entity.cloudCoverMid` at `:837` with the builder's resolved values.
2. **Desktop** — the same, via `DesktopWeatherDao` / `DesktopWeatherRepository`, replacing
   `points.map { it.cloudCoverMid }` at `CloudCoverGraph.kt:170`.
3. Both must go through the existing site-collapse (`LocationMatch` / `selectNearestSite`) before
   reaching the builder, exactly as `CloudSeriesBuilder`'s header requires.

### 3. Render — the fourth glyph series

`CloudLayerGlyphPlacer` currently separates two series by phase (`MID_PHASE = 0f`,
`HIGH_PHASE = 0.5f`). Four series on one plot will collide.

1. **Suppress the observed glyph where it agrees with the forecast.** Draw pink only where
   `abs(actual − forecast) >= CloudCoverGraphPalette.ACTUAL_LABEL_MIN_DIVERGENCE` (8 points). This
   follows the constant's existing rationale — below it "the curves overlap on screen and the extra
   number is pure clutter" — and it gives the pink glyph a meaning worth having: *the forecast was
   wrong here*. On a day the forecast got right, the graph looks exactly as it does today.
2. Where both must draw, add `MID_ACTUAL_PHASE = 0.25f` / `HIGH_ACTUAL_PHASE = 0.75f` so all four
   series interleave rather than overprint. The existing `nudgePx` coincidence handling stays.
3. Feed observed band values into the `layerValues` list that drives `verticalScale.topScale`
   (`CloudCoverGraphRenderer.kt:251`, `CloudCoverGraph.kt:172`). Omitting them lets a pink glyph
   render off-scale.
4. Add observed glyph boxes to `layerGlyphBounds` / `onLayerGlyphsPlaced`. The free-floating
   dominant-station label treats glyph trails as obstacles; a second trail it cannot see is a
   label collision waiting to happen.
5. Pink glyphs use `CloudCoverGraphPalette.CURVE_ACTUAL`, no new palette entry.
6. **Never draw a pink glyph for a not-frozen hour.** With no snapshot there is no prediction, so
   there is no divergence to report — the hour shows its grey glyph alone.

## Verification

| # | Kind | What it pins | Result |
|---|---|---|---|
| 1 | Unit (shared) | `CloudSeriesBuilderBandsTest` — bands split frozen-vs-actual; future hours never frozen; a missing snapshot falls back to live and says so; `isFrozen`/`isFrozenBands` move independently; ±30 min anchor tolerance | 7/7 pass |
| 2 | Unit (shared) | `PriorDayBandForecastTest` — most-recent-at-least-24h selection, the too-close and too-old rejections, exact-lead boundary, band-less snapshots dropped, per-hour independence | 8/8 pass |
| 3 | Unit (shared) | `CloudLayerGlyphDivergenceTest` — the divergence floor and its boundary, symmetry, the unfrozen gate, missing values, and that all four phases are distinct and land on different x | 8/8 pass |
| 4 | Unit (shared) | `ObservedCloudBandsReadTest` — the `CloudVerticalKind` gate: `PROVIDER_BANDS` supplies bands, `CUMULATIVE_LAYERS`/`TOTAL_ENVELOPE` supply none, band-less rows contribute nothing, the METAR branch yields no bands | 7/7 pass |
| 5 | **Integration** (shared, 4 classes) | `ObservedCloudBandPipelineIntegrationTest` — stored rows → `MetarCloudBlender` + `PriorDayBandForecast` → `CloudSeriesBuilder` → `CloudLayerGlyphPlacer.divergentActuals`. Covers a missed forecast, an accurate one, today's real state (observations but no snapshot), a future hour, the kind gate surviving the wiring, and mid/high resolving independently | 6/6 pass |
| 6 | Robolectric (app) | `CloudCoverGraphObservedBandRobolectricTest` — a divergent frozen actual adds glyph ink, an agreeing or unfrozen one adds none, observed glyphs reach the placement search, and a tall observed band stays inside the plot (the scale wiring) | 5/5 pass |
| 7 | Compose (desktop) | `CloudCoverGraphObservedBandTest` — the same four invariants against a real font engine | 4/4 pass |
| 8 | Mutation check | Breaking the `frozen` gate in `divergentActuals` fails #3 and #5; dropping the observed trail from `layerGlyphBounds` fails #6. Both restored | Both caught |
| 9 | Full suites | `:shared` 2556, `:desktop` 492, `:app` 3154 | 0 failures |
| 10 | On-device (desktop) | Built the distributable, restarted via the autostart launcher, schema migrated 22 → 23. Two healthy processes; graph renders unchanged, which is the correct no-data behaviour (see below) | Pass |

### What the on-device check can and cannot show yet

The band columns in `hourly_forecast_history` began filling on 2026-08-26; the earliest stored
prediction bucket carrying a band is **2026-08-26 21:00**. With a 24-hour lead requirement, the
first hour eligible for a frozen band forecast is **2026-08-27 21:00**. Until then every hour is
`isFrozenBands = false`, no pink glyph may be drawn, and the graph is expected to look exactly as it
did before — which is what it does.

The write path is confirmed working on the same restart. NWS/Synoptic rows carry
`cloudVerticalKind = 20` (`CUMULATIVE_LAYERS`) with 44 mid bands and 315 low bases from NWS and 5
mid from Synoptic; high remains 0 across every station, matching the 7-day METAR replay.

**The Open-Meteo observed side is fully confirmed**: the first post-restart fetch wrote 721 rows at
`cloudVerticalKind = 10` (`PROVIDER_BANDS`) with all three bands populated on every row, at the
provider's native 15-minute cadence (11:15, 11:30, 11:45, 12:00 — which is exactly why the ±30 min
anchor tolerance in `CloudSeriesBuilder` is tested).

Those rows also caught the blind spot this feature exists for, live. At 12:00 local the low band
read **1%** — the graph's main curve calls that a clear sky — while the mid band read **63%**, having
climbed 13 → 63 over the preceding two hours:

| hour | low | mid | high |
|---|---|---|---|
| 10:15 | 15 | 13 | 0 |
| 11:00 | 5 | 23 | 0 |
| 11:30 | 2 | 48 | 0 |
| 12:00 | 1 | 63 | 0 |

**Still outstanding:** the forecast half. `actualBands` now populate, but `isFrozenBands` stays
false until 21:00, so no pink glyph may be drawn yet and the visual confirmation must wait. The
Android emulator screenshot is also outstanding.

## Prerequisite — DONE

Evidence 5 is a live bug independent of this feature and did not ride in on it. `ForecastFetchCoordinator
.fetchPriorDayCloudForecast` had a bare `if (byHour.isEmpty()) return`; it now logs `PRIOR_CLOUD_EMPTY`
naming the withdrawn variable, so a silent server-side change is visible in `app_logs` instead of
degrading into an unmarked hindcast.

Whether the **low** band also switches to `hourly_forecast_history` snapshots is a larger question
and remains open. Until it is answered the low curve's frozen forecast stays broken — the logging
makes that visible, it does not fix it.

## Deferred

- **`CUMULATIVE_LAYERS` (NWS / Synoptic / METAR).** Observed bands exist and are informative — over
  7 days of stored raw METARs, 103 of 1,318 reports carry a mid band the low curve reports as clear
  sky — but there is no band forecast to grade them against, so the glyphs would mean something
  different than they do here. That difference needs its own UI answer.
- **Observed high on METAR sources is dead weight regardless**: 0 of 1,318 reports in 7 days
  reached the 8 km boundary; the highest base observed was 6,096 m.
- **`TOTAL_ENVELOPE` (Tomorrow.io).** A base/ceiling pair in metres has no representation on a
  0–100 % axis. If it is ever drawn it needs an altitude axis, which is a different graph.

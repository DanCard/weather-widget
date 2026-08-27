# Report total cloud cover, not the low layer

**Date:** 2026-08-27
**Status:** Proposed — implementing on request
**Reverses:** the 2026-08-20 decision to prefer `cloudCoverLow` over `cloudCover` everywhere cloud
is reported.

## Why reverse it

The original decision was measured and, on its own terms, correct: on 2026-08-20 the total column
ran 83-99% all afternoon on thin cirrus while the low layer and every surface station read 4-13%.
Drawing the total made the graph claim an overcast day that nobody outside could see.

What that reasoning missed is the opposite error, which is larger and more frequent. Measured
2026-08-27 over the 720 stored Open-Meteo hours carrying both values:

| | hours | share |
|---|---|---|
| total exceeds low by >= 30 points | 125 | 17.4% |
| low < 20 but total >= 70 — the curve paints a clear sky over a covered one | 93 | 12.9% |
| mean total − low | 15.5 points | — |
| max total − low | 100 points | — |

Live at 12:00 on 2026-08-27 the low band read **1%** while mid read **63%**, having climbed 13 → 63
over two hours. The graph called that a clear sky.

On station data the low preference costs almost nothing: over 1,247 replayed METAR reports carrying
a low band, `max(low, mid, high)` exceeds low on only 2.2%, and badly (low < 20 while true cover
>= 70) on 0.5%. So this is overwhelmingly a **forecast-side** problem.

## Scope (confirmed with the user)

1. **Everywhere cloud is reported** — forecast curve, actual curve, frozen day-ago series, the daily
   noon-cloud icon, the hourly observation backfill, and the genmon panel. Not the graph alone: a
   day icon disagreeing with the graph about the same sky is the same bug wearing a different hat.
2. **Station rows get a real total.** NWS/METAR/Synoptic store no `cloudCover` by design — their
   layers live in the band columns — so their total is `max(low, mid, high)`, which is exactly what
   `MetarSkyCover.totalPercent` already computes from the cumulative layers. Read-side only, no
   schema change. Without this the forecast curve would show total while the actual curve showed
   low, and the two would no longer be comparable — which is the accuracy claim the graph exists to
   make.

## Proposed implementation

### 1. One shared resolver

A single `VisibleCloudCover.of(total, low, mid, high)` in `:shared`, so twelve call sites cannot
drift the way they would if each flipped its own `?:` expression:

- the total where the row has one;
- otherwise the maximum of whichever bands are present (the cumulative-layer case);
- otherwise null. Null stays "not reported" — never zero, never a clear sky nobody observed.

Extension overloads for `HourlyForecast` and `ObservationReading` keep the call sites one-liners.

### 2. Flip the read sites

| File | Site |
|---|---|
| `CloudSeriesBuilder.kt` | `visibleCloudCover()` — the live/future forecast value |
| `MetarCloudBlender.kt` | `visibleCloud()`, plus the four `!= null` has-cloud predicates and the station-eligibility check |
| `HourlyForecastHistoryDao.kt` | `getSyntheticCloudSeries` — the frozen day-ago read |
| `DesktopWeatherDao.kt` | `getSyntheticCloudSeries` — same, desktop |
| `DailyNoonCloudCover.kt` | the noon sample backing the daily icon |
| `HourlyObservationBackfill.kt` | the has-cloud filter |
| `CloudCoverGraph.kt` | `rawCloudValues` — the desktop live curve |

### 3. Flip the write side

- `OpenMeteoApi.PREVIOUS_RUNS_VARIABLE`: `cloud_cover_low_previous_day1` →
  `cloud_cover_previous_day1`.
- `ForecastFetchCoordinator` (Android) and `DesktopWeatherDao.upsertSyntheticCloudSeries` (desktop)
  file the prior-run value on `cloudCover` instead of `cloudCoverLow`.
- Old `OPEN_METEO_PRIOR24` rows keep their value on `cloudCoverLow`; the new total-preferred reader
  still finds them through the fallback, so nothing is lost and nothing needs migrating.

**This also fixes the week-dead frozen forecast curve.** `cloud_cover_previous_day1` is the one
previous-runs variable Open-Meteo still populates — 192/192 non-null against 0/192 for every band
variant (probed at three locations). Reversing the decision restores the curve that commit
`26d04efc` could only make audible.

### 4. Leave alone, deliberately

- The `m`/`h` glyph trails keep drawing the **bands**. They are how a reader tells thin cirrus from
  a low deck once the main curve reads total, so they become more load-bearing, not less.
- `cloudCoverLow` keeps being fetched, parsed and stored everywhere it is today. This changes what
  is *reported*, not what is *recorded*.
- `MetarSkyCover.LOW_LAYER_CEILING_M` and the band boundaries are untouched.

## Verification

| # | Kind | What it pins | Result |
|---|---|---|---|
| 1 | Unit (shared) | `VisibleCloudCoverTest` — total beats every band; bands' max when no total; a single band; a zero total honoured over a present band; all-null stays null; clamping; the observation overload | 8/8 pass |
| 2 | Unit (shared) | `CloudSeriesBuilderTest` — the live curve draws the total; a bands-only row draws their max (both restated from the old low-preference test) | pass |
| 3 | Unit (shared) | `MetarCloudBlenderTest` — the synthetic-source branch reports the total and falls back to the bands; source pinning unchanged | pass |
| 4 | Unit (shared) | `DailyNoonCloudCoverTest` — the day icon reports the total; the freshest-row rule preserved with the discriminator moved to `cloudCover` | pass |
| 5 | **Integration** (app, 4 layers) | `OpenMeteoTotalCloudViewParityIntegrationTest` — hourly graph and daily bar report the same number for one sky, through persistence → handler → shared builder → real bitmap renderer. Renamed from `OpenMeteoLowCloudViewParityIntegrationTest`, which was written the same morning to guard *against* this change; the parity claim was always the load-bearing half | pass |
| 6 | Unit (shared) | `CloudLayerGlyphTotalCoincidenceTest` — DRAW clear of the curve, NUDGE when the band alone explains the total, SUPPRESS when a lower band explains it; coincidence applies at any value not just 100; delta boundary; null total; and that a 0% band under a 0% total is already silent via `MIN_COVER` | 9/9 pass |
| 7 | Full suites | `:shared` 2583, `:desktop` 555, `:app` 3159 | 0 failures |
| 8 | On-device | Desktop rebuilt and restarted | see below |

### Correction found during the on-device check

The 765 surviving `OPEN_METEO_PRIOR24` rows carry their value on `cloudCover`, not `cloudCoverLow`.
That is the tell that the frozen curve was **never** broken by an upstream withdrawal:

- last successful write: **2026-08-20 20:26**
- `f9a05d26`, which switched the request to `cloud_cover_low_previous_day1`: **2026-08-20 21:56**

The write stopped 90 minutes *before* the commit that changed the variable. The curve produced
nothing from the moment it started asking for the low layer — Open-Meteo has never populated it, and
it never worked once. This was a self-inflicted regression at a known commit, not a server-side
change, and commit `26d04efc` describes it the other way round. Corrected in
`OpenMeteoApi`, `ForecastFetchCoordinator`, `PriorDayBandForecast` and the session memory.

**Still outstanding:** confirming `OPEN_METEO_PRIOR24` rows resume. The fetch is throttled to once
an hour per process, so the first write lands within an hour of the restart; a poller is watching
for it. The Android emulator screenshot is also outstanding.

## Risk

The failure the original decision prevented comes back: on a thin-cirrus day the curve will read
high while it looks clear outside. That is now a deliberate, user-made trade — and unlike in August
the graph carries `m`/`h` glyph trails that say *which* layer is responsible, which is the
information the low-only curve threw away.

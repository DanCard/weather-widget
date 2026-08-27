# Observed mid/high cloud bands as pink glyph curves — Open-Meteo

**Date:** 2026-08-27
**Plan:** [plans/260827-observed-cloud-band-glyphs-openmeteo.md](../plans/260827-observed-cloud-band-glyphs-openmeteo.md)

## What happened

Started from a question — "the last few commits added cloud layers to actuals; what could be
graphed?" — and answered it with data rather than with source reading. `observations.rawMetar` has
been preserving the original report strings all along, so the parser shipped in `ccee8f2a` could be
replayed over **1,318 stored raw METARs** covering seven days, with no wait for new data. That
replay produced the numbers that shaped the whole design:

- mid band populated on 103 of 1,318 reports (7.8%);
- **high band populated on 0** — the highest base any of five Bay Area stations reported was
  6,096 m (20,000 ft, the automated-station reporting cap);
- 801 of 1,247 low-band reports carry a null base (clear codes), so a time-height plot is sparse;
- the 2 km → 3 km boundary move reclassified 45 layer points, and 3,048 m (10,000 ft) — the fourth
  most common base overall — sits 48 m above the new boundary.

Scoping followed from a second measurement: over two days of `hourly_forecasts`, only Open-Meteo
carries band columns (387/420 rows). NWS, Silurian, Tomorrow.io and OpenWeatherMap forecast a single
total number and have 0 band rows each. Open-Meteo is therefore the only source where an observed
band has anything to be graded against, and the user chose that scope.

## The blocker found while verifying the plan

Probing the Previous Runs API live — three locations, two lookbacks — showed
`cloud_cover_previous_day1` returning 192/192 non-null while `cloud_cover_low_previous_day1`,
`_low_previous_day2`, `cloud_cover_mid_previous_day1` and `cloud_cover_high_previous_day1` all
returned **zero** non-null. The API accepts the band names and returns their keys; the server has no
data for them.

That is not only a band problem. `OpenMeteoApi.PREVIOUS_RUNS_VARIABLE` **is**
`cloud_cover_low_previous_day1`, and the newest `OPEN_METEO_PRIOR24` row in `weather.db` was dated
**2026-08-20 20:26** — the cloud graph's frozen forecast curve had been dead for a week. It failed
invisibly precisely because the null handling is correct: nulls are omitted rather than zeroed, so
`CloudSeriesBuilder` falls back to the live row with `isFrozen = false` and still draws a
continuous, plausible curve. Nothing reached `app_logs`.

So the bands' frozen forecast was rerouted to our own `hourly_forecast_history` snapshots under the
real `OPEN_METEO` source id (~76 prediction buckets/day at every lead time, carrying the band
columns since 2026-08-26). Falling back to the total column was rejected: `OpenMeteoApi:38-45`
documents with measurements why a total-column forecast must not be graded against a visible-layer
actual.

## What changed

**Prerequisite.** `ForecastFetchCoordinator.fetchPriorDayCloudForecast` had a bare
`if (byHour.isEmpty()) return`; it now logs `PRIOR_CLOUD_EMPTY` naming the withdrawn variable.

**Shared.**
- `CloudBands` + `CloudPoint.forecastBands`/`actualBands`/`isFrozenBands`; `isFrozenBands` is
  deliberately separate from `isFrozen` because the low band has a prediction history the bands do
  not.
- `CloudSeriesBuilder.build` gained `priorBands`/`retroBands` and resolves them with the same
  elapsed-hour rule and ±30 min anchor tolerance as the low band.
- `MetarCloudBlender.Result.bands`, populated in the Open-Meteo branch and gated on
  `CloudVerticalKind.PROVIDER_BANDS` — a data property, not a hardcoded source id, which is what
  keeps the feature Open-Meteo-only without saying so.
- New `PriorDayBandForecast`: the most recent snapshot made at least 24 h and at most 48 h before the
  target hour, or nothing. Deliberately unlike `DailySnapshotSelector`, whose earliest-candidate
  fallback would file a two-hour-old prediction as a day-ago forecast.
- `CloudLayerGlyphPlacer.divergentActuals` plus `MID_ACTUAL_PHASE`/`HIGH_ACTUAL_PHASE`.

**Both renderers.** Mid/high are now resolved by `CloudSeriesBuilder` instead of read off the live
row — which fixes a pre-existing defect: for an elapsed hour that row has already been
retro-corrected, so the grey glyphs left of the NOW line were already showing observed values.
Observed trails draw in `CURVE_ACTUAL` pink, feed the vertical scale, and join the glyph-obstacle
list the free-floating label search consumes.

**Reads.** `HourlyForecastHistoryDao.getPriorDayBandForecast` (Room) and
`DesktopWeatherDao.getPriorDayBandForecast` (JDBC), both bounding the prediction-bucket range in SQL
and collapsing to one site before reducing.

## The design decision worth remembering

A pink glyph is drawn **only where the actual diverges from a genuine frozen forecast** by at least
`ACTUAL_LABEL_MIN_DIVERGENCE` (8 points). This reuses the constant's existing rationale, solves the
four-trail collision problem by mostly not having four trails, and gives the glyph a meaning worth
the ink: *the forecast was wrong here*. On a day the forecast got right, the graph is unchanged.

The `frozen` half of that gate is load-bearing, not an optimisation: without a stored snapshot the
forecast trail is carrying the retro-corrected live row — the actual itself — so divergence would be
measured against a copy of the thing being measured.

## Verification

All ten rows of the plan's verification table pass; `:shared` 2556, `:desktop` 492, `:app` 3154
tests, 0 failures. Two mutation checks confirmed the new tests can fail (breaking the `frozen` gate;
dropping the observed trail from the obstacle list).

**What could not be shown yet.** The earliest stored prediction bucket carrying a band is
2026-08-26 21:00, so with a 24 h lead the first eligible hour is **2026-08-27 21:00**. Until then
every hour is `isFrozenBands = false` and no pink glyph may be drawn — the graph is expected to look
unchanged, and does.

Both write paths were confirmed on the same restart. NWS/Synoptic rows carry
`cloudVerticalKind = 20` with 44 mid bands and 315 low bases from NWS, 5 mid from Synoptic, and 0
high anywhere — matching the 7-day replay exactly. The first post-restart Open-Meteo fetch wrote 721
rows at `cloudVerticalKind = 10` with all three bands populated on every row, at the provider's
native 15-minute cadence.

Those rows caught the blind spot live: at 12:00 local the low band read **1%** while the mid band
read **63%**, having climbed 13 → 63 over two hours. The main curve calls that a clear sky. That is
the case the `m` trail exists to show, and it happened on the first fetch after the restart.

Outstanding: a visual confirmation of a pink trail (needs `isFrozenBands`, so after 21:00), and the
Android emulator screenshot.

## Left open

The **low** band's frozen forecast is still broken upstream and this work does not fix it — only
makes it audible. Whether it should also move to `hourly_forecast_history` snapshots (complete but
gap-free only while the app runs) or wait for Open-Meteo to restore the band variables is undecided.

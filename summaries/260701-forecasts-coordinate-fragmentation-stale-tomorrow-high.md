# Forecasts coordinate fragmentation: stale "tomorrow 74° vs 77°" (2026-07-01)

## Symptom

Samsung showed tomorrow's high as 74° (NWS) while the Pixel and desktop showed 77°. User suspected a
location change from an errand.

## Diagnosis

**Not the errand** — background fetches never use GPS (`WeatherWidgetWorker.doWork` reads the
widget's stored configured location), and every row in the Samsung DB was at home coordinates.

**Actual cause: a two-day-stale forecast.** NWS revised its July-2 forecast upward over two days
(74° on Jun 29/30 → 76° Jun 30 evening → 77° by Jul 1 noon). The Samsung had the fresh 77° row but
displayed the June 29 74° row, because:

1. The fetch coordinate jittered ~1.5 m on June 30 12:26 (per-widget float-jittered stored
   locations; `doWork` uses `firstNotNullOfOrNull` over widget IDs, so the picked widget's coords
   can change). Rows are keyed by exact `(locationLat, locationLon)`, so the abandoned key's last
   batch was never overwritten:
   - Site B `37.41682815551758, -122.0889205932617` — last batch June 29, tomorrow = 74°
   - Site A `37.41684341430664, -122.0890045166016` — batches since June 30, freshest = 77°
2. `ForecastDao` range queries kept `MAX(batchFetchedAt)` **per exact coordinate** inside the
   ±0.1° `LocationMatch` box, so both sites' rows survived the query.
3. `DailyViewHandler` picked the first row per (date, source) with no cross-site freshness
   ranking — the stale row won by rowid order.

Without a fix this never self-corrects: retention deletes by old `targetDate` only, so an
abandoned site's future-dated rows shadow fresh data until each date passes (~the stale batch's
forecast horizon, July 6 here). The whole forecast week had been riding the June 29 batch, not
just tomorrow.

## Fix (mirrors the earlier hourly-table fix)

- **Shared `DailyForecastSelector`** (`shared/.../shared/actuals/DailyForecastSelector.kt`) —
  collapses rows to one per (targetDate, source): `LocationMatch.sameSite` preferred, freshest
  `(batchFetchedAt, fetchedAt)` wins. Generic over row type (extractor lambdas) since
  `ForecastEntity` lives in `:app`.
- **Android read path** — the five per-exact-site `ForecastDao` range queries renamed
  `...AllSites`; extension wrappers with the original names apply the selector, so all callers
  heal without call-site changes. History-preserving queries (`getAllForecastsInRange*`,
  `getForecastsInRangeBySource`, `getForecastEvolution`) intentionally stay uncollapsed.
  mockk-based tests must stub the `AllSites` member names.
- **Desktop read path** — already immune: `getDailyForecasts`/`getDailyForecastSnapshots` use a
  global `MAX(batchFetchedAt)` across the whole box. No change.
- **Quantize-on-write** — `LocationMatch.quantize` (3 dp) applied in Android
  `saveForecastSnapshot` + `fetchClimateNormalsGap` and desktop `upsertForecasts`, so jitter can't
  split sites again.
- **Migrations** — Android `MIGRATION_49_50` (version 50) and desktop schema v5 round existing
  `forecasts` lat/lon onto the grid. Unlike the hourly collapse (47→48 / desktop v3) they dedupe
  ONLY full rounded-PK collisions, because `forecasts` intentionally keeps one row per batch for
  accuracy/evolution history.

## Verification

- New `DailyForecastSelectorTest` (6 tests, includes exact 74-vs-77 repro with the real
  coordinates); full `:shared`, `:desktop`, `:app` unit suites pass.
- Installed on Samsung/Pixel/emulator; after `ACTION_REFRESH`, Samsung DB shows schema v50, a
  single quantized site (37.417, -122.089), and exactly one NWS row for tomorrow (77°, Jul 1
  batch). Screenshot confirms Thursday = 77°, matching the Pixel.
- Desktop rebuilt + restarted via `scripts/buildStart-desktop.sh` (schema v5, zero un-rounded
  rows).

## Incidental

- CLAUDE.md and the auto-restart memory referenced the old `scripts/buildStart.sh` name; corrected
  to `buildStart-desktop.sh`.

# Daily history frozen display columns (self-sufficient daily view)

**Date:** 2026-07-05
**Plan:** `plans/260704-daily-history-self-sufficient-daily-view.md`

## Goal

Make `daily_history` self-sufficient for rendering past days in the daily bar view, so the
`forecasts` and hourly tables can eventually run shorter retention without breaking history.
Extends the pattern the `forecastDayPrecipChance`/`forecastNightPrecipChance` columns established:
freeze "what was displayed" while the day is live, replay it afterward.

## New columns on `daily_history` (both platforms, all nullable)

| Column | Frozen from | Window closes |
|---|---|---|
| `forecastHighTemp` | latest complete non-climate forecast batch | local midnight (end of day) |
| `forecastLowTemp` | same batch (moves as a unit with high) | local midnight |
| `forecastPrecipAmountMm` | same batch (monotone: null never overwrites) | local midnight |
| `noonCloudPercent` | `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent` | next-day 8am (late obs backfill) |

## Key pieces

- **Shared** `DailyHistoryFreeze` (`shared/.../shared/util/DailyHistoryFreeze.kt`): window
  predicates + monotone merge. Merge rules: overlay only overwrites when the batch is complete
  (guards NWS evening null-low batches and desktop degenerate high==low rows); nothing ever
  regresses a frozen value to null. Because the writer runs after every fetch, the surviving
  overlay equals "most recent complete batch of the day" — identical to what the snapshot-table
  reader selects.
- **Schema:** Android Room v51→52 (`MIGRATION_51_52`, schemas/52.json exported); desktop
  `SCHEMA_VERSION` 6→7 (`addColumnIfMissing` ×4).
- **Writers:** extended `snapshotDisplayedRainChance` on both platforms (Android
  `ForecastRepository`, desktop `DesktopWeatherRepository`) — same function that freezes the rain
  chances, same existing-rows-only attach rule.
- **REPLACE carry-over:** extended the fragment-heal copy in Android `ObservationRepository` and
  desktop `recomputeDailyExtremes` with the four new columns (the actuals recompute rebuilds rows
  from raw observations and would otherwise null them — the exact clobber bug the chance columns
  hit once).
- **Readers** (past days only; today/future keep live derivation): Android
  `DailyViewLogic.prepareGraphDays` (overlay, noon cloud ratio + icon percent, rain-label amount),
  desktop `DesktopDailyForecastModel.buildDay` (same three). Null frozen values fall back to the
  old snapshot/hourly paths, so pre-feature rows render unchanged.
- **One-time backfills** (`backfillFrozenDisplayColumnsIfNeeded`, both platforms): fill old rows
  from still-retained `forecasts` batches (most recent complete, matching reader selection) and
  `hourly_forecast_history` noon rows. Android pref-gated; desktop app_logs-tag-gated
  (`FROZEN_DISPLAY_BACKFILL_DONE`), mirroring the chance backfill exactly.

## Emulator verification + backfill fix (later same day)

Verified on emulator-5554: migration to v52 clean, live writer froze all four sources for today,
backfill filled 175/177. Found and fixed a transition-window bug: the live writer runs before the
backfill and freezes *yesterday's* noon cloud (window still open), which made the backfill's
all-columns-null row filter skip that row's overlay forever. Backfill is now **per-column** (fills
what's null, `?:`-preserves what's set); regression tests added on both platforms. Re-ran on the
emulator (cleared the one-shot pref, aged fetchedAt): the four 07-04 rows gained their overlay
with noon cloud untouched. Note: phones updated with the pre-fix build keep that one-day gap
(flag already set) — invisible, since the reader falls back to snapshots which share the same
retention. Also learned: `am broadcast` does not wake a force-stopped package — `am start` the
activity first.

## Gotcha found while testing

`DesktopWeatherDao.getDailyForecastSnapshots` deliberately **excludes the newest batch** for the
source (it's the live forecast, not a snapshot). A test that seeds only a past-date batch makes it
the newest overall and gets nothing back; production never hits this because today's live batch is
always newer. Test seeds a fresher today-batch to recreate reality.

## Tests

- `shared: DailyHistoryFreezeTest` — windows, merge unit-pair rule, null-never-clobbers.
- `app: ForecastRepositorySnapshotDisplayedRainChanceTest` — freeze today, incomplete-batch
  no-clobber, closed-window immutability, backfill picks older complete batch over newer null-low.
- `app: ObservationRepositoryDailyMergeTest` — REPLACE carry-over now pins all six archived columns.
- `app: DailyViewLogicTest` — frozen overlay beats snapshot bait; frozen noon cloud renders with
  hourly data absent.
- `desktop: DesktopSnapshotDisplayedRainChanceTest` — same four scenarios + backfill.
- `desktop: DesktopDailyForecastModelTest` — frozen-preferred + null-falls-back-to-snapshot.

## Scope boundary (unchanged from plan)

Only the daily bar view is archival. Day-tap hourly zoom still needs `hourly_forecast_history`;
the forecast-evolution graph (📈) needs the full snapshot series. Shortening those tables'
retention is a follow-up task once ≥30 days of frozen rows have accumulated.

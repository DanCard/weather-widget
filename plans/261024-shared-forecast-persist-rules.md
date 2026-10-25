# One forecast-persist rule: what a fetch's payload becomes in `forecasts` and `hourly_forecasts`

Follows `261023-duplicate-logic-audit-different-names.md` → "Next" item 2 (**B rest**: per-source
fetch/persist). Tier 3 (Host contracts) finished 2026-10-24.

## Why
Every source's fetch ends in the same two writes — the daily rows and the live hourly rows — and each
platform decides them itself:

| Step | Android | Desktop |
|---|---|---|
| Hourly live write | `HourlyForecastStore.saveHourlyEntities(FromShared)` | `DesktopWeatherRepository.persistForecastResult` → `DesktopWeatherDao.upsertHourlyForecasts` |
| Daily write | `ForecastFetchCoordinator.saveDailyBatch` → `ForecastSnapshotStore.saveForecastSnapshot` (~180 lines of per-row rules) | `persistForecastResult` → `DesktopWeatherDao.upsertForecasts` (~110 lines, inside the JDBC transaction) |

The pieces are shared already (`SameDayExtremeCutoff`, `ForecastTempRounding`, `DailyHourlySummaries`,
`DailyPrecipPeriods`, `HourlyOnDemand.trim`, `HourlySnapshotRun`, `ElapsedForecastBackfill`), but the
row-by-row decision that strings them together is written twice, and the two copies have drifted.
The fetch itself (`RawFetch` from the shared API clients) is already one; NWS is the exception
(Android `NwsForecastMapper` → entities, desktop `DesktopWeatherService.fetchNwsForecast` → `RawFetch`).

## Divergences found (read 2026-10-24)

### Daily write
| # | | Android | Desktop | Effect today |
|---|---|---|---|---|
| D1 | "Prior row" (frozen high/low after the cutoff, carried summaries, kept today low) | newest row on the **exact quantized key** | newest row anywhere in the **±0.1° box** (`latestForecast`, `latestForecastWithLow`: `JDBC_WHERE`) | none on desktop's data (one site in the box); a second site inside the box — a city search a few km away, Warsaw/Lviv style visits — would freeze/carry another town's values |
| D2 | Unchanged row (same values as the newest) | not written; the existing row's `batchFetchedAt` re-stamped (`SNAPSHOT_SKIP`) unless a user Refresh | a new row every fetch (PK includes `fetchedAt`) | desktop stores ~1 row per day per fetch (11,776 rows now); readers take the newest row per day, so nothing drawn differs |
| D3 | Row with neither high nor low (after cutoff + no prior) | dropped | written with both NULL | a NULL/NULL newest row could hide an older real one from "newest row per day" readers on desktop; 18 such rows in the desktop DB on 2026-10-24 (NWS 7, five other sources 2–3 each) |
| D4 | Daily period already over (`periodEndTime` past) | dropped (`SNAPSHOT_SKIP_ELAPSED`) | `DailyForecast` has no period end; never dropped | NWS evening "Tonight" only; desktop relies on the cutoff instead |
| D5 | Logs | `SNAPSHOT_SAVE/SKIP/UPGRADE/SKIP_HINDCAST`, `NWS_BATCH_SAVE_SUMMARY` | `FORECAST_SKIP_HINDCAST` only | — |

### Hourly live write
| # | | Android | Desktop | Effect today |
|---|---|---|---|---|
| H1 | A field this fetch lacks (cloud, chance, amount) | kept from the stored row (`mergePreservingNullableFields`) | written NULL (plain `INSERT OR REPLACE`) | a fetch without sky cover blanks desktop's cloud for those hours until the next one has it |
| H2 | Unchanged hour | not rewritten — its `fetchedAt` stays at the first fetch that said so | rewritten, `fetchedAt` = now | Android's `fetchedAt` means "first said", desktop's "last confirmed". `GoogleHistoryRefill.staleHours` reads it: on Android an hour Google confirmed every fetch but never changed looks stale (fetched > 6 h before it) and a History refresh spends a `history/hours` request on it |
| H3 | Snapshot rows | the merged rows (H1 applied) | the raw trimmed rows | follows H1 |

Already the same: the elapsed-hour boundary, `HourlyOnDemand.trim`, quantized keys, the snapshot
label (`HourlySnapshotRun`), the elapsed-hour backfill, `DailyHourlySummaries` (payload, then stored),
SHORT keeping only `SHORT_DAYS` of daily rows, quota recording.

## Decisions needed (recommendation first)
**Decided (user, 2026-10-24): every recommendation below** — D1, D3, D4, D5 Android's; D2 changed rows
only; H1/H3 keep stored values; H2 last confirmed.

- **D1 — exact site (Android's).** The prior row is the newest on this site's quantized key.
- **D2 — Android's (write changed rows only, re-stamp the rest).** Desktop's DB stops growing with
  duplicates; "last confirmed" is kept by the re-stamp, which `PriorDayForecast` already reads.
  Alternative: desktop's (a row per fetch) — simpler, more rows.
- **D3 — drop (Android's).**
- **D4 — Android's**, by giving the shared `DailyForecast` the period end NWS already parses (other
  sources: null, never dropped).
- **H1/H3 — keep stored values (Android's).**
- **H2 — rewrite `fetchedAt` on every confirmed hour (desktop's)**, so `fetchedAt` means "last
  confirmed" on both, as `forecasts.batchFetchedAt` already does. Costs Android one write per hour per
  fetch (it skipped them to save writes; 72 rows, trivial against the snapshot it already writes).
  Alternative: Android's skip, and `GoogleHistoryRefill` keeps reading "first said".
- **D5 — Android's tags on both** (`SNAPSHOT_*`), desktop's `FORECAST_SKIP_HINDCAST` renamed.

## Design
- `:shared` `DailyForecastWrite` — pure: `(incoming: List<DailyForecast>, prior: rows at the site,
  nowMs, zone, userRefresh) → Plan(write: rows, restamp: rows, logs)`. Holds today's two loops
  (cutoff/freeze, kept low, rounding, carry-forward, periods, unchanged/better, D3/D4).
- `:shared` `HourlyForecastWrite` — pure: `(incoming hours, stored at the site, nowMs, keep) →
  Plan(live, snapshot, trimLog)`: elapsed boundary, trim, H1 merge, H2 stamp.
- `:shared` `ForecastPersistRun` + `Host` (reads: stored daily/hourly at the site; writes: rows,
  restamps, snapshot rows, logs). Android `RoomForecastPersistHost` (replaces the bodies of
  `saveForecastSnapshot` / `saveHourlyEntities`), desktop `DesktopForecastPersistHost` (replaces
  `upsertForecasts`' rule part and `persistForecastResult`'s write part). A
  `ForecastPersistHostContract` per the Tier 3 pattern (`HostContractCoverageTest` forces it).
- NWS on Android maps to `RawFetch` first (`NwsForecastMapper` → the shared model) so every source
  takes the one path; the NWS entity path goes.
- Not in this plan: observations persistence (`saveHistoricalActuals` vs `persistObservations`),
  Tomorrow.io's 5-min history merge, the fetch registry / `refresh()` orchestration — later phases,
  same pattern.

## Phases
1. `DailyForecastWrite` (D1–D5) + both platforms calling it; `DailyParity`-style golden cases from
   real rows pulled from the Pixel and desktop DBs.
2. `HourlyForecastWrite` (H1–H3).
3. `ForecastPersistRun` + Hosts + contract; Android NWS through `RawFetch`.

Each phase: tests that fail on the old code for each decided divergence, full suite incl.
`:app:compileDebugAndroidTestKotlin`, install on the phones, restart desktop, check `SNAPSHOT_*` /
`FORECAST_HORIZON` lines and a day's rows on a device, Progress line in the audit plan, commit.

## Verification on devices
- Desktop `forecasts` row count per fetch drops to the changed days only (D2); no NULL/NULL newest rows
  (D3): `select count(*) from forecasts where highTemp is null and lowTemp is null`.
- Android: `hourly_forecasts.fetchedAt` of Google's next-24-h rows ≈ the last fetch (H2); a History
  refresh on yesterday logs `GOOGLE_HISTORY_REFILL stale=` no larger than desktop's for the same day.

## Progress
- 2026-10-24, phase 1: `:shared` `DailyForecastWrite` (the plan: written rows, re-stamped rows,
  `SNAPSHOT_*` lines). Android `ForecastSnapshotStore.saveForecastSnapshot` and desktop
  `DesktopWeatherDao.upsertForecasts` read the site's own rows (exact quantized key) and write the plan;
  desktop passes the user Refresh through. `DailyForecast.periodEndMs` (NWS daytime period end, from
  `NwsDailyMapper`) feeds D4. Tests: `DailyForecastWriteTest` (8 rule cases) and 3 `DesktopWeatherDaoTest`
  cases that fail on the old desktop writer (unchanged row stored again, NULL/NULL row stored,
  neighbour's high taken as prior). Desktop tests that seeded June days "now" seed them before the day.
  Android unchanged: no Android test changed.
- 2026-10-24, phase 2: `:shared` `HourlyForecastWrite` (elapsed boundary, horizon trim, nullable
  fields kept from the stored row, every written hour stamped now). Android
  `HourlyForecastStore.saveHourly` (both entry points) and desktop `persistForecastResult` read this
  source's rows on the site's exact key and write the plan; the snapshot is the merged rows on both.
  Android's NWS path now takes the elapsed-hour boundary too (it wrote stale cached hours live).
  `hasMeaningfulHourlyChange` / `mergePreservingNullableFields` gone (the rule writes every hour).
  Tests: `HourlyForecastWriteTest`, Android `HourlyForecastStoreWriteTest` (H2 fails on the old Android
  write, H1 on a no-merge rule), desktop `DesktopForecastPersistTest` (H1, D2 through `refresh()`).
- 2026-10-24, "yesterday's forecast" with changed-only rows (raised by a side note; user: fix the
  readers, no schema change). Two dates per row already answer "current at the cutoff?" —
  `fetchedAt` first seen, `batchFetchedAt` last confirmed — but (1) desktop's older-forecasts reader
  left `lastConfirmedAt` = `fetchedAt`, and (2) both platforms' readers skip the shown row, which for a
  forecast unchanged since before the cutoff is the only copy: a probe on desktop's new writer gave
  0 candidates → no left bar before 06:00 (the phone's known gap). Fix: desktop maps
  `lastConfirmedAt = batchFetchedAt`; `DailyForecast` carries `fetchedAt` / `lastConfirmedAt` (read
  only, both readers); `DailyColumnBuilder` offers the shown row to today's pick when first seen before
  yesterday's 16:00 cutoff (later, it is today's forecast — offering it then made today's own forecast
  the "earliest after the cutoff" stand-in; the parity fixtures caught it). Tests:
  `StableForecastPriorBarTest` (desktop writer → readers → builder) and `DailyViewHandlerTest`
  "a forecast unchanged for three days…" (Android) both fail without the builder change.
  What two dates cannot tell — a fetch in the 24 h before the cutoff when the device was off then but
  confirmed the forecast after — draws solid rather than dashed; not worth a column.

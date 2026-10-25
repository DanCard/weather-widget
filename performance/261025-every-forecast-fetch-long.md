# Every forecast fetch is LONG (SHORT removed)

User, 2026-10-25: "I don't like the long vs short updates any longer. Prefer all updates be long."

Supersedes `performance/261025-displayed-source-long-every-four-hours-screen-on.md` (not implemented).

## Today (`:shared` `ForecastHorizonCadence`, every source alike)

| Battery | Screen | LONG (≈ 8 days) | SHORT (3 days) |
|---|---|---|---|
| ≥ 70 % or charging | on | 8 h | 4 h |
| ≥ 70 % or charging | off | 24 h | 8 h |
| < 70 % | either | 24 h | none |

## Change: one horizon, at the old SHORT timing

| Battery | Screen | Every fetch (LONG) |
|---|---|---|
| ≥ 70 % or charging | on | every 4 h |
| ≥ 70 % or charging | off | every 8 h |
| < 70 % | either | every 24 h |

Every scheduled, forced, and user fetch asks the source's full reach and stores every daily row and up to
8 days of live hourly. Days 4–8 are never older than days 1–3.

- `ForecastHorizonCadence`: delete `Horizon` (or keep a single value only where a signature needs it —
  prefer deleting), `SHORT_*`, `shortIntervalHours`, `lastShortMs`, `shortDueAtMs`, `storesDailyDate`.
  `intervalHours(...)` = 4 / 8 / 24. `decide` returns due/not-due + `preWakeAtMs`.
- **Pre-wake** kept: on a charger, the fetch comes due 45 min before the alarm. The overnight skip
  carries over: charging + screen off + pre-wake within 8 h → the 8 h fetch waits for the pre-wake one
  (otherwise a 03:00 fetch is overwritten at 06:15 before anyone looks).
- `forced`: always fetches (no horizon to choose).
- `askHours` / `storeHours` / `snapshotHours`: the LONG branch only. Snapshots stay 72 h.
- Carried rows (`showsCarriedRow`, 36 h): kept — a source whose newer answer covers fewer days still
  falls back the same way; only the KDoc reason changes.
- Markers: one per source + site. Android prefs `forecast_long_fetch_` kept; `forecast_short_fetch_`
  no longer read or written (left to age out, no migration). Desktop `FORECAST_LONG_FETCH` kept,
  `FORECAST_SHORT_FETCH` dropped.
- `ForecastFetchPolicy.HIGH_BATTERY_TICK_MINUTES` points at the new 4 h constant.
- Logs: `FETCH_PLAN horizons=NWS:due(every=4h/3h)` — no `long=/short=` split.
- Docs: `arch/hourly-forecast-update-cadence.md`, `arch/ARCHITECTURE.md`, `AGENTS.md` link text,
  `ForecastHorizonCadence` KDoc.

## Cost

Free sources: LONG and SHORT are the same request (routine reach) — only more rows stored.
Google (`costsPerExtraDay`): up to 8 `forecast/hours` pages per fetch instead of 3, pages 2+ only when
page 1 changed. Screen on all day: 6 × 8 = 48 requests/day worst case (from ~33). Inside 200/day.

## Tests

- `ForecastHorizonCadenceTest`: due at 4 / 8 / 24 h per row of the table; a fetch at 4 h with the
  screen on is the full horizon (fails on old code: SHORT); pre-wake overnight skip still holds.
- `ForecastFetchPlanTest`, `ForecastScheduleTest`, `DesktopForecastPlannerTest`: drop SHORT cases,
  assert every planned fetch stores days 4–8.
- `ForecastHorizonStorageIntegrationTest` / `DesktopForecastHorizonStorageTest`: a routine 4 h fetch
  writes day 8's daily row (fails on old code).
- Verify: emulator `FETCH_PLAN` + desktop app after restart.

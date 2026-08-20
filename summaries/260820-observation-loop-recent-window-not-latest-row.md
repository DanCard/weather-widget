# Observation loop dropped intermediate readings — recent window instead of single latest row (2026-08-20)

## Problem

> "At 10 min you already record roughly 1 in 2 KSJC observations" — "That is a bug. Should record both."

`2befc157` cut the observation loop from a 7-day history window to a single
`/stations/{id}/observations/latest` lookup. The CPU win was real, but stations that publish faster
than the poll interval now have their intermediate readings discarded.

## Evidence

Live DB, 2026-08-20. KSJC publishes ~every 5 min; the loop polls every 10. Exactly two gaps >6 min
in 48h, both since the change:

| Missing (local) | Exists in NWS API? |
|---|---|
| 10:10 | No — station genuinely didn't publish |
| 10:30 | **Yes** (`17:30Z`, 19 °C) — dropped by us |

`fetchedAt` separates the mechanisms: rows ≤10:20 all carry `fetchedAt=10:32:24` (hourly bulk
backfill); 10:25 and 10:35 carry their own poll timestamps. 10:30 fell between two single-row polls.

Also found: `/observations/latest` lags the list endpoint. The 10:48:36 poll returned a 10:25
reading while the list endpoint already had 10:45 — the single-row fetch cost freshness too.

Not permanent data loss: the hourly full pull backfills the 7-day window, so holes heal within ~1h
of AC uptime. But the hole scales with the poll interval, which matters for the proposed 30-minute
screen-off tier.

## What changed

- `fetchObservationBundles` fetches a **90-minute** window in recent-only mode instead of one row.
  Covers a 30-min poll + the ~25-min publish lag + a missed cycle. ~50–90 rows/cycle across 5
  stations vs ~2500 for the 7-day window, so `2befc157`'s reduction survives.
- The existing tri-state `historicalOutcome` handling now covers both modes, retiring the
  `historicalOutcome!!` that the flag had introduced.
- `latestOnly` renamed to `recentOnly` (`WeatherApiClient`, `DesktopWeatherService`,
  `DesktopWeatherRepository`) — it no longer fetches only the latest.

Unchanged: full-pull path, Synoptic/web prefer-newest merge for current temp, and the hourly 7-day
backfill as the long-gap safety net.

## Verification

- `DesktopObservationLatestOnlyTest` → `DesktopObservationRecentWindowTest`: its old assertions
  (zero `getObservations` calls, exactly 2 rows) encoded the bug and are inverted — one windowed call
  per station, window ≈90 min not 7 days, and every observation in the window returned. That last
  assertion is the direct regression test for the dropped 10:30 row.
- `DesktopObservationCpuTest`'s 500ms process-CPU bound retained as the full-pull guard.
- `./gradlew :desktop:testByDurationDesktop`

## Follow-ups

- The screen-off 30-min / screen-on 10-min-staleness cadence change that surfaced this is separate
  and not yet implemented.

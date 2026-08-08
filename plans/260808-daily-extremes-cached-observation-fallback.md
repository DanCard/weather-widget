# daily_history: fall back to stored observations, and record where the value came from

Date: 2026-08-08
Third in the series. Follows `260808-nws-actuals-forecast-contamination.md` (removed the
forecast-as-actual writers, added the per-day NWS station pull) and
`260808-history-actuals-from-nws-station-pull.md` (the pull produces both actuals).

User direction:
> "If a days history extreme data is missing can we fall back to our hourly graph actuals data?"
> "a. … if nws end point for history daily extremes doesn't work, then derive values from hourly
> actuals table. Probably should store in daily history table the source of the data. Was it from
> nws api daily whether extremes or other source."

## Problem

`/stations/{id}/observations` retention is a **rolling window from now**, not whole days, so the
oldest in-range day arrives sliced off at the current wall-clock hour. Measured 2026-08-08 09:00,
every official station's 2026-08-01 series began at hour 09 — KNUQ 33 readings, KPAO 7, KSJC 178,
none before 09:00. No station clears the pre-dawn half of the coverage guard, so the day gets no
`apiHighTemp` at all.

Our own `observations` table still has that day complete:

| KNUQ, 2026-08-01 | coverage | pre-dawn readings | extremes |
|---|---|---|---|
| live endpoint | hours 09–23 | 0 | unusable |
| **stored `observations`** | 00:15–23:55, 72 rows | 21 | **59.0 / 77.0** |

We captured the whole day while it was current and kept it; the endpoint has since discarded the
early hours. On device the table holds ~11 days against the endpoint's usable ~7, so there is a
band — roughly days 7–11 back — where **we are the only source with the complete day**.

Two consequences today:

1. The day never gets an api actual.
2. It never leaves `findNwsDatesMissingStationActuals`, so it costs 5 requests on *every* fetch
   cycle until it ages out — the re-pull waste noted at the end of the previous plan.

## Scope: the api actual only

The blend already falls back and needs no change. `computedHighTemp` is produced by the ordinary
`recomputeDailyExtremesForDay` from the stored pool — the same `blendObservationSeries` that draws
the hourly graph's actual line — and `poolCoversDay` makes a truncated pull skip rather than
overwrite it. That is why 08-01's blend self-heals. Confirmed on the emulator.

So the only gap is `apiHighTemp`/`apiLowTemp`.

## What changes

### 1. New column: `actualsSource` (Room v60, desktop schema v14)

`daily_history.actualsSource TEXT`, one of:

| Value | Meaning |
|---|---|
| `NWS_STATION_PULL` | live per-day `/stations/{id}/observations` request |
| `CACHED_OBSERVATIONS` | derived from our retained `observations` rows |
| `null` | not resolved, or written before v60 |

Shared enum in `:shared` so both platforms and the UI agree on the strings.

### 2. Distinguish "insufficient" from "request failed"

`fetchStationDay` currently returns `emptyList()` for both, which conflates a truncated day with a
network blip. The fallback must not fire on a blip — that would lock in a cached value when a live
pull would have worked next cycle.

Adopt the tri-state the codebase already uses for this exact distinction (`NwsApi.FetchOutcome`:
`Success` / `NoData` / `Failed`, see `getLatestObservationDetailedResult`). `resolveForDates`
returns per date whether the day was *resolved*, *insufficient* (data came back, coverage was not
there), or *unavailable* (a request failed). Only **insufficient** triggers the fallback;
**unavailable** leaves the date in the missing set to retry.

### 3. Fall back to stored observations

When a date is insufficient, run the **same** `StationDailyExtremes.resolve` over the stored
`observations` rows for that day — same nearest-official rule, same coverage guard, same exclusions
(PWS, `NWS_BLEND`, `<SOURCE>_MAIN`, QC-failed). Only the pool differs.

The stored pool includes Synoptic rows (16 of KNUQ's 72 on 08-01). That is the deliberate choice
from the two options weighed: the Synoptic rows for KNUQ are the same ASOS METARs redistributed —
the stored union reproduced the endpoint's extremes exactly on 08-05/06/07 — whereas restricting to
`isWebFallback = 0` leaves 17–24 of 72 readings, the subset already measured under-reporting peaks
by 1.8 °F. Completeness beats nominal purity here, and `actualsSource` discloses the difference
rather than hiding it.

### 4. Replace the implicit freeze marker

`persistExtremes` currently freezes a past day's blend on `apiStationId != null`. The previous plan
logged this as a risk: the column doubles as a provenance marker and would misfire if another
writer ever set it. Switch the guard to `actualsSource != null`, which says what it means.

### 5. Re-pull waste goes away

A day resolved from cache gets a non-null api pair, drops out of the missing set, and stops costing
requests. A day that is genuinely *unavailable* still retries, which is correct.

### 6. Surface it

`ForecastHistoryActivity` and desktop `ForecastHistoryWindow` label the API-actual row with its
provenance — e.g. `NWS official · KNUQ 3.8 km` vs `KNUQ 3.8 km (from stored readings)`. Same
discipline as the accuracy screen's borrowed-baseline label: a number that does not say where it
came from misleads.

## Testing

Shared (pure):

- `NwsDailyExtremesFetchTest`
  - an insufficient day reports insufficient, not resolved; a failed request reports unavailable.
  - the two are distinguishable in the returned result.
- New `CachedObservationFallbackTest` (or extend `StationDailyExtremesTest`)
  - the 08-01 fixture: endpoint pool covers 09–23 only, stored pool covers 00:15–23:55 → fallback
    yields KNUQ 59.0/77.0.
  - stored pool that also fails the guard → still nothing, no invented value.
  - fallback obeys the same exclusions (PWS never wins, synthetic rows never win).

Android:

- `NwsStationActualsStoreTest`
  - a cache-resolved row is written with `actualsSource = CACHED_OBSERVATIONS`.
  - a pull-resolved row is written with `NWS_STATION_PULL`.
  - the freeze guard keys on `actualsSource`, not `apiStationId` — prove by nulling `apiStationId`
    while leaving `actualsSource` set and confirming the blend still freezes.
  - a date whose request *failed* stays in `findNwsDatesMissingStationActuals`; a date resolved
    from cache does not.
- Migration test v59→v60: column added, existing rows get `actualsSource = NULL`, no other column
  disturbed.

Desktop: mirror the freeze-guard and provenance assertions.

Prove each new guard can fail by removing it, as with the previous two plans.

## Risks

- **Cache quality varies by station.** KPAO stores 11 rows for 08-01 with 1 pre-dawn reading — it
  would fail the guard, correctly, and fall through. The guard is doing the work; the fallback just
  changes which pool it reads.
- **Freeze semantics for cached rows.** A cached value is final in practice (endpoint retention only
  shrinks), so freezing it is right. But it means a later observation backfill that improves the
  stored pool will not revise the api actual. Acceptable; note it.
- **One more schema column.** v60 on Android, v14 on desktop, for a field that is diagnostic rather
  than functional. Justified by it also retiring the implicit freeze marker.

## Out of scope

- The blend: unchanged, already falls back.
- Today's row and the live current-temperature path.
- Non-NWS sources.
- Widening beyond `MAX_LOOKBACK_DAYS = 7`. The stored pool holds ~11 days, so a later change could
  resolve days 8–11 purely from cache without any request. Worth considering separately; it changes
  the trigger's date range, not this mechanism.

# Today-column "fcst" delta shows −14.5° from a 13-day-old coordinate fragment

Samsung Fold (SM-F936U1, `RFCT71FR9NT`), daily forecast view, Today column, Meteo source.
User-reported 2026-08-06 ~19:26 PDT: today column shows `−14.5 fcst` — wrong.

Related: `plans/260806-today-column-stale-fragment-delta.md` documents the same bug observed
later the same evening (−13.7 at 19:31/19:41) and proposes the fix. This plan adds the
earlier, fully-traced 19:23 evidence and confirms that root cause; it does not supersede it.

## Evidence (all pulled live from the device)

Screenshot (main display, 19:26): today column `−14.5 fcst`, `65.7°`, `0m`; header `65.1° −0.4 from yest`.
Daily bars correct (Today 74.1/59.3) — only the delta is wrong.

Verbose resolver trace still in the logcat buffer — the exact flip, same widget (345),
same observation (`obs=65.70`, OPEN_METEO_MAIN @19:15), 6 seconds apart:

```
19:23:06.837  CURR_TEMP_RESULT: display=65.21 estimate=64.21 obs=65.70 delta=+1.00  estAtObs=64.70
              isStaleHourlyData: scopedCount=16 latestFetchMs=1786047334316 (today 13:15:34)  fcst=64
19:23:12.391  CURR_TEMP_RESULT: display=65.07 estimate=79.52 obs=65.70 delta=-14.45 estAtObs=80.15
              isStaleHourlyData: scopedCount=16 latestFetchMs=1784948582262 (2026-07-24!)     fcst=32
19:23:12.389  TODAY_OVERLAY widget=345 observedAt=1786068900000 delta=-14.5
              dominantTemp=65.7 dominantAge=0m stationId=OPEN_METEO_MAIN
```

Arithmetic checks out exactly against the DB dump:

1. Correct render (+1.0): home-site `37.417,-122.089` rows 19:00=65.6, 20:00=62.0
   (fetched today 13:15:34) → 19:15 interpolation `64.70`, now (19:23) estimate `64.21`.
2. Wrong render (−14.45): stale fragment `37.419,-122.087` rows 19:00=81.3, 20:00=76.7
   (fetched **2026-07-24 20:03**, 13 days old) → 19:15 `80.15`, now `79.52`.
3. `appliedDelta = 65.7 − 80.15 = −14.45` → displayed `−14.5 fcst`.

## Root cause (confirms the existing plan, with one clarification)

1. GPS jitter (~780 m lat / ~1.9 km lon observed) defeats `WRITE_QUANTIZE_DECIMALS = 3`
   (~111 m), so `hourly_forecasts` (PK `dateTime, source, locationLat, locationLon`)
   accumulates ~8 coordinate fragments for the same physical desk. Only the current one
   keeps being refreshed; the rest freeze with long-range rows for today.
2. The two hourly loaders disagree on fragment admission at exactly this boundary:
   `GraphDataLoader.loadCurrentTempResolutionHourlyForecasts` filters `sameSite` against the
   raw canonical center `37.41681671,-122.08899689` (Δlat to the stale fragment
   `0.0021833 > 0.002` → excluded → +1.0 render), while `HourlyForecastLoader.load` filters
   against the quantized best-site `37.417,-122.089` (Δlat `0.002 ≤ 0.002` → **admitted**).
3. `HourlyForecastLoader.load` then collapses `(history + filteredCurrent)` with
   `associateBy { dateTime to source }` — last-wins, `fetchedAt`-blind. DAO order is
   `dateTime ASC` with ties broken by the `(locationLat, locationLon)` index, i.e. ascending
   latitude: home's fresh 66.6 (37.417) arrives first, the stale 81.3 (37.419) arrives last
   and **wins**.
4. `isStaleHourlyData` flagged `stale=true` (age 13 days vs 2 h threshold) and nothing acted
   on it — the delta was applied and rendered anyway.
5. Clarification vs. the sibling plan: `hasMeaningfulHourlyChange` dedup in
   `HourlyForecastStore.saveHourlyEntities` is NOT implicated — home's current-table rows
   were fresh at both render times (the 19:23:07 full-horizon upsert, 361 rows, had landed).
   The stale fragment wins purely in the read-path collapse.

## What will change

1. **Freshness-aware collapse (core fix).** In `HourlyForecastLoader.load`, replace the
   last-wins `associateBy` with a max-by-`fetchedAt` reduction per `(dateTime, source)`.
   A stale fragment can then never overwrite a fresh row, regardless of admission boundary
   or SQLite tie order.
2. **One shared site-collapse helper.** Add a `LocationMatch` helper that reduces a box
   result to one row per `(dateTime, source)` by greatest `fetchedAt`, and route both
   `HourlyForecastLoader` and `GraphDataLoader` hourly loads through it so the two render
   loaders can never disagree again (no re-tuning of `SAME_SITE_TOLERANCE_DEG` — that only
   moves the boundary).
3. **Do not gate the delta on `isStaleEstimate`.** `displayTemp = estimatedTemp +
   appliedDelta`; nulling the delta when stale would have shown ~79.5° as the current temp.
   Freshness belongs in row selection, not delta suppression.

## Verification

1. Unit (`:shared` or `:app` plain JVM): feed the real eight-fragment 19:00 set (above) in
   ascending-latitude order through the new collapse → greatest-`fetchedAt` row wins; assert
   the old `associateBy` order-dependence is gone.
2. Unit: `HourlyForecastLoader` and `GraphDataLoader` return the same 19:00 temperature for
   center `37.41681671,-122.08899689` given all eight fragments.
3. Device (Samsung): rebuild, install, confirm `CURR_TEMP_RESULT estAtObs` tracks the latest
   fetch and `TODAY_OVERLAY delta=` no longer oscillates between fresh and Jul-24 values
   across consecutive renders.

## Follow-ups (not in this change)

1. Frozen fragments remain in the DB and can leak into other coordinate-keyed reads; consider
   a cleanup pass dropping fragments superseded by a fresher same-site fragment.
2. `WRITE_QUANTIZE_DECIMALS = 3` does not collapse this device's real jitter; the
   `LocationMatch` doc comment's jitter assumptions are wrong for a stationary phone.
3. Stored-delta scope is persisted quantized (`37.417`) but compared with `< 0.000001`, so it
   mismatches the raw canonical location (`37.41681671`) and forces pointless recomputes
   (seen in the same logcat: `scopeMismatch=lat,lon`).

## Status

Planned — not yet implemented.

# Session log — Replace `daily_extremes` table with a cache derived from the hourly-view computation

**Date:** 2026-06-17
**Branch:** main
**Status:** **Planning only** (plan mode). No code changed. Investigation + design decisions captured below; final plan not yet written/approved.
**Continues:** `260617-hourly-graph-nan-crash-pin-removal-5min-window-and-anchored-rerender.md`
(this is a deliberate follow-up to that log's **Open item #1 — daily vs hourly actual high/low still disagree**).

---

## Goal

User wants to stop the **daily forecast bar** and the **hourly temperature graph** from disagreeing on a
day's actual high/low. Proposal: get rid of the persisted `daily_extremes` table and replace it with a
cache whose builder uses **the same data and the same algorithm as the hourly view** for each day.
Concrete trigger example the user cited: **location change**.

---

## All prompts (verbatim, in order)

1. `I'd like to get rid of the daily extremes table and just use a cache in its place.  What do you think?  Is that a good idea.  Discrepancies arise between table and hourly view, so that is why I want to get rid of it.  Cache builder should use same data and algorith and hourly view for the day.`
2. `Example issue: location change.` *(interrupt)*
3. *(AskUserQuestion answers)* Q1 "What should the cache actually be?" → answered with a question: `Would it be a good idea to add location to extreme table?`; Q2 "Which value should the daily bar's high/low match?" → `Hourly graph's labeled extrema`
4. *(AskUserQuestion rejected)* `The user wants to clarify these questions. ... Start by asking them what they would like to clarify.`
5. `Write session log to session-logs/ dir`

---

## Investigation (read-only; via 3 parallel Explore agents + direct reads)

### The `daily_extremes` table
- Entity: `app/.../data/local/DailyExtremeEntity.kt`. Room table `daily_extremes`.
  **PK already = `(date, source, locationLat, locationLon)`** + index on `(date, locationLat, locationLon)`.
  Fields incl. `highTemp/lowTemp` (Float °F), `condition`, `updatedAt`, `precipAmountMm/DayMm/NightMm`.
- DAO: `DailyExtremeDao` — `insertAll` (REPLACE), `getExtremesInRange(start,end,lat,lon)`
  (filtered by `LocationMatch.ROOM_WHERE`, ~7mi box), `deleteOldExtremes` (30-day retention).
- DB **version 47**; column adds via `MIGRATION_44_45` / `MIGRATION_45_46` (+ self-heal at
  `WeatherDatabase.kt:131-135`).
- Desktop mirror: `DesktopWeatherDao.upsertDailyExtremes` / `getExtremesInRange` / `getDailyActuals`.

### Write path is ALREADY unified with the hourly graph's blender
- `ObservationResolver.computeDailyExtremes` → `ActualsAggregator.aggregate`
  → `blendDailyExtremesViaSeries` → **`ActualTemperatureSeriesBuilder.blendObservationSeries`**
  (the same builder the hourly graph uses), then `series.maxOf/minOf` per day
  (`ActualsAggregator.kt:141-156`).
- ⇒ The comment at `ObservationRepository.kt:471-474` ("persisted row uses IDW-of-per-station-max, a
  different algorithm") is **STALE**. That algorithm drift was already removed. Today is still computed
  live, but for *algorithm* reasons that no longer apply.

### So the remaining divergence is NOT algorithm — it's stale persistence + a labeling difference
- **Read path:** `WeatherRepository.getDailyActualsWithLiveToday` →
  `ObservationRepository.getDailyActualsWithLiveToday` (`:416-479`): past days from the table; **today
  always computed live** and explicitly NOT merged with the persisted row.
- **Consumers of the table:** widget daily bar (`WeatherWidgetWorker:262`, `WeatherWidgetProvider:324`),
  `WidgetIntentRouter:431`, **`AccuracyCalculator:88`** (30-day ground truth), **`ForecastHistoryActivity:288`**,
  desktop `DesktopWeatherRepository`.
- **Location-change failure modes (the user's example):**
  - Move **> ~7mi**: old rows correctly fall outside the tolerance box ⇒ daily bar has no past-day
    actuals for the new location until a `recomputeDailyExtremesForDay` pass runs ⇒ lags the hourly
    graph (which recomputes from current observations every render).
  - Move **< ~7mi**: tolerance box matches **both** old and new rows; `sourceExtremesToDailyActualMap`
    picks geometrically-closest ⇒ can serve a **stale old-location** value.
  - The hourly graph has no persisted aggregate, so it never goes stale ⇒ the asymmetry.

### Two different definitions of "the day's high/low" exist
- Daily bar / `ActualsAggregator`: **raw `max/min`** of the blended day series.
- Hourly graph **label**: `shared/.../graph/TemperatureExtrema.compute` — turning-point detection +
  **5° incomplete-day-high suppression** (`INCOMPLETE_DAY_HIGH_MARGIN_DEGREES`, today only) +
  **midnight-straddle shoulder dropping** + boundary exemptions. Driven by `transitionX` (NOW boundary).
- These can disagree even with the same blender. **User chose: the daily bar should match the graph's
  LABELED extrema.**

### Supporting shared pieces
- `shared/.../graph/HourDataAssembler.assembleHourData` builds the dense sub-hourly list.
- `LocationMatch.SAME_SITE_TOLERANCE_DEG = 0.002` (`sameSite()`) unifies same-site forecast fragments
  (recent commit 5d73feaa). `LocationMatch.TOLERANCE_DEG` is the wider ~7mi read box.

---

## Decisions captured this session

- **My assessment (stated to user):** good idea *in spirit*, but reframe from "delete the table" to
  "make the daily bar derive its high/low from the same computation the hourly graph labels, so there
  is one source of truth." The disease is duplicated, separately-invalidated state — not persistence
  per se. The persistence question hinges on `AccuracyCalculator` + `ForecastHistoryActivity` needing
  durable historical actuals.
- **User answer — which value:** daily bar should match the **hourly graph's labeled extrema**
  (`TemperatureExtrema.compute`), NOT raw `max/min`.
- **User answer — persistence:** *did not pick* Keep-table vs Drop-table; instead asked
  **"Would it be a good idea to add location to extreme table?"** → I clarified that **location is
  already in the PK**, so the issue is stale rows, not a missing dimension. (Awaiting the real
  persistence decision.)

---

## Open questions (still to resolve before a final plan)

1. **Persistence model:** keep `daily_extremes` as a strictly overwrite-only / active-location cache,
   vs. drop it and derive on demand from retained observations (impacts `AccuracyCalculator` &
   `ForecastHistoryActivity` recompute cost + observation-retention dependency).
2. **Stale-location handling:** since location is already keyed, the fix is to prevent stale-location
   rows from being served (active-location filter; clear/ignore old-location rows on move), regardless
   of model #1.
3. **Where the shared "labeled per-day extrema" function lives:** extend `ActualsAggregator.aggregate`
   to run `TemperatureExtrema.compute` per day, vs. a new shared `DailyExtremaFromGraph`-style helper
   that both the daily bar and any cache builder call.

---

## Status / next steps
- No final plan written yet (the 2nd clarifying AskUserQuestion was rejected; user asked to clarify
  first, then requested this log).
- Next: resolve open questions 1–3, then write the plan file and ExitPlanMode.

## Related memories
- `daily_vs_hourly_actual_extrema_mismatch`, `hourly_singleday_pin_nan_crash`,
  `desktop_coordinate_fragmentation`, `shared_location_match_predicate`,
  `per_day_actual_extrema_labels`, `shared_hourdata_assembler_convergence`.

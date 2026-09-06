# Stop writing daily_history rows for feeds that cannot be displayed

**Date:** 2026-09-06
**Status:** done — option B implemented, tested and verified on device

Fourth in the Samsung tap-latency thread. The previous plan deferred this with "Part 2 makes it
nearly free, so it is not worth the risk of removing a row something may read." That framing was
half wrong in each direction, and the investigation below is why it is worth doing after all:
**nothing reads the row**, and it is **not nearly free** — it duplicates the single most expensive
blend group in the recompute.

## What is actually happening

`ActualsAggregator` groups observations by `api` and drops provider-only feeds from the output. Its
comment states the intent exactly:

```kotlin
// Provider-only feeds are dropped from the OUTPUT. METAR is not a display source, so a
// daily-history row filed under it would never be read — only written, retained a month and
// recomputed. The rows still do their work through `borrowedGroups`.
val groups = byApi.entries
    .filter { (api, _) -> api != ActualsProviderResolver.DEFAULT_PROVIDER.id }
    .map { it.key to it.value } + borrowedGroups
```

**The comment names a category; the code names one member of it.** `DEFAULT_PROVIDER` is METAR, so
METAR is dropped and every other provider-only feed is not. SYNOPTIC is a provider-only feed —
`WeatherSourceOrdering.ALL_CONFIGURABLE` is `NWS, TOMORROW_IO, OPEN_METEO, SILURIAN, WEATHER_API,
OPEN_WEATHER_MAP`, and SYNOPTIC is absent, exactly like METAR. It cannot be selected in Settings.

The asymmetry is visible in the data:

| source | daily_history rows | in ALL_CONFIGURABLE? |
|---|---:|---|
| NWS | 204 | yes |
| SILURIAN | 157 | yes |
| OPEN_METEO | 87 | yes |
| TOMORROW_IO | 79 | yes |
| **SYNOPTIC** | **51** | **no** |
| WEATHER_API | 8 | yes |
| OPEN_WEATHER_MAP | 6 | yes |
| **METAR** | **0** | no — already filtered |

METAR has zero rows because the filter works for it. SYNOPTIC has 51 because the filter does not
reach it.

## Nothing reads it — verified, not assumed

Every read path filters `daily_history` by an **active display source**, and SYNOPTIC can never be
one:

- `DailyActualsStore.getDailyActualsWithLiveToday` — `getExtremesInRange(...).filter { it.source in activeSources }`
- `ForecastHistoryActivity` — `sortedAppActuals.find { it.source == requestedSource?.id }`, where
  `requestedSource` is a display source
- `NwsStationActualsStore`, `DailyHistorySnapshotter`, `NwsObservationBackfiller` — all NWS- or
  writer-scoped

So the row is written, retained a month, and recomputed, and is never read. That is precisely the
condition the comment already describes as the reason to drop METAR.

**The "duplicate" observation from the previous plan was a symptom, not the cause.** The SYNOPTIC row
matches SILURIAN for every settled day on *this* device only because `actuals_provider_SILURIAN =
SYNOPTIC` — Silurian borrows Synoptic, so its borrowed group reduces the same observations. Clear
that preference and the rows stop matching, but the SYNOPTIC row is still unreadable. **The fix must
therefore key on "cannot be displayed", never on "duplicates another row"**, or it would do nothing
on a device without that preference.

## Why it is not "nearly free"

Part 2 made recomputes rare, not cheap. On a day that *does* recompute, this is the most expensive
group in the aggregator: SYNOPTIC is the largest observation pool by a wide margin — **34,726 rows
against NWS's 7,862** in one 132 h window — and the group runs a full IDW blend over it. Silurian's
borrowed group already blends those same rows, so the SYNOPTIC group is a second pass over the
biggest input in the app.

Expected saving is therefore a meaningful share of `recompute=` on changed days (measured 2,531–5,083 ms),
plus 51 rows and their writes. Small in absolute terms now; the stronger argument is the second one
below.

## The other reason: it is a debugging trap

`DAILY_HISTORY_STABLE` and `DAILY_HISTORY_OVERWRITE` emit a line per source, so SYNOPTIC currently
doubles the log volume for Silurian's numbers and makes the two look like independent corroboration
when they are the same computation twice. This session lost time to exactly that — reading identical
SILURIAN/SYNOPTIC highs as agreement between two sources rather than one value printed twice.

## Change

Generalise the filter from "not the default provider" to "not a display source", derived rather than
enumerated so it self-corrects if SYNOPTIC is ever made selectable:

```kotlin
val displayableIds = WeatherSourceOrdering.ALL_CONFIGURABLE.map { it.id }.toSet()
val groups = byApi.entries
    .filter { (api, _) -> api in displayableIds }
    .map { it.key to it.value } + borrowedGroups
```

`borrowedGroups` is computed from `byApi` *before* this filter, so Silurian keeps receiving Synoptic
rows exactly as today — the same mechanism that already lets borrowers use METAR.

### The one decision to make first

`ALL_CONFIGURABLE` also excludes **VISUAL_CROSSING**, a deprecated source its KDoc says is "retained
only for historical data parsing". The filter above would stop writing its daily_history rows too.
That is arguably correct — it cannot be displayed either, so its rows are equally unreadable — but it
is a wider change than this plan's subject and should be a conscious choice.

Two options:

| | predicate | drops | note |
|---|---|---|---|
| **A (narrow)** | `api !in ALL_CONFIGURABLE && ActualsProviderResolver.canProvide(source)` | METAR, SYNOPTIC | Targets provider-only feeds exactly, matching the comment's wording. Verify `canProvide(VISUAL_CROSSING)` is false before relying on it. |
| **B (broad)** | `api in ALL_CONFIGURABLE` | METAR, SYNOPTIC, VISUAL_CROSSING | Simpler and expresses "only displayable sources get rows", but silently changes a deprecated source's behaviour. |

**User chose B** (2026-09-06). Verified safe before implementing — see Outcome.

### Existing rows

The 51 stored SYNOPTIC rows stay unreadable and retention prunes them within a month, so a cleanup is
optional. If wanted, there is precedent in `DailyHistoryDao` — `deleteTomorrowIoHistory()` and
`deleteOpenMeteoHistory()`, both one-shot `DELETE ... WHERE source = ? AND computedHighTemp IS NOT NULL`,
guarded so FORECAST_ONLY_ROW rows survive. Follow that shape exactly if adding one.

## Risks

- **A future release makes SYNOPTIC selectable.** Deriving from `ALL_CONFIGURABLE` means adding it
  there restores its rows automatically; no second place to remember.
- **Something reads daily_history without a source filter.** Searched and none found, but the
  aggregator is shared with desktop — check `:shared` and `:desktop` consumers before landing, not
  just `:app`.
- **Accuracy statistics.** `StatisticsActivity` grades a source against its own history; SYNOPTIC has
  no forecast snapshots, so it cannot appear there. Confirm rather than assume.

## Verification

- **Unit, in `:shared`:** given a pool containing NWS, SYNOPTIC and METAR observations plus a
  borrower configured onto Synoptic, `ActualsAggregator.aggregate` emits NWS and the borrower and
  **no** SYNOPTIC or METAR group; the borrower's values are unchanged from before the filter. That
  last clause is the real assertion — it is what proves `borrowedGroups` still sees the rows.
- **On device with Silurian displayed:** the actual curve and daily bars must be identical before and
  after. This is the case that would break if the filter were applied before `borrowedGroups`.
- `DAILY_HISTORY_STABLE`/`OVERWRITE` should stop emitting `src=SYNOPTIC`; SILURIAN's lines must keep
  the same values.
- `SYNC_PERF recompute=` on a changed day should fall; capture before/after on a day that actually
  recomputes, since a skipped day shows nothing.

## Related

- [260906-thin-personal-stations-and-skip-settled-day-recomputes.md](260906-thin-personal-stations-and-skip-settled-day-recomputes.md) — deferred this; Part 2's skip is why the win is now bounded to changed days
- [260906-scope-observation-read-by-api-and-bound-paint-concurrency.md](260906-scope-observation-read-by-api-and-bound-paint-concurrency.md) — where `actuals_provider_SILURIAN = SYNOPTIC` was first established as load-bearing


---

# Outcome

**Option B implemented**: the filter is now `api in ALL_CONFIGURABLE`, dropping METAR, SYNOPTIC and
VISUAL_CROSSING alike. Derived from `WeatherSourceOrdering.ALL_CONFIGURABLE`, so making a feed
selectable in Settings restores its rows with no second place to remember.

## VISUAL_CROSSING was checked before committing to B, and it is safe

B's only extra risk over A was VISUAL_CROSSING, and it needed more than "it has zero rows here" —
`CurrentTempRepository:321` genuinely writes observations under that api, so another device could
have rows. Two facts close it:

1. **`AccuracyCalculator` reads daily_history with NO source filter** — `getExtremesInRange(...)`
   then hands *every* source's rows to `AccuracyBreakdown.compute`. This is exactly the "something
   reads daily_history without a source filter" risk the plan listed, and it is real.
2. **But the baseline resolver cannot pick it.** `ActualsBaselineResolver.resolveBaselineSource`
   iterates `orderedVisibleSources` and filters on `hasNativeActuals`, which is
   `historicalDataKind != NONE`. VISUAL_CROSSING declares no `historicalDataKind`, so it defaults to
   `NONE` and is excluded from ever being an accuracy baseline. SYNOPTIC and METAR are excluded a
   step earlier by never being visible at all.

So no reader can select any non-displayable source's row, and B costs nothing A would have kept.

## Verified on device

`DAILY_HISTORY_STABLE` after the change emits exactly four lines per day — and Silurian, the source
that would break if `borrowedGroups` were affected, keeps real borrowed values:

```
src=SILURIAN    high=59.568882 low=53.0708
src=TOMORROW_IO high=59.25     low=54.53
src=OPEN_METEO  high=58.8      low=53.6
src=NWS         high=60.509552 low=54.25357
```

No `src=SYNOPTIC`, where there were five lines before. The 51 stored SYNOPTIC rows stop being
updated (their `updatedAt` freezes at the pre-install value) and age out with the ~1-month
retention; no cleanup query was added, since they are unreadable in the meantime.

## Tests

`ActualsNonDisplayableSourceTest` (`:shared`), 5 cases: SYNOPTIC, METAR and VISUAL_CROSSING each get
no group; every emitted source is one `ALL_CONFIGURABLE` contains; and a borrower configured onto
Synoptic still receives its provider's rows with non-null extremes.

**Mutation-tested.** Reverting the filter to the old `api != DEFAULT_PROVIDER.id` fails four of the
five — and `METAR still gets no group` correctly keeps passing, since that one behaviour was already
right. That is the check that the tests pin the change rather than the code's shape.

Full `:app:testDebugUnitTest` and `:shared:test` green.

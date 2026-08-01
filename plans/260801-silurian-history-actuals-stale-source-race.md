# Silurian history actuals vanish after API toggle (stale `activeSourceList` race)

**Date:** 2026-08-01
**Device:** Samsung SM-F936U1 (`RFCT71FR9NT`), widget 345 (10x5 GRAPH, offset -1)
**Symptom (user report):** "daily forecast view: when I move to silur api, history actual temp is
missing. Sometimes reappears. Does reappear if I click back in history."

## Diagnosis

A real race, not slow rendering. An in-flight `WeatherWidgetWorker` repaints the widget using a
`DailyActualsBySource` map that was deliberately filtered down to the display sources captured at
the *start* of the worker run — but the repaint reads each widget's *current* display source.

### Evidence

Ordered by `app_logs.id` so same-second events are unambiguous:

| id | time | event |
|----|------|-------|
| 4546051 | 07:52:11 | Toggle NWS → **OPEN_METEO**, correct paint |
| 4546048 | 07:52:11 | `NET_FETCH_START force=true target=OPEN_METEO` — worker starts |
| 4546089 | 07:52:15 | Toggle → **SILURIAN**, `origin=USER_INTERACTION` — **actuals correct** (`obsHigh=58.91 obsLow=56.13 trueHigh=59.x`) |
| 4546102 | 07:52:15 | `worker_paint_start` — the *old* worker finishes (`SYNC_PERF total=4444ms`) |
| 4546105 | 07:52:15 | Worker's render of 345: `obsHigh=58.506 obsLow=58.506 trueHigh=null` — collapsed |
| 4546107-8 | 07:52:15 | `MISSING_ACTUALS_FETCH source=SILURIAN actuals_today` + `actuals_history` |
| 4546112 | 07:52:16 | `WIDGET_PAINT widget=345 origin=WORKER_FETCH push=partial` — **clobbers the good paint** |
| 4546136 | 07:53:06 | Next `onUpdate` render — history reappears |

Data was never missing. `daily_history` holds SILURIAN rows for every recent day at coordinates
inside the `LocationMatch` box (verified — this is *not* the recurring coordinate-fragmentation
failure):

```
2026-07-30  NWS 76/59  METEO 78/56  SILUR 78/57  TMRW 76/58
2026-07-31  NWS 77/57  METEO 80/56  SILUR 79/55  TMRW 78/56
```

### Mechanism

- `WeatherWidgetWorker.kt:179` snapshots `activeSourceList` from each widget's display source at
  the start of `doWork()`.
- `DailyActualsStore.kt:66` and `:81` hard-filter *both* the `daily_history` past extremes and
  today's observations to that set: `.filter { it.source in activeSources }`.
- `updateAllWidgets` re-reads each widget's **current** display source at paint time.

Toggle sources during the worker's ~4.4s run and the worker paints a source whose actuals it
already discarded.

### Why Silurian specifically

Widgets 349 and 352 display NWS, so NWS is always in `activeSourceList` no matter what widget 345
does. Silurian lives on widget 345 alone — the instant that one widget's snapshot says anything
else, Silurian is filtered out of the map entirely. Sources that happen to be displayed elsewhere
mask the bug.

### Why the toggle itself opens the window

`WidgetIntentActionHandler.toggleApi` calls `refreshRequester.requestForced(...)` for the newly
selected source. Each toggle *launches* the worker that will clobber the *next* toggle. Stepping
through the cycle to reach Silurian is what makes it reproducible.

### Why clicking back in history fixes it

That is a `USER_INTERACTION` render through `DailyInteractionRenderer` → `DailyActualsLoader.kt:41`,
which loads all sources with **no source filter at all**. Two paths, two different filtering
contracts over the same map.

Worker slowness is a contributing factor, not the cause: the 4.4s run is what makes the race
window wide. Speeding it up would reduce frequency without fixing the defect.

## Fix

1. **`WeatherWidgetWorker`** — stop trusting the start-of-run snapshot. Re-read display sources
   immediately before `fetchDailyActuals` (cheap prefs reads; shrinks the window from ~4.4s to the
   ~600ms actuals load).
2. **`updateAllWidgets`** — close the remaining window: before the paint loop, re-read each
   widget's current source; if any is absent from `dailyActuals.keys`, reload actuals once with the
   union set. Costs an extra query only in the rare race.
3. **Keep the source filter** — it is load-bearing for battery/latency (`SYNC_PERF actuals=610ms`).
   Removing it is not the answer.
4. **Regression test** — pure-function coverage over the "which sources must the actuals map cover
   for this paint" decision, so the two paths cannot drift apart again.
5. **Keep `MISSING_ACTUALS_FETCH` logging** — it is what made this diagnosable.

## Implementation notes

**New file `app/.../widget/DailyActualsCoverage.kt`** — pure reconciliation between the filter set
used to build the actuals map and the sources a repaint is about to display:

- `uncoveredSources(paintSourceIds, loadedForSourceIds)` — painted sources excluded from the load.
- `unionSourceIds(...)` — filter set for a repair reload; retains the originals so widgets that did
  *not* change source keep their actuals in the same reload.

Coverage is measured against **the filter set that was used**, never against `dailyActuals.keys`. A
source with genuinely no observations legitimately produces no entry; comparing against keys would
make every run pay for a pointless reload.

**`WeatherWidgetWorker.kt`**

- New `currentDisplaySourceIds(stateManager)` helper reads every installed widget's display source
  fresh. Replaces the three duplicated `appWidgetIds.map { ... }.distinct()` blocks.
- The actuals load now uses `actualsSourceList = currentDisplaySourceIds(...)` read immediately
  before `fetchDailyActuals`, not the start-of-run `activeSourceList`. Window drops from ~4.4s to
  the ~600ms load. `activeSourceList` is still snapshotted at run start for `ForecastFetchContext`
  / `requiredSourceIds`, which is correct there — those are fetch-policy decisions about what to
  request, not about what to paint.
- `updateAllWidgets` gains `loadedActualsSourceIds` and an optional `reloadActuals` lambda. Before
  the paint loop it re-reads each widget's source, and on any uncovered source logs
  `ACTUALS_SOURCE_RACE` and reloads once for the union. On reload failure (empty map) it keeps the
  original rather than repainting every widget with no actuals. Both call sites (`doWork` and
  `refreshWidgetsFromCache`) pass the lambda with `recompute = false` — the run already recomputed
  the extremes, only the read needs a wider filter.

## Verification

- `DailyActualsCoverageTest` — 6 cases, all pass. Covers the reported OPEN_METEO→SILURIAN mid-run
  toggle, retention of unchanged sources through the repair, and the no-false-positive rule for a
  requested-but-empty source.
- `./gradlew installDebug` — installed on Samsung SM-F936U1, Pixel 7 Pro, emulator.
- Post-install on the Samsung: all three widgets render (`WIDGET_RENDER_OK` for 345/349/352), a
  worker run completed, and **no spurious `ACTUALS_SOURCE_RACE`** — the common path does not
  trigger reloads.
- Outstanding: live confirmation of the repair firing needs a real mid-run source toggle. Watch for
  `ACTUALS_SOURCE_RACE` present *and* `MISSING_ACTUALS_FETCH source=SILURIAN` absent. Screenshots
  and `uiautomator dump` both fail on this fold ("Display Id '0' is not valid", "could not get idle
  state"), so the toggle has to be done by hand.

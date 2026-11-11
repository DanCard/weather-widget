# Tap renders drop today's fog: route the interaction path through the shared actuals assembler

User (2026-11-11): "Fold 4: nws: Should there be fog indicator for today?"

## Evidence (Fold 4, RFCT70G352Y, build installed 08:24)

- Data: `DAILY_FOG_OBSERVED date=2026-11-11 src=NWS level=4->4 top=53.6 vis=0.125` (08:24:21).
- Worker renders draw it: 08:26:39 `origin=WORKER_FETCH` → `DAILY_FOG_DRAWN date=2026-11-11 level=4 top=53.6`
  for every daily widget.
- Every tap render after that omits it: 08:26:43 `USER_INTERACTION`, 08:26:47–58 `ACTION_REFRESH`
  (`DailyInteractionRenderer`, `path=refresh_action_cache_first`) → only `DAILY_FOG_DRAWN date=2026-11-09`.
- Screenshot 08:28: Monday shows its fog, Today none.

## Root cause

Two implementations of "past rows + live today":

| Path | Today's actuals |
|---|---|
| Worker / startup (`WidgetDataBundleLoader`, `WidgetStartupCoordinator`) | `DailyActualsStore` → `:shared` `DailyActualsAssembler` (live blend, low-coverage gate, previous-site donors, **today's observed fog**) |
| Taps (`DailyInteractionRenderer`) | Android-only `DailyActualsLoader` → `ObservationResolver` (live blend only) |

Today's fog exists only in the assembler (`withTodayFog`); `daily_history` holds today's fog only after
the recompute writes it, which the tap path does not read for today. So every tap repaints Today without
fog. The loader also lacks the assembler's day-start low gate and previous-site donors, so a tap render
can differ from a worker render on those too — the AGENTS.md "no parallel per-platform rule" violation.

## Fix

1. `DailyInteractionRenderer.loadData` reads daily actuals through `DailyActualsStore.getDailyActualsWithLiveToday`
   (the same call the worker path makes), passing the renderer's `sources` and the hourly rows it
   already loads (unified to the nearest site, as today).
   - With a `repository`: `repository.getDailyActualsWithLiveToday(...)`.
   - Without one (fallback construction): a `DailyActualsStore` built from the `database` DAOs and
     `WidgetPersonalStationWeightProvider`, as `ObservationRepository` builds it.
2. Delete `DailyActualsLoader` and its allowlist entry in `HourlyProximityQueryAllowlistTest`.
3. The cached `WidgetInteractionCache.Data.dailyActuals` keeps its shape (`DailyActualsBySource`).

## Tests

- Robolectric: the existing `DailyActualsLoader uses live today actuals…` test moves to the interaction
  renderer's `loadData` (live today still beats a persisted today row).
- New Robolectric test: foggy METARs this morning, no stored fog → `loadData(...).dailyActuals[NWS][today].observedFog`
  is foggy (fails on the old loader).
- Fold 4: install, tap Refresh / a day; `DAILY_FOG_DRAWN date=<today>` on tap renders and a screenshot.

## Docs

`arch/ARCHITECTURE.md`: the interaction path reads actuals through `DailyActualsStore` like the worker.

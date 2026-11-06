# Preemption settle repaints with a null repository (graph loses its actuals)

## User prompt

> Pixel 7 pro: indicators are missing. Should we add a 50% text font shrink if don't it?
> yes investigate the empty redraw

## Evidence (Pixel 7 Pro, 2026-11-06; `backups/20261106_145038_pixel_7_pro_2A191FDH300PPW`)

1. Screenshot 14:47: hourly widget 88 (Open-Meteo, NWS actuals) has no pink actual line, no
   station label, no "Actual temperature data from NWS", no forecast delta.
2. `app_logs`, twice after a package-replaced startup:

   | time | event |
   |---|---|
   | 14:46:36.6 | `WIDGET_PAINT origin=PROVIDER_ON_UPDATE` — startup fast path, `hours=19`, no actuals (by design) |
   | 14:46:36.9 | logcat `TIMED_REPAINT_DEFERRED trigger=STARTUP_FOLLOW_UP active=1` (the REFRESH interaction) |
   | 14:46:37.6 | `ACTION_REFRESH` paint: `obsQueryMs=630 rawObs=1067 hours=108` — good |
   | 14:46:39.9 | `WIDGET_PREEMPTION_SETTLE widgets=88,78,79` |
   | 14:46:40.8 | `ACTION_REFRESH` paint: `obsQueryMs=1 rawObs=0 obsRows=0 hours=19` — **empty** |
   | 14:48:17.5 | next worker paint, `rawObs=1068` — recovered after ~1.5 min |

   Same pair at 14:40:08 (good) / 14:40:11.5 settle / 14:40:12.3 (empty).

## Root cause

`TemperatureViewHandler.scheduleStartupFullGraphRefresh`: when the timed-repaint gate defers the
startup follow-up, it calls `WidgetPreemptionSettler.recordPreempted(appContext, listOf(id), null)`.
The settler later repaints via `renderWidgetFromCache(ctx, id, repository = null)`, so
`TemperatureGraphHoursLoader` runs `repository?.readObservationsInRange(...)` → null → no
observations → forecast-only graph. The daily handler degrades the same way
(`repository?.getObservationsInRange` for its header). `BaseWidgetActionReceiver` can also pass null
(`::repository.isInitialized` false). The null is silent: nothing logs it.

A 50% font step would not help — the labels report `no_text`, not `no_empty_band`.

## Proposed fix

1. **Settler resolves its own repository** — `WidgetPreemptionSettler.settle` uses
   `repository ?: resolveRepository(context)`, where `resolveRepository` is a test seam defaulting to
   the Hilt `RepositoryEntryPoint` (add `fun weatherRepository(): WeatherRepository`). One fix covers
   all three callers. `WIDGET_PREEMPTION_SETTLE` gains `repo=passed|resolved`.
2. **No silent null in the graph read** — `TemperatureGraphHoursLoader`, when actuals are not
   deferred but `repository == null`, logs `OBS_READ_NO_REPOSITORY widget=… source=…` (WARN) so a
   future caller that drops the repository is visible in `app_logs`.

## Tests

1. `WidgetPreemptionSettlerTest`: `settle(context, null)` renders with the resolved (non-null)
   repository; a passed repository is used as-is. Fails on current code (render receives null).
2. Device: install on the Pixel 7 Pro, reproduce (install → immediate REFRESH), confirm the settle
   paint has `rawObs>0` and the screenshot keeps the actual line and labels.

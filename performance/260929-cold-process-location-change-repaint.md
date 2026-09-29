# Cold-process location change: 5–12 s from Save to the cached site on screen

Status: **investigation plan, no fix yet.** Follows
`plans/260929-location-change-adopts-cached-new-site-under-banner.md`, which made the order right
(banner over the old graph until the new site is drawn) but not the speed.

## Symptom (Pixel 7 Pro, 2026-09-29, phone time UTC+2)

Switching to a site with a fresh, drawable cache should be a local read plus a repaint. Warm process:
under 1 s (21:47:58, 21:48:10). Cold process, shortly after the app was installed or launched:

| Switch | Save | Cache probe done | Repaint pushed | Save → drawn |
|---|---|---|---|---|
| → Mountain View (process ~16 s old) | 22:12:05.66 | 22:12:06.46 | 22:12:18.54 | **12.9 s** |
| → Kyiv (process ~15 s old) | 22:14:09.37 | 22:14:14.76 | 22:14:21.21 | **11.8 s** |

The job itself starts promptly (expedited; ~0.1–0.8 s). The time is inside the probe and the repaint.

## What the logs show

1. **Repaint gap with no log lines.** Mountain View: `HOURLY_LOAD caller=bundle` at 22:12:06.98, then
   nothing until `GPS_RESAMPLE trigger=paint` at 22:12:16.02 — **~9 s** inside
   `WidgetPaintCoordinator.refreshWidgetsFromCache` → `WidgetDataBundleLoader.load`, after the hourly
   load. Render itself then took ~2.5 s (`WIDGET_RENDER_PERF otherMs≈1600` per widget).
2. **Heavy hourly scan for fragmented sites.** Kyiv's probe read `current=5680 history=20168` rows
   across **13** input sites to stitch 480 (5.4 s, cold). Mountain View reads 439 + 840 across 2.
   The 13 sites around Kyiv look like coordinate fragmentation (see memory "Coordinate
   fragmentation"), not 13 real places.
3. **The probe and the repaint load the same hourly data twice** (`caller=location_change_probe`,
   then `caller=bundle`), back to back.

## Hypotheses, in order to test

1. The 9 s gap is the bundle's non-hourly work in a cold process: observation blend / actuals
   (`DAILY_RECOMPUTE`-class work), snapshots, current-temp resolve. JIT-cold and competing with the
   startup `onUpdate` storm (`performance/260910-post-install-cold-start-storm.md`).
2. Kyiv's cost scales with fragment count; merging or pruning the 13 sites would cut the probe.
3. The duplicate hourly load is a cheap win: hand the probe's rows to the repaint.

## Investigation steps

1. Add timing breadcrumbs inside `WidgetDataBundleLoader.load` (per stage, one `BUNDLE_PERF` line,
   matching `SYNC_PERF`'s shape) so the 9 s is attributable, not inferred.
2. Reproduce cold on the Pixel: `am force-stop` + `am start` ConfigActivity (memory: force-stop
   needs `am start`), wait 5 s, switch to a cached site; repeat warm for the baseline.
3. Query the Kyiv hourly fragments: `SELECT round(locationLat,3), round(locationLon,3), count(*)
   FROM hourly_forecasts WHERE abs(locationLat-50.4495)<0.1 GROUP BY 1,2`.
4. Decide from the breadcrumbs: share the probe's hourly rows with the repaint; defer non-visible
   bundle work (actuals recompute) until after the first paint; or prune fragments.

## Tests to add with any fix

- Robolectric: a location-change adoption loads hourly rows once (probe result reused).
- A `BUNDLE_PERF` line exists per bundle load (diagnostic kept, per the keep-diagnostics rule).

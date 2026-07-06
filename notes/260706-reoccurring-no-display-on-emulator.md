---
name: widget_blank_selfheal_render_ok
description: "Blank widget = stale RemoteViews / self-heal gap, not data; ACTION_REFRESH now always direct cache repaints; WIDGET_RENDER_OK breadcrumb"
metadata: 
  node_type: memory
  type: project
  originSessionId: 6c4ca13a-2054-4723-b21f-25a4b5b37d15
---

Recurring symptom: widget renders blank (glass background + a nav arrow, no temp/graph/days) while the app is healthy and the DB is full. **It is a stale-RemoteViews problem, not a data problem.** The launcher holds the last (blank/initialLayout) views; the provider process was frozen/cached so `onUpdate` never re-ran, and the widget stayed blank until the app was manually opened.

**Recovery gap that made it stick (fixed 2026-07-06):** `ScreenOnReceiver` self-heals on unlock by broadcasting `ACTION_REFRESH`, but off-charger it set `EXTRA_UI_ONLY` and `handleRefreshAction` routed that through `triggerUiOnlyUpdate` → a **WorkManager job**, which Android defers under the exact freeze/Doze condition causing the blank. The immediate direct repaint (`WidgetIntentRouter.renderAllWidgetsFromCache`) was gated behind `needsNetworkFetch` (charging AND stale), so it rarely ran.

**Fix #1:** `handleRefreshAction` now calls `renderAllWidgetsFromCache` **unconditionally** (direct render, cache-only ~0.5s, runs even while frozen) on every `ACTION_REFRESH`; the network branch still fires only when stale. All `ACTION_REFRESH` senders are low-frequency (unlock, observations/history refresh, one-shot deferred startup graph refresh in `TemperatureViewHandler`), so unconditional repaint is safe.

**Fix #3 (diagnostics):** unified `WIDGET_RENDER_OK` app_log breadcrumb at both full-render success points — `renderStartupWidgets` (`path=onUpdate`) and `WidgetIntentRouter.refreshWidget` (`path=<reason> action=<tag>`). Query on next blank occurrence:
`SELECT datetime(timestamp/1000,'unixepoch','localtime'), message FROM app_logs WHERE tag='WIDGET_RENDER_OK' ORDER BY timestamp DESC;`
If last event is a `path=onUpdate` long ago with no `onUpdate_entry`/`WIDGET_RENDER_OK` since → launcher dropped views / process frozen (confirms first-cause). Distinguishes that from a cancelled (`HOURLY_PAINT_TRACE ... _CANCELLED`, stranded Loading) or errored render.

Verified end-to-end on emulator-5554: install → `WIDGET_RENDER_OK path=onUpdate`; `am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider` → `WIDGET_RENDER_OK path=refresh_action_cache_first action=REFRESH`. Note: real weather DB is `databases/weather_database` (17MB + WAL); the backup script's `weather_widget.db` is a 0-byte legacy stub. `am broadcast APPWIDGET_UPDATE` is SecurityException-blocked, but our custom `ACTION_REFRESH` broadcasts fine.

Not yet done (deferred, offered): #2 — make `initialLayout` (`@layout/widget_weather`) show "Loading…/tap to refresh" instead of blank glass. Related: [[widget_dark_after_crash_bad_process_block]], [[widget_loading_after_test_run]], [[samsung_widget_dead_native_sigsegv]], [[crashes_native_not_in_db_proc_exit_logger]].

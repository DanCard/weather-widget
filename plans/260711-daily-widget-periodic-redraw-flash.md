# Daily widget redraws every ~20 min — diagnosis (and candidate fixes)

**Date:** 2026-07-11
**Device:** Samsung SM-F936U1 (`RFCT71FR9NT`), 5 widgets on home screen (4 DAILY, 1 TEMPERATURE)
**Complaint:** widget visibly redraws every x minutes — irritating, distracting. User sees it on
the **daily view**; the hourly view's now-line movement is *wanted* ("kind of cool to see it move").

## Why it redraws (from device evidence, no new logging needed)

Diagnosed entirely from `app_logs` pulled off the device (`backup_databases.py`) — the existing
`WIDGET_PAINT` / `WIDGET_LIFECYCLE` / `GraphRepaintGate` breadcrumbs told the whole story.

### Not the cause: the 2-minute UI ticker

While charging, the opportunistic UI-update alarm fires every ~2 min
(`UIUpdateIntervalStrategy.PLUGGED_IN_MAX_DELAY_MS`). But DAILY widgets **skip** those ticks —
logs show `WIDGET_PAINT ... caller=DAILY state=skipped_ui_only` with no push to the launcher
(`WidgetRenderer.shouldSkipDailyUiOnlyRepaint`). Only the TEMPERATURE view rebuilds on those
ticks (its `GraphRepaintGate` passes nearly every tick via `temp_changed` — the formatted temp
carries a decimal — and `now_drift` at the 4px threshold). That is the now-line movement the
user likes; **leave it alone**.

### The cause: background data-refresh paints are full `updateAppWidget()` replacements

Every worker-driven refresh (`WeatherWidgetWorker.updateAllWidgets`, both the post-fetch path
and `refreshWidgetsFromCache`) repaints **all widgets** with a full `updateAppWidget()`. A full
update makes the launcher tear down the widget's existing view tree and re-inflate it from
scratch, reloading the graph bitmap — a visible blink on Samsung's launcher even when the
rendered content is pixel-identical.

Observed full-paint times (2026-07-11 afternoon, `WIDGET_LIFECYCLE phase=worker_paint uiOnly=false`
+ launcher `onUpdate_entry`): **14:16, 14:36, 14:59, 15:15, 15:17** — roughly every 20 min, not
the 60-min charging fetch interval, because these stack:

- battery-aware scheduled fetch (60 min while plugged in)
- screen-unlock–triggered refresh (fetch if charging & data >30 min stale)
- hourly launcher `onUpdate` (14:15:26, 15:15:56)

### Bonus finding: post-fetch double paint

After the 15:16 fetch, every widget painted **twice within 3 seconds** (15:17:45–46 and
15:17:48; duplicate `WIDGET_RENDER_PERF` rows per widget) — the instant paint-from-cache
followed by the post-fetch repaint. Guaranteed visible double-blink once per fetch cycle.

## Key mechanism

Android has two widget-update APIs with very different visual costs:

| API | Launcher behavior | Visual |
|-----|-------------------|--------|
| `updateAppWidget()` | discards + re-inflates the whole view tree | visible flash |
| `partiallyUpdateAppWidget()` | patches existing views in place | seamless |

The app already uses the partial variant for the hourly header temp (why the 2-min now-line
ticks look smooth). All worker-driven repaints — including DAILY — use the full variant.

## Candidate fixes (BOTH approved by user 2026-07-11; implemented — see status at bottom)

1. **Partial-push worker repaints.** Thread a `partialPush: Boolean` from
   `WeatherWidgetWorker.updateAllWidgets` → `WidgetRenderer.updateWidgetWithData` → the four
   view handlers; at each handler's final data push use `partiallyUpdateAppWidget` when set.
   Safe because binders already set every view on every paint (the repo's "sticky visibility"
   discipline, guarded by the reapply() test pattern — partial apply *is* reapply semantics).
   Full pushes remain for hierarchy-establishing paths: `onUpdate` (boot/placement/hourly),
   resize, user interaction — these also keep the launcher's persisted RemoteViews fresh so a
   launcher restart never restores a stale widget. Add `push=partial|full` to `WIDGET_PAINT`
   log lines for future cadence audits.
2. **Dedupe the post-fetch double paint.** Skip the paint-from-cache when the follow-up fetch
   paint is seconds away (or skip the second paint when fetched data changed nothing visible).
   Smaller, independent fix; removes the most visible single artifact.

## Constraints from user

- Do **not** change hourly-view rebuild cadence (`GraphRepaintGate` thresholds / `temp_changed`
  string comparison stay as-is) — the moving now-line is desired behavior. An initial attempt
  to gate rebuilds on a ≥1° numeric temp delta was reverted at user request.
- Check with the user before making changes.

## Implementation (2026-07-11, both fixes)

### Fix 1 — partial pushes for worker repaints
- `WidgetViewHandler.updateWidget` + all four handlers gained `partialPush: Boolean = false`;
  each handler's final **data** push does `partiallyUpdateAppWidget` when set (warning/degenerate
  paths stay full). `WIDGET_PAINT` data lines now log `push=partial|full`.
- `WidgetRenderer.updateWidgetWithData(partialPush)` forwards to handlers;
  `WeatherWidgetWorker.updateAllWidgets` passes `partialPush = true` (covers post-fetch paints,
  `refreshWidgetsFromCache`, current-temp loop paints, uiOnly gate-passed rebuilds).
- Interaction paths (`WidgetIntentRouter`), provider `onUpdate`/startup, resize keep the default
  full push — those must (re)establish the launcher hierarchy and refresh its persisted
  RemoteViews (launcher restarts restore the last FULL push).

### Fix 2 — skip no-op current-temp repaints
- New pure `CurrentTempFetchPolicy.shouldSkipPostRunRepaint(policyBlocked, fetchFailed,
  attemptedSourceCount)`: skip when policy-blocked or when a successful run attempted 0 sources
  (repository freshness skip / all throttled — `refreshCurrentTemperature` returns attempted
  count; its fresh-skip returns 0). Failures still repaint (error indicators).
- `handleCurrentTempOnlyWork` logs `CURR_PAINT_SKIP` instead of calling
  `refreshWidgetsFromCache()` in those cases — kills the 15:17:48-style wave (fetch=3ms skip
  followed by a 2117ms repaint of every widget).

### Tests
- `CurrentTempFetchPolicyTest`: 4 new cases for `shouldSkipPostRunRepaint`.
- `DailyViewHandlerTest`: push-mechanism regression pair (partialPush=true → partial slot only;
  default → full slot only) via extended `mockAppWidgetManager` (now also captures
  `partiallyUpdateAppWidget`; mockk strictness means an unexpected partial push in any other
  test throws).
- Full `testDebugUnitTest` green; installed on Samsung + Pixel 15:52.

### NOT addressed (deliberate)
- The `onUpdate` → `ACTION_REFRESH` cache-first pair (waves 1–2, both full, ~1s apart on
  unlock/hourly onUpdate). ACTION_REFRESH full push is the blank-widget self-heal path
  ([[widget_blank_selfheal_render_ok]]) — deduping it risks re-breaking that. Revisit only if
  the user still sees flashes at unlock.

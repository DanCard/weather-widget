# Session summary — "Cloud data unavailable" on Tomorrow.io was a stale paint, not a data gap

**Date:** 2026-09-01 · **Plan:** `plans/260901-stale-source-paint-clobbers-hourly-graph.md`

## What was reported

Samsung Fold, widget 345, cloud view, source Tmrw (Tomorrow.io): "Cloud data unavailable".

## What it actually was

Tomorrow.io had 98/98 future hours with `cloudCover` at the active site, fetched 06:05:30. The
message was a background repaint drawing an empty graph over a correct one, 265ms after the user's
own render, and then sitting there for 40 minutes because nothing repaints an idle widget.

Diagnosed from `app_logs` (`HOURLY_SOURCE_MISS`, `HOURLY_SOURCE_RACE`, `CLOUD_COVER_GAPS`,
`TOGGLE_API_RENDER_OK`) at millisecond resolution, and confirmed on device: forcing a repaint with
the nav arrows brought the full Tomorrow.io curve straight back.

This is the 2026-08-08 hourly source-snapshot race ([[worker_hourly_source_snapshot_race]])
surviving in two places its original fix did not reach.

## What changed

**`HourlyForecastLoader`** — new pure `scopeForDisplaySources()`, so the scope a reload *requests*
and the scope `sourcesMissingFromLoad` checks it *against* cannot drift. `hourlySourceIds()` now
delegates to it.

**`WidgetPaintCoordinator.resolveEffectiveHourly`** — was a single check-then-reload; now a loop
bounded at `MAX_HOURLY_SOURCE_RACE_RELOADS = 2` that re-checks after each reload. The reload is
itself a ~1s query, and the observed failure was a toggle landing inside that window. Tracks the
scope requested rather than the sources present in the returned rows (a source may legitimately hold
zero rows, which would otherwise loop to the bound). Empty reload still keeps the caller's rows and
now also stops retrying.

**`WidgetRenderer.shouldSkipStaleSourcePaint`** — new pure predicate, modelled on the existing
`shouldSkipDailyUiOnlyRepaint`. Drops a repaint that provably cannot draw the display source, but
only when it is background-origin (WORKER_FETCH / WORKER_CACHE / UI_ONLY), on an hourly-sourced view,
for a widget that already has a real body this process. USER_INTERACTION and ACTION_REFRESH still
paint: there a missing source is a genuine upstream gap and the message is the honest output.
Logs `WIDGET_PAINT ... state=skipped_stale_source`.

The two layer: fix 1 shrinks the race window, fix 2 makes whatever survives it harmless.

## Verification

12 new tests, all passing; both regression oracles shown failing against pre-fix behaviour. On
device, 14 rapid source toggles across two forced syncs produced `attempt=1/2` repairs that
re-checked clean, with no source-miss and no empty paint. Full detail in the plan's Verification
section.

## Not changed

The message itself is still correct output when a source genuinely has no rows — this fix narrows
*when* it is shown, not what it says.

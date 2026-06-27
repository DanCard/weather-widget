# Daily widget stuck on "Loading…" — durable fix

Date: 2026-06-26

## Symptom

After an instrumented-test run / repeated `installDebug` (and, in production, a Play Store
app update), the home-screen DAILY widget showed the current-temp header but the graph area
stayed on the "Loading…" placeholder. `dumpsys appwidget` showed the weather widget with
**`views_bitmap_memory=0`** while other widgets on the same launcher had ~1–6 MB.

## Root cause

`WidgetRenderer.updateWidgetWithData`'s `ViewMode.DAILY` branch returned early on `uiOnly`
repaints — a sound optimization (the daily graph has no per-minute moving element, so the
~2-min now-tracking tick skips the expensive rebuild). But it assumed a graph bitmap already
existed. After a force-stop / fresh process / app update the widget shows the placeholder and
the **first** update is often UI-only (e.g. `ACTION_REFRESH` takes the UI-only path when cached
data is fresh). The early-return then never set the graph bitmap → stuck "Loading…".

## Fix

Gate the skip on a process-scoped "already fully painted" set:

- `WidgetRenderer.shouldSkipDailyUiOnlyRepaint(uiOnly, alreadyPaintedThisProcess)` — pure,
  returns true only when `uiOnly && alreadyPaintedThisProcess`.
- The DAILY branch now skips only when the widget is in `fullyPaintedDailyWidgetIds`; otherwise
  it falls through to a full `DailyViewHandler.updateWidget`. The set is populated after each
  full daily paint and cleared implicitly when the process dies.

Result: the first update of a fresh process always paints the graph (even a UI-only one), so the
widget **self-heals** instead of stranding on "Loading…".

Files: `app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`.

## Tests

`app/src/test/java/com/weatherwidget/widget/WidgetRendererDailyUiOnlyRepaintTest.kt`:
- Unit (pure): the skip decision truth table.
- Integration (Robolectric, drives `updateWidgetWithData`): a fresh-process UI-only repaint
  pushes a real daily view (`graph_view` VISIBLE, not "Loading…") and marks the widget painted;
  an already-painted UI-only repaint skips (no `updateAppWidget` push).

## Verified end-to-end

Installed on emulator, `am force-stop com.weatherwidget` (reproduces the stuck state), then fired
only a UI-only `ACTION_REFRESH`. `views_bitmap_memory` for widgets 52/53 went `0 → ~960 KB` and the
graph rendered — no manual nav needed.

See memory `widget_loading_after_test_run.md` for the diagnostic fingerprints.

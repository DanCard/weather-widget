# Stale-source worker paint clobbers a correct hourly graph

**Date:** 2026-09-01
**Device:** Samsung SM-F936U1 (Fold), widget 345, CLOUD_COVER view, source TOMORROW_IO
**Symptom:** "Cloud data unavailable" painted over a graph the user had just correctly rendered,
and it stayed on screen for 40+ minutes.

## Problem

Tomorrow.io had complete cloud data the whole time. From the device DB:

```
TOMORROW_IO  37.417,-122.089   98 rows   98 with cloudCover   fetched 06:05:30
```

The widget was showing an empty-graph message against fully populated rows.

## Root cause

This is the 2026-08-08 hourly source-snapshot race
([[worker_hourly_source_snapshot_race]], `plans/260814-hourly-source-snapshot-race-code-review.md`)
surviving in two places the original fix did not close.

Millisecond timeline from `app_logs`:

| Time | Event |
|---|---|
| 06:49:01 | Cloud view selected → `SYNC_START reason=cloud_while_viewing force=true` (ran 20.4s) |
| 06:49:01 | Worker snapshots hourly source scope while display source is **NWS** → SQL scoped `NWS\|Generic` |
| 06:49:13 | `TOGGLE_API_RENDER_OK from=NWS` → Open-Meteo |
| 06:49:17 | `TOGGLE_API_RENDER_OK from=OPEN_METEO` → Silurian |
| 06:49:18.76 | Repair fires: `HOURLY_SOURCE_RACE loaded=NWS\|Generic atPaint=SILURIAN\|NWS missing=SILURIAN reloadedRows=467` |
| 06:49:19.57 | `TOGGLE_API_RENDER_OK from=SILURIAN` → **TOMORROW_IO**; user's own paint is correct (`push=full`) |
| 06:49:19.81 | Worker paints from the pre-toggle list: `CLOUD_COVER_GAPS source=TOMORROW_IO missing=19 total=19 sourceMissingFromLoad=true` |
| 06:49:19.84 | `WIDGET_RENDER_PERF hourlyCount=0 source=TOMORROW_IO`, pushed `partial` — **265ms after the good frame** |

Decisive line:

```
WARN HOURLY_SOURCE_MISS widget=345 view=CLOUD_COVER origin=WORKER_FETCH
     displaySource=TOMORROW_IO unified=467 present=SILURIAN:240,NWS:227
```

467 rows in hand, zero for the display source.

Two independent defects:

1. **`WidgetPaintCoordinator.resolveEffectiveHourly` is a single check-then-reload.** It re-reads the
   display sources once, reloads, and paints. A toggle landing inside the reload's own ~1s window
   (exactly what happened: reload finished 06:49:18.76, toggle at 06:49:19.57) leaves it stale again
   with no second check.
2. **`WidgetRenderer` detects the miss and paints anyway.** `sourceMissingFromLoad` (line 373) is
   threaded to the handlers, but it only suppresses the *gap detector's* forced sync. The empty graph
   is still rendered and pushed, overwriting a correct user-initiated frame.

The code comment at `WidgetRenderer.kt:369` claims this "self-heals on the next paint". It does not:
nothing repaints an idle widget. Confirmed on device — the message sat from 06:49 until nav-arrow
taps forced a render, at which point the full Tomorrow.io curve appeared immediately.

## What will change

### Fix 1 — bounded re-check loop (`WidgetPaintCoordinator.kt`)

`resolveEffectiveHourly` becomes a loop bounded at `MAX_HOURLY_SOURCE_RACE_RELOADS = 2`:
read paint sources → if none missing, return → reload scoped to those sources → repeat.
Tracks the scope actually *requested* (not the sources present in the returned rows, since a source
may legitimately have zero rows). Keeps the existing "empty reload keeps the original list" guard.
The common path (no toggle in flight) still does zero extra queries.

Scope construction moves to a new pure `HourlyForecastLoader.scopeForDisplaySources()` so the
coordinator and the loader cannot disagree about what "covers these sources" means.

### Fix 2 — never let a background repaint paint a known-stale source (`WidgetRenderer.kt`)

New pure predicate, modelled on the existing `shouldSkipDailyUiOnlyRepaint`:

```kotlin
shouldSkipStaleSourcePaint(sourceMissingFromLoad, viewMode, origin, hasPaintedBody)
```

Skips the paint when **all** hold:
- `sourceMissingFromLoad` — the loaded set provably has no rows for this source;
- the view is one of TEMPERATURE / PRECIPITATION / CLOUD_COVER (DAILY does not consume
  `sourceFilteredHourly`);
- `origin` is background — WORKER_FETCH, WORKER_CACHE, UI_ONLY. **USER_INTERACTION and
  ACTION_REFRESH still paint**: there, a missing source is a genuine upstream gap and the user must
  see the message;
- `hasPaintedBody` (`WidgetPushDispatcher.hasFullPushedThisProcess`) — otherwise a widget still on
  the "Loading…" placeholder would be stranded, the same trap `shouldSkipDailyUiOnlyRepaint` guards.

Logs `WIDGET_PAINT ... state=skipped_stale_source` so the skip is visible in `app_logs`.

The two fixes layer: fix 1 shrinks the race window, fix 2 makes whatever survives it harmless.

## Tests

| # | Test | Kind | Asserts |
|---|---|---|---|
| 1 | `no reload when no source is missing` | integration | `load` never called; original rows returned |
| 2 | `single toggle reloads once` | integration | one `load`; reloaded rows returned |
| 3 | `toggle during the reload triggers a second reload` | integration | **the observed bug**: source changes between load 1 and load 2; final rows cover the final source |
| 4 | `reload attempts are bounded` | integration | source changes every load; exactly `MAX` loads, no hang |
| 5 | `empty reload keeps the original rows` | integration | transient DB miss must not blank the graph |
| 6 | `background repaint skips when the source is missing` | unit | true for WORKER_FETCH / WORKER_CACHE / UI_ONLY |
| 7 | `user interaction paints the genuine gap` | unit | false for USER_INTERACTION / ACTION_REFRESH |
| 8 | `daily view never skips` | unit | false for ViewMode.DAILY |
| 9 | `unpainted widget never skips` | unit | false when `hasPaintedBody` is false |
| 10 | `covered source never skips` | unit | false when `sourceMissingFromLoad` is false |

Tests 3 and 6 are the regression oracles; both must be shown failing against the current code.

## Verification

**Implemented 2026-09-01.** Both fixes landed as planned; no scope changes.

### Unit / integration tests — 12 new, all passing

```
WidgetPaintCoordinatorSourceRaceReloadTest  6 tests  PASSED
WidgetRendererStaleSourcePaintTest          6 tests  PASSED
./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.*"   BUILD SUCCESSFUL
```

Both regression oracles were shown failing against the pre-fix behaviour, not just passing after:

- With `MAX_HOURLY_SOURCE_RACE_RELOADS = 1` (the old single-shot repair),
  `a toggle landing inside the reload triggers a second reload` fails with exactly the observed
  symptom — `source=SILURIAN` rows returned while TOMORROW_IO is on screen.
- With `shouldSkipStaleSourcePaint` stubbed to `false` (the old always-paint behaviour),
  `background repaint with no rows for the display source is skipped` and
  `every background origin is skippable` both fail.

### On device (Samsung SM-F936U1, widget 345)

Installed build verified by dex inspection (`skipped_stale_source` and `source_race_reload` present
in `classes18.dex`) — the first repro attempt ran against a process predating the install and was
discarded.

Forced sync + 8 rapid source toggles (~2s apart, spanning the fetch):

```
07:05:29.024  HOURLY_SOURCE_RACE  attempt=1/2 loaded=TOMORROW_IO|NWS|Generic
              atPaint=OPEN_METEO|NWS missing=OPEN_METEO staleRows=395 reloadedRows=466
```

The repair fired, reloaded once, and the **re-check then found the source covered and stopped** —
no `attempt=2/2` line. Across 14 toggles over two forced syncs there was no `HOURLY_SOURCE_MISS`, no
`CLOUD_COVER_GAPS ... sourceMissingFromLoad=true`, and no empty-graph paint. Screenshots before and
after show a fully rendered graph throughout.

`skipped_stale_source` did not fire on device, which is the expected outcome: fix 1 closes the
window before fix 2 is needed. Fix 2 is the backstop for a toggle faster than the query, which is
not reliably reproducible by hand — its coverage is the unit tests above.

### Note on the original diagnosis

The pre-existing comment "Self-heals on the next paint" (`WidgetRenderer.kt`) was wrong and is now
superseded: nothing repaints an idle widget, and the empty frame sat on screen from 06:49 until
nav-arrow taps forced a render 40 minutes later.

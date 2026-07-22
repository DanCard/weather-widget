# Widget stranded on the bare layout after a launcher cache drop — fix options

**Date:** 2026-07-22
**Status:** Options for discussion. Instrumentation and a deterministic reproduction are committed
(`4ba47019`, `8a858128`); no fix chosen yet. **A real defect is proved; that it caused this
particular outage is NOT** — see "What is not proved" below before treating the options as a fix for
the reported symptom.
**Scope:** Delivery of RemoteViews to the launcher. Not a data-layer or rendering bug — the app had
correct data and rendered it correctly throughout.

## Symptom

Samsung SM-F936U1, widget 345 (574x401dp, 10 columns, DAILY/GRAPH). For ~30 minutes the widget
showed a **live header** (`78.2° +4.2`, `NWS`) over a **body at XML defaults** (`Today` / `--°` /
`--°`, no graph). Reported by the user as "Samsung display not working". Self-healed at 13:40:41.

Third occurrence of this shape; see `summaries/260714-widget-partial-push-stale.md` for the first.

## In plain terms

The app does not draw on the home screen directly. It builds a picture, hands it to the launcher,
and can hand it over two ways:

- **Full** — the entire picture, replace what you have
- **Partial** — only the bits that changed, patch your copy

Partial is cheaper, so routine updates use it. But a patch only works if the launcher still *has* the
previous picture to patch. If the launcher has discarded its copy, the patch lands on nothing and is
silently thrown away.

The app tracks "have I sent a full picture yet?" against **its own** process lifetime, not the
launcher's. The launcher can discard its copy at any time and nothing in Android tells the app. So
the app can sit there believing the launcher has a picture, sending patch after patch, every one
discarded — which is what a widget showing nothing but placeholder dashes looks like.

## Timeline (from `app_logs`, device pulled same day)

| Time | Event |
|------|-------|
| 12:15:19 | `GPS_RESAMPLE outcome=healed` — site moves home (`37.4168,-122.0890`) → Garfield Park (`37.4240,-122.0884`). User is playing basketball. |
| 13:07:50 | `widget=345 caller=TEMPERATURE state=data push=full` — a full push lands, `dataLoc` still home |
| 13:10:45 | `dataLoc` catches up to the park |
| 13:11:22 | `CURR_FETCH_LOOP_STOP reason=policy_blocked plugged=false interactive=false` — **app goes idle, logs nothing for 29 min** |
| ~13:35 | User returns, plugs in. Widget is blank. |
| 13:36 | Screenshot: live header, body at XML defaults, no graph |
| 13:40:23 | `CURR_FETCH_WORK_START isPlugged=true isInteractive=true` — app wakes |
| 13:40:26 | `push=partial` — **no effect** |
| 13:40:34 | `push=partial` — **no effect** |
| 13:40:41 | `caller=DAILY push=full` — **heals** |

Of the ~30 min outage, ~29 were the app simply not running. The app-side defect accounts for the
~18s between wake and heal, and — more importantly — for the fact that recovery depended on an
unrelated DAILY full push happening to come along.

## Ruled out

**"A full TEMPERATURE push blanks the daily body."** Proposed and disproved the same day. The
reasoning was sound on paper: `TemperatureViewHandler.handle()` builds a fresh `widget_weather` tree
(`TemperatureViewHandler.kt:169`) and pushes it (`:188`) without passing `bodyComplete`, taking
`WidgetPushDispatcher.push`'s `bodyComplete = true` default (`WidgetPushDispatcher.kt:112`) — unlike
the `TEMPERATURE_HEADER` path, which correctly passes `false` (`:377`). That inconsistency is real
but is **not this bug**: a full TEMPERATURE push still binds `graph_view` with a bitmap, so it
cannot produce the observed no-graph widget.

**`--°` is not a usable signal.** Robolectric dump at 574dp:

| state | `day2_high` | `graph_view` | bitmap |
|---|---|---|---|
| bare XML inflate | `--°` VISIBLE | GONE | none |
| daily wide render | `--°` VISIBLE | VISIBLE | yes |
| temperature wide render | `--°` VISIBLE | VISIBLE | yes |

The `day2_*` TextViews read `--°` in *every* state including both healthy ones — in GRAPH mode the
day columns are painted into the `graph_view` bitmap. Assert on **`graph_view` visibility + non-null
drawable**; anything keyed on `day2_high` passes vacuously.

## The defect

`WidgetPushDispatcher.fullPushedThisProcess` is scoped to the **app's** process. The launcher's
cached tree can be dropped independently. After such a drop the app still believes the widget is
backed by a full push, so every complete-body repaint goes out **partial** — and
`partiallyUpdateAppWidget` is documented to be ignored until the widget has received a full update.
The widget stays on the XML defaults until some unrelated path happens to push full.

Reproduced deterministically in `app/src/test/java/com/weatherwidget/widget/LauncherCacheDropRecoveryTest.kt`
via a `FakeLauncher` (`apply` = `updateAppWidget`, `reapply` = `partiallyUpdateAppWidget`, partials
ignored until a full lands, `dropCache()` = host loses its tree). Test
`KNOWN DEFECT - partials after a launcher cache drop are all discarded` asserts current behaviour.

**No detection is possible.** Nothing in the AppWidget API reports whether the host still holds our
views — `getAppWidgetOptions()` returns size, not view state, and host callbacks belong to the
launcher. Every option below is a blind heuristic.

## What is not proved

The defect above is real and reproduced. **That it caused this outage is not established**, and two
facts sit awkwardly with that story:

**1. Most of the outage was simply the app not running.** It went idle at 13:11 and executed no code
until 13:40. The defect accounts for the ~15s of wasted partials after wake — not the ~29 min before
it. Fixing the defect would have shortened the visible outage by seconds, not minutes.

**2. The header was populated, which a full cache drop does not explain.** If the launcher had
discarded its tree, the header should have been at XML defaults too. It showed `78.2° +4.2` and
`NWS`. Possible readings, none confirmed:

- The header text is left over from a push that landed *before* the tree was lost, and "live" is an
  illusion — the last `CURR_TEMP_RESULT` was 13:11:22 and the screenshot was 13:36, so a 25-minute-old
  value would look plausible on screen.
- The framework's RemoteViews cache survived and only the launcher's *rendered view* was recycled,
  so a different (and only partially understood) recovery path applies.
- Something re-bound the header specifically. No log row supports this.

Reading 1 is the cheapest explanation and fits the timestamps, but nothing in `app_logs`
distinguishes it from the others.

**Why it stayed murky:** the evidence window closes over exactly the interesting moment. `app_logs`
has nothing between 13:11:22 and 13:40:23 — the 29-minute blind spot in which the launcher lost its
copy. Everything about the drop itself is reconstruction from the two edges.

**Consequence for the options.** A and B are worth doing regardless: they close a genuine hole
cheaply, and are correct whichever reading above holds. But neither can be sold as a guaranteed fix
for the reported symptom until an occurrence is captured with the new `WIDGET_PUSH` breadcrumbs in
place. Do not close this out on a green test alone.

## Options

### A. Always full for complete-body pushes

Drop the partial path whenever `bodyComplete = true`. Header-only partials stay partial — promoting
those blanks the body they never populated (`WidgetPushDispatcher.kt:54-56`).

- **Cost is smaller than it looks.** Complete-body partials already build a full `widget_weather`
  tree and carry the graph bitmap, so partial vs full is an *identical* Binder payload; the only
  saving is launcher-side re-inflation. Volume goes from ~400-660 fulls/day to ~1,500-2,000.
- **Correct regardless of the host-side mechanism** — never depends on the host having anything
  cached. Given the open question below, that robustness is worth a lot.
- **Risk:** `updateAppWidget` re-inflates; at ~1/min during active hours flicker may become visible.
- **Effort:** smallest. One branch, plus inverting the known-defect assertion.

### B. Promote the first complete-body push after a gap — *recommended*

Track last-push time per widget; force the next complete-body push to full when the gap exceeds a
threshold (~15 min).

- **Rationale:** a cache drop can only have happened while we weren't pushing. A partial that lands
  is itself proof the host's tree is alive, so uncertainty only enters across a silence.
- **Fits the timeline exactly.** Pushes ran ~every 2.5 min per widget, went silent 13:11→13:40, then
  resumed. The 13:40:26 push is promoted, healing ~15s after wake — and, critically, without
  depending on the DAILY full at 13:40:41 that happened to follow.
- **Effectively "promote on wake from idle", expressed as elapsed time** rather than by enumerating
  wake events (charging loop, opportunistic job, screen unlock, `onUpdate`). Covers wake paths that
  don't exist yet, which is where this class of fix usually rots.
- **Cost:** near zero. One timestamp per widget; a handful of promotions per day.
- **Gap in the argument:** a drop during a *busy* period strands us until the next quiet spell.
  Strictly better than today, not airtight.
- **Shape:** pure `shouldPromoteAfterGap(lastPushMs, nowMs, thresholdMs)` beside the existing
  `shouldPromoteToFull`, unit-testable without a Context.

### C. Periodic forced full

Force a full every N pushes or T minutes regardless of gaps.

- Bounds worst-case blankness even during busy periods — B's blind spot.
- Pays the cost continuously rather than only when uncertain, and N/T is a tunable that will be
  wrong. Only worth layering on B if a busy-period drop is ever observed.

## Recommendation

**B, with A as the fallback.** B is cheap, matches the observed failure precisely, and carries no
flicker risk. If the on-device `WIDGET_PUSH` breadcrumbs later show a drop *without* a preceding
gap, B is disproven and A becomes the answer — and we would know, because every full push is now
recorded.

## Open question that could change the choice

**No `onUpdate` fired at 13:40.** If `AppWidgetService` had lost its RemoteViews cache it should
have called `onUpdate` to rebuild. That suggests the framework cache survived and the *launcher's
rendered view* was recycled instead — a somewhat different failure than `FakeLauncher` models.

A and B are robust to both readings. C's tuning would depend on which is true. Resolving it needs
on-device evidence, not more tests: Robolectric encodes the "partials ignored until full" rule
(it lives in `AppWidgetService`, not `RemoteViews`), so a green test confirms the app honours the
documented contract but is not evidence that Samsung's host obeys it.

## Supporting work already committed

- `WidgetPushDispatcher.shouldPersist` now persists **every** full push, not just the first per
  widget per process — the previous rationing is why this investigation had no row for the push that
  mattered. Fulls run ~400-660/day (~2% of app_logs volume), so they are not rare.
- `app_logs` retention is tiered: `capUnprotectedToNewest` (50k, telemetry) and
  `capProtectedToNewest` (25k, `WIDGET_PUSH`/`WIDGET_PAINT`/`WIDGET_LIFECYCLE`). A single 50k cap
  against ~32k rows/day was trimming the table to 38h despite a 72h age policy, aging out the
  breadcrumbs with it.
- `CurrentTempTouchRoutingRoboTest` gained four tests pinning `graph_view` as the discriminating
  signal, including a tripwire that fails if a full TEMPERATURE push ever stops binding the graph
  (which would make the disproved theory live again).

## Definition of done

Two separate bars, and the first does not imply the second.

**Defect fixed** — flip `KNOWN DEFECT - partials after a launcher cache drop are all discarded` in
`LauncherCacheDropRecoveryTest` to `assertTrue(launcher.isBound)` and rename it. Full unit suite
green (1,582 at time of writing).

**Symptom fixed** — an occurrence captured on-device *with* the new `WIDGET_PUSH` breadcrumbs, showing
a promoted full push immediately after wake instead of the wasted partials seen at 13:40:26 and
13:40:34. Until that exists, per "What is not proved", we do not know that the defect was the cause;
a green test proves the hole is closed, not that it was the hole we fell through.

## Not shipped to the device

The Samsung runs release build 1.0.2; a debug build has a different signature and installing would
require an uninstall, destroying widget instances and forecast-snapshot history. None of this
instrumentation helps until it rides a release build.

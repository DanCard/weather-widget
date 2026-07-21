# Desktop Observations window showed stale readings; + @Category enforcement for :desktop and :shared

**Date:** 2026-07-20
**Plan:** [plans/260720-desktop-observations-window-stale.md](../plans/260720-desktop-observations-window-stale.md)
**Status:** Implemented & verified; distributable rebuilt and restarted

## Problem

User report: "Desktop: station observations seems to be showing stale info."

The DB was fine. Pulled live at 19:58: NWS stations fetched 19:55:08 with readings as recent as
19:47, on a steady ~10-minute cadence (`19:55, 19:45, 19:35, 19:25, 19:15, 19:05`). Fetching and
storage were healthy — the staleness was entirely in the UI.

The Stations window was a **one-shot snapshot**. Its only reload trigger was
`LaunchedEffect(currentSource, logFilter)` — both *user-input* keys — so it re-read the DB when the
user touched a control and never when the data changed. No timer, no push subscription, no reload on
re-show. Left open, it displayed the DB as of the moment it was opened, indefinitely.

The decisive evidence was the asymmetry with the main popup, which wires **three** independent paths
into `reloadCachedForecast` (socket push `Main.kt:262`, `.data-updated` watcher `:570`, resume-aware
fallback tick `:377`) plus a reload-on-show (`:269`). The observations window had none of the four.

Second-order: `ObservationOrigin.of(nowMs = System.currentTimeMillis())` was evaluated during
composition, so with no recomposition the clock never advanced. A station aging past the 3h
`BLEND_MAX_AGE_MS` while the window sat open kept rendering as a live `(API)` contributor with a
temperature — even though the blend had already decayed its weight to zero. The UI could not
self-report the very staleness the user noticed.

## What changed

- **Reload on the real data signal.** `ObservationsWindow` takes `dataUpdateCount` — the popup's
  existing consolidated "DB changed" counter — and keys its reload on it:
  `LaunchedEffect(currentSource, logFilter, dataUpdateCount, showRequestId)`. That inherits all three
  daemon paths at once, since each already terminates in `dataUpdateCount++`; no fourth refresh
  mechanism was added. Including `showRequestId` also fixes a distinct bug: raising an
  **already-open** window from the tray (`obsShowRequestId++`) previously called `toFront()` without
  reloading. (A *closed* window drops out of composition, so reopening always did reload — which is
  why this reproduced only for a window left open.)
- **Ages advance.** Minute-aligned ticker (`AGE_TICK_MS = 60_000L`) drives `nowMs` state, threaded
  through `ObservationList(observations, useCelsius, nowMs)` into `ObservationOrigin.of(...)`.
  Display-only: it changes whether the badge tells the truth about the blend, not the blend math.
- **Pure seam.** The filter/group/sort chain moved out of the `@Composable` into
  `visibleStationRows(all, source)`. Implemented with `maxByOrNull { it.timestamp }` instead of the
  original `first()` — that was correct *only* because the DAO ends in `ORDER BY timestamp DESC`, a
  silent coupling that would have started showing up-to-24h-old temperatures if the clause were ever
  dropped.
- **`@Category` enforcement ported to `:desktop` and `:shared`** (requested mid-implementation),
  mirroring `:app`'s `validateUnitTestDurations`. Each module gets markers under
  `<module>/src/test/.../com/weatherwidget/test/category/` — same package and names in all three so
  `@Category` lines read identically — a `validate*TestCategories` task wired via
  `tasks.withType<Test>().configureEach { dependsOn(...) }`, `test{Short,Medium,Long}<Module>[Fresh]`
  bucket tasks, and a `testByDuration<Module>[Fresh]` aggregate. Rule: exactly one bucket per class,
  no unknown markers, no duplicates.
- **`AGENTS.md`** updated with the three-module requirement, thresholds, and a per-module task table.

### Bucketing

Assigned from measured wall time, not guessed: **Short <0.2s, Medium 0.2–2s, Long ≥2s.**

| Module | Classes | Tests | Buckets |
|---|---|---|---|
| `:desktop` | 22 | 181 | Long 3 (`DesktopStartupTest`, `DesktopUiTest`, `UiNotifyChannelTest`), Medium 4, Short 15 |
| `:shared` | 74 | 513 | Short 74 — Medium/Long empty |

`:shared` is all-Short because it is pure JVM logic with no Compose, socket, or emulator work: 513
tests in ~0.9s, slowest class 0.161s (`AppLogsContractTest`), nothing near a boundary. Its
Medium/Long buckets are kept for cross-module symmetry and future slower tests. Measuring mattered —
DB-touching `DesktopWeatherDaoTest` intuitively looks Medium but runs in 0.113s.

`scripts/unit-tests.sh` needed **no** change: it drives whole-module `:shared:test` / `:desktop:test`,
which still run everything and now fail fast on an uncategorized test.

## Tests & verification

- New `ObservationsWindowRowsTest` (7 tests): newest-per-station wins; synthetic `NWS_BLEND` /
  `NWS_MAIN` excluded under NWS but `<SOURCE>_MAIN` kept for non-NWS; `BLENDED` excluded;
  other-source rows excluded; sorted by distance. Counts read from the results XML (7 tests, 0
  failures, **0 skipped**), not inferred from a green BUILD line.
- **Buckets partition each suite exactly** — the check that catches a class belonging to *no* bucket:
  `:desktop` 15+4+3 = 22 classes / 134+11+36 = 181 tests; `:shared` 74+0+0 = 74 / 513+0+0 = 513. Both
  match their full-module runs.
- **Proved every guard can fail** (never trust a test not seen red):
  - missing `@Category` → `expected exactly one category bucket, found [none]`, BUILD FAILED
    (both modules);
  - two markers in one `@Category` → `more than one category in one @Category: [ShortDuration,
    LongDuration]`, BUILD FAILED;
  - bogus marker → `unknown category marker(s) [HugeDuration] — known: [LongDuration,
    MediumDuration, ShortDuration]`, BUILD FAILED;
  - reverting `visibleStationRows` to `rows.first()` → the oldest-first guard failed with
    `expected:<72.0> but was:<60.0>` — exactly the order-dependency regression it was written for.
- Verified an **empty** bucket task is a no-op, not a build failure (Gradle *does* fail a `--tests`
  filter matching nothing; category filtering does not).
- `:shared:test :desktop:test --rerun-tasks` green with both validators running ahead of them.
- Rebuilt and restarted via `scripts/buildStart-desktop.sh`; healthy two-process signature returned
  (pids 370529 + 370626).

## Ruled out

- `.groupBy{}.map{ first() }` was **not** returning stale rows in production — the DAO's
  `ORDER BY timestamp DESC` made it correct. Hardened anyway (see above).
- `NWS_MAIN` ("NWS: History Backfill") is 85h stale in the DB (last fetched 2026-07-17), but
  `ObservationSourceMatcher` filters it out of the list, so it was not what was on screen.

## Follow-ups / notes

- **Why `NWS_MAIN` is 85h stale is still unexplained** — filtered from this view, but worth a look.
- End-to-end confirmation that an open window visibly advances across a fetch boundary wants a human
  look; the automated coverage proves the transform, not the Compose wiring.
- Unifying the desktop and Android stations-list queries into `:shared` (see
  [[feedback_share_android_desktop_logic]]) — the two share the matcher but the Android side has its
  own location-scoped query (`getRecentObservationsNear`).
- Commit `4f75c3cf "Desktop: observations window with historical data display"` landed mid-session
  from outside this work; it contains the three observations fixes and the pre-status plan file. Its
  message does not describe the change. Verified it captured the correct implementation
  (`maxByOrNull`), not the temporary `first()` mutation used to prove the test could fail.
- Everything else (test categories, AGENTS.md, plan status, this summary) is uncommitted — user to
  decide on commit, and whether to split the observations fix from the test-category work.

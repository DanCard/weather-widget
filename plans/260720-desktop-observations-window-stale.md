# Desktop Observations window shows stale readings while left open

**Date:** 2026-07-20
**Goal:** The "Stations" window should reflect the live DB the whole time it is open, and its
staleness badges should age in real time — instead of freezing the snapshot taken at open.

## Symptom

User report: "Desktop: station observations seems to be showing stale info."

## Diagnosis — the data is fine, the window is a one-shot snapshot

The DB is fresh. Pulled from the live `~/.local/share/weather-widget/weather.db` at 19:58 local:

```
stationId        newest_obs           last_fetch           obs_age_h
KPAO             2026-07-20 19:47:00  2026-07-20 19:55:08  0.2
NWS_BLEND        2026-07-20 19:47:00  2026-07-20 19:55:08  0.2
AW020            2026-07-20 19:35:00  2026-07-20 19:55:08  0.4
KSJC             2026-07-20 19:35:00  2026-07-20 19:55:08  0.4
```

with a steady ~10-minute fetch cadence (`19:55:08, 19:45:06, 19:35:01, 19:24:58, 19:14:55, 19:04:53`).
So fetching and storage are healthy; the staleness is purely in the UI.

`ObservationsWindow.kt:175` has the only automatic trigger for `loadData()`:

```kotlin
LaunchedEffect(currentSource, logFilter) {
    loadData()
}
```

Both keys are **user-input** state (the source cycler button, the log-filter menu). The effect
therefore encodes "reload when the user fiddles with a control", never "reload when the data
changed". There is no timer, no push subscription, and no reload on re-show. Left open, the window
displays the DB as of the moment it was opened, indefinitely.

### The asymmetry with the main popup

The popup wires *three* independent paths into `reloadCachedForecast` (`Main.kt:236-271`, `:361-389`).
The Observations window has none of them:

| Path | Main popup | Observations window |
|---|---|---|
| Daemon socket push (`UiNotifyClient`) | `Main.kt:262` | — |
| `.data-updated` file watcher | `Main.kt:570` | — |
| Resume-aware fallback tick (30s tick / 10min reload) | `Main.kt:377` | — |
| Reload on re-show | `Main.kt:269` | — (its `showRequestId` effect only calls `toFront()`) |

That last row is a distinct second bug: `obsShowRequestId++` (`Main.kt:724`, `:823`) raises an
**already-open** window without reloading it. Clicking the tray entry to check the stations returns
the old snapshot with no indication it is old. (When the window is *closed*, `observationsVisible`
drops the composable from composition, so the next open does re-read — which is why this reproduces
only for a window left open.)

### Second-order: frozen `nowMs` freezes the staleness badge

`ObservationsWindow.kt:325-330` computes origin during composition:

```kotlin
val origin = ObservationOrigin.of(..., nowMs = System.currentTimeMillis())
```

Without recomposition `nowMs` never advances, so a reading cannot age into `Kind.STALE`. Since
`ObservationOrigin.BLEND_MAX_AGE_MS` is 3h and is the same constant the blend estimators decay to
zero at (see [[observation_origin_stale_badge]]), a station that drops out of the blend while the
window is open keeps rendering as a live `(API)` contributor with a temperature. The UI cannot
self-report the very staleness the user noticed.

## Ruled out

- **`.groupBy{}.map{ it.value.first() }` (`:160-161`) is correct.** `DesktopWeatherDao.getRecentObservations`
  (`DesktopWeatherDao.kt:879-886`) ends in `ORDER BY timestamp DESC`, so `.first()` per group is
  genuinely the newest row. Worth stating because the idiom is order-dependent and would silently
  select a ≤24h-old reading if that `ORDER BY` were ever dropped.
- **`NWS_MAIN` ("NWS: History Backfill") is 85h stale in the DB**, last fetched 2026-07-17 — but it is
  filtered out as a synthetic row by `ObservationSourceMatcher` (`:156-159`), so it is not what is on
  screen. Whether that row *should* be 85h stale is a separate question, out of scope here.

## Changes

### 1. Reload on the existing data-change signal — `ObservationsWindow.kt` + `Main.kt`

`dataUpdateCount` (`Main.kt:181`) is already the app's consolidated "DB changed" counter, bumped by
all three popup paths (`:247`, `:387`). Reuse it rather than adding a fourth refresh mechanism:

```kotlin
internal fun ObservationsWindow(
    ...
    showRequestId: Int = 0,
    dataUpdateCount: Int = 0,
    ...
)

LaunchedEffect(currentSource, logFilter, dataUpdateCount, showRequestId) {
    loadData()
}
```

Pass `dataUpdateCount = dataUpdateCount` at the call site (`Main.kt:643-653`). Adding
`showRequestId` to the keys fixes the raise-without-reload bug in the same stroke, mirroring the
popup's `LaunchedEffect(showRequestId)` contract.

This covers socket push, file watch, fallback tick, and re-show, because every one of them already
terminates in `dataUpdateCount++`.

### 2. Let the staleness badge age — `ObservationsWindow.kt`

Drive `nowMs` from state ticked once a minute, and thread it into `ObservationList`:

```kotlin
var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
LaunchedEffect(Unit) {
    while (true) {
        delay(60_000L - (System.currentTimeMillis() % 60_000L))
        nowMs = System.currentTimeMillis()
    }
}
```

Aligning to the minute boundary matches the existing interpolation ticker (`Main.kt:594-595`). One
minute is enough resolution for a 3h threshold and keeps the window idle-cheap
(cf. [[desktop_idle_cpu_already_solved]]).

`ObservationOrigin.of(...)` then takes this `nowMs` instead of calling `System.currentTimeMillis()`
inline. Note this is display-only: it does not change blend math, only whether the badge tells the
truth about it.

### 3. Extract a pure seam for the list transform

The filter/group/sort chain at `:151-162` currently lives inside a `@Composable`, which is why the
one-shot behavior was never caught by a test. Per [[testing-strategy]] (no mocking framework —
prefer pure-function extraction), lift it to an internal top-level function:

```kotlin
internal fun visibleStationRows(
    all: List<DesktopObservationEntity>,
    source: WeatherSource,
): List<DesktopObservationEntity>
```

The composable keeps only the DAO call and the `withContext` hop. Deliberately *not* pushed to
`:shared` yet — the Android stations list (`WeatherObservationsActivity.kt:297-304`) already shares
the matcher but has its own location-scoped query (`getRecentObservationsNear`, see
[[observations_list_location_scoping]]); unifying the two is a follow-up, not this fix.

## Tests

- **New pure unit test** for `visibleStationRows` (desktop module, alongside `DesktopUiTest.kt`):
  newest-per-station wins when several rows share a `stationId`; `NWS_BLEND` and `NWS_MAIN` synthetic
  rows are excluded; `stationType == "BLENDED"` excluded; rows from a non-selected `api` excluded;
  result sorted by `distanceKm`. Feed the input **in `timestamp DESC` order** to match the DAO
  contract, and add one case with input in ascending order asserting the newest still wins — that is
  the regression guard for the `.first()` order dependency called out under *Ruled out*.
- **`ObservationOrigin` staleness is already covered** by `ObservationOriginTest.kt`; no new coverage
  needed for the constant, only for the fact that the window now passes a moving `nowMs`.
- Re-run `DesktopUiTest`, `DesktopWeatherDaoTest`.

## Verification

```bash
./gradlew :desktop:test --tests "*DesktopUi*" --tests "*Observation*"
scripts/buildStart-desktop.sh
```

Then manually: open Stations, note the "Fetched" time, leave the window open across at least one
fetch boundary (~10 min, confirm with
`sqlite3 ~/.local/share/weather-widget/weather.db "SELECT MAX(fetchedAt) FROM observations"`), and
confirm the displayed "Fetched" time advances without touching the source cycler. Second check:
raise an already-open window from the tray and confirm it reloads rather than just coming forward.

## Out of scope

- Why `NWS_MAIN` history-backfill rows are 85h old.
- Unifying the desktop and Android stations-list queries into `:shared`
  (see [[feedback_share_android_desktop_logic]]) — worth a follow-up.
- The `Fetch Logs` tab's cap/filter behavior, which is unchanged.

---

## Implementation status — DONE (2026-07-20)

**Summary:** [summaries/260720-desktop-observations-stale-and-test-categories.md](../summaries/260720-desktop-observations-stale-and-test-categories.md)

All three changes landed, plus a mid-flight addition (§4) requested during implementation.

**1. Reload on the data-change signal.** `ObservationsWindow` takes `dataUpdateCount: Int = 0`;
the reload effect is now
`LaunchedEffect(currentSource, logFilter, dataUpdateCount, showRequestId)`. Wired at
`Main.kt` from the popup's existing counter.

**2. Ages advance.** `AGE_TICK_MS = 60_000L`, a minute-aligned ticker drives `nowMs` state, threaded
through `ObservationList(observations, useCelsius, nowMs)` into `ObservationOrigin.of(...)`.

**3. Pure seam.** `visibleStationRows(all, source)` extracted to a top-level `internal` function.
Implemented with `maxByOrNull { it.timestamp }` rather than the original `first()`, so correctness no
longer depends on the DAO's `ORDER BY`.

**4. `@Category` enforcement for `:desktop`** (added at user request mid-implementation, mirroring
the app module):
- Markers at `desktop/src/test/kotlin/com/weatherwidget/test/category/{Short,Medium,Long}Duration.kt`,
  deliberately reusing the app module's package/names so `@Category` lines read identically.
- `validateDesktopTestCategories` in `desktop/build.gradle.kts` — exactly one bucket per file,
  no unknown markers, no duplicates. Wired via `tasks.withType<Test>().configureEach { dependsOn(...) }`,
  so it also gates the plain `:desktop:test` that `scripts/unit-tests.sh` invokes.
- Bucket tasks `test{Short,Medium,Long}Desktop[Fresh]` + aggregates `testByDurationDesktop[Fresh]`.
- All 22 existing desktop test classes annotated. Buckets by measured wall time
  (Short <0.2s, Medium 0.2–2s, Long ≥2s): Long = `DesktopStartupTest`, `DesktopUiTest`,
  `UiNotifyChannelTest`; Medium = `DesktopBackfillIntegrationTest`, `DesktopNoHourlyDayClickTest`,
  `DesktopSynopticFallbackTest`, `DesktopBackfillChanceSnapshotTest`; Short = the other 15.
- `scripts/unit-tests.sh` needed **no** change: it runs whole-module `:desktop:test`, which still
  runs everything and now fails fast on an uncategorized test.

**5. `@Category` enforcement for `:shared`** (follow-up, also requested during implementation):
same port again — markers, `validateSharedTestCategories`, `test{Short,Medium,Long}Shared[Fresh]`,
and `testByDurationShared[Fresh]` in `shared/build.gradle.kts`. All **74** shared classes annotated.

Every one is `ShortDuration`: the whole module is 513 tests in ~0.9s, slowest class 0.161s
(`AppLogsContractTest`), because `:shared` is pure JVM logic with no Compose, socket, or emulator
work. Medium/Long are therefore **empty by design** — kept for symmetry across modules and for tests
that grow slower later. Verified an empty bucket task is a no-op, not a build failure.

### Verification performed

- `ObservationsWindowRowsTest` — 7 tests, 0 failures, 0 skipped (confirmed from the results XML, not
  just a green BUILD line).
- **Buckets partition each suite exactly.** `:desktop` 15+4+3 = 22 classes and 134+11+36 = 181 tests;
  `:shared` 74+0+0 = 74 classes and 513+0+0 = 513 tests. Both match their full-module runs. This is
  the check that catches a class silently belonging to no bucket.
- **Proved every guard can fail** (per [[feedback_keep_test_diagnostics]] / the project's habit of
  not trusting a green test that was never seen red):
  - desktop, missing `@Category` → `expected exactly one category bucket, found [none]`, BUILD FAILED;
  - desktop, two markers in one `@Category` → `more than one category in one @Category:
    [ShortDuration, LongDuration]`, BUILD FAILED;
  - shared, missing `@Category` → same `found [none]` failure;
  - shared, bogus marker `HugeDuration` → `unknown category marker(s) [HugeDuration] — known:
    [LongDuration, MediumDuration, ShortDuration]`, BUILD FAILED;
  - reverting `visibleStationRows` to `rows.first()` → the oldest-first test failed with
    `expected:<72.0> but was:<60.0>`, i.e. it catches exactly the order-dependency regression it was
    written for.
- `:shared:test :desktop:test --rerun-tasks` green with both validators running ahead of them.
- `AGENTS.md` updated with the three-module `@Category` requirement, thresholds, and task table.
- Rebuilt and restarted the distributable via `scripts/buildStart-desktop.sh`; confirmed the healthy
  two-process signature came back (pids 370529 + 370626).

### Left for a follow-up

- End-to-end confirmation that an open window visibly advances across a fetch boundary still wants a
  human look — the automated coverage proves the transform, not the Compose wiring.
- Note `4f75c3cf "Desktop: observations window with historical data display"` was committed mid-work
  by something other than this session; it contains changes 1–3 and the pre-status version of this
  plan. The category work (4, 5) and this status section are separate.

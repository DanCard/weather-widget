# Code Review: WidgetIntentRouter.kt (current-state structural pass)

Reviewed: 2026-07-28
File: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
Reviewed state: commit `d9a53253` (`WidgetIntentRouter.kt` is 1,234 lines and clean in the
worktree)

## Scope and prior work

This review evaluates the router after the fixes and extractions recorded in:

1. `plans/260728-widget-intent-router-code-review-fixes.md`
2. `plans/260728-widgetintentrouter-code-review-opus.md`
3. `plans/260728-widgetintentrouter-code-review-glm.md`

The previously implemented per-widget mutex, resize debounce, isolated multi-widget repaint,
interaction breadcrumbs, source-window parity, single-`now` graph rendering, and the extractions
of `SourceStalenessProbe`, `ResizeDiagnosticsLogger`, `CurrentTempStalenessLogger`, and
`DailyActualsLoader` are not re-raised as findings.

The remaining structural work is actionable in this review. It is not deferred cleanup: the
current class still combines interaction execution, state transitions, refresh policy, daily and
graph data assembly, and RemoteViews dispatch, and that coupling contributes directly to the
correctness findings below.

## Overall assessment

The interaction serialization and render-path diagnostics are materially stronger than in the
earlier revisions. The main remaining risk is the construction of `RefreshContext`:

1. It is not scoped to the widget being handled.
2. It combines coordinates selected from widget preferences with freshness taken from an unrelated
   globally newest database row.
3. It performs best-effort background scheduling in the critical path of the visible cache render.

There are also two places where the implementation does not satisfy its own concurrency claims:
pre-lock breadcrumb state can be stale, and the interaction cache does not coalesce concurrent
loads.

The file should be reduced to a thin public facade. The daily and graph render pipelines are
already cohesive enough to extract without inventing new abstractions.

## Findings

### F1 — Refresh context is not scoped to the target widget or rendered location [HIGH]

Locations: `resolveLocation` / `resolveRefreshContext` (`:193-228`) and
`renderAllWidgetsFromCache` (`:242-270`).

Every public interaction receives an `appWidgetId`, but `resolveRefreshContext` does not. It calls:

```kotlin
val latestWeather = forecastDao.getLatestWeather()
val loc = resolveLocation(context, forecastDao, latestWeather)
```

`resolveLocation` then gets coordinates through `ActiveLocationResolver.resolve`, which selects
the first configured widget location, while assigning `latestWeather?.fetchedAt` to those
coordinates. `ForecastDao.getLatestWeather()` is global:

```sql
SELECT * FROM forecasts
ORDER BY batchFetchedAt DESC, fetchedAt DESC
LIMIT 1
```

There are two independent correctness failures:

1. A tap on widget B can render using widget A's coordinates. This is especially visible in
   `renderAllWidgetsFromCache`: the loop passes a different ID to `refreshWidget`, but every
   iteration resolves the same first configured location.
2. Even with only one active location, a recent row retained from the previous location can be
   paired with the new configured coordinates. The stale gate then sees the old site's fresh
   timestamp and suppresses the fetch needed by the site actually being rendered.

The storage and configuration surface demonstrate that widget identity matters:
`WidgetStateManager.getWidgetLocation(widgetId)` is widget-scoped, and widget-add mode in
`ConfigActivity.saveChosenLocation` writes only the new widget's latitude and longitude. The
router must therefore either honor the target widget or the application must explicitly remove
per-widget location semantics. Silently selecting the first widget is not a valid middle state.

**Required fix:**

1. Make `resolveRefreshContext` accept `appWidgetId`.
2. Resolve `WidgetStateManager.getWidgetLocation(appWidgetId)` first. Retain
   `ActiveLocationResolver` only as the documented fallback for a widget with no stored location.
3. Obtain freshness for the resolved physical site, never from unfiltered
   `getLatestWeather()`. Add a location-scoped DAO query or use the site/source-scoped successful
   fetch metadata described in F2.
4. Thread `appWidgetId` through every call, including each iteration of
   `renderAllWidgetsFromCache`.
5. Add a regression test with two bound widgets at different coordinates and a newer database row
   at only one site. The target widget must render/query its own site, and the other site's row must
   not make its stale decision fresh.

This change also requires checking `WeatherWidgetWorker`'s first-configured-location behavior. If
multiple widget locations are supported, fixing only the interaction renderer would still leave
the worker fetching one site. That companion audit is part of this finding, not optional follow-up.

### F2 — Generic interaction staleness uses content age, not successful-check age [MED]

Locations: `resolveLocation` (`:206-212`), `resolveRefreshContext` (`:215-227`), and
`RefreshScheduler.refreshIfStale`.

`LocationResult.fetchedAt` comes from a forecast row and is passed to the four-hour stale-data
gate. `FetchMetadata` documents why that is not a fetch-success clock: unchanged provider
responses do not rewrite forecast rows, so row `fetchedAt` describes content age rather than the
last successful check.

Consequently, once an unchanged forecast row is four hours old, each interaction reason can
enqueue another forced refresh after its 30-second debounce even if the provider was checked
successfully moments ago. `ExistingWorkPolicy.KEEP` prevents cancellation and duplicate work only
while an existing worker is pending/running; it does not correct the next stale decision after that
worker finishes.

The API-toggle path already has the correct model: `SourceStalenessProbe` considers the maximum of
the newest row timestamp and `FetchMetadata.getLastForecastSourceSuccessTime(...)`.

**Required fix:**

1. Replace `LocationResult.fetchedAt` with a clearly named freshness value derived for the target
   widget's displayed source and resolved site.
2. Prefer site/source-scoped successful-check time, with location-scoped row age as the bootstrap
   fallback when metadata is absent.
3. Rename `RefreshScheduler.refreshIfStale(latestFetchedAt = ...)` to describe the semantic value it
   receives, such as `latestSuccessfulOrContentAtMs`.
4. Add a pure decision test proving that an old unchanged row plus a recent successful check does
   not enqueue, and a Room/integration test proving that success metadata from another site does not
   suppress the target site's refresh.

### F3 — Best-effort refresh scheduling can abort the visible interaction render [MED]

Locations: `resolveRefreshContext` (`:215-227`) and `handleToggleApiInternal` (`:529-593`).

`resolveRefreshContext` calls `RefreshScheduler.refreshIfStale` before returning the database and
location needed for the render. `handleToggleApiInternal` similarly calls
`enqueueForcedRefresh` before `refreshGraphView` / `refreshDailyView`.

The ordering comment in the API-toggle path correctly says that a render failure must not suppress
the needed network request. The inverse is also required: a WorkManager initialization/enqueue
failure, preferences failure, or other scheduling exception must not suppress a cache-backed
render. Today it does. The handler has already mutated source/mode/offset state, then
`runInteraction` catches the scheduling exception and returns without updating the surface. This
recreates the persisted-state/visible-state mismatch that the interaction failure breadcrumbs were
added to diagnose.

**Required fix:**

1. Separate context resolution from the optional scheduling side effect.
2. Attempt the cache render and refresh request independently. Ordinary scheduling failures should
   produce a sparse `STALE_REFRESH_ENQUEUE_FAIL` / `TOGGLE_REFRESH_ENQUEUE_FAIL` breadcrumb and
   still allow the render; cancellation must still propagate.
3. Introduce a small scheduler interface (or injected function) so a unit test can make scheduling
   throw and assert that the renderer is still invoked.
4. Preserve `KEEP` for immediate unique work; this finding does not authorize `REPLACE`.

### F4 — “from=” breadcrumb metadata is read before the serialization lock [LOW]

Locations: `handleCycleZoom` (`:452-467`), `handleToggleApi` (`:505-516`),
`handleToggleView` (`:599-610`), and `handleTogglePrecip` (`:672-683`).

These entry points read current state before calling `runInteraction`, which acquires the
per-widget mutex. The `handleCycleZoom` comment claims the pre-lock value remains accurate because
same-widget interactions are serialized. That is incorrect: two coroutines can both read the same
old value before either acquires the lock, then execute sequentially. The second breadcrumb reports
the wrong starting source/mode/zoom.

The behavior transition itself remains serialized; this is diagnostic correctness. In this project
the persistent breadcrumbs are a primary source for reconstructing interaction failures, so they
should not encode a known race.

**Required fix:**

1. Capture state-dependent metadata inside the same lock as the transition.
2. Change the interaction wrapper to accept an in-lock metadata producer or return an
   `InteractionOutcome` containing before/after metadata.
3. Add a two-coroutine test where two same-widget toggles record successive starting states rather
   than the same pre-lock state.

### F5 — WidgetInteractionCache does not coalesce the concurrent loads it claims to share [MED]

Location: `loadCachedDailyData` (`:637-667`) and `WidgetInteractionCache`.

The cache documentation says a burst across widget instances can share a single expensive
`DailyActualsLoader.load`. The router uses:

```kotlin
WidgetInteractionCache.get(key, nowMs)?.let { return it }
val weatherListRaw = ...
val dailyActuals = DailyActualsLoader.load(...)
WidgetInteractionCache.put(key, data, nowMs)
```

Per-widget interaction locks are intentionally independent. Therefore two widgets entering this
code concurrently can both miss, both run the expensive queries/aggregation, and both write the
same entry. The cache serves sequential bursts but does not provide the cross-widget single-flight
behavior its KDoc and performance rationale promise.

**Required fix:**

1. Move loading behind `WidgetInteractionCache.getOrLoad(key, loader)`.
2. Use a per-key `Mutex` or shared `Deferred` so one caller performs the load and the others await
   it. Do not use one global mutex that serializes unrelated locations.
3. Store the completion time, not the pre-load time, as the TTL origin so a slow load does not
   consume its own cache lifetime.
4. Add a coroutine test that launches two same-key loads, blocks the loader, and asserts the loader
   executes exactly once. Add a different-key test proving independence.

### F6 — Extract interaction execution and debounce state into WidgetInteractionCoordinator [STRUCTURAL]

Locations: mutex/breadcrumb/batch-loop code (`:61-189`) and resize debounce (`:790-826`).

This state is one cohesive concurrency component:

1. Per-widget mutex ownership.
2. Interaction success/failure outcome logging.
3. Isolated multi-widget execution.
4. Per-widget trailing-edge resize tokens.
5. Widget-forget cleanup.

It does not belong beside forecast queries and RemoteViews data assembly. Keeping it in the router
also forces tests to reach through `@VisibleForTesting` methods on the production facade.

**Required extraction:** create `WidgetInteractionCoordinator` and move
`withWidgetInteractionLock`, `runInteractionWithDao`, `forEachWidgetIsolated`,
`awaitLatestResizeRequest`, `forgetWidget`, and their maps/counters into it. Keep the public
`WidgetIntentRouter` methods as a compatibility facade while callers and tests are migrated.

The coordinator should expose a typed interaction result so F4 metadata is captured under the lock
without adding another callback convention.

### F7 — Extract daily interaction rendering into DailyInteractionRenderer [STRUCTURAL]

Locations: daily navigation (`:309-419`), cached daily loading (`:637-667`), and
`refreshDailyView` (`:912-1039`).

These methods form a coherent daily pipeline:

1. Define the daily history/forecast range.
2. Load/cache live forecasts and actuals.
3. Apply climate-normal gaps.
4. Select snapshot history.
5. Load/unify hourly data needed by daily cloud/current-temperature rendering.
6. Build `WeatherData` / `ObservationData`.
7. Dispatch to `DailyViewHandler` and log timing.

The duplicated range construction in daily navigation and refresh is already a sign that the
boundary is missing. The current method also has 13 parameters, several of which are optional
preloaded data or diagnostics.

**Required extraction:** create `DailyInteractionRenderer` with:

1. A `DailyRenderRequest` value object (`appWidgetId`, location, captured time, push mode/origin,
   action diagnostics).
2. A `DailyRenderData` value object for optional preloaded daily data.
3. One shared range calculation used by navigation and rendering.
4. Ownership of the single-flight cache from F5.

`WidgetIntentRouter` should mutate navigation/view state and delegate one request; it should not
query forecast, hourly, observation, history, or climate-normal DAOs.

### F8 — Extract graph interaction rendering into GraphInteractionRenderer [STRUCTURAL]

Locations: graph navigation (`:424-447`), zoom handling (`:469-500`),
`refreshGraphView` (`:1041-1101`), and `updateHourlyViewWithData` (`:1122-1231`).

This is the graph counterpart to F7 and already has a clean internal flow:

1. Read zoom/source/offset and resolve the fixed/live center.
2. Load the graph window.
3. Load the independent now-centered current-temperature window.
4. Resolve current observations and daily fallback precipitation.
5. Dispatch to temperature, precipitation, or cloud-cover handlers.

**Required extraction:** create `GraphInteractionRenderer` with a `GraphRenderRequest` carrying a
single captured `now`, location, source/view state, push mode/origin, and diagnostics. Move the
graph-specific API-toggle staleness probe orchestration here or into the F1 context component; do
not leave it in the facade.

The public router should choose the requested transition and delegate. It should not own DAO reads
or the `when (viewMode)` render dispatch.

### F9 — Extract widget-scoped refresh context and policy orchestration [STRUCTURAL]

Locations: `LocationResult`, `RefreshContext`, `resolveLocation`, and `resolveRefreshContext`
(`:193-228`).

F1-F3 all converge on this boundary. A dedicated `WidgetRefreshContextResolver` should own:

1. Target-widget location resolution.
2. Database/DAO access required by both renderers.
3. Location/source-scoped freshness resolution.
4. The refresh-request decision consumed by an isolated scheduler collaborator.

The resolver should return data only; scheduling should be an explicit subsequent operation so its
failure cannot prevent render. Inject a clock and refresh scheduler to make location/freshness
tests deterministic.

After F6-F9, `WidgetIntentRouter` should contain only the public action methods, small state
transitions, and delegation. A practical target is under roughly 300 lines, not an arbitrary split
of the existing file into equally sized pieces.

### F10 — Daily refresh still captures “today” more than once [LOW]

Location: `refreshDailyView` (`:927-1039`).

The method captures `today = LocalDate.now()` at `:928`, later captures
`now = LocalDateTime.now()` at `:969`, then calls `LocalDate.now()` again for `todayStartMs` at
`:983`. A render crossing midnight can query daily forecasts/snapshots for one date but current
observations for the next.

**Required fix:** capture one `LocalDateTime now` at the start of the daily render request and derive
`today`, all epoch bounds, and current-temperature windows from it. F7's `DailyRenderRequest`
provides the natural place to enforce this invariant.

## Implementation order

1. Add characterization tests for F1, F2, F3, F4, F5, and F10 against the current code where
   practical. At minimum, demonstrate F1 and F5 failing before production changes.
2. Extract `WidgetInteractionCoordinator` (F6) without behavior changes, retaining existing lock,
   cancellation, debounce, and breadcrumb tests.
3. Extract `WidgetRefreshContextResolver` (F9), then fix target-widget/location freshness (F1/F2)
   and scheduling isolation (F3).
4. Extract `DailyInteractionRenderer` (F7), implement single-flight loading (F5), and use one
   captured time (F10).
5. Extract `GraphInteractionRenderer` (F8).
6. Move metadata capture inside the coordinator lock (F4).
7. Reduce `WidgetIntentRouter` to its action facade and remove obsolete forwarding helpers and
   `@VisibleForTesting` hooks only after their tests target the owning components.

Do not combine these changes with unrelated renderer styling or repository refactors. The current
worktree contains unrelated ForecastRepository work that must remain untouched and unstaged.

## Verification

### Focused JVM/Robolectric

1. `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
2. `./gradlew :app:testShortDebugUnitTest --tests "com.weatherwidget.widget.handlers.WidgetIntentRouterExecutionTest"`
3. `./gradlew :app:testShortDebugUnitTest --tests "com.weatherwidget.widget.handlers.WidgetInteractionCacheTest"`
4. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.WidgetIntentRouterRobolectricTest"`
5. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.WidgetIntentRouterCrashSafetyRoboTest"`
6. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewApiToggleIntegrationRoboTest"`
7. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.CloudCoverViewModeRoboTest"`
8. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.NavigationPersistenceRoboTest"`
9. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.HistoryClampingRegressionRoboTest"`
10. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.ZoomCycleRoboTest"`
11. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.WeatherWidgetProviderDayTapSourceGapRoboTest"`
12. `./gradlew :app:testLongDebugUnitTest --tests "com.weatherwidget.widget.DailyCloudCoverSiteParityRoboTest"`

Add and run:

1. A target-widget/two-location refresh-context integration test.
2. A successful-check-vs-content-age stale-decision test.
3. A scheduler-failure-does-not-suppress-render test.
4. An in-lock before/after metadata concurrency test.
5. Same-key single-flight and different-key independence cache tests.
6. A fixed-clock midnight-boundary daily render-data test.

Every new test class must declare exactly one duration category based on measured wall time.

### Emulator/device

Because the changes alter interaction routing and location selection, JVM success is insufficient:

1. Install on one running emulator and identify it with `getprop`.
2. Exercise daily navigation, graph navigation, source toggle, view toggle, set-view/day tap, zoom,
   and resize. Confirm the expected `*_RENDER_OK`, `*_TIMING`, and `WIDGET_RENDER_OK` rows with no
   corresponding `*_FAIL`.
3. Verify the resize framework chain with
   `./scripts/emulator-tests.sh -c com.weatherwidget.widget.ResizeDebounceInstrumentedTest`.
4. Configure/reproduce two distinct widget locations if that remains a supported feature. Query the
   database and breadcrumbs to prove each interaction uses the target widget's coordinates.
5. If product policy is changed to one app-wide location instead, verify that widget-add and
   Settings both synchronize every widget and remove the per-widget ambiguity before closing F1.

## Implementation result

Implemented on 2026-07-28:

1. Reduced `WidgetIntentRouter` from 1,234 lines to a 197-line public facade.
2. Extracted:
   - `WidgetInteractionCoordinator`
   - `WidgetIntentActionHandler`
   - `WidgetRefreshContextResolver`
   - `InteractionRefreshRequester`
   - `InteractionRenderDispatcher`
   - `InteractionTimingLogger`
   - `DailyInteractionRenderer`
   - `GraphInteractionRenderer`
3. Made the application's existing one-location policy explicit:
   - `ActiveLocationResolver` now persists one canonical active site.
   - widget-add and Settings synchronize all widget compatibility keys.
   - legacy divergent widget keys are healed to the canonical site.
   - worker, GPS handoff, interaction refresh, and startup behavior therefore share one site.
4. Replaced global latest-row freshness with displayed-source and active-site freshness, combining
   content age with the site/source successful-check timestamp.
5. Isolated stale and targeted WorkManager hand-offs so scheduling failures are logged without
   suppressing cached rendering; cancellation still propagates and immediate work remains `KEEP`.
6. Moved state-dependent interaction metadata capture inside the per-widget lock.
7. Added per-key single-flight daily loading with completion-based TTL and independent keys.
8. Derived every daily-render time bound from one captured `LocalDateTime`.

### Verification completed

1. Debug Kotlin and unit-test Kotlin compilation passed.
2. The complete `:app:testShortDebugUnitTest` suite passed.
3. Focused Long/Robolectric coverage passed for router behavior, crash safety, API toggles,
   cloud-cover mode, navigation persistence, history clamping, zoom, day taps, daily cloud parity,
   canonical location resolution, ConfigActivity, LocationUpdater, and GPS resampling.
4. The API 36 `Generic_Foldable_API36` emulator was identified as Google
   `sdk_gphone64_x86_64`, the debug APK was installed with the existing launcher widget preserved,
   and the placed widget rendered successfully.
5. Explicit daily navigation, set-view, graph navigation, zoom, source toggle, precipitation toggle,
   and view toggle broadcasts all produced their expected `*_RENDER_OK`/timing breadcrumbs with no
   corresponding action failure.
6. `ResizeDebounceInstrumentedTest` passed on the emulator (1/1).
7. The emulator's canonical active-location preference and widget IDs 2/7 compatibility keys were
   read back and matched exactly; a final post-install daily navigation rendered with
   `NAV_RENDER_OK` and no `NAV_FAIL`.

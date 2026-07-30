# Code Review: `WidgetStateManager.kt`

**Reviewed:** 2026-07-30
**File:** `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
**Reviewed state:** commit `dc43794c` on `main`; worktree clean before this review; 1,064 lines
**Scope:** correctness, maintainability, state-lifecycle ownership, persistence compatibility, and
required cohesion extractions

## Implementation result

**Implemented:** 2026-07-30

All actionable findings in this review were implemented:

1. `WidgetStateManager` is now a 405-line compatibility facade with no raw key schema or migration
   implementation.
2. State ownership moved to `WeatherSourcePreferences`, `WidgetPresentationStateStore`,
   `CurrentTemperatureDeltaStore`, `WidgetFetchStateStore`, `WidgetLocationStore`, and
   `WeatherDisplayPreferences`.
3. Source selection, view mode, and zoom use stable string identities with explicit boolean/integer
   upgrade codecs. Unknown stored types/IDs normalize safely.
4. Provider deletion removes the deleted widget's full state across both preference files without
   changing another widget or app-wide settings. Default reads do not recreate deleted keys.
5. Visual Crossing and OpenWeatherMap remain parseable historical enum values but are removed from
   the shared Android/desktop configurable-source policy and persisted visible lists.
6. Source reorder preserves surviving selections by identity, while removed selections fall back
   to the first configured survivor.
7. Presentation transitions and hourly offset/anchor writes use one editor per logical mutation;
   coherent snapshot reads decode one `SharedPreferences.all` snapshot.
8. Cooldowns and anchors use injected clocks, the render-frequency center trace is verbose, and
   sparse state-event logging uses a Hilt-provided application scope with failure reporting.

### Verification completed

1. Focused regression tests cover legacy booleans (false and true), large integer steps, unknown
   stored types, stable mode/zoom migration, deprecated selected sources, source reorder/removal,
   complete cleanup, exact cooldown/transient boundaries, wall-clock rollback, DST, and time-zone
   changes.
2. Full automated lanes passed:

   ```text
   ./gradlew :app:testByDurationDebugUnitTest
   ./gradlew :shared:test
   ./gradlew :desktop:testByDurationDesktop
   ```

3. `WidgetStateManagerMigrationInstrumentedTest` passed four tests on
   `Generic_Foldable_API36`/API 36: boolean upgrade, source-identity preservation, coherent view
   transitions/navigation, and provider deletion cleanup using synthetic widget IDs.
4. A no-data-clear APK upgrade was observed on launcher widget `59`. Before installation it used
   NWS, Temperature view, offset `0`, wide zoom ordinal `0`, date offset `-1`, and coordinates
   `37.416798/-122.089`. After installation and repaint it retained the same visible state and
   location while preferences normalized to `widget_display_source_59=NWS`,
   `widget_view_mode_59=TEMPERATURE`, and `widget_zoom_level_59=WIDE`.
5. Before/after screenshots showed the widget rendering normally. Instrumented tests left the
   emulator running, preserved the production widget, and removed synthetic location IDs `9126`
   and `9127`.
6. No WorkManager enqueue/cancellation policy was changed.

## Overall assessment

`WidgetStateManager` is not one complex algorithm. It is a large persistence facade that currently
owns at least six unrelated state domains:

1. Global display settings, API keys, and personal-station weighting.
2. Global weather-source ordering plus four historical migrations.
3. Per-widget daily/hourly navigation, graph mode, zoom, and source selection.
4. Per-widget transient render state and current-temperature delta state.
5. Fetch cooldowns and source-health diagnostics.
6. Widget locations stored in a second `SharedPreferences` file.

The file's small methods make local reading easy, but the shared key namespace and cross-domain
cleanup make it difficult to preserve lifecycle and migration invariants. That has produced live
correctness problems: an old boolean toggle cannot be read by the current integer codec, widget
deletion does not clear all widget-owned state, and an obsolete migration still enables a source
that the current project contract says must remain hidden/deprecated.

The earlier review-target note in `plans/260725-code-review-target-files-plan.md` describes
`logEvent` as a 914-line/high-complexity method. That metric is a parser artifact: the current
`logEvent` body is only seven lines (`:52-58`). The real structural problem is responsibility
count, not cyclomatic complexity.

The required end state is a compatibility facade named `WidgetStateManager` delegating to cohesive
stores. Do not perform a blind rename or a one-commit rewrite: add regression tests for the
correctness findings first, then extract one state domain at a time while keeping callers stable.

## Findings

### F1 — The legacy boolean source-toggle migration throws instead of migrating [HIGH]

Locations: `getDisplaySourceToggleStep` (`:916-923`) and the source-selection readers
(`:800-834`).

Before WeatherAPI rotation, `widget_display_source_<id>` was a boolean. Git history confirms the
old implementation read and wrote that exact key with `getBoolean`/`putBoolean`. The current code
does this:

```kotlin
if (prefs.contains(key)) {
    return prefs.getInt(key, 0)
}
return if (prefs.getBoolean(key, false)) 1 else 0
```

For a real legacy boolean, `contains(key)` is true and `getInt` throws `ClassCastException`. The
advertised fallback is unreachable for the only state it is meant to migrate. Any path that reads
the displayed source can then fail, including widget render, startup prioritization, setup source
remapping, and API toggle.

**Required fix:**

1. Introduce a typed source-selection codec that reads the stored value through
   `SharedPreferences.all[key]`.
2. Decode:
   1. current stable source ID/string;
   2. current integer step/index during migration;
   3. legacy boolean (`false -> first source`, `true -> second source`);
   4. absent or unknown types to the first valid source.
3. Persist the normalized selection immediately as a stable `WeatherSource.id`, then remove any
   obsolete representation.
4. Store source identity, not a list position. Translate to the current effective list only when
   cycling; if the stored source is no longer available, select and persist the first survivor.
5. Add a Robolectric regression test that seeds a boolean under
   `widget_display_source_<id>`, calls `getCurrentDisplaySource`, asserts no exception and the
   expected second source, then verifies the preference was normalized.
6. Add integer-step migration cases, including a step larger than the current list and an unknown
   stored type.

This repair must precede the source-store extraction so the new component starts with one explicit
serialization contract.

### F2 — Widget deletion leaves location, transient-message, and cooldown state behind [HIGH]

Locations: `clearWidgetState` (`:480-520`), location storage (`:855-908`), and
`WeatherWidgetProvider.onDeleted` (`WeatherWidgetProvider.kt:105-118`).

`onDeleted` relies on `clearWidgetState` as the persistent lifecycle cleanup. That method omits:

1. `widget_transient_msg_<id>` and `widget_transient_msg_expires_<id>`.
2. Every `widget_missing_data_refresh_<id>_<source>_<type>` key.
3. `widget_lat_<id>` and `widget_lon_<id>`, which live in `ConfigActivity.PREFS_NAME`.

The test suite exposes the first omission indirectly: several tests call
`clearTransientMessage(id)` separately after `clearWidgetState(id)`. Production deletion does not.

The remaining keys are not harmless bookkeeping. If an app-widget ID is reused or a test reuses an
ID, a new widget can inherit an old location, suppress a needed missing-data refresh until the old
cooldown expires, or repaint an old transient banner. Even without ID reuse, deleted-widget keys
accumulate indefinitely and bug reports can report configuration for a widget that no longer
exists.

**Required fix:**

1. Make one per-widget cleanup operation own every state domain, including the second preferences
   file used for coordinates.
2. Remove exact scalar keys and dynamically keyed families using an anchored widget prefix. Avoid
   substring matching that could confuse widget `12` with widget `123`.
3. Include legacy keys and all source-scoped current-temperature delta variants.
4. Keep the cleanup write atomic within each preferences file. Document that two files cannot be
   committed as one transaction, but leave neither domain intentionally out.
5. Add a table-driven Robolectric test that seeds every current per-widget key family plus another
   widget's keys, invokes cleanup, and asserts:
   1. all target-widget keys are gone from both files;
   2. the other widget is unchanged;
   3. app-wide source/API/unit preferences remain unchanged.
6. Add a provider lifecycle test proving `onDeleted` invokes the complete cleanup rather than
   requiring callers to remember companion methods.

### F3 — An obsolete migration makes Visual Crossing visible despite the current source contract [HIGH]

Locations: `migrateVisualCrossingIfNeeded` (`:360-383`), source parsing/filtering (`:279-293`),
source setters (`:385-438`), and the associated migration tests
(`WidgetStateManagerTest.kt:398-456`).

The project contract says Visual Crossing and OpenWeatherMap remain in `WeatherSource` but are
hidden/deprecated. `WidgetStateManager` filters OpenWeatherMap, but it still:

1. inserts `VISUAL_CROSSING` at position 1 for eligible existing preferences;
2. retains it when parsing stored source order;
3. accepts it in both source-order setters.

The current tests assert this obsolete behavior. `SettingsActivity.allSources` and
`WeatherSourceOrdering.ALL_CONFIGURABLE` also still list Visual Crossing, so fixing only the
manager would leave Android/desktop policy inconsistent.

For an upgrading user without a valid Visual Crossing key, the migration can place an unusable
provider second in the widget cycle and fetch set. This is not merely dead migration code; it
actively changes persisted user configuration.

**Required fix:**

1. Define one shared source-policy list for configurable sources and one sanitizer for persisted
   visible sources. `WeatherSourceOrdering` is the existing appropriate shared owner.
2. Remove Visual Crossing and OpenWeatherMap from the configurable set while retaining enum/parser
   support for historical rows.
3. Replace the insertion migration with a new one-time hide/deprecation migration that removes
   both deprecated sources from existing visible orders.
4. Preserve the selected source by identity for every active widget while applying the sanitized
   order; widgets displaying a removed source move to the first surviving configured source.
5. Guarantee a non-empty global order, using the canonical platform default when sanitization
   removes every entry.
6. Update Android Settings and desktop Settings to consume the shared policy rather than their own
   source lists.
7. Replace the current migration assertions with upgrade cases for:
   1. Visual Crossing in the middle of a valid order;
   2. only deprecated sources stored;
   3. a widget currently displaying Visual Crossing;
   4. unknown future IDs mixed with valid IDs.

### F4 — Persisted modes and selections use unstable enum ordinals/list positions [MED]

Locations: view mode (`:624-635`), zoom (`:776-789`), source selection (`:800-834`), and
`setVisibleSourcesOrderForSetup` (`:406-438`).

`ViewMode.ordinal`, `ZoomLevel.ordinal`, and the visible-source list index are persisted. These
values are coupled to declaration order in two modules and to a mutable user-configured list.
Reordering an enum, inserting a mode, or changing source availability can silently reinterpret
stored state as a different mode/source.

`setVisibleSourcesOrderForSetup` already demonstrates the missing invariant: it must snapshot
selected sources by identity and translate them back after the list changes. The regular Settings
setter instead resets every widget to position zero, and ordinary reads cannot distinguish an old
position from a current source identity.

**Required fix:**

1. Persist `ViewMode.name`, `ZoomLevel.name` (or explicit stable IDs in `ZoomStage`), and
   `WeatherSource.id`.
2. Give each codec an explicit migration path from the current integer representation.
3. Treat unknown future strings as a documented default without throwing.
4. Make all source-order mutations preserve surviving widget selections by identity. Remove the
   separate behavior split between the regular setter and setup setter; expose one operation with
   an explicit removed-source fallback policy.
5. Add compatibility tests using historical integers and unknown strings, plus source reorder,
   removal, and insertion tests.

### F5 — Multi-field state transitions are observable halfway through [MED]

Locations: source-order mutation (`:385-399`), view-mode transitions (`:637-687`), and hourly
navigation (`:695-713`).

Several logical transitions are implemented as multiple independent `SharedPreferences.edit()`
operations:

1. `setVisibleSourcesOrder` writes the new list, then separately clears all selection positions.
2. View toggles write the mode, then separately update offset/anchor and zoom.
3. Navigation reads offset and zoom separately, then writes offset/anchor.

`apply()` updates in-memory preferences immediately. A concurrent widget render or background
refresh can therefore observe a new mode with an old offset/zoom, or a new source order with a
selection encoded for the old order. The router serializes user interactions per widget, but it
does not serialize WorkManager, scheduled UI updates, Settings changes, and render reads against
these preference edits.

**Required fix:**

1. Represent presentation state as a typed `WidgetPresentationState`.
2. Compute each transition as a pure function from old state to new state.
3. Persist every field changed by one transition through one editor/apply call.
4. Provide a snapshot read that obtains all relevant raw preference values before decoding, so a
   renderer does not compose a state from unrelated read times.
5. Keep app-wide source-order plus per-widget selection remapping in one synchronous in-memory
   mutation. Do disk work off the main thread only where a durable `commit()` is genuinely needed.
6. Add pure transition tests and a SharedPreferences listener/fake-store test asserting one
   coherent mutation per operation.

### F6 — Time and logging side effects are embedded in a preference facade [LOW]

Locations: `logScope`/`logEvent` (`:50-58`), source logging (`:385-395`, `:429-435`,
`:455-477`), wall-clock cooldowns (`:522-555`), and graph anchoring (`:695-750`).

The manager creates an unmanaged `CoroutineScope(Dispatchers.IO)` and launches database logging
without lifecycle ownership, ordering guarantees, or exception handling. `logEvent` itself is
unused, while other methods duplicate its launch pattern. The same class calls
`System.currentTimeMillis()` and `LocalDateTime.now()` directly, making cooldown and DST/time-zone
behavior harder to test deterministically.

`HOURLY_CENTER_TRACE` is emitted with `Log.d` on each center resolution (`:739-744`), which is a
render-frequency breadcrumb and should use the project's verbose tier.

**Required fix:**

1. Inject a clock into stores that own expiry/cooldown/anchor calculations.
2. Inject the application-owned coroutine scope or, preferably, a small sparse event logger whose
   implementation owns dispatch and failure handling.
3. Remove the unused `logEvent` helper after call sites use the logger abstraction.
4. Change the per-render center trace to `Log.v`; retain sparse source-order changes at debug.
5. Add deterministic clock tests for exact cooldown/expiry boundaries and a time-zone/DST anchor
   case.

### F7 — Split the manager by state ownership while retaining a thin compatibility facade [STRUCTURAL]

Location: the complete 1,064-line class.

The following extraction is required because F1-F6 cross the current shared namespace. File size
alone is not the reason; each proposed component has its own key schema, lifecycle, and migration
rules.

#### 1. Extract `WeatherSourcePreferences`

Own:

1. visible-source order and sanitization;
2. source-order migrations;
3. selected source identity/remapping;
4. API keys;
5. primary/active source queries.

Use the shared `WeatherSourceOrdering` policy. Do not put network coverage checks in this store;
setup/runtime policy remains in its existing selector/coordinator.

#### 2. Extract `WidgetPresentationStateStore`

Own:

1. daily offset;
2. `ViewMode`;
3. hourly offset and graph anchor;
4. zoom;
5. rain-shown date;
6. transient message;
7. last graph-render state.

Expose typed snapshot reads and atomic transition operations rather than a public getter/setter for
every raw key.

#### 3. Extract `CurrentTemperatureDeltaStore`

Own the multi-key `CurrentTemperatureDeltaState` codec, legacy migration, source scoping, and
per-widget cleanup. Keep all seven fields in one editor operation.

#### 4. Extract `WidgetFetchStateStore`

Own:

1. missing-data refresh cooldowns;
2. current-temperature per-source throttle;
3. source failure count/code/time.

Use injected time and document which records are app-wide, source-wide, or widget-scoped.

#### 5. Extract `WidgetLocationStore`

Own coordinate reads/writes and deletion from `weather_widget_prefs`. Return a named location value
instead of `Pair<Double, Double>`. Keep fallback policy (`stored widget`, legacy delta,
`historical_pois`) separate from raw persistence so consumers can request authoritative stored
coordinates when fallbacks would be unsafe.

#### 6. Retain `WidgetStateManager` as a facade

Initially preserve existing method signatures and delegate to the new stores so dozens of render
and repository callers do not need to migrate at once. New code should inject the narrow store it
needs. Remove facade methods only after `rg` proves there are no production/test callers.

Global unit preference and personal-station discount can move into a small
`WeatherDisplayPreferences` component during this extraction. They must not be mixed back into
per-widget presentation state.

## Implementation sequence

1. Add failing tests for F1, F2, and F3.
2. Repair the boolean/int source-selection codec and normalize to stable source IDs.
3. Implement complete widget cleanup across both preference files.
4. Centralize configurable/deprecated source policy and replace the Visual Crossing insertion
   migration.
5. Migrate `ViewMode` and `ZoomLevel` persistence to stable IDs.
6. Make source-order and presentation transitions atomic.
7. Extract `WeatherSourcePreferences`.
8. Extract `WidgetPresentationStateStore`.
9. Extract `CurrentTemperatureDeltaStore`.
10. Extract `WidgetFetchStateStore`.
11. Extract `WidgetLocationStore`.
12. Convert high-frequency center logging to verbose and replace the unmanaged logging scope.
13. Migrate high-use callers to narrow dependencies, leaving `WidgetStateManager` as a temporary
    compatibility facade.
14. Remove dead constants/helpers and obsolete migration flags only after upgrade tests prove the
    supported migration path.

Compile after each extraction rather than postponing verification until the facade has moved in
full.

## Verification requirements

### Focused automated tests

1. Source codec:
   1. legacy boolean false/true;
   2. current integer position;
   3. stable source ID;
   4. unknown/malformed value;
   5. reordered and removed source.
2. Cleanup:
   1. every target-widget key family removed;
   2. second widget and global preferences preserved;
   3. coordinates removed from `weather_widget_prefs`;
   4. provider `onDeleted` integration.
3. Source policy:
   1. deprecated sources removed from upgrades;
   2. at least one valid source retained;
   3. selected-source identity preserved;
   4. Android and desktop consume the same configurable list.
4. Presentation transitions:
   1. daily/temperature/precipitation/cloud transitions;
   2. zoom and offset preservation/reset rules;
   3. graph anchor behavior at live/history boundaries;
   4. historical integer-to-string mode migration.
5. Clock behavior:
   1. exact cooldown and transient expiry boundaries;
   2. wall-clock rollback behavior;
   3. DST/time-zone graph-anchor case.

Suggested focused lane:

```bash
./gradlew :app:testLongDebugUnitTest \
  --tests com.weatherwidget.widget.WidgetStateManagerTest \
  --tests com.weatherwidget.widget.WidgetStateManagerApiRotationRoboTest \
  --tests com.weatherwidget.widget.WidgetStateManagerSyncRoboTest
```

Then run:

```bash
./gradlew :app:testByDurationDebugUnitTest
./gradlew :shared:testShortShared
./gradlew :desktop:testByDurationDesktop
```

If source-policy changes touch desktop configuration, also run the desktop settings/config focused
tests before the full desktop lane.

### Emulator verification

Because these changes affect runtime widget persistence:

1. Record each emulator widget's initial view, source, offset/zoom, and location.
2. Install the debug APK without clearing app data.
3. Seed or use an upgrade fixture containing a boolean `widget_display_source_<id>` and verify the
   widget renders and the preference normalizes without a crash.
4. Reorder sources and verify each widget keeps the same displayed source when it survives.
5. Delete a disposable widget, inspect both preferences files with `run-as`, and verify no keys for
   that ID remain.
6. Add a widget again and verify it starts with default presentation state and the currently
   selected location rather than deleted-widget state.
7. Exercise Daily, Temperature, Precipitation, and Cloud Cover transitions plus hourly navigation.
8. Restore the initial emulator source/view/location state before reporting completion.

Use `./scripts/emulator-tests.sh -c <fully.qualified.TestClass>` for any new instrumented lifecycle
test. Do not run on physical devices unless requested.

## Completion criteria

The work is complete only when:

1. F1-F6 have regression coverage and implemented fixes.
2. The five stores in F7 exist with documented key ownership.
3. `WidgetStateManager` is a thin delegating facade rather than the owner of raw keys and
   migrations.
4. Deprecated source policy is consistent across Android and desktop settings.
5. Focused and full duration-bucket test lanes pass.
6. Emulator upgrade, deletion cleanup, source preservation, and view-transition checks pass.
7. No new `REPLACE` or cancel-by-name WorkManager behavior is introduced.

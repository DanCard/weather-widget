# Sync data-screen sources with the main view (desktop + Android parity)

Status: **approved 2026-11-19 ("proceed"); implemented**

## Request

User, 2026-11-19: "desktop: What do think of synchronizing API sources between 'forecast comparison'
window and main window?" After the recommendation: "write the sync plan. yes: the Stations
(Observations) window get the same treatment."

## Evidence (current behavior)

| Screen | Opens on | Pill cycle changes main source? | Main change while open reaches it? | View counted |
|---|---|---|---|---|
| Android Forecast Comparison (`ForecastComparisonActivity.cycleApiSource`, ~l.1154) | widget source (intent extra) | **yes**: `widgetStateManager.setCurrentDisplaySource` + UI-only refresh broadcast | n/a (widget is covered) | **no** |
| Android Stations (`WeatherObservationsActivity.cycleSource`, l.574) | widget source | **yes**: `setCurrentDisplaySource`, repaint on close | n/a | yes, `OBSERVATIONS` |
| Desktop Forecast Comparison (`ForecastComparisonWindow.kt`) | main source on each open (fixed 2026-11-19, `remember(showRequestId)`) | **no**: local `var source` | **no** | no |
| Desktop Stations (`ObservationsWindow.kt` l.296) | main source **once**: unkeyed `remember { config.displaySource }` (the same stale bug the comparison window had) | **no** | **no** | yes, `OBSERVATIONS` (`DesktopSourceViews.record`) |

- The trigger this caused: on 2026-11-19 the desktop comparison window still showed NWS (1 snapshot
  for Nov 26) while the main window showed Open-Meteo (49 rows). See the fix in this session.
- Android switches from the data screens bypass `SourceSwitchRunner` (no `SOURCE_SWITCH_E2E`, no
  stale-source check). Desktop's main switches go through it via `DesktopSourceSwitch.switchTo`.
- `SourceViewProbability.toggleToday` counts only `TOGGLE` and `OBSERVATIONS` triggers as "the user
  switched away from the primary today".

## Proposed behavior (Claude proposal)

One displayed source, shared by the main view and both data screens, on both platforms:

1. A data screen always shows the **main view's current source**. Desktop windows read
   `config.displaySource` directly (no local copy), so a switch in the main window or popup updates
   an open Forecast Comparison / Stations window immediately.
2. Cycling a data screen's pill (left = next, right-click = previous on desktop) **switches the main
   view's source**, exactly like the API button: same persist, same view count, same stale-source
   refresh rule, same `SOURCE_SWITCH_E2E` logging.
3. Trade-off (accepted on Android already): browsing sources in a data screen moves the main
   view/widget too, and may refetch a stale source (one source only, per
   `SourceToggleRefreshPolicy`; never all sources).

## Implementation

### :shared
- Add `SourceViewTrigger.COMPARISON` ("the source cycle in the Forecast Comparison screen").
  Stored by name in `source_view_days.trigger` (TEXT); no schema change. **Verify** both DAOs store
  the name as-is and add no CHECK constraint.
- `SourceViewProbability.toggleToday`: count `COMPARISON` with `TOGGLE` and `OBSERVATIONS`.
- No new runner: both platforms' data-screen switches go through the existing `SourceSwitchRunner`.

### :desktop
- `ForecastComparisonContent` and `ObservationsContent`: delete the local `source` state; derive it
  from `config.displaySource` (falling back to the first visible source as today). The 2026-11-19
  `remember(showRequestId)` re-seed becomes unnecessary and is removed.
- Pill cycle → `DesktopSourceSwitch.switchTo(config, next.id, trigger, onUpdateConfig)`, with
  `COMPARISON` / `OBSERVATIONS`. Both windows already receive `onConfigUpdate` (→
  `saveConfigAndNotify`); the Stations window's separate `DesktopSourceViews.record` call goes away
  (the runner records the view).

### :app (Android)
- `ForecastComparisonActivity.cycleApiSource` and `WeatherObservationsActivity.cycleSource`: switch
  through `SourceSwitchRunner` with an Android host. **Verify first** whether the widget's existing
  host (`WidgetIntentActionHandler` l.297) can be reused from an Activity; otherwise a small
  screen host (persist = `setCurrentDisplaySource`; paint = the existing UI-only refresh broadcast /
  repaint-on-close; refresh = the same `refreshIfStale` as the widget toggle; record =
  `SourceViewRecorder`).
- Forecast Comparison starts counting its switches (`COMPARISON`); today it counts none.

### Docs
- `arch/ARCHITECTURE.md` (runner list / source switching) and whichever topic doc describes the data
  screens: one paragraph on "data screens share the displayed source". `rg` `arch/` for
  `OBSERVATIONS` / `SourceSwitchRunner` first and update the single canonical place.

## Tests (integration first)

1. **Desktop, real `DesktopWeatherDao` + window content + a config holder wired like
   `saveConfigAndNotify`** (`DesktopDataScreenSourceSyncTest`):
   - cycle in Forecast Comparison → `config.settings.weatherSource` is the next source, a
     `source_view_days` row with trigger `COMPARISON`, header pill shows it;
   - change the config's source while the window is shown → the pill and the snapshot count follow;
   - same two cases for Stations (`OBSERVATIONS`);
   - right-click cycles backwards and also persists.
   Must fail on the current code (local state).
2. **Android Robolectric, real Room DB**: cycling in each screen persists the widget source, logs
   `SOURCE_SWITCH_E2E`, and counts the view under the right trigger. Close every opened activity in
   `@After` (see the 2026-11-19 leak fix).
3. **:shared** `SourceViewProbabilityTest`: a `COMPARISON` switch away from the primary counts as a
   toggle day.
4. Update `DesktopSourceCycleIntegrationTest` (it asserts cycling without persistence) and the
   reopen test from 2026-11-19.

## Verification

- End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
- Desktop by hand: open Forecast Comparison and Stations beside the main window; cycle in each and
  watch the main window follow; switch in the main window and watch both windows follow.
- Android (Fold 4 / emulator): cycle in each screen, back out, and the widget shows that source.
- Logs: `SOURCE_SWITCH_E2E trigger=COMPARISON|OBSERVATIONS platform=desktop|android`.

## Open questions

1. Android Stations repaints the widget only on close (`widgetContentChanged`), Forecast Comparison
   immediately. Proposal: keep each as is (the widget is hidden behind the screen either way).
2. Should the desktop main window's months/daily view mode affect which `SourceViewKind` the data
   screens record? Proposal: keep what each does today (Stations records `config.viewMode`; Android
   records `DAILY`).

## Implementation notes (2026-11-19)

- Verified: both DAOs store `triggerKind` as plain TEXT (no CHECK), so `COMPARISON` needed no migration.
  `SourceFetchGate` already counts any trigger.
- Android: a new `WidgetIntentActionHandler.switchFromScreen` reuses `WidgetSourceSwitchHost`; the
  screen kinds (`SwitchKind.COMPARISON`, `OBSERVATIONS`) share the API button's reason, stale reason
  (`toggle_api_stale`, so `SyncDoneNotice` owes the same banner) and action tag (`TOGGLE_API`, so the
  daily paint prefers stored today the same way). `ui/DataScreenSourceSwitch` launches it on
  `@ApplicationIoScope`; without a widget it only counts the view.
- Desktop: `cycleDisplaySource(..., trigger, ...)`; both windows read `config.displaySource`.
  The history window's config saves are now labelled `history-window` (they said `observations`).
- Open question 1 resolved by the runner: both Android screens now paint the widget at switch time,
  so Stations no longer owes an exit repaint for a source change.
- **Bug in the first cut (user, 2026-11-19: "clicking on NWS api in the forecast comparison window
  did nothing"):** the pills saved under the window labels (`history-window`, `observations-window`),
  and `resolveDesktopConfigSave` admits a `weatherSource` change only from `popup`, so the merge put
  the old source back (`CONFIG_SAVE … merged-away-stale-settings weatherSource`). Fixed with a
  dedicated pill save channel (`onSourceSwitchSave`) under `ConfigSaveSource.HISTORY_SOURCE_SWITCH` /
  `OBSERVATIONS_SOURCE_SWITCH`; the windows' other saves still cannot change the source. The tests
  had used a config holder that accepted every save; they now apply the real policy under the app's
  labels and fail with the shipped wiring ("expected OPEN_METEO but was NWS").
- **Wiring under test (user, 2026-11-19: "Should there be an integrated test for clicking on api
  source in 'forecast comparison'?"):** the labels moved out of `DesktopUiApplication` into
  `DataWindowSaves.forForecastComparison` / `forStations(saveConfigAndNotify)`, handed whole to the
  window and its content. `DesktopDataScreenSourceSyncTest` and `DesktopSourceCycleIntegrationTest`
  build their saves with the same factories; re-wiring either factory's pill to the window label fails
  3 tests (checked by mutation).

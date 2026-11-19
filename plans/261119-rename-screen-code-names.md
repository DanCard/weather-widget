# Rename screen code names to the names users see

Status: **approved 2026-11-19**

## Request

User, 2026-11-19: "Should we create a plan for refactoring function names?" → "proceed with full
refactor". Claude reads "full" as both screens, including the Stations screen's code names; persisted
and log-visible names stay (below), pending the user's confirmation.

## Evidence

Two screens are named differently in code than on screen (`rg -w`, main + test sources):

- **Forecast Comparison** (renamed from "Forecast History" in code on 2026-11-15, `805f9050b`; class
  names done, locals and helpers not): `onOpenHistory` 15, `historyShowRequestId` 10, `openHistory` 8,
  `historyVisible` 5, `HistoryData` 5, `setupHistoryShortcut` 4, `historyInitialDate` 3,
  `loadHistory` 2, `RefreshNoticeSurface.HISTORY` 14, `ConfigSaveSource.HISTORY_*` 5; tests
  `HistoryActivitySyncRoboTest`, `HistoryReloadOnReturnRoboTest`; `HistoryRefreshOutcome` (verify
  it is this screen's before renaming).
- **Stations** (window title "Stations"; Android screen title to verify): `WeatherObservationsActivity`
  56, `onOpenObservations` 56, `WeatherObservationsSupport` 27, `ObservationsWindow` 12,
  `setupWeatherStationsShortcut` 11, `obsShowRequestId` 9, `observationsVisible` 5,
  `ObservationsContent` 5, `RefreshNoticeSurface.OBSERVATIONS` 16, `ConfigSaveSource.OBSERVATIONS_*` 6;
  tests `WeatherObservationsActivityRobolectricTest`, `WeatherObservationsSupportTest`,
  `WeatherObservations*IntegrationTest`, `WeatherObservationsShortcutTest`, `ObservationsWindowRowsTest`.

## Proposed renames (Claude proposal; code-only)

| Now | New |
|---|---|
| `onOpenHistory`, `openHistory*`, `historyVisible`, `historyShowRequestId`, `historyInitialDate` | `onOpenComparison`, `openComparison*`, `comparisonVisible`, `comparisonShowRequestId`, `comparisonInitialDate` |
| `setupHistoryShortcut`, `HistoryData`, `loadHistory` | `setupComparisonShortcut`, `ComparisonData`, `loadComparison` |
| `RefreshNoticeSurface.HISTORY` / `.OBSERVATIONS` | `.COMPARISON` / `.STATIONS` (also the test tag `refresh_notice_*`) |
| `ConfigSaveSource.HISTORY_WINDOW` / `HISTORY_SOURCE_SWITCH` | `COMPARISON_WINDOW` / `COMPARISON_SOURCE_SWITCH` |
| `ConfigSaveSource.OBSERVATIONS_WINDOW` / `OBSERVATIONS_SOURCE_SWITCH` | `STATIONS_WINDOW` / `STATIONS_SOURCE_SWITCH` |
| `WeatherObservationsActivity`, `WeatherObservationsSupport` | `StationsActivity`, `StationsSupport` (manifest entry too) |
| `ObservationsWindow`, `ObservationsContent`, `onOpenObservations`, `observationsVisible`, `obsShowRequestId` | `StationsWindow`, `StationsContent`, `onOpenStations`, `stationsVisible`, `stationsShowRequestId` |
| `setupWeatherStationsShortcut` | `setupStationsShortcut` |
| test classes named after the old screens | the new names (`ForecastComparisonSourceSyncRoboTest`, `StationsActivityRobolectricTest`, …) |

Files are renamed with `git mv` so history follows them. KDoc that says "History of Forecasts" or
"Observations screen" for these screens is updated in the same pass.

## Kept on purpose (persisted or log-visible)

Renaming these breaks stored data or the log queries in `arch/` and memory:

- **config.json keys:** `historyWindowX/Y/Width/Height`, `obsWindowX/Y/Width/Height`, `obsSelectedTab`.
  The Kotlin properties keep their names (a rename needs `@SerialName` aliases for no user benefit).
- **Database values:** `SourceViewTrigger.OBSERVATIONS` and `COMPARISON` are stored by name in
  `source_view_days.triggerKind`.
- **Log values:** `SCREEN_RELOAD screen=history`, refresh reasons `"history"`, `logTiming(… "history" …)`,
  and the `CONFIG_SAVE source=` labels that predate today (`observations-window`). The constants
  move to the new names but keep these string values. (`history-window` / `history-source-switch`
  date from today and could take new values; proposal: keep them too, so one rule covers all.)
- **Data names:** the observation data model (`ObservationEntity`, `observations` lists, the
  Observations *tab* `ObservationsTabs.OBSERVATIONS`) is weather observations, not the screen.

## Open question: log tags

`AGENTS.md` asks for `TAG = "ClassName"`. Renaming `WeatherObservationsActivity` changes its tag,
and `Log.d`+ lines persist to `app_logs` under it. Proposal: the tag follows the class
(`"StationsActivity"`) and the rename's commit says so; old rows age out with log retention.
Alternative: keep the old tag string. **User decision needed.**

## Steps

1. Rename per the table with the IDE-safe approach (symbol-by-symbol `sed -w` over `app/`,
   `desktop/`, `shared/`, `git mv` for files), one screen per commit.
2. `rg` for leftovers of each old name; anything left must be on the keep list.
3. `CrossPlatformDuplicateNamesTest` (new names must not collide across `:app`/`:desktop`).

## Tests

- No behavior change: the existing suites must pass unchanged apart from renames.
- `CrossPlatformDuplicateNamesTest`, `LocaleResourceParityTest`, `WidgetActivityPendingIntentFlagsGuardTest`
  (PendingIntents name the activity class).

## Verification

- End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
- On device: open Stations and Forecast Comparison from the widget (the PendingIntents point at the
  renamed activity) on the Fold 4; desktop: open both windows, cycle sources, move a window and
  reopen (bounds still read from the kept config keys).

## Docs

- `arch/` mentions of the renamed classes (`rg` both old names) and memory notes that name them.

## Decisions (user, 2026-11-19)

- Approved as written, after the split plan.
- Log tag follows the class (`TAG = "StationsActivity"`); the commit notes the changed tag.

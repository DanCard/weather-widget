# Settings → Refresh data refreshes every source — summary (2026-10-29)

No plan file (small, single-rule change).

## User prompts

1. "When I hit refresh data from settings, it should refresh all sources"
2. "I thought I did this on desktop a few minutes ago and that didn't happen"
3. "yes" (approved the proposed fix; header Refresh left as is)
4. "write above summary to summaries/"
5. "I clicked refresh under settings"

## Evidence

- **Desktop, 22:01:40:** Settings → Refresh data fetched NWS only.
  ```
  REFRESH_CLICK  origin=settings … src=NWS
  REFRESH_ENTER  reason=user_refresh:settings parts=BOTH horizon=LONG source=NWS
  REFRESH        source=NWS hourly=156 daily=8 obs=21
  ```
  The Settings button and the header Refresh share `requestFullRefresh`, which builds one repository
  for `config.displaySource`. Other sources were fetched only by the daemon's forecast loop
  (`DaemonRuntime`, `nonActiveSources`) on its own schedule.
- **Android, Fold, 21:56:43:** already fetched every source:
  `FETCH_PLAN parts=GOOGLE_WEATHER:BOTH,OPEN_METEO:BOTH,NWS:BOTH,TOMORROW_IO:BOTH,SILURIAN:BOTH`.
  `CURR_FETCH_START targets=NWS` is complete as well: `CurrentConditionsRun.plan` groups sources by the
  feed their current temperature comes from, and every visible source on the Fold uses NWS.
- **Android latent gap:** a forced untargeted sync still applied the recently-viewed gate
  (`ForecastFetchCoordinator.planSync`, `backgroundSources = gate?.forecasts`), so a source not viewed
  in 8 days would have been skipped. The Fold fetched all 5 only because all had been viewed recently.

## What changed

- **Shared rule (`:shared` `SettingsRefreshAllSources`):**
  - `order`: the displayed source first, then the rest of the usable sources.
  - `bypassesViewGate`: an untargeted all-sources refresh skips the 8-day recently-viewed gate.
  - `refreshOthers`: refreshes each non-displayed source one at a time; a failure is logged
    (`SETTINGS_REFRESH … failed`) and the rest still run; cancellation propagates.
- **Desktop** (`DesktopUiApplication.requestFullRefresh`): for `origin == "settings"`, after the
  displayed source refreshes and publishes, the other sources refresh through the app-owned
  `refreshRunner` (closing Settings cannot cancel it). The graph reloads from cache when they finish.
- **Android:**
  - `SettingsActivity` enqueues with a new `KEY_ALL_SOURCES` flag, carried through `WorkInput`,
    `deferredSignature`, `FullSyncPipeline` and `ForecastFetchContext.allSources`.
  - `SourceFetchGateLoader.forSync` returns no gate for that sync; `ForecastRepository` uses it.
  - The widget's Refresh and source switching are unchanged.
- **Unchanged by request:** the desktop header Refresh still refreshes the displayed source only,
  matching the widget's Refresh on Android.

## Guard tests

Two architecture guards failed on the first version; the code was changed to satisfy them:

- `UserRefreshScopeGuardTest` requires every `userRefresh = true` call to sit lexically inside
  `refreshRunner.start { … }`. The per-source refresh moved from a separate host file into the block.
- `HostContractCoverageTest` requires every shared `interface Host` to have a two-platform database
  contract suite. This runner stores nothing and Android doesn't implement it, so `refreshOthers` takes
  two plain functions instead of a Host port.

## Tests

- `SettingsRefreshAllSourcesTest` (shared): order; the displayed source is not fetched twice; one
  failure does not stop the rest; cancellation propagates; when the gate is bypassed.
- `SourceFetchGateRoboTest`: Settings fetches a source not viewed in 9 days; widget Refresh, a targeted
  force and an unforced sync keep the gate.
- `WidgetWorkSchedulerCollisionTest`: the Settings request carries `allSources` into `WorkInput`; a
  widget refresh does not; a deferred Settings refresh does not fold into a widget refresh.
- `./scripts/unit-tests.sh`: 6399 passed.

## Runtime verification

- `installDebug` on the Fold and Pixel; desktop rebuilt and restarted (`buildStart-desktop.sh`).
- **Fold** (Settings → Refresh data tapped at 22:18):
  ```
  NET_FETCH_START force=true target=null …
  FETCH_PLAN parts=NWS:BOTH,OPEN_METEO:BOTH,SILURIAN:BOTH,TOMORROW_IO:BOTH,GOOGLE_WEATHER:BOTH
  SYNC_SUCCESS Weather=113, Snapshots=54, Hourly=525
  ```
  The banner that followed is Google's hourly quota (`PRODUCT_QUOTA source=GOOGLE_WEATHER
  product=HOURLY`): the request was made and refused, the existing quota banner, unrelated to this change.
- **Desktop** (user clicked Settings → Refresh data at 22:22:55; prompt 5: "I clicked refresh under
  settings"):

  | Time | Source | Result |
  |---|---|---|
  | 22:22:55 | OPEN_METEO (displayed, first) | hourly=552, daily=23 |
  | 22:22:59 | `SETTINGS_REFRESH start displayed=OPEN_METEO others=NWS,TOMORROW_IO,SILURIAN,GOOGLE_WEATHER` | |
  | 22:22:59 | NWS | hourly=156, daily=8 |
  | 22:23:01 | TOMORROW_IO | hourly=121, daily=7 |
  | 22:23:02 | SILURIAN | hourly=361, daily=14 |
  | 22:23:04 | GOOGLE_WEATHER | daily=10, hourly=0 (429, today's hourly quota) |
  | 22:23:05 | `SETTINGS_REFRESH done refreshed=NWS,TOMORROW_IO,SILURIAN,GOOGLE_WEATHER failed=none` | |

  About 10 s in all; the displayed source published after 4 s. Google's refresh counts as a success
  because its daily part arrived; the hourly refusal is the existing quota handling.

## Not done

- Not committed.

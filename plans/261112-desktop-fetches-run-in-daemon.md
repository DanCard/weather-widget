# Desktop: every data fetch runs in the daemon, not the window process

## Evidence

- The desktop UI process is ephemeral: it hard-exits 400 ms after its last window closes
  (`DesktopUiApplication.kt`, "All windows closed. Ephemeral UI process exiting…", `exitProcess(0)`).
- It runs seven fetches that write the database itself, through its own `DesktopWeatherRepository`
  on `uiScope` (`UserRefreshRunner` keeps them alive across *window* closes, not *process* exit):
  1. viewing refresh on popup / History / Observations open (`checkViewedSource`)
  2. Refresh (popup, History of the displayed source, Settings) + the observation window fill
  3. Settings → Refresh data: every other source (`SettingsRefreshAllSources`)
  4. History refresh of a non-displayed source (`requestSourceRefresh`)
  5. Observations window Refresh (`refreshObservations`)
  6. location change (`refresh(userLocationChange = true)`)
  7. on-demand hourly for a tapped day (`extendHourlyFor`) and deep history on zoom (`ensureHistory`)
- Closing the last window mid-fetch kills it. A failure there also cannot arm a `SourceRetry` that
  anything will run (plans/261112-refresh-failure-reason-and-retry.md), so those messages could say
  why but never when. The only UI → daemon signal is the lossy `.refresh-requested` touch file.
- Android already has the right shape: screens and widget share one app process; screen fetches run
  on the app-lifetime scope through the same `UserRefreshRunner`.

## Fix

1. **`FetchRequest` / `FetchResponse`** (desktop, `@Serializable`, one JSON line each) over a new
   Unix socket `fetch-request.sock` that the daemon serves (`DaemonFetchServer`). Kinds:
   `FORECAST` (source, lat, lon, reason, userRefresh, observationWindow, userLocationChange),
   `OTHER_SOURCES`, `OBSERVATIONS`, `HOURLY_DAY` (date), `HISTORY_DEPTH` (backHours).
2. **Daemon executes** on `daemonScope` through a daemon-owned `UserRefreshRunner`: the displayed
   source at the current site uses the daemon's own repository (publish + `notifyDataUpdated`), any
   other source or site a repository built for it (the non-active loop's recipe). A failed forecast
   fetch arms `SourceRetry` (and wakes the forecast loop). The reply carries `ok`, `changed`, and the
   failure class/message. A client that disconnects never cancels the fetch; its result is in the DB.
3. **UI** sends the request, awaits the reply, reloads its cache from the DB, and shows the notice
   (`failedOutcome` reads the reason and armed retry from the daemon's rows). Throttle, in-flight and
   banner logic stay where they are. The UI's repository remains for reads (`loadCached`, status).
4. **Daemon unreachable** (it spawns the UI, so this is a restart window): the request fails like a
   network error with `class=DaemonUnavailable`; the notice says "Couldn't refresh X".
5. Display-only network stays in the window: radar, rain loop, Nominatim / IP lookups.

## Tests

- Codec round-trip for every kind; unknown kind → error reply, not a dropped connection.
- Server + client over a real socket in a temp dir: a client that closes before the reply does not
  cancel the fetch (the executor still finishes and logs).
- Guard test: `DesktopUiApplication.kt` constructs no `DesktopWeatherService` and calls no repository
  fetch (`refresh`, `refreshObservations`, `extendHourlyFor`, `ensureHistory`,
  `refreshObservationWindow`).
- Live: History Refresh then close every window at once; `FETCH_REQUEST done` lands in the daemon's
  log and the rows are stored.

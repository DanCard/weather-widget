# Desktop Stations Refresh Window-Cancellation Fix

## Problem

The desktop Stations/Observations refresh button launches `repository.refresh()` from the
window-local `rememberCoroutineScope`. Closing that window removes its composition, cancels the
scope, and aborts the in-flight fetch. Live evidence on 2026-08-02 showed `REFRESH_ENTER` at
17:32:13 followed by `REFRESH_CANCELLED` at 17:32:17, with no terminal successful `REFRESH` row.

Settings previously had the same ownership defect and now launches through the application-level
`uiScope`. The Stations window still uses the old pattern.

## Implementation

1. Remove `DesktopWeatherRepository` ownership from `ObservationsWindow`.
2. Give the window caller-owned `isRefreshing` and `onRefreshData` parameters.
3. Extract the refresh icon into a small testable composable that renders the caller-owned state.
4. In `Main.kt`, use one app-owned full-refresh launcher for both Settings and Stations:
   - suppress duplicate full refreshes;
   - assign the returned forecast;
   - increment `dataUpdateCount` so an open Stations window reloads immediately;
   - notify the daemon only after successful completion;
   - retain durable origin-specific success/failure/cancellation breadcrumbs.
5. Add desktop UI regression tests proving the Stations button only dispatches a callback and that
   caller-owned in-flight state disables it.

## Verification

1. Run the focused desktop UI test class.
2. Run the desktop duration/category validation and desktop compile/package checks.
3. Rebuild and restart the distributable desktop app.
4. Open Stations, start Refresh, close Stations before the network fetch completes, and verify:
   - a successful terminal `REFRESH` row is written;
   - no new `REFRESH_CANCELLED` row is written;
   - the app/daemon receives the completed refresh notification;
   - reopening Stations shows the newly persisted observations.

## Non-Goals

- Changing station weighting, QC, Synoptic fallback, or current-temperature interpolation.
- Changing daemon scheduling or Android refresh behavior.

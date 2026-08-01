# Android Current Observations tabs

## Goal

Give Android's **Current Observations** activity the same content separation as the desktop
**Weather Observations & Logs** window: station cards and fetch logs occupy separate tabs instead
of permanently splitting the available height.

## Evidence

1. The live desktop window has an `Observations` / `Fetch Logs` tab row and gives the selected pane
   the full content height (`desktop/.../ObservationsWindow.kt`).
2. The live API-36 emulator shows the station list above a permanently visible 120dp fetch-log
   panel, divider, heading, and Close button. The fifth station is already partly clipped on the
   foldable emulator (`app/.../activity_weather_observations.xml`).
3. Android already loads station rows and fetch logs independently, and its observation/log flows
   refresh both datasets. This is a presentation problem; no database, fetch, or source-selection
   behavior needs to change (`WeatherObservationsActivity.kt`).

## Sharing decision

Keep the tab presentation platform-specific. Desktop uses Compose `TabRow`/`Tab`; Android uses an
AppCompat XML/View hierarchy and does not depend on Compose or Material tabs. Introducing a shared
tab abstraction would couple UI state without sharing any rendering code.

Continue using the behavior that is meaningfully shared already:

1. `ObservationSourceMatcher` for source/synthetic-row filtering.
2. `ObservationOrigin` for API/Web/QC/stale classification.
3. `StationHistoryUrl` for station history links.

The Android log query and formatting currently use Android's Room `AppLogEntity`, while desktop
uses `DesktopLogEntity` and different filter sets. Unifying those is outside this UI-only change.

## Implementation

1. Add a two-item tab row below the Android header, matching desktop's order: observations first,
   fetch logs second.
2. Put the subtitle/list and the fetch-log view in separate full-height content containers.
3. Default to observations. Clicking a tab swaps container visibility and updates text/indicator
   colors. Preserve the selected tab across activity recreation.
4. Keep source cycling, manual refresh, settings, Close, data loading, filtering, and formatting
   unchanged.
5. Add Robolectric coverage for the default pane, switching both directions, and recreation state.

## Verification

1. Run the focused `WeatherObservationsActivityRobolectricTest` class.
2. Build the debug APK.
3. Install on the API-36 emulator without clearing app/widget data.
4. Open/retain Current Observations and capture evidence that:
   - the observations tab has no fixed log panel and gains the content height;
   - the Fetch Logs tab shows the existing source-filtered logs in the full pane;
   - switching back restores the station list;
   - source and refresh controls remain available.

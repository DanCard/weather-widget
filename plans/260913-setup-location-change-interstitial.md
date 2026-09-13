# Setup-driven location change: paint "Getting weather for {place}…" instead of the old city

## Problem

When the user changes location through the setup screen (`ConfigActivity` on Android, the
location picker on desktop), the widget keeps showing the **previous** site's render until the
fetch for the new site lands — and, on Android, sometimes a **blank graph** in between:

- `LocationUpdater.applyActiveLocationToAllWidgets` writes the coordinates and enqueues a
  force-refresh worker but paints nothing. `FullSyncPipeline` paints only after the fetch
  succeeds; on failure it paints nothing, so the old city sits there indefinitely with no sign
  the save took.
- `writeActiveLocation` also enqueues a `location_changed` current-temp job whose tail is
  `refreshWidgetsFromCache()`. At a new site with no rows the bundle is empty and
  `DailyForecastGraphRenderer` returns a blank bitmap. Which of the two jobs wins the race decides
  whether the user sees old-city or blank.
- Desktop: a changed `config.lat/lon` re-creates the repository; `LaunchedEffect(repository)`
  calls `loadCached()`, which returns null at a site with no rows, so `forecast` keeps the old
  snapshot and `dataStatus` stays `Live`. Old city, no indication.

A setup change is the one location write where the user is guaranteed to be looking at the
widget within seconds ("did my choice register? did it pick the right place?"). A GPS
follow-device move is background: nobody is watching, the fetch is battery-gated, and CLAUDE.md
already settles that display is never withheld on that path. The paint policy should follow the
fetch policy: foreground/user-initiated gets feedback, background does not.

## Design

Shared rule (`:shared`, `LocationChangePaintPolicy.shouldShowInterstitial`):
show the interstitial only when **all** hold —
1. the change is user-initiated (setup screen / picker), never `applyFollowDeviceLocation`;
2. the new coordinates are a different site (`LocationMatch.sameSite` false, or no prior site);
3. the new site has no cached forecast row for today (switching home ↔ work must not flash).

Text: "Getting weather for {place}…" — the place name is the confirmation the user wants, not
"querying API".

### Android

- `WidgetRenderer.updateWidgetFetchingLocation(context, mgr, id, placeName)` — sibling of
  `updateWidgetLoading`; full push; root tap bound to `ACTION_REFRESH` (same intent as the error
  placeholder) so it never falls through to MainActivity on One UI.
- `WidgetStateManager.setPendingLocationFetch(label) / getPendingLocationFetch() / clear` —
  one pref key. Set synchronously on the setup path *before* the worker is enqueued so every
  later paint path can see it.
- `LocationUpdater.applyActiveLocationToAllWidgets(..., displayName)`: after
  `writeActiveLocation`, if the site changed, mark pending and enqueue the force-refresh with
  `KEY_LOCATION_CHANGE_PLACE`. The forced sync itself
  (`WidgetPaintCoordinator.paintLocationChangeInterstitial`, first thing in `FullSyncPipeline.run`)
  checks `forecastDao.getForecastForDate(todayUtc, lat, lon)`; no row and still pending → paint
  the interstitial on every widget; row present → clear pending, paint nothing (the cache repaint
  will produce a correct render). A run the `StartupCooldown` defers paints first, then defers
  the fetch. *(First cut launched this from the activity on a fire-and-forget IO scope; see
  Result.)*
- `WidgetPaintCoordinator.refreshWidgetsFromCache`: an empty bundle (no daily and no hourly rows)
  never pushes — if a location fetch is pending, re-assert the interstitial; otherwise log
  `WIDGET_PAINT_SKIP reason=empty_cache` and leave the screen alone. This closes the blank-bitmap
  race for the GPS path too.
- `WidgetPaintCoordinator.updateAllWidgets`: a render launched with forecast rows clears the
  pending flag.
- `FullSyncPipeline` failure branches (`SYNC_FAILURE`, `SYNC_EXCEPTION`): if pending →
  `painter.renderPendingLocationFetchFailure()` paints the existing "Tap to refresh" fallback
  on every widget and clears pending. The interstitial must never be a dead end.
- String `widget_fetching_location` in `values/` + the 19 locales (LocaleResourceParityTest).

### Desktop

- `DataStatus.FetchingLocation(placeName)` in `:shared` (`ForecastTypes.kt`); popup branch
  renders `CenteredMessage("Getting weather for …")`.
- `DesktopUiApplication.saveConfigAndNotify`: `source == "location-picker"` and site changed →
  `pendingLocationLabel = label`.
- `LaunchedEffect(repository, pendingLocationLabel)`: `loadCached()` non-null → adopt it, Live,
  done. Null → `forecast = null`, `dataStatus = FetchingLocation`, then `repo.refresh()` in the UI
  process (same pattern as `requestFullRefresh`, with `refreshInFlight` held): success → Live;
  exception → `DataStatus.Error("Couldn't get weather for X — …")`. The daemon's own
  `.config-changed` fetch still runs; whichever lands first wins.
- `reloadCachedForecast` and `requestFullRefresh` success: if status is `FetchingLocation` or
  `Error`, restore `Live` — otherwise a daemon push or a Settings → Refresh could load good rows
  under a stuck message.

### GPS path — unchanged

`applyFollowDeviceLocation` neither marks pending nor paints. The only GPS-path change is the
empty-bundle guard, which replaces "blank graph" with "leave the last render up".

## Tests

- `:shared` `LocationChangePaintPolicyTest` — the three gates.
- `LocationUpdaterTest`: setup change to a new site marks pending; same-site setup change does
  not; follow-device move never does.
- `PlaceholderTapTargetRoboTest`: interstitial claims the tap; text carries the place name.
- `WidgetStateManagerTest`: pending set/get/clear round-trip.
- `WidgetPaintCoordinator` empty-bundle guard — Robolectric test that an empty cache with a
  pending label re-asserts the interstitial and pushes no data render.
- `LocaleResourceParityTest`.
- Desktop: `DataStatus` popup branch is trivial; the effect logic is covered by a pure helper
  `resolveLocationChangeStatus` test if extracted; otherwise manual verification via
  `scripts/buildStart-desktop.sh` — pick a distant city, confirm the message, confirm the
  transition to data, and confirm the error message with network off.

## Verification

- Android: install on emulator, set a distant location via Settings → Set Location…, screenshot
  the interstitial, then the rendered new city; `app_logs` shows
  `WIDGET_PAINT caller=fetching_location` followed by `SYNC_SUCCESS` and a data paint. Repeat
  with airplane mode on: interstitial → "Tap to refresh".
- Desktop: as above.

## Result (2026-09-13)

Implemented as designed on both platforms; 1641 `:app` + 396 `:desktop` + 5 `:shared` unit
tests green, `LocaleResourceParityTest` included.

One addition found on-device: after the offline fallback painted "Tap to refresh", a follow-up
`missing_actuals` sync "succeeded" with 0 rows and its `updateAllWidgets` pushed an empty grey
graph over it. The empty-data guard therefore moved into `updateAllWidgets` itself
(`WIDGET_PAINT_SKIP reason=empty_data origin=…`), covering every caller; the cache path keeps its
own check only to re-assert the interstitial. `WidgetPaintCoordinatorEmptyDataGuardTest` covers it.

Verified on emulator-5554 (Lviv → Denver online: `FETCHING_LOCATION` push in the same second as
the save, `SYNC_SUCCESS` → `LOCATION_FETCH_PENDING action=cleared` 10 s later, no blank frame;
Chicago offline: interstitial → `render_error reason=sync_success_empty` → "Tap to refresh" holds;
back to cached Lviv: no interstitial) and on the desktop distributable (Lviv → Denver → Chicago:
`render_interstitial` → `cleared` at 38 s / 55 s — NWS's first fetch at a new site is slow — and
back to Lviv: `cached_rows_adopted`).

Follow-up, same day: every full-push placeholder (Loading, No location, Tap to refresh, this one)
showed the layout's XML header defaults ("72°", "NWS", nav arrows — `widget_weather.xml:1635`,
`:1407`) or, under a launcher `reapply()`, the previous render's header, because none of them
touched the header views; the gear and arrows also had no PendingIntent. Fixed with
`WidgetRenderer.applyPlaceholderChrome`: blank the data views, hide the arrows/icons, keep the
settings gear and bind it via `setupSettingsShortcut` (verified: gear → `SettingsActivity`).
`PlaceholderTapTargetRoboTest` asserts all four.

Also checked and *not* a bug: the 30 s `MIN_RENDER_INTERVAL_MS` throttle lives on a
per-worker-instance `WidgetPaintCoordinator`, so it never drops a paint from a different job.

## Follow-up (2026-09-13, later): `lightZ must be a finite positive, given=Infinity`

`ConfigActivityRobolectricTest > search result choice saves location and pins mode` failed once
in the Long bucket. Cause: the first cut launched the cache probe + paint from the activity on a
process-scoped IO coroutine; in Robolectric that thread outlived the test (Room open, RemoteViews
push via `AppWidgetManager`) and raced the next test's environment, whose dialog then read a
display density of 0 (`ThreadedRenderer.setLightCenter` divides by it). Order-dependent, so it
reproduced only sometimes.

Fix: the probe and paint moved into the forced sync (`KEY_LOCATION_CHANGE_PLACE` on the work
request → `FullSyncPipeline` → `WidgetPaintCoordinator.paintLocationChangeInterstitial`). No stray
thread; survives process death; `WidgetPaintCoordinatorLocationChangeTest` covers the three
outcomes (paint / cached-rows-adopted / superseded). Long bucket ×2 = 1076/1076.

Found while re-verifying: a save inside the `StartupCooldown` window (cold process, save within
~30 s) had its forced sync deferred, and with it the interstitial. `deferForStartupCooldown` now
paints the interstitial before deferring. Measured on the emulator (debug build, cold process):
interstitial in the same second as the save; fetch landed 68 s later (cooldown + a cold
current-temp job ahead of it in WorkManager). The cooldown is a deliberate policy and was left
alone; a bypass for location-change runs would be a one-line change if that latency is not
acceptable.

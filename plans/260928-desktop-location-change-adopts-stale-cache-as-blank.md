# Desktop: location change adopts a 13-day-old cache and shows nothing for ~26 s

## Symptom
Desktop, 2026-09-28 08:43:15: location picker → 94043 (Mountain View). The window displayed nothing
(no temperature, no graph, no "Getting weather for…" message) until 08:43:43.

## Evidence (desktop `weather.db`, `app_logs`)
```
08:43:15.453 CONFIG_SAVE          source=location-picker weatherSource: OPEN_METEO -> NWS
08:43:16.654 CURR_TEMP_RESULT     display=none estimate=none obs=none
08:43:16.683 LAUNCH_REFRESH_CHECK cachePresent=true action=FULL_FORECAST forecastAgeMs=1137230971 (13.2 days)
08:43:17.551 LOCATION_FETCH_PENDING action=cached_rows_adopted      <-- no interstitial
08:43:42.794 NWS_STATION_ACTUALS  (daemon refresh lands)
08:43:43.374 REFRESH source=NWS hourly=156 daily=7
08:43:43.845 CURR_TEMP_RESULT     display=56.89                     <-- first content
```
Newest NWS `forecasts` row at the site before the change: targetDate 2026-09-21, fetched 2026-09-15.
Nothing for today, nothing inside the hourly window (`getHourlyWithHistory` maxAge = 24 h).

## Root cause
`DesktopUiApplication`'s setup-driven location-change effect gates the interstitial on
`repo.loadCached() != null`. `loadCached` returns null only when hourly **and** daily are both
empty; `getDailyForecasts` returned the 13-day-old history rows, so the snapshot was non-null but
contained nothing drawable for the visible window. Desktop adopted it, set `DataStatus.Live(now)`,
and painted an empty graph until the daemon's fetch finished.

The contract in `LocationChangePaintPolicy` (and Android's `hasTodayForecastRowAt`) is "the new site
already has a forecast row **for today**". Desktop never calls `shouldShowInterstitial` at all — it
re-implemented the cache gate with a weaker test. Classic Android/desktop drift.

## Fix
1. Desktop: compute `hasCachedRowsAtNewSite` as "the cached snapshot has a daily row whose
   `targetDate` == today (UTC midnight)" and route the decision through
   `LocationChangePaintPolicy.shouldShowInterstitial(userInitiated = true, previous = null, …)`,
   same shape as Android. Stale-only cache → interstitial + in-process fetch (existing path).
2. Put the "today row present" test in `:shared` as a pure function (list of targetDates + today)
   so both platforms use one definition; Android's DAO probe keeps its query but the desktop path
   uses the snapshot it already loaded.

## Tests
- Shared unit test: today row present → adopt; only past rows (13 days old) → interstitial; empty → interstitial.
- Desktop test over the effect's decision helper with a snapshot containing only 2026-09-15..21 rows
  → `render_interstitial`.

## Verification
`scripts/buildStart-desktop.sh`, switch to a city not visited recently, confirm
"Getting weather for {place}…" appears then clears (`LOCATION_FETCH_PENDING action=cleared`); switch
back to a freshly cached city, confirm `cached_rows_adopted` and no flash.

## Addendum (user request): always confirm a user-initiated location change
User: "there should be a toast message or something after location change, saying retrieving data
for new location." This deliberately widens `LocationChangePaintPolicy`'s "no flash for a cached
site" rule. That rule still holds for the full-graph interstitial. The toast is added on top and
does not replace the graph.

- **Desktop**: reuse the existing top-center overlay `historyFetchToast` in `DesktopWidgetPopup`
  (generalize it into one transient-message slot). On every location-picker save to a different site,
  show "Getting weather for {short place}…" over whatever is drawn:
  - when there is no row for today: the full interstitial (fix above) and the toast text is redundant, so skip the toast;
  - when there is a row for today: the cached graph plus the toast.
  Clear the toast when the next data reload lands (daemon `.config-changed` fetch → UI reload) or,
  if `LAUNCH_REFRESH_CHECK` says no fetch is needed, right away. Use a 60 s safety timeout that swaps
  it for "Couldn't get weather for {place}". Log `LOCATION_FETCH_PENDING action=toast_shown/toast_cleared`.
- **Android**: `ConfigActivity`'s save paths already toast "Location: {label}". Change that to
  "Getting weather for {label}…" (new string plus 19 translations for locale parity). An Android
  Toast can't follow fetch completion, so the widget interstitial and the "Tap to refresh" failure
  paint keep handling that.
- Place name: short form (city), not Nominatim's full "860, Avery Drive, …" string. Use
  `FriendlyLocationName` if it covers this.

## As implemented (deviations from the addendum)
- **Desktop fetches in-process in both branches** rather than waiting for the daemon's reload with a
  60 s timeout: the daemon's `.config-changed` refresh is gated on the *source's* last fetch
  (`getLastSuccessfulFetch`), not the site's, so it can skip a days-stale site entirely. The banner
  now lives exactly as long as a real fetch and ends on its success or failure (4 s failure banner).
  Banner ownership is a per-change token (`LocationBanner`), so a superseded pick never clears a
  newer one's banner.
- **Place name**: `shortPlaceNameOrNull` in `:shared`. A letterless lead component is ambiguous
  (house number in "860, Avery Drive, …", postcode in "94043, Mountain View, …", as the emulator
  search showed), so it yields null → "the new location", upgraded to the reverse-lookup
  `friendlyName` ("Mountain View, California") when that lands, the same name Android's toast uses.
- **Android**: reused the already-translated `widget_fetching_location` string for the global-save
  toast (no new translations); removed the now-unused `location_saved_success_named` from all locales.
  Verified on emulator-5554: 94043 search → toast "Getting weather for Mountain View, California…"
  over the cached widget graph.

## Revision 2 (user feedback after emulator test)
User on emulator-5554: "I didn't see toast message, did see old location for like 8 seconds. Would
prefer a toast message be up, while old location data is shown." Logcat: `Toast already killed`
~4 s after save; the new render landed 14 s after it. A toast can't span the fetch.

- `LocationChangePaintPolicy.feedback(userInitiated, siteChanged, hasRenderToKeep)` → NONE /
  BANNER / INTERSTITIAL. BANNER whenever something is on screen; INTERSTITIAL only first-ever.
- Android: `LocationChangeBanner` — sets the widget's existing transient message (every view
  handler binds it) with a 120 s safety expiry, pushes a banner-only partial update at save time,
  and the forced sync (`KEY_LOCATION_CHANGE_BANNER`) clears it before its final paint, or clears it
  and paints "Tap to refresh" on failure. No pending-interstitial mark in banner mode.
- Desktop: `DesktopLocationChangeFeedback.decide` — today row → adopt new cache under the banner;
  else keep the previous site's graph under the banner; `holdForLocationChange` stops the three
  reload paths from swapping in an undrawable new-site snapshot mid-fetch.
- Verified on emulator-5554 (Mountain View → Warsaw): banner at 09:02:54 over the old graph,
  startup-cooldown deferral 24.5 s, sync 09:03:19–09:03:43, `banner_cleared`, Warsaw drawn.

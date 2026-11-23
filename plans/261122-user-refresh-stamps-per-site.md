# User-refresh stamps per site

## Report

Desktop, 2026-11-22: "I changed location then hit refresh all and got a message: already up to date".

## Evidence (desktop `app_logs`)

1. 08:59:44 Settings → Refresh data at Scottsdale (33.645,-111.911): partition
   `fetch=OPEN_WEATHER_MAP,GOOGLE_WEATHER,OPEN_METEO,TOMORROW_IO,WEATHER_API,SILURIAN skip_fresh=NWS(12s)`;
   all six refreshed there by 08:59:51.
2. 09:01:31 location picker → Mountain View (37.417,-122.089). `LOCATION_CHANGE_REFRESH action=fetch
   source=NWS` fetched **NWS only** (forecast age there 8.6 h).
3. 09:01:51 Settings → Refresh data:
   `partition fetch=none skip_fresh=NWS(18s),GOOGLE_WEATHER(126s),OPEN_METEO(126s),…` → `none=ALL_FRESH`
   ("Already up to date").

NWS(18 s) is correct — it was fetched at the new site. The other six at 126 s are the 08:59:44 click
at the **old** site: none had been fetched at Mountain View.

## Root cause

`SettingsRefreshAllSources.partition` takes `latest(lastFetchMs, lastUserRefreshMs)`. Since
plans/261121-desktop-location-change-freshness-and-config-clobber.md, `lastFetchMs` is site-keyed,
but the user-refresh stamp is still keyed by source only:

- desktop: in-memory `lastUserRefreshMsBySource[sourceId]` (`DesktopUiApplication.kt`)
- Android: `FetchMetadata.getLastUserRefreshTime(context, sourceId)` (prefs)

So a refresh at site A suppresses refreshes at site B for 10 min. The same stamp feeds the
single-source throttle (`UserRefreshThrottle.check`) — popup/widget refresh, comparison screen,
observations screen — so those are site-blind too (e.g. "Refreshed 2 min ago, wait 8 min" right
after moving).

## Proposed fix (Claude proposal)

1. `:shared`: `UserRefreshStampKey.of(id, lat, lon)` = `"$id@${quantize(lat)},${quantize(lon)}"`
   using `LocationMatch.quantize`, so the stamp follows the same site identity as fetch metadata.
   A null site keys by id alone (no location → nothing to fetch anyway).
2. Desktop: every read/write of `lastUserRefreshMsBySource` (settings partition, popup refresh,
   comparison, observations) uses the site key for the config's current lat/lon;
   `desktopSettingsRefreshPartition` takes the keyed lookup.
3. Android: `FetchMetadata.get/setLastUserRefreshTime` callers (`SettingsActivity`,
   `SettingsRefreshPartitionLoader`, `WidgetRefreshCoordinator`, `ForecastComparisonActivity`,
   `CurrentObservationsActivity`) pass the site-keyed key. Old unkeyed prefs entries simply age out
   (10-min window); no migration needed.

## Tests

- Shared unit: `UserRefreshStampKey` quantizes (two coords in one LocationMatch box → same key;
  different sites → different keys).
- Shared unit: `partition` with a stamp at site A and no fetch at site B → B fetches all.
- Desktop integration (real SQLite `DesktopWeatherDao` + `desktopSettingsRefreshPartition`):
  refresh-all at A, fetch row for NWS only at B, partition at B → `fetch` = the six others,
  `skip_fresh=NWS`. Must fail on current code.
- Android Robolectric: `SettingsRefreshPartitionLoader.load` with a stamp written at A, location B
  → nothing skipped.

## Docs

Update the settings-refresh / user-refresh throttle section in `arch/` (rg `FRESH_SKIP_MS`,
`UserRefreshThrottle`) to say the stamp is per source per site.

## Verification

Desktop: refresh all, change location, refresh all → partition logs `fetch=` the non-fetched
sources. End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

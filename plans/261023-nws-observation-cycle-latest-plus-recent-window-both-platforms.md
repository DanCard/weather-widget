# NWS observation cycle: latest + last 90 min on both platforms (option A)

User, 2026-10-23: "Lets go with option A" — after asking why Android and desktop fetch NWS
observations differently ("I don't see why desktop and android should be different").

## Before
| | Android current-temp cycle | Desktop current-temp cycle |
|---|---|---|
| Per station (≤ 5) | `latest` only | `latest` + `observations` for the last 90 min (`recentOnly`) |
| Readings between two cycles | missed until a repair job (`HourlyObservationBackfill` → 72 h backfill, only when the hourly view notices holes) | stored every cycle |
| Silent-vs-failed verdict | from the `latest` outcome | from the window outcome + web leg |
| Code | `NwsCurrentObservationUpdater.fetchAndStoreStation` | `DesktopWeatherService.fetchObservationBundles` |

Desktop moved to the window in August after finding dropped readings
(`plans/260820-observation-loop-recent-window-not-latest-row.md`); Android never followed. No product
reason for the difference.

## Change
- `:shared` `NwsStationBundles.fetch`: desktop's per-station step, moved — web leg started first,
  then per station in parallel `latest` (shared `NwsStationLatestFetcher`) + the window (shared
  `NwsStationHistoryFetcher`, web fallback for the first stations), the verdict (store / touch /
  fail) and its logs (`NwsStationAttemptLog`). Platforms pass their fetchers and a Host (touch, log).
- Desktop: `fetchObservationBundles` delegates (behaviour unchanged).
- Android: `fetchNwsCurrent` uses it with the 90-minute window — stores latest + window + cloud
  carrier, recomputes each touched day once, blends the latest readings as before. About 5 more
  small NWS requests per cycle; the recent-gap repair job stays for long outages.
- Not in scope: the header blend itself (Android `SpatialInterpolator.interpolateIDW`, desktop
  `NwsBlend.build`) — audit item E, current-temp resolution.

## Tests
Shared bundle tests with fake fetchers (window rows stored, silent station touched, failure logged,
slow station's latest kept on an empty recent window); Android updater test that a cycle stores the
window's intermediate readings; existing desktop observation tests unchanged; emulator tests.

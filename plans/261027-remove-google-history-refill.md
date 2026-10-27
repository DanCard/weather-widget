# Remove the Google history refill

**User, 2026-10-27:** "remove the Google history refill"

Reverses [plans/261010-google-history-refills-stale-elapsed-hours.md](261010-google-history-refills-stale-elapsed-hours.md).

## Why

Since a2f157d8 ([plans/261027-pre-hour-freeze-retention-and-shared-pick.md](261027-pre-hour-freeze-retention-and-shared-pick.md))
every started hour freezes at its newest pre-hour forecast ("Freeze wins always"). A refill
rewrites live rows *after* the hour, so the stitched line draws it only where no pre-hour snapshot
survives, which in practice is never. Each refill still spends Google `history/hours` quota (20/day
per user, shared by every device).

## Keep

`GoogleWeatherApi.needsHistory`, the first-coverage fill. A site with no history hours in the last
day still requests `history/hours` on any fetch. It writes hours that have no forecast at all,
which is the "no pre-hour row → earliest" case, so it still draws.

## Remove (both platforms, shared first)

1. `:shared`
   - Delete `GoogleHistoryRefill`.
   - `GoogleHistoryRun`: drop `staleHours`, `refill`, the `not_requested` stale log, and the
     `Host.liveHours` / `Host.saveRefill` members. `needsHistory(nowMs)` reduces to first coverage.
   - `SourceFetchTable`: drop `Request.refillDay` and the `refillDay` parameter of
     `Host.googleNeedsHistory`.
   - Fix KDoc in `ForecastTypes.providerHistoryHourly`, `HourlyForecastWrite`, and `SettledForecastPick`.
2. `:app`
   - Drop `ForecastFetchContext.historyRefillDay` and the `ForecastRepository` /
     `WeatherRepository.fetchSourceOnDemand` parameter.
   - `ForecastFetchCoordinator`: drop `refillStaleGoogleHours` and `googleHistory`.
   - `RoomSourceFetchHost`: drop the refill parameter.
   - `HourlyForecastStore`: drop the refill write and the host members.
   - `ForecastHistoryActivity`: a past day's Refresh becomes the same source refresh as any other day.
3. `:desktop`
   - Drop `refillDay` from `DesktopWeatherRepository.refresh` / `fetchForecast`, plus
     `refillStaleGoogleHours` and `refillGoogleHistory`.
   - Drop it from `DesktopWeatherService`, `WeatherApiClient`, and `DesktopSourceFetchHost`.
   - `DesktopUiApplication.requestFullRefresh` / `requestSourceRefresh` / `requestObservationsRefresh`
     lose it. A past day's Observations Refresh then does what today's does: observations only.
   - Fix the `ForecastHistoryWindow` KDoc.
4. Tests
   - Delete `GoogleHistoryRefillTest` and `DesktopGoogleHistoryRefillTest`.
   - Remove the refill cases from `GoogleHistoryHostContract`, `SharedFetchStepsTest`,
     `SourceFetchTableTest`, and `GoogleWeatherFetchIntegrationTest`.
   - Keep and verify the first-coverage `needsHistory` cases.
   - Add one shared test: a past-day request never asks for `history/hours` when coverage exists.

Existing refilled live rows stay. They are ordinary post-hour rows, and the freeze already ignores
them wherever a pre-hour snapshot exists.

## Verify

`./scripts/unit-tests.sh`; parity diff; rebuild and restart desktop; `installDebug`. Then a past-day
History Refresh on desktop and on a phone logs no `history/hours` request (`GOOGLE_HISTORY_REFILL`
absent, Google fetch without `includeHistory`).

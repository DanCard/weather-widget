# Desktop: borrowed NWS actuals are never fetched by a full refresh

## Symptom (2026-10-07 02:48 PDT)

Display source Google Weather, actuals provider switched METAR → NWS in the Observations window.
NWS showed KNUQ 64 °F; the desktop hourly graph showed KNUQ 66.2 °F.

## Evidence

- KNUQ METARs: `070915Z … 19/14` (02:15 PDT, 66.2 °F) and `070935Z … 18/14` (02:35 PDT, 64.4 °F).
  The app holds the 02:15 report from all three feeds (NWS fetched 02:28:49, Synoptic 02:29:07,
  METAR 02:36:22) and has no 02:35 row. 66.2 was correct when fetched; it was one report stale.
- The provider switch at 02:48:13 logged `LAUNCH_REFRESH_CHECK reason=actuals_provider_change
  actualsProvider=NWS action=FULL_FORECAST`. Both that refresh and the two refresh clicks after it
  logged `REFRESH source=GOOGLE_WEATHER … obs=0`, taking about 1 s each with no NWS request.
- Last `OBS_REFRESH` was at 01:56. Each config change (about 40 source switches between 02:28 and
  02:48) cancels `fetchJob`, which restarts the observation loop's initial `delay()`. No loop tick
  has fired since.

## Root cause

`DesktopWeatherRepository.fetchBorrowedRecovery` runs only for providers METAR and SYNOPTIC. A
display source that borrows **NWS** (Google, Silurian, …) gets no observations from
`refreshWithOutcome`. That is the path behind the refresh button, provider/source changes,
launch, and wake. Only the periodic `refreshObservations()` loop reaches NWS:
`fetchObservationsOnly` already routes `NWS → fetchNwsObservationsOnly`. Every restart of that
loop pushes the fetch back by another full interval.

Related: `refreshObservationWindow` (the deferred 7-day pull) checks `displaySource == NWS` rather
than the resolved provider. Borrowed-NWS sources therefore never get the 7-day backfill either.
The `fetchBorrowedObservationsOnly` KDoc also claims NWS borrowing is unwired; that has not been
true since `fetchObservationsOnly` gained the NWS branch.

## Fix (revised: move the decision to `:shared`, do not patch the desktop copy)

The bug exists because the "which observation feed to fetch" decision is written separately per
platform, and on desktop twice: once in `fetchBorrowedRecovery`, once in `fetchObservationsOnly`.
A review of `:shared` cannot see it.

1. Add `ObservationFetchPlan` to `:shared`: a pure function of `(displaySource, provider, trigger)`,
   where `trigger` is FULL_REFRESH, RECENT, or DEFERRED_WINDOW. It returns the feed to fetch and its
   window (recent only, recovery, or 7-day).
2. Make desktop's `fetchBorrowedRecovery`, `fetchObservationsOnly` dispatch, and the
   `refreshObservationWindow` gate all call it. Make the Android worker's observation dispatch call
   it too. Each platform keeps only the code that performs the fetch.
3. Add an exhaustive contract test in `:shared` covering every
   `WeatherSource × ActualsProvider × trigger`. Google+NWS on FULL_REFRESH must fetch NWS recent
   observations, and DEFERRED_WINDOW must fetch the 7-day window.
4. Search `:app` and `:desktop` for code that branches on `providerIdFor` or a specific
   `WeatherSource`. List the decisions that are duplicated across platforms. Move the cheap ones
   into `:shared` in this pass; list the larger ones for the user.
5. Fix the stale KDoc on `fetchBorrowedObservationsOnly`.

Out of scope: the observation-loop timer reset on every config change. With (1), a provider/source
change fetches actuals immediately, so the reset no longer leaves the reading stale.

## Verification

Rebuild and restart the desktop app with display source Google and provider NWS. Click refresh and
expect `REFRESH … obs>0`, `BORROWED_NWS_RECOVERY rows>0`, and the latest KNUQ row in the DB.

## Implemented (2026-10-07)

- `:shared` `ActualsFeedPolicy`: `feedFor` (provider + NWS coverage), `fullRefreshFetch`,
  `hasDeferredHistoryWindow`, `borrowers` (now backs `MetarFetchPolicy`/`SynopticFetchPolicy.consumers`),
  `requiresFeed`, `currentTempFeeds`.
- **Reuse stored rows** (user's question: "NWS data already exists, can't reuse?"). Observations are
  stored per site, not per displayed source. A borrowed-NWS full refresh takes the recent window
  whenever the newest stored NWS row is under 60 min old (`NWS_RECENT_WINDOW_COVERS_GAP_MS`). Only
  a real gap pays for the 7-day pull.
- Desktop: `fetchObservationsOnly` dispatches on `feedFor`; `fetchBorrowedRecovery` and
  `refreshObservationWindow` use the policy; the `BORROWED_<FEED>_RECOVERY` log covers every feed.
- Android: the current-temp loop uses `currentTempFeeds`, so a feed inherits its consumers' rank and
  active status. The full-sync NWS backfill uses `requiresFeed`. The Observations screen refresh
  dropped its own provider dispatch; it had the same bug, because `refreshCurrentTemperature(source =
  provider)` skips a provider that is not visible.
- Tests: `ActualsFeedPolicyTest` (14, including every source × candidate provider),
  `DeferredObservationWindowTest` (+3, borrowed NWS), `WeatherRepositoryTest` (+1, Android loop).
- Verified live on desktop: `BORROWED_NWS_RECOVERY recentOnly=true rows=36` (~2 s), then
  `OBS_WINDOW_REFRESH rows=1726 ms=2482`; KNUQ 02:35/02:55 = 64.4 °F stored.

## Left for later (found in the sweep, not changed)

- NWS as the *displayed* source still pulls 7 days on every refresh click
  (`fetchForecast(recentObservationsOnly = false)`). The reuse-stored-rows rule above applies
  there too.
- Desktop `persistObservations` special-cases `providerIdFor(...) == TOMORROW_IO` in two places
  (desktop-only, no Android twin to drift from).
- `DaemonRuntime.runLaunchRefresh` reads the last observation fetch by `providerIdFor`, which
  ignores NWS coverage.

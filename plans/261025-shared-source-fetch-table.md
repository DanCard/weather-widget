# One per-source fetch table: what each source's fetch asks for and what rides on it

Follows `261023-duplicate-logic-audit-different-names.md` → "Next" item 2, the last of B: the fetch
registry. Forecast persistence (`ForecastPersistRun`) and observation writes (`ObservationWriteRun`) are
shared; what each platform still writes itself is *how to fetch source X and what runs after it*.

## Why
| | Android | Desktop |
|---|---|---|
| Per-source request | `ForecastFetchCoordinator.buildFetchRegistry` (map of `SourceFetchEntry`) | `DesktopWeatherService.fetchForecast` (`when` over the source id) + `fetchOpenMeteoForecast`, `fetchTomorrowIoForecastWithFiveMinuteHistory`, `fetchOpenWeatherMapForecastWithCurrent`, `fetchNwsForecast`, `googleStoredHours`, `googleNeedsHistory` |
| Follow-up jobs | declared on the source (`afterPersist`) or after the fetch loop | `DesktopWeatherRepository.refresh` → `runPostFetchBackfills`, `backfillWeatherApiHistoryIfNeeded`, `maybeFetchPriorDayCloudForecast`, after **every** source's refresh |

Both platforms fetch every due visible source (desktop: one `DesktopWeatherRepository` per source,
`DaemonRuntime` `otherRepo`). The API clients are already `:shared`; the knowledge of how to call them
and what follows is written twice.

## Already the same (written twice, no decision)
- Open-Meteo request: max forecast days, 7 history days, parts; its nowcast saved from the same reply.
- Tomorrow.io: forecast and five-minute history in parallel, history only when the daily part is asked,
  a history failure keeps the forecast.
- Google: `includeHistory` from `GoogleHistoryRun.needsHistory`, `hoursAhead` from `HourlyOnDemand.askHours`,
  the `GOOGLE_HOURS_PAGES` line, the stale-day refill.
- NWS hourly-only fetch: `NwsForecastFetch` with the requested parts.
- No cross-source fallback: a failed source throws on both (desktop logs `SOURCE_ERROR`).

## Divergences (read 2026-10-25)
| # | | Android | Desktop | Effect today |
|---|---|---|---|---|
| F1 | A forecast reply's provider *current* reading | not stored by the forecast fetch (the current-conditions path stores it) | Open-Meteo and OpenWeatherMap forecast fetches also store it as `<SOURCE>_MAIN` | same key as the current path's row, so mostly a re-write of the same reading; a second writer of one row |
| F2 | Google `storedHours` (decides which hour pages to re-fetch) | this source on the site's exact key | same-site tolerance box, plus rows with no location | desktop can count a jitter fragment's hours as stored and skip pages; the forecast-persist D1 rule is exact key |
| F3 | Prior-day cloud (`PriorDayCloudRun`) | after an Open-Meteo fetch | after every source's refresh (the service returns nothing unless the product exists) | desktop asks the run once per source refresh; the run throttles to once an hour, so mostly no-ops |
| F4 | WeatherAPI history (`WeatherApiHistoryRun`) | after a WeatherAPI fetch that asked for the daily part | after every WeatherAPI refresh, daily part or not | an hourly-only WeatherAPI refresh can spend a history request on desktop |
| F5 | NWS station actuals (`NwsStationActualsRun`) | after an NWS fetch that asked for the daily part | after every source's refresh | desktop runs it once per visible source's refresh; the run is idempotent (missing dates only), so extra reads, not extra writes |
| F6 | NWS full (daily) fetch | forecast only; station observations come from the backfiller / current cycle | also resolves stations and pulls their observations into the same `RawFetch` | two different paths feed NWS observations; desktop's is a third writer of NWS station rows |

## Decisions needed (recommendation first)
- **F1 — Android's: a forecast fetch stores provider history only; current readings come only from the
  current-conditions path.** One writer per row. Alternative: both platforms store it from the forecast
  reply too.
- **F2 — exact site key on both**, as `ForecastPersistRun` decided (D1): a fragment's hours are another
  site's.
- **F3 — rides the Open-Meteo fetch (Android's).** The only source with the product.
- **F4 — rides a WeatherAPI fetch that asked for the daily part (Android's).** History is a daily product.
- **F5 — rides an NWS fetch that asked for the daily part (Android's).** It writes NWS's own rows only.
- **F6 — investigate before deciding.** Desktop's NWS observation pull in the forecast fetch predates the
  shared `NwsStationBundles` cycle; whether it is still needed (or only re-fetches what the cycle stores)
  needs a read of desktop's observation loop and a DB comparison. Proposed: phase 2, with its own
  measurement and recommendation.

## Design
- `:shared` `SourceFetchTable`: per source, `request(source, parts, context) → RawFetch` over a `Clients`
  port (the `:shared` API clients plus the per-platform reads Google needs: stored hours, history need)
  and `followUps(source, parts) → List<FollowUp>` (prior-day cloud, WeatherAPI history, NWS station
  actuals, Google refill) that each platform's `Host` runs through the existing shared runners.
- Android: `buildFetchRegistry` becomes a lookup in the table; `SourceFetchEntry.afterPersist` goes.
  Desktop: `fetchForecast`'s `when` and the per-source helpers become the table; `refresh`'s follow-ups
  run from `followUps(source)` instead of unconditionally.
- Contract: a `SourceFetchTable.Host` contract on Room and desktop SQLite (stored hours on the exact key,
  history need), forced by `HostContractCoverageTest`.
- Not in this plan: `refresh()` orchestration beyond the follow-up list (gates, retention, logging order).

## Phases
1. The table for the request half (F1, F2): both platforms fetch through it; per-source helpers deleted.
2. Follow-ups (F3–F5) from the table; F6 measured and brought back as a recommendation.

Each phase: tests that fail on the old code for each decided divergence, full suite incl.
`:app:compileDebugAndroidTestKotlin`, install on the phones, restart desktop, check `FETCH_PLAN` /
`GOOGLE_HOURS_PAGES` / follow-up log lines on a device, Progress line here and in the audit plan.

## Verification on devices
- F2: `GOOGLE_HOURS_PAGES` on desktop shows the same `reason=` / page count as Android for the same site.
- F3–F5: desktop's prior-day cloud, WeatherAPI history and NWS station-actuals lines appear once per
  owning source's fetch, not once per visible source.
- Request counts per hour per source (`GOOGLE_REQUEST`, `api_usage_stats`) unchanged or lower.

## Progress
- 2026-10-25 — Phase 1 (F1–F5 approved as recommended, F6 to measure). `:shared` `SourceFetchTable`
  (`data.remote`): each source's request (parts, Open-Meteo `past_days`, Tomorrow.io history pairing,
  Google's history need / stored hours / pages) over injectable `Calls`; NWS stays `Host.fetchNws` until F6.
  `SourceFetchTableHostContract` on Room and desktop SQLite. Android's registry and desktop's
  `fetchForecast` / `fetchForecastAhead` fetch through it; desktop's per-source helpers deleted.
  - F1: desktop no longer files the Open-Meteo / OpenWeatherMap forecast reply's current reading.
    `OpenWeatherMapDesktopServiceTest` made distinct (its current reading shared the first forecast slot's
    time and value, so the provider-history row satisfied it) — fails on the old service.
  - F2: exact site key; the contract fails under desktop's old tolerance-box filter (the fragment's hour).
  - Also unified, no decision: desktop's on-demand Google fetch now passes stored hours and parts as
    Android's did; a failed Tomorrow.io history now logs `FETCH_TMRW_5M_FAIL` on desktop too (was logcat);
    on a failed history the forecast reply keeps its own current fields (desktop's; Android never read them).
  - Follow-ups (F3–F5) unchanged: Android `afterPersist`, desktop `refresh` — phase 2.
- 2026-10-25 — Phase 2 (F3–F5; F6 not yet measured). `:shared` `SourceFetchTable.runFollowUps(source,
  parts, stored, FollowUpJobs)` owns which follow-ups ride a fetch (`followUps`: prior-day cloud on
  Open-Meteo, WeatherAPI history / NWS station actuals on a daily fetch of their own source — the plan's
  parts, not `requested`'s widening), whether a failed fetch still runs them (`FollowUp.afterFailedFetch`:
  NWS station actuals only), their order and `FOLLOW_UP_FAIL` logging. Platforms pass `FollowUpJobs` (each
  an existing shared runner, so no new Host port / contract).
  - User 2026-10-25: keep Android's NWS timing (station actuals even when the NWS forecast fetch failed),
    and desktop and phone run the same code. Desktop now runs them from its failure path too.
  - Android: `SourceFetchEntry.afterPersist` and the post-loop NWS block gone; one `runFollowUps` per
    fetched source after the loop. `fetchSingleSource` (on-demand day) runs none, as desktop's
    `extendHourlyFor` (it ran WeatherAPI history / prior-day cloud before).
  - Desktop: `refresh` runs them right after persist (WeatherAPI history before the extremes recompute, as
    before; NWS station actuals now also before it, was after); `refreshHourlyOnly` runs them too (prior-day
    cloud on an hourly-only Open-Meteo fetch). F3 was already gated inside `PriorDayCloudRun` (no service
    call for other sources); F5 was not — every visible source's refresh ran the NWS station-actuals read.
  - Tests that fail on the old code: `DesktopRefreshFollowUpsTest` (3 of 4: Open-Meteo / other source ran
    NWS station actuals; failed NWS refresh ran none), `ForecastFetchCoordinatorFollowUpsTest` (on-demand
    ran WeatherAPI history). `SourceFetchTableTest`: the table, failed-fetch rule, failure logging.
  - Full suite 6068 passed, `:app:compileDebugAndroidTestKotlin` ok; installed on both phones and both
    emulators; desktop restarted 16:28 — first refresh (Tomorrow.io) ran no follow-ups, no
    `SOURCE_ERROR` / `REFRESH_FAIL` since 12:25.
  - Phase 1 open check closed: desktop's `GOOGLE_HOURS_PAGES pages=8 reason=no_stored_hours_given
    hours=192` is a LONG 192 h fetch (Google ignores stored hours past 72 h, `GoogleWeatherApi`), not F2;
    stored Google rows are on the quantized key.
  - Pixel `app_logs` (backup 2026-10-25 16:46): Google `pages=8 reason=no_stored_hours_given hours=192` at
    14:31, the same line as desktop's for the site (F2 device check). No follow-up lines since the ~16:25
    install yet; desktop logged `NWS_STATION_ACTUALS_OUTCOME` 53× in 2 days against the Pixel's 8 (F5).
- 2026-10-25 — F6 measured (desktop `weather.db`, Pixel backup `backups/20261025_164643_pixel_7_pro_*`):
  - Coverage is the same without it: NWS rows per day at the site, last 8 days, desktop vs Pixel within
    ±9 rows/day, same stations (4–5) every day. The Pixel's NWS forecast fetch pulls no observations; its
    week comes from `NwsObservationBackfiller` (gap-targeted, `OBS_HOURLY_BACKFILL_REQ` 155 in 2 days) and
    the current cycle.
  - Desktop pulls the whole 7-day window ~10×/day: 6 inside non-deferred NWS forecast fetches + 15
    `OBS_WINDOW_REFRESH` in 2 days, each 5 stations, ~960–1040 rows, 4–14 s (one 337 s). Every pull
    rewrites the window (`INSERT OR REPLACE`, NWS history readings carry no `fetchedAt`): grouping NWS rows
    by write minute, the latest window pull owns 495 rows, the one before 383 — a re-write, not new data.
  - Borrowers already use the shared `ActualsFeedPolicy.fullRefreshFetch` rule (recent 90-min window when
    stored rows reach the last hour, 7 days only on a real gap): 41 recent pulls, 0 full, in 2 days. NWS
    itself is exempt (`feed == displaySource → null`), so its forecast fetch always takes 7 days.
  - Recommendation: Android's — the NWS forecast fetch is forecast only on both (`NwsForecastFetch` through
    the table; `Host.fetchNws` and desktop's in-fetch station pull go; the fetch-time current reading goes
    with it, as F1). Desktop's NWS refresh then takes its observation pull through `fullRefreshFetch` like a
    borrower (recent unless a real gap). Not in this plan: sharing the week's gap-fill itself (Android
    `NwsObservationBackfiller` vs desktop `refreshObservationWindow`) — the next twin, its own plan.
- 2026-10-25 — F6 approved as recommended (user) and done:
  - `:shared` `NwsForecastReply.fetch` is the NWS forecast fetch on both (Android's `NwsForecastMapper` body
    and diagnostic lines moved there; the class is now a thin Hilt adapter). NWS is `SourceFetchTable.Calls.nws`;
    `Host.fetchNws` gone. Desktop's `fetchNwsForecast` / `fetchNwsObservations` / `NwsObservationFetch` deleted,
    and with them the fetch-time current reading and `RawFetch.nwsDailyExtremes` (set, never read). Desktop
    NWS hours now carry `fetchedAt` and desktop writes Android's NWS diagnostic tags (`NWS_PERIOD_SUMMARY`,
    `NWS_BATCH_SUMMARY`, `NWS_TODAY_SOURCE`, …).
  - `ActualsFeedPolicy.fullRefreshFetch`: NWS displaying itself now gets the pull (was null), so desktop's NWS
    refresh stores observations through `fetchObservationsOnly` — logged `BORROWED_NWS_RECOVERY source=NWS`.
  - `WeatherApiClient.fetchForecast(recentObservationsOnly)` was then ignored; removed in a follow-up
    (35 test sites in 11 files plus the 3 main files). `deferObservationWindow` stays: it still narrows
    the observation pull, which `DeferredObservationWindowTest` now asserts via `fetchObservationsOnly(true, …)`.
  - Tests failing on the old policy: `ActualsFeedPolicyTest` (NWS-self case; the every-provider loop pinned
    the old null), `DesktopNwsForecastOnlyRefreshTest` (week on a gap, recent window when stored reaches the
    last hour). Full suite 6080: one failure, `HistorySnapshotPrunerTest` rain-accuracy prior-day pick, which
    fails on the pre-change code too (pre-existing, time-dependent). `:app:compileDebugAndroidTestKotlin` ok.
  - Installed on 3 devices (both phones, one emulator), desktop restarted 17:15. Device check pending: the
    next desktop NWS refresh (~18:13) — `BORROWED_NWS_RECOVERY source=NWS recentOnly=true`, NWS diagnostic
    tags, no `SOURCE_ERROR` / `REFRESH_FAIL`.

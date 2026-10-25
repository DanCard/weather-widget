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


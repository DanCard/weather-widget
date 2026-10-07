# Google `forecast/hours` one page when unchanged; slower charger cadence; targeted refresh

## Problem
`ForecastHoursQueriesPerDay` = 90 per project. Its window starts at midnight Pacific (verified: 429
`window_start_time` 1791356400 = 2026-10-07 00:00 PDT). It was exhausted by 03:16, because:
- one full fetch spends **3** `forecast/hours` calls (72 h at 24 per page);
- a charging device fetches the primary source every 60 min (≈72 calls/day by itself), and five
  clients (Pixel, Samsung, 2 emulators, desktop) share the pool.

## A. One page, continue only on change (`:shared`, both platforms)
`GoogleWeatherApi.fetchForecastHours` fetches page 1 (next 24 h) and then decides:
- **Stop** when page 1 matches what is already stored for those hours AND the stored hours past page 1
  still reach the 72 h horizon AND that tail was fetched less than `MAX_TAIL_AGE` (12 h) ago.
- **Continue** to pages 2–3 otherwise.

Pure rule `GoogleHourPaging.shouldFetchRest(page1, stored, nowMs)` in `:shared`. "Matches" means
that over the overlapping hours:
- max |Δtemp| < 1 °F,
- max |Δprecip probability| < 10 points,
- no condition change in more than 2 hours.

Tolerances are needed because a fresh run nudges values every hour, so exact equality would always
continue.

- The caller supplies the stored hours for the site:
  - Android: `ForecastFetchCoordinator` via `hourlyForecastDao` (same site match as `googleNeedsHistory`);
  - desktop: `DesktopWeatherService` from its DB.
- **No stored rows** (new site, first fetch) → full 3 pages.
- **Stopped early:** `RawFetch.hourly` holds 24 h. Saving is an upsert by hour (`saveHourlyEntities`,
  nothing deleted), so hours 25–72 keep their rows and older `fetchedAt`.
- **To verify:** the hourly loader picks the newest row *per hour*, not "rows from the newest batch".
  If it is the latter, the tail would vanish, and the loader must be fixed first. A test pins this.
- Log `GOOGLE_HOURS_PAGES pages=1|3 reason=unchanged|changed maxDt=… tailAgeMin=…|no_cache|tail_short|tail_old`.

Cost per full fetch: current 1 + days 1 + hours **1** (3 on change) + history (rarely).

## B. Slower charger cadence, same rule for every source (both platforms)
User 2026-10-07: no special case for Google. Fetching non-displayed sources less often is good and
stays. The split is displayed vs. other, never per provider.

On a charger (or battery ≥ 80 %, treated as charging):

| | Screen on | Screen off |
|---|---|---|
| Displayed source | 4 h (was 60 min) | 6 h (was 2 h) |
| Other sources | 8 h (was 6 h) | 12 h (was 8 h) |

Off charger: unchanged (displayed 4 h / 8 h / none by battery tier; others ×2).

- The matrix moves to `:shared` (`ForecastCadence`). Android `ForecastFetchPolicy.intervalMinutes`
  delegates to it, and so does desktop `DesktopFetchStrategy.getForecastRefreshDelayMs`. The desktop
  gains the screen distinction where `ScreenStateDetector` provides it; its AC values were 60/120.
- The Android periodic tick stays hourly on a charger. It only checks what is due (and resamples
  location).
- The rank thresholds `ForecastStalenessPolicy` (60/90/120 by list position) disagree with this
  cadence and drive the refresh-action trigger in C. `DataFreshness` switches to the same
  `ForecastFetchPolicy` due check, using the device's charge, screen and battery state.

Google budget (with A): on a charger with the screen on, as the displayed source, 6 fetches/day ×
1–3 pages = 6–18 `forecast/hours` calls/day per device.

## C. A refresh action fetches only the sources that are due, and never a quota-blocked one (all sources)
Seen 2026-10-07 07:33–08:01 on the emulator: Google stayed stale because every fetch returned 429.
Each widget interaction then logged `REFRESH_DECISION … isDataStale=true` and started
`SYNC_START force=true reason=stale_on_refresh_action_cache_first`. That re-fetched all five
sources four times in 28 minutes, while the others were 5–11 min old.
- The stale-on-refresh path enqueues a non-forced sync, targeted at the sources that are due under
  B, instead of `force=true` for all.
- A source in a known quota block (Google: until `GoogleQuota.nextResetMs`; generalised as a per-source
  "blocked until" reported by the API client) is neither due nor retried before the block ends.
  Its staleness alone must not trigger a sync.
- `REFRESH_DECISION` logs which sources were due and which were skipped as blocked.

## Tests
- `GoogleHourPagingTest`:
  - unchanged → stop;
  - 1.5 °F shift → continue;
  - no cache → continue;
  - tail short of 72 h → continue;
  - tail older than 12 h → continue.
- `GoogleWeatherApi` with a fake HTTP engine: unchanged page 1 → exactly one `forecast/hours`
  request; changed → three.
- Hourly read keeps tail hours from an older `fetchedAt` when page 1 is newer (Android loader + desktop).
- Cadence (shared `ForecastCadenceTest` + updated `ForecastFetchPolicyTest`, `DesktopFetchStrategyTest`):
  on a charger, displayed 240/360 and others 480/720 (screen on/off); off-charger tiers unchanged;
  Google and NWS get identical results for the same role.
- C: a stale Google with others fresh does not force-fetch the others; a quota-blocked Google is
  not retried before reset; the user refresh fetches only the due sources.

## Verify
Emulator + desktop: count Google requests in `api_usage_stats` / `GOOGLE_HOURS_PAGES` over a few
hours; the hourly graph still runs out to 72 h.

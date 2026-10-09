# On-demand hourly: one shared single-source path for every API (Android + desktop)

Status: **implemented 2026-10-09** (approved after revising from a Google-only design
at the user's request: "Would it be better to have same code for all APIs?"). Follows
`plans/261009-google-hourly-on-demand-past-72h.md` and `plans/261009-hourly-pan-into-empty-day-fetches.md`.

## Problem

On the Pixel, a pan onto Fri Oct 16 took **about 42 s** from the last tap to Friday's hours on screen;
desktop takes about 2 s. Android runs the generic no-hourly **forced full sync**
(`enqueueRequiredNoHourlyFollowUp` → `FullSyncPipeline`) just to fill one day. A non-Google tap on a
day with no hourly takes the same slow path.

## Evidence (Pixel `app_logs`, 2026-10-09 05:00–05:01)

| Stage | Time | Source |
|---|---|---|
| Last › to follow-up queued | 1 s | pan settle (`PAN_SETTLE_MS`), by design |
| Queued to `SYNC_START` | **9.6 s** | `SYNC_DEFERRED_STARTUP … processAgeMs=22454 delayMs=9551`: `StartupCooldown` defers syncs in a young process |
| `NET_FETCH_START` to first Google reply | **17.7 s** | nothing logged in between (32.2 → 49.97) |
| 8 `forecast/hours` pages | 3.5 s | 0.3–0.8 s per page |
| Save weather / hourly / backfill | 3.2 s | `SYNC_STAGE` |
| Actuals recompute + repairs + widgets, before the complete broadcast | 7.2 s | `SYNC_PERF … actuals=5197ms widgets=2427ms` |
| Banner clear + repaint | about 1 s | `CLICK_DAILY_NO_HOURLY phase=result` |

## Design: one path, source differences as data

### 1. Per-source hourly horizons (`:shared`, replaces `source == GOOGLE` checks)

`HourlyHorizons` describes each source:
- `routineHours`: what a normal fetch stores;
- `maxHours`: the furthest its API serves (capped at the app's 240 h reach);
- `costsPerExtraDay`: whether extra hours cost requests.

| Source | routineHours | maxHours | costsPerExtraDay |
|---|---|---|---|
| Google | 72 | 240 | yes (1 billed page / 24 h) |
| Open-Meteo, Silurian | full horizon | 240 (API serves 16 d) | no |
| NWS, Tomorrow.io, WeatherAPI, OWM | full horizon | their API horizon (to verify: NWS ≈ 156 h, Tomorrow.io 120 h, OWM 120 h 3-hourly, WeatherAPI per plan) | no |

`HourlyOnDemand.hoursToCover` / `panAction` read this table instead of `extendsHourly(source)`. A
day can be fetched on demand when it lies within the source's `maxHours` and fresh stored rows do
not cover it. That covers:
- Google past 72 h;
- any source whose stored hourly is stale or missing for that day;
- later, the viewing-frequency plan's shorter windows, with no new code path.

Past `maxHours` → the "No hourly forecast … data ends …" message, as today.

### 2. One single-source on-demand fetch

**Android** — `HourlyOnDemandWorker` (replaces the forced full sync for taps and pans):
- runs the tapped source's existing `ForecastFetchCoordinator` registry entry — the same per-source
  fetch-and-save the full sync uses — with `ForecastFetchContext.hourlyAhead` set (Google already
  honours it; every other source's single call returns its whole horizon anyway);
- **skips** the rest of the full sync: other sources, actuals recompute, repairs, the location
  resample and the all-widgets paint;
- then the existing `handleRefreshComplete` logic (clear the banner or say where data ends) and a
  repaint of that widget from cache;
- expedited on API 31+ (like the location-change sync) and not subject to `StartupCooldown` — the
  user is looking at the banner;
- unique per widget, REPLACE; a pan keeps its 1 s settle, a tap has none.

**Desktop** — `DesktopWeatherRepository.extendHourlyFor(date)` becomes source-neutral: refresh just the
displayed source through its normal `WeatherApiClient` fetch with the deeper horizon, save its rows,
reload. Google-only `fetchHourlyAhead` goes away.

### 3. Google's two extra requests: accepted

Google's registry entry fetches current conditions + daily alongside the hours: 2 billed requests
per tap beyond the hour pages. They are on separate quotas from `forecast/hours`, and they also
refresh the header. Accepted for simplicity; Settings → Usage stats shows whether it matters.
(Fallback, if it does: an "hourly only" flag in `ForecastFetchContext` that Google's entry honours and
the others ignore — still no separate path.)

### 4. Instrumentation

`GOOGLE_REQUEST` (and the generic fetch log) gain `ms=` (request duration); the worker logs
`HOURLY_ON_DEMAND source= date= hours= rows= startDelayMs= fetchMs= saveMs= paintMs=`. That explains
the 17.7 s gap: network warm-up shows in `fetchMs`; work inside the full sync simply disappears.

**Expected:** 1 s settle (pan only) + about 0.5 s start + the source's fetch (Google: 0.3–0.8 s per
page + 2 small calls) + save under 1 s + paint about 0.5 s ⇒ **about 3–6 s** a week out, on every
source.

## Unchanged

- Which day, 12 h freshness, 240 h reach, the banner and message flow, the pan settle.
- The scheduled full sync and its cadence.

## Tests

- `:shared`:
  - `HourlyHorizons` table;
  - `hoursToCover` / `panAction` per source: Google past 72 h fetches; NWS inside its horizon with
    stale rows fetches; NWS past its horizon gives the message.
- Android Robolectric: a tap or pan on any source with an uncovered day enqueues `HourlyOnDemandWorker`
  (expedited, REPLACE, pan delay), never the forced full sync.
- Android worker integration (Robolectric + Room + Ktor `MockEngine`; the `GoogleWeatherFixtures`
  replay and an Open-Meteo fixture):
  - only the tapped source is fetched;
  - Google asks `hours=` deep enough;
  - rows are stored live and in history;
  - the banner clears;
  - a quota-blocked Google hours product shows the end-of-data message without requests.
- Desktop: `extendHourlyFor` for Google and for a non-Google source (fake client, real SQLite).
- On the Pixel: tap and pan onto an uncovered day on Google and on Open-Meteo; measure tap-to-graph
  from `app_logs`; target under 6 s.

## Implementation notes (2026-10-09)

- `:shared` `HourlyHorizons` (table); `HourlyOnDemand.hoursToCover` reads it.
  - New guard: a source fetched within 12 h whose data simply ends sooner is not fetched again.
    Without it, a day at NWS's 156 h edge would refetch on every pan.
  - `extendsHourly` / `requestFor` removed.
- Android:
  - `HourlyOnDemandWorker` (expedited on API 31+, REPLACE per widget, pan settle inside the work)
    → `WeatherRepository.fetchSourceOnDemand` → `ForecastFetchCoordinator.fetchSingleSource`;
  - then `WidgetDayClickCoordinator.completeOnDemand` and a cache paint of that widget;
  - removed the forced-sync follow-up and its plumbing: `enqueueRequiredNoHourlyFollowUp`,
    `ACTION_NO_HOURLY_REFRESH_COMPLETE`, `KEY_NO_HOURLY_*`, the `WorkInput` fields, the
    `FullSyncPipeline` broadcast;
  - Google on demand skips `history/hours` (20/day quota; no elapsed hours needed).
- Desktop:
  - `WeatherApiClient.fetchForecastAhead` (Google: `getForecast(hoursAhead)`; others: their normal
    call);
  - `extendHourlyFor` saves through `persistForecastResult`;
  - `GoogleWeatherApi.getForecastHours` removed.
- `GOOGLE_REQUEST … ms=` (request duration). `HOURLY_ON_DEMAND … startDelayMs= fetchMs= paintMs=`.
- Live, desktop: › onto Sun Oct 18 → `forecast/hours` 429. Testing that day used ~70 hour pages
  against the per-project daily quota. The flow handled it: no pages, "data ends Sat 11 PM" shown.
  `ms=` read about 490 ms per request.

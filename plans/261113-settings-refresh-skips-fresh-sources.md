# Settings → Refresh data skips sources fetched in the last 10 minutes

## Request

User, 2026-11-13: "When I hit refresh data in settings, data sources that have been refreshed within
last 10 minutes should be ignored." NWS went from 1 min stale to 0 min on History of Forecasts.

## Evidence (desktop `app_logs`, 2026-11-13)

1. 16:57:52 popup opened → `FETCH_REQUEST start kind=FORECAST source=NWS origin=popup_open
   reason=viewing_forecast_stale`; 16:57:53 `FORECAST_LONG_FETCH source=NWS`.
2. 16:59:04 `LAUNCH_REFRESH_CHECK … forecastAgeMs=71355` (NWS 71 s old).
3. 16:59:07 `REFRESH_CLICK origin=settings` → `FETCH_REQUEST start kind=FORECAST source=NWS
   origin=settings`; 16:59:08 `FORECAST_LONG_FETCH source=NWS` again, 75 s after the last one.
4. 16:59:10 `SETTINGS_REFRESH start displayed=NWS others=OPEN_METEO,…,TOMORROW_IO`; all six fetched
   (16:59:11–16:59:21). No skip line of any kind.

## Root cause

Not a bug in an existing rule: no freshness skip exists. `SettingsRefreshAllSources` (`:shared`) only
orders the sources; desktop forces the displayed source through a `FORECAST` request and then every
other one through `refreshOthers`; Android's planner forces `BOTH` for every candidate when
`forceRefresh && targetSourceId == null` (`ForecastFetchCoordinator.planSync`).

## Proposed fix

1. `:shared` `SettingsRefreshAllSources` gains the whole rule:
   - `const val FRESH_SKIP_MS = 10 * 60_000L`.
   - `fun partition(order: List<String>, lastFetchMs: (String) -> Long?, nowMs: Long): Plan` →
     `Plan(fetch, skipped: Map<id, ageMs>)`; a source with no fetch at this site is never skipped.
   - One log line per refresh: `SETTINGS_REFRESH skip_fresh sources=NWS(1m),… fetch=…`.
   - "Last fetch" = the same marker the viewing rule uses: last LONG fetch or last successful check at
     this site (desktop `getLastSuccessfulFetch`, Android `getLastLongFetch` ∪ last success).
2. Desktop (`DesktopUiApplication` settings click + `DaemonRuntime.executeFetchRequest`): partition
   once at the click, before the displayed-source `FORECAST` request. A fresh displayed source sends no
   `FORECAST` request; `OTHER_SOURCES` refreshes only the partition's non-displayed `fetch` list.
3. Android (`ForecastFetchCoordinator.planSync`): for an untargeted all-sources force
   (`fetchContext.allSources`), `forced` returns `BOTH` only for sources outside the skip set; skipped
   sources fall through to the normal due check (so they still fetch if their cadence says so).
4. Notice: the existing 3 s refresh notice. If every source was skipped it reads "Already up to date"
   (Claude proposal) instead of starting a refresh; otherwise unchanged.
5. Observations: unchanged. The observation window still refreshes on a settings refresh even when the
   displayed source's forecast is skipped (Claude proposal: the 10-minute rule is about forecasts; obs
   have their own cadence).
6. Docs: `arch/hourly-forecast-update-cadence.md` (settings refresh section); update KDoc on
   `SettingsRefreshAllSources`.

## Tests

1. `:shared` JVM: `partition` — 9 min 59 s skipped, 10 min fetched, never-fetched fetched, displayed
   source skipped when fresh, order preserved, log line names ages. Fails on old code (no skip).
2. Android Robolectric/JVM: `planSync` with `allSources=true`, one source fetched 2 min ago → not in
   `due`; one fetched 11 min ago → `BOTH`.
3. Desktop JVM: settings refresh with a fresh displayed source sends no `FORECAST` request and
   `OTHER_SOURCES` omits fresh sources (fake fetch client).
4. Runtime: desktop — open the popup (fetches NWS), then Settings → Refresh data within a minute;
   expect `SETTINGS_REFRESH skip_fresh sources=NWS(…)` and no second `FORECAST_LONG_FETCH source=NWS`.
   Android emulator — tap the widget, then Settings → Refresh data; same check in logcat/`app_logs`.

## Open question for the user

Should the skip also apply when *every* source is fresh, i.e. a second press within 10 minutes does
nothing but say "Already up to date"? The plan assumes yes.

## Approval and refinement (2026-11-13)

User approved; "Already up to date" when every source is fresh. User: "On individual refreshes the 10
minute rule definitely exists. It will say something like: Not updating ..." That rule is
`UserRefreshThrottle` (`RATE_LIMITED`, "Not doing because last refresh was X ago…"), keyed on the last
*user* refresh of a source, not its last fetch. Today Settings → Refresh data applies it to the
displayed source only, so a hand refresh of NWS blocks every other source, and a non-user fetch (popup
open) does not count.

Refinement:
1. A source is fresh when its last fetch **or** its last user refresh is under 10 minutes
   (`UserRefreshThrottle.THROTTLE_MS`, one constant). This replaces the displayed-source throttle on the
   settings button on both platforms.
2. The partition is made once, at the click. Every source it fetches gets its user-refresh mark, so a
   later single-source refresh still says "Not doing because…".
3. Android passes the skip set to the worker (`KEY_SKIP_SOURCES` → `ForecastFetchContext.skipSourceIds`);
   `planSync` does not force a skipped source (its normal cadence still decides). Desktop: a fresh
   displayed source sends no `FORECAST` request; `OTHER_SOURCES` carries the explicit source list.
4. New notice reason `ALL_FRESH` → "Already up to date" (desktop text; Android string, all locales).

## Second refinement (2026-11-13, during implementation)

1. User: "No it shouldn't block. Only the one data source should be blocked from refreshing. The rest
   should update if they are more than 10 minutes stale." — the partition does exactly this.
2. User: "Should limit data refreshes per source to once every 10 minutes max." `UserRefreshThrottle.check`
   now takes the source's last fetch too (`lastFetchMs`), so every single-source forecast refresh (widget
   tap, History, desktop buttons) is refused within 10 minutes of any fetch, not only a user refresh. The
   Observations-only refresh passes `lastFetchMs = null` (its charging loop fetches every 10 minutes, so
   counting it would block the button almost always).
3. Plan item 5 replaced: a skipped source is skipped whole, observations included (Android enqueues the
   current-temp work only when the displayed source is fetched).

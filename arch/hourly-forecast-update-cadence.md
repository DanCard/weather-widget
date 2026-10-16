# Forecast Update Cadence

How often each source's forecast — daily and hourly together — is refetched from providers, on
both Android (`:app`) and Linux desktop (`:desktop`).

Source of truth: `shared/src/main/kotlin/com/weatherwidget/data/remote/ForecastHorizonCadence.kt`
(user, 2026-10-16; `performance/261016-unified-long-short-forecast-horizons.md`). One rule for every
source and provider: no displayed-vs-other split, no view-age bands. It replaced `ForecastCadence`
(daily), `HourlyCadence` (hourly by view age) and `HourlyWindowPolicy` (48 h / 8-day windows).

## Two horizons

| Battery | Screen | LONG (≈ 8 days) | SHORT (3 days) |
|---|---|---|---|
| ≥ 70 % or charging | on | every 8 h | every 4 h |
| ≥ 70 % or charging | off | every 24 h | every 8 h |
| < 70 % | on or off | every 24 h | none |

- **LONG**: the source's whole reach (Google asks 192 h); every daily row is stored, plus 72 h of
  hourly. The far-day summaries (noon cloud, day/night rain maxima) are computed from the whole
  download.
- **SHORT**: asks 72 h; stores today + 2 days of daily rows and 72 h of hourly. Days 4+ keep the last
  LONG fetch's rows, which is why both platforms' daily reads take each day's newest row.
- A LONG fetch also counts as SHORT. "Due" is measured per source + site from two markers (Android
  prefs, desktop `app_logs` rows), with a 2-minute grace.
- A forced fetch (screen-on catch-up, source switch, location change) takes what is due, at least
  SHORT; the user's Refresh is LONG (`ForecastHorizonCadence.forced`).

Sources not viewed in 8 days are not fetched in the background at all (`SourceFetchGate`).

### Periodic worker tick (not a fetch interval)

`ForecastFetchPolicy.periodicTickMinutes` only decides when Android's worker *wakes* and asks which
sources are due (and resamples location):

- Charging / ≥ 80 %: **60 min**
- 70–79 %: **240 min** (the SHORT screen-on interval)
- 50–69 %: **480 min**; below 50 %: **24 h**

Desktop sleeps until the next due horizon instead (`ForecastSchedule`, `DesktopForecastPlanner`).

## Sooner-than-scheduled refreshes

Hourly data can be refetched before its interval elapses:

1. **Hourly gaps** — graph/precip/cloud views detect missing hours for the displayed source
   (`hourly_gaps` / `hourly_gaps_elapsed`, cooldown via `WidgetStateManager.shouldRefreshMissingData`).
2. **User interaction** — manual refresh, source switch, day-tap with no hourly data.
3. **Stale-data gate** — interaction when data is older than
   `BatteryFetchStrategy.STALE_DATA_THRESHOLD_MS` (4 h).

## Distinction: current-temperature display updates

The current temperature on the widget is interpolated from stored hourly rows
(`CurrentTemperatureResolver`). That *display* path updates more often and does **not**
refetch the hourly forecast series (`CurrentTempFetchPolicy`):

| State | Interval |
|---|---|
| Charging, screen on | 10 min |
| Charging, screen off | 16 min |
| On battery, screen on, battery ≥ 70% | 20 min |
| Otherwise | 45 min opportunistic only (no dedicated loop) |

"Now" stays fresh through observations and current temperature; the forecast itself is on the
slower `ForecastHorizonCadence` schedule.

## Why these intervals

The user's table, 2026-10-16: refresh the near term (3 days) often while the battery is high and
the screen is on, the full 8 days every 8 h with the screen on (12 h at first, changed the same day), and nothing but a daily LONG fetch below 70 %.
Earlier history: charger values were once 60/120 min for the displayed source, which spent a
provider's per-project daily `forecast/hours` quota by ~03:16
(`plans/261007-google-hours-one-page-and-slower-charger-cadence.md`).

## Related code

| Piece | Location |
|---|---|
| Forecast cadence (LONG / SHORT) | `shared/.../data/remote/ForecastHorizonCadence.kt` |
| Battery thresholds / base intervals | `shared/.../util/BatteryTier.kt` |
| Android scheduling wrapper | `app/.../widget/ForecastFetchPolicy.kt` |
| Android battery helper | `app/.../widget/BatteryFetchStrategy.kt` |
| Current-temp loop cadence | `app/.../widget/CurrentTempFetchPolicy.kt` |
| Periodic tick tests | `app/src/test/.../ForecastFetchPolicyTest.kt` |
| Shared cadence tests | `shared/src/test/.../ForecastHorizonCadenceTest.kt` |
| Android plan / storage tests | `ForecastHorizonPlanRoboTest`, `ForecastHorizonStorageIntegrationTest` |
| Desktop plan / storage tests | `DesktopForecastPlannerTest`, `DesktopForecastHorizonStorageTest` |

# Hourly Forecast Update Cadence

How often hourly temperature forecast data is refetched from providers, on both Android
(`:app`) and Linux desktop (`:desktop`).

Source of truth: `shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastCadence.kt`,
with battery thresholds in `BatteryTier.kt`. One rule for every provider — the split is
displayed source vs. other source, never per provider.

## Scheduled refetch intervals

### On charger (or battery ≥ 80%, treated as charging)

| Condition | Interval |
|---|---|
| Displayed source, screen on | **4 h** (240 min) |
| Displayed source, screen off | **6 h** (360 min) |
| Other source, screen on | **8 h** (480 min) |
| Other source, screen off | **12 h** (720 min) |

### Off charger

| Battery | Displayed source | Other sources (× `OFF_CHARGER_OTHER_MULTIPLIER` = 2) |
|---|---|---|
| > 70% | 4 h | 8 h |
| > 50% | 8 h | 16 h |
| ≤ 50% | no scheduled fetch | no scheduled fetch |

Interval selection is elapsed time since the source's own last successful fetch (no clock
slots). Android wraps this in `ForecastFetchPolicy.intervalMinutes`; desktop calls
`ForecastCadence.intervalMinutes` directly.

### Periodic worker tick (not a fetch interval)

`ForecastFetchPolicy.periodicTickMinutes` only decides when the worker *wakes* and evaluates
which sources are due (and resamples location):

- Charging / treated-as-charging: **60 min**
- Off charger with a valid battery-tier interval: that interval
- Off charger below 50%: **24 h** fallback tick (still no forecast fetch if none is due)

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

"Now" stays fresh through observations and current temperature; the hourly series itself is
on the slower `ForecastCadence` schedule.

## Why these intervals

Charger values were previously 60/120 min for the displayed source. That spent a provider's
per-project daily `forecast/hours` quota by ~03:16. User decision 2026-10-07: slow the
charger cadence. See `plans/261007-google-hours-one-page-and-slower-charger-cadence.md`
and `notes/261007-hourly-forecast-update-cadence.md`.

## Related code

| Piece | Location |
|---|---|
| Forecast refetch intervals | `shared/.../util/ForecastCadence.kt` |
| Battery thresholds / base intervals | `shared/.../util/BatteryTier.kt` |
| Android scheduling wrapper | `app/.../widget/ForecastFetchPolicy.kt` |
| Android battery helper | `app/.../widget/BatteryFetchStrategy.kt` |
| Current-temp loop cadence | `app/.../widget/CurrentTempFetchPolicy.kt` |
| Interval table tests | `app/src/test/.../ForecastFetchPolicyTest.kt` |
| Shared cadence tests | `shared/src/test/.../ForecastCadenceTest.kt` |

# Hourly Forecast Update Cadence

*Date: October 7, 2026. Supersedes the charger values in [260525-forecast-fetch-frequency.md](260525-forecast-fetch-frequency.md).*

Hourly forecast data is refetched on the battery-aware **`ForecastCadence`** schedule
(`shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastCadence.kt`). Same rule on
Android (`:app`) and desktop (`:desktop`). The split is displayed source vs. other source —
never per provider.

## On charger (or battery ≥ 80%, treated as charging)

| Condition | Interval |
|---|---|
| Displayed source, screen on | **4 h** (240 min) |
| Displayed source, screen off | **6 h** (360 min) |
| Other source, screen on | **8 h** (480 min) |
| Other source, screen off | **12 h** (720 min) |

## Off charger

| Battery | Displayed source | Other sources (×2) |
|---|---|---|
| > 70% | 4 h | 8 h |
| > 50% | 8 h | 16 h |
| ≤ 50% | no scheduled fetch | no scheduled fetch |

Constants live in `ForecastCadence` (charger tiers, `OFF_CHARGER_OTHER_MULTIPLIER = 2`) and
`BatteryTier` (battery thresholds and off-charger base intervals). Android wraps them in
`ForecastFetchPolicy.intervalMinutes`; desktop uses the same `ForecastCadence` object.

## Why these values

Charger values were previously 60/120 min for the displayed source. That cadence spent a
provider's per-project daily `forecast/hours` quota by ~03:16. User decision 2026-10-07:
slow the charger cadence; "now" stays fresh through observations and current temperature,
which have their own cadence. See
`plans/261007-google-hours-one-page-and-slower-charger-cadence.md`.

## Sooner-than-scheduled refreshes

Hourly data can still be refetched before the interval elapses:

1. **Hourly gaps** — a graph/precip/cloud view detects missing hours for the displayed source
   (`hourly_gaps` / `hourly_gaps_elapsed` via `WidgetStateManager.shouldRefreshMissingData`).
2. **User interaction** — manual refresh, source switch, day-tap with no hourly data.
3. **Stale-data gate** — interaction when data is older than `BatteryFetchStrategy.STALE_DATA_THRESHOLD_MS` (4 h).

## Not the same as current-temperature display updates

The current temperature shown on the widget is **interpolated from the stored hourly rows**
(`CurrentTemperatureResolver`). That *display* updates more often via a separate lightweight
path (`CurrentTempFetchPolicy` / `CurrentTempUpdateScheduler`):

| State | Interval |
|---|---|
| Charging, screen on | 10 min |
| Charging, screen off | 16 min |
| On battery, screen on, battery ≥ 70% | 20 min |
| Otherwise | 45 min opportunistic (no dedicated loop) |

Those runs refresh observations / current temp only — they do **not** refetch the hourly
forecast series.

## Related code

- `shared/.../util/ForecastCadence.kt` — per-source forecast refetch intervals
- `shared/.../util/BatteryTier.kt` — battery thresholds and base intervals
- `app/.../widget/ForecastFetchPolicy.kt` — Android scheduling wrapper
- `app/.../widget/CurrentTempFetchPolicy.kt` — current-temp loop cadence
- `app/src/test/java/com/weatherwidget/widget/ForecastFetchPolicyTest.kt` — interval table tests

# Current-temperature fetch schedule

The charging current-temperature loop fetches **all enabled/visible sources**, not only the source
currently displayed. The battery-mode opportunistic loop is deliberately narrower.

| Trigger / state | Nominal frequency | Current-temperature sources fetched |
|---|---:|---|
| Charging, screen on | ~10 minutes | All visible sources. Release defaults: **NWS, Open-Meteo, Silurian**. Debug also includes **Tomorrow.io**. |
| Charging, screen off | ~16 minutes | Same visible-source set. |
| Opportunistic job, battery above 65% | ~45 minutes; Android may delay it | **Primary source only while unplugged**; all visible sources while charging. |
| Opportunistic job, battery at or below 65% | Not scheduled, even while charging | None. An already-persisted job rechecks the level before fetching. The separate charging loop still runs. |
| Charging, screen on, secondary non-primary loop | ~30 minutes | Sources not currently displayed by any widget. This overlaps with the main 10-minute loop. |
| Unlock while charging | Immediate attempt | All visible sources. |
| Unlock while on battery | No network fetch | None; the widget repaints from cache. |
| Connect charger | Immediate attempt, debounced | All visible sources. |
| Settings "Refresh data" | Immediate, forced | All visible sources. |
| Widget refresh when data is stale | Immediate, forced | All visible sources. |
| Observations-screen refresh | Immediate, forced | Only the source selected on that screen. |
| Interpolation/UI update | Variable cache repaint | None; calculated from stored hourly forecasts and observations. |

## Source-specific exceptions

| Source position/state | Rule |
|---|---|
| Battery-mode opportunistic fetch | Only the configured **primary source**, and only above 65%. |
| First three visible sources while charging | Fetched on every permitted main-loop cycle, whether displayed or not. With defaults, these are **NWS, Open-Meteo, and Silurian**. |
| Fourth or later source, currently displayed while charging | Fetched on every permitted main-loop cycle. |
| Fourth or later source, not displayed | Throttled to at most once per **60 minutes**. In debug defaults, this normally applies to **Tomorrow.io**. |
| Any non-forced fetch within five minutes of the previous current-temperature fetch | Entire attempt is skipped by the global five-minute freshness gate. |
| Forced/manual refresh | Bypasses the five-minute and low-priority throttles. |

Therefore, on a normal release installation while charging with the screen on, the effective
intention is approximately:

| Source | Frequency |
|---|---:|
| NWS | ~10 minutes |
| Open-Meteo | ~10 minutes |
| Silurian | ~10 minutes |
| Tomorrow.io | Not enabled by default in release |
| Hidden/deprecated sources | Not fetched by this current-temperature loop unless explicitly enabled |

The confusing part is genuine while charging: the code has a separate "non-primary" 30-minute loop,
but the main 10/16-minute loop already fetches the non-displayed first-three sources. That secondary
loop is canceled on battery.

## Battery-first effect

When unplugged and above 65%, the nominal maximum falls from 48 all-visible-source cycles per day
(every 30 minutes) to 32 primary-only cycles per day (every 45 minutes). At 65% or below, this
opportunistic network loop is not scheduled, even while charging. Manual refreshes and the separate
10/16-minute charging loop remain unrestricted by battery percentage.

Relevant implementation:

- [`CurrentTempRepository.kt`](../app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt)
- [`CurrentTempFetchPolicy.kt`](../app/src/main/java/com/weatherwidget/widget/CurrentTempFetchPolicy.kt)
- [`WidgetStateManager.kt`](../app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt)
- [`NonPrimaryObservationScheduler.kt`](../app/src/main/java/com/weatherwidget/widget/NonPrimaryObservationScheduler.kt)
- [`OpportunisticUpdateJobService.kt`](../app/src/main/java/com/weatherwidget/widget/OpportunisticUpdateJobService.kt)

# Bug: Rain Icon Not Showing for Today

**Date discovered:** 2026-02-10
**Status:** Investigating

## Symptom

Widget shows "Partly Sunny" icon for today even though rain is forecast from 1pm onward. NWS hourly data confirms: "Chance Light Rain" (1pm), "Light Rain Likely" (2pm), "Rain" (4pm), "Chance Showers And Thunderstorms" (7pm).

## Root Cause

Two issues combine to suppress the rain icon:

### 1. Observations overwrite forecast condition for today

In `WeatherRepository.kt`, `fetchDayObservations()` runs for `daysAgo=0..7` (includes today). It fetches this morning's observation station data and computes a condition based purely on **cloud cover scores** (lines ~800-819). This produces `"Partly Cloudy (50%)"` and stores it as today's condition.

When the forecast loop runs next (line ~560), it checks `if (conditionByDate[date] == null)` — but today already has a condition from observations. So the NWS forecast `"Partly Sunny then Rain"` is silently discarded.

Evidence from app_logs:
```
NWS_TODAY_SOURCE: condition=Partly Cloudy (50%) (OBS:AW020)
                  firstTodayPeriod=Today@...=Partly Sunny then Rain
```

### 2. Cloud score algorithm is completely blind to precipitation

The observation condition algorithm (`fetchDayObservations()` lines ~800-819) only maps cloud-related keywords:

```kotlin
when {
    desc.contains("mostly cloudy") -> 75
    desc.contains("mostly clear") || desc.contains("mostly sunny") -> 25
    desc.contains("partly") -> 50
    desc.contains("cloudy") || desc.contains("overcast") -> 100
    desc.contains("clear") || desc.contains("sunny") || desc.contains("fair") -> 0
    else -> 50 // "Rain", "Light Rain", "Thunderstorm" all fall here!
}
```

Precipitation descriptions like "Rain", "Light Rain", "Snow", "Thunderstorm" all hit `else -> 50` and are treated as "Partly Cloudy". **Even at 8pm after a full day of rain, the algorithm would average out to something like "Partly Cloudy."**

### Data flow summary

```
NWS Observation Station (morning)
  -> textDescription: "Partly Cloudy", "Mostly Sunny", etc.
  -> cloud score average: 50
  -> "Partly Cloudy (50%)"
  -> stored as today's condition        <-- wins because it runs first

NWS Daily Forecast API
  -> shortForecast: "Partly Sunny then Rain"
  -> discarded (conditionByDate[today] already set)
```

## Proposed Fix (Two Parts)

### Part 1: For today, prefer the NWS daily forecast `shortForecast`

The NWS `shortForecast` (e.g., "Partly Sunny then Rain") is a professional whole-day summary. It's more informative than a computed cloud score at any time of day:
- At 8am: tells you what's coming
- At 8pm: still accurately describes what happened (NWS doesn't retroactively change it)

Options:
- **Don't set observation condition for today** — only use observations for `daysAgo=1..7`. Let the forecast condition win for today.
- **Or let forecast always override observation condition for today** — reverse the priority.

### Part 2: Fix observation cloud score for past days (precipitation awareness)

For past days (yesterday, etc.), add precipitation detection to the cloud score algorithm:

```kotlin
// Before cloud scoring, check for precipitation
val hasPrecipitation = daylightObservations.any { obs ->
    val desc = obs.textDescription.lowercase()
    desc.contains("rain") || desc.contains("drizzle") ||
    desc.contains("shower") || desc.contains("storm") ||
    desc.contains("thunder") || desc.contains("snow") ||
    desc.contains("sleet") || desc.contains("freezing")
}
```

If precipitation is detected, override the cloud-only condition with an appropriate precipitation condition string (e.g., "Rain", "Thunderstorm", "Snow").

## Edge Case: Late in the Day

At 8pm, most of the day is over. The concern is whether the forecast or observation should take priority.

**Argument for always using forecast for today:** The NWS `shortForecast` already summarizes the whole day well. It doesn't become inaccurate as the day progresses. The observation cloud score is *always* less informative because it loses precipitation data.

**Potential late-day issue:** If the forecast said "Rain" but it didn't actually rain, the widget would still show rain at 8pm. However, this is arguably better than the reverse (showing sunny when it did rain), and the NWS forecast is rarely that wrong for same-day predictions.

**If a time-based cutoff is desired:** Could switch from forecast to observation condition after 6-8pm, but ONLY if the observation algorithm is fixed to detect precipitation (Part 2 above). Without Part 2, switching to observations at any time would still lose rain info.

## Key Files

| File | Relevance |
|------|-----------|
| `WeatherRepository.kt:500-540` | Observation fetch loop (includes today) |
| `WeatherRepository.kt:556-572` | Forecast condition assignment (skipped if obs exists) |
| `WeatherRepository.kt:790-823` | Cloud score algorithm (precipitation-blind) |
| `WeatherIconMapper.kt:7-30` | Condition string -> icon mapping |
| `WeatherWidgetProvider.kt:1016` | Daily graph uses `weather.condition` for icon |

## DB Evidence (2026-02-10)

**Daily condition stored:**
| Source | Condition | isActual |
|--------|-----------|----------|
| NWS | Partly Cloudy (50%) | 0 |
| Open-Meteo | Drizzle | 0 |

**NWS hourly forecast for today:**
| Hour | Condition |
|------|-----------|
| 6am-10am | Sunny / Mostly Sunny |
| 11am-12pm | Partly Sunny |
| 1pm | Chance Light Rain |
| 2-3pm | Light Rain Likely |
| 4pm | Rain |
| 5pm | Light Rain Likely |
| 6pm | Chance Light Rain |
| 7pm | Chance Showers And Thunderstorms |

**NWS daily forecast API (fetched live):**
```
Today: 61°F | Partly Sunny then Rain
Tonight: 48°F | Showers And Thunderstorms
```

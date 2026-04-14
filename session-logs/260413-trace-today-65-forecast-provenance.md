# Tracing "65°" displayed for Today in Daily View — full provenance

**Date:** 2026-04-13
**Mode:** plan mode (read-only investigation, no code changes)
**Emulator:** `emulator-5554`
**Widget location:** Google HQ default (lat=37.422, lon=-122.0841)
**Displayed source at time of investigation:** NWS

---

## Question

> "Daily view forecast : today: where does the 65 degrees for today come from?"

Followed by: "From which NWS api call did the 65 come from? Can you query the api directly and show me all the details of what it says?"

---

## TL;DR

The **65°** label above Today's bar is:

`max(observedHigh=53.72°, forecastHigh=65.0°, trueActualHigh=64.70°) = 65.0°`

The winning value (**65.0°**) is `fallbackWeather.highTemp` from the most recent `ForecastEntity` row for today (source=NWS), which was written at **2026-04-13 15:34:54** local time by `NwsApi.getForecast`. That function hits
`GET https://api.weather.gov/gridpoints/MTR/93,87/forecast`
and parses the `periods[]` array. The 65 came from the period **named "Wednesday" (April 13), isDaytime=true, temperature=65, shortForecast="Sunny"**.

By the time of this investigation (~21:45 PDT) that period no longer exists in the live API response — NWS has rolled over to "Tonight" as period #1. The widget is reading the last cached value.

---

## Render path — where the label is chosen

### 1. `DailyForecastGraphRenderer.kt:398-413`

```kotlin
// High Temp Label
if (day.high != null) {
    val displayHigh = if (day.isToday) {
        listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: day.high
    } else {
        day.high
    }
    val highLabel = formatTempLabel(displayHigh, day.isToday || day.isPast)
    ...
    val labelY = if (day.isToday) {
        val absoluteHigh = listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: 0f
        layout.graphTop + layout.graphHeight * (1 - (absoluteHigh - layout.minTemp) / layout.tempRange)
    } else y
    val tempPaint = if (day.isToday) paints.todayTempTextPaint else paints.tempTextPaint
    canvas.drawText(highLabel, centerX, labelY - dpToPx(context, 6f * layout.scaleFactor), tempPaint)
}
```

So for the today column the numeric label is `max(day.high, day.forecastHigh, day.trueActualHigh)`.

### 2. `DailyActualsEstimator.calculateTodayTripleLineValues` (`DailyActualsEstimator.kt:44-95`)

Populates the three fields that feed into `DayData`:

```kotlin
// 1. Observed so far (history/current)
val actual = dailyActuals[today]
val observedHigh = currentTemp ?: actual?.highTemp         // → day.high
val observedLow  = listOfNotNull(actual?.lowTemp, currentTemp).minOrNull()

// 2. Full-day prediction (including both past and future hours)
val hourlyMax = todayHourly.maxOfOrNull { it.temperature }
val hourlyMin = todayHourly.minOfOrNull { it.temperature }

val forecastHigh = fallbackWeather?.highTemp ?: hourlyMax  // → day.forecastHigh
val forecastLow  = listOfNotNull(fallbackWeather?.lowTemp, hourlyMin).minOrNull()

// trueActualHigh = actual?.highTemp                       // → day.trueActualHigh
```

### 3. Live values from logcat

`adb -s emulator-5554 logcat -d DailyEstimator:D '*:S'`, most recent line at **21:45:57**:

```
today: actual.high=64.69983 actual.low=51.037434
       currentTemp=53.722717
       observedHigh=53.722717 observedLow=51.037434
       fallbackWeather.high=65.0 fallbackWeather.low=null
       hourlyMax=64.0 hourlyMin=47.0
       forecastHigh=65.0 forecastLow=47.0
       todayHourlyCount=24 source=NWS
```

Plugging into the renderer:

- `day.high` (observedHigh) = `currentTemp` = **53.72°**
- `day.forecastHigh` = `fallbackWeather.highTemp` = **65.0°**  ← winner
- `day.trueActualHigh` = `actual.highTemp` = **64.70°**
- `max(53.72, 65.0, 64.70)` = **65.0°** → displayed label

Note the endpoint disagreement the log exposes in passing:

- Daily endpoint `/forecast` → **65°F**
- Hourly endpoint `/forecast/hourly` → `hourlyMax = 64°F`

Both come from NWS. They're allowed to disagree by 1°F because the daily period carries a single integer summary while the hourly stream is resampled on a different grid.

---

## The NWS endpoint — `NwsApi.kt`

Two calls are involved in producing `ForecastEntity.highTemp`:

### Call 1 — grid lookup
```
GET https://api.weather.gov/points/37.422,-122.0841
```
`NwsApi.getGridPoint` (`NwsApi.kt:77-102`) parses:
- `properties.gridId`
- `properties.gridX`, `properties.gridY`
- `properties.forecast` (URL for call 2)
- `properties.observationStations` (used elsewhere for the actuals pipeline)

For the widget's default coordinates this resolves to:
```
gridId = MTR   grid = (93, 87)
forecast = https://api.weather.gov/gridpoints/MTR/93,87/forecast
```

### Call 2 — daily periods
```
GET https://api.weather.gov/gridpoints/MTR/93,87/forecast
```
`NwsApi.getForecast` (`NwsApi.kt:227-246`):

```kotlin
val response: String = httpClient.get(gridPoint.forecastUrl) {
    header("User-Agent", USER_AGENT)
    header("Accept", "application/json")
}.body()

val jsonObj = json.parseToJsonElement(response).jsonObject
val properties = jsonObj["properties"]?.jsonObject
val updated = properties?.get("updated")?.jsonPrimitive?.content
val generatedAt = properties?.get("generatedAt")?.jsonPrimitive?.content
val periods = properties?.get("periods")?.jsonArray ?: return emptyList()
```

Each period becomes a `ForecastPeriod` (`NwsApi.kt:248-254`):

```kotlin
val name = obj["name"]?.jsonPrimitive?.content ?: ""                       // "Wednesday"
val tempRaw = obj["temperature"]?.jsonPrimitive?.content
val temperature = tempRaw?.toDoubleOrNull()?.roundToInt() ?: 0            // 65
val temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F"
val shortForecast = obj["shortForecast"]?.jsonPrimitive?.content ?: ""     // "Sunny"
val isDaytime = obj["isDaytime"]?.jsonPrimitive?.content?.toBoolean() ?: true
```

The per-period `temperature` (int, °F) is the **sole source** of the daily high written to `ForecastEntity.highTemp`. Downstream aggregation picks the `isDaytime=true` period whose startTime falls on the target local date and uses its temperature as the high; the companion overnight period provides the low.

---

## DB evidence — what was stored for today

Database: `/data/data/com.weatherwidget/databases/weather_database`
Pulled locally via `adb shell run-as com.weatherwidget cat databases/weather_database > /tmp/ww_emu.db`.

Schema (from `.schema forecasts`):
```sql
CREATE TABLE IF NOT EXISTS "forecasts" (
    `targetDate` INTEGER NOT NULL,
    `forecastDate` INTEGER NOT NULL,
    `locationLat` REAL NOT NULL,
    `locationLon` REAL NOT NULL,
    `locationName` TEXT NOT NULL,
    `highTemp` REAL,
    `lowTemp` REAL,
    `condition` TEXT NOT NULL,
    `isClimateNormal` INTEGER NOT NULL,
    `source` TEXT NOT NULL,
    `precipProbability` INTEGER,
    `periodStartTime` INTEGER,
    `periodEndTime` INTEGER,
    `batchFetchedAt` INTEGER NOT NULL,
    `fetchedAt` INTEGER NOT NULL,
    precipAmountMm REAL,
    nativeDailyIconToken TEXT,
    PRIMARY KEY(`targetDate`, `forecastDate`, `locationLat`, `locationLon`, `source`, `fetchedAt`)
);
```

Query:
```sql
SELECT datetime(targetDate/1000,'unixepoch','localtime') AS target,
       datetime(forecastDate/1000,'unixepoch','localtime') AS forecast,
       datetime(fetchedAt/1000,'unixepoch','localtime') AS fetched,
       highTemp, lowTemp, locationLat, locationLon, condition, source
FROM forecasts
WHERE source='NWS'
  AND targetDate >= (strftime('%s','2026-04-13','start of day')*1000)
  AND targetDate <  (strftime('%s','2026-04-14','start of day')*1000)
ORDER BY fetchedAt DESC
LIMIT 10;
```

Result:
```
target               forecast             fetched              highTemp  lowTemp  lat      lon        condition                     source
-------------------  -------------------  -------------------  --------  -------  -------  ---------  ----------------------------  ------
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 15:34:54  65.0               37.422   -122.0841  Sunny                         NWS
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 13:16:11  65.0               37.422   -122.0841  Sunny                         NWS
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 12:38:11  65.0               37.422   -122.0841  Sunny                         NWS
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 11:37:40  65.0               37.422   -122.0841  Sunny                         NWS
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 10:37:02  65.0               37.422   -122.0841  Sunny                         NWS
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 05:17:11  64.0      46.0     37.422   -122.0841  Patchy Fog then Mostly Sunny  NWS
2026-04-12 17:00:00  2026-04-12 17:00:00  2026-04-13 05:17:10  64.0      46.0     37.422   -122.0841  Patchy Fog then Mostly Sunny  NWS
2026-04-12 17:00:00  2026-04-11 17:00:00  2026-04-12 22:47:29  64.0      46.0     37.422   -122.0841  Fog then Sunny                NWS
2026-04-12 17:00:00  2026-04-11 17:00:00  2026-04-12 22:13:44  64.0      46.0     37.422   -122.0841  Fog then Sunny                NWS
2026-04-12 17:00:00  2026-04-11 17:00:00  2026-04-12 21:40:13  64.0      46.0     37.422   -122.0841  Fog then Sunny                NWS
```

Observations:
- `targetDate` and `forecastDate` are stored as epoch millis at UTC midnight. `2026-04-12 17:00:00` local (PDT = UTC−7) corresponds to **2026-04-13 00:00 UTC**, i.e. "April 13 in UTC-anchored storage." This is the April 13 target row.
- NWS had 64°F / "Patchy Fog then Mostly Sunny" in the 05:17 fetch.
- Between 05:17 and 10:37 the Weds-daytime period was **revised upward to 65°F / "Sunny"** by the forecaster at the MTR office.
- Every subsequent fetch (10:37, 11:37, 12:38, 13:16, 15:34) returned the same 65°/Sunny and wrote a fresh row.
- `lowTemp` became NULL in all the April-13-fetched rows because by morning the overnight period ("Tonight" from April 12's perspective) had already rolled out of the period list.
- **Latest fetch: 15:34:54**, ~6.2 hours before the log capture at 21:45. Under the battery-aware schedule for plugged-in devices (60-min interval per CLAUDE.md), we'd expect 5-6 more fetches in that window. Either fetches are firing but not producing new rows (e.g., because NWS no longer returns an April-13-targeted period so the writer skips the row), or they're failing / rate-limited. Worth following up but out of scope for this question.

---

## Live `/forecast` call — reproduced directly

Command:
```bash
curl -sS \
  -H "User-Agent: WeatherWidget/1.0 (contact@weatherwidget.app)" \
  -H "Accept: application/json" \
  "https://api.weather.gov/gridpoints/MTR/93,87/forecast"
```

Response snapshot (`generatedAt = 2026-04-14T04:54:24+00:00`, ≈21:54 PDT on 04-13):

```
#1 Tonight          2026-04-13T21:00 → 2026-04-14T06:00  isDay=False  48°F  Mostly Clear     pop=0
#2 Tuesday          2026-04-14T06:00 → 2026-04-14T18:00  isDay=True   65°F  Mostly Sunny     pop=0
#3 Tuesday Night    2026-04-14T18:00 → 2026-04-15T06:00  isDay=False  47°F  Partly Cloudy    pop=0
#4 Wednesday        2026-04-15T06:00 → 2026-04-15T18:00  isDay=True   66°F  Mostly Sunny     pop=2
#5 Wednesday Night  2026-04-15T18:00 → 2026-04-16T06:00  isDay=False  49°F  Partly Cloudy    pop=3
#6 Thursday         2026-04-16T06:00 → 2026-04-16T18:00  isDay=True   68°F  Sunny            pop=0
```

Key facts from the live response:

1. **There is no period starting on 2026-04-13 anymore.** The Wednesday-daytime period ended at 18:00 PDT and NWS has dropped it. If the widget fetches NWS again right now, the writer has nothing to store for targetDate=April 13 — the existing 15:34 row remains authoritative.
2. The very next daytime period — "Tuesday" April 14 — happens to also be **65°F**, so the number on screen coincidentally matches what tomorrow will show.
3. `pop` (probabilityOfPrecipitation) is 0 for Tuesday, consistent with the "Sunny" stored for today.

---

## Why the stored row says "65" instead of "64" like it did at 05:17

NWS revised the April-13 daytime period upward between 05:17 and 10:37 local. The shortForecast also changed from "Patchy Fog then Mostly Sunny" to plain "Sunny". Every fetch after that morning update read the same revised period and re-wrote the row. The widget is faithfully displaying what NWS most recently said.

---

## Why `hourlyMax` is 64 but `highTemp` is 65

- `/gridpoints/MTR/93,87/forecast` returns per-period **integer** °F values (rounded from a higher-precision internal grid).
- `/gridpoints/MTR/93,87/forecast/hourly` returns an hour-by-hour integer °F series over the same internal grid.

The two endpoints apply rounding independently. For April 13 the daily "Wednesday" period was flagged as 65°F, but no single hourly bin exceeded 64°F — a 1°F discrepancy that's within the published error band and routine for NWS. This is captured in memory already under "API Data Differences."

---

## Why the `observedHigh` is only 53.72°

`observedHigh` in the log is `currentTemp`, which comes from `TemperatureInterpolator` reading hourly forecasts around "now". At 21:45 PDT sunset has passed, the sun has dropped, and the interpolated temperature is well below the day's peak of 64.70° (which was `actual.highTemp`, i.e. the true observed peak captured via the NWS observation-station pipeline earlier today).

The renderer's decision to use **max(observed, forecast, actual)** exists precisely to prevent the Today label from "collapsing" back down to the current temperature as evening falls. Without that `max()`, a glance at the widget at 10 PM would show today's high as ~54° — which would be wrong and confusing.

The side effect is that as long as the forecast high (65°) exceeds the true actual high (64.70°), the widget stays on the forecast value even though the day is effectively over. By a rounding accident, "65" is 0.3° higher than what actually happened (64.70° would round to 65° anyway, so the user-facing difference is zero).

---

## Commands used during the investigation

```bash
# Devices
adb devices

# Most recent DailyEstimator logs
adb -s emulator-5554 logcat -d -t 2000 DailyEstimator:D '*:S' | tail -40

# Pull DB (read-only copy)
adb -s emulator-5554 shell "run-as com.weatherwidget cat databases/weather_database" > /tmp/ww_emu.db

# Schema
sqlite3 /tmp/ww_emu.db ".tables"
sqlite3 /tmp/ww_emu.db ".schema forecasts"

# April 13 NWS forecast rows
sqlite3 -header -column /tmp/ww_emu.db "
  SELECT datetime(targetDate/1000,'unixepoch','localtime') AS target,
         datetime(forecastDate/1000,'unixepoch','localtime') AS forecast,
         datetime(fetchedAt/1000,'unixepoch','localtime') AS fetched,
         highTemp, lowTemp, locationLat, locationLon, condition, source
  FROM forecasts
  WHERE source='NWS'
    AND targetDate >= (strftime('%s','2026-04-13','start of day')*1000)
    AND targetDate <  (strftime('%s','2026-04-14','start of day')*1000)
  ORDER BY fetchedAt DESC LIMIT 10;"

# Grid lookup
curl -sS -H "User-Agent: WeatherWidget/1.0 (contact@weatherwidget.app)" \
     -H "Accept: application/json" \
     "https://api.weather.gov/points/37.422,-122.0841"

# Live forecast
curl -sS -H "User-Agent: WeatherWidget/1.0 (contact@weatherwidget.app)" \
     -H "Accept: application/json" \
     "https://api.weather.gov/gridpoints/MTR/93,87/forecast" > /tmp/nws_forecast.json
```

---

## Files of interest (no changes made)

- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt:398-413` — label computation (`max(day.high, day.forecastHigh, day.trueActualHigh)`)
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt:44-95` — triple-line value computation + `Log.d("DailyEstimator", ...)` at line 81-84
- `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt:77-102` — `getGridPoint` (the `/points` call)
- `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt:227-281` — `getForecast` (the `/gridpoints/.../forecast` call and period parsing)
- `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt:283` — `/gridpoints/.../forecast/hourly` (the separate hourly endpoint)

---

## Open follow-ups (not pursued in this session)

1. **Stale forecast row.** Latest NWS fetch was at 15:34; at 21:45 that's ~6 hours without a refresh, despite the documented 60-min plugged-in interval. Likely explanations:
   - Fetches firing but skipping the writer because NWS no longer returns an April-13-targeted period (the writer may only upsert rows whose targetDate matches a returned period).
   - `NET_FETCH_FAIL` / `NET_FETCH_ERROR` recurring (could check via logcat).
   - Rate limiter bug regression (see memory: `WeatherRepository.lastNetworkFetchTime`).
2. **Triple-line label policy after sunset.** The `max()` label is correct for mid-day but arguably wrong once the day is effectively over (past sunset, currentTemp dropping). Consider preferring `trueActualHigh` after local sunset in `DailyForecastGraphRenderer.kt:400-401`.
3. **Low-temp null.** `fallbackWeather.lowTemp` is NULL for today's row because the overnight period dropped off. `forecastLow` then falls back to `hourlyMin=47`, which is fine; just worth noting the daily row itself carries incomplete data by mid-morning onward.

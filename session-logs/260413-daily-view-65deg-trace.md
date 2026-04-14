# Session Log: Trace 65° Daily View Forecast Source

**Date:** April 13, 2026
**Objective:** Trace the origin of the "65°" displayed for today (April 13) in the daily view forecast on the emulator.

## User Prompts & Critical Decisions

### 1. Question
> **User:** Emulator : Daily view forecast : today: where does the 65 degrees for today come from?

Investigation followed the evidence-first debug protocol — no guessing, all claims verified with runtime evidence (logcat, database, live API).

## Answer: Full Trace from Pixel to API

The 65° displayed for today comes from the **NWS "This Afternoon" forecast period** at `2026-04-13T13:00:00-07:00`, which reported `temperature=65F` with `isDaytime=true`. The value flows through this chain:

1. **NWS API** (`https://api.weather.gov/gridpoints/MTR/93,87/forecast`) returns a period named "This Afternoon" with `temperature: 65`
2. **ForecastRepository.kt:658-682** — `applyForecastPeriods()` maps `isDaytime=true` periods to `highTemp`: `temperatureMap[dateString] = period.temperature.toFloat() to currentTemps.second`
3. **Database** — stored as `forecasts` row: `targetDate=1776038400000 (2026-04-13), source=NWS, highTemp=65.0, lowTemp=NULL`
4. **ForecastDao.getForecastsInRange()** (line 90-112) — selects by `MAX(batchFetchedAt)` per `(targetDate, source)` group, picking the latest batch
5. **DailyViewHandler.kt:244-248** — `weatherByDate` map groups by date, prefers `displaySource` entry → NWS entity with `highTemp=65.0` becomes `weatherByDate[today]`
6. **DailyActualsEstimator.kt:75** — `forecastHigh = fallbackWeather?.highTemp ?: hourlyMax` = **65.0** (hourly max was only 64.0, so forecast value wins)
7. **DailyViewLogic.kt:146** — `visibleHigh = max(observedHigh=53.72, forecastHigh=65.0, trueActualHigh=64.70)` = **65.0**

## Runtime Evidence

### Logcat: DailyEstimator (final rendering decision)

```
DailyEstimator: today: actual.high=64.69983 actual.low=51.037434 currentTemp=53.722717
    observedHigh=53.722717 observedLow=51.037434
    fallbackWeather.high=65.0 fallbackWeather.low=null
    hourlyMax=64.0 hourlyMin=47.0
    forecastHigh=65.0 forecastLow=47.0
    todayHourlyCount=24 source=NWS
```

This confirms:
- `fallbackWeather.high=65.0` (the NWS daily forecast)
- `hourlyMax=64.0` (the hourly forecast data max)
- `forecastHigh=65.0` (fallback wins because `fallbackWeather?.highTemp` is non-null)
- `fallbackWeather.low=null` (NWS dropped the daytime period, so no low is available for today)
- `forecastLow=47.0` (falls through to `hourlyMin`)

### Logcat: WidgetRenderer (current temp resolution)

```
WidgetRenderer: currentObservationSelection: widget=25 viewMode=DAILY zoom=WIDE source=NWS
    selected=graph_style graphStyleTemp=53.972717 graphStyleObservedAt=1776141000000
    fallbackTemp=54.257046 fallbackObservedAt=1776141000000
    finalTemp=53.972717 finalObservedAt=1776141000000
```

### Logcat: DAILY_RENDER (overall layout)

```
DAILY_RENDER: widget=25 mode=GRAPH offset=-1 cols=9 rows=5 evening=true center=2026-04-13 source=NWS days=9
    dates=2026-04-12,2026-04-13,2026-04-14,2026-04-15,2026-04-16,2026-04-17,2026-04-18,2026-04-19,2026-04-20

DailyGraphRenderer: renderGraph: days=9, minTemp=45.0, maxTemp=74.0, widthPx=584, heightPx=385
```

### Database: NWS forecasts for today (2026-04-13 = epoch 1776038400000)

Most recent entries (ordered by `fetchedAt` DESC):

| targetDate (UTC) | highTemp | lowTemp | source | fetchedAt (UTC) |
|---|---|---|---|---|
| 2026-04-13 | 65.0 | NULL | NWS | 2026-04-13 22:34:54 |
| 2026-04-13 | 65.0 | NULL | NWS | 2026-04-13 20:16:11 |
| 2026-04-13 | 65.0 | NULL | NWS | 2026-04-13 19:38:11 |
| 2026-04-13 | 65.0 | NULL | NWS | 2026-04-13 18:37:40 |
| 2026-04-13 | 65.0 | NULL | NWS | 2026-04-13 17:37:02 |
| 2026-04-13 | 64.0 | 46.0 | NWS | 2026-04-13 12:17:11 |
| 2026-04-13 | 64.0 | 46.0 | NWS | 2026-04-13 05:47:29 |
| 2026-04-13 | 64.0 | 46.0 | NWS | 2026-04-13 05:13:44 |
| 2026-04-13 | 64.0 | 46.0 | NWS | 2026-04-13 04:40:13 |
| ... (earlier entries with highTemp values ranging from 61-68) | | | | |

The `getForecastsInRange()` DAO query selects `MAX(batchFetchedAt)` per `(targetDate, source)`, so the winning row is `highTemp=65.0, lowTemp=NULL` fetched at 22:34 UTC.

### Database: daily_extremes for today

| date | source | highTemp | lowTemp | updatedAt (UTC) |
|---|---|---|---|---|
| 2026-04-13 | NWS | 64.699829 | 51.037434 | 2026-04-13 22:55:31 |
| 2026-04-13 | OPEN_METEO | 60.5 | 52.7 | 2026-04-13 21:45:57 |
| 2026-04-13 | SILURIAN | 60.337185 | 54.060658 | 2026-04-13 21:45:57 |
| 2026-04-13 | VISUAL_CROSSING | 63.7 | 54.3 | 2026-04-13 21:52:59 |

The `trueActualHigh` of 64.69983 in the log comes from `dailyActuals[today]` (the NWS extreme from observations). But this is only used as one of three candidates in `max(observedHigh, forecastHigh, trueActualHigh)`, and forecastHigh=65.0 wins.

### App Logs: NWS_TODAY_SOURCE (provenance trail)

```
fetchedAt=1776130482255 | high=65.0 (FCST:This Afternoon@2026-04-13T13:00:00-07:00) low=null (null)
fetchedAt=1776119689579 | high=65.0 (FCST:This Afternoon@2026-04-13T13:00:00-07:00) low=null (null)
fetchedAt=1776111368124 | high=65.0 (FCST:This Afternoon@2026-04-13T12:00:00-07:00) low=null (null)
fetchedAt=1776109090296 | high=65.0 (FCST:This Afternoon@2026-04-13T12:00:00-07:00) low=null (null)
fetchedAt=1776105460364 | high=65.0 (FCST:Today@2026-04-13T11:00:00-07:00) low=null (null)
fetchedAt=1776101822245 | high=65.0 (FCST:Today@2026-04-13T09:00:00-07:00) low=null (null)
fetchedAt=1776082630555 | high=64.0 (FCST:Monday@2026-04-13T06:00:00-07:00) low=46.0 (FCST:Overnight@2026-04-13T03:00:00-07:00)
fetchedAt=1776082630036 | high=64.0 (FCST:Monday@2026-04-13T06:00:00-07:00) low=46.0 (FCST:Overnight@2026-04-13T03:00:00-07:00)
```

This shows the evolution:
1. **Morning (pre-9 AM local)**: NWS had a full day period "Monday" with high=64, low=46
2. **From 9 AM local onwards**: NWS split into "Today" then "This Afternoon" with high=65, but **no low** (because the overnight period belonged to tomorrow)
3. After ~5 PM local, NWS dropped the daytime period entirely, keeping only "Tonight" at 48°F — but the `highTemp=65, lowTemp=NULL` row persisted in the database

### NWS API: Live call at investigation time

```
GET https://api.weather.gov/gridpoints/MTR/93,87/forecast

Period 0: Tonight       2026-04-13T21:00:00-07:00 -> 2026-04-14T06:00:00-07:00  temp=48F  daytime=False
Period 1: Tuesday       2026-04-14T06:00:00-07:00 -> 2026-04-14T18:00:00-07:00  temp=65F  daytime=True
Period 2: Tuesday Night 2026-04-14T18:00:00-07:00 -> 2026-04-15T06:00:00-07:00  temp=47F  daytime=False
...
```

The current live API no longer contains any Monday daytime period — it starts with "Tonight" at 48°F. The "Tuesday" period showing 65°F is for tomorrow (April 14), not today.

### NWS API: Logcat from last fetch (21:41 UTC / 2:41 PM local)

```
NwsApi: getForecast: url=https://api.weather.gov/gridpoints/MTR/93,87/forecast,
    fetchedAt=1776141667128, updated=null, generatedAt=2026-04-14T01:34:44+00:00, periodCount=14

NwsApi: getForecast[0]: name=Tonight start=2026-04-13T18:00:00-07:00 end=2026-04-14T06:00:00-07:00
    tempRaw=48 tempRounded=48 unit=F isDaytime=false short=Mostly Clear pop=1
NwsApi: getForecast[1]: name=Tuesday start=2026-04-14T06:00:00-07:00 end=2026-04-14T18:00:00-07:00
    tempRaw=65 tempRounded=65 unit=F isDaytime=true short=Mostly Sunny pop=0
...
```

This confirms the API response had the "Tuesday" period with temp=65 being mapped to targetDate 2026-04-14 (tomorrow), while the "Tonight" period at 48°F provides no daytime high for today.

## Code Path: How 65° Flows Through the System

### Step 1: NWS API Response → `applyForecastPeriods()`

**File:** `ForecastRepository.kt:644-690`

```kotlin
private fun applyForecastPeriods(
    forecastPeriods: List<NwsApi.ForecastPeriod>,
    todayDateString: String,
    temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
    ...
): List<NwsApi.ForecastPeriod> {
    forecastPeriods.forEach { period ->
        val dateString = extractNwsForecastDate(period.startTime) ?: return@forEach
        if (period.isDaytime) {
            val currentTemps = temperatureMap[dateString] ?: (null to null)
            temperatureMap[dateString] = period.temperature.toFloat() to currentTemps.second  // ← highTemp set here
            highTempSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
        } else {
            val lowDateString = extractNwsForecastDate(period.endTime) ?: dateString
            val currentLowTemps = temperatureMap[lowDateString] ?: (null to null)
            temperatureMap[lowDateString] = currentLowTemps.first to period.temperature.toFloat()  // ← lowTemp set here
        }
    }
}
```

When the NWS API returned "This Afternoon" (daytime period for 2026-04-13, temp=65):
- `dateString = "2026-04-13"`
- `temperatureMap["2026-04-13"] = 65.0 to null` (high=65, low=null because no overnight period maps low to today's date)

### Step 2: Persisted to `forecasts` table

The most recent batch stores `highTemp=65.0, lowTemp=NULL` for `targetDate=2026-04-13, source=NWS`.

### Step 3: `getForecastsInRange()` selects the latest-batch row

**File:** `ForecastDao.kt:90-112`

```sql
SELECT * FROM forecasts f1
WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
AND targetDate >= :startDate AND targetDate <= :endDate
AND batchFetchedAt = (
    SELECT MAX(batchFetchedAt) FROM forecasts f2
    WHERE f2.targetDate = f1.targetDate
    AND f2.source = f1.source
    AND f2.locationLat = f1.locationLat
    AND f2.locationLon = f1.locationLon
)
ORDER BY targetDate ASC
```

This picks the row with `batchFetchedAt=1776119694560` (the latest batch for NWS+today), which has `highTemp=65.0, lowTemp=NULL`.

### Step 4: `weatherByDate` map construction

**File:** `DailyViewHandler.kt:244-248`

```kotlin
val weatherByDate =
    weatherList
        .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
        .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
        .mapValues { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }
```

Since the widget's `displaySource` is NWS, the NWS forecast entity with `highTemp=65.0` is selected as `weatherByDate[today]`.

### Step 5: `DailyActualsEstimator.calculateTodayTripleLineValues()`

**File:** `DailyActualsEstimator.kt:44-94`

```kotlin
val forecastHigh = fallbackWeather?.highTemp ?: hourlyMax  // 65.0 ?: 64.0 = 65.0
val forecastLow = listOfNotNull(
    fallbackWeather?.lowTemp,  // null
    hourlyMin                   // 47.0
).minOrNull()                  // min(null, 47.0) = 47.0
```

- `fallbackWeather.highTemp` = 65.0 (from NWS forecast) — used directly
- `fallbackWeather.lowTemp` = null (NWS evening period dropped the daytime data) — falls through to `hourlyMin=47.0`

### Step 6: `DailyViewLogic.prepareTextDays()` final selection

**File:** `DailyViewLogic.kt:136-153`

```kotlin
// isToday branch
val tripleValues = DailyActualsEstimator.calculateTodayTripleLineValues(...)

val visibleHigh = listOfNotNull(
    tripleValues.observedHigh,   // 53.72 (current observed temperature)
    tripleValues.forecastHigh,   // 65.0 (NWS "This Afternoon" prediction)
    tripleValues.trueActualHigh  // 64.70 (daily_extremes NWS high so far)
).maxOrNull()                    // max(53.72, 65.0, 64.70) = 65.0

val visibleLow = tripleValues.observedLow ?: tripleValues.forecastLow  // 51.04 or 47.0
highLabel = formatTempLabel(visibleHigh)  // "65°"
```

The `max()` call ensures the displayed high incorporates all three sources. Since `forecastHigh=65.0` exceeds both `observedHigh=53.72` and `trueActualHigh=64.70`, the displayed value is **65°**.

## Why `lowTemp=NULL` for Today

NWS reports overnight lows as the *end time* date (per `ForecastRepository.kt:678`):

```kotlin
// The overnight low physically occurs in the early morning of the following day.
// Use the endTime's date (not startTime's) so the low is attributed correctly.
val lowDateString = extractNwsForecastDate(period.endTime) ?: dateString
```

For today (April 13):
- **Morning fetches** (before 9 AM): The NWS had a "Monday" period (6 AM–6 PM) with high=64, and an "Overnight" period whose `endTime` was April 13, so `low=46` was attributed to today. Hence `highTemp=64.0, lowTemp=46.0`.
- **Afternoon fetches** (9 AM+): NWS split the day into "Today" / "This Afternoon" (high only, `isDaytime=true`). The "Tonight" period (6 PM–6 AM next day) has `endTime=2026-04-14`, so its low gets attributed to **tomorrow**. Hence `lowTemp=NULL` for today.

## Why 65° Persists After Evening

1. The NWS daily forecast API only returns future periods. Once the "This Afternoon" period expired (~5 PM local), the API stopped including it.
2. But the app's database already had `highTemp=65.0, lowTemp=NULL` stored from the 2:34 PM fetch (batchFetchedAt=1776119694560).
3. The DAO query `getForecastsInRange()` selects the row with `MAX(batchFetchedAt)` per `(targetDate, source)`, which is the 10:34 PM batch that still had `highTemp=65.0` for today.
4. No subsequent fetch overwrote today's high with a different value (the evening fetches only produced "Tonight" periods mapping to tomorrow).

## Generic / Climate Normal Fallbacks

The database also contains `source=Generic` (climate normal) entries for today with `highTemp=70.0, lowTemp=52.0, isClimateNormal=1`. These are only used as a last-resort fallback when no real forecast data is available (`DailyViewLogic.kt:156-162`). Since NWS data exists, climate normals are not consulted for today.

## Open-Meteo Data

The `daily_extremes` table has Open-Meteo's observed high for today at 60.5°F (from hourly data), but this is not the display source — the widget is configured to use NWS, which takes priority in the `weatherByDate` map.

## Database Queries Used

```sql
-- NWS forecasts for today (all, including stale batches)
SELECT targetDate, highTemp, lowTemp, source, isClimateNormal,
       datetime(fetchedAt/1000, 'unixepoch'), datetime(batchFetchedAt/1000, 'unixepoch')
FROM forecasts
WHERE targetDate = 1776038400000 AND source = 'NWS' AND isClimateNormal = 0
ORDER BY fetchedAt DESC;

-- Daily extremes for today (all sources)
SELECT date, source, highTemp, lowTemp, datetime(updatedAt/1000, 'unixepoch')
FROM daily_extremes
WHERE date = 1776038400000;

-- NWS_TODAY_SOURCE app logs for today
SELECT * FROM app_logs
WHERE tag = 'NWS_TODAY_SOURCE'
ORDER BY timestamp DESC LIMIT 10;
```

## API Calls Used

```bash
# Gridpoint resolution
curl -s -H "User-Agent: WeatherWidget/1.0 (contact@weatherwidget.app)" \
  -H "Accept: application/json" \
  "https://api.weather.gov/points/37.422,-122.084"

# Daily forecast (same endpoint the app uses)
curl -s -H "User-Agent: WeatherWidget/1.0 (contact@weatherwidget.app)" \
  -H "Accept: application/json" \
  "https://api.weather.gov/gridpoints/MTR/93,87/forecast"
```

## Key Files Referenced

1. `ForecastRepository.kt:658-682` — `applyForecastPeriods()`: maps NWS periods to `highTemp`/`lowTemp` by daytime flag and `extractNwsForecastDate()`
2. `ForecastRepository.kt:692-716` — `logTodayDiagnostics()`: logs `NWS_TODAY_SOURCE` provenance trail
3. `ForecastDao.kt:90-112` — `getForecastsInRange()`: SQL query selecting `MAX(batchFetchedAt)` per `(targetDate, source)`
4. `DailyViewHandler.kt:244-248` — `weatherByDate` construction: groups by date, prefers display source
5. `DailyActualsEstimator.kt:44-94` — `calculateTodayTripleLineValues()`: merges actual, forecast, and hourly data
6. `DailyViewLogic.kt:136-153` — `prepareTextDays()`: selects `max(observedHigh, forecastHigh, trueActualHigh)` as `visibleHigh`
7. `NwsApi.kt:227-280` — `getForecast()`: calls `gridPoint.forecastUrl` and parses `temperature` from each period
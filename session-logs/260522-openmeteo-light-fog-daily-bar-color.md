# Open-Meteo Light Fog and Daily Bar Color Investigation

## User Prompts
1. "Emulators: Meteo api, daily forecast view for today. Cloud cover percent as donated by the vertical bar color seems wrong."
2. "I see the bar as all grey, why do you state it is mostly gold?"
3. "Is is possible the drawing is stale? Forecast updated after it was drawn?"
4. "Investigate why drawn bars are solid grey on emulators, when they should be near solid gold for daily forecast view."
5. "There seems to be several problems. It is not foggy and meteo api doesn't say it is foggy. What does the meteo api say about foggy? Slightly foggy?"
6. "Can we map code 45 to slightly foggy? If yes, would that solve the vertical bar color?"
7. "Lets change 45 to light fog , then proceed from there."
8. "Implement the plan."
9. "Yes , countrary to what you said, that did fix the vertical bar color issue."
10. "Write detailed session log to session-logs/ dir"

## Runtime Context
1. The investigation focused on the daily forecast view on emulators, especially `emulator-5556`.
2. The widget was using Open-Meteo (`source=OPEN_METEO`) and showing a daily graph for Today.
3. A screenshot captured from `emulator-5556` showed the Today forecast bar as solid grey even though the cloud-cover ratio being logged for the bar was near clear.
4. The worktree already had an unrelated untracked `cloud_graph.png`; it was not touched.

## Evidence Collected
1. Logcat showed repeated daily renders for widget `13`, Open-Meteo, Today `2026-05-22`.
2. Representative renderer log lines showed:
   1. `today_forecast mixed bar geometry: date=2026-05-22 ... splitRatio=0.05 topFraction=0.95 topColor=#FFF4C542 bottomColor=#FF8E99A4`
   2. `graphDay ... date=2026-05-22 isToday=true iconName=ic_weather_fog`
3. The app database snapshot showed Open-Meteo hourly rows for `2026-05-22`:
   1. `07:00` condition `Fog`, cloud cover `100`.
   2. `08:00` condition `Overcast`, cloud cover `99`.
   3. `09:00` condition `Overcast`, cloud cover `100`.
   4. `10:00` condition `Partly Cloudy`, cloud cover `61`.
   5. `11:00` condition `Clear`, cloud cover `11`.
   6. `12:00` condition `Clear`, cloud cover `5`.
4. A live Open-Meteo query for `37.422,-122.0841` returned:
   1. Current `2026-05-22T07:45 weather_code=3`, meaning overcast.
   2. Daily `2026-05-22 weather_code=45`.
   3. Hourly `07:00 weather_code=45 cloud=100`.
   4. Hourly `08:00 weather_code=3 cloud=99`.
   5. Hourly `12:00 weather_code=0 cloud=5`.
5. Open-Meteo's public WMO code table uses `45` for fog and `48` for depositing rime fog. It does not expose a separate "slightly foggy" code.

## Findings
1. There were two overlapping issues:
   1. The app mapped Open-Meteo code `45` to full `"Fog"`, which resolved to the full fog icon.
   2. The daily bar renderer had a suspicious implementation detail: `drawWeatherAdaptiveBar` logged `split.topColor` as gold but created the top segment paint from the incoming base `paint.color`.
2. The first issue was the actual user-visible fix for this emulator case:
   1. With code `45` mapped to `"Fog"`, Today resolved to `ic_weather_fog`.
   2. Full fog is not a mixed icon, so its base forecast color was cloudy grey.
   3. The top segment was therefore drawn grey even though the debug log said the split top color was gold.
3. The original assessment that changing `45` to light fog would not fully solve the vertical bar color was too conservative for this specific case.
4. After changing `45` to `"Light Fog"`, the icon path resolves to `ic_weather_fog_light`, which is a mixed icon. That causes the daily bar to use the mixed/sunny forecast color path, fixing the observed grey Today bar for Open-Meteo code `45`.
5. The renderer inconsistency may still be worth addressing separately, but it was not required to fix the observed Open-Meteo `45` daily bar issue.

## Code Changes
1. `app/src/main/java/com/weatherwidget/data/remote/OpenMeteoApi.kt`
   1. Changed `weatherCodeToCondition(45)` from `"Fog"` to `"Light Fog"`.
   2. Left `48` as `"Dense Fog"`.
2. `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`
   1. Changed the Open-Meteo native daily token mapper for code `45` from `"Fog"` to `"Light Fog"`.
   2. Left code `48` as `"Dense Fog"`.
3. `app/src/test/java/com/weatherwidget/data/remote/OpenMeteoApiTest.kt`
   1. Added assertions that code `45` maps to `"Light Fog"` and code `48` maps to `"Dense Fog"`.
4. `app/src/test/java/com/weatherwidget/util/DailyForecastIconResolverTest.kt`
   1. Added a regression test that Open-Meteo native token `"45"` resolves to `R.drawable.ic_weather_fog_light`.

## Verification
1. Focused test command:

```bash
./gradlew testDebugUnitTest --tests '*OpenMeteoApiTest*' --tests '*DailyForecastIconResolverTest*' --tests '*WeatherIconMapperTest*' --tests '*DailyViewLogicTest*'
```

2. Result: passed.
3. Build command:

```bash
./gradlew assembleDebug
```

4. Result: passed.

## Remaining Notes
1. The separate renderer behavior should remain on the watch list:
   1. `drawWeatherAdaptiveBar` logs `split.topColor`.
   2. The implementation still draws the top segment from the incoming base paint color.
   3. This can make logs overstate how gold the visible bar should be when the icon's base color is not already gold.
2. For this specific Open-Meteo `45` case, the product mapping change fixed the visible daily bar color by routing the icon through the light-fog/mixed path.
3. Existing cached database rows will continue to show whatever condition string was stored before the mapping change until Open-Meteo data is fetched again.

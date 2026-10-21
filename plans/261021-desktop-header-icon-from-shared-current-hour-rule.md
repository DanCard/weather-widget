# Desktop header icon: one shared current-hour rule

## Bug (2026-10-21 ~10:00, Mountain View, Google displayed)
Desktop header showed a **sunny** icon; every source's 10:00 hourly said overcast
(Google `Cloudy` 99 %, NWS `Cloudy` 90 %, Open-Meteo `Overcast` 96 %). Android's header, same data,
showed cloudy.

## Root cause
The two platforms pick the header icon from different inputs.

- **Android** (`DailyHeaderResolver.resolveState`): the displayed source's hourly row for the next
  hour (or now) via shared `HourlyForecastSelector`, then
  `resolveIconName(condition, isNight, cloudCover, precipProbability)`; falls back to today's daily icon.
- **Desktop** (`DesktopWidgetHeader` → `WeatherIcon.painter(resolved.currentCondition)`): a bare
  condition *string* — the nearest NWS station's text (`DesktopWeatherService` `closestBundle.latest`,
  or `latestObs.condition` in `DesktopWeatherRepository`), else `hourly.firstOrNull()` (not
  necessarily the current hour). No cloud cover, no rain %, no night.

The nearest station is **KNUQ** (3.8 km): `KNUQ 211655Z AUTO … 9SM CLR`. An automated ASOS ceilometer
only sees below ~12,000 ft, so a high overcast reads `CLR` → "Clear" → sun. The observation was
borrowed from NWS even though Google was displayed.

## Fix
1. `:shared` `HeaderConditionIcon.resolveIconName(hourly: List<HourlyForecast>, displaySourceId, now,
   lat, lon, todayDaily…)` — Android's rule moved verbatim: `HourlyForecastSelector` row for
   now+1h (same day) else now; `SunPositionUtils.isNight`; fallback today's daily icon resolution.
2. Android `DailyHeaderResolver` (and `HourlyHeaderBinder` if it repeats the rule) call it; maps the
   name to a drawable as today. No behaviour change on Android.
3. Desktop `DesktopWidgetHeader` calls it with the snapshot's hourly; `WeatherIcon` maps the name.
   `resolved.currentCondition` stays only for what still needs a text condition (panel/`CurrentStatus`)
   — check each reader; any that draws an icon moves to the shared rule too.
4. Log once per change: `HEADER_ICON source=… hour=… condition=… cloud=… icon=…` — desktop console /
   autostart log (the composable has no DAO, so not app_logs).

## Done 2026-10-21
`:shared` `HeaderConditionIcon` (hour keys, row pick, icon name; twilight for hourly views as Android's
hourly headers); Android `DailyHeaderBinder.resolveTodayHeaderForecast` uses its hour keys; desktop
`resolveHeaderIconName`. Tests `HeaderConditionIconTest`, `DesktopHeaderIconTest`. Verified live:
desktop on Silurian drew `ic_weather_cloudy_slight_chance_rain` (11:00 Rain, 97 % cloud, 30 %).

## Tests
- Shared unit: overcast hourly + a "Clear" observation present → cloudy icon; next-hour preference;
  night; no hourly → daily fallback; non-displayed sources' rows ignored.
- Desktop: header test asserting the icon resource for a fixture with an AUTO-CLR station and 99 %
  cloud hourly (fails on the old code).
- Android: existing `DailyHeaderResolver` tests unchanged (equivalence).

## Follow-up (not in this change)
Audit of the rest of the desktop header (temp, delta, rain %, nowcast, date/centre fit) for logic
Android already has and desktop re-implements; one shared resolver per header field.

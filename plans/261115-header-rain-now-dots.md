# Header icon shows blue dots while it is raining now

**Status:** approved 2026-11-15 ("Lets leave rain as blue always. proceed"), implemented
**Date:** 2026-11-15
**Mockups:** https://claude.ai/artifact/Vo6nPhHKkRitDbFrWA63j3 (option E, chosen by the user: "Yes, I love option E")

## Goal

While it is raining now, the header icon is only blue dots: no cloud, no forecast art. The dot count
and size encode intensity. When nothing says it is raining any more, the forecast icon comes back.
This applies on Android and desktop and in every view whose header draws an icon: daily, temperature,
precipitation and cloud cover.

Three inputs can turn the dots on (user, 2026-11-15: "I like the idea that radar turns on rain dots.
Tomorrow.io should only turn it on if we are viewing tomorrow api or source is tomorrow.io"):

1. **Station:** the nearest official station's newest report says rain or drizzle.
2. **Radar:** NOAA's radar mosaic shows an echo at the site. This applies inside NWS coverage only.
3. **Tomorrow.io:** its 5-minute history says rain or drizzle. This counts **only while Tomorrow.io is
   the displayed source**.

The level shown is the strongest of the three. Any one of them turns the dots on.

## Evidence (2026-11-15, desktop DB `~/.local/share/weather-widget/weather.db`, `app_logs`)

| Local time | KNUQ (3.8 km, reports :15/:35/:55) | Radar at the site | Tomorrow.io 5-min |
|---|---|---|---|
| 16:55 | `9SM BKN033 …` dry | no echo until 16:57 | Cloudy |
| 17:04 | — | echo `86,149,193` (blue) | Cloudy |
| 17:15 / 17:35 | dry, 9–10 SM (AWC; our DB has no 17:15 row) | echo | Cloudy / **Rain from 17:25** |
| **17:55** | **`7SM -RA FEW005 …`** | echo | Rain |
| 18:15 → 20:55 | `-RA BR` every report (2½ → 1¼ → 2½ SM) | echo; 21:12 sample `14,214,20` (green) | Rain / Drizzle |

What this shows:
1. Model "Rain" labels can't be trusted for "raining now". OWM said `Rain` at 14:10 while every gauge
   read dry.
2. Radar sees rain aloft before it lands. From 17:04 to 17:55 the echo was blue (weak). KNUQ measured
   dry twice with a 4–5 °C temperature–dewpoint gap, which looks like virga. Weak echoes should
   therefore draw the lightest dots.
3. **Station latency is ~20 min, not an hour.** api.weather.gov rows arrive 60–80 min late. The
   fetch-both path stores the NWS METAR-text copy (`isWebFallback=1`) about 20 min after the report
   (`OBS_WEB_API_DELTA … chosen=web`). The 17:15 gap is the desktop observation loop not running
   from 17:01 to 18:45.
4. When the API row for the same time lands later, it **replaces** that copy. If the API row has no
   `rawMessage`, the stored row ends up blank (17:35, 18:55). This doesn't affect "now", because a
   newer row exists by then. It is a separate history bug and is **not** in this plan.
5. Most METAR rows store an **empty `condition`**, so the rule must decode `rawMetar`. KPAO 17:57 read
   `" Rain"` as its condition while its METAR said `VCSH`, so the raw METAR wins.
6. **Tomorrow.io 5-min rows are stored `stationType=1` (official) at 0 km**
   (`stationId=TOMORROW_IO_5M_HISTORY`). Without an explicit exclusion, the station rule would treat
   them as the nearest station under every source.

## Rule (`:shared`, new `com.weatherwidget.shared.observations.RainNow`)

```
enum class Level { SPRINKLING, RAINING, HEAVY }
data class Verdict(val level: Level?, val station: Level?, val radar: Level?, val tomorrow: Level?, …why)
fun resolve(readings, radar: RadarNowSample?, displaySourceId, lat, lon, nowMs): Verdict
```

### 1. Station

- **Rows that can speak:**
  - `stationType == OFFICIAL`, not `qcFailed`, not the `NwsBlend` row.
  - `api` is a station network: NWS, METAR or SYNOPTIC. Tomorrow.io and model-current rows are excluded.
  - `distanceKm ≤ ObservedPrecip.MAX_STATION_KM` (25 km).
  - `timestamp` in `(now − 75 min, now + 5 min]`.
  - A non-blank `rawMetar` or `condition`.
- **The nearest station decides**, using its newest speaking row. KSJC raining 16 km away doesn't turn
  the dots on while KNUQ reads dry.
- **Classify** with `MetarDecoder.presentWeather`, ignoring `VC*` groups:

  | Group | Level |
  |---|---|
  | `+RA`, `+SHRA`, TS with rain, `+DZ` | HEAVY |
  | `RA`, `-RA`, `SHRA`, `-SHRA`, `FZRA`, `UP` | RAINING |
  | `DZ`, `-DZ`, `FZDZ` | SPRINKLING |
  | No precipitation group | dry (null) |

  The strongest group wins. A row without `rawMetar` falls back to the NWS `condition` words
  ("Heavy Rain" / "Light Rain" / "Drizzle" / "…in Vicinity" → null).
- Snow and ice (`SN`, `PL`, `GR`, `GS`) → null. *Claude proposal.*

### 2. Radar (new)

- **Source:** the same NOAA GeoServer WMS the Radar pill already uses
  (`opengeo.ncep.noaa.gov`, `{workspace}_bref_qcd`; `RadarFeed.Nws`).
  - One `GetFeatureInfo` per run: a 3×3-pixel box of ±0.01° around the site, centre pixel, JSON.
  - The response is one pixel's RGBA (measured 2026-11-15:
    `{"RED_BAND":14,"GREEN_BAND":214,"BLUE_BAND":20,"ALPHA_BAND":255}`).
  - No key is needed. The site's coordinates already go to this host for the Radar pill; the user
    approved NOAA.
- **Classify the pixel:**
  - Alpha < 0x80 means no echo, the same cut as `RadarLoop.hasEchoes`.
  - Otherwise use colour family, not exact colours, because NOAA publishes no legend: GetLegendGraphic
    is blank, the SLD has no ColorMap, and the ImageServer legend is just R/G/B bands. *Claude
    proposal:*
    - **Blue or cyan** (B is the largest channel) → SPRINKLING. This covers weak echoes like tonight's
      virga hour.
    - **Green** (G largest, R < 150) → RAINING.
    - **Yellow, orange, red or magenta** (R ≥ 150) → HEAVY.
- **Freshness:** a sample counts for 25 min: the 20-min screen-on battery run interval
  (`CurrentTempCadence.BATTERY_SCREEN_ON_MINUTES`) plus 5 min slack, so radar-only dots don't blink off
  between runs. A 15-min limit went dark 5 min in every 20 on battery (side-agent review, 2026-11-15).
  The 66–69 % band's 45-min runs can still leave gaps.
- **Outside NWS coverage** (RainViewer feed): no radar input. RainViewer serves only tiles, capped at
  zoom 7. *Claude proposal.*
- **Known limit:** radar can't tell snow from rain. A snow echo would draw rain dots.
- **When it is fetched:** inside the shared `CurrentConditionsRun`, the runner both platforms already
  use on the observation cadence.
  - It runs once per run for the site and doesn't count against any provider's quota.
  - Nothing fetches radar while no run is due. Screen off, Android battery ≤ 65 % → no runs.
  - A failure logs `RADAR_NOW_FAIL` and keeps the previous sample until it ages out.
- **Storage:** the shared runner owns fetch, classification, freshness and logging. Each platform's
  `Host` only stores the last sample per site (quantized with `LocationMatch`):
  - Android: SharedPreferences, because the renderer runs separately from the worker.
  - Desktop: a small file, `~/.cache/weather-widget/radar-now.txt`. The daemon samples and the UI
    process draws the header, so memory would not reach it (found during implementation).
  - No DB schema change.

### 3. Tomorrow.io (new)

- Counts only when the header's `displaySourceId` is Tomorrow.io.
- Uses the newest `TOMORROW_IO_5M_HISTORY` row within 30 min. *Claude proposal.*
- Mapping from `WeatherCodeMapper.tomorrowIoCodeToCondition`:

  | Condition | Level |
  |---|---|
  | "Drizzle" | SPRINKLING |
  | "Rain", "Freezing Rain", "Thunderstorm" | RAINING |
  | "Heavy Rain" | HEAVY |
  | Snow or ice | null |

### Combining

The level is the highest of station, radar and Tomorrow.io; null when all are null. Tonight's replay:

| Time | Station | Radar | Dots |
|---|---|---|---|
| 16:55 | dry | none | off |
| 17:05 | dry | blue | sprinkling |
| 17:55 | `-RA` | — | raining |
| 21:12 | `-RA` | green | raining |

With Tomorrow.io displayed, 17:25 is raining.

## Wiring

- **`HeaderContent.build` / `iconName`:**
  - Take a required `rainNow: RainNow.Level?`, resolved once per render by the caller with
    `RainNow.resolve`.
  - A non-null level returns the rain-now icon. Otherwise today's forecast rule is unchanged.
  - Required, with no default, so a forgotten call site fails to compile.
- **Android:**
  - `WidgetRenderer` already loads `observationRows` (≈ 37.5 h, location-scoped, every API) for the
    current temperature. It resolves `RainNow` once from those rows, the stored radar sample and the
    displayed source.
  - The level travels through `WidgetViewModeDispatcher` params to the four header builders
    (`DailyHeaderResolver`, `TemperatureHeaderStateBuilder`, `PrecipViewHandler`,
    `CloudCoverViewHandler`) and the `GraphInteractionRenderer` path.
  - No new DB query.
- **Desktop:**
  - `desktopHeaderContent` resolves it from `forecast.raw.rawObservations`, the service's radar
    sample and the displayed source.
  - Add the level to the Compose `remember` keys so the icon updates when it changes.
- **Icons:**
  - Three new names in `WeatherConditionResolver`: `IC_RAIN_NOW_LIGHT`, `IC_RAIN_NOW`,
    `IC_RAIN_NOW_HEAVY`.
  - Vector drawables in `app/src/main/res/drawable/`, with identical copies in
    `desktop/src/main/resources/drawable/`.
  - Dots are circles, colour `#2E9BF0`, on a 24×24 viewport, laid out in offset rows as in mockup E:
    - Sprinkling: 5 dots, r 1.55
    - Raining: 10 dots, r 1.9
    - Heavy: 14 dots, r 2.2
  - `WeatherIconMapper` maps the three. Android `IconGalleryActivity` and the desktop `SettingsWindow`
    galleries list them, with labels in all 19 locales.
- **Logging (shared):**
  - `RAIN_NOW` `Log.d` on **transitions only**. It names the inputs that are on (station id, distance,
    report time, decisive group; radar RGBA and sample age; Tomorrow.io condition).
  - `RADAR_NOW` `Log.v` for each sample.
  - `RADAR_NOW_FAIL` `Log.w` for each failure.

## Not changing

- The rain %, its 6-hour window, the daily bars and the graphs.
- The forecast icon rule (`HeaderConditionIcon`) when it isn't raining.
- No change to the observation cadence and no schema change. The one new request is the radar pixel
  per current-conditions run.
- The blank-API-row overwrite (evidence 4) is a separate fix, if wanted.

## Tests (each fails on the current code)

1. **`:shared` `RainNowTest` (pure JVM):**
   - Station classification:
     - `-RA` → RAINING, `+RA` → HEAVY, `-DZ` → SPRINKLING, `-RA BR` → RAINING, `RA DZ` → RAINING,
       `UP` → RAINING.
     - `VCSH` → null, `-SN` → null, AO2 with no group → null.
   - Raw METAR beats condition.
   - A blank row is skipped. A stale row (> 75 min) is ignored, as is a row > 25 km away or from a
     non-official station.
   - Nearest decides: KNUQ dry + KSJC `RA` → null.
   - A **Tomorrow.io row is not a station.** A `TOMORROW_IO_5M_HISTORY` "Rain" row at 0 km with a
     non-Tomorrow source displayed → null.
   - Tomorrow.io "Rain" with Tomorrow.io displayed → RAINING. Older than 30 min → null.
   - Radar:
     - `86,149,193,255` → SPRINKLING, `14,214,20,255` → RAINING, `230,0,0,255` → HEAVY.
     - Alpha 0 → null. A sample older than 25 min → null; 24 min still counts.
   - Combining: station RAINING + radar HEAVY → HEAVY.
   - Tonight's replay as a fixture (table above).
2. **`:shared` `RadarNowSamplerTest`** (Ktor `MockEngine`):
   - The request has the `GetFeatureInfo` shape: layer, bbox, i/j, JSON.
   - The measured JSON parses.
   - A RainViewer-region site makes no request.
   - A failure keeps the previous sample and logs `RADAR_NOW_FAIL`.
3. **`:shared` `CurrentConditionsRun` test:** a run stores one radar sample through the Host.
4. **`:shared` `HeaderContentTest`:**
   - With `rainNow = RAINING`, every view's `iconName` is `IC_RAIN_NOW`, even when the forecast row
     says clear.
   - With null, it is unchanged.
5. **Android Robolectric integration test** (WidgetRenderer → DailyHeaderResolver):
   - Stored KNUQ `-RA` rows give header `iconRes` `R.drawable.ic_weather_rain_now`.
   - A stored green radar sample with dry stations gives the same.
   - Neither gives the forecast drawable.
6. **Android `WeatherIconMapperTest`:** maps the three new names.
7. **Desktop:**
   - `desktopHeaderContent` with a `-RA` reading gives `IC_RAIN_NOW`.
   - A resource test loads all three drawables through `WeatherIcon.painterForIconName`.

## Verification

- `./scripts/unit-tests.sh`.
- `./gradlew installDebug` on every connected device; rebuild and restart the desktop app.
- While it is still raining (KNUQ `-RA` at 20:55), screenshot the header on the Fold, the Pixel and
  the desktop.
- Confirm the `RAIN_NOW` and `RADAR_NOW` log lines.
- After the rain ends, confirm the forecast icon returns. If the rain outlasts the session, I'll
  report that as unverified.

## Docs

- `arch/ARCHITECTURE.md`, header section: rain-now dots and their three inputs.
- `arch/hourly-forecast-update-cadence.md`: the radar pixel fetched per current-conditions run.

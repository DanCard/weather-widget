# Fix: Silurian (and Tomorrow.io / OWM) forecast history not displaying

## Context
The Forecast History screen (📈 evolution graph) shows "no data" for Silurian even
though the database holds rich history. Verified against the desktop DB
(`~/.local/share/weather-widget/weather.db`): **1,081 SILURIAN forecast rows across 32
target dates and 18 distinct forecast-made days, with proper per-`fetchedAt` evolution.**
So snapshots are being saved correctly — this is purely a **display/filtering bug**.

Root cause: the evolution graph routes each snapshot into one of two render series via a
hardcoded *allow-list* of "meteo-like" sources (`VISUAL_CROSSING + OPEN_METEO + WEATHER_API`).
Any source not on that list — Silurian, Tomorrow.io, OpenWeatherMap — is dropped from both
series, so the graph renders empty. Because the view only ever shows **one selected source at
a time** (snapshots are pre-filtered to the chosen API; sources are never overlaid), the correct
rule is the *deny-list* "everything that isn't NWS", which also auto-includes any future source.

Out of scope (per user): no color changes, no changes to the graphing mechanism, and
GENERIC_GAP/climate-normal data stays a daily-forecast-view concept — it must NOT appear in the
history graph (it already doesn't, since snapshots are filtered to the requested API source).

## Changes

### 1. Android — `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt` (`displayData`, ~lines 359-433)
- Replace the hardcoded source buckets (`visualCrossingPoints`, `meteoPoints`,
  `weatherApiPoints`, `meteoLikePoints`, `gapPoints`) with a deny-list split of the
  already-single-source `evolutionPoints`:
  - `nwsPoints = evolutionPoints.filter { it.source == WeatherSource.NWS }`
  - non-NWS series = `evolutionPoints.filterNot { it.source == WeatherSource.NWS || it.source == WeatherSource.GENERIC_GAP }`
  (renderer signature stays `(nwsPoints, meteoPoints)` — keep the existing two-color mechanism).
- Fix the snapshot **summary count** (lines ~366-400): the `pointsBySource` map has no key for
  Silurian/Tomorrow.io/OWM, so `summaryCount` currently resolves to 0 for them. Since the view is
  single-source, just use the count of `evolutionPoints` for the requested source.
- Fix **legend visibility** `when (requestedSource)` (lines ~418-433): the `else -> show both`
  branch is wrong for the never-null single-source flow. Map `NWS -> NWS legend only`, every other
  source -> the non-NWS legend only.
- Fix the **legend label**: the non-NWS legend's `TextView` is hardcoded `"OM"`. Give it an id in
  the layout and set its text to `requestedSource.shortDisplayName` so a Silurian graph reads
  "Silur", Tomorrow.io reads "Tmrw", etc.

### 2. Android layout — `app/src/main/res/layout/activity_forecast_history.xml`
- Add an `android:id` (e.g. `legend_meteo_text`) to the `TextView` inside `legend_meteo_group`
  (currently `android:text="OM"`) so the label can be set dynamically.

### 3. Android renderer — `app/src/main/java/com/weatherwidget/widget/ForecastEvolutionRenderer.kt` (`renderErrorGraph`, lines 184-185)
- Error-mode draws two curves filtered to `NWS` and `OPEN_METEO`. The `OPEN_METEO` filter drops
  Silurian even when it reached the meteo bucket. Change it to the deny-list
  `errorSamples.filterNot { it.source == WeatherSource.NWS }` so the selected non-NWS source's
  error curve renders. (Evolution-mode `renderGraph` needs no renderer change — it draws whatever
  is in `meteoSeries`.)

### 4. Desktop — `desktop/src/main/kotlin/com/weatherwidget/desktop/ForecastHistoryWindow.kt`
- `loadHistory` (line 513): `meteoPoints = points.filter { it.source.id in METEO_LIKE_IDS }`
  → `points.filterNot { it.source == WeatherSource.NWS }`. (`points` is already filtered to the
  selected `source.id` at line 500, so this is just "non-NWS of the selected source".)
- Remove the now-unused `METEO_LIKE_IDS` constant (lines 54-58).
- No legend change needed: desktop `Legend` already uses `source.shortDisplayName` and picks the
  color by `NWS vs else`. `drawError`/`drawEvolution` already draw both buckets with no hardcoded
  source filter — they work once `meteoPoints` includes Silurian.

## Reuse / things already correct
- `getForecastEvolution` (`ForecastDao.kt:306`, and `DesktopWeatherDao.getForecastEvolution`)
  already returns all rows for the target date; no query change.
- Snapshot saving for Silurian is already correct (`ForecastRepository.saveForecastSnapshot`,
  invoked at line 411) — do not touch.
- `WeatherSource.shortDisplayName` already provides per-source legend labels.

## Tests
- `app/src/test/java/com/weatherwidget/widget/ForecastEvolutionRendererTest.kt`: add a regression
  test that passes a **SILURIAN**-source point as `meteoPoints` and asserts a non-blank render, and
  an **error-mode** test with a non-`OPEN_METEO`/non-`NWS` source (Silurian) that previously drew
  nothing. Keep existing signatures.
- Optional: extract the deny-list bucketing into a tiny pure helper so it can be unit-tested
  without Robolectric (mirrors the project's "pure function extraction for testability" pattern).
- Run: `./gradlew :app:testDebugUnitTest --tests "*ForecastEvolutionRenderer*"` and
  `./gradlew :shared:test :desktop:test`.

## Verification (end-to-end)
1. **Desktop**: rebuild + restart via `scripts/buildStart.sh`. Open Forecast History (📈 icon),
   cycle the API source button to **Silur**, navigate to a target date known to have history
   (pick one via the DB query below). Confirm the evolution curve + data points render and the
   legend reads "Silur". Repeat for Tomorrow.io if enabled.
   - DB sanity query (read-only):
     `sqlite3 ~/.local/share/weather-widget/weather.db "SELECT source, COUNT(*) FROM forecasts GROUP BY source;"`
2. **Android emulator**: `./gradlew installDebug`, open the widget → tap a day → Forecast History,
   cycle source to Silurian, screenshot (convert PNG→JPG before reading, per CLAUDE.md). Confirm the
   curve renders in evolution mode AND error mode (toggle the mode button on a past date).
3. Confirm GENERIC_GAP does **not** appear in the history graph for any source.

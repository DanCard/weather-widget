# Plan: morning fog on the daily bars

User request (2026-11-11, a foggy morning): "I'd like to add fog to the daily forecast view. Since
fog only happens in the morning can we make the early part of the bars foggy?"

Design choices (user, AskUserQuestion 2026-11-11):
1. **Height:** fog runs from the bar's low up to the temperature fog burns off at.
2. **Fog + rain:** fog is a translucent wash *over* the bottom of the bar; the blue rain share keeps its
   size and shows through.
3. **Days:** today and future from each source's forecast; **past days from observed fog** (METAR).

## Evidence

### Fog is a morning event in our data
On the Samsung backup `backups/20261111_061920_sm-f936u1_RFCT70G352Y`, 543 of 610 stored fog/mist
`hourly_forecasts` rows fall between 00:00 and 10:59. Most of the rest are Tomorrow.io all-day "Fog" runs
(2026-11-03, -11, -12). So a window from midnight to noon captures it; evening fog belongs to the next
morning's bar.

### Sources that already name fog hourly (no new API fields)
| Source | Stored `condition` |
|---|---|
| NWS | `Patchy Fog`, `Areas Of Fog` (also `… then Patchy Fog` on daily rows) |
| Open-Meteo | `Light Fog` (code 45), `Dense Fog` (48) |
| Tomorrow.io | `Fog` (2000, 2100) |
| WeatherAPI | `Mist` |
| Google | `Fog` (the `"FOG" in type` branch in `WeatherCodeMapper`). **That branch also maps `HAZE` → "Fog"**: haze is smoke or dust, not fog |
| OWM | `main` = `Mist` / `Fog` / `Haze`; none stored so far |
| Silurian | none seen |

This morning (Pixel `backups/20261111_061920_pixel_7_pro_2A191FDH300PPW`): Open-Meteo forecast Light Fog
02–09 and Tomorrow.io forecast Fog 02–09. The other five sources had no fog.

### Observed fog is already stored, but not decoded
`observations.rawMetar` keeps the full report, for example (live, 2026-11-11):
```
KNUQ 111315Z AUTO 00000KT 3SM BR SCT002 BKN007 11/10 A3008 RMK AO2
KSFO 111410Z 21005KT 1/4SM R28R/2000V2400FT FG VV002 13/12 A3008 RMK AO2
```
`MetarDecoder` reads temperature, dewpoint, sky and remarks, but not the visibility group or the
present-weather `BR`/`FG`. So observed fog needs only parsing, with no observation schema change. Raw
observations are kept about 13 days, so the observed result must be frozen into `daily_history`.

### The bar is a temperature axis
`AdaptiveBarStrokes.plan` strokes one round-capped line from highY (top) to lowY (bottom). Morning is
the cold end, so "the early part" is the bottom of the bar, where the rain share sits today.

## Design

### Fog level (one shared rule, matching the existing fog icons)
New `:shared` `FogLevel` with `NONE`, `LIGHT`, `FOG`, `DENSE`, read from:
- **Forecast condition text:** `Dense Fog` → DENSE. `Light Fog`, `Patchy Fog`, `Mist` → LIGHT. Other
  `fog` (`Fog`, `Areas Of Fog`, `Freezing Fog`) → FOG. `Haze` / `Smoke` → NONE. This uses the same
  degree words as `plans/260408-degree-of-fog-icons.md`.
- **METAR, two rules (user, 2026-11-11: "I like adding both rules"):** a report is foggy when either
  1. **the station flags it**: `FG` or `BR` at **any** visibility (`MIFG`/`BCFG`/`PRFG` included), or
  2. **the air is saturated, with evidence of condensation**: temperature = dewpoint at the station's
     precision (tenths from the `T` group when present, else whole °C, so `10/10` counts), wind ≤ 5 kt,
     inside the morning window, **and** visibility under 10 SM or a cloud layer at or below 500 ft.
     Refinement (side-agent note, user 2026-11-11 "maybe drop fog indicator very low … like 5%"):
     without the evidence, KNUQ `00000KT 10SM CLR 13/13` at 00:15 and `10SM FEW038 12/12` at 01:15
     counted as fog on a clear night. With it, they do not, while 06:15 `7SM FEW002 10/10` still does.
     A report that is foggy **only** by this rule draws at **5 %** (see Drawing).
  `HZ`, `FU` and `VV` on their own are not fog. Level (for logs and tests) follows visibility:
  < ¼ SM DENSE, ≤ 1 SM FOG, else LIGHT.

### Fog for a day
For a date, the **morning window is 00:00–12:00 local**:
- `fogLevel`: the strongest level among the window's hours. `NONE` (0) is a real value meaning "covered
  and no fog". Null means the window is not covered or there is no data.
- `fogVisibilityMi`: the **lowest visibility** among the fog hours, in statute miles. This drives the
  opacity (see Drawing). Observed: the METAR visibility, or 6 SM for a saturation-only report. Forecast:
  the provider's hourly visibility where the API returns one (Open-Meteo `visibility`, Tomorrow.io
  `visibility`, Google `visibility`, NWS grid `visibility`, OWM `visibility`, WeatherAPI `vis_miles`),
  else a stand-in from the condition word: Dense Fog ⅛, Fog / Areas Of Fog ½, Light/Patchy Fog and Mist
  3. Stored as a third member of the pair: it moves with `fogLevel`/`fogTopTemp`, never alone.
- `fogTopTemp` (°F): the **burn-off temperature**, meaning the forecast temperature at the first non-fog
  hour after the morning's last fog hour. If fog lasts to the window's end (12:00), it is the temperature
  at 12:00. Null when the level is NONE. It is *not* the warmest fog hour: fog holds the temperature flat.
  On Open-Meteo 2026-11-11 (3:12 fetch), fog 02–09 was 52.9–54.4°, then 10:00 clear at 56.9°. The warmest
  fog hour would draw 9 % of a 52.8–70.9° bar; the burn-off temperature draws 23 %.

  **Height at draw time:** `fraction = (fogTopTemp − low) / (high − low)`, using the bar's own low/high and
  `y(temp)` mapping (both °F, so Celsius converts identically). It is clamped to [circle, full bar]: a
  provider whose daily low/high come from a different period than its hours can put the top outside the
  bar.

NONE (0) is a real value on both tables: on `forecasts` it means "covered and no fog" (see the carry-forward
trap below), and on `daily_history` it means "the stations were checked and there was no fog". Null means
not computed.

### Forecast side (today and future): stored on the `forecasts` row
User, 2026-11-11: "I think it should go there" (the daily forecast table). It is computed from **hourly**
rows at persist time, never from the daily `condition` words. NWS's "Partly Cloudy then Patchy Fog"
(2026-11-07/-08/-09) means fog that *evening*, which is really the next morning's, so the words cannot
place the fog in the morning.
1. Extend `DailyHourlySummaries.Summary` with `fogLevel` and `fogTopTemp`. They are computed in `forDate`
   behind `windowCovered(rows, 00:00, 12:00)` and carried forward **as a pair**, like the day max and its
   peak hour.
2. Persist them on the `forecasts` row (`ForecastEntity`, `ForecastTypes`, desktop `forecasts`) through
   `ForecastPersistRun`, the same path as `noonCloudPercent` and `hourlyDayPrecipMax`.
3. The explicit NONE (0) is required. `carryForward` keeps the prior value when the incoming one is null,
   so a covered window without fog must write 0, or an older fetch's fog would stick. Test this.
4. Read order mirrors `liveDayNight`: today uses the stitched hours when they cover the window, else the
   row; future days use the row, else the stored hourly rows (rows written before the columns existed).
5. Per source only. There is no cross-source fallback: a source that never names fog draws no fog.

### Observed side: each source's own actuals provider (user, 2026-11-11: "Yes per-provider")
Observed fog follows `ActualsProviderResolver` exactly as the actual-temperature line does: NWS → NWS
station observations, Open-Meteo → its analysis, Tomorrow.io → its 5-minute history, WeatherAPI → its
history, OWM → its current conditions, Google/Silurian → the borrower default (NWS inside coverage, METAR
elsewhere), or whatever the user picked.
1. `MetarDecoder` gains `visibilityStatuteMiles` (`10SM`, `3SM`, `1/4SM`, `M1/4SM`, `1 1/2SM`),
   `presentWeather` tokens and `windSpeedKt` (`00000KT`, `VRB04KT`, `21005G15KT`).
2. New `:shared` `ObservedMorningFog`, over the provider's readings (`api` = the provider id) in the
   00:00–12:00 window:
   - Readings that cannot say anything about fog are skipped: QC-failed, the NWS blend row, and
     personal stations with no condition and no raw METAR (CWOP rows carry neither).
   - **Nearest station per hour** among the rest, as observed rain does.
   - A report with raw METAR text uses both station rules (flag, or saturation with wind ≤ 5 kt). Any
     other report (model analyses, NWS text-only rows) uses its condition word (`FogLevel`).
   - Level = from the lowest visibility in fog; top = the temperature of the first fog-free report after
     the last foggy one (else the latest report's, for fog still in progress or lasting past noon).
3. **Written by `DailyBlendRecomputeRun`**, which already reads each day's observations and writes each
   source's `daily_history` row. The merge takes the three observed-fog columns from the recompute
   whenever it computed them, and otherwise keeps the stored ones (never overwritten by null).
   `WRITE_RULES_VERSION` is bumped so every day still in reach (9 days) is recomputed once, which
   backfills. Today's row is recomputed as observations arrive, so today's "observed so far" comes from
   the same column.
4. Model providers' observation rows store no visibility number, so their level uses the word's stand-in.

### Hourly visibility: carried in memory, not stored per hour
`HourlyForecast.visibilityMi` is filled by each parser (Open-Meteo metres, Tomorrow.io `visibility` in
imperial miles, Google `visibility.distance` + unit, OWM metres, WeatherAPI `vis_miles`, NWS grid
`visibility` metres; Silurian has none). `ForecastPersistRun` computes the day's fog from the fetch's own
download, so the provider's visibility reaches `forecasts.fogVisibilityMi` without a new
`hourly_forecasts` column. Only the secondary path (stored hours, used when a download starts mid-window,
for example Google and OWM today) falls back to the word stand-ins. The `hourly_forecasts.visibilityMi`
column listed under Schema is dropped.

### Today on screen
Today = observed so far (today's `daily_history` row) combined with the forecast for the hours still ahead
in the window (the stitched hourly rows the graph draws, from the current hour to noon; the stored row if
there are none). The stronger level wins, the top is the higher, and the visibility is the lower.

### Drawing: a translucent fog ellipse over the bar (shared plan, platforms only paint it)
User, 2026-11-11: "I want the fog to look like fog … should be able to see info behind it. Wider than
current vertical bars." Then, after mock-ups (scratchpad `fog_options.png`, `fog_options_round.png`):
"A looks the best. Prefer rounder less rectangular." Then: "I like A2 the best" (ellipse); opacity
options 25/35/50/65 % rendered in scratchpad `fog_ellipse_opacity.png`. The user picked **25 %**.

1. The bar itself is **unchanged**: `AdaptiveBarStrokes` still draws gold/grey/blue. Fog is a separate
   layer, planned by a new `:shared` `FogPatch.plan(fog, lowY, topY, centerX, columnWidth, inkRadius)`.
2. **Shape:** an **ellipse** centred on the bar, about **85 % of the column width**, several times the
   bar's stroke. Vertically it spans from just below the low (past the round cap) to a little above
   y(topTemp), so the narrow tips do not cut the fog short.
3. **Translucency, dynamic (user, 2026-11-11: "more dynamic based on the rules"):** fog-white
   `WeatherColors.FOG` (≈ `#E6EBEF`), with alpha from `fogVisibilityMi` on a log scale:
   `alpha = 15 % + 20 % × (ln 6 − ln v) / (ln 6 − ln ⅛)`, v clamped to [⅛, 6] SM.
   | v | 6 SM / saturated | 3 SM mist | 1 SM | ½ SM | ¼ SM (KSFO 11-11) | ⅛ SM |
   |---|---|---|---|---|---|---|
   | alpha | 15 % | 19 % | 24 % | 28 % | 31 % | 35 % |
   Ordinary fog (about 1 SM) lands on the user's preferred 25 %. A day whose observed fog rests only on
   the saturation rule (no `BR`/`FG` report) draws at **5 %**, a hint, whatever its visibility. Mock-up: scratchpad
   `fog_opacity_scale.png`. The log scale matches how fog is felt: ¼ → ½ mile is a big change, 5 → 6 is
   not.
4. **Soft edge:** about 3 stacked ellipses, each inset 1–2 dp and translucent enough that their sum is the
   target alpha at the centre and fades toward the rim. There are no gradient shaders, because Samsung
   launchers flattened them (6deaeece). Verify on the Samsung.
5. **Paint order: bars → fog ellipse → text labels.** The fog lies over the bar at 50 %, so the bar is
   still seen through it, and the high/low and rain labels stay crisp on top.
6. Light theme / light wallpaper: white-on-light is weak, so it may use a cool grey (`#B8C2CC`). Check in
   the screenshot.
7. Height: see `fogTopTemp` above; at least the ellipse's width (a circle), at most the whole bar.
8. Applies to the main bar's column; the ellipse is wide enough to also lie over a triple bar's cold ends.
9. Android `DailyBarRenderer` (Canvas `drawOval` with a translucent `Paint`) and desktop
   `DailyForecastGraph` (Compose `drawOval` with alpha) only paint the shared ellipses. Android
   `DailyViewLogic` and desktop `DailyColumnBuilder` take the fog from one shared
   `DailyMorningFog.forColumn(isPast, isToday, observedFrozen, observations, stitchedHours)`.

### Today's column: observed so far + forecast ahead (user, 2026-11-11)
User: "NWS station observation does say fog mist? Should we put that in for the today column?" Evidence:
KNUQ 05:15 `3SM BR` was stored with NWS textDescription "Fog/Mist". The 05:35 `6SM BR` row had a blank
description, so the level comes from the decoded raw METAR, not NWS's text.

Today's fog = the stronger level of (a) `ObservedMorningFog` over the window's hours so far and (b) the
source's forecast fog for the remaining hours. The top is the higher of the two burn-off temperatures (observed so far vs forecast ahead). On every source, so NWS
(whose forecast had no fog) shows this morning's fog: KNUQ `BR` from 05:15 and `10/10` calm since, so the ellipse grows while it lasts (3 SM → about 19 %). This is a measurement
shared by all sources, like the observed temperature line, not a cross-source forecast fallback. At
midnight the day becomes past and shows observed only.

10. Logging: `Log.v` `DAILY_FOG date= src= level= top= low= high= from=row|stitched|observed`, logcat only.
   A `Log.d` once per `daily_history` write of observed fog.

### Side fix
`WeatherCodeMapper` (Google): map `HAZE` to "Haze", not "Fog", so smoke days do not draw fog. The icon
mapper already treats haze separately.

## Schema
- Room: next version, with a migration adding `forecasts.fogLevel INTEGER`, `forecasts.fogTopTemp REAL`,
  `forecasts.fogVisibilityMi REAL`, `daily_history.observedFogLevel INTEGER`,
  `daily_history.observedFogTopTemp REAL` and `daily_history.observedFogVisibilityMi REAL`, plus the
  exported schema. `daily_history` is needed because the daily view pans further back than the ~13 days raw
  observations are kept (`arch/data-pruning.md`).
- Desktop SQLite: the matching `DesktopWeatherDatabase` migration.
- Only `ADD COLUMN`, so it is safe on `minSdk` SQLite (`MigrationSqliteCompatGuardTest`).

## Tests (written to fail on today's code)
1. `MetarDecoderTest`: visibility and present weather for the KNUQ, KSFO and KHAF strings above,
   `M1/4SM`, `1 1/2SM`, `P6SM`, and no-visibility AUTO reports.
2. `FogLevelTest`: every stored condition string in the evidence table, including `Haze` → NONE and
   `Patchy Fog then Sunny`.
3. `DailyHourlySummariesTest`: window coverage (uncovered → null), covered without fog → NONE, **a later
   fetch with no fog clears an earlier fetch's fog** (the carry-forward trap), level and top carried as a
   pair, top = the temperature at the first clear hour (Open-Meteo 2026-11-11: 56.9°, not 54.4°), fog lasting past noon → the 12:00 temperature, and another source's fog never leaks
   in. Also a persist round-trip on both platforms (`ForecastPersistRun`).
4. `ObservedMorningFogTest`: nearest station per hour (KNUQ BR 3SM beats a farther KSFO FG; KSFO used
   for an hour KNUQ missed); `6SM BR` → foggy (any-visibility rule); `10/10` calm with 7 SM and no `BR`
   → foggy (saturation rule, whole °C); `11/09` → not; `T01060089` (10.6/8.9) → not; `10/10` with 12 kt
   → not; saturation in the afternoon → not; Nov 9 KNUQ shape → burn-off at the first report that passes
   neither rule.
5. `FogOpacityTest`: the table above, the clamps, and monotonic in visibility. Provider parsers: hourly
   visibility read per API (unit conversion: metres, km and miles).
6. `FogPatchTest`: no fog gives no ellipses; the outer ellipse is about 85 % of the column and wider
   than the bar's ink; the ellipses nest; the summed centre alpha equals `FogOpacity(v)`; it covers
   lowY plus the cap through y(topTemp); the minimum height is a circle. `AdaptiveBarStrokesTest` is unchanged (the bar ignores fog).
7. Renderer test (Robolectric, dp geometry): the fog ellipse paints after the bars and before the
   labels, and it is wider than the bar's ink.
   `DailyMorningFogTest`: today combines observed-so-far with the forecast ahead (NWS 2026-11-11: no
   forecast fog + KNUQ `3SM BR` 05:15, `6SM BR` 05:35–06:35 and `10/10` calm → foggy through the latest report, top = latest temperature); past days are observed only; future days are forecast
   only.
8. Migration tests: Room (instrumented, real SQLite) and desktop (JVM, real SQLite).
9. `DailyColumnBuilder` / `DailyViewLogic` parity: the same inputs produce the same fog on both
   platforms (existing cross-platform test style).

## Verification
1. `./scripts/unit-tests.sh`, then `./scripts/emulator-tests.sh -c` for the migration and renderer
   tests.
2. Install on the emulator and phones, switch to Open-Meteo and Tomorrow.io, and screenshot today's bar
   (forecast fog 02–09) and a past foggy day such as 2026-11-09/-10 (observed). Past days show the same
   observed fog on every source. On today's bar, NWS shows the observed mist only: its 00:12, 02:32 and
   05:22 hourly forecasts had no fog.
3. Desktop: restart the distributable and compare the same days.
4. Check `DAILY_FOG` logcat against the database rows.

## Docs
- Update `arch/daily-history-extremes.md` (new frozen observed-fog columns), and `ARCHITECTURE.md` where
  it describes the daily bar sky.

## Open questions (defaults chosen; say if you want otherwise)
- **No settings toggle.** Fog is on for everyone. A toggle would need `activity_settings.xml` and
  `SettingsWindow.kt`.
- No "clears 10a" text label. That is easy to add later from the same summary if wanted.

## Changes made during implementation (2026-11-11)
1. **Fog by visibility on METARs:** a report at or below ⅝ SM with no precipitation counts as fog even
   without `FG`. KPAO 06:47 `1/16SM VV001 12/12` wrote no `FG`.
2. **Today's observed fog is computed live** in `DailyActualsAssembler`, like today's blend. The
   assembler never reads today from storage, so the first desktop run showed no fog on today's NWS
   column although `daily_history` had it.
3. **Bigger minimum patch** (user: "B: Bigger minimum circle"): a just-started fog is a circle 1.5× the
   ellipse width, narrowing smoothly to the normal width as the fog grows. Opacity is unchanged
   (15–35 %, hint 5 %).
4. **Six ellipse layers** instead of three: three showed visible rings on desktop.
5. Fog words are matched with `.contains()` (the localization guard treats `in` literals as UI prose).

Verified: unit tests (6,730), `DesktopFogStorageTest` (3), Room `WeatherDatabaseMigrationTest` on the
emulator (35), desktop screenshots (today and 9 Nov on NWS), and Pixel `DAILY_FOG_DRAWN` logs plus a
screenshot.
6. **Look, revised** (user: "Raise fog max from 35% to 50%. The fog should be wider. O.k. to spill over
   to adjacent columns … more of a continuous spectrum"): the maximum alpha is 50 % at ⅛ SM; the width is
   130 % of the column; the minimum patch is a circle 1.25× that width; there are 16 layers with 8-bit
   alphas that track the running total, so the fade is smooth and the centre lands on the target; a
   saturation-only hint runs continuously from 5 % at 10 SM to 15 % at 6 SM, meeting the fog scale.
7. **Hourly visibility is stored after all** (`hourly_forecasts.visibilityMi`, Room MIGRATION_86_87, desktop
   v39). User: "I'm not seeing dense fog on today column". Desktop trace: today `level=2 vis=3.0
   from=observed+stitched` while tomorrow `level=4 vis=0.0 from=row`. Today's fog still ahead reads the
   stored hours, which had lost Open-Meteo's 0-mile visibility and fell back to the "Light Fog" 3-mile
   stand-in. The in-memory-only choice above is reversed.
8. Max alpha raised to 65 % (user, 2026-11-11).
9. **Burn-off top = midway** between the last foggy reading and the first clear one (both sides), and a
   saturation hint no longer prolongs a morning that had real fog. User: "tomorrow.io, monday … Has fog
   reaching up past mid day. Fog only lasted till around 10 am." KNUQ 2026-11-09: last mist 09:55
   (57.2°), hint at 10:15 (`8SM FEW004 15/15`, 59.0°), clear at 10:35 (62.6°). The top was 62.6°; it is
   now 58.1°. `WRITE_RULES_VERSION` 4 recomputes stored days.

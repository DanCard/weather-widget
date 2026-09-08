# Codebase Quality Audit — 2026-09-08

Scope: main sources only (`app/src/main` 52k lines / 244 files, `shared/src/main` 26k / 176,
`desktop/src/main` 17k / 53). All findings below were verified by reading the cited code or by
grep reference counts — nothing is inferred from file size alone.

Already done (do not re-do): the `260907-simplification-pass` plan landed as `78ea95e4` (NwsApi),
`92910766` (DesktopWeatherRepository), `e30d7e3a` (TemperatureStateResolver).

---

## A. Duplicate code

### A1. Rain-amount label placement — forked line-for-line between platforms (~75 lines x2) — HIGH
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt:456-534` (`calculateRainAmountPlacements`)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/PrecipitationGraph.kt:389-466`

Same order of operations: candidates from `period.anchorX ?: xFractions`, `coerceIn(textWidth/2, width - textWidth/2)`, `cy = graphTop + graphHeight*yFrac`, reject outside graph, inflate by pad, collision filter, sum intersection area, keep min-overlap, accumulate padded bounds.

The fork has **already drifted**:
- app reads `HourlyGraphDefaults.OVERLAY_X_FRACTIONS` / `OVERLAY_Y_FRACTIONS`; desktop hardcodes `listOf(0.18f,0.35f,0.50f,0.65f,0.82f)` / `listOf(0.25f,0.45f,0.65f,0.82f)` (`PrecipitationGraph.kt:404-405`)
- app measures ascent/descent; desktop uses `textHeight/2`
- app pads `RAIN_AMOUNT_PADDING_DP`; desktop hardcodes `4f`

Fix: extract a pure `RainAmountLabelPlacer` into `shared/.../shared/graph/` taking
`(textWidth, ascent, descent)` measurement lambdas. `shared` already has `GraphRect`,
`GraphEmptySpaceFinder`, `CollisionTester` to build on.

### A2. Cloud-layer glyph plan duplicated (~110 lines x2) — HIGH
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt:334-352, 354-373, 376-424`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt:252-265, 266-280, 281-330`

`shared/.../graph/CloudLayerGlyphPlacer` already owns the *placement* algorithm. What is
duplicated is the *plan*: `highLowerBands = max(lows[i], midCovers[i])`, `noLowerBand`,
forecast/actual totals, then the same five `place(...)` calls with the same
`MID_PHASE`/`HIGH_PHASE`/`LOW_ACTUAL_PHASE`/`MID_ACTUAL_PHASE`/`HIGH_ACTUAL_PHASE`, same `±nudgePx`
signs, same `minCover = 0, suppressMatchingTotal = true` triple.

Fix: `CloudLayerGlyphPlan.build(midCovers, highCovers, actualLow/Mid/High, totals, xAt, yAt)` in
`shared` — pure, returns both glyph lists.

### A3. Daily-history rain-chance snapshot + freeze forked (~90 + ~35 lines x2) — HIGH
- `app/src/main/java/com/weatherwidget/data/repository/DailyHistorySnapshotter.kt:134-222` and `:371-430`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt:949-1053` and `:1068-1093`

Both do: yesterday+today, `dayWindowOpen` 20:00 / `nightWindowOpen` next-day 08:00, early exit when
neither opens, `DailyHistoryFreeze.overlayWindowOpen`/`noonCloudWindowOpen`,
`DailyRainLabels.resolveLiveDayNightChanceAtSite`, `DailyHistoryFreeze.merge`, then
`copy(forecastDayPrecipChance=…, …)`.

**Live behavioral divergence** (this is a bug, not just duplication):
- app overlay-row gate: `!isClimateNormal && source != GENERIC_GAP && highTemp != null && lowTemp != null` (`DailyHistorySnapshotter.kt:190-195`)
- desktop gate: `!isClimateNormal && highTemp != it.lowTemp` (`DesktopWeatherRepository.kt:1001`)
- app uses `resolveMeasuredNoonCloudCoverPercentAtSite`; desktop uses the non-`AtSite` variant.

Fix: shared `DailyHistoryFreezePlanner` with a small row adapter (`DailyHistoryEntity` vs `DailyHistory`).

### A4. `formatBias` — 3 copies that already disagree — MEDIUM
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/ForecastHistoryViewLogic.kt:54-64`
- `app/src/main/java/com/weatherwidget/ui/StatisticsActivity.kt:205-216`
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt:636-646`

All three open with the same 4 lines (`displayBias = if (useCelsius) bias / 1.8 else bias`,
`absBias`, `threshold = if (useCelsius) 0.5/1.8 else 0.5`, `when { absBias < threshold -> "" … }`).
Then they diverge: the shared copy hardcodes English `" (%.1f° low)"` with no string resources;
`ForecastHistoryActivity` always renders 1 decimal; `StatisticsActivity:208` renders °F as
`${absBias.toInt()}°` and °C as `%.1f°`. Same quantity, two different renderings on two screens.

Fix: keep the `:shared` copy, give it a format-string or `(biasText) -> String` parameter, delete
both app copies.

### A5. 50 inline `if (useCelsius)` conversions in main sources — MEDIUM
`TempUtils.formatTemp` (`shared/src/main/kotlin/com/weatherwidget/shared/util/TempUtils.kt:19`)
contains exactly this expression, yet it is re-inlined ~50 times across all three modules
(sampled: `app/.../ui/DailyAccuracyAdapter.kt:65-68`, `app/.../widget/handlers/TemperatureTextMode.kt:65`,
`desktop/.../StatisticsWindow.kt:157-160`, `desktop/.../TemperatureTrayPainter.kt:93`,
`shared/.../graph/ForecastEvolutionGeometry.kt:126`).
A delta variant `if (useCelsius) x / 1.8f else x` adds ~8 more
(`app/.../ui/StatisticsActivity.kt:173,174,206,208`, `app/.../ui/ForecastHistoryActivity.kt:598,599,637,639`).
`celsiusToFahrenheit` is also hand-written twice inside `:shared`:
`MetarObservationMapper.kt:106`, `NwsObservationMapper.kt:115`.

Drift already exists: `DailyAccuracyAdapter:65-68` and `StatisticsWindow:157-160` round
differently on the same table.

Fix: `TempUtils.display(v, useCelsius)` / `TempUtils.displayDelta(v, useCelsius)`. Mechanical,
but 50 call sites — do it as a sweep, not ad hoc.

### A6. Age/`formatAge` formatting — 6 divergent implementations — MEDIUM
- `shared/.../graph/FetchDotLabel.kt:17-28` (`formatAgeLabel`, canonical)
- `shared/.../observations/StaleObservationFallback.kt:55-65` (`"${minutes}min"`)
- `desktop/.../ForecastHistoryWindow.kt:587-598` (`"just now"`, `"${h}h ${m}m"`)
- `desktop/.../LocationResolver.kt:95-102` and `desktop/.../DesktopUiApplication.kt:779-786` — byte-identical (`"${h}h ${m}m old"`)
- `shared/.../actuals/BlendTableFormatter.kt:80` (`formatAgeMs`)

Four user-visible renderings of the same quantity. Fix: one `AgeFormatter` in `shared/util` with an
explicit style enum.

### A7. `dpToPx` / `Float.dp` — 14 declarations + 8 identical `Float.dp` in `:app` — LOW
`private fun Float.dp(density: Float) = this * density` appears identically in
`DailyGraphPaintCache.kt:225`, `DailyHighLabelPlanner.kt:68`, `DailyColumnRenderer.kt:213`,
`DailyForecastGraphRenderer.kt:468`, `DailyGraphLayoutResolver.kt:353`,
`DailyForecastHeaderRenderer.kt:623`, `TodayColumnOverlayRenderer.kt:338`, `DailyBarRenderer.kt:553`.
Separately, 4 independent `TypedValue.applyDimension` bodies (`TemperatureGraphStyle.kt:332`,
`HourlyGraphPaints.kt:22`, `GraphLayout.kt:62`, `handlers/HeaderWidthChecker.kt:497`) plus ~9
private delegates that chain into them
(`CloudCoverGraphRenderer.kt:920 → CloudCoverGraphStyle.kt:149 → HourlyGraphPaints.kt:22`).

Fix: one `internal object Dp { fun toPx(context, dp): Float }`. The delegate chain actively hides
the single real definition.

### A8. Ktor 2xx check + throw boilerplate — 5 near-identical blocks — LOW
`SilurianApi.kt:128-136`, `TomorrowIoApi.kt:82-90`, `TomorrowIoApi.kt:100-108`,
`TomorrowIoApi.kt:181-188`, `VisualCrossingApi.kt:44-52`. Each is 8-9 lines of
`if (status !in 200..299) { val body = runCatching { bodyAsText() }.getOrDefault(...); throw ApiAccessException(...) }`.
`OpenWeatherMapApi.kt:71-80` already extracted it as `checkResponseStatus` — follow that.
Fix: `suspend fun HttpResponse.require2xx(source, label)` in `shared/.../data/remote/`.

### A9. Small verbatim copies — trivial fixes
- Settings `labelFor(percent)`: `app/.../ui/SettingsActivity.kt:515-519` ≡ `desktop/.../SettingsWindow.kt:564-568`. Pure string logic, belongs in `:shared`.
- Overlay row `displayText()`: `app/.../widget/TodayColumnOverlayRenderer.kt:230-236` ≡ `desktop/.../DailyForecastGraph.kt:709-714`. `shared/.../graph/TodayColumnOverlayBlocks.kt:9-11` already models this and desktop already imports it.
- `forecastColor(flags)` ARGB→Compose wrapper: `desktop/.../CloudCoverGraph.kt:51`, `PrecipitationGraph.kt:46`, `TemperatureGraph.kt:65`.
- Resolver window literals: `DesktopWeatherRepository.kt:115-116, 174-175` hardcode `12L / 3L` instead of `CurrentTemperatureResolver.RESOLUTION_LOOKBACK_HOURS/RESOLUTION_LOOKAHEAD_HOURS` (`shared/.../widget/CurrentTemperatureResolver.kt:58-59`). Values match today; nothing enforces it.
- `formatCoord`: `desktop/.../BugReportMailto.kt:49` ≡ `desktop/.../SettingsWindow.kt:425`.
- `setupViews()` boilerplate in 7 activities — verify overlap before extracting; names may not imply identical bodies.

### A10. Already well-shared — no action needed (verified)
Icon/condition mapping, colors, zoom stage/rules, temperature interpolation & current-temp
resolution, unit conversion primitives, header precipitation, hour-data assembly, sun position,
rain analysis, accuracy stats — all single-sourced in `:shared` and consumed by both platforms.
`shared/.../data/local/desktop/DesktopWeatherDao.kt` (1563 lines) is **not** a fork of the Room
DAOs — it is a JDBC implementation against a different stack with near-zero method-name
intersection. Leave it alone.

---

## B. Over-large modules / god classes

### B1. `app/.../widget/CloudCoverGraphRenderer.kt` (939) — worst offender
`renderGraph` is **673 lines (144-817) with 21 parameters (144-186)**. Inside one function:
scale/geometry (213-245), series smoothing + scale union (247-303), glyph placement/drawing
(326-459), curve paths (461-503), hour labels (505-538), percent labels (540-569), day labels
(571-600), NOW indicator (602-613), watermark placement (615-668), delta-label filtering (671-712),
dominant-station label (714-795), error watermark (797-814).
Nesting to depth 10 at :761; duplicated `when (segment.part)` paint selection at :721-727 and :781-787.
**Split:** `CloudCoverGraphLayers`, `CloudCoverGraphLabels`, `CloudCoverGraphAnnotations`; collapse
the 21 params into a `CloudRenderRequest` data class. The `curveYsAt` interpolator (740-769) is
pure and belongs in `shared/graph`.

### B2. `app/.../widget/handlers/CloudCoverViewHandler.kt` (919)
`updateWidget` is **623 lines (116-739) with 22 parameters**. Mixes repaint gating (162-185),
RemoteViews header binding (195-401), tap wiring (381-403), **DB/DAO loading + prior-cloud history
fetch (438-500)**, hour-data build (407-600), bitmap render and push.
It was **forked from** `PrecipViewHandler.kt` and still shares ~152 lines with it: a 74-line header
block at 263-337 (`HeaderPrecipCalculator` / `HeaderWidthChecker` / `HeaderRemoteViewsBinder`),
a 40-line param block at :115, plus 21- and 17-line blocks at :578 / :239.
**Split:** `HourlyHeaderBinder` (shared with Precip), `CloudSeriesLoader`, `CloudWidgetPusher`.

### B3. `app/.../widget/WidgetRenderer.kt` (683)
`updateWidgetWithData` is **425 lines (260-684) with 16 params**, including a 48-line inline
diagnostic `run {}` block (348-395) with a nested local `fun ranges`, and a 113-line
`when (effectiveViewMode)` (526-638) that is pure argument plumbing to four handlers.
**Split:** `WidgetRenderLocationResolver`, `HourlyUnifyDiagnostic`, `ViewModeDispatcher`.

### B4. `app/.../widget/handlers/DailyViewHandler.kt` (909)
`updateWidget` is 439 lines (130-569), and the class also owns network backfill triggering
(`shouldProbeHistoryBackfill` :569, `maybeBackfillIncompleteHistory` :586-638,
`requestMissingDataRefresh` :638-664) and render logging (`logDailyRenderSummary` :687-722).
A view handler that triggers network work is a god object.
**Split:** `DailyHistoryBackfillCoordinator` (569-664), `DailyRenderLogger` (687-747).

### B5. `app/.../widget/handlers/DailyViewLogic.kt` (856)
`prepareGraphDayInputs` is **359 lines (407-766) with 20 params**; `prepareTextDays` is 236 lines
(120-356) with 18. Both are one `dayOffsets.forEachIndexed` with a 13-variable mutable scratch
block (476-489) and three branches (past 491-511 / today 512-563 / future 564+). Pure logic, no
Android — trivially testable once split.
**Split:** `DailyPastDayResolver` / `DailyTodayResolver` / `DailyFutureDayResolver`; replace the 20
params with a `GraphDayRequest`.

### B6. `app/.../widget/handlers/TemperatureStateResolver.kt` (1012)
`resolve` is 358 lines (104-461) with 19 params; `loadGraphHours` is 280 lines (642-922) with 19
params. Mixes API-warning resolution, DB observation loading, blending, header-state building
(538-614), current-temp phase (488-538), diagnostics (462). Nesting depth 8 at :352.
**Split:** `TemperatureGraphHoursLoader` (642-922), `TemperatureHeaderStateBuilder` (538-614).

### B7. `app/.../widget/DailyForecastHeaderRenderer.kt` (624)
**Four functions re-walk the same header cursor layout:** `drawHeader` (33-147),
`resolveHeaderDateBounds` (147-183), `resolveHeaderDateLayout` (183-237),
`resolveHeaderInkBottom` (439-567, containing a 109-line local `considerText` at :458), plus
`resolveLeftClusterRight` (:280) and `resolveApiLeftPx` (:309).
**Split:** compute one `HeaderCursorLayout`, consume it in all four. The `consider`/`considerText`
ink math is pure geometry — move to `shared/graph`; desktop currently duplicates it ad hoc.

### B8. `app/.../widget/PrecipitationGraphRenderer.kt` (817)
`calculateLayout` (171-445, 274 lines) computes geometry *and* runs `ValueLabelEngine` *and* does
rain-amount placement *and* icon bounds. `calculateRainAmountPlacements` (456-536) is a
hand-rolled second label engine alongside the shared one (see A1).
**Split:** `PrecipRainAmountPlacer`, `PrecipGraphGeometry`.

### B9. `app/.../widget/handlers/DailyGraphRenderer.kt` (635)
`render` is 352 lines (46-398), of which ~80 lines (71-148) is `TODAY_BAR_DEBUG` /
`TODAY_HIGH_PROVENANCE` diagnostic logging and ~85 more is provenance formatting
(560-630: `buildTodayHighProvenanceMessage`, `formatStationMaxes`, `formatObservationSpan`,
`formatTempValue`, `formatDistance`, `formatLocalTime`).
**Split:** `DailyTodayProvenanceReporter` — pure reporting, zero risk.

### B10. `app/.../ui/WeatherObservationsActivity.kt` (1182)
**Four classes in one file, 39 functions:** Activity (67-885), `WeatherObservationsSupport`
(886-988, 32-line `when` at :905), `ObservationAdapter` (989-1117), companion (1133+).
`loadObservations` is 128 lines (671-796) with nested local `suspend fun` and 5-level nesting at :750.
**Split:** move `WeatherObservationsSupport` and `ObservationAdapter` to their own files; extract
`ObservationLoadingPipeline`.

### B11. `app/.../widget/WidgetActionReceiver.kt`
A **121-line `when (intent.action)` starting at :39**. Each branch is small, so it is a router —
replace with a `Map<String, ActionHandler>`.

### Big but actually fine — do NOT split
- `data/local/WeatherDatabase.kt` (854) — lines 53-702 are ~26 sequential Room `Migration` objects. A registry, not a god object. (See C for its real risk.)
- `data/repository/CurrentTempRepository.kt` (654) — cohesive: one 26-line `when` dispatcher over eight thin per-source adapters. Correct shape.
- `widget/handlers/TemperatureTouchTargets.kt` (641) — 14 flat single-purpose `setup*` functions, no intra-file duplication.
- `widget/TemperatureGraphAnnotationRenderer.kt` (805) — six `place*` functions, each delegating to `shared/graph`. Long by volume, correct by structure.
- `ui/SettingsActivity.kt` (587), `ui/ConfigActivity.kt` (709) — sequential setup code, low fan-out.

I checked the claim that `shared/graph/TemperatureExtrema.kt` is unused by `:app`: it has no direct
`:app` main reference, but it is reached indirectly through `TemperatureLabelResolver`, which
`app/.../widget/TemperatureGraphStyle.kt` and `WeatherWidgetApp.kt` do use. Not a real gap.

---

## C. Dead / deprecated code

### C1. Visual Crossing is fully unreachable — ~440 lines deletable — HIGH
Confirmed structurally dead, not merely hidden: `WeatherSourceOrdering.ALL_CONFIGURABLE`
(`shared/.../shared/util/WeatherSourceOrdering.kt:28-35`) omits `VISUAL_CROSSING`, and every
selection path funnels through `sanitizeVisibleIds` (`:49-65`) — Android
(`app/.../widget/WeatherSourcePreferences.kt:137-152`) and desktop (`DesktopConfig.kt:305,328`)
both sanitize. So `VISUAL_CROSSING in sourcesToFetch` (`ForecastFetchCoordinator.kt:183`) can never
be true.
- `shared/.../data/remote/VisualCrossingApi.kt` — 107 lines, whole file
- `app/src/test/.../VisualCrossingApiTest.kt` — 165 lines, whole file
- 33 `VISUAL_CROSSING` references in main sources across 18 files: `ForecastFetchCoordinator.kt` (10),
  `AppModule.kt` (9, incl. provider at :338 and key at :343), `CurrentTempRepository.kt` (7),
  `ForecastRepository.kt` (3, ctor param), `WeatherSource.kt` (5), `DesktopWeatherService.kt` (3),
  plus one-liners in `StatisticsActivity:132,161`, `ForecastHistoryActivity:568,585`,
  `BugReportActivity:156`, `SettingsActivity:311`, `ApiSourceWarningHelper:208`,
  `AccuracyCalculator:47,56`, `DailyForecastIconResolver:196`, `ApiKeySignupUrls:40`,
  `WeatherSourceDescriptions:21`, `ObservationSourceMatcher:47`, `shared/.../stats/AccuracyModels.kt`.
- `AccuracyCalculator.kt:47` runs a full `calculateAccuracy(VISUAL_CROSSING, …)` DB pass for a
  source that can never have rows — wasted work on every stats computation.

### C2. Two contradictory "requires an API key" switches over the same enum — HIGH
Verified, they disagree **today** on `SILURIAN`:
- `shared/.../data/model/WeatherSource.kt:160-167` — `requiresApiKey`: `SILURIAN -> true`
- `shared/.../shared/util/ApiKeySignupUrls.kt:36-46` — `requiresUserKey`: `SILURIAN -> false`

Both are exhaustive `when`s over `WeatherSource` modelling the same concept; neither is derived
from the other. `SILURIAN` is in `DEFAULT_VISIBLE_IDS`, so a user can enable it on a fresh install
and the two answers differ.
Fix: move `signupUrl` / `description` / key-requirement onto the enum as constructor params (it
already carries `displayName`, `supportsHourly`, …) and delete `ApiKeySignupUrls.requiresUserKey`
and `WeatherSourceDescriptions`. Related: `fromDisplaySourceOrNull` (:174-186), `fromId` (:198-216)
and `getDatabaseSourceName` are three parallel string→enum maps that must be edited together;
`fromId` can be `entries.firstOrNull { it.id == id }`.

### C3. Confirmed-dead symbols (grep hit count = 1, declaration only)
- `app/src/main/java/com/weatherwidget/util/NwsCoverageCache.kt:9` — 132-line `object`, zero references anywhere in `app/src`, `shared/src`, `desktop/src`.
- `app/.../util/DailyForecastIconResolver.kt:27` — `@Deprecated fun getMinimumPrecipProbability`, zero callers.
- `app/.../data/repository/WeatherRepository.kt:95` — `getWeatherRange`, zero callers.
- `app/.../util/DeviceUtils.kt:31` — `reportsStandardGps`, zero callers. (`isEmulator` at :11 is live — `BugReportActivity.kt:108`.)

### C4. Docs vs code mismatch — Tomorrow.io is not actually debug-only — MEDIUM
AGENTS.md says Tomorrow.io is "debug-only (tight free quota)". In fact
`AppModule.kt:390-398` always provides `TomorrowIoApi`, and it is in `ALL_CONFIGURABLE`
(`WeatherSourceOrdering.kt:30`), so it is selectable in release. Only the Android *debug default
list* gates it (`WidgetStateManager.kt:446-455`); desktop has no debug variant and uses
`DEFAULT_VISIBLE_IDS`, which excludes Tomorrow.io — a direct violation of the AGENTS.md
dual-platform parity rule. Either fix the code or fix the doc; right now both are wrong.

### C5. Clean bills of health
- `TODO|FIXME|XXX|HACK` in all main sources: **0**. Including tests: **0**.
- Commented-out code in main: 24 lines total, max 2 per file — negligible.
- `old/`, `backups/`, `crap/`, `tmp/`, `logs/`, `screenshots/`: 0 tracked files (all gitignored at `.gitignore:74-80`). Note `backups/` holds ~17 GB on disk — untracked bloat, not repo bloat.
- Write-only prefs: none found (scanned every `KEY_`/`PREF_` for writes without reads).
- Unused layouts: none — all 12 manifest activities and every `res/layout/*.xml` have ≥1 reference.

---

## D. Structural / complexity risks

### D1. Adding a weather source costs 7-9 file edits — HIGH maintenance tax
A new source must be touched in: `ForecastFetchCoordinator.kt` (2 places per source, see the 7
copy-pasted blocks at `:139-315` plus the hand-maintained `listOf(...)` at `:317-325`),
`AppModule.kt` (`:343,353,387,397`), `WeatherSource.fromId` (`:198-216`),
`WeatherSourceDescriptions.describe`, `ApiKeySignupUrls.signupUrl`, `SettingsActivity.sourceDescription`,
`desktop/.../DesktopWeatherService` switch, and probably `CurrentTempRepository`.
None of the `when`s are exhaustive-checked because they all have `else ->` branches
(`ApiKeySignupUrls.kt:31`, `SettingsActivity.kt:315`).
Fix: a `Map<WeatherSource, suspend (Double, Double) -> List<…>>` plus a per-entry
`enabled: (Double, Double) -> Boolean` predicate, driven by `entries` — removes ~150 lines **and**
makes the compiler catch missing cases.

### D2. `WeatherDatabase.kt` migration accumulation — MEDIUM
`version = 70` with **26 hand-written migrations** occupying ~600 of 854 lines
(`MIGRATION_44_45` at :53 … `MIGRATION_69_70` at :660). `exportSchema = true` (:17) so Room
auto-migration is available and unused — most are simple `ALTER TABLE … ADD COLUMN`.
Compounding: `.fallbackToDestructiveMigration(dropAllTables = true)` (:765) silently wipes on any
gap, and `healCorruptDatabaseVersion` (:809-839) hand-rolls schema repair by downgrading
`db.version` 46→45→44 while probing `PRAGMA table_info` — it must be kept in sync with all 26
migrations by hand. Also `MIGRATION_65_66` (:612) is declared *after* `MIGRATION_66_67` (:549).

### D3. Mutable global state coupling unrelated components — MEDIUM
- `app/.../widget/WidgetPushDispatcher.kt:55-64` — `object` with three process-lifetime concurrent collections plus a swappable `@Volatile elapsedRealtimeProvider`; every render path mutates it.
- `shared/.../shared/actuals/BlendSeriesCache.kt:61-70` — global MRU `ArrayDeque` + `synchronized(lock)`, shared across all windows and sources.
- `app/.../data/local/WeatherDatabase.kt:38-42` — three `@Volatile` statics with `setIsTesting` / `setDatabaseForTesting` / `resetInstanceForTesting` reachable from production code.
- Also: `ForecastFetchCoordinator.kt:111` (`lastPriorCloudFetchMs` on a `@Singleton`), `DailyActualsStore.kt:85` (signature cache), `WidgetStateManager.kt:434` (`prefsNameOverride` test seam in the production companion), `RefreshScheduler.kt:17,39`, `ActualsProviderResolver.kt:35`, `shared/.../util/Log.kt:60`.
- Test-only seams in production classes: `WeatherObservationsActivity.kt:74,80,88`, `BugReportActivity.kt:46`, `OpportunisticUpdateJobService.kt:38`.

### D4. Deep nesting hotspots — LOW
- `desktop/.../DesktopWidgetPopup.kt` — max depth 14 at :303, 434 lines deeper than 4 levels.
- `desktop/.../DaemonProcess.kt` — depth 11 at :751, 370 deep lines.
- `desktop/.../SettingsWindow.kt` — 340 deep lines.
- `desktop/.../DesktopWeatherDao.kt` — 558 lines at depth >4, max depth 7 at :61.
- `app/.../widget/CloudCoverGraphRenderer.kt` — depth 10 at :761.

### D5. Git history confirms where the pain is — context
6 of the last 15 commits are hot-path firefighting on the same two subsystems: click/observation
read latency (`e30d7e3a`, `837801e1`, `7b78a43e`, `3607c048`, `71b2e03c`, `49b5280d`, `696d03bf`)
and stale daily rain/fragment repair (`3452983c`, `f5e7aeec`). That is consistent with B2/B4/B6
(1000+ line handlers on the interaction path) plus D3 (global mutable caches on that same path)
being the real complexity centers — which is where refactor effort pays back fastest.

---

## Suggested phasing

Each phase is behavior-preserving, ends with `./gradlew test`, and pauses for approval before the
next. Highest value-to-risk first.

1. **Delete dead code** — C1 (Visual Crossing, ~440 lines), C3 (4 dead symbols, ~150 lines). Zero behavior change, biggest immediate line reduction, and removes the wasted `AccuracyCalculator` DB pass.
2. **Fix the source-metadata switches** — C2 (SILURIAN contradiction), C4 (decide Tomorrow.io parity), D1 (source registry replacing the 7 copy-pasted fetch blocks). Highest correctness value: one live bug and one maintenance tax.
3. **Shared helpers already bypassed** — A4, A5, A6, A9, A8. Mechanical deletions, ~60 call sites, no architectural risk.
4. **Cross-platform forks** — A1, A2, A3. A3 also fixes a live platform divergence. Needs care: these are the paths the recent firefighting commits touched.
5. **Split the god classes** — B9 and B11 first (zero-risk extractions), then B1/B2/B3/B5/B7, then B4/B6/B10.

Not recommended: splitting `WeatherDatabase.kt`, `CurrentTempRepository.kt`,
`TemperatureTouchTargets.kt`, or `TemperatureGraphAnnotationRenderer.kt` — verified cohesive.

# Session log — Duplicate-code detection & de-duplication

**Date:** 2026-06-15
**Branch:** main
**Theme:** "Is it easy to spot duplicate code and share, rather than duplicate?" → wire up a copy-paste
detector, then work through the de-duplication batches it surfaces.

---

## Overview

Started from the user's question about whether desktop/Android/shared duplication is easy to spot and
share. Confirmed the preconditions were already good (`:shared` is pure Kotlin/JVM, `:app → :shared ←
:desktop`, an established "delegate" pattern). Added an automated copy-paste detector, then executed
six de-duplication batches. Tracked progress via `./gradlew cpdCheck`.

**Duplicate-block count across the session: 99 → 80.**

**Duplicate-block count across the session: 99 → 84.**

---

## Batch 0 — Tooling: copy-paste detection (CPD)

- Added the `de.aaschmid.cpd` Gradle plugin (PMD CPD, Kotlin support) at the **root** build, applied
  **report-only** (`ignoreFailures = true`). Run with `./gradlew cpdCheck`; reports at
  `build/reports/cpd/cpdCheck.{xml,text}`.
- Config in `build.gradle.kts`: `language = "kotlin"`, `minimumTokenCount = 70`, scans
  `*/src/main/**.kt` for `:app`/`:desktop`/`:shared` (tests excluded).
- **Gotcha:** do NOT pin `toolVersion`. Pinning PMD 6.55.0 broke the worker with
  `net/sourceforge/pmd/cpd/CPDReportRenderer` ClassNotFound. Let the plugin pick its matching PMD 7.
- Version catalog: added `cpd = "3.5"` version + `cpd = { id = "de.aaschmid.cpd", ... }` plugin.
- **Baseline: 99 duplicate blocks.**

## Batch 1 — Shared pure-math extractions (`:shared`)

Cross-platform (Android + desktop both delegate). New files in `shared/src/main/.../shared/graph/`:

- `TemperatureColorModel.kt` — thresholds (50/70/90), COLOR_COLD/MILD/HOT as packed ARGB ints,
  `tempToColorArgb` + integer-RGB `blend`. **Closed a latent parity bug:** desktop had been blending
  via Compose `lerp()` (different color space); now pixel-identical to Android.
- `FetchDotLabel.kt` — `formatAgeLabel` ("17m"/"1h 5m"/null, ≤12h span gate).
- `CurveMath.kt` — `computeTangents` (monotone-aware Catmull-Rom) on `List<Pair<Float,Float>>`.
  Android `GraphRenderUtils` delegates directly; desktop `DesktopGraphUtils` maps `Offset`↔`Pair`.
  Path *building* stays per-platform (Canvas vs Compose Path).
- `formatTemp`: Android `TemperatureGraphStyle.formatTemp` + desktop fetch-dot inline now both
  delegate to existing `TemperatureLabelResolver.formatTemp` (NOT `TempUtils.formatTemp` — different
  contract: that one adds "°" and rounds differently).

**Discovery (extends "renderer test colors are zero"):** `TemperatureGraphStyleTest`'s 7 tempToColor
tests were silently vacuous — plain-JUnit stubs `android.graphics.Color.*`→0 on BOTH the impl and the
expected side, so `assertEquals(0, 0)` always passed. Moving color math to pure-Kotlin made the impl
return real values, exposing 7 failures. Fixed by repointing expected at `TemperatureColorModel`
constants/blend (real values, no android stub).

New shared tests: `TemperatureColorModelTest`, `FetchDotLabelTest`, `CurveMathTest`.

## Batch 2 — App graph-style paints (`:app`, within-platform)

`android.graphics.Paint` can't live in `:shared`, so this is within-`:app` dedup.

- New `app/.../widget/HourlyGraphPaints.kt` — 6 byte-identical Paint builders (currentTime, hourLabel,
  percentLabel, nowLabel, dayLabel, todayDayLabel) + gradientFill + dpToPx.
- `CloudCoverGraphStyle` and `PrecipitationGraphStyle` both delegate; each keeps its own caching
  strategy (cloud = plain field, precip = `@Volatile` double-checked lock), cache key
  (`tallGraph: Boolean` vs `heightDp: Float`), and unique paints (cloud curve; precip divider +
  rain-amount). Residual CPD hit is only the inherent `PaintSet(...)` field-assignment boilerplate.
- CPD 99 → 98.

## Batch 3 — Desktop CloudCoverGraph ↔ PrecipitationGraph composables (`:desktop`, within-platform)

Planned in plan mode (Explore agent mapped shared-vs-unique). Three seams:

1. **`DrawScope.drawDayLabels` → `DesktopGraphUtils.kt`** — was triplicated as a *private* function in
   all three graph files (identical except font size); extracted with a `dayLabelFontSp` param
   (cloud/precip 10f, temp 14f).
2. **`Modifier.hourlyGraphFooterTapInput` → `HourlyGraphInput.kt`** — `hourlyPanZoomInput` + the
   footer-tap handler, byte-identical for cloud + precip. (Temperature's tap toggles zoom — left
   alone.)
3. **`@Composable rememberHourlyGraphSetup` → `HourlyGraphInput.kt`** — returns a `HourlyGraphSetup`
   holder (now/dragHours/window/points/painters/smoothIterations); null when <2 points (caller
   `?: return`). Watermark + `rememberTextMeasurer()` stay at each call site.
   Plus combined `DrawScope.drawDayLabelsFooterAndNow` (cloud + precip).

**KEY judgment call — TemperatureGraph EXCLUDED from the combined tail:** temp interleaves its
fetch-dot rings BETWEEN the footer strip and the NOW label, so folding it into the combined helper
would reorder drawing (the NOW label must stay on top). CPD flags textual similarity; it can't see
draw-order semantics. Temp got only the shared `drawDayLabels`.

Cleaned 7 orphaned imports per refactored graph file. CPD 98 → 89; the big 57/37/30-line composable
blocks gone.

## Batch 4 — Desktop in-`DrawScope` coordinate setup (`yAt` residual) — DONE

The remaining cloud↔precip residual (24/15/14/11/8-line blocks) is the in-`DrawScope` coordinate
geometry: `w`/`h`/`graphTop`/`footer`/`graphBottom`/`graphHeight`/`dataStart`/`dataEnd`/`dataSpan`/
`dragResidualPx`/`xAtTime`/`xAt` are identical; only `yAt` (cloud `topScale` vs precip `yScaleMax`)
and the `coords` line that calls it differ.

**Plan:** add `DrawScope.hourlyGraphCanvasGeometry(points, textMeasurer, scale, dragHours)` to
`DesktopGraphUtils.kt` returning a `HourlyGraphCanvasGeometry` holder (`w`, `h`, `graphTop`,
`graphBottom`, `graphHeight`, `footer`, `xAtTime`, `xAt`). Each graph destructures the shared values,
keeps its own local `yAt` + `coords`. `xAtTime`/`xAt` become val lambdas → the existing `::xAt`
function-reference passed to the footer/tail helpers becomes plain `xAt`.

**Done:** added `DrawScope.hourlyGraphCanvasGeometry(...)` → `HourlyGraphCanvasGeometry` holder to
`DesktopGraphUtils.kt`. Both graphs destructure it and keep their own local `yAt` + `coords` (precip
also keeps its unique `stepWidth`). `xAt` flipped from a local `fun` to a `val` lambda, so the
`::xAt` function-references passed to the footer/tail helpers became plain `xAt`. Removed 3 now-unused
imports from CloudCoverGraph (Instant/ZoneId/LocalDateTime — precip still uses them).

**Result:** CPD 89 → 87. The remaining cloud↔precip residuals are the un-deduplicatable `@Composable`
signature (param list) and trivial `val x = geo.x` destructuring — CPD counts textual blocks, not
logic shared, so the modest count drop understates the real win (the `xAtTime`/`dataSpan`/
`dragResidualPx`/bounds geometry is now single-source). 49 desktop tests green; app rebuilt + restarted
healthy (2 procs).

---

## Files touched this session

**Tooling:** `build.gradle.kts`, `gradle/libs.versions.toml`

**`:shared` (new):** `shared/graph/TemperatureColorModel.kt`, `FetchDotLabel.kt`, `CurveMath.kt`
**`:shared` tests (new):** `TemperatureColorModelTest.kt`, `FetchDotLabelTest.kt`, `CurveMathTest.kt`

**`:app`:** `widget/HourlyGraphPaints.kt` (new), `widget/TemperatureGraphStyle.kt`,
`widget/GraphRenderUtils.kt`, `widget/CloudCoverGraphStyle.kt`, `widget/PrecipitationGraphStyle.kt`,
`widget/CloudCoverGraphRenderer.kt`, `widget/PrecipitationGraphRenderer.kt`,
`test/.../widget/TemperatureGraphStyleTest.kt`, `test/.../widget/GraphRenderUtilsTest.kt`

**`:desktop`:** `DesktopGraphUtils.kt`, `HourlyGraphInput.kt`, `CloudCoverGraph.kt`,
`PrecipitationGraph.kt`, `TemperatureGraph.kt`

---

## Verification status

- Batches 0–5: shared + app + desktop compile clean; new shared tests + Android renderer/style tests
  + 49 desktop tests (`DesktopGraphZoomTest` 24, `DesktopUiTest` 20, `DesktopStartupTest` 5) all green.
- Desktop app rebuilt + restarted via `scripts/buildStart.sh` after each desktop batch; healthy (2 procs).
- Batch 5: `installDebug` OK on both physical devices; 44 renderer Robolectric tests green; on-device
  screenshot skipped (Pixel locked).

## Batch 5 — App CloudCoverGraphRenderer ↔ PrecipitationGraphRenderer (`:app`, within-platform) — DONE

These were **already** mostly de-duplicated (real shared logic in `GraphRenderUtils.drawHourLabels`/
`drawDayLabels`/`computeDayLabelPlacements` + `ValueLabelEngine`). CPD's remaining hits were mostly
*structural* (data-class field lists, function-signature tails, already-shared call sites). Extracted
the two genuine *logic* dups to `GraphRenderUtils`:
- `drawHourIcon(context, canvas, iconRes, iconRect, isRainy/isMixed/isNight/isTwilight/isSunny)` — the
  footer weather-icon draw+tint body. Each renderer keeps its own per-icon side-effect at the call
  site (cloud `drawnIconBounds.add`, precip `onHourIconDrawn`).
- `dayLabelEndpoints(first, last, currentTime)` → `DayLabelEndpoints` (today/left/right date+text),
  destructured at the call sites.

Added a plain-JUnit `dayLabelEndpoints` test. CPD 87 → 84. Residual cloud↔precip renderer pairs
(14/14/11-line) are the structural ones, left intentionally. 44 renderer Robolectric tests green;
`installDebug` succeeded on both physical devices (Samsung Fold + Pixel 7 Pro); on-device screenshot
skipped (Pixel screen was off/locked — did not wake the user's phone).

## Batch 6 — App CloudCoverViewHandler ↔ PrecipViewHandler (`:app`, within-platform) — DONE (safe seams only)

The riskiest pair (RemoteViews binders, sticky-visibility hazard). User chose "safe seams only".
New `app/.../widget/handlers/HourlyGraphViewCommon.kt` (object) with:
- `bindHourlyTextMode(views, forecasts, centerTime, numColumns, displaySource, valueText)` — the
  byte-identical text-mode column binder; only the per-graph value string is a lambda (cloud
  `cloudCover%`/`--%`, precip `precipProbability%`/`--%`). Consolidating the `setViewVisibility` calls
  into one place *reduces* sticky-visibility drift risk.
- `resolveHourPresentation(...)` → `HourPresentation` — the identical per-hour sun/icon/label block;
  each builder keeps its own null-check and data-class construction (precip retains
  `precipAmountMm`/`actualPrecipAmountMm`).
- The shared `Quad` moved here. `formatHourLabel` reused from `handlers/WidgetFormatUtils.kt`.

**Deferred (per "safe seams only"):** the 85-line graph-render orchestration — the two renderers have
incompatible signatures (`missingHours/missingReason` vs `rainAmountWindowHours/rainLabelMode/
onDebugLog`); unifying needs a sealed-class strategy in the sticky-visibility hot zone (high
machinery, modest gain). Header-binding residuals also left.

CPD 84 → 80. Verified: compile clean; the `reapply()` sticky-visibility guard
(`CurrentTempTouchRoutingRoboTest`, 11) + 7 other handler suites green (64 total); new
`HourlyGraphViewCommonRoboTest` (3); `installDebug` OK on emulator + Pixel; widget renders healthy on
the emulator (couldn't force cloud-cover view via `ACTION_SET_VIEW` — wrong widget id; relied on the
Robolectric coverage of the changed paths).

## Remaining de-duplication backlog

- **Still parked (now the only big target):** the 85-line graph-render orchestration inside
  `CloudCoverViewHandler` ↔ `PrecipViewHandler` — needs a sealed-class renderer strategy; deferred
  for risk/ROI.
- The leftover cloud↔precip residuals across the desktop graphs, the Android renderers, and these
  handlers are now *structural* (composable/function signatures, data-class field lists, header-bind
  orchestration, already-shared call sites) — CPD keeps flagging them, but they're not
  deduplicatable without harming readability.

## Notes / no commits

No commits or pushes made this session (not requested). All changes are in the working tree.

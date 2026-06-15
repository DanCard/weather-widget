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
three de-duplication batches. Tracked progress via `./gradlew cpdCheck`.

**Duplicate-block count across the session: 99 → 89** (and a 4th batch in progress at session end).

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
`test/.../widget/TemperatureGraphStyleTest.kt`

**`:desktop`:** `DesktopGraphUtils.kt`, `HourlyGraphInput.kt`, `CloudCoverGraph.kt`,
`PrecipitationGraph.kt`, `TemperatureGraph.kt`

---

## Verification status

- Batches 0–3: shared + app + desktop compile clean; new shared tests + Android renderer/style tests
  + 49 desktop tests (`DesktopGraphZoomTest` 24, `DesktopUiTest` 20, `DesktopStartupTest` 5) all green.
- Desktop app rebuilt + restarted twice via `scripts/buildStart.sh`; healthy (2 procs each time).
- Batch 4: not yet verified (in progress).

## Remaining de-duplication backlog

- **Batch 4** (in progress): desktop `yAt` coordinate-setup extraction.
- App-side `CloudCoverGraphRenderer` ↔ `PrecipitationGraphRenderer` (Android Canvas) — separate target.
- **Biggest, still parked:** app `CloudCoverViewHandler` ↔ `PrecipViewHandler` (85+47+22-line CPD hits)
  — RemoteViews widget-binding with sticky-visibility hazards; warrants its own plan-mode pass.

## Notes / no commits

No commits or pushes made this session (not requested). All changes are in the working tree.

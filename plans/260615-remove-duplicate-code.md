# Spot & share duplicate code (desktop ↔ Android ↔ shared)

## Context

The user suspects significant copy-paste between the Android `:app` module and the
Linux `:desktop` module, and asked whether duplication is easy to *spot* and to
*share* rather than re-duplicate.

**Answer from exploration:** Yes on both counts, because the preconditions already exist:

- `:shared` is **pure Kotlin/JVM, zero Android deps**; both platforms depend on it
  one-directionally (`:app → :shared ← :desktop`). Sharing is mechanical, not risky.
- There is already a proven **delegate pattern**: pure logic lives in `:shared`
  (`TemperatureLabelEngine`, `LocationMatch`, `NwsHourlyGridMerge`, `TempUtils`),
  each platform calls in, occasionally via a thin wrapper that adds logging / converts
  Room entities → shared models.
- Duplication is **concentrated and predictable**: it clusters in the graph renderers,
  where `android.graphics.Canvas` (Android) vs Compose `DrawScope` (desktop) force two
  copies of the *same math*. ~800–1200 lines remain duplicated.

What's missing today: (1) no automated copy-paste detector (only ktlint, which is
formatting-only), so duplication is caught by review; (2) a batch of pure-math helpers
still living in both renderers.

This plan does **both, pure-math only** (per user): wire up a copy-paste detector so
future duplication surfaces automatically, then extract the high-confidence,
platform-free duplicates as the first batch — proving the detector's findings get resolved.

The hard boundary we will NOT cross in this batch: drawing calls (Canvas vs DrawScope),
text metrics (`Paint.FontMetrics` vs `TextMeasurer`), and DB access (Room `@Dao` vs raw
JDBC). Those need adapters and are explicitly out of scope here.

---

## Part A — Tooling: automated copy-paste detection

Use **PMD's CPD** (it supports Kotlin; detekt has no copy-paste detection) via the
`de.aaschmid.cpd` Gradle plugin, configured **report-only** so it never fails a build.

1. Add the plugin to the **root** `build.gradle.kts` (apply to all modules) — or, if the
   root has no plugins block, add a small `cpd` config there. Settings:
   - `language = "kotlin"`, `minimumTokenCount` ≈ 60–75 (tuned so it flags the renderer
     duplicates but not trivial boilerplate), `ignoreFailures = true`.
   - Source set = `app/src/main`, `desktop/src/main`, `shared/src/main` (main only; skip tests).
   - Output: `build/reports/cpd/cpd.xml` + `cpd.html` for human reading.
2. Add a convenience entry to project docs/scripts: `./gradlew cpdCheck` produces the report.
3. **Deliverable:** run it once and capture the current duplication report as the baseline —
   this is the "is it easy to spot?" answer made concrete and repeatable.

> Note: keep it report-only for now. Turning it into a CI gate is a follow-up decision once
> the baseline is clean — out of scope here to avoid blocking unrelated work.

---

## Part B — First extractions (pure-math only)

Each item is platform-free. Pattern: create the pure function in `:shared`, then have **both**
renderers delegate. Where a thin platform wrapper already exists (logging), keep it and delegate
its body.

### B1. `formatTemp` — delete two copies, reuse what already exists
`shared/util/TempUtils.formatTemp` **already exists**. Replace the re-rolled copies:
- `app/.../widget/TemperatureGraphStyle.kt:73` → delegate to `SharedTempUtils.formatTemp`
  (Android already imports it elsewhere).
- `desktop/.../TemperatureGraph.kt` inline `formatTemp` (~line 378) → call shared.
This is pure cleanup: no new code.

### B2. Temperature color model → new `shared/graph/TemperatureColorModel.kt`
Extract the thresholds + blend + `tempToColor` returning a **packed ARGB `Int`** (pure bit math,
no `android.graphics`):
- Constants: `COLD_THRESHOLD=50f`, `MILD_TEMP=70f`, `HOT_THRESHOLD=90f`, colors
  `#5AC8FA / #E8A24E / #FF6B35`.
- `fun tempToColorArgb(temp: Float): Int` with integer-RGB blend (port Android's `blendColors`).
- Callers:
  - `app/.../TemperatureGraphStyle.kt:56` `tempToColor` → return `tempToColorArgb(...)` (already Int).
  - `desktop/.../TemperatureGraph.kt:109` `tempToColor` → `Color(tempToColorArgb(temp))`.
- **Bonus fix:** desktop currently blends via Compose `lerp()` (different color space) — moving to
  the shared integer blend makes the two platforms pixel-identical, closing a latent parity bug.
- The duplicated constants `COLD/MILD/HOT_THRESHOLD` at `TemperatureGraph.kt:67-69` are then deleted.

### B3. Fetch-dot age label → `shared/graph/` (e.g. `FetchDotLabel.kt`)
Identical logic at `TemperatureGraphStyle.kt:86` and `TemperatureGraph.kt:83`:
- `fun formatAgeLabel(ageMinutes: Long, spanHours: Long, maxSpanHours: Long = 12): String?`
  returning `"17m"` / `"1h 5m"` / null.
- Android wrapper keeps its `Log.w` on negative input, then delegates.
- Desktop calls shared directly. Delete desktop's `AGE_LABEL_MAX_HOURS_SPAN` (line 72) and Android's
  (`TemperatureGraphStyle.kt:41`) in favor of the shared default.

### B4. Catmull-Rom tangents → `shared/graph/CurveMath.kt`
`computeTangents` is byte-for-byte identical (only `Pair<Float,Float>` vs `Offset`):
- `fun computeTangents(points: List<Pair<Float, Float>>): List<Pair<Float, Float>>`
  (monotone-aware, max-safe-dx clamp — port verbatim).
- `app/.../widget/GraphRenderUtils.kt:104` → delegate (already `Pair<Float,Float>`).
- `desktop/.../DesktopGraphUtils.kt:145` → map `Offset`↔`Pair` at the boundary; `buildCurve`/path
  construction stays in desktop (Compose `Path`), only the tangent math is shared.
- Also fold in curve-Y interpolation if it lands cleanly: Android `interpolateYAtX` vs desktop
  `getCurveYAtX` — same pure linear interpolation.

### B5. Hour label formatting → `shared/graph/` (if low-friction)
`formatHourLabel(hour: Int): String` ("12a", "1p") exists on both sides
(`DesktopGraphUtils.kt:198`, inlined in Android). Extract one shared function. Skip if it turns out
to be entangled with platform text layout — pure-string only.

> Out of scope (adapter-pattern, deferred): fetch-dot 3-way placement geometry, the staleness
> displacement cascade, and shared SQL string builders for the Room/JDBC DAOs. The user chose
> pure-math only; these are the natural next batch once the detector baseline is green.

---

## Critical files

**Tooling:** root `build.gradle.kts`, `gradle/libs.versions.toml` (plugin coordinate).

**Shared (new/edited):**
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureColorModel.kt` (new)
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/FetchDotLabel.kt` (new)
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/CurveMath.kt` (new)
- `shared/src/main/kotlin/com/weatherwidget/shared/util/TempUtils.kt` (reused, no change)

**Android callers:** `app/.../widget/TemperatureGraphStyle.kt`, `app/.../widget/GraphRenderUtils.kt`.

**Desktop callers:** `desktop/.../TemperatureGraph.kt`, `desktop/.../DesktopGraphUtils.kt`.

---

## Verification

1. **Build both platforms:** `./gradlew :shared:test :app:assembleDebug :desktop:compileKotlin`
   (compilation across all three modules is the first guard — shared is pure-JVM so it compiles fast).
2. **Unit tests for the new pure functions** in `shared/src/test` — these are now trivially testable
   (the whole reason to extract): assert `tempToColorArgb` at boundary temps (≤50, =70, ≥90, mids),
   `formatAgeLabel` ("17m"/"1h 5m"/null at >12h span and negative), and `computeTangents` against a
   small known point set. Per repo convention (no mocking framework), these are pure-function tests.
3. **Parity check (the real point):** because B2 unifies the color blend, confirm desktop output is
   unchanged-or-corrected. Restart the desktop app with the repo script
   (`scripts/fast-desktop-restart.sh` if no Gradle change, else `scripts/buildStart.sh`) and eyeball
   the hourly temperature graph gradient + the fetch-dot age label.
4. **Android:** `./gradlew installDebug`, add the widget, confirm the hourly graph renders identically
   (gradient, age label, smooth curve).
5. **Run the detector again:** `./gradlew cpdCheck` and confirm the extracted blocks no longer appear
   in `build/reports/cpd/cpd.html` — the baseline shrinks by the lines we moved.

# Daily forecast: today rain-% label gap + Android/desktop parity

## Context

On the Samsung (Galaxy Z Fold, `RFCT71FR9NT`) daily forecast view, today's `15%` rain
chance label floats far above today's `75.6°` high-temp label — a large *gap*, not an
overlap. The user wants the rain label to sit close to the high label, wants to know
**why** the gap is there (add logging if the cause isn't obvious), and asked whether the
desktop port should share this positioning code (it currently does not). User chose
**full parity**.

What exploration established:

- **Text is already shared** via `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`
  (both platforms call `buildDailyRainLabel` / `buildNightRainLabel`). Only the **positioning**
  is duplicated.
- **Android day-label placement** lives in `DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement()`:
  `rainBaseline = highLabelTop - gap - rainDescent`, where `highLabelTop` comes from
  `DailyForecastGraphRenderer.resolveHighLabelBaseline()` = `tempToY(effectiveHigh()) - HIGH_LABEL_OFFSET_DP`.
  `gap = RAIN_HIGH_TEMP_GAP_DP` (currently `-3f`, applied at line 121).
- The today high label is drawn at the **same** anchor (`tempToY(effectiveHigh()) - labelOffset`,
  `DailyForecastGraphRenderer.kt:883/890`), so the math says the rain label should sit ~3dp above
  it. The observed large gap is therefore **not explained by the current source** → needs runtime data.
- `RAIN_HIGH_TEMP_GAP_DP` in `DailyForecastGraphRenderer.kt:74` is **dead code** (never read); the
  user's recent two-file `-2f→-3f` edit only mattered in the RainLabelRenderer copy.
- **Desktop** (`desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt:326-335`)
  does NOT reuse its real high-label top. It approximates: `yAt(high) - (14f + 8f)*scale - rainHeight`,
  with hardcoded fudge factors, while one block up (lines 281-284) it already computes the true
  `highLabelY`. Night-tuck constants are also duplicated as local vals (lines 362-366).

## Phase 1 — Diagnose the gap (answer "why?")

Do this first; the fix in Phase 2 depends on the finding.

1. **Rule out a stale install (cheapest first).** Rebuild current source and install on the
   Samsung, then re-screenshot:
   - `./gradlew installDebug`
   - `adb -s RFCT71FR9NT shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE` (or the
     app's `ACTION_REFRESH`) to force a redraw.
   - Screenshot via the Fold-safe path (multi-display prepends a warning line): capture PNG, strip
     bytes before the `\x89PNG` magic, convert to JPG, then read. (See scratchpad
     `samsung_clean.png` flow already used this session.)
   - If the gap collapses to ~3dp, the device was simply running an old binary — note it and skip to
     Phase 3.

2. **If the gap persists, add permanent VERBOSE tracing** (per user's "keep graph-label debug
   logging" preference — use `Log.v`, logcat-only, no "remove later" marker). In
   `DailyForecastRainLabelRenderer` (`drawDailyRainLabel` / `resolveDailyRainLabelPlacement`),
   log per day: `date`, `isToday`, `effectiveHigh`, `tempToY(high)`, `highBaseline`
   (`anchorBaselineY`), `highLabelTop`, rain `baseline/top/bottom`, `gap` px, and the derived
   `delta = highLabelTop - rainBottom`. This reveals whether `highBaseline` is being pushed toward
   the header, whether `effectiveHigh` diverges from the printed high, or whether the gap is real.

3. **Scope note (user-clarified).** The upper `15%` near the top-left is the widget's
   current-conditions / header rain chance and is **out of scope**. The label in question is
   today's *column* day-rain `15%`, which sits with a large gap above today's `75.6°` high. The
   Phase-1 trace should make the day-column label's numbers (`highLabelTop`, rain `bottom`, `delta`)
   explicit so we see exactly how far above the high it lands and why.

## Phase 2 — Fix the root cause + single gap knob

Apply whatever Phase 1 reveals (e.g. correct the anchor, suppress a duplicate, or simply confirm
the rebuild fixed it). Then make the gap a **single shared value** so it's tuned in one place
instead of the current duplicated/dead constants.

## Phase 3 — Full Android/desktop parity (user-selected)

1. **Shared constants** — add to `shared/.../util/DailyRainLabels.kt` (or a sibling
   `DailyRainLabelGeometry.kt`):
   - `RAIN_HIGH_TEMP_GAP_DP` (the day-label gap, the single knob from Phase 2).
   - The night-tuck set: `NIGHT_SCALE`, `NIGHT_TUCK_ROOM_MIN_DP`, `NIGHT_TUCK_ROOM_MAX_DP`,
     `NIGHT_TUCK_OVERLAP_BASE_DP`, `NIGHT_TUCK_NUDGE_BASE_DP`, `NIGHT_TUCK_NUDGE_RANGE_DP`.
   - Document the shared rule: **rain-label bottom = high-label top − gap** (negative gap = slight
     overlap); both platforms apply their own density/`scale` to the dp value.

2. **Android cleanup** — delete the dead `RAIN_HIGH_TEMP_GAP_DP` in
   `DailyForecastGraphRenderer.kt:74`; point `DailyForecastRainLabelRenderer` (line 17 const + line
   121 use) at the shared constant; replace its private night-tuck consts (lines 17-24) with the
   shared ones. Behavior unchanged — pure de-duplication.

3. **Desktop parity** — in `DailyForecastGraph.kt`:
   - Day label (lines 326-335): stop using the `14f + 8f` fudge. Hoist/reuse the real high-label top
     (`highLabelY`, computed at line 283) and place via the shared rule
     `rainBottom = highLabelTop - gapPx; rainTop = rainBottom - rainHeight`, using shared
     `RAIN_HIGH_TEMP_GAP_DP * scale`. This both brings desktop close (matching Android) and removes
     the divergent magic numbers.
   - Night label (lines 362-366): replace the local `NIGHT_TUCK_*` vals with the shared constants.

## Critical files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt` — add shared constants.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastRainLabelRenderer.kt` — consume shared
  constants; add Phase-1 VERBOSE trace.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — delete dead constant
  (line 74).
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` — day + night label use
  shared constants and the real high-label top.

## Verification

- **Android**: `./gradlew installDebug`; force widget refresh; Fold-safe screenshot of the daily
  view; confirm today's `15%` sits just above `75.6°` and only one day-label renders per column.
  Pull logcat (`adb -s RFCT71FR9NT logcat -d | grep DailyRainLabelRenderer`) and confirm the traced
  `delta` matches the intended gap.
- **Desktop**: `scripts/buildStart.sh` (rebuild + restart); visually confirm the daily-view rain
  label now sits as close to the high label as Android.
- **Unit tests**: extend the existing renderer placement tests for
  `resolveRainAboveHighPlacement` to assert the shared-constant gap; keep colors-are-zero caveat in
  mind (assert geometry, not colors). Run `./gradlew testDebugUnitTest --tests "*DailyForecast*"`.
- Do NOT run `./gradlew connectedDebugAndroidTest` (removes widgets); use `scripts/emulator-tests.sh`
  if instrumented coverage is needed.

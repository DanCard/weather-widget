# Daily-view history: dual actual/forecast high labels + desktop label shadow

**Date:** 2026-06-14
**Area:** Daily forecast view (Android `DailyForecastGraphRenderer.kt` + desktop `DailyForecastGraph.kt` + shared `DualHighLabel.kt`)

## Summary

Added a second high-temperature label on **past days** in the daily view so a forecast miss
shows BOTH the actual high (thermostat pink) and the forecast high (adaptive amber) — gated by a
shared "room-based" decision. Then iterated heavily (per live screenshots on desktop + Android
devices) on the trigger threshold, cross-platform color/label parity, font-size shrink rules, and
— at length — the **desktop label shadow**, landing on a true `Stroke`-outline.

All work verified live on desktop (4K screenshots) and Android (emulator-5554 + Motorola), with
shared unit tests and the full `:app:testLongDebugUnitTestFresh` suite green.

---

## Prompts (verbatim, in order)

1. > Daily forecast view: History: I'm wondering about displaying both the actual and forecasted
   > high temp. My thoughts:
   > 1) Only display if difference is substantial. Like yesterday there was an 8 degree difference.
   > 2) Show actual in thermostat color?
   > 3) Show forecast in standard forecast color of sun color?
   > What do you think?
   > Slight overlap is o.k.
   > Have various devices connected so we can see how it looks.

2. > Also we can shrink the font size a bit when displaying two temperature labels for prior day high temp.

3. > Actually when displaying temps with tenths of a digit, lets auto shrink by 10%.

   *(answers to clarifying questions during planning)*
   - Threshold: "Can we make it room based? If there is enough room so that labels don't overlap
     too much? If not lets try greater than or equal to 5"
   - Forecast label color: "Match the forecast bar"

4. > Several minor tweaks. Can be done step by step, starting with first.
   > 1) Desktop for past Friday doesn't look like android. Prefer android. Android shows actual
   >    where as android shows forecasted temp in history?
   > 2) After that is resolved: I think there is room for Friday to show both. Should we tweak
   >    overlap detection to allow overlap, or at least experiment to see if it looks good. Feel
   >    free to look at emulator-5554 if that helps.

5. > 3) Want colors on android and desktop to match for forecast color. Any suggestions?
   - Answer to question: "Adaptive amber (Recommended)"

6. > 560 tests completed, 6 failed … Task :app:testLongDebugUnitTestFresh FAILED  *(NPE report)*

7. > What do think of adding a shadow to the history high temp labels. Sometimes hard to see when
   > overlap with temperature bar.

8. > I regret the decision to shrink the history font size by 10%. Can we revert that decision?

9. > Actually lets soften the two label font size shrink. Should we try 8% shrink. Lets try 3 digit
   > shrink of 5%

10. > Not seeing shadow on desktop. Can we just draw a black font bigger first, then draw the real
    > font on top of that?  *(reiterated: "bigger or bold")*

11. > Tweak two label font size shring from 8% to 4%

12. > The outline idea doesn't look good on desktop. Thoughts? Other ideas to add a black shadow?

13. > Shadow on desktop doesn't look good. Can we have a stronger shadow? Or completely different
    > algorithm for shadow? Might work if we draw the shadow with bold text. Reduce dual label
    > shrink from 4% to 2%.

14. > Shadow on desktop still doesn't look good. Can we have a stronger shadow? Or completely
    > different algorithm for shadow?

15. > Thanks that works

16. > write a session log to session-logs/ dir  Include all prompts

---

## What was built

### Core feature — dual high labels for past days
- New shared pure fn `shared/src/main/kotlin/com/weatherwidget/shared/graph/DualHighLabel.kt`:
  `showBoth(actualHigh, forecastHigh, actualLabelTopY, forecastLabelTopY, labelHeightPx)`.
  - Gate = `|diff| >= MIN_DIFF_DEG` **AND** label-box gap `>= labelHeight*(1 - MAX_OVERLAP_FRACTION)`.
  - Room-based because temp→Y is linear, so the pixel gap == `|diff| * pixelsPerDegree`, which
    auto-scales with each device's graph compression.
- Both renderers already had the forecast overlay **bar**; this added the second **label**:
  - Android `DailyForecastGraphRenderer.drawDayBars()` + new `drawTempLabel()` helper. Actual =
    `solidLineHigh` (color `COLOR_OBSERVED_RED`), forecast = `dashedLineHigh` (color
    `WeatherConditionColors.forecastColor(...)`).
  - Desktop `DailyForecastGraph.kt`. Actual = `day.solidHigh` (`COLOR_OBSERVED`), forecast =
    `day.forecastHigh` (`forecastColor(day)`).
- Plain-JUnit `DualHighLabelTest.kt` (asserts relative to the constants so tuning doesn't break it).

### Iteration outcomes
- **Threshold:** room-based with a low floor. Settled on `MIN_DIFF_DEG = 3f`,
  `MAX_OVERLAP_FRACTION = 0.6f` so the **room test is the real gate** (let Friday's ~3.7° miss show
  both). Verified Tue's sub-3° miss correctly stays single.
- **Single-label parity (#4.1):** desktop's single past-day label was `max(solidHigh, forecastHigh,
  …)` (showed the forecast); changed to mirror Android's `effectiveHigh()` → show the **actual**
  (`day.solidHigh`), forecast fallback only if no actual.
- **Forecast color parity (#5):** desktop past forecast **bar** + dual **label** were hardcoded
  `Color.Yellow`; pointed both at the adaptive `forecastColor(day)` desktop already uses for future
  bars. Both platforms now use the shared `WeatherColors.FORECAST_*` palette (sunny #F4C542 amber,
  cloudy #8E99A4, rainy #5A8FBF). Today's forecast stays pure yellow on both (the "today highlight"
  color, already matched).
- **Test fix (#6):** the room test dereferenced `basePaint.fontMetrics.descent` unconditionally;
  `Paint.getFontMetrics()` returns null under stubbed-Paint renderer tests → NPE on every day.
  Fixed null-safe: `fontMetrics?.let { descent - ascent } ?: textSize`.

### Font-size shrink saga (#2, #3, #8, #9, #11, #13)
- v1: 0.85× two-label shrink + **0.9× tenths shrink** (decimal labels 10% smaller).
- User **reverted the tenths 10%** (#8) → removed `hasTenths`/`TENTHS_FONT_SCALE`.
- Replaced (#9) with **digit-count** `isWideLabel(label) = digitCount >= 3` → `WIDE_LABEL_FONT_SCALE
  = 0.95` (5% for `100°` and decimals like `97.7°`).
- Two-label shrink softened 0.85 → 0.92 (8%, #9) → 0.96 (4%, #11) → **0.98 (2%, #13)**.

### Desktop label shadow saga (#7, #10, #12, #13, #14) — five attempts
The motivation: dual labels are colored to match their bars (pink-on-pink, amber-on-amber), so
they vanish where they overlap. Android already had `setShadowLayer` (bumped 1.5→**2.5dp** radius,
which the user accepted). Desktop had none, and went through:
1. Compose blurred `Shadow` — too faint (rejected).
2. Scaled "bigger black behind" outline — blobby; whole-string scaling widens letter gaps (rejected).
3. Thin sharp offset drop shadow — too weak (rejected).
4. Bold-black centered halo — still not strong/clean enough (rejected).
5. **FINAL (accepted):** true black **outline** — measure the glyphs with
   `TextStyle(drawStyle = Stroke(width = fontSize * OUTLINE_STROKE_FRACTION 0.32))`, draw at the real
   topLeft, then the fill text on top. The stroke traces each glyph's real path → uniform black on
   all sides; only the outer ~half shows. One tunable fraction.

   **Lesson:** to outline Compose text, render it with `drawStyle = Stroke` underneath the fill —
   beats faking a halo with offset/scaled/bold copies.

---

## Files changed
- **New:** `shared/src/main/kotlin/com/weatherwidget/shared/graph/DualHighLabel.kt`
- **New:** `shared/src/test/kotlin/com/weatherwidget/shared/graph/DualHighLabelTest.kt`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — dual labels,
  `drawTempLabel()` helper, `isWideLabel` shrink, null-safe fontMetrics, shadow radius 1.5→2.5.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` — dual labels, single
  label → actual, adaptive forecast color on past bar+label, `tempFontSize` (wide shrink),
  `drawShadowedText` (Stroke outline).

## Final tunable knobs
- `DualHighLabel.MIN_DIFF_DEG = 3f`, `MAX_OVERLAP_FRACTION = 0.6f`
- `DualHighLabel.TWO_LABEL_FONT_SCALE = 0.98f`, `WIDE_LABEL_FONT_SCALE = 0.95f`
- Desktop `OUTLINE_STROKE_FRACTION = 0.32f`; Android `LABEL_SHADOW_RADIUS_DP = 2.5f`

## Verification
- `./gradlew :shared:test` — green; `:app:testLongDebugUnitTestFresh` — green (after NPE fix).
- Desktop: `scripts/buildStart.sh` + `.show` trigger + `xfce4-screenshooter`; inspected Fri column
  at each iteration. Android: `./gradlew installDebug` (4 devices) + `APPWIDGET_UPDATE` broadcast +
  `adb exec-out screencap`.
- Data note: in UTC-day terms yesterday (06-13) had actual 79.6° vs forecast 87° (−7.4° miss) and
  Friday (06-12) 84.3° vs 88° (~3.7°) — good live cases for the dual-label trigger.

## Memory
- `dual_high_label_past_days.md` (+ MEMORY.md index line) records the feature, the room-based gate,
  the font-shrink history (incl. the reverted tenths rule), the cross-platform parity fixes, and the
  five-attempt desktop shadow lesson.

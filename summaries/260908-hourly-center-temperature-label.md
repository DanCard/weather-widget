# 260908 — Add one temperature label at the hourly graph center

**Date:** 2026-09-08 · **Plan:**
[plans/260908-hourly-center-temperature-label.md](../plans/260908-hourly-center-temperature-label.md) ·
**Status:** implemented, tested, and verified on desktop and an Android emulator; **not committed**

## User prompts

> Look at desktop, I'd like to know what is temperature at midpoint of graph / 2pm. Hourly
> temperature graph. What do you think?

> Skip the faint vertical marker and proceed

> In this case I think only one center temperature label is needed since both actual and forecasted
> hourly temp are close to each other.

> My preference would be to give higher priority to actual label for midpoint if looking at history.
> In other words if actual temperature line exists.

> looks good on desktop

## Evidence and root cause

The live desktop graph initially showed Monday 12 PM through 4 PM, placing 2 PM at the visual
midpoint. The NWS data contained a 79°F forecast and an approximately 78.3°F resolved actual there,
but neither value was labeled at 2 PM.

The shared candidate collector only injected its old midpoint fallback when exactly two edge labels
survived. Interior extrema therefore prevented a midpoint label even when the graph center itself
had no readable value. The fallback also selected `hours.lastIndex / 2`, which is not the temporal
midpoint when the actual series injects dense sub-hourly samples, and it always used forecast data.

## Change

1. `LabelCandidateCollector` now selects the sample nearest the temporal midpoint of the visible
   window on graphs with enough horizontal room.
2. The center label uses the actual value and actual styling when the actual line covers that
   sample. It falls back to the forecast value and forecast styling when it does not.
3. Only one candidate remains at the center sample. The center candidate is placed before other
   temperature labels so a nearby extremum cannot displace it.
4. If the center is itself an actual high or low, its established semantic role is retained while
   receiving center priority. This preserves the tuned peak/valley placement behavior.
5. Added the shared `CENTER` role for ordinary midpoint samples and wired its styling/debug coverage
   through Android and desktop.
6. No new vertical marker was added. Vertical time/fetch indicators already present in some views
   are existing graph behavior.

## Tests and verification

- Added `TemperatureCenterLabelTest` with coverage for temporal selection in a densely sampled
  history, actual-over-forecast preference, forecast fallback, and center-first sorting.
- Updated `TemperatureGraphLabelPlacementRobolectricTest` for the persistent center behavior.
- Focused shared temperature tests: **92 passed** after preserving actual-extremum roles.
- Focused Android placement test class: **32 passed**.
- Full `:shared:test`: passed.
- Full `:desktop:test`: passed.
- `:app:assembleDebug`: passed.
- `:desktop:createDistributable`: passed.
- `git diff --check`: passed.
- Desktop: user confirmed the resulting label looks good.
- Android API 36 emulator (`emulator-5554`, Google `sdk_gphone64_x86_64`): debug APK installed and
  runtime inspection confirmed one center value. The displayed center was just beyond actual-line
  coverage, so the forecast fallback was correctly used.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/LabelCandidateCollector.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/LabelGeometryResolver.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TempLabelCandidate.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureRole.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt`
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphAnnotationRenderer.kt`
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureCenterLabelTest.kt`
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`
- `plans/260908-hourly-center-temperature-label.md`

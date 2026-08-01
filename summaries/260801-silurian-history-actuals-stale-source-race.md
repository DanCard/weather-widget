# Silurian history actuals vanish after API toggle — fixed

**Date:** 2026-08-01
**Device:** Samsung SM-F936U1 (`RFCT71FR9NT`), widget 345
**Plan:** `plans/260801-silurian-history-actuals-stale-source-race.md`

**Reported symptom:** "daily forecast view: when I move to silur api, history actual temp is
missing. Sometimes reappears. Does reappear if I click back in history."

## The fix

**`app/.../widget/DailyActualsCoverage.kt`** (new, pure) — reconciles the filter set used to build
the actuals map against the sources a repaint is about to display. Two functions:
`uncoveredSources` and `unionSourceIds`.

**`app/.../widget/WeatherWidgetWorker.kt`** — three changes:

1. New `currentDisplaySourceIds()` helper replaces the three duplicated
   `appWidgetIds.map { ... }.distinct()` blocks.
2. The actuals load now reads sources **immediately before** `fetchDailyActuals` instead of reusing
   the start-of-run snapshot — window drops from ~4.4s to the ~600ms load.
3. `updateAllWidgets` re-reads each widget's source before the paint loop; on any uncovered source
   it logs `ACTUALS_SOURCE_RACE` and reloads once for the union, falling back to the original map if
   that reload comes back empty.

`activeSourceList` is deliberately still snapshotted at run start for `ForecastFetchContext` /
`requiredSourceIds` — those are decisions about *what to request*, where a start-of-run reading is
correct. Only the paint-facing use was wrong.

## Design notes

**Coverage is measured against the filter set, not `dailyActuals.keys`.** Tempting to write
`paintSource !in dailyActuals`, but a source with no observations yet legitimately has no key — that
version would fire a ~600ms reload on every single worker run forever. Comparing against what was
*requested* distinguishes "filtered out" from "genuinely absent."

**The repair reloads the union, not just the missing source.** Reloading only SILURIAN would drop
NWS from the map and break widgets 349/352 in the same paint — trading one widget's bug for two.

**The deeper lesson is a two-contract split.** `DailyInteractionRenderer` → `DailyActualsLoader`
applies *no* source filter, while the worker applies a strict one, over the same
`DailyActualsBySource` type. That asymmetry is exactly why tapping the history arrow always "fixed"
it. `DailyActualsCoverage` is now the seam where the two are forced to agree.

## Verification

- `DailyActualsCoverageTest` — 6 cases, all pass, including the exact OPEN_METEO→SILURIAN mid-run
  toggle from the device logs.
- `./gradlew installDebug` — installed on the Samsung, Pixel 7 Pro, and emulator.
- Post-install on the Samsung: all three widgets render (`WIDGET_RENDER_OK` for 345/349/352), a
  worker run completed, and **no spurious `ACTUALS_SOURCE_RACE`** — the common path stays free.

## Outstanding

Live confirmation of the repair firing needs a real mid-run source toggle, and this fold blocks
automation in both directions — `screencap` returns "Display Id '0' is not valid" and
`uiautomator dump` returns "could not get idle state". Stopped rather than keep poking at it.

**Asked the user to:** toggle widget 345 through to Silurian a couple of times, fairly quickly (the
race needs the second toggle within ~4s of the first). Then check logs for `ACTUALS_SOURCE_RACE`
present *and* `MISSING_ACTUALS_FETCH source=SILURIAN` gone.

# Daily graph y-axis spans only drawn temperatures (2026-10-29)

## User prompts

1. "Desktop: Seems compressed vertically. What do you think of stretching the daily desktop view more
   vertically?"
2. "I'm mostly interested in lengthening the bars, making the text bigger is less interesting."
3. "I didn't want the text to shrink. Only the bars to lengthen"
4. "What about the top of the bars, will they go higher?"
5. "Make the log permanent, not temporary"
6. "Skip the bottom change for now"
7. "I started an emulator" / "The pixel is not on lock screen"
8. "write a session summary to summaries/"

## Why

- The desktop window is 894×835 px, but the daily graph looked flat. The temperature range
  (45.8°–71°) only used part of the height.
- A new `axisRange` log showed the axis top at **75°**, but the hottest visible bar was 71°:

  ```
  axisRange canvas=870x768 scale=2.65 top=5 bottom=584 reserve=184 raw=45.8..75.0
    maxDay=2026-11-03 solid=70.0 fcst=null ghost=null snap=75.0
  ```

- The 75° was Tue Nov 3's prior-day snapshot (an older saved forecast). `DailyColumnBuilder` fills
  `snapshotHigh/Low` for future days too. Both renderers draw the snapshot bar only on today's and past
  columns, yet both still counted it in the axis range. That left about 4°, roughly 70 px, empty at the
  top. Android had the same bug (`DailyGraphLayoutResolver` included `snapshotHigh/Low` for every day).

## What changed

1. **New `:shared` rule, `DailyAxisTemps.drawn(...)`:** lists the temperatures a column draws.
   - A future day drops its snapshot.
   - Exception: a future day with no solid or forecast high keeps it, because its high label falls back
     to the snapshot.
2. **Both platforms use it:**
   - Desktop: `DailyForecastGraph` draw range and `computeDailyGraphTapLayout`, through a private
     `axisTemps(day)`.
   - Android: `DailyGraphLayoutResolver.resolve` (plausibility filter unchanged).
3. **Permanent `Log.v` `axisRange` line** in the desktop `DailyForecastGraph`: canvas size, scale,
   top/bottom, reserve, raw min..max, and the column that sets the max (matched with the same helper).
   It's `Log.v`, so it never reaches `app_logs`.

## Tried and reverted

- **Capping the desktop daily graph's `uiScale` by width** (`min(height/320dp, width/380dp)`) so a taller
  window lengthens bars instead of text. At the user's window it shrank the text ~10% and gained only ~2%
  bar length. Reverted because the user wants bars longer with text unchanged.

## Deferred

- **Bottom reserve:** `DailyForecastGraph.kt` sizes it from rough allowances (`font×1.4+4`,
  `font×1.5+6`, `+6`) instead of the measured icon + low label + day-name stack. About 50 px sat unused
  under the coldest column. Fixing it would add ~45 px of bar length. The tap layout has a duplicate of
  the formula. Skipped at the user's request.
- **Desktop header crowding:** with the taller bars, the hottest day's high label ("71°") now rides into
  the header beside "Thu 29". Header bleed is intentional, but the spacing here is tight.

## Verification

1. **Tests, one per module, each failing with the old rule temporarily restored:**
   - `DailyAxisTempsTest` (`:shared`, Short)
   - `DailyForecastGraphTapZoneTest.undrawnFutureSnapshot_doesNotStretchAxis` (`:desktop`): icon tops
     are unchanged by a hotter future snapshot.
   - `DailyForecastGraphRendererSizingTest` "undrawn future snapshot does not raise the axis" (`:app`):
     `maxTemp == 71`.
2. **Full JVM suite:** `./scripts/unit-tests.sh` passed, 6,300 tests.
3. **Desktop:**
   - The log now shows `raw=45.8..71.0`.
   - In before/after screenshots, the 71° bar grows from ~268 px to ~312 px (+16%); text size unchanged.
4. **Android:** `installDebug` on the Pixel 7 Pro and `emulator-5554`. Screenshots show the widget
   rendering correctly, with the hottest bars reaching the header (Pixel: Meteo; emulator: Google).

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/daily/DailyAxisTemps.kt` (new)
- `shared/src/test/kotlin/com/weatherwidget/shared/daily/DailyAxisTempsTest.kt` (new)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`
- `desktop/src/test/kotlin/com/weatherwidget/desktop/DailyForecastGraphTapZoneTest.kt`
- `app/src/main/java/com/weatherwidget/widget/DailyGraphLayoutResolver.kt`
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererSizingTest.kt`

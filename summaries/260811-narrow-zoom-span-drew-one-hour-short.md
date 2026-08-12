# Android: Hourly Zoom Set to 8 Hours Drew 7 — the Shared Half of the Desktop Bug

**Date:** August 11, 2026
**Baseline:** uncommitted work on top of `02664458` ("Release version 26081001 to Play Store Open
Beta and Production")
**Platform:** Android (widget) + `:shared`. Desktop already had its own end of this fixed; the
shared builder it calls did not.
**Status:** Implemented and verified. `:shared:test` (107 classes), `:app:testDebugUnitTest` and
`:desktop:test` fully green; confirmed on the emulator and on the Samsung, and independently
verified by the user.

---

## 1. What was asked, and what it turned into

The request was a Robolectric test: *"settings of 8 hours for narrow view matches display of 8
hours."* The test passed on the first run — against a widget that was visibly showing 7 hours.

That is the whole story of this change. The test I wrote first counted **hour marks**, and the code
emitted 8 of them, so it agreed with itself. The user's report — *"I told it to display 8 hours in
narrow view, but I only see 7"*, then *"same issue on samsung"* — is what exposed the definition
error underneath both.

| | Marks drawn | Hours covered |
|---|---|---|
| 8 h setting, before | 8 (`12a…7a`) | **7** ✗ |
| 8 h setting, after | 9 (`12a…8a`) | 8 ✓ |

---

## 2. The defect

`ActualTemperatureSeriesBuilder.build()` (`:shared`) walked its top-of-hour marks with:

```kotlin
while (currentHour.isBefore(endHour)) {   // exclusive
```

`endHour` is `alignedCenter + forwardHours` — **a mark inside the view, not one past it**. A window
covering n hours runs `start..start+n` and therefore has `n + 1` marks. Emitting n marks covered
`n - 1` hours.

This is not a cosmetic point-count issue. The renderers map first-mark→last-mark across the full
graph width (deliberately, so the curve reaches both edges), so dropping the end mark makes the
*drawn axis itself* an hour narrower than the setting promises.

**The tell:** the same function's actuals filter, eleven lines below, was already **inclusive**
(`!obsTime.isAfter(endHour)`). Two halves of one window disagreed about their own edge.

### Where it came from

Desktop hit this exact off-by-one on 2026-08-09 and fixed it **one layer up**, in its own point
filter (`hourlyPointsInWindow`, "6 h rendered a 5 h graph"). The shared builder both platforms call
was never touched, so Android kept the bug — identically on the Pixel emulator and the Samsung,
because the defect is in code neither platform owns.

---

## 3. The fix

Four loops walk this window and had to move together:

| File | Loop | Why it matters |
|---|---|---|
| `shared/…/actuals/ActualTemperatureSeriesBuilder.kt` | top-hour marks | the temperature graph, **both platforms** |
| `app/…/handlers/PrecipViewHandler.kt` | draw loop | precip graph owes the same span |
| `app/…/handlers/CloudCoverViewHandler.kt` | draw loop | cloud graph owes the same span |
| `app/…/handlers/CloudCoverViewHandler.kt` | `buildWindowHourKeys` | counts hours *with* cloud data; would under-report the window by an hour and the missing-data flag would lie |
| `app/…/handlers/MissingForecastHours.kt` | gap summary | a real forecast gap at the last mark would never be reported |

All now read `while (!currentHour.isAfter(endHour))`. 22 lines of production change across 4 files,
most of it comments recording why the end is inclusive.

Checked and deliberately **not** changed: the remaining `zoom.totalSpanHours` consumers are the
repaint gate (`windowSpanMinutes`) and the narrow-widget label cadence — neither is x-geometry, so
nothing drifts. `HOURLY_GRAPH_LOOKAHEAD_HOURS` is 168 h against a 164 h worst-case requirement, so
the extra inclusive mark at the far-future edge is still backed by fetched data.

---

## 4. Tests

### New

- **`shared/…/actuals/SharedNarrowSpanDisplayedHoursTest`** — the platform-independent half, placed
  on the code Android and desktop genuinely share. Asserts elapsed coverage for every configurable
  span (4–8 h), plus a named assertion on *which* edge mark exists, so a regression reads as "lost
  the last hour" rather than "count is off".
- **`app/…/ui/HourlyZoomSpanSettingRoboTest`** (Robolectric) — the Android end-to-end chain the user
  actually drives: SeekBar release → `WeatherDisplayPreferences` → `WidgetStateManager.getZoomWindow`
  (the single seam every renderer reads) → the hours all three hourly graphs build. Also covers the
  narrow-widget label thinning (fewer *labels*, unchanged *coverage*) and the `ACTION_REFRESH`
  repaint, since a persisted span the widget never repaints for reads as the setting not working.

### The assertion that matters

```kotlin
val drawn = Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
assertEquals(expectedHours.toLong(), drawn)
```

**Elapsed coverage, never mark count.** A count assertion passed against a widget visibly showing
7 hours. Desktop's `NarrowZoomSpanDisplayedHoursTest` had already settled on the same definition
(`cutoffMs - startMs == span`); the Android and shared tests now match it.

Both new tests were confirmed to fail before the fix, with the user's exact symptom:

```
a 8h setting must draw 8h of weather, first=8a last=3p marks=8 expected:<8> but was:<7>
```

### Existing tests updated (5)

All five encoded "n marks per n-hour window" — the convention being corrected, not regressions:

| Test | Change |
|---|---|
| `HourlyZoomCenteringRoboTest` (5 cases) | narrow label lists +1 mark; WIDE 24 → 25; comments rewritten |
| `TemperatureViewHandlerActualsTest > WIDE zoom covers 24 hours` | 24 → 25 marks — it was asserting a graph covering **23** hours under its own "covers 24 hours" name |
| `PrecipGraphQueryWindowTest` (2 cases) | `back + forward + 1` |
| `CloudCoverViewHandlerTest > buildWindowHourKeys` | 24 → 25 keys |
| `MissingForecastHoursTest` | `endHour = start.plusHours(4)` — inclusive walk over the same five fixture hours, all expected values unchanged |

---

## 5. Verification

| Check | Result |
|---|---|
| New tests before fix | Failed, `expected:<8> but was:<7>` |
| `:shared:test` | 107 classes, 0 failures |
| `:app:testDebugUnitTest` | Green |
| `:desktop:test` | Green (shared change is consumed by desktop too) |
| Emulator (`emulator-5554`) | `12a 1a 2a 3a 4a 5a 6a 7a 8a` — 9 marks, **8 hours** |
| Samsung (`RFCT71FR9NT`) | `11p 12a 1a 2a 3a 4a 5a 6a 7a` — 9 marks, **8 hours** |
| User | Independently verified working |

---

## 6. Lessons

1. **A test derived from the implementation can only confirm it.** The first version of the test
   inherited the code's own "n marks = n hours" assumption and even wrote a comment rationalizing
   the exclusive end. It passed against a visibly wrong widget.
2. **Assert what the user counts.** Hours of coverage, not marks, labels, or points. The count is an
   implementation detail; the coverage is the promise printed in Settings.
3. **A per-platform fix to a shared defect leaves the other platform broken.** Desktop's fix sat one
   layer above `:shared`, so the shared builder kept the bug and Android inherited it whole. When a
   defect is reported on one platform, check whether the fix belongs in `:shared` — and if the
   symptom later shows up on the other platform, that is evidence it always did.
4. **Look for a window that disagrees with itself.** The inclusive actuals filter sitting eleven
   lines below the exclusive mark loop was the diagnostic that pinned the defect in seconds.

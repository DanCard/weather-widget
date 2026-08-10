# Hourly Label Clutter, Dominant-Station Reading Time, and Tip Jar Localization

**Date:** August 9, 2026
**Baseline:** everything below is uncommitted work on top of `81c7dea8` ("Name the dominant blend
station on the hourly graph")
**Devices:** desktop (primary), emulator-5554; Samsung Fold dropped off `adb` partway through
**Status:** Implemented and verified. `:shared:test` and `:app:testDebugUnitTest` both fully green —
zero failures for the first time this session.

---

## 1. Desktop hourly graph: label clutter

**Reported as:** "fix the label clutter on desktop hourly temperature graph".

The screenshot showed the observed line carrying five labels piled into two unreadable blobs —
`76.6°/77.3°/77.4°` rendering as "76.7:6:37.4°", and `75.8°/76.1°` as "75.6:6.1°". The permanent
graph-label debug logging identified two *independent* defects; both had to go.

### Defect A — the fallback turning points had no cap

```
LabelAccepted: displayed="76.6" t=13:15 role=ACTUAL_HIGH reason=PROMINENT_ACTUAL_TURN idx=112
LabelAccepted: displayed="77.3" t=13:55 role=ACTUAL_HIGH reason=PROMINENT_ACTUAL_TURN idx=122
LabelAccepted: displayed="77.4" t=15:00 role=ACTUAL_HIGH reason=PROMINENT_ACTUAL_TURN idx=136
LabelAccepted: displayed="75.8" t=13:35 role=ACTUAL_LOW  reason=PROMINENT_ACTUAL_TURN idx=116
LabelAccepted: displayed="76.1" t=14:15 role=ACTUAL_LOW  reason=PROMINENT_ACTUAL_TURN idx=126
```

Five "prominent" turns inside **1.6 °F over 105 minutes**. `TemperatureExtrema.
findProminentActualTurningPoints` applies only a per-reversal hysteresis
(`ACTUAL_TURN_REVERSAL_DEGREES` = 0.75 °F) with no cap, so a flat afternoon plateau returns every
chatter turn that clears it, and `addActualTurningPointLabels` accepted all of them unfiltered.

These are *fallback* labels — the resolver's own comment says they exist only to fill the gap when a
slice has no confirmed daily actual high/low. That question ("where did the observed line peak in
this slice?") has one answer per side, so `mostExtremeTurn` now keeps the most extreme turn and logs
what it dropped. Multi-peak windows remain the daily-extrema path's job.

### Defect B — two placers bypassed collision entirely

`placeActualHighAboveCurve` never consulted `drawnLabelMetas`. Its comment justified this — *"above
the global observed peak there is only headroom"* — which is true for **the** peak and false the
moment a window has several. Worse, `placeActualLowBelowCurve` is invoked *precisely* on
`LABEL_OR_ICON_BLOCKED`, i.e. when a label already blocked the normal direction, and then drew anyway.

Both now step over blockers (`ACTUAL_EXTREME_STACK_GAP_DP`, up to `ACTUAL_EXTREME_STACK_MAX_STEPS`)
and drop the label if clearing them would push it out of the plot.

### The regression this caused, and the correction

The first cut of Defect B used "any pixel of intersection → step or drop". That failed
`TemperatureGraphLabelPlacementRobolectricTest > actual low at valley with forecast curve dipping
below…` — the ACTUAL_LOW vanished entirely rather than moving.

The rule was **stricter than anything else in the engine**. A pink ACTUAL_LOW is *supposed* to share
a valley with an amber FORECAST_LOW a couple of degrees below it; the engine has a deliberate
tolerance budget for exactly that (`MINOR_OVERLAP_HEIGHT_RATIO` = 0.30, see the two-overlap-constants
history). Both placers now call the engine's own `shouldAllowMinorOverlap` / `maxVerticalOverlap` via
a new `overlapIsWithinBudget` helper, so they tolerate exactly what the rest of the engine tolerates
and resolve only what it would resolve. Substantial stacking (ratio ≈ 1.0) is still fixed; grazing is
left alone.

### Result

`77.4°` and `75.8°`, cleanly separated. Log confirms `ActualTurnThinning: kept=123 of 3` /
`kept=103 of 2`.

---

## 2. Dominant-station label now carries the reading time

**Requested as:** `knuq <temperature>` → `knuq <temperature> @ <time the temperature was reported>`.

`DominantStationLabel.format` gained `lastReadingMs` + `zoneId`, producing `knuq 73.4° @ 5:35 pm`.

- The timestamp is `BlendContribution.lastReadingMs` — the same field the Blend tab prints in its
  "last read" column, so the two surfaces cannot drift.
- `h:mm a` is the app's established time-of-day pattern (Observations, fetch-failure indicator,
  forecast-evolution), lowercased to match the already-lowercased callsign: at 9 sp a shouted "PM"
  pulls more attention than the temperature it qualifies.
- A missing/zero timestamp drops **only** the `@ …` clause, not the label — an undated temperature
  still beats nothing. `format` returns null only when there is no station or no temperature.

Both platforms updated; desktop passes its own `zoneId`.

---

## 3. Tip Jar strings were never translated (19 locales)

`LocaleResourceParityTest > every locale has exactly the base translatable keys` had been failing
since a772b12b. Four keys — `support_development_title`, `_description`, `_button`, `_no_browser` —
shipped base-English only, so **every non-English user has been seeing the Tip Jar UI in English**.
No crash, no build error: exactly the silent-English-fallback case the test exists to catch.

Added to all 19 locales (ar bn de es fr hi in it ja ko pl pt ru th tr uk ur vi zh-rCN), inserted in
base file order between `invalid_coordinates_range` and the Bug Reporting block rather than appended,
since the locale files mirror base ordering. Each locale is now 284 keys, matching the base's 284
translatable keys.

"Tip Jar" is rendered as the natural local equivalent rather than literally — de *Trinkgeld*,
fr *Pourboire*, it *Lascia una mancia*, ja チップを送る, ko 후원하기, zh 打赏, in *Beri Tip*.
`support_development_no_browser` keeps the indexed `%1$s` in all 19 (a translator writing `%s` there
compiles fine and crashes at runtime in that one language — two sibling tests police this).

---

## 4. Verification

| Check | Result |
|---|---|
| `:shared:test` | green |
| `:app:testDebugUnitTest` | green — **0 failures** |
| `LocaleResourceParityTest` | green (was the long-standing failure) |
| `TemperatureGraphLabelPlacementRobolectricTest` | green (was broken by my first cut of Defect B) |
| `./gradlew cpdCheck` | green |
| `./gradlew assembleDebug` | succeeds — aapt accepts the new resource XML (`&amp;`, `\'`, RTL ar/ur) |
| Desktop | rebuilt via `scripts/buildStart-desktop.sh`, screenshotted clean |
| Android | `installDebug`, verified on emulator |

**New tests, each verified to fail without its fix** (by temporarily neutering the fix and re-running):

- `TemperatureActualTurningPointLabelTest > plateau with many prominent turns yields one actual high
  and one actual low` — fixture reproduces the real 5-turns-in-1.6 °F window.
- `…> plateau fixture would offer five prominent turns before thinning` — proves the fixture is not
  vacuous: asserts 3 highs + 2 lows are genuinely on offer and that no daily extreme exists, so the
  fallback actually runs.
- `ActualExtremeLabelStackingTest > two daily observed highs do not print on top of each other` —
  without the fix, fails with `labels overlap by 18.0px (budget 5.4px)`, both highs pinned at the
  identical `baselineY=97.44`.
- `DominantStationLabelTest` — 6 new cases for the reading time, pinned to a fixed `ZoneId` so the
  expected strings cannot drift with the CI machine's timezone.

**A verification mistake worth recording:** I first reported the stacking test as vacuous because it
"passed without the fix". It hadn't — `gradle -q` suppresses assertion text, and `EXIT=$?` after a
pipeline reads `grep`'s status, not Gradle's. Re-running with `-i` and checking Gradle's own exit
code showed the real failure. Check the exit code of the *build*, not the last pipe stage.

---

## 5. Follow-ups

- **Have a native speaker check the Tip Jar button label per locale.** I wrote all 19 translations
  myself. The meaning is sound, but "tip jar" is idiomatic and varies by market, and that string is
  the one users actually tap.
- The Fold was not re-verified after the reading-time change (it left `adb` mid-session).
- `GhostLineLabel` still uses the single-answer `sampleVisibleCurveY` — carried over from the
  previous commit's follow-ups list.
- The 3-day zoom retire for the dominant-station label is unit-tested but still has not been
  eyeballed on-device.

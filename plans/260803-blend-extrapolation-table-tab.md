# Blend / extrapolation table tab

**Date:** 2026-08-03
**Trigger:** "observed temp on hourly graph doesn't seem to match station list temperatures"
(reported on Samsung SM-F936U1, emulator, and desktop).

## Confirmed diagnosis (no fix in this task)

The observed dot is a blend of **forecast projections** of station readings, so it can — and does —
land above every station in the list. Reproduced exactly from both DBs.

Desktop @ 08:20, `personalStationWeight = 0.05` (95% discount, default):

| station | type | km | last read | raw | fed to blend | kind | age | weight | share |
|---|---|---|---|---|---|---|---|---|---|
| AW020 | PERSONAL | 2.22 | 08:20 | 65.0 | 65.00 | observed | 0m | 0.010105 | 9.8% |
| KNUQ | OFFICIAL | 3.82 | 08:15 | 64.4 | 64.90 | extrapolated | 5m | 0.066791 | 64.7% |
| KPAO | OFFICIAL | 6.06 | 07:47 | 64.4 | **67.05** | extrapolated | 33m | 0.022254 | 21.6% |
| KSJC | OFFICIAL | 15.91 | 08:05 | 64.4 | 65.90 | extrapolated | 15m | 0.003622 | 3.5% |
| LOAC1 | PERSONAL | 8.33 | 07:10 | 55.0 | 59.50 | extrapolated | 70m | 0.000441 | 0.4% |

→ blend **65.39** ("65.4"), max real reading **65.0**. Samsung @ 08:10 → **65.85** ("65.9") vs max
real **64.4** (+1.45°F above every station).

Mechanism, three compounding parts:

1. `ActualTemperatureSeriesBuilder.extrapolateForward` (:574) carries a stale station forward by the
   *forecast's* change over the gap. NWS ramps 63°→69° between 8–9am, so 33-min-stale KPAO enters at
   67.05° — 2.65°F no thermometer measured.
2. `timeDecayFactor` (:624) decays linearly over 3h, so 33-min-old KPAO still carries 82% weight.
3. `DEFAULT_PERSONAL_STATION_DISCOUNT = 95` cuts AW020 — nearest station, only one reporting at the
   target minute — to less weight than KNUQ. Net: **~90% of the dot's weight is extrapolated**, yet
   the point is flagged `isObservedActual = true` because `hasObserved` (:311) is set when *any*
   single contributor had a literal reading.

Second, independent divergence: the station list's own "NWS Blended" row comes from a **different
function** — `SpatialInterpolator.interpolateIDW` (raw latest readings, 1h cohort-spread filter, no
PWS discount, no extrapolation). It read 64.8 at that same 08:20 while the graph said 65.4.

**Decision (user, 2026-08-03): do not change the blend math or the PWS discount. Make the
extrapolation visible in-app instead.**

## Scope

Add a third **"Blend"** tab to the Observations window on **both** platforms — they already have the
identical two-tab layout ("Observations" / "Fetch Logs"), so the tab sits directly beside the station
list being compared against.

- Desktop: `desktop/.../ObservationsWindow.kt` (`TabRow`, `selectedTab`, :305–340)
- Android: `app/.../ui/WeatherObservationsActivity.kt` (`showTab`, `TAB_OBSERVATIONS` /
  `TAB_FETCH_LOGS`, :120–170) + its layout XML

## Design

### Where the numbers come from

The tab computes its table by calling the **same shared function that produces the dot** —
`ActualTemperatureSeriesBuilder.blendObservationSeries` — with capture enabled. It must **not**
re-derive the arithmetic in UI code: a third independent implementation is exactly the class of bug
under investigation (see the `SpatialInterpolator` divergence above).

New shared types in `ActualTemperatureSeriesBuilder.kt`:

```kotlin
data class BlendContribution(
    val stationId: String,
    val stationName: String,
    val stationType: String,       // OFFICIAL | PERSONAL
    val distanceKm: Float,
    val lastReadingMs: Long,       // anchorTs — the real reading this value derives from
    val rawTemp: Float,            // what the station actually measured
    val resolvedTemp: Float,       // what was fed to the blend
    val sourceKind: String,        // observed | interpolated | forecast_extrapolated
    val ageMs: Long,
    val decay: Float,
    val weight: Double,
)

data class BlendBreakdown(
    val targetMs: Long,
    val blendedTemp: Float,
    val sourceKind: String,        // the point's reported kind (the `hasObserved` flag)
    val contributions: List<BlendContribution>,
) {
    val weightSum: Double get() = contributions.sumOf { it.weight }
    val maxRawTemp: Float? get() = contributions.maxOfOrNull { it.rawTemp }
    /** True when the blend exceeds every real reading — the reported symptom. */
    val exceedsAllStations: Boolean get() = maxRawTemp?.let { blendedTemp > it } == true
}
```

### Capture is opt-in

`blendObservationSeries` gains `captureBreakdowns: Int = 0` (0 = off) and returns the most recent N
breakdowns on `BlendObservationResult`. The widget/desktop **render** path keeps passing 0 so the
per-minute tick pays nothing; only the Blend tab passes a cap (60).

Populate `BlendContribution` inside the existing per-candidate loop (:299–315), where `resolved`,
`ageMs`, `isPersonal` and the raw `stationObs` are all already in hand. `weight` must be read back
from the same expression `blendCandidateTemperature` uses, not recomputed alongside it — extract the
weight calculation into a small private function both call, so they cannot drift.

### Parity requirement

The tab must pass **exactly** the render path's parameters — `displaySourceId` (the window's current
source cycler), `userLat`/`userLon`, `personalStationWeight` (`config.personalStationWeight()` /
`stateManager.getPersonalStationWeight()`), and the same `hourlyForecasts` selection. If these drift,
the table will disagree with the dot and be worse than useless. Covered by a test (below).

### Rendering — as shipped

**Blend is the FIRST tab and the default** on both platforms (user, 2026-08-03), ahead of
Observations and Fetch Logs. It shows only the **current** blended point; history was dropped.

Column set fixed by the user, eight columns in this order, sorted **nearest station first** (matching
the Observations tab so the two can be read side by side):

```
 station   type   km     last read   age    raw    fed to blend   weight
 AW020     P      2.24   13:30       5m     84.0   84.17 E        12.7%
 KNUQ      O      3.83   13:15       20m    80.6   81.27 E        79.0%
 KPAO      O      6.03   10:47       168m   71.6   80.85 E         2.4%
```

- One-letter codes with a legend under the table: `O`/`P` for station type, `R`/`I`/`E` for
  real / interpolated / extrapolated. Spelled-out words made the columns wider than the window at the
  tab's enlarged font and every cell wrapped mid-word, destroying the alignment.
- Headers are deliberately short (`fed to blend`, `weight`) — at this font size the *headers*, not the
  cells, set the column widths.
- `E` rows are tinted amber: those degrees came from the forecast, not a thermometer.
- Hairline rules between rows (7% white on desktop, `#1F1F22` on Android). Deliberately barely-there:
  the table is scanned column-wise (raw vs fed-to-blend), so the rules only need to stop the eye
  drifting a row — anything stronger competes with the value colouring that carries the meaning.
- Header line flags `outside station range` in red when the blend falls outside every real reading.
- Tapping a row opens that station's NWS time-series page — same affordance as the Observations tab.
- Fonts: desktop 39sp data / 27sp header; Android 20sp data / 16sp header, with generous row padding.
  Neither uses a monospace face (user, 2026-08-03).
- Android renders real per-column views in a `TableLayout` rather than preformatted text — a
  proportional font cannot hold fixed-width columns in alignment, and real rows restore the per-row
  tap target.

`BlendTableFormatter` (shared, pure) produces the row model and the legend for both platforms, so they
cannot drift. It also exposes `renderText()` for pasting a table into a bug report.

## Tests — as built

`BlendBreakdownCaptureTest` (shared, 8 tests, all passing):

- fixture reproducing the desktop 08:20 case above
  (5 stations, the real distances/timestamps/temps, NWS forecast 63@08:00 / 69@09:00, PWS weight
  0.05) asserts blended = 65.39 ± 0.01, per-station `resolvedTemp` and `weight` match the table, and
  `exceedsAllStations` is true. This pins the diagnosis as a regression test.
- Parity test: capture-on and capture-off runs produce **identical** `observations` output — capture
  must be observationally inert.
- `captureBreakdowns = 0` yields an empty list and allocates nothing per candidate.
- Desktop `ObservationsWindowRowsTest`-style row-builder test for the new tab's row model
  (pure-function extraction, no Compose — see `testing-strategy`).
- Android: Robolectric test that the third tab shows/hides content and survives rotation via
  `STATE_SELECTED_TAB` (assert dp geometry only — `robolectric_no_font_engine`).

## Out of scope

Changing the blend math, the extrapolation, the decay curve, the 95% PWS discount, or unifying
`SpatialInterpolator` with `ActualTemperatureSeriesBuilder`. Recorded here so the options survive:

| variant | desktop 08:20 | Samsung 08:10 |
|---|---|---|
| current | 65.39 | 65.85 |
| hold last reading flat (no extrapolation) | 64.42 | 64.31 |
| extrapolate, but no PWS discount | 64.98 | 64.43 |
| max real station | 65.0 | 64.4 |

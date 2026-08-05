# Large Daily Today-Column Overlay

## Outcome

For an Android daily graph that the launcher sizes at 10 or more forecast columns and four or more
launcher rows, replace the normal 10-day layout with nine displayed days, give Today 1.25 normal day
widths, thin Today's three bars, and add a one-row delta annotation plus a two-row observation
annotation:

1. The signed temperature difference from the same time yesterday.
2. The raw reading from the dominant current-blend station and that reading's blend age,
   in minutes. Do not draw the station ID or long name.

Smaller widgets, text-mode daily views, and navigated windows that do not contain Today retain their
current layout and day count.

## Observed Baseline

Evidence captured on `emulator-5554` before implementation:

1. AppWidget ID 59 is 594 x 392 dp and resolves to 10 columns x 5 rows.
2. It is in `DAILY` graph mode, source `NWS`, date offset `-1`, zoom `WIDE`.
3. `DAILY_RENDER` showed 10 dates and `CURR_TEMP_RESULT` resolved the current blend from an observed
   value at 22:10.
4. The source rows at that time include multiple candidate stations (`AW020`, `KNUQ`, `KSJC`, and
   others). The displayed station temperature and age therefore must come from the actual
   dominant blend weight; nearest station, newest row, and the synthetic `NWS_BLEND` row are not
   valid substitutes.
5. The initial screenshot is `/tmp/weather-widget-overlay-baseline.png`. Runtime widget state was
   recorded before edits so date offset, source, view, and zoom can be restored after validation.

## Eligibility and Column Topology

Use one pure policy as the gate:

- launcher-derived columns >= 10;
- launcher-derived rows >= 4;
- daily graph mode;
- Today occurs in the visible window.

There is no preference gate. This is the requested default behavior for every sufficiently large
daily widget.

When eligible:

```text
normal 10 slots: [D1][Today][D3][D4][D5][D6][D7][D8][D9][D10]
detailed layout: [D1][ Today  ][D3][D4][D5][D6][D7][D8][D9]
```

- Prepare and display nine dates instead of ten.
- Give Today a weight of 1.25 and every other date a weight of 1. The nine dates occupy 9.25 weighted
  units, leaving enough room for the narrow three-row text while returning horizontal space to every
  other date.
- Resolve all centers and widths from this one weighted topology. Do not independently clamp the
  renderer and labels.
- RemoteViews exposes only ten equal predeclared touch zones. Bind both zones crossed by the widened
  Today column to the same action so the entire widened visual column remains tappable.
- Thin only Today's triple bars from 8 dp to 6 dp. Other daily bars are unchanged.
- Expand the frosted Today panel to the weighted Today width.

## Data Provenance

### Dominant station and age

Extend the shared blend result with lightweight metadata for the contribution having the greatest
final weight at an emitted blend point:

- station ID and name (retain both for diagnostics only; never draw either in this compact overlay);
- target timestamp;
- last reading timestamp;
- blend age (`target timestamp - last reading timestamp`);
- raw/resolved temperatures and weight share for diagnostics/tests.

`ActualsAggregator.resolveCurrentObservationDetails()` selects the same current blended point as the
existing `resolveCurrentObservation()` compatibility wrapper and returns its matching dominant
contribution. Do not infer dominance from distance, timestamp, station type, or row ordering.

The large daily path requests enough raw observations to cover yesterday, runs this same resolver,
and passes the metadata through `DailyGraphRenderer` to the bitmap renderer. The persistent DB log
gets at most one sparse overlay summary per render; placement traces remain `VERBOSE`.

### Delta from yesterday

Reuse `YesterdayDeltaCalculator.computeDelta()` and the signed numeric formatting from
`YesterdayDeltaLabel` in the hourly temperature graph:

```text
delta = current blended observation - blended observation at the same instant 24 hours earlier
```

The existing +/-90-minute qualification and interpolation rules remain authoritative. If the
yesterday value is unavailable, omit the delta. Do not fall back to yesterday's daily high: that is
not the same metric and would make the daily and hourly labels disagree.

### Text

Render a one-row delta block and a two-row contribution block from a 30 dp base, then apply a final
0.85 scale after width fitting. Applying the reduction after fitting is necessary: lowering only the
nominal base to 25.5 dp produced the same Samsung glyph height because the prior 30 dp request had
already been width-fitted to roughly that size. Draw `yest` smaller on the same baseline as the
signed delta:

```text
+3.2 yest
63.4°
15m
```

The first block uses the shared signed/Celsius delta formatter with the single-word `yest` caption
at 62% of the value size. On narrow columns, horizontally condense the rows before reducing their
height before the final 15% reduction; reserve only 1 dp at each text edge because a
5 dp inset consumes 30 bitmap pixels across Samsung's high-density panel. The second uses the
dominant station's raw temperature and the same integer-minute age shown in that station's
Blend-table row; keep its
forecast-adjusted value-fed-to-blend diagnostic-only. The station ID and long name also remain
diagnostic-only.
Pass horizontal and vertical padding to the placement planner separately so vertical clearance does
not incorrectly narrow the text-fit band.
Draw the delta value, smaller inline `yest` caption, dominant temperature, and age in opaque pure
white (`#FFFFFFFF`).

Missing metadata suppresses only its own line. It does not suppress the other line or alter the
column topology.

## Least-Cluttered Placement

Add a pure `TodayColumnOverlayPlanner` that receives the Today column bounds, graph bounds, measured
text boxes, and occupied geometry. First plan the one-row and two-row blocks separately, adding the
first chosen block as an obstacle before placing the second. If the doubled text prevents both blocks
from fitting, retry the same scoring with one grouped three-row stack so all requested information
remains visible.

Candidate locations cover all requested vertical relationships:

1. `ABOVE`: clear space above the topmost Today bar/high-label/rain-label geometry.
2. `BELOW`: clear space below the Today bars/bulb and above the icon/low/day-label stack.
3. `ON_COLUMN`: sampled positions through the bar band. These may cross the thin bars but must not
   collide with temperature labels, weather/rain labels, icon, day label, or the other overlay line.

For each candidate:

```text
score = minimum_clearance_to_hard_obstacle
      + free_band_height_bonus
      - bar_overlap_penalty
      - edge_penalty
```

- Reject candidates outside the Today panel or intersecting a hard obstacle.
- Prefer the highest-scoring free `ABOVE`/`BELOW` candidate; use `ON_COLUMN` only when it offers the
  best remaining readable location.
- Draw a dark outline/shadow behind the text so an allowed bar overlap stays legible.
- If neither independent placement nor the grouped fallback fits, omit only the block that has no
  valid placement.
- Emit `VERBOSE` placement diagnostics naming the chosen zone and bounds.

## Ownership

1. `ActualTemperatureSeriesBuilder`: weight-derived dominant contribution metadata.
2. `ActualsAggregator`: detailed current-observation result plus compatibility wrapper.
3. `DailyLargeTodayOverlayPolicy`: pure eligibility, effective day count, and visual slot mapping.
4. `DailyGraphLayoutResolver`: weighted centers/widths and compact Today-bar geometry.
5. `TodayColumnOverlayPlanner`: pure independent annotation placement and grouped-stack fallback.
6. `TodayColumnOverlayRenderer`: Canvas measurement, obstacle collection, outlined drawing, and
   placement debug result.
7. `DailyGraphRenderer`: gated observation query, shared delta computation, text construction, and
   data handoff.
8. `DailyClickHandlerFactory`: duplicate Today action across its two visual slots.

Keep `DailyForecastGraphRenderer` as the ordered facade: normalize, resolve layout, draw panel/bars/
column content, draw Today annotations, draw header/watermark, and return typed debug geometry.

## Desktop Evaluation

The desktop daily model currently caps its base view at nine columns and does not expose Android
launcher icon rows. The >=10-column eligibility condition is therefore unreachable on desktop.
No desktop behavior change is appropriate for this Android launcher-specific feature; the shared
blend metadata and delta formatter remain available to both platforms without creating a divergent
desktop-only gate.

## Verification

1. Shared unit tests prove dominant selection uses final blend weight and that metadata matches the
   exact current point returned by `ActualsAggregator`.
2. Pure app tests cover the 10x4 threshold, 10x3/9x5 non-eligibility, nine-date result, weighted slot
   centers, and two-slot Today click mapping.
3. Planner tests cover independent `ABOVE`, `BELOW`, and `ON_COLUMN` choices, collision avoidance,
   one-block-only fallback, and the grouped three-row retry.
4. Robolectric/real-Canvas tests assert the 1.25-unit Today width, 6 dp bars, three overlay rows, returned
   placement bounds, and unchanged smaller-widget output.
5. Run focused app/shared tests, duration validation, relevant broader unit lanes, `assembleDebug`,
   and `git diff --check`.
6. Install on `emulator-5554`, refresh AppWidget 59, and capture screenshot plus `VERBOSE` placement
   and sparse overlay-provenance logs. Verify the rendered dominant temperature/age against the
   Blend tab inputs and the delta against the hourly shared calculator.
7. Restore widget 59's source `NWS`, view `DAILY`, date offset `-1`, hourly offset `0`, and zoom `WIDE`
   after validation. Leave the emulator running.

## Implementation Status

- [x] Live renderer/callers/tests/prior plan and commits inspected.
- [x] Runtime size/state/database/log baseline captured.
- [x] Shared dominant-contribution contract implemented and tested.
- [x] Large-widget topology and aligned touch slots implemented and tested.
- [x] Least-cluttered annotation planner/renderer implemented and tested.
- [x] Focused and broad verification complete (`:shared:test`, all app duration buckets, focused
      renderer/click/planner tests, `assembleDebug`, and diff checks).
- [x] Emulator visual/provenance validation complete with state restored. Final evidence:
      `/tmp/weather-widget-today-overlay-four-row.png`; widget 59 remains `NWS`, `DAILY`, date offset
      `-1`, hourly offset `0`, zoom `WIDE`, and the emulator remains running.
- [x] Samsung SM-F936U1 validation complete. Its 574 x 401 dp widget exposed a width-fit regression:
      the old minimum scale left the caption wider than the Today text area, then an unsuccessful
      grouped retry discarded the otherwise valid temperature/age placement. The fitter now honors
      the available width, a failed grouped retry retains any valid independent placement, and the
      smaller `yest` caption shares the delta row. Evidence before the latest color, width, size, and
      raw-temperature adjustments:
      `/tmp/weather-widget-samsung-overlay-inline-yest.png`.
- [x] Revalidated Samsung after making all text pure white, changing Today to 1.25 widths, using
      horizontally condensed 30 dp text, and displaying the dominant station's raw
      Blend-table temperature. AppWidget 345 visibly shows `-3.6 yest`, `62.6°`, and `10m`; final
      evidence: `/tmp/weather-widget-samsung-overlay-final-white-large-narrow.png`.
- [ ] Revalidate Samsung after applying the 15% reduction to the final fitted size.

# Desktop hourly temperature graph: actual-line start + header parity with Android

## Context

Two desktop hourly-temperature-graph issues, both about matching the Android app:

1. **Actual (pink) observation line doesn't reach the left edge** of the zoomed-in graph. Android's
   line spans from the left edge; desktop's starts ~1h in. Root cause: the desktop forecast window
   and the actual-observation window don't share a start hour.
2. **Header button scheme differs from Android.** Android's hourly header has a *cycling* graph
   selector + a dedicated 🌡️ station-observations button + 🏠 home. Desktop instead has four flat
   direct-switch emojis (☁️ 🌡️ 🏠 📈) and *no* way to open station observations from the header.

(Already completed earlier this session and verified on-screen — context only, not part of this plan:
the staleness age label now uses Android's span≤12h gate + pink `COLOR_ACTUAL` + shadow + above-dot
placement; and `xAtTime` maps the data span `[firstPoint, lastPoint] → [0,w]` so the curve fills the
full width. See memories `desktop_staleness_age_label_parity`, `desktop_temp_graph_fills_full_width`.)

The user also requires **tests, including integration tests**, to prevent regressions.

---

## Part A — Actual line starts at the graph's left edge

### Why it happens
- Android `TemperatureHourDataBuilder.kt` builds forecast hours over `alignedCenter ± backHours`
  (`alignedCenter` = center truncated to the hour, rounded up if `minute >= 30`) — the *same* window
  the shared `ActualTemperatureSeriesBuilder.build` uses internally (it does the identical
  alignment). So forecast and actual series share a start hour → pink line starts at the edge.
- Desktop `TemperatureGraph.kt` filters forecast `points` over `(rawCenter − backHours − 1h)..(rawCenter + forwardHours)`
  — a `-1h` pad, and using the *raw* (unaligned) center. So its left edge sits ~1h before where the
  actual series begins, leaving the pink line short of the edge.

### Change (`desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt`)
1. Add an internal, pure, testable helper mirroring the builder's alignment exactly:
   ```kotlin
   internal data class HourWindow(val startMs: Long, val endMs: Long)
   internal fun temperatureGraphHourWindow(
       centerMs: Long, backHours: Int, forwardHours: Int, zoneId: ZoneId = ZoneId.systemDefault()
   ): HourWindow {
       val center = LocalDateTime.ofInstant(Instant.ofEpochMilli(centerMs), zoneId)
       val truncated = center.truncatedTo(ChronoUnit.HOURS)
       val alignedCenter = if (center.minute >= 30) truncated.plusHours(1) else truncated
       val startMs = alignedCenter.minusHours(backHours.toLong()).atZone(zoneId).toInstant().toEpochMilli()
       val endMs   = alignedCenter.plusHours(forwardHours.toLong()).atZone(zoneId).toInstant().toEpochMilli()
       return HourWindow(startMs, endMs)
   }
   ```
2. Replace the current `start`/`cutoff` (raw `center ∓ backHours`) and the padded `points` filter
   with this window: set `start = window.startMs`, `cutoff = window.endMs`, and filter
   `hourly.filter { it.dateTime in start..cutoff }` (no `-1h` pad). Keep the existing `.ifEmpty{…}`
   fallback. This makes `dataStart` (= `points.first().dateTime`, used by the already-added
   data-span `xAtTime`) equal the actual series' start hour, so the pink line lands at `x = 0`.
3. `windowStart`/`windowEnd` (the gating window for the NOW indicator, fetch-dot visibility, day
   labels, staleness span) become the aligned `start`/`cutoff` — consistent with the data-span
   mapping and with Android.

Net effect: the desktop hourly window becomes identical to Android's (`alignedCenter ± backHours`,
uniform top-of-hour points), so both the left edge (actual line) and right edge (forecast line) are
filled. The existing day-centering (`offsetToDayCenter` → noon) still yields midnight→midnight in
WIDE because noon is already hour-aligned.

---

## Part B — Header parity: cycling selector + station-observations button

Match Android's `setupGraphSelectorShortcut` / `setupWeatherStationsShortcut` / `setupHomeShortcut`
in the desktop `WidgetHeader` center cluster (`Main.kt`, the `if (isHourly) { Row { … } }` block,
currently ~lines 817–861). Replace the four flat emojis with three elements, left→right:

1. **Cycling graph selector** (replaces the separate ☁️/🌡️/📈). Shows the **next** view's icon and
   switches to it on tap, mirroring Android's cycle and emojis:
   | current view | icon shown | tap → |
   |---|---|---|
   | TEMPERATURE/HOURLY | ☁️ | CLOUD_COVER |
   | CLOUD_COVER | 🌧️ | PRECIPITATION |
   | PRECIPITATION | 🌡️ | HOURLY |

   `onUpdateConfig(config.copy(viewMode = nextView))`. testTag `graph_selector`. No active-state
   highlight (it always shows the *next* view). (Reuse the exact emoji codepoints from
   `setupGraphSelectorShortcut`: ☁️ `☁️`, 🌧️ `🌧️`, 🌡️ `🌡️`.)
2. **Station-observations button** 🌡️ (next to home) — *always* calls the existing
   `onOpenObservations` callback (already plumbed into `WidgetHeader` and wired in `WidgetPopup`).
   testTag `open_observations_header`.
3. **Home** 🏠 — unchanged: `viewMode = DAILY`. testTag `switch_to_daily` (keep).

The left-cluster "NN%" precip-probability tap (→ PRECIPITATION) and the top-left current-temp toggle
stay as-is. Drops the standalone 📈 (precip is reached via the cycle or the % tap, like Android).

---

## Part C — Tests (`desktop/src/test/kotlin/com/weatherwidget/desktop/`)

**Integration test — actual line reaches the edge** (new `TemperatureGraphWindowTest.kt`, plain
JUnit, exercises the real shared builder + the desktop window helper together):
- Fixed `now`/zone; hourly forecasts at every top-of-hour across ±6h; observations every ~20min in
  the past (including before the window start so the carry path applies).
- Compute `temperatureGraphHourWindow(nowMs, backHours=2, forwardHours=2, zone)` (NARROW); derive
  `points = hourly.filter { it.dateTime in start..end }`.
- Call `ActualTemperatureSeriesBuilder.build(...)` with the same `centerTime=now`, `backHours=2`,
  `forwardHours=2`. Assert `series.points.first { it.isActual && it.actualTemp != null }.timeMs ==
  points.first().dateTime` (left edge) and `points.last().dateTime == window.endMs` (right edge).
- Plus a small unit assertion that `temperatureGraphHourWindow` aligns like the builder (e.g.
  12:33 → start 11:00 / end 15:00 for NARROW; 12:20 → 10:00/14:00).

**Integration/UI tests — header** (add to existing `DesktopUiTest.kt`, Compose
`createComposeRule`, same style as `testCloudCoverToggle`):
- `headerObservationsButtonOpensObservations`: `WidgetPopup` in HOURLY, `onOpenObservations = { opened = true }`,
  click testTag `open_observations_header`, assert `opened`.
- `headerGraphSelectorCyclesViews`: HOURLY → click `graph_selector` → assert `viewMode == "CLOUD_COVER"`;
  then from CLOUD_COVER → PRECIPITATION; from PRECIPITATION → HOURLY.
- Keep `testCloudCoverToggle` (on HOURLY the selector shows ☁️ → CLOUD_COVER, so it still holds);
  adjust only if the emoji node is ambiguous.

Run: `./gradlew :desktop:test` (per memory `desktop_test_running_app_conflict`, this is safe while
the app runs).

---

## Verification
1. `./gradlew :desktop:test` — all green (new + existing).
2. `./gradlew :desktop:compileKotlin`, then `scripts/buildStart.sh`; surface the popup
   (`.show`) and screenshot via `xdotool`/`import` (window "Weather Widget"), convert to JPG.
3. Zoomed-in temperature view: pink actual line starts at the **left edge**; forecast fills to the
   **right edge** (already fixed); staleness label pink above the dot (already fixed).
4. Header: the left cluster icon is the **cycling** selector (☁️ on temperature) and cycles
   temp→cloud→rain; the 🌡️ next to 🏠 opens the **station observations** window; 🏠 returns to daily.

## Scope notes
- `CloudCoverGraph.kt` / `PrecipitationGraph.kt` share the same window/pad pattern and right-edge
  gap but are **out of scope** here (user asked about the temperature graph). Worth a follow-up.
- Desktop shows the observations 🌡️ in all hourly views; Android hides it when not "today"
  (`positionCenterIcons` `isToday`). Minor divergence, left as-is unless you want it gated.

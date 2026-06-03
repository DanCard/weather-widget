# Desktop Android Parity: Actuals Line, Graph Style, and Header

## Context

The desktop app's popup widget (`:desktop` module) needs to close three visual gaps vs. the Android widget:

1. **Hourly graph has no actuals** — the graph draws only forecast data; past hours show no observed temps
2. **Graph looks app-like, not widget-like** — solid line everywhere; Android uses a solid actual line + dashed forecast line with visual distinction
3. **Header looks app-like** — "Cycle API" / "Hourly" / "Daily" buttons break the glass aesthetic; Android shows a dominant temp, subtle API indicator, minimal chrome

---

## Part 1: Actuals on the Hourly Temperature Graph

### Data available
`ForecastResult.rawObservations: List<ObservationReading>` already carries real observed temperatures with `timestamp` (epoch ms) and `temperature` (°F). This is the same data that feeds the Android actual line — it just isn't wired to the desktop graph yet.

### Changes

**`ForecastTypes.kt` (shared model)** — no changes needed; `rawObservations` is already in `ForecastResult`.

**`TemperatureGraph.kt`** — add `observations: List<ObservationReading> = emptyList()` parameter. Inside `Canvas {}`:

1. Compute `transitionX`: the X position of the last observation that falls within the display window (observations sorted by timestamp). This is the "past/future" boundary.

2. **Past segment (actual line)**: For hours before `transitionX`, snap each hourly X to the nearest observation temperature (linear interpolation between observation timestamps), build a `Path`, draw as a **solid** line in `Color(0xFFFF3366)` (matching Android's `OBSERVED` pink), stroke width `3f`.

3. **Future segment (forecast line)**: For hours at/after `transitionX`, draw the existing Catmull-Rom curve as a **dashed** path using `PathEffect.dashPathEffect(floatArrayOf(16f, 8f))` inside a `Stroke`. Keep the gradient coloring.

4. Keep the gradient fill unchanged (it applies to the whole area and looks fine behind both lines).

**`Main.kt`** — where `TemperatureGraph(...)` is called inside `WidgetPopup()`, pass `observations = snapshot.rawObservations`.

### Visual result
Solid pink line traces the actual observed temperatures for past hours; dashed gradient line shows the forecast ahead. The transition is the "now" boundary, matching Android behavior.

---

## Part 2: Graph Visual Parity

### Remaining differences after Part 1
- Android forecast line has **weather-adaptive per-segment tinting** (night/sunny/rainy color shifts) — this is complex; defer for now.
- Android has a **ghost line** (expected forecast with applied delta at 55% alpha) — defer; desktop has no delta concept yet.
- Stroke widths: Android actual = 1.0dp (narrow), forecast = 1.0dp. Desktop currently uses 3f px for the whole line — this is fine as a design choice for a larger popup.

### Change in Part 2
Just making the forecast portion dashed (done in Part 1 above) is the biggest single visual improvement. No separate work needed here beyond Part 1.

---

## Part 3: Header Redesign

### Problems
1. `remember { LocalDateTime.now() }` freezes the date at composition time — it never updates.
2. "Cycle API" is a button label, not a widget indicator.
3. "Hourly" / "Daily" toggle buttons are app-chrome, not glass aesthetic.
4. The current temp is the second-row element, not the dominant anchor.

### Target (Android-like)
- **Top row**: Large current temp (dominant, top-left) + subtle API indicator (top-right, clickable to cycle, no button border)
- **Bottom row**: Location (small, clickable) + date (live, small right-aligned)
- **View mode toggle**: Small icon-based toggle below the graph, not in the header. Or: keep "Hourly"/"Daily" as very small text chips without button chrome. Minimal.

### Changes to `Main.kt` (`WidgetHeader` composable, lines 576–694)

1. **Fix frozen date**: Replace `remember { LocalDateTime.now() }` with a `LaunchedEffect`-driven `mutableStateOf` that ticks every minute.

2. **Top row — dominant current temp**:
   ```
   Row: [ WeatherIcon (32dp) | currentTemp (displaySmall, bold) | currentCondition (bodySmall, 70%) ]
         weight(1f)
   ```
   Then right side:
   ```
   Column(End): [ API source text (labelSmall, 50% alpha, clickable to cycle — NO button border) ]
                [ date text (labelSmall, 70% alpha) ]
   ```

3. **Bottom row — location + view mode**:
   ```
   Row SpaceBetween:
     [ location name (bodySmall, clickable) | gear icon (14dp) ]
     [ "H" "D" tiny text chips (labelSmall, no elevation, minimal padding) ]
   ```
   The Hourly/Daily chips become very small, not big buttons.

4. **Remove `SourceToggle` composable** — replace its usages with inline `Text(..., modifier = Modifier.clickable {...})` styled with a faint border only when selected.

---

## Files to Modify

| File | Change |
|------|--------|
| `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` | Add `observations` param, draw actual (solid pink) + forecast (dashed) segments |
| `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` | Pass `observations` to graph; redesign `WidgetHeader` |

No shared model changes required — `rawObservations` already exists in `ForecastResult`.

---

## Verification

1. Run `./gradlew :desktop:run` (stop the installed app first to avoid the single-instance lock)
2. Open the popup — the hourly graph should show a solid pink line for past hours
3. The past/future boundary should match the current time indicator (white vertical line)
4. The header should show the current temp as the dominant element, API source as subtle text, live date
5. The "Cycle API" button label should be gone; clicking the API text cycles the source
6. "Hourly"/"Daily" should be visually minimal chips, not full-height buttons

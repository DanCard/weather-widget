# Actual absolute-low label intermittently missing on the hourly temperature graph

## Context

On the hourly ("24 hour") temperature graph, the **actual (pink/observed) line's absolute low is
sometimes not labeled** — it appears in some views and disappears after navigating home and back to
the 24h view. The user wants the absolute low of the actual line to always be labeled, and gave the
guiding rule: **at a coincident index, the actual role should win over the forecast/boundary role.**

### Root cause (confirmed from live emulator logs, 2026-06-16)

The actual low is dropped only when its index coincides with the **right-edge endpoint** (NOW):

```
TempExtrema:      ACTUAL_EXTREMA lowIdx=288 lowTemp=60.9   (288 == hours.lastIndex, the right edge)
TempLabelResolver: Potential anchors: ...(288, ACTUAL_LOW), (288, END), (288, ACTUAL_END)...
TempLabelResolver: LabelAccepted: role=END idx=288 val=61.0
```

`resolveExtremaRole(idx, ...)` in
`shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt:355-372`
assigns a single role per index by **check order**, and `END` is tested before `ACTUAL_LOW`:

```kotlin
0 -> TemperatureRole.START                       // line 362
hours.lastIndex -> TemperatureRole.END           // line 363  ← wins at idx 288
in extrema.actualDailyHighIndices -> ACTUAL_HIGH // line 364
in extrema.actualDailyLowIndices -> ACTUAL_LOW   // line 365  ← never reached
```

So the endpoint wins, the label shows the forecast endpoint value (61.0) instead of the observed low
(60.9), and the actual-low semantic is lost. The existing rescue
`addCoincidentActuals(... ACTUAL_LOW, FORECAST_LOW_ROLES ...)` (`:233`) does **not** fire, because it
only re-injects when the surviving role is a forecast **LOW** role — `END`/`ACTUAL_END` are not in
`FORECAST_LOW_ROLES`.

This explains the intermittency: when the coldest observed point is interior, `ACTUAL_LOW` resolves
cleanly and shows; when the curve is still cooling at NOW (coldest point at the right edge), `END`
preempts it.

## Approach (recommended)

Make **actual extrema win over the boundary roles (`START`/`END`)** in the role tie-break, while
keeping forecast global extrema (`HIGH`/`LOW`) above actual so the existing dual-label injection for
forecast-vs-actual highs/lows is untouched.

### 1. Reorder `resolveExtremaRole` — `TemperatureLabelResolver.kt:355-372`

Move the two actual-extrema checks **above** the `START`/`END` boundary checks (but leave them below
`dailyHighIndex -> HIGH` / `dailyLowIndex -> LOW`):

```kotlin
extrema.dailyHighIndex -> TemperatureRole.HIGH
extrema.dailyLowIndex -> TemperatureRole.LOW
in extrema.actualDailyHighIndices -> TemperatureRole.ACTUAL_HIGH   // moved up
in extrema.actualDailyLowIndices -> TemperatureRole.ACTUAL_LOW     // moved up
0 -> TemperatureRole.START
hours.lastIndex -> TemperatureRole.END
extrema.forecastHighIndex -> TemperatureRole.FORECAST_HIGH
... (unchanged)
extrema.actualEndIndex -> TemperatureRole.ACTUAL_END
else -> TemperatureRole.LOCAL
```

Net effect at idx 288: not `dailyHigh`(180), not `dailyLow`(12) → matches `actualDailyLowIndices` →
`ACTUAL_LOW`, value `actualLabelTemps[288]` (the observed low). The right-edge label now reads the
observed low instead of the forecast endpoint.

### 2. Mirror the priority in `deduplicateAnchors` — `TemperatureLabelResolver.kt:393-428`

The `rolePriority` list (`:398-404`) decides which **index** survives when two anchors at *different*
indices collapse into the same value-slot. Mirror the reorder so a forecast/boundary anchor cannot
evict a coincident-value actual extreme at a nearby index. Move `ACTUAL_HIGH`/`ACTUAL_LOW` above
`START`/`END` (keep them below `HIGH`/`LOW`):

```kotlin
val rolePriority = listOf(
    TemperatureRole.HIGH, TemperatureRole.LOW,
    TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW,        // moved up
    TemperatureRole.START, TemperatureRole.END, TemperatureRole.ACTUAL_END,
    TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
    TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
    TemperatureRole.LOCAL,
)
```

This directly implements the user's "for dedup, actual should win over forecast" — scoped so it does
not disturb the forecast global-extreme dual-label path.

### Why not the broader change

Flipping `ACTUAL_*` above `HIGH`/`LOW` as well would suppress the forecast half of the deliberate
dual-high/low labels (`addCoincidentActuals` only injects the actual value when a forecast role is the
survivor) — regressing the past-day dual-high-label feature. Boundary-scoped is the minimal fix for
the reported bug. Shared engine is used identically by Android and desktop, so the fix lands on both.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
  - `resolveExtremaRole` (`:355-372`) — reorder actual checks above boundary checks
  - `deduplicateAnchors` `rolePriority` (`:398-404`) — mirror reorder
- Test file for the resolver (plain JUnit, `shared/src/test/.../graph/`): add a case asserting that an
  `actualDailyLowIndices` entry equal to `hours.lastIndex` resolves to `ACTUAL_LOW` (observed value),
  not `END`. Assert on role + value, not colors (renderer color stubs are 0 in plain-JUnit).

## Verification

1. Unit: `./gradlew :shared:test --tests "*TemperatureLabelResolver*"` (and any extrema/engine suite
   touching role resolution). Confirm new coincident-endpoint test passes and no dual-high-label
   regressions.
2. Build + install: `./gradlew installDebug`.
3. On the emulator, open the 24h hourly view while the temperature is cooling into the evening (actual
   low at the right edge). Capture with the project workflow:
   `adb -s emulator-5554 exec-out screencap -p > /tmp/emu.png && convert /tmp/emu.png /tmp/emu.jpg`,
   then read `/tmp/emu.jpg`. Confirm the right-edge label shows the observed low and persists across
   home→back navigation.
4. Pull logs and confirm `LabelAccepted: role=ACTUAL_LOW idx=<lastIndex>`:
   `adb -s emulator-5554 logcat -d | grep -E "ACTUAL_EXTREMA|LabelAccepted"`.
5. Desktop parity (shared engine): optionally restart desktop (`scripts/fast-desktop-restart.sh`) and
   confirm the same coincident-low case labels correctly.

## Follow-up (after implementation, outside plan mode)

Save a memory documenting: actual absolute-low label intermittently missing == `resolveExtremaRole`
boundary-before-actual ordering at the right-edge index; fix = actual wins over START/END (not over
HIGH/LOW, to preserve dual-label injection).

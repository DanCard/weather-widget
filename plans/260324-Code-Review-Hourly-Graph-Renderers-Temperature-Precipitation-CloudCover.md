# Code Review: Hourly Graph Renderers (Temperature, Precipitation, Cloud Cover)

## Context

The three hourly graph renderers (`TemperatureGraphRenderer`, `PrecipitationGraphRenderer`, `CloudCoverGraphRenderer`) and their shared utility (`GraphRenderUtils`) have grown organically with significant copy-paste duplication and divergent evolution. This review catalogs bugs, dead code, naming issues, and architectural debt.

---

## Bugs

### 1. Fetch dot Y-position uses raw values instead of smoothed values (Cloud Cover + Precip)
- **CloudCoverGraphRenderer.kt:374-378** — Interpolates `hours[fetchIdx].cloudCover` (raw), but the curve is drawn from `smoothedValues`. The dot can float off the visible curve.
- **PrecipitationGraphRenderer.kt:927-931** — Same issue with `hours[fetchIdx].precipProbability` vs the curve. (Note: Precip smoothing is currently a no-op so the bug is dormant there, but would surface if smoothing is re-enabled.)

### 2. `displaySource` parameter shadowed in PrecipViewHandler
- **PrecipViewHandler.kt:62** — `displaySource: WeatherSource` is a function parameter.
- **PrecipViewHandler.kt:126** — `val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)` shadows it. The parameter is never used.

### 3. Day label collision: CloudCover doesn't track day-vs-day overlap
- **CloudCoverGraphRenderer.kt:314-319** — `collides()` checks `drawnLabelBounds` and `drawnIconBounds` but does NOT check previously drawn day labels against each other. PrecipitationGraphRenderer has `drawnDayLabelBounds` tracking (line 842-847) that CloudCover lacks.
- Both renderers also unconditionally draw the BOTTOM fallback position without collision detection.

### 4. Precipitation smoothing is silently disabled
- **PrecipitationGraphRenderer.kt:215** — `val smoothedProbs = rawProbs` — the `smoothIterations` parameter is accepted but never applied. CloudCoverGraphRenderer correctly calls `GraphRenderUtils.smoothValues()`. This may be intentional (Bezier spline handles visual smoothing) but the parameter and variable name are misleading.

---

## Dead Code

| Item | Location | Notes |
|------|----------|-------|
| `buildJaggedPath()` | GraphRenderUtils.kt:10 | Never called from any production code |
| `localProminence()` | PrecipitationGraphRenderer.kt:1245-1254 | Superseded by `bilateralProminence()` family |
| `MIN_ICON_LABEL_SPACING_DP` | PrecipitationGraphRenderer.kt:18 | Declared, never referenced |
| `currentHourIndex` | PrecipitationGraphRenderer.kt:239 | Computed, never used (NOW position computed differently) |
| `dotLabelForDebug` | CloudCoverGraphRenderer.kt:417 | Computed, never used (IS used in PrecipRenderer) |
| `iconStrideForLabelSpacing()` | PrecipitationGraphRenderer.kt:1373-1376 | Always returns 1, making the modulo check at line 263 a no-op |

---

## Naming Issues

| Issue | Location | Suggestion |
|-------|----------|------------|
| `prob` used for cloud cover % | CloudCoverGraphRenderer.kt:248 | Rename to `cloudPct` or `coverPct` |
| `precipSmoothIterations` used for cloud cover | CloudCoverViewHandler.kt:283 | Add `cloudSmoothIterations` to ZoomLevel, or rename generically |
| `smoothedProbs` when no smoothing occurs | PrecipitationGraphRenderer.kt:215 | Rename to `probs` or actually apply smoothing |
| "original"/"truth"/"actual" used interchangeably | TemperatureGraphRenderer | Standardize on one term for observation data |

---

## Code Duplication (Architectural Debt)

### High-priority extraction candidates:

1. **Fetch dot rendering** (~150 lines x 3 renderers) — dot paint setup, age calculation/formatting, value label placement (right/left/top cascade), staleness label. Near-identical in all three renderers.

2. **Day label placement** (~60 lines x 2) — `DayCandidate` data class, bounds calculation, TOP/MIDDLE/BOTTOM cascade, collision detection. Duplicated between CloudCover and Precip (Temperature handles it differently).

3. **Paint definitions** (~50 lines x 3) — `currentTimePaint`, `hourLabelTextPaint`, `nowLabelTextPaint`, `dayLabelTextPaint`, `todayDayLabelPaint` are created identically in each renderer.

4. **Layout zone calculation** (~20 lines x 3) — Top padding, icon sizing, graph bounds calculation.

5. **`dpToPx` utility** — Duplicated in every renderer file. Could live in GraphRenderUtils.

### Suggested extraction target: `GraphRenderUtils.kt`
All of the above could be extracted as utility functions in the existing `GraphRenderUtils`, avoiding the need for a base class (which would fight the current `object` singleton pattern).

---

## Over-Engineering

- **PrecipitationGraphRenderer label placement**: ~530 lines (lines 296-828) with 7 priority levels, bilateral prominence, soft dip detection, shoulder peak dropping, plateau centering with parabolic interpolation, multiple de-clutter rules. CloudCover achieves acceptable results in ~70 lines. The complexity may be justified for precipitation (where precise label placement matters more), but the 15+ `Log.d` calls suggest ongoing instability.

- **Performance instrumentation in production**: PrecipitationGraphRenderer has `Log.d("PrecipGraphPerf", ...)` calls that fire unconditionally. Should be gated behind `BuildConfig.DEBUG`.

---

## Design Inconsistency

- **X-axis spacing**: Temperature uses epoch-based spacing (respects time gaps), while CloudCover and Precipitation use index-based spacing (uniform, ignores gaps). This means missing hours in data produce different visual artifacts across graph types.

---

## Documentation Bug

- **CLAUDE.md** references `HourlyTemperatureGraphRenderer.kt` — this file does not exist. The actual file is `TemperatureGraphRenderer.kt`.

---

## Recommended Priority Order

1. **Fix bugs** (#1-4 above) — especially the fetch dot off-curve issue and `displaySource` shadowing
2. **Remove dead code** — low risk, immediate cleanup
3. **Fix naming issues** — improves maintainability
4. **Extract fetch dot rendering** into GraphRenderUtils — highest-value duplication reduction
5. **Extract day label placement** — second-highest duplication
6. **Extract shared paints** — reduces boilerplate
7. **Gate debug logging** behind `BuildConfig.DEBUG`
8. **Update CLAUDE.md** — fix `HourlyTemperatureGraphRenderer` reference

## Verification

- Run unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
- Run instrumented tests: `./scripts/run-emulator-tests.sh`
- Visual check: install on emulator, verify all three graph types render correctly at multiple zoom levels

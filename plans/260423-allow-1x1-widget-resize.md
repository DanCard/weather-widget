# Allow Widget to Resize to 1x1; Hide Yesterday/API/Gear Based on Icon Width

## Context

The weather widget currently blocks 1-icon-wide resizing (`minWidth="110dp"` / `minResizeWidth="110dp"`) per an older plan in `conductor/1-row-daily-widget-optimizations.md`. The user now wants 1×1 allowed, and wants the following controls hidden whenever the widget is **1 launcher-icon wide** (physical width), regardless of how many day-data columns the widget ends up rendering:

- Yesterday column
- API source indicator (top-right NWS/Open-Meteo toggle)
- Gear/settings icon

**Key distinction from the previous plan draft:** "1 icon wide" is a **physical widget-width** signal (how many launcher grid slots the widget occupies), NOT a count of day-data columns. A 1-icon-wide widget may still render 2 day-data columns (today + tomorrow) — we just don't want yesterday in it. The current `numColumns` variable in the code conflates these two concepts; this plan decouples them.

Naming convention: CLAUDE.md describes sizes as **rows × cols** (e.g. "1x3" = 1 row × 3 cols). "2x1" = 2 rows × 1 col.

## Two Separate Signals

| Signal | Meaning | Source | Used For |
|---|---|---|---|
| `numColumns` (existing) | How many day-data columns to render | `cols = round((widthDp + 15) / CELL_WIDTH_DP=70)` in `WidgetSizeCalculator.kt:50` | Selecting how many day cards fit horizontally. **Unchanged.** |
| `isIconWidth` (new) | Is the widget physically 1 launcher icon wide? | `widthDp <= ICON_WIDTH_THRESHOLD_DP` (proposed 130dp, see note) | Hiding yesterday + API + gear |

**Why 130dp?** Typical launcher cell widths are 65–100dp (Pixel ≈ 72dp). A widget resized to 1 cell usually reports `minWidth` around 65–100dp. Two cells reports ~140–200dp. 130dp cleanly separates "1 icon" from "2 icons" across common launcher grid sizes. Threshold is exposed as a single constant — easy to tune.

**Decoupling consequences:**
- A widget at `widthDp=95dp`: `numColumns=1`, `isIconWidth=true` → 1 day col (today), no yesterday/API/gear.
- A widget at `widthDp=125dp`: `numColumns=2`, `isIconWidth=true` → 2 day cols (today + tomorrow), no yesterday/API/gear. ← this is the user's "2 columns of day data at 1 icon wide" case.
- A widget at `widthDp=160dp`: `numColumns=2`, `isIconWidth=false` → 2 day cols (today + tomorrow), show API/gear.
- A widget at `widthDp=200dp`: `numColumns=3`, `isIconWidth=false` → 3 day cols (yesterday, today, tomorrow), show API/gear.

Existing `numColumns == 2` behavior already shows `today + tomorrow` and omits yesterday at the data-col level (`DailyViewLogic.kt:112-121`). So no change to `prepareTextDays` day-selection is required for yesterday hiding at 1 icon wide — `isIconWidth` in those cases already implies `numColumns <= 2`, which already excludes yesterday. The `isIconWidth` flag is still the authoritative semantic signal; we'll use it to gate API/gear visibility, and it keeps yesterday-hiding intent-aligned even if launcher edge cases drift.

## Critical Files

- `app/src/main/res/xml/weather_widget_info.xml` — resize constraints
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetSizeCalculator.kt` — add `isIconWidth` to `WidgetDimensions`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` — hide controls, skip bitmap header
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — already null-guards header at line 278; no change
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` (and friends: `TemperatureViewBinder.kt`, `TemperatureTouchTargets.kt`)
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`

Read-only / confirmed already correct:
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt:112-121` — yesterday already excluded when `numColumns <= 2`.
- `app/src/main/java/com/weatherwidget/util/NavigationUtils.kt:80-83` — `getDayOffsets` already excludes yesterday for `numColumns <= 2`.

## Implementation

### Step 1 — Allow 1-icon resize

`app/src/main/res/xml/weather_widget_info.xml`:

```xml
android:minWidth="40dp"          <!-- was 110dp -->
android:minResizeWidth="40dp"    <!-- was 110dp -->
```

Leave `minHeight="80dp"`, `minResizeHeight="40dp"`, `targetCellWidth="2"`, `targetCellHeight="2"` as-is.

**Samsung caveat (document in commit message):** Per `MEMORY.md` `samsung_honeyspace_targetcell` entry, Samsung OneUI inflates `minResizeWidth` by ~100dp before dividing by cell size. Samsung users will likely still be blocked below 2 cells even with `minResizeWidth=40dp`. We accept launcher-specific behavior.

### Step 2 — Add `isIconWidth` to `WidgetDimensions`

`app/src/main/java/com/weatherwidget/widget/handlers/WidgetSizeCalculator.kt`:

```kotlin
private const val ICON_WIDTH_THRESHOLD_DP = 130  // widget <= this dp is considered 1 launcher icon wide

data class WidgetDimensions(
    val cols: Int,
    val rows: Int,
    val widthDp: Int,
    val heightDp: Int,
    val isIconWidth: Boolean,  // NEW
)
```

In `getWidgetSize(...)` (lines 30-54), compute and include:
```kotlin
val isIconWidth = width <= ICON_WIDTH_THRESHOLD_DP
return WidgetDimensions(cols, rows, width, height, isIconWidth)
```

### Step 3 — DailyViewHandler: hide API + gear when `isIconWidth`

`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`:

After line 190 (`val numColumns = dimensions.cols`):
```kotlin
val isIconWidth = dimensions.isIconWidth
```

**Guard touch-setup calls** at lines 208, 209, 252, 426:
- `setupCurrentTempToggle(...)` — keep unconditional; current temp stays tappable.
- `setupSettingsShortcut(...)` — wrap `if (!isIconWidth)`.
- `setupApiToggle(...)` — wrap `if (!isIconWidth)`.

**Graph-mode path (baked bitmap header):** Where `headerRenderData` is constructed (lines ~595-609), wrap so `headerRenderData = null` when `isIconWidth`. `DailyForecastGraphRenderer.kt:278` already guards `if (headerData != null)`, so passing null cleanly removes the API text and gear from the bitmap. No renderer change needed.

**Graph-mode RemoteViews (`setGraphModeViews`, lines 692-707):** add `isIconWidth: Boolean = false` parameter. When `isIconWidth`, force `api_source_container`, `api_touch_zone`, `settings_icon`, `settings_touch_zone` to `View.GONE` instead of `VISIBLE`. Pass `isIconWidth` from the call site.

**Text-mode RemoteViews (`setTextModeViews`, line 709; `setSingleRowControlsVisible`, line 746):** add `isIconWidth: Boolean = false` parameter to `setTextModeViews`; internally pass `!isIconWidth` into `setSingleRowControlsVisible` so the `text_mode_*` API/gear variants are `GONE` when narrow.

**Warning path (line 252):** guard `setupApiToggle(...)` with `if (!isIconWidth)`; the source-warning body uses different ids and remains visible.

**Yesterday column:** no change. `DailyViewLogic.prepareTextDays` already excludes yesterday at `numColumns <= 2`, which covers every `isIconWidth=true` case (since a 1-icon-wide widget will not exceed `numColumns=2` under the existing `CELL_WIDTH_DP=70` formula).

### Step 4 — Non-Daily handlers: hide API + gear when `isIconWidth`

For each: `TemperatureViewHandler.kt`, `PrecipViewHandler.kt`, `CloudCoverViewHandler.kt`:

1. Compute `val isIconWidth = dimensions.isIconWidth` after reading `dimensions`.
2. Guard `setupApiToggle(...)` and `setupSettingsShortcut(...)` calls with `if (!isIconWidth)`.
3. When `isIconWidth`, force these to `View.GONE` in the handler's view update:
   - `R.id.api_source_container`, `R.id.api_touch_zone`
   - `R.id.settings_icon`, `R.id.settings_touch_zone`
   - `R.id.text_mode_api_source_container`, `R.id.text_mode_api_touch_zone`
   - `R.id.text_mode_settings_icon`, `R.id.text_mode_settings_touch_zone`

Anchor line numbers for setup calls (from grep):
- `PrecipViewHandler.kt:94, 156`
- `CloudCoverViewHandler.kt:143, 208`
- `TemperatureViewHandler.kt` / related: follow `TemperatureTouchTargets.setupApiToggle` usages.

If any of these handlers bakes API/gear into a bitmap header (similar to Daily's graph mode), mirror the `headerData = null` pattern. Quick check: grep each handler for `settingsIconRes` or `apiSourceText` usage before finalizing.

## Verification

1. Build: `./gradlew installDebug` (Java 21 required).
2. On a Pixel emulator (`Medium_Phone_API_36`), place widget, resize through these shapes:
   - **1×1 (widthDp ≈ 70-95dp, isIconWidth=true, numColumns=1):** Daily view, today only, no yesterday/API/gear. Current temp visible and tappable.
   - **2×1 (widthDp same as above, 2 rows tall):** same hiding rules; graph mode may kick in if `rawRows ≥ 2.2`. Bitmap has no API/gear baked in.
   - **3×1 (widthDp same, 3 rows tall):** graph mode with null header; no API/gear.
   - **1×2 (widthDp ≈ 120-130dp, still isIconWidth=true, numColumns=2):** text mode shows today + tomorrow, no yesterday/API/gear. This is the "2 data cols at 1 icon wide" case.
   - **1×3 (widthDp ≈ 200dp, isIconWidth=false, numColumns=3):** yesterday visible, API + gear visible. Regression check.
   - **2×3, 3×4:** graph mode with full header. Regression check.
3. Switch view mode (tap current temp cycles view) at 1×2 to Temperature → confirm API + gear still hidden in Temperature handler. Repeat for Precipitation, Cloud Cover via their respective tap paths.
4. Resize large → small → large. After shrinking, tap where the gear was: should do nothing (guarded `setupSettingsShortcut` means no PendingIntent wired at narrow widths). After expanding back, gear tap should re-engage (PendingIntent re-wired on next widget update).
5. Inspect `adb logcat -d | grep 'DAILY_RENDER\|WidgetSizeCalculator'` — confirm `widthDp` and the new `isIconWidth` signal match visual state. (Add a brief log line in `DailyViewHandler` for this.)
6. Unit tests: `./gradlew testDebugUnitTest` — existing `DailyViewLogic` and handler tests should still pass (no logic change to day-data selection).
7. **Optional** Samsung device: verify the 1-icon-wide resize is blocked by the launcher (expected per MEMORY note). Widget still works at 2 icons wide.

## Open Threshold Question

The `ICON_WIDTH_THRESHOLD_DP = 130dp` is a heuristic. On launchers with unusually wide cells (tablets with ~100dp cells), a 1-cell widget may report ~95dp and correctly trip the threshold. On tiny-cell launchers (≤ 65dp cells), a 2-cell widget may report ~140dp and narrowly miss. If real-device testing shows the threshold misfires, the constant is a single line to tune. Alternative: make the threshold == `2 * CELL_WIDTH_DP - margin = 125dp` for tight coupling with the existing cols formula. Either is acceptable; pick one and note the trade-off in the commit.

## Out of Scope

- Designing a dedicated compact 1×1 layout (font sizing, icon placement refinements). Existing layout will reflow acceptably with elements hidden.
- Changing the `CELL_WIDTH_DP=70` cols formula.
- Fighting Samsung's `minResizeWidth` inflation.
- Backend / data-pipeline changes.

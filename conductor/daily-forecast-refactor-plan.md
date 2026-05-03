# Daily Forecast Graph Renderer Refactor

## Objective
Refactor `DailyForecastGraphRenderer.kt` to fix a critical Android shared-state bug, improve thread safety, optimize performance by reducing DP-to-PX conversions, and clean up the architecture by extracting Header and Rain Label rendering logic.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **New File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastHeaderRenderer.kt`
- **New File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastRainLabelRenderer.kt`

## Implementation Steps

### 1. Fix Mutable Shared State
- Call `.mutate()` on all `Drawable` instances fetched via `ContextCompat.getDrawable()` before calling `setBounds()` or `setTint()`. This prevents the widget from permanently altering the default state of the icons across the entire app.

### 2. Improve Thread Safety for Paint Caches
- Group `cachedPaintSet` and its associated key into a single immutable `PaintCache` data class.
- Group `cachedHeaderPaints` and its associated key into a `HeaderPaintCache` data class.
- Update both atomically using a single `@Volatile` reference.

### 3. Optimize DP-to-PX Conversions
- Pass `density` (from `context.resources.displayMetrics.density`) through `LayoutInfo`.
- Introduce a lightweight extension function `Float.dp(density: Float)` or `Int.dp(density: Float)` to replace multiple calls to `TypedValue.applyDimension()`.

### 4. Code Smells & Naming
- Rename `COLOR_HEX_HEADER` back to `HEADER_TEXT_COLOR` and remove any redundancy.
- Replace dynamic `Color.parseColor("#...")` defaults in `HeaderRenderData` with compile-time integer literals (e.g., `0xFF6B35.toInt()` instead of `Color.parseColor("#FF6B35")`) to prevent runtime string parsing overhead.

### 5. Architectural Extraction
- Change the visibility of `LayoutInfo`, `PaintSet`, and `DayData` to `internal` so they can be accessed within the `com.weatherwidget.widget` package.
- Move header-specific data (`HeaderRenderData`, `HeaderPaintSet`) and functions (`drawHeader`, `getHeaderPaintSet`) into a new object: `DailyForecastHeaderRenderer`.
- Move rain label data (`RainAboveHighPlacement`, `NightTuckParams`, `NightHorizontalFit`) and functions (`drawDailyRainLabel`, `drawNightRainLabel`, `resolveRainAboveHighPlacement`, `resolveNightAnchorBaseline`, `resolveNightHorizontalFit`, `createScaledRainPaint`) into a new object: `DailyForecastRainLabelRenderer`.
- Update `DailyForecastGraphRenderer` to delegate to these new objects during the draw phase.

## Verification & Testing
- Run `./scripts/emulator-tests.sh` to ensure no visual regressions exist.
- Specifically verify that widget resizing and UI updates do not crash or cause threading issues.
- Ensure that the Header and Rain labels appear exactly as they did before the refactoring.
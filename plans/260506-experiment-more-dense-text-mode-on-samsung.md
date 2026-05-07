# Fix text-mode low-temp clipping on Samsung

## Context

In text-mode (single-row) layouts, Samsung's One UI launcher allocates a 1×6 widget cell at ~80dp tall, while Pixel/AOSP gives the same widget ~100dp+. The four stacked elements per day (label 18sp + icon 30dp + high 22sp + low 18sp) plus default `includeFontPadding=true` overhead need ~100dp. With `gravity="center"`, the bottom element — the low temp — clips on Samsung but renders fine on Pixel/emulator.

Confirmed via `dumpsys appwidget` on Samsung: `appWidgetMinHeight=80, semAppWidgetRowSpan=1, appWidgetSizes=[574x80.8]`. Confirmed visually in `/tmp/samsung.jpg` (low temp clipped) vs `/tmp/emulator.jpg` (everything visible).

Goal: low temp visible across all Samsung 1-row sizes without regressing the Pixel/emulator look.

## Approach

Two layered changes, smallest first.

### Step 1 — Recover ~12-15dp via `includeFontPadding=false` (XML)

**File**: `app/src/main/res/layout/widget_weather.xml`

Add `android:includeFontPadding="false"` to every TextView in each of the 8 day blocks (`day1` through `day8`):
- `dayN_label` (line 33-39 for day1, equivalent for day2…day8)
- `dayN_high` (line 50-57 for day1, equivalent for day2…day8)
- `dayN_low` (line 59-65 for day1, equivalent for day2…day8)
- `dayN_rain` (line 67-75 for day1, equivalent for day2…day8)

That's 32 attribute additions across the 8 day blocks (lines 33-462). Pattern is already used elsewhere in the file (e.g., `current_temp` at line 1013).

No Kotlin changes here. No API guards. Zero render cost.

### Step 2 — Adaptive icon size by widget heightDp (Kotlin)

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`

#### 2a. Plumb `heightDp` into `populateDay`

`updateTextMode()` (line 804) is called from a context that has `dimensions: WidgetDimensions` in scope (called from line 593-600 in the text-mode branch where `dimensions` is bound). Add `heightDp: Int` parameter to both `updateTextMode()` (line 804) and `populateDay()` (line 854). Pass `dimensions.heightDp` from the call site at line 593-600.

#### 2b. Choose icon-bitmap size from heightDp

Add a private helper in `DailyViewHandler.kt`:

```
private fun iconSizeDpForHeight(heightDp: Int): Int = when {
    heightDp >= 95 -> 30  // current default — Pixel, tablets, multi-row
    heightDp >= 80 -> 24  // Samsung 1-row tight case
    else           -> 20  // very compact, sub-80dp grids
}
```

#### 2c. Render the icon as a Bitmap at the chosen size

User chose the all-API bitmap path (uniform code, no API-31 branch).

Add a private helper. Pattern matches existing `ContextCompat.getDrawable().mutate() + Canvas` usage in `DailyForecastGraphRenderer.kt`:

```
private fun renderIconBitmap(
    context: Context,
    @DrawableRes resId: Int,
    sizeDp: Int,
    @ColorInt tintColor: Int  // 0 = no tint
): Bitmap {
    val sizePx = (sizeDp * context.resources.displayMetrics.density).roundToInt()
    val drawable = ContextCompat.getDrawable(context, resId)!!.mutate()
    if (tintColor != 0) {
        DrawableCompat.setTint(drawable, tintColor)
    } else {
        DrawableCompat.setTintList(drawable, null)
    }
    drawable.setBounds(0, 0, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.draw(Canvas(bmp))
    return bmp
}
```

#### 2d. Replace the resource-based icon binding in `populateDay`

Replace lines 862-874 (the `setImageViewResource` + tint branching block) with:

```
val iconRes = data.iconRes
val tintColor: Int = when {
    WeatherIconMapper.isPrecipitation(iconRes) || WeatherIconMapper.isMixed(iconRes) -> 0
    WeatherIconMapper.isSunny(iconRes) -> context.getColor(R.color.sunny_yellow)
    else -> context.getColor(R.color.weather_icon_tint_default)
}
val iconBmp = renderIconBitmap(context, iconRes, iconSizeDpForHeight(heightDp), tintColor)
views.setImageViewBitmap(ids.icon, iconBmp)
views.setViewVisibility(ids.icon, View.VISIBLE)
```

The XML 30dp×30dp `ImageView` becomes a max-size container; smaller bitmaps render centered (default `scaleType` = `fitCenter`).

### What NOT to do (deferred)

- FrameLayout overlap of icon with label/high — too invasive, likely unnecessary.
- Adaptive text-size shrinking — user chose the simpler scope; revisit if needed after install.
- API-31 `setViewLayoutHeight` branch — user chose uniform bitmap path; minSdk=26 means this stays simpler.

## Critical files

- `app/src/main/res/layout/widget_weather.xml` — Step 1 (32 edits across lines 33-462)
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` — Step 2 (signature changes, helpers, populateDay swap)

## Existing patterns to reuse

- `DailyForecastGraphRenderer.kt` — `ContextCompat.getDrawable().mutate()` + `Canvas` rendering
- `DailyHeaderBinder.kt:68`, `TemperatureViewHandler.kt:234` — `setTextViewTextSize` precedent (not used in this plan, but confirms RemoteViews-runtime-sizing precedent in the codebase)
- `WeatherIconMapper.isPrecipitation/isMixed/isSunny` — already imported and used in `populateDay`
- `WidgetDimensions.heightDp` from `WidgetSizeCalculator.kt:16-22` — already plumbed to the call site

## Verification

1. Build: `./gradlew installDebug`
2. Per CLAUDE.md screenshot rule (Samsung wireless ADB corrupts piped binaries):
   ```
   adb -s <device> shell screencap -p /sdcard/screen.png
   adb -s <device> pull /sdcard/screen.png /tmp/<device>.png
   convert /tmp/<device>.png /tmp/<device>.jpg
   ```
3. Compare screenshots:
   - **Samsung 1×6 (~80dp)**: low temp fully visible, icon ~24dp.
   - **Pixel 1×6 / emulator wider row (~100dp+)**: indistinguishable from current — icon still 30dp.
   - **Any sub-80dp grid (rare)**: 20dp icon, low still visible.
4. Visual sanity: graph mode (multi-row) untouched — Step 2 only changes `populateDay` which only runs in text mode.
5. App-logs DB check (optional): query `app_logs` for any new errors during widget update tick — `python3 scripts/backup_databases.py` + `sqlite3` query per CLAUDE.md memory.

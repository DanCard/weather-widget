# 1-Row Daily Widget Refinements (v2)

## Objective
Address user feedback for the 1-row text mode widget:
1.  Disable the touch zones that open the temperature graph.
2.  Fix precipitation icons to show their natural colors (blue raindrops) by removing the overriding grey tint.
3.  Reduce the right-side gap to save space.

## Background & Motivation
- **Touch Zones:** Tapping the left side of the widget navigates to the temperature graph because `current_temp_zone` and `precip_touch_zone` remain active. We must hide them in Text Mode.
- **Icon Colors:** In Text Mode, the icons are tinted grey by the XML (`android:tint`). This overrides the natural colors of the weather icons (like blue raindrops in `ic_weather_rain`). We need to remove the XML tint and manage it in code so that we can leave precipitation icons un-tinted.
- **Right Gap:** The `72dp` padding is too large. Reducing it to `40dp` will provide a better balance between space and information.

## Scope & Impact
1.  **widget_weather.xml:** Remove `android:tint` from all `day1_icon` through `day7_icon`.
2.  **DailyViewHandler.kt (setTextModeViews):** Add `View.GONE` for `current_temp_zone` and `precip_touch_zone`.
3.  **DailyViewHandler.kt (populateDay):** Update tint logic to apply colors only to non-precipitation icons, and explicitly clear the tint for precipitation icons.
4.  **DailyViewHandler.kt (updateWidget):** Reduce `paddingEndPx` from `72` to `40`.

## Implementation Steps
1.  **Modify `widget_weather.xml`:**
    Remove `android:tint="@color/widget_text_secondary"` from `day1_icon`, `day2_icon`, `day3_icon`, `day4_icon`, `day5_icon`, `day6_icon`, and `day7_icon`.
2.  **Modify `DailyViewHandler.kt` - Touch Zones:**
    In `setTextModeViews`, add:
    ```kotlin
    views.setViewVisibility(R.id.current_temp_zone, View.GONE)
    views.setViewVisibility(R.id.precip_touch_zone, View.GONE)
    ```
3.  **Modify `DailyViewHandler.kt` - Icon Tinting:**
    In `populateDay`, replace the tint logic:
    ```kotlin
    if (!WeatherIconMapper.isPrecipitation(iconRes) && !WeatherIconMapper.isMixed(iconRes)) {
        val tintColor = if (WeatherIconMapper.isSunny(iconRes)) {
            context.getColor(R.color.sunny_yellow)
        } else {
            context.getColor(R.color.weather_icon_tint_default)
        }
        views.setInt(ids.icon, "setColorFilter", tintColor)
    } else {
        // Clear tint for precipitation icons to show natural colors (blue raindrops)
        // views.setInt(ids.icon, "setColorFilter", 0) // Often works to clear filter
        // Better: use setImageViewTintList(..., null) if possible, or just set filter to 0
        views.setInt(ids.icon, "setColorFilter", 0)
    }
    ```
4.  **Modify `DailyViewHandler.kt` - Padding:**
    In `updateWidget` (Text Mode), change `72` to `40`.

## Verification & Testing
- Deploy to emulator.
- Tap the far left; ensure no navigation to graph.
- Verify rain icons show blue raindrops (natural color).
- Verify sunny icons are still yellow and cloudy icons are still grey.
- Verify the right gap is reduced.
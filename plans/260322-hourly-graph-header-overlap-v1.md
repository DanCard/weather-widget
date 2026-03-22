# Fix Header Overlap on Narrow Devices

## Context
On narrow devices (e.g., Pixel with 3-column widget, ~225dp usable width), the top row of the hourly temperature graph has overlapping elements. The layout uses `FrameLayout` with gravity-based positioning and fixed margins — nothing prevents overlap. The total content demand (~350dp) far exceeds available space (~225dp). Three targeted changes reclaim ~60-80dp to eliminate the overlap.

## Changes

### 1. Reduce API-to-settings gap
**File:** `app/src/main/res/layout/widget_weather.xml`

- `api_source_container` (line 786): `marginEnd` 56dp → 32dp
- `api_touch_zone` (line 813): `marginEnd` 44dp → 20dp (maintains same relative offset to the container)

Savings: ~24dp

### 2. Reduce current temp font on narrow widgets
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`

Add dynamic font sizing for `current_temp` after setting the text (around lines 491-492 and 767-768), following the existing pattern used for `api_source` at lines 969-975:

```kotlin
val tempTextSizeSp = if (numColumns <= 3) 22f else 26f
views.setTextViewTextSize(R.id.current_temp, TypedValue.COMPLEX_UNIT_SP, tempTextSizeSp)
```

Apply at both locations where `current_temp` text is set (graphical mode ~491 and text mode ~767). `numColumns` is already available in scope at both sites.

Savings: ~8-10dp

### 3. Shrink precip paddingEnd
**File:** `app/src/main/res/layout/widget_weather.xml`

- `precip_probability` (line 771): `paddingEnd` 48dp → 16dp

This 48dp was a static anti-collision buffer against the API indicator. With fix #1 shifting the API indicator closer to settings, and the reduced font sizes, 16dp provides sufficient breathing room.

Savings: ~32dp

## Files Modified
1. `app/src/main/res/layout/widget_weather.xml` — margins and padding (fixes 1 & 3)
2. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` — dynamic temp font sizing (fix 2)

## Verification
1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Test on Pixel device — verify header elements no longer overlap on 3-column widget
3. Test on wider widget (4+ columns) — verify 26sp temp font still used, layout looks normal
4. Verify API indicator and settings gear remain tappable (touch zones adjusted proportionally)
5. Run existing unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "com.weatherwidget.util.HeaderPrecipCalculatorTest"`

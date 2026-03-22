# Header Overlap Analysis — Narrow Devices

## Problem
On narrow devices (e.g., Pixel), the top row of the hourly temperature graph has overlapping fields. The widget uses a `FrameLayout` with gravity-based positioning — no constraint system to prevent overlap. Every element is independently positioned with `layout_gravity` and fixed margins.

## Top Row Layout (Left to Right)

### 1. Current Weather Container (`current_weather_container`) — top|start
**File:** `widget_weather.xml:728-777`
- `LinearLayout`, horizontal, `marginTop="-4dp"`
- Children:
  - **Weather icon** (`weather_icon`): 24x24dp, `marginEnd="2dp"`, starts GONE
  - **Current temp** (`current_temp`): 26sp, fixed — no dynamic sizing
  - **Delta badge** (`current_temp_delta`): 14sp, `marginStart="4dp"`, starts GONE
  - **Precip probability** (`precip_probability`): 26sp (scaled 60-100% by probability value), `marginStart="8dp"`, **`paddingEnd="48dp"`** (hardcoded anti-collision buffer), starts GONE

### 2. Center Icons — top|center_horizontal
**File:** `widget_weather.xml:818-854`
- Home icon: 26x26dp, dead center, `marginTop="4dp"`
- Stations icon: 26x26dp, `marginEnd="44dp"` (left of center)
- History icon: 26x26dp, `marginStart="44dp"` (right of center)
- Each has 48x48dp touch zone; all start GONE

### 3. API Source (`api_source_container`) — top|end
**File:** `widget_weather.xml:779-804`
- `FrameLayout`, `marginEnd="56dp"`, height 48dp, `paddingStart="8dp"`, `paddingEnd="6dp"`
- API text (`api_source`): 11sp in XML, dynamically set to 14/16/18sp based on `numRows` (TemperatureViewHandler:969-975)
- Touch zone (`api_touch_zone`): 112x96dp, `marginEnd="44dp"`

### 4. Settings Icon (`settings_icon`) — top|end
**File:** `widget_weather.xml:893-916`
- 18x18dp, `marginTop="6dp"`, `marginEnd="6dp"`
- Touch zone: 44x44dp

## Space Budget on Narrow (3-col widget, ~225dp usable)

| Region | Approximate Width |
|--------|------------------|
| Left: icon + temp + delta + precip + padding | ~170dp+ |
| Center: home/stations/history band | ~114dp |
| Right: API (at end-56dp) + settings (at end-6dp) | ~70dp |
| **Total demand** | **~350dp+** |
| **Available** | **~225dp** |

## Font Size Summary

| Element | XML Default | Dynamic Override |
|---------|------------|-----------------|
| `current_temp` | 26sp | **None** (fixed!) |
| `current_temp_delta` | 14sp | None |
| `precip_probability` | 26sp | Scaled 60-100% of 26sp by precip value (`HeaderPrecipCalculator.getPrecipTextSize`) |
| `api_source` | 11sp | 14/16/18sp based on numRows (`TemperatureViewHandler:969-975`) |

## Key Code Paths

- **Current temp set:** `TemperatureViewHandler.kt:491-492` and `:767-768`
- **Precip text + size set:** `TemperatureViewHandler.kt:518-522`, uses `HeaderPrecipCalculator.getPrecipTextSize()`
- **API text size set:** `TemperatureViewHandler.kt:969-975`
- **Temp formatting:** `CurrentTemperatureResolver.kt:231-240` — drops decimal for 1-col, keeps it for 2+ cols
- **Precip size scaling:** `HeaderPrecipCalculator.kt:38-46` — scales 0.6x to 1.0x of 26sp based on probability

## Selected Fixes (Ideas 1, 2, 4)

### Fix 1: Reduce API-to-settings gap
- `api_source_container`: `marginEnd` 56dp → ~32dp (save 24dp)
- `api_touch_zone`: `marginEnd` 44dp → ~20dp (maintain relative offset)

### Fix 2: Reduce current temp font on narrow
- Currently 26sp with no dynamic override
- Add column-aware sizing in `TemperatureViewHandler` (pattern already exists for `api_source`)
- Suggested: 22sp for cols ≤ 3, 26sp for wider

### Fix 4: Shrink precip paddingEnd
- Currently `paddingEnd="48dp"` in XML — static anti-collision buffer
- Reduce to ~16dp or make it column-aware
- Consider also capping `getPrecipTextSize` on narrow widgets

## Key Files to Modify

- `app/src/main/res/layout/widget_weather.xml` (margins, padding)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` (dynamic font sizing)
- `app/src/main/java/com/weatherwidget/util/HeaderPrecipCalculator.kt` (optional: column-aware text size)

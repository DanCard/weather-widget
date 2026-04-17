# Plan: Windy Icons Brainstorming and Experiment Gallery

Add a "long list" of alternative wind icons to an "experiment" section in the Settings view to allow for visual comparison and selection of a replacement for the current "crappy" wind icon.

## Objective
- Brainstorm and implement 9 alternative wind icon designs.
- Add these icons to an "Icon Experiment" section in the `SettingsActivity`.
- Keep the original wind icon in the main gallery for comparison.

## Proposed Icon Designs
1. **Classic Gusts**: Traditional 3 horizontal lines with loops on the ends.
2. **Dynamic Swirls**: Overlapping spiral shapes representing high-speed air.
3. **Wind Sock**: A diagonal cone on a pole (aviation style).
4. **Bent Tree**: A tree leaning heavily to one side showing wind force.
5. **Motion Swooshes**: Minimalist tapered curved lines.
6. **Boreas (Cloud Blowing)**: A stylized cloud face blowing air.
7. **Flying Leaves**: 2-3 leaves with curved "trail" lines.
8. **Anemometer**: A cup-style wind speed meter.
9. **Weather Vane**: A classic arrow-style wind direction indicator.

## Key Files & Context
- `app/src/main/res/drawable/`: New vector drawables will be created here.
- `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`: Will be updated to populate the `experimentIcons` list.
- `app/src/main/res/values/strings.xml`: New strings for icon names will be added here.

## Implementation Steps

### 1. Create Vector Drawables
Create the following new drawable files in `app/src/main/res/drawable/`:
- `ic_weather_wind_classic_gusts.xml`
- `ic_weather_wind_dynamic_swirls.xml`
- `ic_weather_wind_sock.xml`
- `ic_weather_wind_bent_tree.xml`
- `ic_weather_wind_motion_swooshes.xml`
- `ic_weather_wind_boreas.xml`
- `ic_weather_wind_flying_leaves.xml`
- `ic_weather_wind_anemometer.xml`
- `ic_weather_wind_vane.xml`

### 2. Add Strings
Add labels for the new icons in `app/src/main/res/values/strings.xml`.

### 3. Update SettingsActivity
Update `SettingsActivity.kt` to populate the `experimentIcons` list with the new `GalleryIcon` objects.

## Verification & Testing
1. **Build**: Run `./gradlew assembleDebug` to ensure no resource errors.
2. **Visual Audit**: Open the Settings view in the emulator and verify that the "Icon Experiment" section shows all 9 new icons with their respective labels.
3. **Comparison**: Compare the new icons against the existing "Wind" icon in the main gallery.

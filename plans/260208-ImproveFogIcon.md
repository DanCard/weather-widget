# Plan: Improve Fog Icon

**Objective:** Redesign the fog icon (`ic_weather_fog.xml`) to use a "Rolling Fog" concept with multiple wavy layers and transparency.

## Context
- **User Preference:** Liked `fog_rolling.svg` but wants "more waves" and "less dark".
- **Design Concept (v2):**
    - **No Background:** The "dark" part was just the preview background, but we will ensure the icon itself uses light, misty colors.
    - **More Layers:** Increase from 2 to 3 or 4 overlapping layers to create a dense fog effect.
    - **Wavy Profiles:** Ensure the top edge of each layer has a soft, rolling wave pattern (`Q` curves).
    - **Gradient/Alpha:** Use varying alphas (e.g., 30%, 50%, 70%, 90%) to build density from top to bottom.

## Files
- Target: `app/src/main/res/drawable/ic_weather_fog.xml`
- Preview: `fog_rolling_v2.svg`

## Execution Steps

1.  **Backup**
    - [x] Create a backup of `ic_weather_fog.xml` to `ic_weather_fog.xml.bak`.

2.  **Generate Preview v2 (SVG)**
    - [x] Create `fog_rolling_v2.svg` with:
        - Layer 1 (Back, Top): Alpha 0.25
        - Layer 2 (Mid-High): Alpha 0.45
        - Layer 3 (Mid-Low): Alpha 0.65
        - Layer 4 (Front, Bottom): Alpha 0.85
        - All layers using color `#B0BEC5` (Light Blue-Gray).

3.  **Implementation (Android Vector)**
    - [x] Apply the approved multi-layer wave design to `ic_weather_fog.xml`.
    - Use `android:fillAlpha` for transparency.

4.  **Verification**
    - [x] Read the new file to verify XML structure.

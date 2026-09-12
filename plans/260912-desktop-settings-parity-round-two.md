# Desktop Settings: second parity pass against Android

*2026-09-12*

## Request

"Desktop setup screen differs significantly from Android. Can we make them closer to the same?
Why does desktop have Diagnostics → Stations / Observations?"

## Findings

Sections compared in on-screen order (`app/src/main/res/layout/activity_settings.xml` vs
`desktop/.../SettingsWindow.kt`):

| Android | Desktop | Diff |
|---|---|---|
| Hourly Zoom · Notifications · Units · Daily View — Today Column · Personal Weather Stations · Weather Data Sources | same | — |
| **Icon gallery**: description + "View Icon Gallery" → `IconGalleryActivity` | **"Icon Gallery"**: the full 10-icon grid inline | biggest visual difference |
| **Default Location**: "Widget Location: … • Follows device" + "Set Location…" | **"Location"** + "Change Location" | copy |
| Language | — | desktop has no translations; skip |
| **Feedback & Bug Reports** + description | **"Feedback"**, no description | copy |
| **API Keys** (+ description), after Feedback | **API Keys**, immediately after Sources | order |
| — | **Diagnostics → Stations / Observations** | desktop-only |
| Support Development + Tip Jar | same | — |
| instant-apply | Save • / Exit app bar | architectural; keep |

**Why Diagnostics exists:** inertia. Android opens the Observations screen by tapping the widget's
current temperature (`TemperatureTouchTargets.setupWeatherStationsShortcut`); it has no Settings
entry. The desktop header's thermometer icon (`DesktopWidgetHeader.kt:223`) is that same entry
point, so the Settings button is a second door to the same room. The 2026-07-27 parity plan listed
it "keep" without revisiting.

## Changes (desktop only)

1. **Remove the Diagnostics card**; `SettingsWindow` and `SettingsWindowHost` lose
   `onOpenObservations`.
2. **Icon gallery → button + window.** Card = Android's description + "View Icon Gallery";
   new `IconGalleryWindowHost` (pattern of `LocationPickerWindowHost`) shows the existing
   `IconGallery()` grid at a larger icon size. Title "Icon gallery" (Android's capitalisation).
   `SettingsWindow` gains `onOpenIconGallery`; `DesktopUiApplication` gets `iconGalleryVisible`.
3. **API Keys moves** to after Feedback; gains Android's description line.
4. **Copy:** "Location" → "Default Location", line "Widget Location: <name> (lat, lon)" (no
   "Follows device" — desktop has no follow mode), "Change Location" → "Set Location…";
   "Feedback" → "Feedback & Bug Reports" + Android's description.
5. **Tests:** `SettingsWindowSectionsTest` — titles list updated, `sectionOrderMatchesAndroid`
   extended to all 11 sections so drift fails; `DesktopUiTest` — `set_location_btn`, gallery
   button fires its callback, observations click removed.

Kept as desktop-only: Save/Exit bar, no Language, Refresh Data / View App Logs header buttons
(Android has the same two in its toolbar).

## Verification (done 2026-09-12)

- `SettingsWindowSectionsTest` 5/5, `DesktopUiTest` 36/36 (the order test now pins all 11
  sections from one `ANDROID_SECTION_ORDER` list).
- Live: Settings screenshot shows … Weather Data Sources → Icon gallery (description + View Icon
  Gallery) → Default Location ("Widget Location: Mountain View, California (37.4168, -122.0890)",
  Set Location…) → Feedback & Bug Reports → API Keys (with description) → Support Development; no
  Diagnostics. View Icon Gallery opens the "Icon Gallery" window with the 10-icon grid at 56 dp.

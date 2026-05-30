# Findings

## Current State Assessment
- `targetSdk = 34` — needs 35 for new Play Store submissions (Aug 2025+ requirement)
- Release signing config externalized to `local.properties` ✓
- ProGuard/R8 enabled ✓
- Firebase Crashlytics wired up (optional, gated on `google-services.json`) ✓
- Location disclosure string exists in `strings.xml` ✓
- Widget preview image exists (`widget_preview.xml`) ✓
- App icon: adaptive icon with sun rays foreground — functional, not placeholder
- `AppLogsActivity`: `exported="false"` — only reachable from within app Settings
- `FeatureTourActivity`: referenced in AGENTS.md but doesn't exist in codebase
- Privacy policy draft exists at `plans/privacy-policy-draft.md` — needs contact info filled in
- `ACCESS_BACKGROUND_LOCATION` declared — requires Play Console justification form

## API Key Handling
- Build reads keys from `local.properties` or env vars, falls back to `""`
- NWS and Open-Meteo: no key needed
- Silurian, WeatherAPI, OpenWeatherMap, Visual Crossing, Tomorrow.io: keys optional
- WidgetStateManager controls which sources are active

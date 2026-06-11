# Play Store Launch Checklist

## Code Changes (Done)
- [x] `targetSdk` updated to 35
- [x] `compileSdk` updated to 35
- [x] Privacy policy strings added (`privacy_policy_title`, `privacy_policy_body`)
- [x] `PrivacyPolicyActivity` registered in manifest
- [x] Missing `WidgetStateManager` imports restored in API classes
- [x] DI providers fixed in `AppModule.kt`

## Your Actions Required

### 1. Release Signing Setup
Add to `local.properties`:
```
RELEASE_STORE_PASSWORD=your_keystore_password
RELEASE_KEY_PASSWORD=your_key_password
```
Then verify: `./gradlew assembleRelease`

### 2. Privacy Policy Hosting
- Policy text is in `app/src/main/res/values/strings.xml` (privacy_policy_body)
- Draft also at `plans/privacy-policy-draft.md`
- Host at a public URL (GitHub Pages, your website, etc.)
- Required for Play Console submission

### 3. Play Store Assets
| Asset | Size | Notes |
|-------|------|-------|
| App icon | 512x512 PNG | High-res version of your adaptive icon |
| Feature graphic | 1024x500 PNG | Banner shown in Play Store listing |
| Phone screenshots | At least 2 | 16:9 or 9:16 aspect ratio |
| Tablet screenshots | Optional | If you want tablet support listed |

### 4. Background Location Justification
Play Console will ask why you need `ACCESS_BACKGROUND_LOCATION`. Use this:

> The app is a home screen widget that displays weather data. It needs background
> location access to fetch local weather forecasts when the widget updates
> automatically, even when the app is not open. Location data is only used to
> determine the user's weather zone and is never stored or shared beyond weather
> API providers.

### 5. Data Safety Form
Play Console requires declaring data collection. For this app:

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Location (approximate) | Yes | Yes (weather APIs) | App functionality |
| Location (precise) | Yes | Yes (weather APIs) | App functionality |
| Crash logs | Yes | Yes (Firebase) | Analytics |

**Not collected:** Personal info, contacts, photos, files, browsing history

### 6. Firebase Setup (for Crashlytics)
- Create Firebase project for `com.weatherwidget`
- Download `google-services.json` → place in `app/`
- Add `google-services.json` to `.gitignore`
- Without it, builds work but crash reporting is disabled

### 7. API Key Rotation
Keys were in git history. Rotate on provider dashboards:
- WeatherAPI
- Silurian
- OpenWeatherMap
- Visual Crossing
- Tomorrow.io

Put new keys in `local.properties` (not committed).

### 8. Closed Testing Requirement
New Play Console accounts must run a closed test:
- 12+ testers
- 14 continuous days
- Then promote to production with staged rollout (10-20%)

## Optional Improvements
- [ ] Custom app icon (current is a sun — functional but generic)
- [ ] Feature tour / first-run experience
- [ ] In-app review prompt (Play In-App Review API)
- [ ] Accessibility audit (content descriptions on widget elements)

# Play Store Launch & Monetization Plan (Adjusted)

## Background & Motivation
The Weather Widget is a highly accurate, feature-rich home screen widget that aggregates data from multiple sources. To successfully launch on the Google Play Store, the project must adhere to Play Store policies (e.g., background location disclosures), improve user onboarding (via a Launcher Activity), secure API keys, and implement analytics for stability monitoring.

## Proposed Strategy
We will focus on the foundational shell, security, and stability features. Monetization and final store assets will be addressed in a future phase.
- **Initial Goal:** Secure the app for distribution and ensure compliance with Play Store location policies.
- **Security Model:** Implement **BYOK (Bring Your Own Key)** for commercial APIs.

## Implementation Steps

### Phase 1: App Shell & Onboarding UX
1. **Create a Configuration / Launcher Activity**
   - Add a `MainActivity` that serves as the entry point when the user taps the app icon.
   - Design an onboarding flow explaining that this is a widget-first app.
   - Include a tutorial (using Glide to load a GIF) showing how to add the widget to the home screen.
2. **Background Location Permission Flow**
   - Update `AndroidManifest.xml` with required Play Store location disclosures.
   - Create a pre-permission disclosure screen in the `MainActivity` explaining *why* the widget needs background location before requesting `ACCESS_BACKGROUND_LOCATION`.

### Phase 2: Security & API Key Management
1. **Remove Hardcoded Keys from Build**
   - Ensure commercial API keys (Tomorrow.io, OpenWeatherMap, Visual Crossing, Silurian) are completely removed from `BuildConfig` for release builds, or restricted to debug variants.
2. **Implement BYOK (Bring Your Own Key) Settings**
   - Add a Settings screen where users can input their own API keys for supported providers.
   - Update `WeatherRepository` to check `SharedPreferences` / `DataStore` for user-provided keys before falling back to default/free providers.

### Phase 3: Analytics & Crashlytics
1. **Enable Firebase Crashlytics**
   - Set up the Firebase project and download `google-services.json`.
   - Update documentation to note the dependency.
   - Verify that Crashlytics correctly captures crashes without interfering with local logging (`app_logs`).

## Verification & Testing
- **UI Tests:** Ensure `MainActivity` and Settings screens render correctly on various screen sizes.
- **Permission Tests:** Install on Android 14+ emulators to verify the background location prompt flow is compliant.
- **Widget Flow:** Ensure the transition from `MainActivity` -> Adding Widget -> Rendering works seamlessly.
- **Security Audit:** Confirm no sensitive keys are present in the final release APK.

# Play Store Open Beta Release & Production Promotion Summary

**Date:** July 28, 2026  
**Version Code:** `26072801`  
**Version Name:** `26072801`  

---

## 1. Overview
Published the latest release (`versionCode = 26072801`, `versionName = "26072801"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). Per user requirements, `versionName` was set to match `versionCode` (`26072801`). The release was uploaded to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and push the release to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26072701` to `26072801`.
- Matched `versionName` to `versionCode`: `"26072801"`.

### Features & Improvements Included in Version 26072801
1. **WeatherAPI Automatic History Backfill (`78516a71`)**:
   - Added automatic history backfill logic when querying WeatherAPI sources.

2. **Weather Source Selection Optimization (`d310e671`)**:
   - Improved selection of viable weather sources during initial widget setup flow.

3. **Widget Intent Router Architecture & Hardening (`e5fca551`, `feb4c01e`, `9db80db6`, `cf21db7f`, `d9a53253`)**:
   - Refactored `WidgetIntentRouter` into clean modular design split by responsibility.
   - Added intent resize debouncing, interaction breadcrumb logs, and refresh cooldown guards.

4. **Daily Forecast Graph & Rendering Resilience (`caf1f8e3`, `3defa81e`, `4ba689eb`)**:
   - Refactored daily forecast graph renderer.
   - Guarded graph axis rendering against sentinel temperature values.
   - Optimized `TemperatureGraphRenderer` by caching fetch-dot layout and marking forced day-label overlaps.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26072801.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072801.txt))
- Created release changelog `26072801.txt`:
  > Open beta release: Hardened widget intent routing and refresh cooldowns, optimized daily forecast graph rendering, guarded against sentinel temperatures on graph axes, refactored forecast repository, enhanced weather source selection during setup, and automated WeatherAPI history backfill.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All 2,481 unit tests passed cleanly in 61 seconds.

2. **Signed AAB Build (`./gradlew bundleRelease`)**
   - Successfully compiled and signed `app/build/outputs/bundle/release/app-release.aab` using `RELEASE_STORE_FILE` keystore credentials.

3. **Open Beta Track Upload (`fastlane beta`)**
   - Authenticated using `fastlane/play-store-api-key.json`.
   - Uploaded signed AAB and changelog `26072801.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully.

4. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26072801` from `beta` to `production` (`track_promote_to: "production"`, `version_code: 26072801`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with submission to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26072801.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072801.txt)
- `untracked:` [summaries/260728-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260728-playstore-open-beta-and-production-release.md)

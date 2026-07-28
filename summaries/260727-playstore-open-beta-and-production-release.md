# Play Store Open Beta Release & Production Promotion Summary

**Date:** July 27, 2026  
**Version Code:** `26072701`  
**Version Name:** `1.0.6`  

---

## 1. Overview
Published the latest changes (`versionCode = 26072701`, `versionName = "1.0.6"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). The release was uploaded to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and push the release to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L121-L122))
- Bumped `versionCode` from `26072501` to `26072701`.
- Updated `versionName` from `"1.0.5"` to `"1.0.6"`.

### Features & Improvements Included in Version 1.0.6
1. **WeatherWidgetProvider Maintenance & Optimizations (`29fbf02c`)**:
   - Dropped parallel work execution where redundant current-observation fetches hit the network twice.
   - Fixed `headerDateFormatter` locale caching to dynamically resolve locale on each render so header date labels correctly update when device locale changes without requiring process restarts.
   - Enhanced widget deletion lifecycle (`onDeleted`) to cancel in-flight `WidgetUpdateTracker` coroutine jobs.
   - Refactored zone index offset computations and removed dead companion methods.

2. **DailyViewHandler Modularization & Performance (`04b8e333`)**:
   - Extracted `DailyHeaderResolver` and `DailyTextRenderer` modular components to streamline handler maintenance.
   - Eliminated per-render `SharedPreferences` instantiation inside header state binding by resolving temperature unit deltas upfront.
   - Refactored weather data log aggregation to prevent multi-line drop traces.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26072701.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072701.txt))
- Created release changelog `26072701.txt`:
  > Open beta release: Fixed widget layout state initialization and locale-aware date headers, optimized renderer performance by eliminating redundant database and SharedPreferences reads during navigation, improved cleanup on widget deletion, and modularized daily view handlers.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All 2,394 unit tests passed cleanly in 96 seconds.

2. **Signed AAB Build (`./gradlew bundleRelease`)**
   - Successfully compiled and signed `app/build/outputs/bundle/release/app-release.aab` using `RELEASE_STORE_FILE` keystore credentials (`weatherwidget.jks`).

3. **Open Beta Track Upload (`fastlane beta`)**
   - Authenticated using `fastlane/play-store-api-key.json`.
   - Uploaded signed AAB and changelog `26072701.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully.

4. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26072701` from `beta` to `production` (`track_promote_to: "production"`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with submission to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26072701.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072701.txt)
- `untracked:` [summaries/260727-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260727-playstore-open-beta-and-production-release.md)

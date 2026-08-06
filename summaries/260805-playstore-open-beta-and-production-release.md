# Play Store Open Beta Release & Production Promotion Summary

**Date:** August 5, 2026  
**Version Code:** `26080501`  
**Version Name:** `26080501`  

---

## 1. Overview
Published the latest release (`versionCode = 26080501`, `versionName = "26080501"`) to the Google Play Store using Fastlane. Per user requirements, `versionName` was matched to `versionCode` (`26080501`). The release was uploaded first to the **Open Beta** track (`track: "beta"`), verified via Play Store API, and subsequently promoted/uploaded to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so Google Play will review and release the update to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26080301` to `26080501`.
- Matched `versionName` to `versionCode`: `"26080501"`.

### Features & Improvements Included in Version 26080501
1. **Today-Column Station Overlay & Actuals Blending (`53be4ee8`, `5f4ecd33`, `752cdb61`)**:
   - Added today-column station overlay and enriched actuals blending model.
   - Simplified text auto-sizing and shared detailed overlay across Android and Desktop platforms.
   - Made overlay texts opt-in via three user toggles (`487b3d84`).

2. **Daily Forecast Header & Graph Delta Labels (`ef9ae85f`, `673d096c`)**:
   - Added yesterday temperature delta label to daily forecast header.
   - Swapped header delta with graph/overlay delta for consistent cross-platform presentation.

3. **Forecast History Actuals Logic (`a3b45618`)**:
   - Fixed API actual determination in Forecast History to properly identify observation sources.

4. **Background Data Load & Render Throttling (`c13001c4`, `99bfd344`)**:
   - Refactored `WeatherWidgetWorker`: extracted helper classes, deduplicated data loading, and added rendering throttle protections.
   - Scoped observation exit refreshes specifically to single active widget.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26080501.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080501.txt))
- Created release changelog `26080501.txt`:
  > Open beta release: Added Today-column station overlay and header delta display with configurable toggles, improved Forecast History actuals determination, and optimized background worker data loading and render throttling.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./gradlew test`)**
   - Executed unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All unit tests passed cleanly (`BUILD SUCCESSFUL in 10s`).

2. **Open Beta Track Upload (`fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26080501`.
   - Uploaded signed AAB and changelog `26080501.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully (`version_code 26080501`).

3. **Production Track Upload & Promotion (`fastlane production`)**
   - Uploaded signed AAB and metadata for version `26080501` to `track: "production"` with `changes_not_sent_for_review: false`.
   - **Result:** Production track release completed successfully and submitted to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified and untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26080501.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080501.txt)
- `untracked:` [summaries/260805-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260805-playstore-open-beta-and-production-release.md)

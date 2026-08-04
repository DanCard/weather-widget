# Play Store Open Beta Release & Production Promotion Summary

**Date:** August 3, 2026  
**Version Code:** `26080301`  
**Version Name:** `26080301`  

---

## 1. Overview
Published the latest release (`versionCode = 26080301`, `versionName = "26080301"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). Per user requirements, `versionName` was matched to `versionCode` (`26080301`). The release was uploaded first to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and publish the update to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26080101` to `26080301`.
- Matched `versionName` to `versionCode`: `"26080301"`.

### Features & Improvements Included in Version 26080301
1. **Android 11 Background Worker Crash Prevention (`813e4720`)**:
   - Resolved expedited worker crash issues on Android 11 runtime devices.

2. **Cross-Location Observation Cache Leak Fix (`6b8b73e9`)**:
   - Prevented cross-location observation leaks in `CurrentTempRepository`.

3. **8th Day Forecast Rendering Correction (`2643ae18`)**:
   - Fixed 8th daily column rendering "cloudy" icon beyond active forecast coverage.

4. **Query Load Window Optimization (`6f233b23`, `731e0f21`)**:
   - Dynamic sizing of daily load window to visible range and hourly rows targeted specifically to displayed sources.

5. **Desktop Observations & Station Contributions (`611f7702`, `ed1a452b`)**:
   - Defaulting to observations tab, exposing station contribution blend details, and scope handling.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26080301.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080301.txt))
- Created release changelog `26080301.txt`:
  > Open beta release: Fixed Android 11 background worker stability, resolved cross-location observation caching, fixed 8th-day daily forecast rendering past coverage, and optimized query load windows.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./gradlew test`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All unit tests passed cleanly (`BUILD SUCCESSFUL`).

2. **Open Beta Track Upload (`fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26080301`.
   - Uploaded signed AAB and changelog `26080301.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully.

3. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26080301` from `beta` to `production` (`track_promote_to: "production"`, `version_code: 26080301`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with submission to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26080301.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080301.txt)
- `untracked:` [summaries/260803-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260803-playstore-open-beta-and-production-release.md)

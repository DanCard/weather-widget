# Play Store Open Beta Release & Production Promotion Summary

**Date:** August 6, 2026  
**Version Code:** `26080601`  
**Version Name:** `26080601`  

---

## 1. Overview
Published the new release (`versionCode = 26080601`, `versionName = "26080601"`) to the Google Play Store using Fastlane. Following established workflow requirements:
1. `versionName` was matched to `versionCode` (`26080601`).
2. The release was uploaded first to the **Open Beta** track (`track: "beta"`).
3. The release was promoted / uploaded to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`).

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26080501` to `26080601`.
- Matched `versionName` to `versionCode`: `"26080601"`.

### Features & Fixes Included in Version 26080601
1. **Forecast History Missing API Actuals Fix (`c984e8f6`)**:
   - Fixed missing API actuals in Forecast History caused by partial NWS gridpoint rows.
   - Introduced `ApiActualPicker.pickNearestComplete` in `:shared` to ensure complete actual pairs are selected.
   - Prevented `persistNwsGridpointActuals` from overwriting stored values with nulls and coalesced missing fields.
   - Updated ERA5 backfill logic on both Android and Desktop to fill only missing fields while preserving real NWS gridpoint values.
2. **Desktop Forecast History Initial Date**:
   - Updated Desktop Forecast History button to open directly on the viewed hourly graph center date rather than defaulting to today.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26080601.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080601.txt))
- Created release changelog `26080601.txt`:
  > Open beta release: Fixed missing API actuals in Forecast History caused by partial NWS gridpoint data, improved actuals merging to preserve NWS high/low temperatures while filling gaps, and updated desktop Forecast History to open on the viewed date.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./gradlew test`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** `BUILD SUCCESSFUL in 16s` with all unit tests passing.

2. **Open Beta Track Upload (`fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26080601`.
   - Uploaded signed AAB and changelog `26080601.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully (`version_code 26080601`).

3. **Production Track Upload & Promotion (`fastlane production`)**
   - Uploaded signed AAB and metadata for version `26080601` to `track: "production"`.
   - **Result:** Production track release completed successfully and submitted to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified and untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26080601.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080601.txt)
- `untracked:` [summaries/260806-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260806-playstore-open-beta-and-production-release.md)

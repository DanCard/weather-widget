# Play Store Open Beta Release & Production Promotion Summary

**Date:** August 8, 2026  
**Version Code:** `26080801`  
**Version Name:** `26080801`  

---

## 1. Overview
Published a new version release (`versionCode = 26080801`, `versionName = "26080801"`) to the Google Play Store using Fastlane. Following established workflow requirements:
1. `versionName` was matched to `versionCode` (`26080801`).
2. The release was uploaded first to the **Open Beta** track (`track: "beta"`).
3. The release was promoted from Open Beta to the **Production** track (`track_promote_to: "production"`) with review flags enabled (`changes_not_sent_for_review: false`).

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26080701` to `26080801`.
- Matched `versionName` to `versionCode`: `"26080801"`.

### Features & Fixes Included in Version 26080801
1. **NWS Daily Actuals from Station Observations**:
   - Replaced NWS NDFD gridpoints forecast fallback for daily actuals with official station observation pulls from `api.weather.gov/stations/{id}/observations`.
   - Updated baseline scoring logic in `AccuracyCalculator` and added accuracy baseline selector setting.
   - Performed database schema migrations (Room 58 -> 59 -> 60) for `apiStationId` and `apiStationDistanceKm` fields.
2. **Cached Observation Pool Fallback**:
   - Added automatic fallback to the cached observation pool when NWS station pull is offline or unavailable.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26080801.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080801.txt))
- Created release changelog `26080801.txt`:
  > Release 26080801: Derive NWS daily actuals directly from official station observations instead of forecast gridpoints, improve observation pool fallbacks when offline or station pull fails, and update accuracy baseline selection.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across all modules (`:app`, `:shared`, `:desktop`).
   - **Result:** `BUILD SUCCESSFUL` - 2809 unit tests passed in 94s.

2. **Open Beta Track Upload (`fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26080801`.
   - Uploaded signed AAB and changelog `26080801.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload completed successfully (`version_code 26080801`).

3. **Production Track Promotion (`fastlane run upload_to_play_store track:beta track_promote_to:production ...`)**
   - Promoted Open Beta release `26080801` to the `production` track (`changes_not_sent_for_review: false`).
   - **Result:** Production promotion completed successfully (`Result: true`).

---

## 4. Git Status

Per global user rules, no automated git commit was executed. The following modified and untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26080801.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080801.txt)
- `untracked:` [summaries/260808-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260808-playstore-open-beta-and-production-release.md)

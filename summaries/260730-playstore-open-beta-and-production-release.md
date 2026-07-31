# Play Store Open Beta Release & Production Promotion Summary

**Date:** July 30, 2026  
**Version Code:** `26073001`  
**Version Name:** `26073001`  

---

## 1. Overview
Published the latest release (`versionCode = 26073001`, `versionName = "26073001"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). Per user requirements, `versionName` was set to match `versionCode` (`26073001`). The release was uploaded to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and push the release to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26072801` to `26073001`.
- Matched `versionName` to `versionCode`: `"26073001"`.

### Features & Improvements Included in Version 26073001
1. **Observation Repository Coordination Refactor (`a69cedcf`)**:
   - Split monolithic `ObservationRepository` into cohesive NWS source, current-update, backfill, daily-actuals, current-read, and station-weight collaborators.
   - Made observation storage location-safe with Room composite identity migrations and exact-site reads and touches.

2. **Same-Site Incomplete Forecast Repairs (`c31fdc72`)**:
   - Updated `DailyViewLogic.prepareGraphDays` and `prepareTextDays` to use `completeSameSiteReplacement`, ensuring incomplete forecast replacements strictly match physical site location.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26073001.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26073001.txt))
- Created release changelog `26073001.txt`:
  > Open beta release: Refactored observation repository coordination into location-safe collaborators with Room composite identity, and fixed cross-site incomplete forecast snapshot repairs to strictly match physical site location.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All 2,558 unit tests passed cleanly in 91 seconds.

2. **Signed AAB Build (`./gradlew bundleRelease`)**
   - Successfully compiled and signed `app/build/outputs/bundle/release/app-release.aab` using `RELEASE_STORE_FILE` keystore credentials.

3. **Open Beta Track Upload (`fastlane beta`)**
   - Authenticated using `fastlane/play-store-api-key.json`.
   - Uploaded signed AAB and changelog `26073001.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully.

4. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26073001` from `beta` to `production` (`track_promote_to: "production"`, `version_code: 26073001`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with submission to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26073001.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26073001.txt)
- `untracked:` [summaries/260730-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260730-playstore-open-beta-and-production-release.md)

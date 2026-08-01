# Play Store Open Beta Release & Production Promotion Summary

**Date:** August 1, 2026  
**Version Code:** `26080101`  
**Version Name:** `26080101`  

---

## 1. Overview
Published the latest release (`versionCode = 26080101`, `versionName = "26080101"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). Per user requirements, `versionName` was matched to `versionCode` (`26080101`). The release was uploaded first to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and publish the update to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26073001` to `26080101`.
- Matched `versionName` to `versionCode`: `"26080101"`.

### Features & Improvements Included in Version 26080101
1. **Observation & Fetch Log Storage Separation (`c074ef55`)**:
   - Separated Android observation persistence from fetch log auditing for cleaner diagnostics and room queries.

2. **Daily Header Temperature Display & Layout Refactor (`570eb0d8`)**:
   - Improved daily header layout structure and temperature rendering alignment.

3. **Dual High Label Collision Prevention (`48f963f6`)**:
   - Resolved dual high label collision and placement overlap for sub-2° forecast misses on temperature graphs.

4. **Desktop Refresh & Window Scope Handling (`dbcdd733`)**:
   - Added desktop UI refresh button and window scope cancellation handling.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26080101.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080101.txt))
- Created release changelog `26080101.txt`:
  > Open beta release: Separated Android observation storage and fetch log auditing, refined daily header temperature display layout, fixed dual high label collision handling for close forecast misses, and improved desktop refresh responsiveness.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./gradlew test`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All unit tests passed cleanly in 1m 10s (`BUILD SUCCESSFUL`).

2. **Open Beta Track Upload (`fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) using release keystore credentials.
   - Authenticated using `fastlane/play-store-api-key.json`.
   - Uploaded signed AAB and changelog `26080101.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully.

3. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26080101` from `beta` to `production` (`track_promote_to: "production"`, `version_code: 26080101`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with submission to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26080101.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080101.txt)
- `untracked:` [summaries/260801-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260801-playstore-open-beta-and-production-release.md)

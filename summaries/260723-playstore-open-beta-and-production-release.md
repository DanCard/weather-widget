# Play Store Open Beta Release & Production Promotion Summary

**Date:** July 23, 2026  
**Version Code:** `26072301`  
**Version Name:** `1.0.4`  

---

## 1. Overview
Published the latest changes (`versionCode = 26072301`, `versionName = "1.0.4"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). The release was uploaded to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and push the release to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L120-L123))
- Bumped `versionCode` from `26072201` to `26072301`.
- Updated `versionName` from `"1.0.3"` to `"1.0.4"`.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26072301.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072301.txt))
- Created release changelog `26072301.txt`:
  > Open beta release: Added frosted-glass today-column highlight to daily forecast graph, improved 24h wide view temp line labels, reduced widget redraw flash during fetches, enhanced launcher cache drop recovery, and optimized diagnostic logging.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All 2,307 unit tests passed cleanly in ~60 seconds.

2. **Signed AAB Build (`./gradlew bundleRelease`)**
   - Successfully compiled and signed `app/build/outputs/bundle/release/app-release.aab` using `releaseStoreFile` keystore credentials.

3. **Open Beta Track Upload (`fastlane beta`)**
   - Authenticated using `fastlane/play-store-api-key.json`.
   - Uploaded signed AAB and changelog `26072301.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully with status `true`.

4. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26072301` from `beta` to `production` (`track_promote_to: "production"`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with status `true`.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26072301.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072301.txt)
- `untracked:` [summaries/260723-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260723-playstore-open-beta-and-production-release.md)

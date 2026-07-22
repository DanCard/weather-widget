# Play Store Open Beta Release and Production Promotion Summary

**Date:** July 22, 2026  
**Version Code:** `26072201`  
**Version Name:** `1.0.3`  

---

## 1. Overview
Published a new release (`versionCode = 26072201`, `versionName = "1.0.3"`) to Google Play Store using Fastlane `supply` API. The release was uploaded to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and subsequently promoted to the **Production** track (`track: "production"`).

---

## 2. Summary of Changes

### Versioning (`app/build.gradle.kts`)
- Bumped `versionCode` from `26071501` to `26072201` to prevent Play Store API version code collision errors.
- Updated `versionName` to `"1.0.3"`.

### Fastlane Configuration (`fastlane/Fastfile`)
- Updated `lane :beta` to target the Google Play Open Beta track (`track: "beta"`).
- Added `lane :internal` with `track: "internal"` for explicit internal testing builds.
- Updated Fastlane documentation and parameters to handle promotion workflows cleanly with explicit `version_code` mapping for changelogs.

### Release Notes (`fastlane/metadata/android/en-US/changelogs/26072201.txt`)
- Added release changelog for version code `26072201`:
  > Open beta release: Improved temperature line smoothing and ghost line label visibility, updated SDK target for Android 16 compliance, enhanced observation station fallback, and performance optimizations.

---

## 3. Deployment & Verification

1. **Signed AAB Build (`./gradlew bundleRelease`)**
   - Successfully compiled and signed `app/build/outputs/bundle/release/app-release.aab` using `releaseStoreFile` keystore credentials.

2. **Open Beta Track Upload (`fastlane beta`)**
   - Verified credentials (`fastlane/play-store-api-key.json`).
   - Uploaded signed AAB and changelog `26072201.txt` to Google Play API (`track: "beta"`).
   - **Result:** Play Store API upload finished with status `true`.

3. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26072201` from `beta` to `production` (`track_promote_to: "production"`).
   - **Result:** Play Store API promotion completed with status `true`.

---

## 4. Git Status

Per project rules, no automated git commit was made. Modified and untracked files are staged/ready for commit by the user:

```bash
modified:   app/build.gradle.kts
modified:   fastlane/Fastfile
modified:   fastlane/README.md
untracked:  fastlane/metadata/android/en-US/changelogs/26072201.txt
untracked:  summaries/260722-playstore-open-beta-and-production-release.md
```

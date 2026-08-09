# Play Store Open Beta Release & Production Promotion Summary

**Date:** August 9, 2026  
**Version Code:** `26080901`  
**Version Name:** `26080901`  

---

## 1. Overview
Published a new version release (`versionCode = 26080901`, `versionName = "26080901"`) to the Google Play Store using Fastlane. Following established workflow requirements:
1. `versionName` was matched to `versionCode` (`26080901`).
2. The release was uploaded first to the **Open Beta** track (`track: "beta"`).
3. The release was promoted from Open Beta to the **Production** track (`track_promote_to: "production"`) with review flags enabled (`changes_not_sent_for_review: false`).

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26080801` to `26080901`.
- Matched `versionName` to `versionCode`: `"26080901"`.

### Features & Fixes Included in Version 26080901
1. **Configurable Narrow Zoom Span**:
   - Made the tight hourly graph zoom span user-configurable (4-8 hours, defaulting to 5 hours) across Android and Desktop interfaces.
2. **Hourly Source Snapshot Drift Guard**:
   - Reloaded hourly forecast rows when an API source toggle outran background worker scope snapshots, preventing empty hourly graph displays.
3. **Test Harness & Build Infrastructure Improvements**:
   - Fixed test script process leakage and enabled parallel execution for emulator test runs.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26080901.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080901.txt))
- Created release changelog `26080901.txt`:
  > Release 26080901: Make tight hourly zoom span user-configurable (4-8h, default 5h), reload hourly forecast rows when API source toggles outrun worker scope snapshots to prevent blank graphs, and update test harness stability.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across all modules (`:app`, `:shared`, `:desktop`).
   - **Result:** `BUILD SUCCESSFUL` - 2834 unit tests passed in 65s.

2. **Open Beta Track Upload (`fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26080901`.
   - Uploaded signed AAB and changelog `26080901.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload completed successfully (`version_code 26080901`).

3. **Production Track Promotion (`fastlane run upload_to_play_store track:beta track_promote_to:production version_code:26080901 ...`)**
   - Promoted Open Beta release `26080901` to the `production` track (`changes_not_sent_for_review: false`).
   - **Result:** Production promotion completed successfully (`Result: true`).

---

## 4. Git Status

Per global user rules, no automated git commit was executed. The following modified and untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26080901.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26080901.txt)
- `untracked:` [summaries/260809-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260809-playstore-open-beta-and-production-release.md)

# Play Store Open Beta Release & Production Promotion Summary (26081101)

**Date:** August 11, 2026  
**Version Code:** `26081101`  
**Version Name:** `26081101`  

---

## 1. Overview
Published a new version release (`versionCode = 26081101`, `versionName = "26081101"`) to the Google Play Store using Fastlane. Following established workflow requirements:
1. `versionName` was matched to `versionCode` (`26081101`).
2. The release was uploaded first to the **Open Beta** track (`track: "beta"`).
3. The release was promoted from Open Beta to the **Production** track (`track_promote_to: "production"`).

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26081001` to `26081101`.
- Matched `versionName` to `versionCode`: `"26081101"`.

### Features & Fixes Included in Version 26081101
1. **Hourly Graph Zoom Span Fix**:
   - Fixed hourly graph zoom span rendering to display the full selected hour window (e.g. 8h setting now correctly displays all 8 hours instead of ending 1 hour short).

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26081101.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26081101.txt))
- Created release changelog `26081101.txt`:
  > Release 26081101: Fix hourly graph zoom span rendering to display full selected hour window.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** `BUILD SUCCESSFUL` - 2942 unit tests passed in 68s.

2. **Open Beta Track Upload (`~/.local/share/gem/ruby/3.3.0/bin/fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26081101`.
   - Uploaded signed AAB and changelog `26081101.txt` to Google Play (`track: "beta"`).

3. **Production Track Promotion (`~/.local/share/gem/ruby/3.3.0/bin/fastlane run upload_to_play_store track:beta track_promote_to:production version_code:26081101 ...`)**
   - Promoted Open Beta release `26081101` to the `production` track (`changes_not_sent_for_review: false`).

---

## 4. Git Status

Per global user rules, no automated git commit was executed. The following modified and untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26081101.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26081101.txt)
- `untracked:` [plans/260811-playstore-open-beta-and-production-release-26081101.md](file:///home/dcar/projects/weather-widget/plans/260811-playstore-open-beta-and-production-release-26081101.md)
- `untracked:` [summaries/260811-playstore-open-beta-and-production-release-26081101.md](file:///home/dcar/projects/weather-widget/summaries/260811-playstore-open-beta-and-production-release-26081101.md)

### Recommended Commit Message

```text
Release version 26081101 to Play Store Open Beta and Production

Summary of Changes:
- BUMPED versionCode to 26081101 and matched versionName to "26081101" in app/build.gradle.kts.
- ADDED fastlane changelog 26081101.txt for release notes.
- UPLOADED release AAB to Google Play Open Beta channel via Fastlane.
- PROMOTED release 26081101 from Open Beta to Production track with changes_not_sent_for_review=false.

Verification:
- All 2942 unit tests passed via ./scripts/unit-tests.sh.
- Fastlane beta upload and production promotion tasks completed successfully.
```

# Play Store Open Beta Release & Production Promotion Summary (26081001)

**Date:** August 10, 2026  
**Version Code:** `26081001`  
**Version Name:** `26081001`  

---

## 1. Overview
Published a new version release (`versionCode = 26081001`, `versionName = "26081001"`) to the Google Play Store using Fastlane. Following established workflow requirements:
1. `versionName` was matched to `versionCode` (`26081001`).
2. The release was uploaded first to the **Open Beta** track (`track: "beta"`).
3. The release was promoted from Open Beta to the **Production** track (`track_promote_to: "production"`).
4. Updated documentation (`AGENTS.md` and `GEMINI.md`) with the explicit Fastlane executable path (`~/.local/share/gem/ruby/3.3.0/bin/fastlane`).

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L122-L123))
- Bumped `versionCode` from `26080902` to `26081001`.
- Matched `versionName` to `versionCode`: `"26081001"`.

### Features & Fixes Included in Version 26081001
1. **Tip Jar in Settings**:
   - Added Tip Jar option in Settings for both Android and Linux Desktop companion apps.
2. **Hourly Graph Enhancements**:
   - Displayed dominant blend station name and dated station readings on hourly temperature graph view.
   - Streamlined hourly zoom span handling and settings persistence.
   - Refined today-column overlay alignment math.

### Fastlane Documentation & Path Configuration
- Documented explicit fastlane executable location (`~/.local/share/gem/ruby/3.3.0/bin/fastlane`) in both [`AGENTS.md`](file:///home/dcar/projects/weather-widget/AGENTS.md) and [`GEMINI.md`](file:///home/dcar/projects/weather-widget/GEMINI.md).

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26081001.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26081001.txt))
- Created release changelog `26081001.txt`:
  > Release 26081001: Added Tip Jar option in Settings, improved hourly station identification and dates, enhanced hourly graph zoom controls, and refined layout alignment.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** `BUILD SUCCESSFUL` - 2934 unit tests passed in 74s.

2. **Open Beta Track Upload (`~/.local/share/gem/ruby/3.3.0/bin/fastlane beta`)**
   - Built signed release AAB (`app-release.aab`) with versionCode `26081001`.
   - Uploaded signed AAB and changelog `26081001.txt` to Google Play (`track: "beta"`).

3. **Production Track Promotion (`~/.local/share/gem/ruby/3.3.0/bin/fastlane run upload_to_play_store track:beta track_promote_to:production version_code:26081001 ...`)**
   - Promoted Open Beta release `26081001` to the `production` track (`changes_not_sent_for_review: false`).

---

## 4. Git Status

Per global user rules, no automated git commit was executed. The following modified and untracked files are ready for user review:

- `modified:` [AGENTS.md](file:///home/dcar/projects/weather-widget/AGENTS.md)
- `modified:` [GEMINI.md](file:///home/dcar/projects/weather-widget/GEMINI.md)
- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26081001.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26081001.txt)
- `untracked:` [plans/260810-playstore-open-beta-and-production-release-26081001.md](file:///home/dcar/projects/weather-widget/plans/260810-playstore-open-beta-and-production-release-26081001.md)
- `untracked:` [summaries/260810-playstore-open-beta-and-production-release-26081001.md](file:///home/dcar/projects/weather-widget/summaries/260810-playstore-open-beta-and-production-release-26081001.md)

### Recommended Commit Message

```text
Release version 26081001 to Play Store Open Beta and Production

Summary of Changes:
- BUMPED versionCode to 26081001 and matched versionName to "26081001" in app/build.gradle.kts.
- ADDED fastlane changelog 26081001.txt for release notes.
- UPLOADED release AAB to Google Play Open Beta channel via Fastlane.
- PROMOTED release 26081001 from Open Beta to Production track with changes_not_sent_for_review=false.
- DOCUMENTED fastlane binary path (~/.local/share/gem/ruby/3.3.0/bin/fastlane) in AGENTS.md and GEMINI.md.

Verification:
- All 2934 unit tests passed via ./scripts/unit-tests.sh.
- Fastlane beta and production promotion tasks completed successfully.
```

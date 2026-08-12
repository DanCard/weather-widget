# Plan: Google Play Store Release 26081101 (Open Beta & Production Promotion)

## Goal
Release version `26081101` to Google Play Store:
1. Update `versionCode` and `versionName` to `26081101` in `app/build.gradle.kts`.
2. Add Fastlane changelog `fastlane/metadata/android/en-US/changelogs/26081101.txt`.
3. Execute pre-flight unit tests (`./scripts/unit-tests.sh`).
4. Upload release AAB to **Open Beta** track via Fastlane (`~/.local/share/gem/ruby/3.3.0/bin/fastlane beta`).
5. Promote release `26081101` from Open Beta to **Production** track via Fastlane API call (`~/.local/share/gem/ruby/3.3.0/bin/fastlane run upload_to_play_store track:beta track_promote_to:production version_code:26081101 changes_not_sent_for_review:false`).
6. Generate session summary and present git status/commit details (obeying the no-auto-commit rule).

## Proposed Changes
- `app/build.gradle.kts`: Update `versionCode = 26081101` and `versionName = "26081101"`.
- `fastlane/metadata/android/en-US/changelogs/26081101.txt`: Add release notes for version 26081101.

## Verification Plan
1. `./scripts/unit-tests.sh` passes clean across `:app`, `:shared`, and `:desktop`.
2. Fastlane `beta` lane builds and uploads release bundle successfully to Open Beta.
3. Fastlane `production` promotion call succeeds.

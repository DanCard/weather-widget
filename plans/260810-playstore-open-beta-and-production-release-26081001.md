# Plan: Google Play Store Release 26081001 (Open Beta & Production Promotion)

## Goal
Release version `26081001` to Google Play Store:
1. Update `versionCode` and `versionName` to `26081001` in `app/build.gradle.kts`.
2. Add Fastlane changelog `fastlane/metadata/android/en-US/changelogs/26081001.txt`.
3. Execute pre-flight unit tests (`./scripts/unit-tests.sh`).
4. Upload release AAB to **Open Beta** track via Fastlane (`bundle exec fastlane beta`).
5. Promote release `26081001` from Open Beta to **Production** track via Fastlane API call (`fastlane run upload_to_play_store track:beta track_promote_to:production version_code:26081001 changes_not_sent_for_review:false`).
6. Generate session summary and provide git commit details (obeying no-auto-commit rule).

## Proposed Changes
- `app/build.gradle.kts`: Bump `versionCode = 26081001` and `versionName = "26081001"`.
- `fastlane/metadata/android/en-US/changelogs/26081001.txt`: Release notes for version 26081001.
- Fastlane operations for `beta` and `production` tracks.

## Verification Plan
1. `./scripts/unit-tests.sh` must succeed across `:app`, `:shared`, and `:desktop`.
2. Fastlane `beta` lane must complete successfully and return valid Play Store response.
3. Fastlane `production` promotion call must succeed.

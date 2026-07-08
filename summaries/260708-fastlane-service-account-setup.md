# Google Play Console & Fastlane Service Account Setup

**Date:** 2026-07-08

## Summary
Configured a Google Play Developer service account for automated publishing via Fastlane. Integrated this credential in both the `cal-date-widget` and `weather-widget` repositories.

## Google Cloud & Play Console Setup
1. **Google Cloud Console:**
   - Active in GCP project `personal-workspace-mcp-495506`.
   - Created a service account: `fastlane-play-store` (role configuration skipped, as permissions are managed in the Play Console).
   - Generated and downloaded a private JSON key file (`play-store-api-key.json`).
2. **Google Play Console:**
   - Navigated to **Users and permissions** for account `daniel.cardenas@gmail.com`.
   - Invited the service account email `fastlane-play-store@personal-workspace-mcp-495506.iam.gserviceaccount.com`.
   - Granted full release and application management permissions to the service account.

## Repository Integration

### cal-date-widget
- Saved credentials to `fastlane/play-store-api-key.json`.
- Configured `fastlane/Appfile` to link the JSON key file and set the package name (`ai.dcar.caldatewidget`).
- Verified the key file is ignored via `.gitignore` (`fastlane/play-store-api-key.json`).

### weather-widget
- Created the `fastlane/` directory.
- Copied the service account JSON key file to `fastlane/play-store-api-key.json`.
- Created `fastlane/Appfile` with:
  ```ruby
  # Path to the service account JSON key file obtained from Google Developer Console
  json_key_file("fastlane/play-store-api-key.json")

  # Default package name
  package_name("com.weatherwidget")
  ```
- Appended the ignore pattern for the JSON key to `.gitignore` to prevent committing secrets:
  ```text
  # Play Store service-account key (fastlane upload credential — never commit)
  fastlane/play-store-api-key.json
  ```
- Verified that `git status --ignored` registers the key file as ignored.

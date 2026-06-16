# Plan: Bug Reporting and Diagnostics Collection

We will implement a bug reporting and diagnostics collection feature to make it easy for users/testers to report bugs directly from the app before launching on the Play Store.

## 1. Requirements

- A "Feedback & Bug Reports" section at the bottom of the Settings screen.
- A dedicated `BugReportActivity` to allow the user to describe the issue and preview the diagnostic details that will be submitted.
- Automatic collection of critical diagnostics:
  - App Version & Build Code.
  - Android Version & API Level.
  - Device Manufacturer, Model, and Brand.
  - Active Widgets (configured widgets count).
  - Database metadata (DB size, number of logs, number of forecast snapshots).
  - Location settings (GPS capabilities, current coordinates/locations of active widgets).
  - Battery/Charging status and Screen state.
- Checkbox options to include or exclude:
  - App logs (recent 1000 lines from database `app_logs` table).
  - Widget & DB configuration/metadata.
- A "Send Bug Report" button that packages all the details into a markdown format and uses the system sharesheet (`Intent.ACTION_SEND` with `text/plain` MIME type) to let the user email or share it with the developers.

## 2. UI Layouts

### 2.1 Settings Screen Section
Add a card inside `activity_settings.xml` with:
- Title: "Feedback & Bug Reports"
- Description: "Encountered an issue or want to suggest an improvement? Submit a detailed bug report with optional system diagnostics."
- Button: "Submit Bug Report" (pointing to `BugReportActivity`).

### 2.2 Bug Report Screen (`activity_bug_report.xml`)
Create a scrollable layout containing:
- A custom title/header with back button.
- An `EditText` for the bug description (multiline, hints like "Describe the bug (what happened and what you expected to see)...").
- Checkboxes:
  - `bug_report_include_logs_checkbox` (default true)
  - `bug_report_include_metadata_checkbox` (default true)
- A "Diagnostics Preview" card showing gathered device details.
- A "Send Bug Report" button (`rounded_button_blue`).

## 3. Implementation Steps

1. **Strings**: Add strings to `app/src/main/res/values/strings.xml`.
2. **Settings UI**: Edit `app/src/main/res/layout/activity_settings.xml` to add the bug report card.
3. **Settings Activity**: Edit `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt` to handle clicks on the "Submit Bug Report" button.
4. **Bug Report Layout**: Create `app/src/main/res/layout/activity_bug_report.xml`.
5. **Bug Report Activity**: Create `app/src/main/java/com/weatherwidget/ui/BugReportActivity.kt` to fetch details, build the report, and launch the share sheet.
6. **Manifest**: Register `com.weatherwidget.ui.BugReportActivity` in `app/src/main/AndroidManifest.xml`.
7. **Testing**: Write unit/Robolectric tests for the report generation logic to guarantee format and safety.

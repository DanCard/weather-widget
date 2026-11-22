# Plan: bug reports as a short summary plus an attached evidence zip

Status: approved 2026-11-21 ("update the plan, and implement").
User decisions: the goal ("the bug report format is suboptimal"); logs = recent window in full plus
older key events, not 24 h of everything ("Bugs usually happen recently").
Claude defaults (user did not choose; open to change): DB attached opt-in, desktop zip in
Downloads, widget image replayed from held RemoteViews.

## Evidence

Three reports from an outside tester (Diego, Samsung S23 SM-S916U, Android 17, build 26111801),
filed 21:07–21:10 MST on 2026-11-21 to daniecarde55@gmail.com:

1. **Logs arrive hard-wrapped.** The report is the email body (`ACTION_SENDTO` + `EXTRA_TEXT`).
   Gmail wraps at ~76 columns, splitting fields mid-token (`6[FUTURE]:Tuesday` /
   `Night@2026-11-24…`, the Open-Meteo URL, `Measured(...)` dumps). Lines can't be grepped or parsed
   reliably, and the new `HEADER_FIT` lines (commit 5d0fa4588) are ~600 characters.
2. **300 rows covered ~4 minutes.** Report 1's oldest row was 21:05:56. Routine tags fill the tail:
   14 `SNAPSHOT_SKIP` rows per source per sync, `DAILY_HISTORY_OVERWRITE`, 9 consecutive resize
   renders. The three reports, filed 3 minutes apart, are ~90% the same rows.
3. **No screenshot.** The body asks for one; none of the three had one. "Header buttons missing"
   could not be confirmed or ruled out (see that session's investigation).
4. **The strongest evidence is never sent.** The body is cut at 100 KB
   (`BugReportActivity.sendBugReport`), and the database (0.8–1.2 MB here) can't travel in text.
   For a phone we can't connect over adb, the database is what we would otherwise pull with
   `scripts/backup_databases.py`.
5. **Little context.** "back button doesn't work" named neither a widget nor a view. The app already
   logs taps (`INTERACTION_E2E`, `CLICK_DAILY`, `DAILY_NAV_*`), but the report doesn't put them in
   front of the reader.

`app_logs` keeps 72 h (`RetentionPolicy.APP_LOG_HOURS`), capped at 50k unprotected rows
(`WeatherRetentionManager`), so the data for a longer window already exists on the device.

## Proposed change

### 1. Email body: a summary that survives wrapping (Android)

The body keeps only short lines (< 76 chars where practical), in this order:

1. The user's description.
2. **Recent taps:** the last ~10 interaction rows (`INTERACTION_E2E`, `CLICK_*`, `DAILY_NAV_*`,
   `TOGGLE_VIEW_*`, `WIDGET_TOAST`), one per line, as `21:06:41 widget 12 ACTION_SHOW_TOAST "No
   additional history available"`.
3. Version, device, display, timezone, launcher (today's DEVICE INFO / DISPLAY sections).
4. Per widget: view, source, `size=WxHdp`, and the header decision reduced to
   `buttons=SHOWN 3 (home, months)` or `buttons=HIDDEN`. The full `HEADER_FIT` line goes in the zip.
5. A list of the zip's contents.

The current "Diagnostics preview" on the form shows exactly this body, so the user sees what's sent.

### 2. Attached zip: the evidence (Android)

`weather-widget-report-<yyyyMMdd-HHmmss>.zip`, written to `cacheDir/bug_reports/` and shared
through a new `androidx.core.content.FileProvider` (`${applicationId}.bugreports`, one
`<cache-path>`):

| File | Content | Gated by |
|---|---|---|
| `summary.txt` | The email body, unwrapped | always |
| `logs.tsv` | Every `app_logs` row from the last **2 h**, plus **key events** from the full 72 h retained; oldest first: `epoch_ms`, `local_time`, `part` (`recent`/`key`), `level`, `tag`, `message`; one row per line; tabs/newlines escaped | "Include logs" checkbox |
| `widget-<id>.png` | Each widget as the app last pushed it (see §3) | "Include logs" checkbox |
| `weather_database.db` | A consistent copy of the Room DB (WAL checkpoint, then `VACUUM INTO` a temp file) | "Include widget configuration & DB stats" checkbox (label unchanged: a new label needs 19 translations; the summary's ATTACHED ZIP list names the DB copy) |

- Why 2 h + key events (measured on the Galaxy Z Fold4, 2026-11-21): 1 h = 576 rows/49 KB,
  2 h = 1,020 rows/86 KB, 24 h = 16,929 rows/1.8 MB, 24 h of WARN/ERROR = 63 rows. Symptoms are
  recent, but causes are often hours older (a morning location move, a stalled prune, an overnight
  failed sync, `SYNC_CANCELLED` at setup), so the sparse key events cover the full retention.
- Key events (`:shared` `BugReportLogs.KEY_EVENT_TAGS`, one list for both platforms): any
  WARN/ERROR row, plus `CONFIG`, `NO_LOCATION`, `LOCATION_CHANGE_REFRESH`, `LOCATION_FETCH_PENDING`,
  `LOCATION_MIGRATION`, `TIMEZONE_CHANGE`, `SYNC_CANCELLED`, `NET_FETCH_START`, `NET_FETCH_COMPLETE`,
  `HISTORY_PRUNE`, `PROC_EXIT`, `COLD_START_PERF`, `CLICK_WATCHDOG`, `HEADER_FIT`, `SOURCE_ORDER`,
  `SOURCE_LIST_EDIT`, `SETTINGS_REFRESH`, `POWER_CONNECTED_EVENT`, `DB_CREATE`,
  `DB_DESTRUCTIVE_MIGRATION`. Separate from `APP_LOG_PROTECTED_TAGS` (retention), which holds
  high-volume push/paint breadcrumbs.
- Size is logged (`BUG_REPORT_BUILT bytes=… recentRows=… keyRows=… widgets=…`) so we can tune it.
- API keys: `logs.tsv` rows are already redacted on write (`ApiKeyRedaction`). Keys are stored in
  SharedPreferences (`WeatherSourcePreferences.apiKey`), not the database, and preferences are never
  attached. A test still scans the DB copy for key-shaped strings.
- The zip is deleted when a new report is built, and on startup if older than 1 day.

**Intent:** `ACTION_SEND`, type `application/zip`, `EXTRA_EMAIL` daniecarde55@gmail.com,
`EXTRA_SUBJECT`, `EXTRA_TEXT` = summary, `EXTRA_STREAM` = content URI, `FLAG_GRANT_READ_URI_PERMISSION`,
wrapped in a chooser. Unlike `ACTION_SENDTO mailto:`, the chooser also lists non-email apps;
Gmail and other mail apps honour all four extras.

**Subject:** `Weather widget bug report: <first 40 chars of description> (<versionCode>)`.

### 3. Widget images

Hold the last **full** `RemoteViews` per widget in `WidgetPushDispatcher`, plus the partial
`RemoteViews` pushed since (cleared on the next full push; capped at 16, oldest dropped and the
image marked `partial-overflow`). At report time, `apply()` the full tree into a `FrameLayout` sized
to the widget's resolved dp (`WidgetSizeCalculator.getWidgetSize`) at the device density,
`reapply()` each partial in order (how the launcher merges `partiallyUpdateAppWidget`), lay out,
draw to a bitmap, PNG it. Same inflate path as `HeaderTouchZoneAlignmentRoboTest.inflateAndLayout`.

- Shows exactly what the app sent, including header buttons and their visibility. It does not show
  launcher cropping, Samsung rounding, or the launcher's own scaling.
- If the process restarted since the last push there is no tree; the summary says
  `image: not rendered since app process start` (same fallback as the `header:` line).
- Replaying partials makes the image current without a repaint, so the report never triggers
  rendering, fetching, or view-state changes.
- Memory: one full tree (graph bitmap ≤ ~900 KB, `MAX_BITMAP_PIXELS`) plus up to 16 small partials
  per widget, process lifetime only.

### 4. Desktop

`mailto:` can't carry attachments. Proposal: the desktop "Submit Bug Report" button writes the same
zip (`summary.txt`, `logs.tsv` from the desktop DB, `window.png` of the main window via
`ComposeWindow` → `BufferedImage`, optional DB copy) to `~/Downloads/`, opens the mail client with
the summary body (as today), and opens the file manager on the zip with a one-line note in the body:
"Attach weather-widget-report-….zip from Downloads". Behaviour and file names match Android.

### 5. Shared code (`:shared`)

- `BugReportSummary`: builds the summary text from plain inputs (device, display, widgets, taps,
  description), both platforms.
- `BugReportLogTsv`: row → escaped TSV line, plus the header, both platforms.
- `BugReportFormat` (exists): timezone line; add `subject(description, version)`.

Platform code only gathers inputs and writes files (Android: Room, RemoteViews, FileProvider;
desktop: SQLite, Compose window, `Desktop.open`).

## Tests

- `:shared` JVM: TSV escaping (tabs, newlines, CR, non-ASCII), summary line widths (no line over
  76 chars except the description), subject truncation.
- Robolectric integration (`BugReportActivity` + real Room DB + `WidgetPushDispatcher`):
  seed `app_logs` across 80 h with routine and key-event tags → `logs.tsv` holds every row of the
  last 2 h, only key events/WARN/ERROR from 2–72 h, nothing older, marked `recent`/`key`; push a widget's RemoteViews →
  zip contains a non-empty `widget-<id>.png` of the expected pixel size; checkboxes remove
  `logs.tsv`/PNGs/DB; the sent intent is `ACTION_SEND` with a readable `EXTRA_STREAM` and the
  subject format.
- DB copy: open the copied file with real SQLite and read `app_logs`; confirm no API key strings.
- Desktop JVM: zip contents and names match Android's; `window.png` skipped when headless.
- Device: on the Galaxy S23 API 37 emulator and a physical Samsung, file a report into Gmail;
  confirm the attachment opens, `logs.tsv` lines are unwrapped, the PNG matches a screenshot.

## Docs

`arch/operations.md` bug-report paragraph: what's in the body vs the zip, how to load `logs.tsv`
(`sqlite3 :memory: '.mode tabs' '.import logs.tsv logs'`), and opening the attached DB directly
instead of `backup_databases.py` for remote testers.

## Decisions

1. Log window: 2 h in full + 72 h of key events (user, 2026-11-21).
2. Database: attached, opt-in under the metadata checkbox (Claude default).
3. Desktop: zip in `~/Downloads`, mail body + file manager (Claude default).
4. Widget image: replay held full + partial RemoteViews, no repaint (Claude default).

## As built (2026-11-21)

- Desktop has no opt-in checkbox, so its zip always carries the DB copy. The zip stays in
  Downloads until the user attaches it, and the draft names it.
- Desktop images are screen captures of each showing window (`Robot`), `window-<n>.png`.
- `FileProvider.getUriForFile` failures fall back to a text-only send, logged, never a crash.
- Removed the old body-only strings (`bug_report_email_subject`, `bug_report_no_logs`,
  `bug_report_logs_excluded`) from all 20 locale files.
- Widget images inflate with the application context: the AppCompat activity inflater made
  `AppCompatImageView`, whose `setImageBitmap` RemoteViews refuses (`ActionException`, API 37
  emulator). The Robolectric test now pushes a graph bitmap and fails on the old code.
- Memory: a complete-body partial (`bodyComplete`) drops the partials before it, so at most about
  two graph bitmaps per widget are held. Fold4, 4 widgets, 52 repaints: native heap 27.2 → 25.3 MB,
  total PSS 95 → 94 MB (no growth).
- Recent taps come from the 2 h window only; `SETTINGS_REFRESH` is also a key event and showed
  days-old rows without dates.
- Verified end to end on the Galaxy Z Fold4 (2026-11-22): Gmail sent a 6.7 MB zip to
  daniecarde55@; it unzipped intact with 1,636 recent + 435 key log rows back to Nov 19, four
  widget PNGs at their real sizes, and a 33 MB database copy passing `integrity_check`.

# Android Emulator & Device Coverage Analysis

**Date**: 2026-11-01  
**Context**: Evaluating options for creating a new Android Virtual Device (AVD) to expand test and UI coverage beyond current physical phones and emulators.

---

## 1. Current Fleet Inventory

| Device / Serial | Type | Form Factor | Display (px @ dpi) | OS / API Level |
| :--- | :--- | :--- | :--- | :--- |
| **Pixel 7 Pro** (`2A191FDH300PPW`) | Physical | Standard Flagship Phone | 1080×2340 @ 420 dpi | Android 17 (API 37 Preview) |
| **Galaxy Z Fold4** (`RFCT70G352Y`) | Physical | Book Foldable (Inner) | 1812×2176 @ 420 dpi (~1:1 aspect) | Android 16 (API 36) |
| **Medium Phone** (`emulator-5554`) | AVD | Standard Medium Phone | 1080×2400 @ 420 dpi | Android 16 (API 36) |
| **Generic Foldable** (`emulator-5556`) | AVD | 7.6" Foldable | 1768×2208 @ 420 dpi | Android 16 (API 36) |
| *Medium_Phone_API_30* | AVD on disk | Standard Medium Phone | 1080×2400 @ 420 dpi | Android 11 (API 30) *(inactive)* |

**Observation**: Every actively running target is either a phone or foldable running cutting-edge Android (API 36 or 37).

---

## 2. Coverage Gaps & Candidate Options

### Option 1: Large-Screen Tablet (Top Recommendation)
* **Candidate**: **Pixel Tablet** or **Medium Tablet / 10.1" WXGA** (API 36)
* **Why it adds value**:
  - **Zero tablet coverage**: No current physical device or emulator represents a tablet form factor.
  - **Widget behavior**: Tablet home screens have landscape-first orientations, 6–8 column grids, and wider cell dimensions. Weather Widget specifies `maxResizeWidth="800dp"`, which can be exercised on a tablet canvas.
  - **Activity layouts**: Multi-pane activities, radar view (`activity_radar.xml`), and settings (`activity_settings.xml`) layout differently on a true widescreen landscape display than on the near-square Fold4 inner screen.
  - **System UI**: Exercises Android 12L+ tablet persistent taskbar, multi-window splits, and large-screen insets.
  - **Zero overhead download**: Can use the already-installed `system-images;android-36;google_apis_playstore;x86_64`.

### Option 2: Lower-API Compatibility Target (`minSdk = 26`)
* **Candidate**: **Medium Phone or Pixel 4a on API 28 (Pie) or API 30 (Android 11)**
* **Why it adds value**:
  - The project supports `minSdk = 26`, but runtime validations only happen on API 36/37.
  - **Legacy RemoteViews**: Android 12 (API 31) significantly changed widget architecture (`targetCellWidth`/`targetCellHeight`, rounded corners via `system_app_widget_background_radius`, and dynamic colors). Older API levels fall back to legacy sizing and views.
  - **Background execution**: Older Android versions handle WorkManager, AlarmManager wakeups, and notification channels differently.
  - *Note*: An API 30 AVD (`Medium_Phone_API_30`) is already defined on disk and has its system image cached.

### Option 3: 16 KB Page-Size Device
* **Candidate**: **Pixel 8 / 9 on API 36 or 37 with 16k page size** (`ps16k` system image)
* **Why it adds value**:
  - Google Play requirements are shifting toward 16 KB page-size compatibility.
  - Validates SQLite native libraries and JNI/NDK memory alignments under 16k page kernels.

### Option 4: Small Screen / Compact Phone
* **Candidate**: **Small Phone** or **Pixel 4a / 5** (5.4"–5.8" or 720p density)
* **Why it adds value**:
  - Validates widget layout wrapping, text truncation, and icon padding when space is constrained (e.g. 2×2 widget on a narrow 320dp/360dp grid).

---

## 3. Implemented Emulator

* **AVD Name**: `Pixel_Tablet_API36`
* **Device Profile**: `pixel_tablet` (Google Pixel Tablet)
* **Resolution**: 2560×1600 @ 320 dpi (16:10 landscape)
* **System Image**: Android 16 (API 36) Google Play `x86_64`
* **Config Path**: `~/.android/avd/Pixel_Tablet_API36.avd/config.ini`
* **Hardware Specs**: 4096 MB RAM, 12 GB storage, swiftshader GPU rendering, hardware keyboard enabled.
* **Launch Command**:
  ```bash
  $ANDROID_HOME/emulator/emulator -avd Pixel_Tablet_API36
  ```

---

## 4. Summary & Future Options

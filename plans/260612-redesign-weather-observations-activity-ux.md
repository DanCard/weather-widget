# Restyle "Weather Observations & Logs" window (desktop + Android)

## Context

The observations window (desktop `ObservationsWindow.kt`, Android `WeatherObservationsActivity`) looks dated:
grey cards (desktop uses Material3's stock `surfaceVariant` — never deliberately chosen), small dim left-side
text (12sp / 11sp at 70% alpha), and vertically bloated cards (4–5 visual rows each; only 4–5 stations fit).
User wants: **black background, larger/brighter left text, vertical compression, and "a bit of color"**.

Screenshots captured during planning: `/tmp/obs_desktop.jpg` (490x859), `/tmp/pixel_home.jpg` (Pixel 7 Pro).

User decisions (via AskUserQuestion):
- **Compact dark cards**: pure black window bg, near-black cards `#121214` with 1px hairline border `#2A2A2E`, 3 lines per card
- **Bigger + brighter text**: ID/distance 12→14sp, timestamps 11→12sp, drop the 0.7 alpha dimming
- **Desktop first, then Android** (parity per existing convention — desktop views must match Android)
- **Color accents**: reuse the existing temp→color gradient for the big temperature value; station-type as inline colored text

## Design spec (both platforms)

```
╭────────────────────────────────────╮
│ AE6EO MOUNTAIN VIEW          80.0° │  name 16sp bold white · temp colored by tempToColor, bold
│ AW020 · 1.4 mi · PERSONAL          │  14sp; PERSONAL/OFFICIAL inline colored text (no pill bg)
│ Reported 4:25 PM · Fetched 4:44 PM │  12sp, #AAAAAA full alpha
╰────────────────────────────────────╯  condition ("Clear") sits inline left of the temp, bodyMedium
```

- Window/list background: `#000000`
- Card: `#121214` fill, 1px border `#2A2A2E`, corner radius ~12dp, inner padding 10dp, 3dp vertical gap between cards
- Temperature value: colored via existing temp→color gradient (cold blue ≤50° → mild ~70° → hot red ≥90°)
- Station type: `OFFICIAL` = `#4FC3F7` (light blue, readable on black), `PERSONAL` = `#B0B0B8` (grey) — plain bold ~12sp text, no badge background
- Vertical compression: collapse the separate condition/badge row and the two spacers; everything fits in 3 text lines

## Phase 1 — Desktop

**File: `desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt`**

1. Card composable (lines ~203–241):
   - `CardDefaults.cardColors(containerColor = Color(0xFF121214))` + `border = BorderStroke(1.dp, Color(0xFF2A2A2E))`
   - Restructure to 3 lines per spec; remove the two `Spacer`s and the separate condition/Badge row
   - Move condition text inline (left of temp); replace `Badge` with inline colored `Text`
   - Temp text color from temp→color mapping (see step 3)
   - ID/distance line → 14sp; timestamp line → 12sp, no `.copy(alpha = 0.7f)`
2. Window chrome (lines ~122–190):
   - `Surface(color = Color.Black)`; restyle `TabRow` (black container, accent indicator) and header so the stock Material purple-grey disappears
   - LogList tab: black background, keep/adopt terminal look (Android already uses `#0A0A0A` + green monospace — match it)
3. Temp→color reuse: `TemperatureTrayPainter.tempToColor` (lines 75–84) is private — promote it to an internal top-level function or small `DesktopTempColors` object in the same module and call it from both `TemperatureTrayPainter` and `ObservationsWindow`. Do NOT duplicate the thresholds.
4. `LazyColumn` padding 8dp → 6dp, item vertical padding 4dp → 3dp.

**Verify:** `scripts/buildStart.sh` (rebuilds distributable + restarts app — per standing user preference, run it without asking), reopen the window (main popup → 🌡️), screenshot via
`WID=$(xdotool search --name "Weather Observations & Logs") && import -window $WID /tmp/obs_after.png && convert ... .jpg`,
send before/after to user, iterate until approved.

## Phase 2 — Android (after desktop look approved)

**Files:**
- `app/src/main/res/layout/item_weather_observation.xml` — restructure card to the same 3 lines:
  merge "Station Reported:" + "App Fetched:" into one line ("Reported 4:25 PM · Fetched 4:44 PM");
  replace the "Station type: PERSONAL" pill with inline colored text on the ID/distance line; text sizes 14sp/12sp
- `app/src/main/res/drawable/bg_surface_card.xml` — either repoint to new colors or add a new `bg_obs_card.xml` (`#121214` fill, `#2A2A2E` stroke) so other screens using `bg_surface_card` are not unintentionally changed — check usages first
- `app/src/main/res/layout/activity_weather_observations.xml` — root background → `#000000`; consider dropping the "Real-time data from nearby stations" subtitle line (vertical compression); Fetch Logs terminal styling already black/green — keep
- `app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt` (adapter lives here or nearby) — set temp text color programmatically via existing `TemperatureGraphStyle.tempToColor` (`app/.../widget/TemperatureGraphStyle.kt:53`); set station-type text color (blue/grey)

**Verify:** `./gradlew installDebug`, open via widget 🌡️ tap (activity is not exported — adb `am start` is denied; user taps or we use `adb shell input tap` on the widget target), `adb exec-out screencap` → convert to JPG → compare.

## Process/tools recommendation (user asked)

- **Loop:** edit → `buildStart.sh` → screenshot window → side-by-side compare → user feedback. Desktop is the fast iteration surface; Android is a port once approved.
- For future divergent design choices: implement 2–3 variants behind a quick local toggle and screenshot each, or use AskUserQuestion ASCII previews (worked well here). No external design tooling needed for a window this small.

## Out of scope

- Other windows (ForecastHistoryWindow, Settings) — same treatment could follow later if the user likes the result
- Shared theme extraction across desktop windows (worth considering after two windows share the style, not before)

# Emulator widget display issue & GPU renderer mode configuration

Date: 2026-10-31

## Outcome

Diagnosed why the weather widget intermittently failed to display on the emulator during Pixel Launcher starts (~25% of starts), debunked Opus's earlier assumption that this was solely an emulator Quick Boot snapshot restore quirk, and configured both emulator AVDs to use software GPU rendering (`swiftshader_indirect`) to avoid gfxstream host GPU synchronization stalls.

## User prompts

1. emulator: widget doesn't display.  Opus said this is a google pixel launcher issue.  Happens about 25% of the times I start the launcher.
2. I'm only talking about the weather widget
3. how to change Emulator GPU renderer mode ?
4. can you do it?
5. write session summary to summaries/

## Key Findings & Diagnostics

1. **Initial Blank State Reproduction:**
   - On session start on `emulator-5554` (`Medium_Phone_API_36`), Screen 1 displayed the weather widget with only the dark glass background (`@drawable/widget_background`) and the `<` / `>` navigation chevrons (`nav_left`, `nav_right`). All graph curves, temperatures, condition icons, and headers were missing.
2. **View Hierarchy Analysis:**
   - `dumpsys activity top` confirmed `graph_view` (`app:id/graph_view`) was present, enabled, sized 882×900 px, and marked `VISIBLE` (`V`).
   - The individual text views (`current_temp`, `api_source`, `settings_icon`) were marked `INVISIBLE` (`I`). This is intentional in Daily mode (`DailyGraphRenderer.kt:314-320`) because all header text, weather icons, rain labels, and graph lines are drawn directly into the single bitmap loaded into `graph_view`.
   - **Debunking Opus's previous conclusion:** Opus previously concluded that text and icons also failed to render, inferring a total view layout failure. In reality, `graph_view`'s bitmap was the only missing element.
3. **Immediate Heal on Invalidation:**
   - Any touch, gesture (such as an overscroll swipe), or background/foreground cycle (Settings then Back) immediately caused the launcher surface to draw the bitmap without any app code running or any new `RemoteViews` push.
4. **Root Cause:**
   - When Google Pixel Launcher starts up and binds `AppWidgetHostView` on a secondary workspace page (Screen 1), Android 15/16 (API 36) with gfxstream host GPU emulation experiences a race condition where the initial draw occurs before the ~1 MB Ashmem bitmap texture upload completes. Because the view is not marked dirty again after the upload finishes, it remains blank until an external input dirties the RenderNode.

## Changes Applied

Updated both emulator AVD configurations from `auto` host GPU mode to `swiftshader_indirect` (Google SwiftShader CPU emulation), bypassing host GPU driver / gfxstream texture sync issues:

- `~/.android/avd/Medium_Phone.avd/config.ini`:
  - `hw.gpu.enabled=yes`
  - `hw.gpu.mode=swiftshader_indirect`
- `~/.android/avd/Generic_Foldable_API36.avd/config.ini`:
  - `hw.gpu.enabled = yes`
  - `hw.gpu.mode = swiftshader_indirect`

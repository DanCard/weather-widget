# Custom "temperature change" notification sound (desktop + Android)

## Evidence & Root Cause
- **Desktop**: `notifyDesktop` in `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt` invokes `notify-send -u <urgency> -a "Weather Widget" <summary> <body>` with no sound hint and no audio playback. Additionally, `xfce4-notifyd` on this system has `/notify-play-sounds` set to `false` and `/expire-timeout` set to `8`s, so notifications appear silently and disappear quickly.
- **Android**: `DominantTempChangeNotifier` in `app/src/main/java/com/weatherwidget/notify/DominantTempChangeNotifier.kt` creates `NotificationChannel("dominant_temp_change", ...)` with the system default notification sound. Because Android O+ notification channel sounds are immutable once created, changing the sound requires migrating to a new channel ID and deleting the legacy channel.

## Proposed Fix
1. **Generate the audio asset** using `~/projects/phone-sounds/makevoice.sh "temperature change" --notif-only --make-only` (chipmunk pitch `1.5`, 48 kHz Ogg Vorbis `.ogg`), and copy it into both platforms:
   - `desktop/src/main/resources/sounds/temperature_change.ogg`
   - `app/src/main/res/raw/temperature_change.ogg`
2. **Desktop (`DesktopProcess.kt` & `DaemonRuntime.kt`)**:
   - Add an optional `soundResource: String? = null` parameter to `notifyDesktop(...)` (or a dedicated `playDesktopSound(resourcePath)` helper called by `notifyDesktop`).
   - Extract the bundled `.ogg` resource once to `appDataDir().resolve("sounds/temperature_change.ogg")` (best-effort, non-throwing) and play it fire-and-forget via `pw-play` (falling back to `paplay` if `pw-play` is unavailable).
   - Pass `soundResource = "/sounds/temperature_change.ogg"` from `DaemonRuntime.kt` when `DominantTempWatchDecision.Fire` triggers.
3. **Android (`DominantTempChangeNotifier.kt`)**:
   - Bump `CHANNEL_ID` from `"dominant_temp_change"` to `"dominant_temp_change_v2"` and delete the old `"dominant_temp_change"` channel in `ensureChannel` so existing installs pick up the new custom sound without duplicate channels in system settings.
   - Configure the channel's sound with `setSound(soundUri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())` pointing to `android.resource://${context.packageName}/${R.raw.temperature_change}`, and also set `.setSound(soundUri)` on `NotificationCompat.Builder`.

## Verification
- Unit tests in `:desktop` and `:app` (`DominantTempChangeNotifier` / desktop notification helper tests) + `./scripts/unit-tests.sh`.
- Runtime verification: trigger a test `notifyDesktop(..., soundResource = "/sounds/temperature_change.ogg")` on desktop to confirm the chipmunk voice plays audibly alongside the `notify-send` banner, and build/install `:app` (`./gradlew installDebug`) to verify the Android notification channel registers with the custom sound.

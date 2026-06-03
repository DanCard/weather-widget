# Objective
Enhance the visibility of the system tray temperature for the Linux desktop client by utilizing native AppIndicator text labels (similar to the system clock). We will also optimize the fallback square image to be more legible on systems without native label support.

# Key Files & Context
- `desktop/build.gradle.kts`: Will be updated to include the Dorkbox SystemTray dependency.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`: Will be refactored to use `dorkbox.systemTray.SystemTray` instead of `java.awt.SystemTray`, setting both the icon and the `status` text.

# Implementation Steps

1. **Add Dependency**:
   - Add `implementation("com.dorkbox:SystemTray:4.4")` to `desktop/build.gradle.kts`.

2. **Refactor Tray Icon Rendering (`createTemperatureTrayImage`)**:
   - Remove the 90-degree rotation (`graphics.rotate(Math.toRadians(90.0))`). 
   - Draw the text horizontally in the 64x64 square. This will dramatically improve legibility on platforms where the native text label isn't supported (like Windows or non-AppIndicator Linux desktops).

3. **Refactor `TemperatureSystemTray` Composable**:
   - Remove AWT SystemTray imports (`java.awt.SystemTray`, `java.awt.TrayIcon`, `java.awt.PopupMenu`, `java.awt.MenuItem`).
   - Import `dorkbox.systemTray.SystemTray` and `dorkbox.systemTray.MenuItem`.
   - Initialize the tray using `SystemTray.get()`. If `null`, print a not-supported message and return.
   - Set the image using `tray.setImage(createTemperatureTrayImage(temperature))`.
   - Set the native Linux AppIndicator label using `tray.status = temperature?.let { formatTrayTemperature(it) + "°" } ?: "Weather Widget"`.
   - Build the Dorkbox menu dynamically (`tray.menu.add(...)`).
   - In the Compose `onDispose` block, cleanly remove the tray icon using `tray.shutdown()`.
   - Inside the `LaunchedEffect(temperature)`, update both `tray.setImage(...)` and `tray.status = ...` so the native label updates alongside the icon.

# Verification & Testing
- Compile and run the desktop module (`./gradlew :desktop:run`).
- Verify the system tray icon renders correctly.
- Ensure that on Linux environments with AppIndicator support (like Ubuntu/GNOME), the temperature appears as a large, native text label immediately adjacent to the square icon.
- Verify the tray popup menu items (Show, Settings, Update location..., Quit) function correctly.
- Verify the app exits cleanly when Quit is pressed and the tray icon is removed from the panel.

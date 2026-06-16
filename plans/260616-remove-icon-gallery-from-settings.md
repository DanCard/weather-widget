# Plan: Extract Icon Gallery to Dedicated Screen

We will move the icon gallery out of the main Settings screen into a dedicated `IconGalleryActivity`. This will clean up the Settings layout, improve launch performance, and make the codebase more modular.

## 1. Steps

1. **Plan & Document**: Create this plan file in `plans/`.
2. **Strings**: Check strings in `strings.xml`. (We will reuse `@string/icon_preview_title` and `@string/icon_preview_description`, and add `@string/view_icon_gallery`).
3. **Gallery Layout**: Create `app/src/main/res/layout/activity_icon_gallery.xml`.
4. **Gallery Activity**: Create `app/src/main/java/com/weatherwidget/ui/IconGalleryActivity.kt` and move the `GalleryIcon` data class and list of icons (`allGalleryIcons`) from `SettingsActivity.kt` to here.
5. **Settings UI**:
   - Replace the large gallery card section in `activity_settings.xml` with a simple "View Icon Gallery" button.
6. **Settings Activity**:
   - Remove `allGalleryIcons` list and `setupIconGallery()` method.
   - Wire the "View Icon Gallery" button to launch `IconGalleryActivity`.
7. **Manifest**: Register `com.weatherwidget.ui.IconGalleryActivity` in `app/src/main/AndroidManifest.xml`.
8. **Testing**:
   - Write a Robolectric test to verify `IconGalleryActivity` opens and displays all icons.
   - Verify existing unit tests still pass.

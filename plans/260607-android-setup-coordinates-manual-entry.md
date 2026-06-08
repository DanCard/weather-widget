# Plan: Android Config Coordinates Manual Entry

## Objectives
Enhance the Android widget configuration screen (`ConfigActivity`) to allow users to manually input latitude and longitude coordinates. This brings feature parity with the desktop application. The location resolution logic will be moved to the shared module so both environments share the same reverse-geocoding coordinates logic.

---

## 1. Architectural Changes & Code Sharing

### 1.1 Shared Location Model
Move `ResolvedLocation` from the `:desktop` module to the `:shared` module under `com.weatherwidget.data.model`. This model represents the output of location resolution:
```kotlin
data class ResolvedLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
    val source: String,
    val detail: String? = null,
    val isFresh: Boolean = true,
)
```

### 1.2 Shared Location Resolver
Extract the Nominatim text search and coordinates reverse-geocoding logic from the desktop `LocationResolver` into a new shared class `SharedLocationResolver` under package `com.weatherwidget.data.repository` in `:shared`.
* Desktop's `LocationResolver` will import `SharedLocationResolver` and delegate text search, reverse geocoding, and IP prefill functions to it.
* Android's `ConfigActivity` will inject `SharedLocationResolver` via Hilt and use it to reverse-geocode coordinates.

---

## 2. Implementation Steps

### 2.1 Shared Module Changes
* Create [ResolvedLocation.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/model/ResolvedLocation.kt) containing the data class.
* Create [SharedLocationResolver.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/repository/SharedLocationResolver.kt) to handle geocoding using `NominatimApi` and `IpGeolocationApi`.

### 2.2 Desktop Module Changes
* Update [LocationResolver.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/LocationResolver.kt) constructor to receive `sharedLocationResolver: SharedLocationResolver` and remove duplicate code.
* Update [LocationPicker.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/LocationPicker.kt) to import the shared `ResolvedLocation`.
* Update [Main.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt) to instantiate and pass `SharedLocationResolver` to `LocationResolver`.

### 2.3 Android Module Changes
* Add coordinate string resources to [strings.xml](file:///home/dcar/projects/weather-widget/app/src/main/res/values/strings.xml):
  * `or_enter_coordinates` -> "Or enter coordinates:"
  * `latitude_hint` -> "Latitude (e.g. 37.422)"
  * `longitude_hint` -> "Longitude (e.g. -122.0841)"
  * `use_coordinates` -> "Use Coordinates"
* Add layout elements for Latitude, Longitude (EditText fields with `inputType="numberDecimal|numberSigned"`), and a "Use Coordinates" button to [activity_config.xml](file:///home/dcar/projects/weather-widget/app/src/main/res/layout/activity_config.xml).
* Provide DI bindings in Hilt's [AppModule.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/di/AppModule.kt) for `NominatimApi`, `IpGeolocationApi`, and `SharedLocationResolver`.
* Update [ConfigActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/ConfigActivity.kt) to:
  * Inject `sharedLocationResolver`.
  * Set a click listener on the "Use Coordinates" button.
  * Validate inputs: ensure they are within correct coordinate ranges (`-90..90` for latitude, `-180..180` for longitude).
  * Launch a coroutine to resolve the pretty location label using `sharedLocationResolver.fromCoordinates`.
  * Show a Toast with the resolved location name and save the coordinates to widget preferences.

---

## 3. Verification Strategy

### 3.1 Unit Testing
Create a Robolectric unit test under `app/src/test/java/com/weatherwidget/ui/ConfigActivityRobolectricTest.kt` to verify coordinate configuration logic:
* Mock `SharedLocationResolver` to return a predefined geocoded location.
* Enter valid latitude/longitude values and perform click on "Use Coordinates".
* Verify coordinates are correctly saved to widget preferences under `weather_widget_prefs`.
* Verify the resolved location Toast is successfully displayed.

### 3.2 Build Verification
Ensure all modules build correctly and tests pass:
* `./gradlew assembleDebug`
* `./gradlew test`

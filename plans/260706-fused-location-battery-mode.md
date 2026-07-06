# Plan: Always Use Fused Last-Known Location in Battery Mode (Not Charging)

## Context — Why this change
The widget's GPS resampler was originally configured to only run background GPS updates when charging or when the battery level was above 70%. When unplugged and below 71%, the location resampling was skipped entirely (`outcome=skipped_battery`).

To keep the location accurate without draining the battery when the phone is unplugged (on battery), we want to:
1. Retrieve the **last known location** (passive, non-GPS-activating query via Google Play Services Fused Location Provider) **always** when not charging.
2. Avoid active, battery-hungry GPS fixes when the phone is unplugged.
3. Completely bypass the battery level check so we do not skip location updates when unplugged.

---

## Technical Approach

### 1. Update `GpsResampler.kt`
Currently, `GpsResampler.resample` checks `shouldSample(isCharging, batteryLevel)` and early-returns if false.
Under the new requirements, we will:
- Remove the `shouldSample` battery gate check so that location checks are never skipped due to battery level or state.
- Determine whether to use an active fix or a passive last-known location based on the charging status:
  - **Charging**: Use active high-accuracy fix (`useActiveFix = hasBackgroundLocation`).
  - **On Battery (Not Charging)**: Always use passive last-known location (`useActiveFix = false`).
- Set `useActiveFix` as:
  ```kotlin
  val useActiveFix = isCharging && hasBackgroundLocation
  ```
- The `mode` string logged to the diagnostic database will be:
  - `"active_fix"` when `useActiveFix` is true.
  - `"last_location"` when `useActiveFix` is false.

This simplifies `GpsResampler.resample(context)` as follows:
```diff
     suspend fun resample(context: Context) {
         val batteryStatus: Intent? =
             context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
-        val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
         val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
-        if (!shouldSample(isCharging, batteryLevel)) {
-            appLogDao.log(LOG_TAG, "outcome=skipped_battery level=$batteryLevel charging=$isCharging")
-            return
-        }
 
         if (!permissionChecker(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
             appLogDao.log(LOG_TAG, "outcome=skipped_no_permission")
             return
         }
 
         // Without background-location permission an active fix request would be rejected;
         // degrade to the passive last-known location (best-effort).
         val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             permissionChecker(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
         } else {
             true
         }
-        val mode = if (hasBackgroundLocation) "active_fix" else "last_location"
-
-        val location = locationProvider(context, hasBackgroundLocation)
+        val useActiveFix = isCharging && hasBackgroundLocation
+        val mode = if (useActiveFix) "active_fix" else "last_location"
+
+        val location = locationProvider(context, useActiveFix)
         if (location == null) {
             appLogDao.log(LOG_TAG, "outcome=no_fix mode=$mode")
             return
         }
         healIfNeeded(context, location.latitude, location.longitude, trigger = "worker")
     }
```

We will also deprecate/remove the helper method `GpsResampler.shouldSample(...)` since it's no longer used to gate resampling.

---

### 2. Update Tests

#### A. Remove/Update `GpsResamplerGateTest.kt`
Since `shouldSample` is no longer used, we will delete `GpsResamplerGateTest.kt` or remove the `shouldSample` tests.

#### B. Update `GpsResamplerTest.kt`
We will update `GpsResamplerTest.kt` to reflect the new behavior:
- **`battery gate skips sampling when unplugged below threshold`**: This test is no longer valid. Instead, we will add a test to verify that when the battery is not charging (e.g. unplugged at 60%), we **do not skip** but instead call the location provider with `useActiveFix = false` (requesting last location).
- Modify the mock configurations to ensure that `isCharging` state correctly determines the boolean passed to `locationProvider`.

---

## Sequencing
1. Create this plan (`plans/260706-fused-location-battery-mode.md`) to discuss with the user.
2. Update `GpsResampler.kt` with the simplified logic.
3. Update `GpsResamplerTest.kt` to match the new behavior and delete `GpsResamplerGateTest.kt`.
4. Run unit tests (`./gradlew testDebugUnitTest`) to ensure everything is correct.
5. Trigger manual validation on the emulator to check logs.

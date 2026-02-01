# Weather Widget Architecture

## Overview

Android weather widget app with resizable widget support, dual-API data sources (NWS and Open-Meteo), and forecast accuracy tracking. Features a two-tier update system that separates UI updates from data fetches for optimal battery efficiency.

## Core Architecture

### Data Layer

#### Database (Room)
- **WeatherEntity**: Daily weather data (high/low temps, conditions, actual vs forecast)
  - Composite primary key: `(date, source)` allows comparison between NWS and Open-Meteo
  - Tracks `isActual` flag to distinguish observations from forecasts
  - `fetchedAt` timestamp for staleness checking

- **ForecastSnapshotEntity**: Historical forecast snapshots for accuracy tracking
  - Stores 1-day-ahead predictions before 8pm cutoff
  - Enables comparison of predicted vs actual temperatures

- **HourlyForecastEntity**: Hourly temperature data for interpolation
  - Enables smooth current temperature transitions
  - Used for UI-only updates without network requests

#### Repositories
- **WeatherRepository**: Coordinates data fetching from multiple APIs
  - Manages NWS and Open-Meteo API calls
  - Handles data persistence and cache invalidation
  - Fetches historical observations for accuracy tracking

### Widget Layer

#### Widget Provider
- **WeatherWidgetProvider**: Main widget lifecycle manager
  - Handles widget creation, updates, and user interactions
  - Manages navigation (history browsing, forecast scrolling)
  - API source toggling (NWS ↔ Open-Meteo)
  - **View mode toggling (Daily ↔ Hourly)**
  - Coordinates scheduled updates

#### Widget State Management
- **WidgetStateManager**: Persists per-widget state
  - Date offset for navigation (30 days history, 14 days forecast)
  - **View Mode**: Toggles between DAILY and HOURLY views
  - **Hourly Offset**: Tracks time navigation in hourly view (±24h window)
  - Current display source (NWS or Open-Meteo)
  - Navigation bounds checking
  - Accuracy display mode preference

#### Widget Sizing & Rendering
- **Responsive Layout**: Adapts to widget size (1x1 to 8+ columns)
  - 1x1: Today's high (+ current temp if space allows)
  - 1x3: Yesterday, today, tomorrow (text only)
  - 2x3: Graphical bars with high/low ranges
  - 4+ cols: Additional forecast days (2-5 days)

- **TemperatureGraphRenderer**: Renders graphical temperature bars (Daily View)
  - Height-based text scaling for readability
  - Past days show forecast overlay (yellow bar) for accuracy comparison
  - Multiple display modes: FORECAST_BAR, ACCURACY_DOT, SIDE_BY_SIDE, DIFFERENCE

- **HourlyGraphRenderer**: Renders hourly temperature trends (Hourly View)
  - Smooth Bezier curve connecting 24 data points
  - Dynamic vertical scaling based on min/max temp in window
  - Visual "NOW" indicator line
  - Adaptive density (Graph for 2+ rows, Text list for 1 row)

### Update System

#### Two-Tier Update Architecture

The widget uses separate update mechanisms for UI updates vs data fetches to minimize battery impact while maintaining current temperature accuracy.

**Update Strategy Table:**

| Update Type | Frequency | Method | Wakeup | Purpose |
|-------------|-----------|--------|--------|---------|
| **Current Temp UI** | 15-60 min (temp-based) | AlarmManager | No (opportunistic) | Update interpolated temp from cache |
| **Opportunistic UI** | ~30 min | JobScheduler (8+) | No (piggyback) | Update when system already awake |
| **Data Fetch** | 60-480 min (battery-aware) | WorkManager | Yes (controlled) | Fetch from APIs |
| **User Interaction** | Immediate | Direct DB read | N/A | Instant UI update + conditional fetch |
| **Screen Unlock** | Immediate | Direct DB read | N/A | UI update + fetch if charging & stale |

#### Update Components

**1. UI Update Scheduler (UIUpdateScheduler)**
- Uses `AlarmManager.setAndAllowWhileIdle()` for opportunistic updates
- Calculates next update time based on temperature change rate:
  - 15 min intervals: temp changing ≥6°F/hour
  - 20 min intervals: temp changing ≥4°F/hour
  - 30 min intervals: temp changing ≥2°F/hour
  - 60 min intervals: temp changing <2°F/hour
- No guaranteed wakeup - fires when device already awake from other activity
- Re-schedules itself after each update

**2. Opportunistic Update Job (OpportunisticUpdateJobService)**
- Android 8+ only (uses JobScheduler)
- Runs every ~30 minutes when device already awake
- Piggybacks on system wakeups (no independent wakeups)
- Checks for recent hourly data before updating

**3. Data Fetch Worker (WeatherWidgetWorker)**
- Battery-aware update intervals:
  - Plugged in: 60 min
  - Battery >50%: 120 min
  - Battery 20-50%: 240 min
  - Battery <20%: 480 min
- Fetches from NWS and Open-Meteo APIs
- Fetches historical observations (7 days)
- Fetches hourly forecasts (extended ±24h range for hourly view)
- Saves forecast snapshots (before 8pm daily)
- Triggers UI update scheduler after completion

**4. Staleness Checking (DataFreshness)**
- Determines if data needs refreshing (threshold: 30 minutes)
- Checks availability of hourly data for interpolation
- Used by user interaction handlers to decide if background fetch needed

**5. Screen Unlock Receiver (ScreenOnReceiver)**
- Listens for `ACTION_USER_PRESENT` (screen unlock)
- Always: UI-only update from cache (instant feedback)
- If charging + data stale: trigger background data fetch
- Battery-conscious: only fetches when plugged in

**6. User Interaction Handlers**
- **ACTION_REFRESH**: UI update first (instant), then conditional background fetch
- **Navigation (left/right arrows)**: Direct database read, immediate UI update
- **API Toggle**: Direct database read, immediate source switch
- All interactions provide instant visual feedback from cached data

### Temperature Interpolation

**TemperatureInterpolator**
- Calculates current temperature between hourly forecast data points
- Linear interpolation based on minutes into current hour
- Threshold: skips interpolation if temp difference <1°F
- Provides methods for calculating optimal update frequency
- Falls back to nearest hour if surrounding data unavailable

**Current Temperature Display Priority:**
1. Interpolated from hourly forecasts (most accurate)
2. API-provided current temp (fallback)
3. Hidden if unavailable

### Forecast Accuracy Tracking

**AccuracyCalculator**
- Compares 1-day-ahead predictions vs actual observations
- Separate tracking for high and low temperatures
- Metrics (30-day lookback):
  - Average error (MAE)
  - Directional bias (e.g., "forecasts run 2° high")
  - Maximum error
  - Percent within ±3°F
  - Accuracy score (0-5 scale)

**Display Modes:**
- **FORECAST_BAR**: Yellow overlay showing predicted range
- **ACCURACY_DOT**: Color-coded indicator (green ≤2°, yellow ≤5°, red >5°)
- **SIDE_BY_SIDE**: "72° (N:68°)" with source
- **DIFFERENCE**: "72° (N:+4)" with delta
- **NONE**: No comparison shown

### API Source Management

**Dual-API Strategy:**
- Both NWS and Open-Meteo fetched and stored equally
- Composite keys allow side-by-side comparison
- User preference modes:
  - **Alternate** (default): Pseudo-random initial source (varies daily + by widget ID)
  - **NWS Primary**: Prefer NWS with Open-Meteo fallback
  - **Open-Meteo Primary**: Prefer Open-Meteo with NWS fallback
- Tap API indicator to toggle display source (per-widget state)

**API Characteristics:**
- **NWS**: Free, US-only, official government data, no API key required
- **Open-Meteo**: Free, global coverage, no API key required, consistent format

## Data Flow

### Initial Widget Creation
```
1. WeatherWidgetProvider.onUpdate()
   ↓
2. Display loading state
   ↓
3. Trigger immediate data fetch (WeatherWidgetWorker)
   ↓
4. Fetch from both NWS and Open-Meteo
   ↓
5. Fetch historical observations
   ↓
6. Fetch hourly forecasts
   ↓
7. Save all data to Room database
   ↓
8. Update all widgets with new data
   ↓
9. Schedule next data fetch (battery-aware)
   ↓
10. Schedule UI update (AlarmManager + JobScheduler)
```

### Scheduled UI Update
```
1. AlarmManager fires (opportunistic, no wakeup)
   ↓
2. UIUpdateReceiver.onReceive()
   ↓
3. Trigger UI-only worker (WeatherWidgetWorker with KEY_UI_ONLY_REFRESH=true)
   ↓
4. Read hourly forecasts from database
   ↓
5. Interpolate current temperature
   ↓
6. Update all widgets (no network request)
   ↓
7. Schedule next UI update based on temp change rate
```

### User Interaction (Tap/Swipe)
```
1. User taps refresh / navigates / toggles API / toggles View
   ↓
2. BroadcastReceiver handles intent
   ↓
3. Direct database read (goAsync() for non-blocking)
   ↓
4. Immediate widget update (instant feedback)
   ↓
5. Check data staleness (parallel coroutine)
   ↓
6. If stale: trigger background data fetch
   ↓
7. Data fetch completes → update widgets again with fresh data
```

### Screen Unlock
```
1. ACTION_USER_PRESENT broadcast
   ↓
2. ScreenOnReceiver.onReceive()
   ↓
3. Trigger UI-only update (always, instant feedback)
   ↓
4. Check if charging (parallel)
   ↓
5. If charging: check data staleness
   ↓
6. If charging + stale: trigger background data fetch
```

## Battery Optimization Strategies

### Zero Independent Wakeups for UI
- AlarmManager uses `setAndAllowWhileIdle()` (opportunistic)
- JobScheduler piggybacks on existing system wakeups
- UI updates only happen when device already awake

### Battery-Aware Data Fetching
- Interval scales with battery level (60-480 min)
- Longer intervals on battery, shorter when charging
- WorkManager handles scheduling with constraints

### Intelligent Staleness Checking
- Only fetch new data if >30 min old
- User interactions check staleness before fetching
- Screen unlock only fetches when charging + stale

### Efficient Database Queries
- Direct database reads for user interactions (no worker overhead)
- Indexed queries on date and location
- Limit hourly forecast queries to ±3 hour window

### Cached Data Leveraging
- Hourly forecasts enable UI updates without network
- Temperature interpolation from cached data
- Historical data available for immediate navigation

## Error Handling

### Network Failures
- Try alternate API if primary fails
- Fall back to cached data with error indicator
- Display "offline" indicator with last update timestamp
- Retry with exponential backoff (WorkManager)

### GPS/Location Failures
- Fall back to last known location
- Default to Google HQ (37.4220, -122.0841) if no location
- Display location name for user awareness

### Missing Data
- Display "Tap to configure" for new widgets
- Show "--°" for unavailable temperatures
- Hide current temp if interpolation fails
- Toast notification when navigating beyond available data

### Database Errors
- Graceful degradation to empty state
- Logging for debugging
- Retry on next scheduled update

## Data Retention

- **Weather data**: 30 days (rolling window)
- **Forecast snapshots**: 30 days (for accuracy tracking)
- **Hourly forecasts**: Auto-cleanup of old data
- **Navigation range**: 30 days history, 14 days forecast

## Performance Considerations

### Widget Rendering
- Bitmap caching for graph rendering
- Height-based text scaling for readability
- Minimal margins to maximize content area
- Touch zones optimized for reliable input

### Database Performance
- Composite indexes on (date, source)
- Location-based queries with lat/lon filtering
- Limit queries to necessary date ranges
- Batch updates for multiple widgets

### Memory Management
- Coroutines for async operations
- goAsync() for BroadcastReceivers to avoid ANRs
- Expedited work requests for user interactions
- Cleanup of old data to prevent database bloat

## Build Configuration

- **Java**: Requires Java 21 (Android Studio bundled JDK)
- **JAVA_HOME**: `/home/dcar/Downloads/high/android-studio/jbr`
- **Gradle**: 8.13
- **Min SDK**: API level for widgets (check build.gradle)
- **Target SDK**: Latest stable Android version

## Testing Strategy

### Widget Testing
```bash
# Build and install
JAVA_HOME=/home/dcar/Downloads/high/android-studio/jbr ./gradlew installDebug

# On device/emulator:
# 1. Long-press home screen → "Widgets"
# 2. Find "Weather Widget" and drag to home screen
# 3. Resize to test different layouts (1x1, 1x3, 2x3, 4x3, etc.)

# Or use ADB:
adb shell am start -a android.appwidget.action.APPWIDGET_PICK
```

### Update Testing
- Monitor logs for scheduled updates
- Check AlarmManager and JobScheduler status
- Test user interactions (tap, navigate, toggle)
- Verify screen unlock behavior
- Check battery-aware intervals at different battery levels

### Data Testing
- Verify both APIs being fetched
- Check accuracy tracking calculations
- Test navigation bounds (30 days back, 14 forward)
- Validate temperature interpolation
- Confirm data cleanup (30-day retention)

## Future Enhancements

### Potential Improvements
- Weather condition icons/animations
- Multiple location support
- Custom location selection (map picker)
- Precipitation probability display
- Wind speed/direction
- Sunrise/sunset times
- Weather alerts/warnings
- Widget themes (light/dark/custom colors)
- Export accuracy statistics
- Notification for significant weather changes

### Optimization Opportunities
- Differential updates (only changed data)
- Predictive pre-fetching based on user patterns
- Machine learning for accuracy prediction
- Adaptive update intervals based on weather volatility
- Smart data retention based on usage patterns

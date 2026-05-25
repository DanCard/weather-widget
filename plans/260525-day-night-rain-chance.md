# Detailed Implementation Plan: Extract Day/Night Precipitation from Hourly Forecasts

## Objective
For non-NWS APIs (Open-Meteo, Tomorrow.io, WeatherAPI, Visual Crossing, Silurian), the goal is to calculate dedicated daytime and nighttime precipitation probabilities using their respective hourly data streams. This aligns their behavior with NWS, allowing accurate nighttime rain labels to render on the graph.

## Background & Motivation
Currently, only NWS provides distinct daytime and nighttime precipitation probabilities directly. For other APIs, the widget receives a single daily precipitation value, leaving the nighttime probability null, which forces the UI to fall back to the daily value. By extracting these values from the hourly data, we can provide accurate, period-specific rain chances for all sources.

User Preference for Time Windows:
- Daytime: 8:00 AM to 8:00 PM (on the target date).
- Nighttime: 8:00 PM on the target date to 8:00 AM on the following day.

## Implementation Details

### 1. ForecastRepository.kt Enhancements
The mapDailyForecast method will be expanded to accept the list of hourly forecasts fetched for the current provider.
- Method Signature: Add an optional parameter for the hourly forecast list.
- Timezone Handling: Use the local system timezone to convert epoch milliseconds into local hours for accurate bucketing.
- Window Definitions: Define start and end boundaries for the 8 AM to 8 PM window on the target date, and the 8 PM to 8 AM window crossing into the next day.
- Aggregation Logic: For each window, find the maximum precipitation probability among the hourly points falling within that time range. If a window has no data points, it will remain null.
- Entity Mapping: Update the construction of the ForecastEntity to populate the daytimePrecipProbability and nighttimePrecipProbability fields with these calculated maximums for all non-NWS sources.

### 2. Update Fetch Orchestration
The getWeatherData method in ForecastRepository manages the async fetches for all providers.
- Provider Integration: In the safeFetch blocks for Open-Meteo, Tomorrow.io, WeatherAPI, Visual Crossing, and Silurian, the mapDailyForecast call will be updated to pass in the hourly data returned by the API response.
- Coherence: This ensures that every daily forecast row stored in the database has corresponding period-specific rain chances derived from the same fetch batch.

## Verification Strategy

### 1. Unit Testing
- New Test Case: Create a test in ForecastRepositoryTest that provides a mock list of hourly forecasts with varying rain chances throughout the day and night.
- Assertions: Verify that the calculated day/night probabilities match the expected maximums for the 8 AM - 8 PM and 8 PM - 8 AM windows respectively.
- Edge Cases: Test behavior when hourly data is missing, when it doesn't span the full window, or when probabilities are zero.

### 2. Manual/Visual Verification
- UI Audit: Toggle between different API sources (e.g., Open-Meteo vs. NWS).
- Graph Check: Verify that the "night rain label" (the percentage tucked between the bars) now reflects the specific overnight maximum for that provider.
- Logic Check: Compare the value on the graph with the raw hourly data points in the Hourly view to ensure the maximum was correctly identified.

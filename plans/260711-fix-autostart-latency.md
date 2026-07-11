# Plan: Fix XFCE Genmon Widget Autostart/Update Latency

## Problem Analysis
When the system reboots, the XFCE genmon plugin is loaded. It is configured to run the client binary `genmon-weather-bin` with an `update-period` of `120000` ms (2 minutes).
If the panel is initialized before the background `WeatherDaemon` process has started and set up its Unix Domain Socket (`weather.sock`), the client binary fails to connect, falling back to printing `"--"` (grayed out).
The genmon plugin will then wait for the full 2-minute update period before querying again, creating a noticeable delay and giving the user the impression that the autostart has failed.
Furthermore, any updates to the weather data (such as successful network fetches or settings changes) are not reflected on the panel immediately; they are subject to the same 2-minute polling delay.

## Solution
We can resolve this by signaling the XFCE genmon plugin to refresh immediately whenever `PanelIpcServer` updates its weather markup.
To do this:
1. We will dynamically look up the XFCE genmon plugin ID corresponding to `genmon-weather-bin` or `genmon-weather.py` using `xfconf-query`.
2. Once found, we will cache the plugin ID.
3. Every time `PanelIpcServer.update` is called, we will trigger an immediate refresh of the plugin by executing:
   `xfce4-panel --plugin-event=genmon-<ID>:refresh:bool:true`
4. If the plugin ID lookup fails, we will retry lookup on a 1-minute interval. All process executions will be wrapped in try-catch blocks to ensure fail-safe operation (e.g., when running headless without XFCE panel).

## Implementation Steps
1. Edit `PanelIpcServer.kt` to:
   - Add dynamic lookup of the genmon plugin ID.
   - Cache the ID and rate-limit retries if not found.
   - Trigger the xfce4-panel refresh signal on every markup update.
2. Verify the changes by building the project and running tests.

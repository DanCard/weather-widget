# Weather Widget

An elegant and functional Android weather widget application.

## Overview

This project is a modern Android weather widget application. It provides real-time weather updates and forecasts right on your device's home screen.

## Tech Stack

The application is built using modern Android development practices and libraries:

*   **Kotlin:** The primary programming language.
*   **Coroutines & Flow:** For asynchronous programming and reactive data streams.
*   **Hilt:** For Dependency Injection.
*   **Room:** For local database and caching.
*   **Ktor:** For making network requests to the weather API.
*   **WorkManager:** For scheduling background sync tasks.

## Setup Instructions

To build and run this project locally, you will need a valid Weather API Key. 

1.  Clone the repository.
2.  Create a file named `local.properties` in the root directory of the project if it doesn't already exist.
3.  Add your API key to the `local.properties` file using the following format:

    ```properties
    WEATHER_API_KEY=your_actual_api_key_here
    ```

4.  Open the project in Android Studio and sync with Gradle files.
5.  Build and run the application on an emulator or physical device.

## License

This project is licensed under the **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)** License. 

You are free to:
*   **Share** — copy and redistribute the material in any medium or format
*   **Adapt** — remix, transform, and build upon the material

Under the following terms:
*   **Attribution** — You must give appropriate credit, provide a link to the license, and indicate if changes were made.
*   **NonCommercial** — You may not use the material for commercial purposes.

See the [LICENSE.md](LICENSE.md) file for the full legal text.

Copyright (c) 2026 Daniel Cardenas. All rights reserved.
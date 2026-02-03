# AGENTS.md

This file provides guidance to AI agents working on this repository.

## Build Commands

- **Build debug APK**: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`
- **Install to device**: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
- **Build release**: `./gradlew assembleRelease`
- **Clean build**: `./gradlew clean`

## Testing Commands

- **Run all tests**: `./gradlew test`
- **Run specific test class**: `./gradlew test --tests com.weatherwidget.data.repository.WeatherRepositoryTest`
- **Run specific test method**: `./gradlew test --tests com.weatherwidget.data.repository.WeatherRepositoryTest.testGetWeatherData`
- **Run with coverage**: `./gradlew testDebugUnitTest`

## Lint/Type Checking

- This project does not currently have a dedicated lint command (ktlint, detekt, etc.)
- Always run tests before pushing changes
- Check that the code compiles: `./gradlew build`

## Code Style Guidelines

### Import Organization

- Group imports in this order:
  1. Android/framework imports
  2. Third-party library imports
  3. Project imports
- Sort alphabetically within groups
- Use blank line between groups

### Formatting

- 4-space indentation (no tabs)
- Prefer Kotlin idioms over Java-style code
- Use data classes for value objects
- Use `val` by default, `var` only when necessary
- Use string templates (`"$value"`) over concatenation

### Types

- Prefer explicit return types on public functions
- Use nullable types (`Int?`) appropriately for API responses
- Use sealed classes/enums for fixed sets of states
- Use `Result<T>` for functions that can fail

### Naming Conventions

- Classes: PascalCase (`WeatherRepository`)
- Functions: camelCase (`getWeatherData`)
- Properties: camelCase (`weatherDao`)
- Constants: UPPER_SNAKE_CASE (`TAG`, `MONTH_IN_MILLIS`)
- Private constants: UPPER_SNAKE_CASE, in companion object
- Test functions: backtick-wrapped sentences (`getInterpolatedTemperature returns null for empty list`)

### Error Handling

- Use `try-catch` blocks for API calls and I/O operations
- Log errors with `Log.e(TAG, "message", exception)`
- Return `Result.failure(exception)` or throw exceptions as appropriate
- For database operations, Room handles errors; wrap DAO calls in try-catch when needed
- Don't silently swallow exceptions

### Logging

- Define `private const val TAG = "ClassName"` at top of file
- Use appropriate log levels:
  - `Log.d()` for debugging information
  - `Log.i()` for general informational messages
  - `Log.e()` for errors (always include exception)
- Log important state transitions and data fetches

### Dependency Injection

- Use Hilt for DI
- Annotate singletons with `@Singleton`
- Use `@Inject constructor(...)` for constructor injection
- Use `@ApplicationContext` qualifier when needing Context
- Provide dependencies in `AppModule.kt`

### Coroutines

- Use `suspend` functions for async work
- Use `runTest` in unit tests
- Use `coroutineScope` for structured concurrency
- Don't use `GlobalScope`

### Database (Room)

- Entities in `data/local` package
- Use composite primary keys when needed
- DAOs return `suspend` functions or `Flow<T>`
- Add migrations when changing schema
- Use `OnConflictStrategy.REPLACE` for upserts

### API Calls

- Use Ktor client for HTTP requests
- Define data classes for request/response
- Parse JSON using kotlinx.serialization
- Handle network errors gracefully with try-catch

### Testing

- Use JUnit 4 with mockk
- Use `mockk(relaxed = true)` for dependencies where behavior isn't critical
- Setup common test state in `@Before` method
- Write descriptive test names using backticks
- Test happy paths and edge cases
- Use `assertEquals(expected, actual)` ordering

### Widget Development

- Widgets are the primary UI (no launcher activity)
- Use RemoteViews for widget layouts
- Handle resize events directly without WorkManager delays
- API source toggle handled via click on indicator
- Fetch data from database with 30-day lookback for navigation
- Use `goAsync()` with coroutines in receivers for non-blocking operations
- Temperature interpolation updates based on hourly change rate (1-4 updates/hour)

### Git Commits

- Use detailed commit messages with bullet points
- Format:
  ```
  Summary

  • First detail point
  • Second detail point
  • Third detail point
  ```
- Explain "why" not just "what"
- Reference files when specific changes are notable

## Project Structure

```
app/src/main/java/com/weatherwidget/
├── data/
│   ├── local/          # Room entities and DAOs
│   ├── remote/         # API clients (NWS, Open-Meteo)
│   └── repository/     # Data layer coordination
├── di/                 # Hilt dependency injection
├── stats/              # Accuracy calculation logic
├── ui/                 # Activities and adapters
├── util/               # Utility classes
└── widget/             # Widget provider, worker, state manager
```

## Testing the Widget

Widget-only app. To test:
1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Long-press home screen → "Widgets"
3. Find "Weather Widget" and drag to home screen
4. Resize to test different layouts (1x1, 1x3, 2x3, etc.)

Available emulators: `Generic_Foldable_API36`, `Medium_Phone_API_36`
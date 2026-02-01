# Repository Guidelines

## Project Structure & Module Organization
- `app/` is the main Android application module (Kotlin + Jetpack Compose).
- `app/src/main/java/com/celstech/satendroid/` contains product code grouped by feature areas: `ui/` (screens/components/models/theme), `viewmodel/`, `repository/`, `navigation/`, `selection/`, `utils/`, and `dropbox/`.
- `app/src/main/res/` holds Android resources (layouts, drawables, strings, themes).
- Tests live in `app/src/test/` (unit/Robolectric) and `app/src/androidTest/` (instrumented UI tests).
- Architectural notes and migrations are tracked in `docs/`.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` builds a debug APK.
- `./gradlew installDebug` installs the debug build on a connected device/emulator.
- `./gradlew test` runs unit and Robolectric tests.
- `./gradlew connectedAndroidTest` runs instrumented tests on a device/emulator.

## Coding Style & Naming Conventions
- Kotlin (JVM 11 target); follow standard Kotlin/Android style.
- Use 4-space indentation and file-level ordering by feature (screens/components/models).
- Naming patterns: `*Screen` for UI screens, `*ViewModel` for state/logic, `*Manager` for domain utilities, and `*Repository` for data access.
- Prefer small, focused composables and keep state in ViewModels.

## Testing Guidelines
- Unit tests use JUnit4, Mockito, and Robolectric; instrumented tests use AndroidX Test/Espresso.
- Name tests to match the class under test (e.g., `SelectionManagerTest`).
- Add new tests under `app/src/test/` for logic and `app/src/androidTest/` for UI flows.

## Commit & Pull Request Guidelines
- Commit messages in history are short, imperative, and sometimes prefixed (e.g., `bug: ...`, `Update: ...`). Keep that style.
- PRs should include a clear description, the rationale, and testing notes. Include screenshots or screen recordings for UI changes.

## Security & Configuration Tips
- Local Dropbox configuration belongs in `local.properties` (see `local.properties.template`). Do not commit secrets.
- Keep API keys out of source files and prefer local overrides for development.

## restriction of this application
- Should work on API 28.
- Should review with me before changing code.
- Don't request debugging with debug output. Analyze code and find solution logically in software design.

# Contributing to Remote ADB Connector

Thank you for your interest in contributing! This guide covers everything you need to build, test, and submit changes.

## Prerequisites

- **JDK 21** or later
- **Git**
- **Android Studio Panda** (2025.3.1) or IntelliJ IDEA 2025.3+
- **ADB** (for manual testing)

## Build from Source

```bash
# Clone the repository
git clone https://github.com/adbconnect/remote-adb-connector.git
cd remote-adb-connector

# Build the plugin
./gradlew buildPlugin

# The installable ZIP is at: build/distributions/remote-adb-connector-1.0.0.zip
```

## Run in Development IDE

```bash
# Launch a sandboxed IDE instance with the plugin installed
./gradlew runIde
```

This opens a new Android Studio / IntelliJ instance with the plugin pre-loaded.

## Run Tests

```bash
# Run all unit tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.adbconnect.plugin.service.DeviceParserTest"

# Run with verbose output
./gradlew test --info
```

## Project Structure

```
src/
├── main/kotlin/com/adbconnect/plugin/
│   ├── model/          # Data classes (ConnectionState, Device, CommandResult)
│   ├── service/        # Core services (AdbExecutor, StateMachine, etc.)
│   ├── settings/       # Persistent configuration (PluginSettings)
│   ├── notification/   # Notification service with throttling
│   ├── ui/             # Tool window UI (Factory + Panel)
│   └── util/           # Utilities (IpValidator, AdbPathResolver)
├── main/resources/META-INF/
│   └── plugin.xml      # Plugin descriptor
└── test/kotlin/        # Unit tests (mirrors main structure)
```

## Code Style

- **Language**: Kotlin (2.0+)
- **Naming**: Follow Kotlin conventions (camelCase functions, PascalCase classes)
- **Documentation**: KDoc for all public APIs
- **Thread safety**: Document thread-safety assumptions in KDoc
- **Logging**: Use `Logger.getInstance(YourClass::class.java)` for all logging
- **State**: Prefer immutable data classes; mutable state only in services
- **Services**: Use `@Service` annotations with proper scope (`APP` vs `PROJECT`)

## Design Principles

1. **No shell scripts** — Everything is native Kotlin via ProcessBuilder
2. **Per-process environment** — Never modify the IDE's global environment
3. **State machine driven** — All behavior flows from FSM transitions
4. **Event-driven** — Callbacks and StateFlow, not polling loops
5. **EDT-safe** — Never block the Event Dispatch Thread
6. **Testable** — Pure functions where possible, DI via IntelliJ services

## Adding a New Feature

1. **Check ARCHITECTURE.md** for component overview
2. **Write tests first** in `src/test/kotlin/`
3. **Implement** following existing patterns
4. **Update plugin.xml** if adding new extension points
5. **Update README.md** if adding user-facing features
6. **Run the full test suite**: `./gradlew test`
7. **Test in sandbox IDE**: `./gradlew runIde`

## Pull Request Process

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Write code and tests
4. Ensure all tests pass: `./gradlew test`
5. Ensure the build succeeds: `./gradlew buildPlugin`
6. Submit a PR with a clear description of changes

## Reporting Issues

Please include:
- Android Studio version
- Plugin version
- Steps to reproduce
- Expected vs actual behavior
- Relevant log output (Help → Show Log)

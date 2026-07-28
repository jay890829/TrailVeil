# TrailVeil

TrailVeil is a privacy-first Android exploration-map app inspired by [Fog of World](https://fogofworld.app/zh-hant/). The MVP will let users explicitly start an exploration, continue recording while the app is backgrounded or the device is locked, reveal traveled areas on a persistent map, and review locally stored exploration history.

## Project status

TrailVeil now has a buildable single-module Android Gradle scaffold and a minimal Compose application shell. The placeholder destination installs and launches successfully on a local Android emulator; product UI and runtime features have not yet been implemented.

- [MVP plan](docs/PLAN.md)
- [Task ledger](docs/TODO.md)
- [Android technical baseline](docs/ANDROID_BASELINE.md)
- [Claude Code guidance](CLAUDE.md)

## MVP principles

- Deliver the smallest reliable record–reveal–review experience first.
- Keep canonical location tracks on the device; TrailVeil app code must not transmit stored tracks or precise coordinates. Audit map/tile and transitive SDK networking separately, and do not add accounts, cloud sync, analytics, ads, or remote crash reporting to the MVP.
- Continue explicitly started recording through background and locked-screen use with an Android location foreground service.
- Select the production map stack only after comparing MapLibre and Google Maps with the same correctness, lifecycle, performance, privacy, licensing, and cost criteria.
- Treat real-device GPS, background behavior, battery use, and APK upgrade testing as required validation rather than optional final checks.

## Development

Prerequisites currently verified for the scaffold:

- JDK 17
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0

AGP and the JDK 17 test worker require an ASCII-only absolute project path on Windows. If the checkout path contains non-ASCII characters, create a temporary drive mapping before running Gradle:

```powershell
$trailVeilRoot = (Get-Location).Path
subst T: $trailVeilRoot
Set-Location T:\
```

Run the host-side scaffold quality checks from Windows PowerShell with:

```powershell
.\gradlew.bat clean assembleDebug lintDebug testDebugUnitTest
```

Run the scaffold JVM test alone with:

```powershell
.\gradlew.bat testDebugUnitTest --tests "io.github.jay890829.trailveil.navigation.PlaceholderRouteTest.placeholderRouteIsStable"
```

With an Android device or emulator connected, run all instrumentation tests or only the placeholder Compose test with:

```powershell
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.jay890829.trailveil.PlaceholderDestinationTest
```

GitHub Actions runs the equivalent build, lint, and JVM checks on JDK 17 with SDK Platform 37.0 and Build Tools 36.0.0, plus the instrumentation suite on an API 36 emulator. The install and internal-signing command reference remains deferred until those tasks are implemented and verified.

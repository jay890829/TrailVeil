# TrailVeil

TrailVeil is a privacy-first Android exploration-map app inspired by [Fog of World](https://fogofworld.app/zh-hant/). The MVP will let users explicitly start an exploration, continue recording while the app is backgrounded or the device is locked, reveal traveled areas on a persistent map, and review locally stored exploration history.

## Project status

TrailVeil now has a buildable single-module Android Gradle scaffold and a minimal Compose application shell. The placeholder destination installs and launches successfully on a local Android emulator; product UI and runtime features have not yet been implemented.

- [Android technical baseline](docs/ANDROID_BASELINE.md)

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

### Host build and tests

Run the debug quality gate; it intentionally requires no internal-signing material:

```powershell
.\gradlew.bat clean assembleDebug lintDebug testDebugUnitTest
```

After configuring the external key described below, run the equivalent internal build and lint gate with the shared JVM tests:

```powershell
.\gradlew.bat clean assembleInternal lintInternal testDebugUnitTest
```

The fixed-signature APK is written to `app/build/outputs/apk/internal/app-internal.apk`. Build either APK without the other checks with:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleInternal
```

Run the scaffold JVM test alone with:

```powershell
.\gradlew.bat testDebugUnitTest --tests "io.github.jay890829.trailveil.navigation.PlaceholderRouteTest.placeholderRouteIsStable"
```

### Connected-device commands

With an Android device or compatible emulator connected, install one build lineage with:

```powershell
.\gradlew.bat installDebug
.\gradlew.bat installInternal
```

`debug` and `internal` intentionally share the application ID but use different signing certificates. Android will not replace one with the other; uninstall the currently installed package before switching lineages. Repeated `internal` builds use the fixed external key and are upgrade-compatible when `versionCode` increases.

Run all debug instrumentation tests or only the placeholder Compose test with:

```powershell
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.jay890829.trailveil.PlaceholderDestinationTest
```

These instrumentation commands require a compatible device/emulator. The suite is continuously verified on the API 36 GitHub Actions emulator and was also run successfully on the local official Android Emulator AVD `TrailVeil_API_36` (Android 16/API 36, Google APIs x86_64). The former MuMu instance is no longer part of the local validation path.

## Internal signing

The `internal` build type keeps the fixed `io.github.jay890829.trailveil` application ID and uses a non-debug signing key stored outside the repository. By default, Gradle reads:

```text
~/.trailveil/signing/internal-signing.properties
```

Set `TRAILVEIL_INTERNAL_SIGNING_PROPERTIES` to an absolute path to use another external properties file. The file must contain:

```properties
storeFile=C:/absolute/or/properties-relative/path/trailveil-internal.p12
storePassword=replace-with-the-external-secret
keyAlias=trailveil-internal
keyPassword=replace-with-the-external-secret
```

A relative `storeFile` is resolved from the properties file's directory. Never place the properties file, keystore, passwords, private key, certificate-fingerprint record, or recovery details in this checkout. The repository ignores common keystore formats and `internal-signing.properties` as defense in depth, but the external location remains the security boundary.

With no signing material present, debug builds and CI remain available; any task that includes the `internal` variant fails with the required path and property names. Once configured, the `assembleInternal` and `installInternal` commands above use this fixed identity.

Control of the `jay890829` publisher namespace was reconfirmed on 2026-07-28 by an authenticated `jay890829` GitHub account successfully pushing this repository. Keep the actual certificate SHA-256 fingerprint and key-custody/recovery record outside Git with the protected key backup.

GitHub Actions runs the equivalent debug build, lint, and JVM checks on JDK 17 with SDK Platform 37.0 and Build Tools 36.0.0, plus the instrumentation suite on an API 36 emulator. Internal signing material is intentionally not available to CI.

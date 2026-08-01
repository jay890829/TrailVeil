# TrailVeil

TrailVeil is a privacy-first Android exploration-map app inspired by [Fog of World](https://fogofworld.app/zh-hant/). The MVP will let users explicitly start an exploration, continue recording while the app is backgrounded or the device is locked, reveal traveled areas on a persistent map, and review locally stored exploration history.

## Project status

TrailVeil now has an Android 14+ application with canonical Room track storage, a deterministic location-quality and recording state machine, a location foreground service, contextual permission/settings UX, and a production MapLibre Native + OpenFreeMap surface with a local no-network fallback and Room-backed cumulative fog. The app also provides persisted recording states, a live accepted-location marker, recentering, and local history list/detail screens with segment-safe single-session tracks. Scale validation and physical-device endurance gates remain unfinished.

The development requirements and current limitations are summarized below.

## MVP principles

- Deliver the smallest reliable record–reveal–review experience first.
- Keep canonical location tracks on the device; TrailVeil app code must not transmit stored tracks or precise coordinates. Audit map/tile and transitive SDK networking separately, and do not add accounts, cloud sync, analytics, ads, or remote crash reporting to the MVP.
- Continue explicitly started recording through background and locked-screen use with an Android location foreground service.
- Use MapLibre Native with the no-key OpenFreeMap Liberty style as the replaceable default basemap. Provider or network failure must not interrupt recording or locally rebuilt fog. Do not bulk download or prefetch offline regions from the public endpoint; future local/self-hosted PMTiles remains an optional replacement path.
- Treat real-device GPS, background behavior, battery use, and APK upgrade testing as required validation rather than optional final checks.

## Development

The initial MVP supports Android 14 (API 34) and newer.

Prerequisites currently verified for the project:

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

Run the provider-neutral viewport JVM suite alone with:

```powershell
.\gradlew.bat testDebugUnitTest --tests "app.trailveil.data.map.ViewportTrackDataSourceTest"
```

### Connected-device commands

With an Android device or compatible emulator connected, install one build lineage with:

```powershell
.\gradlew.bat installDebug
.\gradlew.bat installInternal
```

`debug` and `internal` intentionally share the application ID but use different signing certificates. Android will not replace one with the other; uninstall the currently installed package before switching lineages. Repeated `internal` builds use the fixed external key and are upgrade-compatible when `versionCode` increases.

Run all debug instrumentation tests or only the recording-entry Compose tests with:

```powershell
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.trailveil.RecordingEntryScreenTest
```

These instrumentation commands require a compatible device/emulator. The current 52-test suite has passed on official Android Emulator AVDs for API 34, 35, and 36. The Room-to-MapLibre cumulative-fog gate also measured 20 persisted-point samples through the next fully rendered frame on API 36 at p95 411 ms and maximum 441 ms against the 2,000 ms target.

## Internal signing

The `internal` build type keeps the fixed `app.trailveil` application ID and uses a non-debug signing key stored outside the repository. By default, Gradle reads:

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

The Android package identity is `app.trailveil` and is independent of the repository owner's account name. Keep the actual certificate SHA-256 fingerprint and key-custody/recovery record outside Git with the protected key backup.

GitHub Actions is configured to run the equivalent debug build, lint, and JVM checks on JDK 17 with SDK Platform 37.0 and Build Tools 36.0.0, plus the instrumentation suite on an API 36 emulator. Internal signing material is intentionally not available to CI.

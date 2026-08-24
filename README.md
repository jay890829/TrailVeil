# TrailVeil

TrailVeil is a privacy-first Android exploration-map app inspired by [Fog of World](https://fogofworld.app/zh-hant/). The MVP will let users explicitly start an exploration, continue recording while the app is backgrounded or the device is locked, reveal traveled areas on a persistent map, and review locally stored exploration history.

## Project status

TrailVeil now has an Android 14+ application with canonical Room track storage, a deterministic location-quality and recording state machine, a location foreground service, contextual permission/settings UX, and a production MapLibre Native + OpenFreeMap surface with a local no-network fallback and Room-backed cumulative fog. The app also provides persisted recording states, a live accepted-location marker, recentering, and local history list/detail screens with segment-safe single-session tracks. Bounded live fog updates and opt-in 10k/100k scale benchmarks are implemented, and the scale, frame-time, and memory gates have passed on the designated physical validation device. Locked-screen endurance and the wider reliability matrix remain unfinished.

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

Produce the only APK eligible for upload to a GitHub Release with the audited local release script:

```powershell
.\scripts\build-github-release.ps1
```

It refuses a dirty tree and publishes its fully verified outputs only under `app/build/github-release/`. Raw Gradle APKs below are engineering artifacts and must never be uploaded as a release asset:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleInternal
.\gradlew.bat assembleRelease
```

The fixed-signature internal engineering APK is written to `app/build/outputs/apk/internal/app-internal.apk`; the unaudited raw release output is `app/build/outputs/apk/release/app-release.apk`.

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

All variants share the application ID. `debug` uses Android's debug certificate and cannot replace a signed distribution build. `internal` and `release` use the same fixed external TrailVeil certificate, so a release APK can replace an internal field build without an uninstall; future updates must keep that certificate and increase `versionCode`.

Run all debug instrumentation tests or only the recording-entry Compose tests with:

```powershell
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.trailveil.RecordingEntryScreenTest
```

These instrumentation commands require a compatible device/emulator. The P4-002 52-test suite passed on official Android Emulator AVDs for API 34, 35, and 36. The Room-to-MapLibre cumulative-fog gate also measured 20 persisted-point samples through the next fully rendered frame on API 36 at p95 411 ms and maximum 441 ms against the 2,000 ms target.

The deterministic scale benchmarks are opt-in so ordinary connected test runs stay bounded. Use a dedicated empty test install with device networking disabled for the production UI benchmark; the test fails unless the packaged local basemap fallback is active:

```powershell
.\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=app.trailveil.benchmark.ScaleBenchmarkTest" "-Pandroid.testInstrumentationRunnerArguments.trailveilScale=true"
.\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=app.trailveil.benchmark.UiScaleBenchmarkTest" "-Pandroid.testInstrumentationRunnerArguments.trailveilUiScale=true"
```

The core benchmark measures 10k/100k canonical Room bbox reads, cold fog rebuilds, warm derived-cache loads, and peak process PSS. Each dataset settles process memory before its own measurement so one dataset's uncollected garbage cannot be charged to the next; all allocation churn produced during a measured workload is still sampled. The UI benchmark drives the production MainActivity and MapLibre surface through fixed pan/zoom operations and 20 lifecycle recoveries while checking camera and fog continuity. Emulator results remain engineering evidence only.

On the designated validation device, append `"-Pandroid.testInstrumentationRunnerArguments.trailveilEnforceFrameGate=true"` to the UI command. This rejects emulators and enforces p95 frame time <= 32 ms plus frozen frames < 1%; omitting it records engineering evidence without claiming the physical-device gate. Both gates passed on the designated POCO F7 Ultra (Android 16): frame p95 17 ms with no frozen frames, and a 100k peak process PSS of 171–238 MB against the 250 MB ceiling.

Some devices restrict adb testing. On HyperOS, `pm clear` and input injection are blocked, so reset app data with `adb uninstall app.trailveil` followed by a reinstall, and keep the screen awake with `adb shell svc power stayon usb` during instrumentation.

## App signing

The `internal` and `release` build types keep the fixed `app.trailveil` application ID and use one non-debug app-signing key stored outside the repository. This was originally named the internal signer; it is now the lifetime signing identity for direct GitHub APK releases as well. Reusing it is deliberate: changing keys would make Android reject an in-place update and force users to uninstall, permanently deleting data that TrailVeil cannot back up or export. By default, Gradle reads:

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

A relative `storeFile` is resolved from the properties file's directory. Never place the properties file, keystore, passwords, private key, custody record, or recovery details in this checkout. The public certificate fingerprint below is deliberately tracked so release consumers and the build gate can identify the signer; it cannot recover or impersonate the private key. The repository ignores common keystore formats and `internal-signing.properties` as defense in depth, but the external location remains the security boundary.

With no signing material present, debug builds, CI, source compilation, and lint remain available; artifact assembly/bundling/packaging/signing/install tasks for `internal` or `release` fail with the required path and property names. Once configured, `assembleInternal`, `installInternal`, and `assembleRelease` use this fixed identity. The release APK is written to `app/build/outputs/apk/release/app-release.apk` with version name `0.1.0`; unlike `internal`, it is non-debuggable and has no `-internal` suffix.

Never upload the keystore or properties file to the repository, GitHub Secrets, Actions artifacts, or release assets. Signed APKs, SHA-256 checksums, and the public certificate fingerprint are safe to publish. `scripts/build-github-release.ps1` refuses a dirty tree, builds and verifies the release variant locally, and stages only those public artifacts under the ignored `app/build/github-release/` directory.

The pinned public certificate SHA-256 is `307963f32352e6565889982c2b6021af960c94db5c40e0e38c52a2f2cf13856d`. The certificate's historical subject says `TrailVeil Internal`; that label does not change its cryptographic identity or make a release APK debuggable. The release script requires this exact public fingerprint as well as equality with a freshly built internal-lineage APK.

The Android package identity is `app.trailveil` and is independent of the repository owner's account name. Keep the key-custody/recovery record outside Git with the protected key backup; publish only the certificate fingerprint.

GitHub Actions is configured to run the equivalent debug build, lint, and JVM checks on JDK 17 with SDK Platform 37.0 and Build Tools 36.0.0, plus the instrumentation suite on an API 36 emulator. App-signing material is intentionally not available to CI; release APKs are built locally and only the signed public artifact is uploaded to GitHub Releases.

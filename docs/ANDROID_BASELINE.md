# Android Technical Baseline

This document records the P0-001 baseline selected on 2026-07-26. It uses stable Android SDK and tooling releases only. Build-time compatibility must still be proven by P0-002 in a clean scaffold; any correction discovered there must update this document and `docs/TODO.md` before later work proceeds.

## Identity and SDK

| Setting | Decision | Rationale |
|---|---:|---|
| `namespace` | `io.github.jay890829.trailveil` | Matches the repository publisher namespace without claiming a separate TrailVeil domain. Confirm continued control of the `jay890829` GitHub namespace before the first internal distribution. |
| `applicationId` | `io.github.jay890829.trailveil` | Fix before internal distribution so state-bearing APKs retain one update lineage. Do not change after testers install the first internal build. |
| `minSdk` | 26 | Deliberate Android 8.0 MVP/test-fleet floor; higher than all selected library floors and reduces old-platform background behavior branches. |
| `compileSdk` | 37 | Google announced the Android 17/API 37 base release on 2026-06-16, published matching AOSP release source, and distributes the numeric, non-preview `platforms;android-37.0` SDK package. Some first-party pages retained preview-era labels on the decision date, so this does not claim that the documentation corpus is uniformly current. |
| `targetSdk` | 36 | Proactive stable runtime target. On 2026-07-26 Play requires API 35 for ordinary phone/tablet submissions; API 36 becomes required on 2026-08-31, subject to Google's documented extension process. Move to target 37 only after testing Android 17 target-gated behavior. |
| Java bytecode | 17 | Matches the selected AGP runtime baseline. |

`compileSdk` controls build-time API and dependency compatibility; `targetSdk` opts into compatibility behavior and policy expectations. Compiling with API 37 does not itself opt TrailVeil into Android 17 target-gated behavior, raise `minSdk`, or permit unguarded API 37 calls on older devices.

## Build toolchain

| Component | Version | Configuration rule |
|---|---:|---|
| Android Gradle Plugin | 9.3.0 | Apply `com.android.application`; use AGP 9 built-in Kotlin. |
| Gradle wrapper | 9.5.0 | AGP 9.3.0 minimum/default compatible Gradle. |
| Gradle runtime JDK | 17 | Also use Java source/target compatibility 17. |
| Built-in Kotlin | 2.2.10 | Supplied by AGP 9.3.0, whose published Google Maven POM depends on Kotlin Gradle Plugin and stdlib 2.2.10. Do not apply `org.jetbrains.kotlin.android` or `kotlin-android`. |
| Compose compiler plugin | 2.2.10 | Apply `org.jetbrains.kotlin.plugin.compose`; its version must match Kotlin. Do not set `composeOptions.kotlinCompilerExtensionVersion`. |
| KSP | 2.3.10 | Use KSP2 for Room; do not use kapt. KSP 2.3.x is versioned independently from Kotlin and 2.3.10 contains an AGP 9 built-in-Kotlin fix. |
| Android Build Tools | 36.0.0 | Let AGP select its default; do not declare `buildToolsVersion`. |

P0-002 must prove the AGP 9.3.0 built-in Kotlin 2.2.10, Compose plugin 2.2.10, KSP 2.3.10, and Room combination in one clean build. First-party sources establish the individual releases but do not publish one complete pairing matrix. The standalone Kotlin Gradle Plugin compatibility table is not the governing Android-plugin pairing here: AGP 9 built-in Kotlin replaces applying `org.jetbrains.kotlin.android`, and the AGP 9.3.0 POM supplies Kotlin 2.2.10 while the AGP compatibility table requires Gradle 9.5.0.

## Library baseline

| Purpose | Coordinate/version |
|---|---|
| Compose alignment | `androidx.compose:compose-bom:2026.06.01` |
| Compose UI | `androidx.compose.ui:ui` (BOM) |
| Compose foundation | `androidx.compose.foundation:foundation` (BOM) |
| Material 3 | `androidx.compose.material3:material3` (BOM) |
| Compose previews/tooling | `ui-tooling-preview` and debug-only `ui-tooling` (BOM) |
| Activity Compose | `androidx.activity:activity-compose:1.13.0` |
| Navigation Compose | `androidx.navigation:navigation-compose:2.9.8` |
| Lifecycle Compose/ViewModel | `androidx.lifecycle:*:2.11.0` |
| Room runtime/compiler | `androidx.room:*:2.8.4` |
| Preferences DataStore | `androidx.datastore:datastore-preferences:1.2.1` |
| AndroidX Core | `androidx.core:core:1.19.0` |
| Coroutines Android/test | `org.jetbrains.kotlinx:kotlinx-coroutines-*:1.11.0` |
| JVM tests | `junit:junit:4.13.2` |
| AndroidX test runner | `androidx.test:runner:1.7.0` |
| AndroidX JUnit extension | `androidx.test.ext:junit:1.3.0` |
| Espresso | `androidx.test.espresso:espresso-core:3.7.0` |
| Compose UI tests | `ui-test-junit4` and debug-only `ui-test-manifest` (BOM) |

Compatibility constraints:

- Core 1.19.0 and Lifecycle 2.11.0 are stable releases selected for the scaffold. Core 1.19.0 and the Lifecycle Runtime Compose Android 2.11.0 variant publish `minCompileSdk=37`; ordinary Lifecycle Runtime/ViewModel Android artifacts do not all share that floor. These constraints require `compileSdk 37`, not `targetSdk 37`.
- The selected Android 17 base SDK is stable numeric `platforms;android-37.0`; `platforms;android-37.1` is a distinct stable minor SDK, while `platforms;android-37.2-beta1` is preview and excluded from this baseline.
- The Compose BOM does not manage the Compose compiler plugin.
- Do not add Kotlin serialization until type-safe serializable navigation routes are actually chosen.
- Do not add `room-ktx`; its APIs were merged into `room-runtime`.
- Do not add `core-ktx`; Core 1.19.0 merged its KTX APIs into `core`, and `core-ktx` is an empty compatibility artifact.

## Location foreground-service policy baseline

- Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_COARSE_LOCATION`, precision-dependent `ACCESS_FINE_LOCATION`, and API 33+ `POST_NOTIFICATIONS` when implementation begins.
- Declare the service with `android:foregroundServiceType="location"` and keep it non-exported unless a separately reviewed requirement proves otherwise.
- TrailVeil will create a location FGS only from an Activity that is actually visible and after current location permission and system location services are usable. This is the product's conservative normal path; Android documents narrow while-in-use exceptions, but the MVP does not depend on them.
- Start the foreground service through the platform-compatible API and promote it immediately—normally in `onStartCommand()`—with a nonzero notification ID, a channel created beforehand on API 26+, and the location service type. Do not intentionally consume the platform's short startup timeout.
- Do not request `ACCESS_BACKGROUND_LOCATION` merely to continue a user-initiated location FGS after the Activity becomes hidden. TrailVeil deliberately creates recording services only from visible UI. A background-FGS-start exemption and background-location eligibility are separate gates.
- Handle both `ForegroundServiceStartNotAllowedException` and while-in-use permission `SecurityException` paths.
- On API 33+, request notification permission contextually for notification-drawer visibility, but do not gate FGS startup on grant. A valid notification remains mandatory when denied; Android exposes the running FGS through Task Manager instead of the notification drawer.
- Keep recording tied to the explicit, user-visible exploration session and stop when that use case ends. Omitting `ACCESS_BACKGROUND_LOCATION` does not by itself establish Google Play policy eligibility.
- Do not promise service immortality or reboot persistence. A process-killed sticky service, explicit force stop, Task Manager Stop, and reboot are distinct states and must receive device tests.

## Privacy, backup, and signing baseline

- The auditable MVP claim is: **TrailVeil app code does not transmit stored tracks or precise coordinates.** Map/style/tile requests and all transitive SDK behavior must be inventoried separately before broader claims are made.
- Do not add analytics, ads, remote crash reporting, coordinate logs, background exports, or cloud synchronization.
- Use `android:allowBackup="false"` plus legacy `fullBackupContent` rules and Android 12+ `dataExtractionRules`. Explicitly exclude sensitive database, file, preference, external, and device-protected domains from cloud backup and device transfer.
- Treat these rules as intended and tested exclusions, not an absolute guarantee against every OEM migration tool.
- Before Play distribution, provide the privacy-policy URL in the designated Play Console field and an accessible privacy-policy link or text inside the shipped app. Make the policy and Data Safety, location, and FGS declarations match the shipped binary and every included SDK.
- Use one stable non-debug signing identity for direct internal APKs, store the key and passwords outside the repository, back the key up securely, record its SHA-256 fingerprint outside the repository, and increment `versionCode`.
- Play App Signing is mandatory before the first Google Play release. Decide whether Google generates the app-signing key or the existing direct-distribution key is supplied to Play; separately define the upload key and ensure the resulting app-signing lineage can update direct-installed copies without data loss.
- APK update eligibility depends on application ID and compatible signing lineage. Room migrations are separately responsible for preserving database usability and history.

## Deferred proof required by later tasks

### P0-002 / P0-004

- Resolve the exact plugin/library set and inspect AAR metadata.
- Run `./gradlew --version`, clean debug assembly, unit tests, lint, and connected tests when those tasks exist.
- Verify SDK Platform 37.0, AGP-selected Build Tools 36.0.0, and JDK 17 in a clean environment.

### P0-005

- Record the internal certificate fingerprint and key-custody/recovery procedure outside Git.
- Prove install-over upgrade compatibility before distributing the first state-bearing APK.

### P3-001 / P3-002

- Inspect the merged manifest for only approved permissions and service declarations.
- Test API 26, 33, 34, 35, and 36 behavior for permission variants, background-start rejection, process death, force stop, Task Manager Stop, and OEM power management.

### P5-001 / P5-002

- Test backup/restore and device-transfer exclusions, including Room WAL/SHM, DataStore, files, diagnostics, and representative OEM transfer tools.
- Audit release dependencies, network traffic, logs, map-provider telemetry, privacy disclosures, and final signing lineage.

## First-party sources

- [Android 17 release announcement](https://android-developers.googleblog.com/2026/06/Android-17.html)
- [Android 17.0.0 Release 1 AOSP tag](https://android.googlesource.com/platform/manifest/+/refs/tags/android-17.0.0_r1)
- [Google Android SDK repository metadata](https://dl.google.com/android/repository/repository2-3.xml)
- [Set up the Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk)
- [Android 17 target-gated behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
- [SDK Platform release notes](https://developer.android.com/tools/releases/platforms) — retained as evidence of first-party documentation inconsistency on the decision date.
- [Android Gradle Plugin 9.3.0 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [AGP 9.3.0 Google Maven POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.0/gradle-9.3.0.pom)
- [Gradle 9.5.0 release notes](https://docs.gradle.org/9.5.0/release-notes.html)
- [Gradle Java compatibility](https://docs.gradle.org/9.5.0/userguide/compatibility.html)
- [Kotlin 2.2.10 release](https://github.com/JetBrains/kotlin/releases/tag/v2.2.10)
- [Kotlin support in AGP](https://developer.android.com/build/kotlin-support)
- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose compiler Gradle plugin](https://developer.android.com/develop/ui/compose/compiler)
- [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity)
- [Navigation releases](https://developer.android.com/jetpack/androidx/releases/navigation)
- [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle)
- [Lifecycle Runtime Compose 2.11.0 AAR metadata source](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.11.0/lifecycle-runtime-compose-android-2.11.0.aar)
- [Room releases](https://developer.android.com/jetpack/androidx/releases/room)
- [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore)
- [Core releases](https://developer.android.com/jetpack/androidx/releases/core)
- [Core 1.19.0 AAR metadata source](https://dl.google.com/dl/android/maven2/androidx/core/core/1.19.0/core-1.19.0.aar)
- [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test)
- [JUnit 4.13.2 release](https://github.com/junit-team/junit4/releases/tag/r4.13.2)
- [KSP releases](https://github.com/google/ksp/releases)
- [kotlinx.coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases)
- [Location foreground service type](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
- [Launch a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Background foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Handle user stopping a foreground service](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Google Play background location policy](https://support.google.com/googleplay/android-developer/answer/9799150)
- [Google Play Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Auto Backup](https://developer.android.com/identity/data/autobackup)
- [App signing](https://developer.android.com/studio/publish/app-signing)
- [App versioning](https://developer.android.com/studio/publish/versioning)

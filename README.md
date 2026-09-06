# TrailVeil

TrailVeil is a privacy-first Android exploration-map app. It lets users explicitly start an exploration, continue recording while the app is backgrounded or the device is locked, reveal traveled areas on a persistent map, and review locally stored exploration history.

## Project status

TrailVeil now has an Android 14+ application with canonical Room track storage, a deterministic location-quality and recording state machine, a location foreground service, contextual permission/settings UX, and a production MapLibre Native + OpenFreeMap surface with a local no-network fallback and Room-backed cumulative fog. The app also provides persisted recording states, a live accepted-location marker, recentering, and local history list/detail screens with segment-safe single-session tracks. Bounded live fog updates and opt-in 10k/100k scale benchmarks are implemented, and the scale, frame-time, and memory gates have passed on the designated physical validation device. Locked-screen endurance and the wider reliability matrix remain unfinished.

The development requirements and current limitations are summarized below.

## MVP principles

- Deliver the smallest reliable record–reveal–review experience first.
- Keep canonical location tracks on the device; TrailVeil app code must not transmit stored tracks or precise coordinates. Audit map/tile and transitive SDK networking separately, and do not add accounts, cloud sync, analytics, ads, or remote crash reporting to the MVP.
- Continue explicitly started recording through background and locked-screen use with an Android location foreground service.
- Use MapLibre Native with the no-key OpenFreeMap Liberty style as the replaceable default basemap. Provider or network failure must not interrupt recording or locally rebuilt fog. Do not bulk download or prefetch offline regions from the public endpoint; future local/self-hosted PMTiles remains an optional replacement path.
- Treat real-device GPS, background behavior, battery use, and APK upgrade testing as required validation rather than optional final checks.

## Map providers, terms and data

TrailVeil draws its basemap with one of two providers, and a build carries exactly one of them. The `debug`, `internal` and `release` build types use MapLibre Native with the OpenFreeMap Liberty style; the `googlePoc` and `googleRelease` build types use the Google Maps SDK for Android. `googleRelease` is the release-configured Google build - non-debuggable, signed like `release`, compiled from the same production Google sources (`src/google`) as `googlePoc`, which adds only the engineering harness (`src/googlePoc`) and its own manifest; so the build that ships and the build the instrumentation suites drive cannot drift apart. Each engine is linked only into its own build types, and packaging any variant - by `assemble` or by `install` - is finalized by a check that reads the packaged APK and proves it carries one provider and not the other. The decided end state is two provider-exclusive builds of the same application - a Google build and an OpenFreeMap build - never both on one screen and with no in-app switch. They share the application ID `app.trailveil`, so builds signed with the same key replace one another in place and keep history and preferences; a build signed with a different key (including the debug-signed `googlePoc` build type today) installs only after uninstalling, which deletes that history. Both the OpenFreeMap build and the Google build are published as release APKs, signed by the same lifetime key, so you can install either over the other and keep your recorded history - that is the point of the shared application ID and the shared signer. Choosing between them is therefore an install, not a setting. A Google build you make yourself is signed by your own key and is not interchangeable with either published APK; it needs your own Google Maps key (see [Google Maps key](#google-maps-key)). Whichever provider is active, TrailVeil app code never transmits stored tracks, history or precise coordinates; the basemap provider necessarily receives requests for the map area being viewed. The in-app disclosure (the first-run sheet and the "Privacy and data" menu entry) names the active provider and the terms below.

### Google Maps build

- TrailVeil includes Google Maps features and content. Their use is subject to the [Google Maps Additional Terms of Service](https://maps.google.com/help/terms_maps/) and the [Google Privacy Policy](https://policies.google.com/privacy), as the Google Maps Platform Terms of Service section 3.2.2(a) require the application to state.
- What Google receives, per the Maps SDK for Android Play data disclosure: the map area being viewed, device metadata (OS version, model, brand, form factor, SDK version), the device IP address, a pseudonymous Maps SDK identifier, crash and stack-trace metrics, and map-interaction events (panning and zooming) because TrailVeil uses the camera APIs. TrailVeil adds no account, advertising ID, analytics or route data to those requests.
- The build depends only on `play-services-maps` and its Play services base libraries: no Places SDK, no map ID or cloud styling, no ads SDK, no Firebase, no analytics. Map loads therefore fall under the Maps SDK SKU, which Google prices as unlimited and free for mobile apps (checked against the first-party pricing page on 2026-09-01); they are attributed to the TrailVeil developer's Google Cloud project and API key, never to the user. The key ships only in an external properties file restricted to this package and signing certificate.
- Google's attribution logo is drawn by the SDK and is never obscured by TrailVeil's fog overlay or safety cover; the app shows no Google Maps content next to or linked with a non-Google map, caches or scrapes no Google Maps content, and registers no point-of-interest listener in production.
- Google's built-in labels and POI icons stay visible above the fog by owner decision. Google offers an opt-in "Promoted Places" marker-monetisation programme; TrailVeil has not enrolled and displays no advertising.

### OpenFreeMap build

- Map data © OpenStreetMap contributors, available under the [Open Database License](https://www.openstreetmap.org/copyright); tiles by [OpenMapTiles](https://www.openmaptiles.org/) and [OpenFreeMap](https://openfreemap.org/). The map's ⓘ control shows the full attribution string the tile service publishes.
- OpenFreeMap is a public, donation-funded tile service that needs no API key and offers no availability guarantee; TrailVeil sends it plain style, tile, sprite and glyph requests that reveal the approximate area being viewed and carry no identifier. Do not bulk-download or prefetch from the public endpoint.
- MapLibre Native is used under the BSD 2-Clause License; its third-party notices ship inside the app as `res/raw/maplibre_third_party_notices.txt`.

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

The `internal`, `release` and `googleRelease` build types keep the fixed `app.trailveil` application ID and use one non-debug app-signing key stored outside the repository. This was originally named the internal signer; it is now the lifetime signing identity for direct GitHub APK releases as well. Reusing it is deliberate: changing keys would make Android reject an in-place update and force users to uninstall, permanently deleting data that TrailVeil cannot back up or export. By default, Gradle reads:

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

With no signing material present, debug builds, CI, source compilation, and lint remain available; artifact assembly/bundling/packaging/signing/install tasks for `internal`, `release` or `googleRelease` fail with the required path and property names. Once configured, `assembleInternal`, `installInternal`, `assembleRelease` and `assembleGoogleRelease` use this fixed identity - a stranger building `googleRelease` writes this properties file for their own keystore, in addition to the Google Maps key file below. The release APK is written to `app/build/outputs/apk/release/app-release.apk` with version name `0.2.0` (versionCode 2; the public 0.1.0 was code 1); unlike `internal`, it is non-debuggable and has no `-internal` suffix. The Google APK is written to `app/build/outputs/apk/googleRelease/app-googleRelease.apk` with version name `0.2.0-google` and the same versionCode 2, and is published alongside it.

Never upload the keystore or properties file to the repository, GitHub Secrets, Actions artifacts, or release assets. Signed APKs, SHA-256 checksums, and the public certificate fingerprint are safe to publish. `scripts/build-github-release.ps1` refuses a dirty tree, builds and verifies both published variants locally from one Gradle invocation of one commit, and stages only their public artifacts under the ignored `app/build/github-release/` directory. Pass `-OpenFreeMapOnly` to build just the OpenFreeMap APK if you hold no Google release key.

The pinned public certificate SHA-256 is `307963f32352e6565889982c2b6021af960c94db5c40e0e38c52a2f2cf13856d`. The certificate's historical subject says `TrailVeil Internal`; that label does not change its cryptographic identity or make a release APK debuggable. The release script requires this exact public fingerprint as well as equality with a freshly built internal-lineage APK.

The Android package identity is `app.trailveil` and is independent of the repository owner's account name. Keep the key-custody/recovery record outside Git with the protected key backup; publish only the certificate fingerprint.

The signing certificate also protects the published Google Maps key. A Maps SDK key is a build input compiled into `resources.arsc`, so it is readable by anyone who downloads the published Google APK - that is true of every Android app that uses the Maps SDK, and no packaging choice changes it. What makes that safe is the key's Google Cloud restriction to Android apps with package name `app.trailveil` and the SHA-1 of this certificate: a key lifted out of the APK will not authorize a request from any other application or any differently signed build of this one. Two consequences follow. The key file must name a release key restricted to this certificate, not the debug-certificate key the `googlePoc` build type uses. And if the signing key is ever lost, the Maps key must be restricted afresh, because the old restriction names a certificate nobody can sign with any more.

GitHub Actions is configured to run the equivalent debug build, lint, and JVM checks on JDK 17 with SDK Platform 37.0 and Build Tools 36.0.0, plus the instrumentation suite on an API 36 emulator. App-signing material is intentionally not available to CI; release APKs are built locally and only the signed public artifact is uploaded to GitHub Releases.

## Google Maps key

The OpenFreeMap build needs no key. The Google Maps builds (`googlePoc` and `googleRelease`) compile a Google Maps API key into their resources, so whoever builds one supplies their own key. The published Google APK carries the project's own key, uncompressed in `resources.arsc` and readable by anyone who downloads it; what protects it is its restriction to this package and this signing certificate, described under [App signing](#app-signing). Both build types read a key by the same path, including the missing-key sentinel, so a keyless build of either still builds and fails closed onto the map-unavailable surface at runtime. Gradle reads the key at configuration time from a properties file outside the repository. By default:

```text
~/.trailveil/maps/google-maps.properties
```

Set `TRAILVEIL_GOOGLE_MAPS_PROPERTIES` to an absolute path outside the checkout to use another file. The file contains:

```properties
# Read by googlePoc, which is debug-signed. An Android Maps SDK key, 39 characters starting with AIza.
debugApiKey=replace-with-your-own-key
# Optional typo self-check: the lowercase hex SHA-256 of the key text. Enforced only when present.
debugApiKeySha256=
# Read by googleRelease, which is signed like release. Restrict it to that certificate, not the debug one.
releaseApiKey=replace-with-your-own-key
# Optional to build, REQUIRED to publish. See below for what it is and how to fill it in.
releaseApiKeySha256=
```

Neither fingerprint is a value Google shows you. Both are digests you compute from your own key: the lowercase hex SHA-256 of the key text exactly as the build reads it, which is the property value with surrounding whitespace trimmed and no trailing newline. `debugApiKeySha256` is a typo self-check and stays optional. `releaseApiKeySha256` is optional for building too, but **publishing requires it**, because it is the only check that can tell your release key from any other valid key: `scripts/build-github-release.ps1` extracts the key actually compiled into the candidate APK, hashes it, and refuses to publish unless the two digests agree. That is what makes "the published APK carries the release key" a checked fact rather than a hope - and it is exactly the mistake it catches, since a debug-restricted key in a release-signed build produces an APK that looks fine and cannot authorize.

Derive it from the file rather than retyping it, so the two lines cannot disagree and neither the key nor its digest reaches your screen:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/set-google-maps-key-fingerprint.ps1
```

It reads `releaseApiKey` from the file the build would use, writes `releaseApiKeySha256` back into that same file, and prints only which property it wrote. Re-running it is safe: an already-correct fingerprint is left alone, an existing one keeps its position instead of gaining a duplicate, and comments, ordering, encoding and line endings are preserved. `-Property debugApiKey` does the debug one. `-VerifyOnly` reports whether the stored fingerprint matches and changes nothing - exit 0 it matches, 2 none is stored, 3 it is wrong. It refuses a value that is not shaped like a Maps key, rather than fingerprinting a typo into something internally consistent that still builds the sentinel, and it refuses a properties file inside the checkout for the same reason the build does.

Both fingerprints belong in the same external file as the key, and neither goes in the repository, in CI, or in a release note - the published audit deliberately carries neither the key nor its digest.

Each Google build type reads its own property and neither falls back to the other. That is deliberate: a Maps key is restricted to one signing certificate, so a shared property can only produce a build whose key cannot authorize - which is exactly what happened to this project's first Google release build. A build type whose property is absent gets the sentinel below, not the other build type's key.

In Google Cloud, restrict the key to Android apps with package name `app.trailveil` and the SHA-1 of the certificate that signs your build - for `googlePoc` that is your own Android debug certificate, and for `googleRelease` the keystore named in the signing properties file described under [App signing](#app-signing) - it is signed exactly like `release`, so without that file it does not build. Neither of your certificates is the one that signs the published APKs, which is why a Google build you make cannot replace an installed published build in place, and why the map-unavailable surface tells you where the keyless build is without promising your history will survive the switch. The key is a build input by construction: the Maps SDK resolves it from a compiled resource, so there is no runtime entry and nothing in the app ever asks for it.

The build never fails for lack of a key; it fails closed. With no file, an unreadable file, a relative override path, a file inside the repository, a malformed key, or a fingerprint that does not match, the sentinel `TRAILVEIL_GOOGLE_MAPS_POC_MISSING_KEY` is compiled instead, `BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED` is `false` with the reason in `GOOGLE_MAPS_POC_KEY_REASON`, and the map slot shows the provider-unavailable surface while recording and history keep working. `verifyGooglePocMergedManifest` asserts that resolution whenever either Google build type is packaged (`googlePoc` and `googleRelease`, by `assemble`, `install` or `bundle`) - the sentinel when no valid key was configured, a key-shaped value when one was - and the hosted "Google PoC keyless build" job runs it with no key anywhere.

Never commit the properties file: the repository ignores `**/google-maps.properties`, `secrets.properties`, `.env` and `google-services.json` as defense in depth. Publishing a Google APK publishes the key inside it, so publish one only with a key restricted as described above, and never with a key that is also used anywhere a stranger's request would be billed or trusted.

`scripts/build-github-release.ps1` audits each published APK for the opposite thing. The OpenFreeMap candidate is refused if it carries the Google Maps key marker, the Maps SDK, the key resource or any key-shaped string. The Google candidate is refused unless it carries the key marker bound to the key resource, exactly one key-shaped string and no missing-key sentinel, the Maps SDK and no MapLibre, no engineering Activity, and the pinned release certificate - so a keyless, a debug-signed or a wrong-provider Google build cannot be published by accident. Both are distributed only from a directory outside Gradle's output tree.


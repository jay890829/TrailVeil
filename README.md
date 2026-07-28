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

Build the debug scaffold APK with:

```bash
./gradlew clean assembleDebug
```

The full build, lint, test, install, internal-signing, and single-test command reference will be added after the corresponding quality and signing tasks are implemented and verified.

LinkDrop transfers files between a Linux desktop and an Android phone on the same Wi-Fi.
It is a Kotlin Multiplatform project.

* [/shared](./shared/src) holds the protocol and connection logic both apps use (no UI).
  - [commonMain](./shared/src/commonMain/kotlin) is code common to all targets.
  - `androidMain` / `jvmMain` are for Android-only and desktop-only code.
* [/androidApp](./androidApp) is the Android app (client in Slice A).
* [/desktopApp](./desktopApp) is the Linux desktop app (server in Slice A).
* [docs/PROTOCOL.md](./docs/PROTOCOL.md) is the wire protocol both apps speak.

All dependency versions are pinned in [gradle/libs.versions.toml](./gradle/libs.versions.toml).

### Running the apps

- Android app: `./gradlew :androidApp:installDebug` (with a phone connected over USB)
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`

### Running tests

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`

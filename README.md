LinkDrop transfers files between a Linux desktop and an Android phone on the same Wi-Fi,
in both directions. It is a Kotlin Multiplatform project.

> **Not secure yet.** Connections are unencrypted (`ws://`) and not authenticated until TLS
> and pairing are added (Slice D). Anyone on the same Wi-Fi can read transferred files and
> can offer files to you. Only use it on a network you trust, and only accept offers you
> expect.

## Requirements

- **Desktop:** Linux. Gradle and a JDK 21 are downloaded automatically by `./gradlew`.
- **Phone:** Android 10 or newer, with USB debugging on to install the debug build.
- Both devices on the **same Wi-Fi** (guest networks often block devices from reaching each
  other).

## Running the apps

- Desktop app: `./gradlew :desktopApp:run` (hot reload: `./gradlew :desktopApp:hotRun --auto`)
- Android app: `./gradlew :androidApp:installDebug` (phone connected over USB)

## Using LinkDrop

1. Start the desktop app. Its window shows an address such as `192.168.1.20:47800`.
2. In the phone app, enter that address and tap **Connect**. The phone appears in the
   desktop window under "Phones".
3. Send files:
   - **Phone → desktop:** tap **Send files**, or use **Send to desktop** in any app's share
     menu (Gallery, Files, …). Sharing connects to the last desktop used and sends
     automatically.
   - **Desktop → phone:** click **Send files…** next to the phone.
4. The receiving side shows an offer (who, which files, total size). Choose **Accept** or
   **Decline**. An offer not answered within 2 minutes is declined.
5. Progress is shown on both sides, with **Cancel** for each transfer.

**Keep the phone app open while files transfer.** LinkDrop keeps the screen on during a
transfer; if you leave the app, Android may stop it and the transfer fails.

### Where received files go

- **Desktop:** `LinkDrop` inside your Downloads folder (from `xdg-user-dirs`, usually
  `~/Downloads/LinkDrop`). **Open folder** opens it.
- **Phone:** `Download/LinkDrop` (no storage permission needed). **Open** shows Downloads.

### What LinkDrop guarantees

- Every file is checked with **SHA-256**; a damaged or incomplete file is deleted, not kept.
- Partial files are never visible under their real name (desktop: hidden `.linkdrop-*.part`
  files, removed at startup; phone: hidden until verified).
- Existing files are **never overwritten**: a second `photo.jpg` is saved as `photo (1).jpg`.
- Incoming names are cleaned: folders in the name, hidden-file dots, invisible characters
  (such as the right-to-left trick that disguises `exe` as `jpg`) are removed or refused.
- An offer is declined automatically if there is not enough free space.
- Several files sent together arrive in order; more than 1000 files are split into several
  offers, which queue up.

To compare a file on both sides yourself:

```
sha256sum ~/Downloads/LinkDrop/<name>
adb shell sha256sum /sdcard/Download/LinkDrop/<name>
```

## Troubleshooting

- **Phone cannot connect:** check the address and port, that both devices are on the same
  Wi-Fi, and that a desktop firewall (if any) allows TCP port 47800. On Android 17 and newer,
  allow LinkDrop's local network permission.
- **"Not connected: connection lost: …":** the text after the colon is the real cause (for
  example a ping timeout). The desktop log shows the same reason after "Disconnected".
  Connections can drop when the phone's screen turns off, because Android pauses background
  apps.
- **"Open folder" opens the wrong program** (e.g. a terminal): set your file manager as the
  default for folders, e.g.
  `xdg-mime default org.gnome.Nautilus.desktop inode/directory`.

## Project layout

* [/shared](./shared/src): code both apps use (no UI).
  - `protocol`: messages, JSON codec, handshake, file-name rules, chunk format.
  - `transfer`: the transfer engine (`TransferSession`): queues, progress, SHA-256 checks,
    cancel, and the receiver's safety rules.
* [/desktopApp](./desktopApp): the Linux app. It runs the server the phone connects to.
* [/androidApp](./androidApp): the Android app. It connects to the desktop.
* [docs/PROTOCOL.md](./docs/PROTOCOL.md): the wire protocol both apps speak.

All dependency versions are pinned in [gradle/libs.versions.toml](./gradle/libs.versions.toml).

## Plaintext development mode

Until TLS and pairing exist, the apps talk over unencrypted `ws://`. This is controlled by
`linkdrop.allowPlaintext` in [gradle.properties](./gradle.properties) (currently `true`):

- Both apps show "Development build: connections are NOT encrypted" while it is on.
- `TransportConfig.choose` in `shared` is the only place that picks plaintext or TLS. It never
  falls back from TLS to plaintext.
- **Release builds are blocked while it is on:** `./gradlew :androidApp:assembleRelease` and
  desktop packaging (`packageDeb`, `createDistributable`, …) fail on purpose.
- Android release builds never allow plaintext anyway: `BuildConfig.ALLOW_PLAINTEXT` is `false`
  and the release network security config forbids cleartext. The build also fails if that
  config is ever changed to allow it.

## Running tests

```
./gradlew :shared:jvmTest :shared:testAndroidHostTest :desktopApp:test :androidApp:testDebugUnitTest
```

- `:shared:jvmTest` / `:shared:testAndroidHostTest`: protocol and transfer engine, run on the
  desktop JVM and as Android code.
- `:desktopApp:test`: the desktop server, file storage, and end-to-end transfers over real
  WebSockets. Includes `SlowPhoneTest` (about 15 s), which checks that a phone pausing for 12 s
  neither drops the connection nor makes the desktop buffer the file in memory.
- `:androidApp:testDebugUnitTest`: the phone's networking (real OkHttp sockets), including
  50 MB transfers in both directions.

Things only a real phone can check (storage, share menu, screen) are not covered by these
tests.

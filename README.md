LinkDrop transfers files between a Linux desktop and an Android phone on the same Wi-Fi,
in both directions. It is a Kotlin Multiplatform project.

Connections are encrypted (TLS 1.3) and both devices prove who they are with a certificate.
A phone and a desktop **pair** once, after both users compared the same 6-digit code; from then
on they recognise each other. Things to know:

- Pairing is only as safe as the code comparison: allow a phone only if both screens show
  the same code.
- Anyone on the Wi-Fi can still *ask* to connect (the desktop then shows a dialog) and can
  appear in the phone's desktop list under any name; only a paired, verified desktop is
  connected to without asking.
- The desktop's private key is a file readable only by your user (like an SSH key); the
  phone's key never leaves the phone's Keystore.

## Requirements

- **Desktop:** Linux. Gradle and the JDKs it needs (21 for Gradle itself, 25 for the app) are
  downloaded automatically by `./gradlew`.
- **Phone:** Android 12 or newer, with USB debugging on to install the debug build.
- Both devices on the **same Wi-Fi** (guest networks often block devices from reaching each
  other).

## Running the apps

- Desktop app: `./gradlew :desktopApp:run` (hot reload: `./gradlew :desktopApp:hotRun --auto`)
- Android app: `./gradlew :androidApp:installDebug` (phone connected over USB)

## Installing

- **Desktop, Arch Linux:** `cd packaging/arch && makepkg -si`. It builds the app and installs it
  in `/opt/linkdrop` with its own Java runtime, plus a `linkdrop` command, a menu entry and the
  icon. Remove it with `sudo pacman -R linkdrop`.
- **Desktop, Debian or Ubuntu:** `./gradlew :desktopApp:packageDeb` writes a `.deb` to
  `desktopApp/build/compose/binaries/main/deb/` (it needs `dpkg-deb`, which Arch does not have).
- **Desktop, any Linux, without installing:** `./gradlew :desktopApp:createDistributable`, then
  run `desktopApp/build/compose/binaries/main/app/linkdrop/bin/linkdrop`.
- **Phone:** the debug build with `./gradlew :androidApp:installDebug`, or a signed release
  build (see [Release builds](#release-builds)).

## Using LinkDrop

1. Start the desktop app. Its window says how phones see it (e.g. "Phones on this Wi-Fi list
   this desktop as "Arch"") and shows its address, such as `192.168.1.20:47800`.
2. Open the phone app. Under **Desktops on this Wi-Fi**, tap the desktop (on Android 17 and
   newer, allow the local network permission first). If it is not listed, tap **Enter address
   manually** and type the address from the desktop window.
3. **The first time, pair:**
   - The phone shows a 6-digit code and asks whether the desktop shows the same one. Tap
     **Yes, pair** only if it does.
   - The desktop then shows "*phone* wants to connect to your device" with the same code.
     Click **Allow** only if the codes match.

   After that the phone is listed under **Paired phones** on the desktop, the desktop under
   **Paired desktops** on the phone, and they connect without asking. The phone appears in the
   desktop window under "Phones".
4. Send files:
   - **Phone → desktop:** tap **Send files**, or use **Send to desktop** in any app's share
     menu (Gallery, Files, …). Sharing connects to the last desktop used and sends
     automatically, following a **paired** desktop to a new address (its certificate proves it
     is the same desktop). An unpaired one is only tried at the last address used.
   - **Desktop → phone:** click **Send files…** next to the phone.
5. The receiving side shows an offer (who, which files, total size). Choose **Accept** or
   **Decline**. An offer not answered within 2 minutes is declined.
6. Progress is shown on both sides, with **Cancel** for each transfer.

**Forget** (under Paired phones / Paired desktops) removes a pairing; the next connection then
pairs again. Reinstalling the phone app, or resetting the desktop (below), gives that device a
new identity: the other side then asks to pair again.

### With the screen off or the app in the background

While the phone is connected to a desktop, LinkDrop keeps the connection running with the
screen off or another app open. A notification shows the desktop's name, the file being sent
or received with its progress, and a **Disconnect** button. It disappears when you disconnect.

- **Android 13 and newer** ask whether LinkDrop may show notifications. LinkDrop asks once, right
  after your first connection. If you decline, everything works the same; only the notification
  is hidden (Android then lists LinkDrop under the active apps in the quick settings).
- **Android 15 and newer** let an app do this for 6 hours a day in the background; opening
  LinkDrop starts the count again. When the time is used up, LinkDrop disconnects cleanly (a
  file being received is kept, see below) and says so. Open LinkDrop and tap **Reconnect**.
- **Some phones stop background apps anyway** (for example some Samsung, Xiaomi or OnePlus
  models). If the connection still drops with the screen off, set LinkDrop's battery use to
  **Unrestricted** (Settings → Apps → LinkDrop → Battery).

### If the connection drops

- **Reconnect:** if the connection to a **paired** desktop drops, the phone shows a large
  **Reconnect to "*name*"** button. It connects again with one tap, also if the desktop's
  address changed (its certificate proves it is the same desktop).
- **Retry:** a send that the lost connection cut off stays in the list (on the desktop under
  "Earlier") with a **Retry** button, shown while the same device is connected again. Retry
  offers the files that did not finish; the receiver accepts or declines as usual.
- **Resume:** between paired devices, a file that was cut off continues where it stopped
  instead of starting over. The receiver keeps the part it already has, hidden, for up to
  7 days (at most 16 such files). The whole file is still checked with SHA-256: if a different
  file with the same name and size is sent, it is deleted, and sending it again starts from
  zero. Before a resumed file continues, both sides read the part already transferred again,
  which takes a few seconds for a large file.
- Files shared into LinkDrop from another app may no longer be readable once that share has
  been closed; Retry then fails for them with "could not open". Share them again.

### Where received files go

- **Desktop:** `LinkDrop` inside your Downloads folder (from `xdg-user-dirs`, usually
  `~/Downloads/LinkDrop`). **Open folder** opens it.
- **Phone:** `Download/LinkDrop` (no storage permission needed). **Open** shows Downloads.

### What LinkDrop guarantees

- Every file is checked with **SHA-256**; a damaged or incomplete file is deleted, not kept.
- Partial files are never visible under their real name (desktop: hidden `.linkdrop-*.part`
  files; phone: hidden until verified). Leftovers from a crash are deleted at the next start;
  parts kept for resuming are deleted after 7 days.
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

### Where LinkDrop keeps its identity

- **Desktop:** `~/.local/share/linkdrop/` (or `$XDG_DATA_HOME/linkdrop/`): `identity.p12` (its
  key and certificate) and `paired-phones.json`. Readable only by you. Deleting the folder
  resets the desktop's identity: every phone must pair again (and warns that the desktop's
  identity has changed until you forget the old pairing there).
- **Phone:** the Android Keystore and the app's private storage. Uninstalling removes both.

## Troubleshooting

- **Phone cannot connect:** check the address and port, that both devices are on the same
  Wi-Fi, and that a desktop firewall (if any) allows TCP port 47800. On Android 17 and newer,
  allow LinkDrop's local network permission.
- **Desktop not listed on the phone:** the desktop window says whether it is announced. Some
  routers and guest networks block the multicast traffic discovery needs; then use **Enter
  address manually**. If the desktop changed networks, restart the desktop app. A desktop
  firewall must allow mDNS (UDP port 5353) for it to be found.
- **"this desktop's identity has changed":** the desktop presented a different certificate
  than when you paired. If you reset or reinstalled LinkDrop on the desktop, forget it under
  **Paired desktops** and pair again. Otherwise another device may be pretending to be it: do
  not pair.
- **"the desktop did not allow the connection":** someone clicked Decline on the desktop, or
  nobody answered within 2 minutes.
- **"the desktop is busy with another phone":** another phone is waiting for the desktop's
  Allow; try again when that is done.
- **"Not connected: connection lost: …":** the text after the colon is the real cause (for
  example a ping timeout). The desktop log shows the same reason after "Disconnected". If it
  happens with the screen off, see
  [With the screen off or the app in the background](#with-the-screen-off-or-the-app-in-the-background).
- **"Open folder" opens the wrong program** (e.g. a terminal): set your file manager as the
  default for folders, e.g.
  `xdg-mime default org.gnome.Nautilus.desktop inode/directory`.

## Project layout

* [/shared](./shared/src): code both apps use (no UI).
  - `protocol`: messages, JSON codec, handshake, pairing, file-name rules, chunk format, and the
    rules for discovery announcements.
  - `transfer`: the transfer engine (`TransferSession`): queues, progress, SHA-256 checks,
    cancel, resuming, and the receiver's safety rules.
  - `ui`: the color palette both apps use (always dark), with a test that keeps every text color
    readable.
* [/desktopApp](./desktopApp): the Linux app. It runs the server the phone connects to and
  announces itself on the network (mDNS).
* [/androidApp](./androidApp): the Android app. It finds desktops on the Wi-Fi and connects;
  `ConnectionService` keeps the connection alive in the background.
* [/packaging/arch](./packaging/arch): the Arch Linux package (`PKGBUILD` and menu entry).
* [/design](./design): the app icon, `Linkdrop-icon.png`. `design/make-icons.sh` regenerates every
  icon file of both apps from it (it needs ImageMagick 7); run it after changing the image.
* [docs/PROTOCOL.md](./docs/PROTOCOL.md): the wire protocol both apps speak.

All dependency versions are pinned in [gradle/libs.versions.toml](./gradle/libs.versions.toml).

## Release builds

**Version.** Both apps take their version from `linkdrop.version` in
[gradle.properties](./gradle.properties) (for example `1.2.3`). Android's version code is derived
from it (`1.2.3` → `10203`): raise the version for every release you install, never lower it.
The Arch package's `pkgver` in [packaging/arch/PKGBUILD](./packaging/arch/PKGBUILD) must match.

**Android signing key.** An Android app can only be updated by an APK signed with the same key,
so make one key once and keep it safe:

1. Create it **outside this repository** (keytool comes with any JDK; Gradle's own is in
   `~/.gradle/jdks/*/bin/`). It asks for a password:

   ```
   mkdir -p -m 700 ~/.config/linkdrop-signing
   keytool -genkeypair -keystore ~/.config/linkdrop-signing/release.p12 -storetype PKCS12 \
       -alias linkdrop -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=LinkDrop"
   chmod 600 ~/.config/linkdrop-signing/release.p12
   ```

2. Tell Gradle where it is, in your **user** file `~/.gradle/gradle.properties` (not the
   project's), then `chmod 600 ~/.gradle/gradle.properties`:

   ```
   linkdrop.signing.storeFile=/home/YOU/.config/linkdrop-signing/release.p12
   linkdrop.signing.storePassword=the password
   linkdrop.signing.keyAlias=linkdrop
   ```

3. `./gradlew :androidApp:assembleRelease` then writes the signed
   `androidApp/build/outputs/apk/release/androidApp-release.apk`. Install it with
   `adb install androidApp-release.apk` (uninstall the debug build first: they are signed with
   different keys, and uninstalling removes the phone's pairing, so pair again afterwards).

**Back up `release.p12` and its password** somewhere other than this computer. If either is
lost, phones cannot update the app any more: it has to be uninstalled and paired again.

Without the `linkdrop.signing.*` settings the release APK is built unsigned (a warning says so).
The build refuses a key that is inside the repository, so it cannot be committed by accident.

**Shrinking.** Release builds are shrunk with R8 (about 3 MB instead of 26 MB): unused code and
resources are removed and names are shortened. Debug builds and the tests are not shrunk, so
**try the release APK on a phone** (pair, send and receive a file) before giving it to anyone,
especially after changing a dependency. A crash report from a release build shows shortened
names; `androidApp/build/outputs/mapping/release/mapping.txt` from the same build turns them back
(with `retrace` from the Android SDK's command-line tools, which Android Studio's SDK Manager
installs), so keep that file with each release you share.

## License

LinkDrop is released under the [MIT License](./LICENSE). Copyright (c) 2026 Agnish.

It ships third-party libraries under their own licenses (mostly Apache 2.0; SLF4J and Bouncy
Castle under MIT); [THIRD-PARTY-NOTICES](./THIRD-PARTY-NOTICES) lists every one with its license
and contains the license texts and NOTICE files they require. Both files are included in the
desktop packages (the Arch package also installs them in `/usr/share/licenses/linkdrop/`).

THIRD-PARTY-NOTICES is generated: after adding, removing or updating a dependency, build the
desktop distributable and run `tools/third_party_notices.py` (Python 3). `./gradlew check` fails
until you do: it checks that the file lists every library each app ships, with its version. The
Android app shows both files under **About** (at the bottom of the home screen).

## Plaintext development mode (debugging only)

By default every connection is TLS with pairing. For debugging, unencrypted `ws://` can be
switched on for one build:

```
./gradlew -Plinkdrop.allowPlaintext=true :desktopApp:run
./gradlew -Plinkdrop.allowPlaintext=true :androidApp:installDebug
```

(`linkdrop.allowPlaintext` in [gradle.properties](./gradle.properties) is `false`.) While it is
on:

- Both apps show "Development build: connections are NOT encrypted". Nothing is paired or
  remembered, so the desktop asks about every connection (without a code).
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

Every test run first scans the source files for invisible or formatting characters
(`./gradlew checkSourceCharacters`), which could make code read differently from what it does.

- `:shared:jvmTest` / `:shared:testAndroidHostTest`: protocol, pairing rules and the transfer
  engine, run on the desktop JVM and as Android code. Includes keeping and resuming
  interrupted files (`TransferRulesTest`, `TransferSessionTest`, `PartialsTest`), and
  `PaletteTest`, which fails if any text color drops below a 4.5:1 contrast.
- `:desktopApp:test`: the desktop server, file storage, and end-to-end transfers over real
  WebSockets. Includes `SlowPhoneTest` (about 15 s), which checks that a phone pausing for 12 s
  neither drops the connection nor makes the desktop buffer the file in memory, and
  `DesktopAnnouncerTest` (about 15 s), which announces the desktop with real mDNS on this
  machine's LAN address and finds it again (skipped without a LAN address). `DesktopTlsTest`
  covers TLS, pairing and the approval dialog against the real server; `DesktopFilesTest`
  covers kept and resumed files on disk, `DesktopStateTest` the Retry button, and
  `AppIconTest` that every icon size is bundled.
- `:androidApp:testDebugUnitTest`: the phone's networking (real OkHttp sockets), including
  50 MB transfers in both directions, and `PhonePairingTest`: pairing, pinning and refusing a
  changed desktop over real TLS (these tests run on JDK 25, for its TLS exporter). Also the
  connection notification's content (`ConnectionNotificationTest`), when Reconnect is offered
  and where it connects (`ReconnectTest`), and that the launch screen's color matches the
  palette (`ThemeResourcesTest`).

Things only a real phone can check are not covered by these tests: storage, the share menu,
the screen, finding desktops with Android's NsdManager, the Keystore key, Android's
pairing-code exporter, the background service with the screen off (and Android's 6-hour
limit), the notification, and the launcher icon.

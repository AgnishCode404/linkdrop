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
  example a ping timeout). The desktop log shows the same reason after "Disconnected".
  Connections can drop when the phone's screen turns off, because Android pauses background
  apps.
- **"Open folder" opens the wrong program** (e.g. a terminal): set your file manager as the
  default for folders, e.g.
  `xdg-mime default org.gnome.Nautilus.desktop inode/directory`.

## Project layout

* [/shared](./shared/src): code both apps use (no UI).
  - `protocol`: messages, JSON codec, handshake, file-name rules, chunk format, and the rules
    for discovery announcements.
  - `transfer`: the transfer engine (`TransferSession`): queues, progress, SHA-256 checks,
    cancel, and the receiver's safety rules.
* [/desktopApp](./desktopApp): the Linux app. It runs the server the phone connects to and
  announces itself on the network (mDNS).
* [/androidApp](./androidApp): the Android app. It finds desktops on the Wi-Fi and connects.
* [docs/PROTOCOL.md](./docs/PROTOCOL.md): the wire protocol both apps speak.

All dependency versions are pinned in [gradle/libs.versions.toml](./gradle/libs.versions.toml).

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
  engine, run on the desktop JVM and as Android code.
- `:desktopApp:test`: the desktop server, file storage, and end-to-end transfers over real
  WebSockets. Includes `SlowPhoneTest` (about 15 s), which checks that a phone pausing for 12 s
  neither drops the connection nor makes the desktop buffer the file in memory, and
  `DesktopAnnouncerTest` (about 15 s), which announces the desktop with real mDNS on this
  machine's LAN address and finds it again (skipped without a LAN address). `DesktopTlsTest`
  covers TLS, pairing and the approval dialog against the real server.
- `:androidApp:testDebugUnitTest`: the phone's networking (real OkHttp sockets), including
  50 MB transfers in both directions, and `PhonePairingTest`: pairing, pinning and refusing a
  changed desktop over real TLS (these tests run on JDK 25, for its TLS exporter).

Things only a real phone can check (storage, share menu, screen, finding desktops with
Android's NsdManager, the Keystore key and Android's pairing-code exporter) are not covered by
these tests.

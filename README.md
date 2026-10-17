# CallCompanion

[![Latest release](https://img.shields.io/github/v/release/N2K02/CallCompanion?label=download)](https://github.com/N2K02/CallCompanion/releases/latest) ![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Use an Android phone **without internet** like a smartwatch for a phone that **has a SIM / internet**, over Bluetooth.

*Bluetooth phone companion app for Android: turn a spare phone into a smartwatch-style remote for SIM and WhatsApp calls.*

**[⬇ Download the latest APK](https://github.com/N2K02/CallCompanion/releases/latest)** · Android 8.0 or newer · Free and open source

Install the same app on both phones and pick a role:

- **Gateway**: the phone with the SIM / internet.
- **Remote**: the "watch-mode" phone with no internet.

## What works

| Feature | How |
|---|---|
| Incoming SIM call rings on the remote (name + number) | Gateway watches call state and forwards it over Bluetooth |
| Answer / reject / hang up from the remote | Gateway answers / ends the call via Android Telecom |
| Dial a number from the remote | Gateway places the call |
| Two-way voice between the phones | Live 8 kHz audio over the Bluetooth link ("Voice link") |
| Turn gateway loudspeaker on/off from the remote | Remote command |
| Calls made in the gateway's own phone app show on the remote (with number) | Outgoing-call broadcast + call state |
| Choose **Phone (SIM)** or **WhatsApp** when dialing from the remote | WhatsApp: opens WhatsApp's voice call for that contact on the gateway |
| WhatsApp calls ring on the remote: answer / decline / hang up | Gateway reads WhatsApp's call notification and presses its buttons |
| **Take call here**: talk on the remote | Gateway loudspeaker ON + voice link; automatic when you answer or dial from the remote |

### WhatsApp setup (gateway)

- Allow CallCompanion in **Notification access** (same as notification sync).
- Allow **Display over other apps** for CallCompanion (button in the *WhatsApp calls* card), so it can open WhatsApp's call screen while running in the background.
- To call someone on WhatsApp from the remote, they must be saved in the gateway's contacts and be on WhatsApp (open WhatsApp once so it syncs).

### Known limitation

Android does **not** let a normal app capture or inject the audio of a SIM call. So when you answer a SIM call from the remote, the conversation itself still happens on the gateway phone (use its loudspeaker, or a Bluetooth headset on it). Relaying the actual call audio needs root / a system app, or a VoIP (internet) calling setup. The voice link in this app is the audio-relay foundation for that next step.

## Install & test

1. Download `CallCompanion-<version>.apk` (e.g. `CallCompanion-1.0.apk`) from the latest **Release** of this repo and install it on **both** phones (allow "install unknown apps").
2. Pair the two phones in Android **Settings → Bluetooth**.
3. **Gateway phone:** open CallCompanion → choose *Gateway* → **Start** → allow all permissions (Phone, Microphone, Nearby devices, Notifications, Contacts, Call logs).
4. **Remote phone:** open CallCompanion → choose *Remote* → pick the gateway phone from the list → **Start** → allow permissions.
5. Status should turn green: *Connected to …*.
6. Call the gateway's number from a third phone. The remote rings with Answer / Reject.

Tip: on some phones (Xiaomi, Samsung, Huawei) set CallCompanion battery usage to **Unrestricted** so it keeps running in the background.

## Updates

The app updates itself from this repo's Releases:

- When you open it (at most every 12 hours) it checks for a newer release. If there is one, a card shows **what's new** with **Update now**, **Later** or **Skip this version**.
- **Check for updates** is at the bottom of the main screen, next to the version number.
- The APK is downloaded inside the app, checked (size, SHA-256 checksum from GitHub, same app, newer version, same signing key) and installed with Android's own installer.
- The first time, Android asks you to allow installs from this app ("Install unknown apps"); after that you just confirm the update.

## Signing

Releases are signed with a private key that is kept encrypted in `signing/keys.tar.enc` and unlocked during the build with the `SIGNING_PASSPHRASE` repository secret (see [signing/README.md](signing/README.md)). Earlier builds used a public test key; Android's key rotation moves installed apps to the private key on the next update, without reinstalling.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release named "CallCompanion <version>" (1.0, 1.1, 1.2 …, one decimal place) with release notes listing the changes since the previous release. To jump to a new number (e.g. 2.0), run the workflow by hand from the Actions tab and type the version. Locally: `./gradlew assembleRelease` (shrunk APK) (needs JDK 17 + Android SDK).

## License

MIT. See [LICENSE](LICENSE).

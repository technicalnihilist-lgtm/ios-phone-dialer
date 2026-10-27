# Phone — iOS Dialer

A pixel-faithful reproduction of the iOS Phone app, shipped three ways from one codebase: an installable **Android APK**, an installable **web app (PWA)**, and a plain **HTML file**.

The Android build is a real dialer, not a mock: it reads your contacts and call log, places real calls, and — once it holds the default-dialer role — runs its own in-call screen with working speaker, mute, hold, DTMF keypad and recording.

<table>
  <tr>
    <td><img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/screenshots/1-keypad-dark.png" width="240" alt="Keypad, dark"></td>
    <td><img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/screenshots/2-keypad-light.png" width="240" alt="Keypad, light"></td>
    <td><img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/screenshots/3-call.png" width="240" alt="In-call screen"></td>
  </tr>
  <tr>
    <td align="center">Keypad · dark</td>
    <td align="center">Keypad · light</td>
    <td align="center">In-call</td>
  </tr>
  <tr>
    <td><img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/screenshots/4-favorites.png" width="240" alt="Favorites"></td>
    <td><img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/screenshots/5-recents.png" width="240" alt="Recents"></td>
    <td></td>
  </tr>
  <tr>
    <td align="center">Favorites</td>
    <td align="center">Recents</td>
    <td></td>
  </tr>
</table>

## Install

**Android (APK)** — grab it from the [latest release](https://github.com/technicalnihilist-lgtm/ios-phone-dialer/releases/latest) (or [`apk/Phone-Dialer.apk`](apk/Phone-Dialer.apk) in the repo), open it on the phone, and allow install from unknown sources when prompted. Android 7.0+ (API 24). Adaptive icon on Android 8+.

On first launch it asks for contacts, call log, phone and microphone permissions. To use the in-call controls, tap **Set** on the banner and make Phone your default dialer.

**iPhone / Android (web app)** — open <https://technicalnihilist-lgtm.github.io/ios-phone-dialer/> in Safari or Chrome, then *Share → Add to Home Screen*. It launches full screen with its own icon and works offline.

**Desktop** — just open `index.html`.

## What works

| Feature | Android app | Browser |
| --- | --- | --- |
| Real contacts from the device | yes (`READ_CONTACTS`) | sample data |
| Real call history | yes (`READ_CALL_LOG`) | sample data |
| Places real calls | yes (`CALL_PHONE`) | hands off to `tel:` |
| Speaker / mute / hold | yes, with the default-dialer role | simulated |
| DTMF keypad mid-call | yes | simulated |
| Call recording | attempted — see below | no |
| Offline | yes | yes (service worker) |

### About recording

Android 9 removed `VOICE_CALL` audio capture for ordinary apps, and Android 10+ blocks it outright. The record button tries `MediaRecorder` with the `VOICE_CALL` source; on devices that still permit it (some OEM builds) it writes an `.m4a` to the app's external files folder. Everywhere else the OS refuses and the app tells you so rather than pretending to record. No third-party dialer can bypass this — it needs root or a system signature.

### About the default-dialer role

Speaker, mute, hold and DTMF only work on a live call when the app owns the call, which means holding the dialer role (`InCallService`). Without the role the app still dials, but Android's own in-call screen takes over and these controls live there instead. The banner in the app requests the role.

## Keypad details

Real iOS metrics: 75pt keys, letters under each digit, T9 contact matching as you type (2-8-2 finds Ava), long-press `0` for `+`, long-press backspace to clear, long-press the empty area above the keypad for **Paste**. Five-tab Classic layout — Favorites, Recents, Contacts, Keypad, Voicemail — in a floating Liquid Glass tab bar whose selection capsule slides, stretches and can be dragged with a finger. Light and dark follow the system appearance, exactly as the real app does.

## Build the APK

Requires a JDK and Android build-tools. `android/build.sh` does everything: `aapt2` compile and link, `javac`, `d8`, zip, `zipalign`, then `apksigner` with a generated keystore.

```bash
cd android
SDK=/path/to/android-sdk
export JAVA_HOME=$SDK/jdk
export PATH=$JAVA_HOME/bin:$SDK/build-tools/34.0.0:$PATH
bash build.sh          # -> android/Phone-Dialer.apk
```

The script expects `$SDK/android-35/android.jar` and `$SDK/android-14` (build-tools 34); adjust the two variables at the top for your layout.

## Project layout

```
index.html               the whole UI — one self-contained file
manifest.webmanifest     PWA manifest
sw.js                    service worker (offline cache)
icons/                   app icons (180 / 192 / 512)
screenshots/             rendered from index.html
apk/Phone-Dialer.apk     signed, installable build (v2.1)
android/                 native app source
  AndroidManifest.xml    permissions, dialer role, InCallService
  src/com/phone/dialer/
    MainActivity.java    WebView shell + contacts/call-log/dial bridge
    DialerInCallService.java   owns the live call
    CallActivity.java    in-call screen: speaker, mute, hold, keypad, record
    DialerActivity.java  role-eligibility trampoline
  res/                   theme, styles, in-call layout and icons
  assets/index.html      the same UI, packaged
  build.sh               full toolchain build
```

## Notes

- The APK is signed with a locally generated key (`CN=Phone Dialer`), fine for sideloading but not a Play Store key.
- Typography is only pixel-identical on Apple hardware, where SF Pro resolves. Elsewhere it falls back to Helvetica Neue / the system sans.
- There is no sample data anywhere: contacts, call history and voicemail come from the device, so the browser build shows empty lists until the Android app fills them in.

## Store metadata

| Field | Value |
| --- | --- |
| App name | Phone |
| Package name / App ID | `com.phone.dialer` |
| Category | Utility |
| Version | 2.1 (versionCode 4) |
| Minimum Android | 7.0 (API 24) |
| Price | Free |
| Icon | https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/icons/icon-512.png |
| License | MIT |

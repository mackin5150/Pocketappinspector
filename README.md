# Pocket App Inspector (Android source)

An on-device Android app inventory and privacy-risk review tool. It scans visible installed packages, including apps without launcher icons and disabled packages when Android exposes them. It scores explainable signals and links to Android's own app settings or uninstall confirmation.

## What it reviews

- System, updated-system, and user-installed package status; app label, package name, version, first-install date, and installer when Android reports it.
- Disabled apps, apps without a launcher icon, duplicate display names, and names resembling common system apps.
- Declared permissions related to location, SMS, contacts, camera, microphone, usage, accessibility, notification access, VPN, and installation of unknown apps.
- Detected active special access: usage access, display-over-other-apps, install-unknown-app access, enabled accessibility service, notification listener, device administrator, and always-on VPN.
- Optional local baseline: packages absent from the saved snapshot and version changes since it was saved.

Each app has a score from 0–100, with the underlying signals listed. This is a triage score, not a malware verdict. A legitimate accessibility tool, launcher, VPN, backup service, or device-management app may receive a high score.

## Important Android limits

- Android does not let an ordinary app silently uninstall packages. For user apps, the app opens Android's uninstall confirmation. For system apps, it opens Android app settings; whether disable/uninstall-updates is available depends on the phone and package.
- Android does not provide a trustworthy way for an ordinary app to reconstruct the exact factory-installed package/version list after OS updates. The local baseline is only as trustworthy as the moment it is saved. For a stronger factory comparison, collect a baseline from a known-clean phone with the same model, region, carrier, and OS build.
- Installer attribution can be missing or incomplete. Unknown installer is a reason to review, not proof of sideloading. A malicious app can imitate a name/icon; package identity and behavior matter more than its visible label.
- Declared permissions are not proof that an app accessed data. Runtime and special access states can change, and Android restricts what one app can inspect about other apps.
- `QUERY_ALL_PACKAGES` is required for a broad inventory on Android 11+. Google Play restricts broad package visibility to approved use cases. This project is intended for personal/device-owner sideloading or private distribution unless the Play Console approves its use.
- The audit does not inspect other apps' private data, browser history, screen recordings, APK code, or network traffic. It does not detect all spyware and does not claim to certify the phone as clean.

## Build

Open the project root in Android Studio (Giraffe or newer) with JDK 17 and Android SDK platform 35 installed. Let Gradle sync, then select **Build > Build APK(s)**. The APK is normally written to `App/build/outputs/apk/debug/App-debug.apk`.

To install for personal testing, enable Android's install-from-this-source permission for the file manager used to open the APK. The app itself requests no internet permission and uploads no scan data.

## Device use

1. Install and open Pocket App Inspector.
2. Review **HIGH REVIEW** entries first and tap an entry to see the evidence.
3. Use **App settings** to review Android's actual access controls. Revoke special access from Android Settings if you do not recognize or trust the app.
4. Use **Uninstall…** only for a user app you recognize and have decided to remove. Android presents its own confirmation.
5. Save a baseline only after you believe the device is in a trusted state. A baseline is stored locally in this app's private preferences.

## Project details

- Package: `com.pocketappinspector`
- Minimum Android: 8.0 (API 26)
- Target SDK: 35
- Permissions: broad package inventory and Android's uninstall confirmation only; no location, contacts, SMS, microphone, camera, accessibility-service, or internet permission.
# Pocketappinspector
# Pocketappinspector

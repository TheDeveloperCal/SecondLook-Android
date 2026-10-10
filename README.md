# SecondLook

**Check Before You Trust.** SecondLook is an open-source Android app for checking suspicious messages, links, and call stories with explainable rules that run on the device.

## What it does

- Analyzes pasted messages and call descriptions for pressure, threats, credential requests, unusual payments, impersonation, and other explicit warning patterns.
- Inspects URL structure without opening or fetching the address.
- Offers optional Android call screening using the system call-screening role, an exact-match local blocklist, and opt-in private-caller silencing.
- Keeps optional scan history off by default. Saved history contains a short result report, not the submitted message.
- Supports system, light, and dark appearance.

The initial rule set is English-focused. Results are guidance, not a guarantee. It cannot verify a sender or caller, check a website's live reputation, know about every recent scam, or protect the whole device. A link with no structural flags is not verified as safe. Unknown calls are allowed.

## Privacy

Manual text and URL analysis runs locally. The app has no account, advertising, analytics, internet permission, or third-party analysis service. The blocklist and optional result history are stored in the app's local database. Scan history is disabled by default and can be cleared in Settings. Android call-screening must be selected by the user; it responds using local rules and allows a call if local screening fails. Android may record a call that the system blocks according to its own call-screening behavior.

The app does not request microphone, SMS, contacts, or call-log permissions. Local app storage does not protect data from every form of device compromise.

## Build

Requirements: JDK 17 or newer and Android SDK platform 35. The project uses Gradle 8.10.2, Android Gradle Plugin 8.8.2, and Kotlin 2.1.20.

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Install the debug APK on a connected, designated test device with:

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

To run Compose instrumentation tests on an available Android device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

## Tests

`app/src/test` covers scam and benign message examples, URL parsing and structure, transparent result categories, and pure call-screening decisions. `app/src/androidTest` covers the Compose dashboard and message, link, and call flows. See the build report for the actual results from this release build.

## License

GPL-3.0-only. See [LICENSE](LICENSE).

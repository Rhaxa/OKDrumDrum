# OKDrumDrum

A low-latency landscape drumpad for Android: 2 rows × 4 pads (tom, mid tom, high tom, cymbal / kick, snare, closed hat, open hat).

- **Tap** a pad to hit it — triggers on touch-down, full multitouch
- **Hold** a pad to open its panel: **record** a custom sound (10 s max, mic), set per-pad **volume**, or **revert** to the default
- Works while your favorite song plays in the background (never grabs audio focus)
- Default sounds are synthesized in-repo (`tools/make_sounds.py`); swap-in custom packs later

## Architecture notes

Pure Kotlin + Compose UI with a thin C++ audio layer: raw **AAudio via JNI** (no third-party libraries) — the device's fast audio callback mixes triggered samples at the native sample rate, filling silence otherwise. Measured trigger latency on the reference device's loudspeaker: **~53 ms total**, of which ≈2 ms is app code; the rest is the vendor output path shared by every low-latency app on that device (platform-parity verified via `dumpsys`). Bluetooth and battery-saver excluded — both add to it.

The full spec, decisions log, and roadmap live in internal project docs not published here.

## Building

Open in **Android Studio** and run, or use the wrapper:

```bash
JAVA_HOME="<path-to-a-JDK-17>" ./gradlew :app:assembleDebug
```

Requires JDK 17 (system Java 26 is too new for the pinned Gradle 8.9). minSdk 29 (Android 10+).

A prebuilt debug APK ships in this repository: **`dist/OKDrumDrum-1.2.0-debug.apk`** (sideload at your own risk; unsigned for release, debug-signed).

## License

[MIT](LICENSE)

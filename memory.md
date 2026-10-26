# OKDrumDrum — Session Memory

Hand-off document for any new AI session. Read this + `PROJECT.md` (the living spec) + `CHANGELOG.md` before touching code. Last full update: **2026-10-26**.

---

## 1. Project goals

A low-latency landscape Android drumpad, pure Kotlin + Compose, no third-party app libraries:

- **Layout**: 2 rows × 4 pads (top: low tom, mid tom, high tom, cymbal · bottom: kick, snare, closed hat, open hat).
- **Tap** → sound on *touch-down* (never on lift), full multitouch, visual flash.
- **Hold** (~300 ms) → haptic tick → **full-screen** per-pad options panel (record custom sound ≤10 s / per-pad volume 0–100 % / revert to default). Started as a bottom sheet, user rejected it — panel must cover the whole screen at once.
- **Persistence**: volumes (SharedPreferences), customs (WAV in `filesDir/custom/`), all surviving restarts.
- **Mixing**: must NEVER request audio focus — plays alongside background music.
- **Latency contract**: touch-down → sound ≤ 33 ms on speaker/wired headphones (USB/BT excluded, battery-saver off). Currently ~47 ms — **~20 ms above target; latency reduction is the next milestone**.
- **Status**: v1.0.0 released 2026-10-25, public at https://github.com/Rhaxa/OKDrumDrum (tag `v1.0.0`).

### v1.x roadmap (user-decided 2026-10-26)

1. **Latency (Phase 7, closed)**: the AAudio/C++ callback engine (raw AAudio via JNI, app/src/main/cpp) is at PLATFORM PARITY with reference drum apps on the user's OPPO — flinger reports an identical 52.67 ms track latency (fast output AudioOut_1D, HAL 256, dirac inactive). App-side ≈2 ms; the loudspeaker constant is vendor HAL/DSP. User A/B feel verdict: parity confirmed by ear (2026-10-26).
2. **Phase 8 shipped in v1.2.0 (2026-10-26)**: banks A–D (independent sound sets; switch = straight tap on button groups on BOTH sides of the top bar; active bank shown ONLY by pad outline color + flash/dot/panel accent, buttons have constant look; ~160 ms cross-fade; per-bank customs/volumes/selection persist; defaults differ subtly per bank via tools/make_sounds.py BANKS dict; v1.1 data migrated into Bank A). Final visuals are the user's own SVGs applied in drawBehind (pad lip/hairline/dot-texture clipped to rounded outline, 64×64 button recipe, black pill, top bar painted black). Palette: A #1DA8E8, B #46D140, C #F7931E, D #F02FC2.
3. Known UI pitfall: the `volumes` state map only recomposes on STRUCTURAL writes — mutating an inner plain map is invisible to Compose (this caused the v1.2.0 slider regression). Keep writes on the outer map.

## 2. Current architecture

Package/identity is `com.okdrumdrum.app` (renamed from `com.okdrummer.app` in v1.0.1, 2026-10-26; old-ID installs are uninstalled manually and their data does not migrate). Log tag is `OKDrumDrum`.

```
app/src/main/java/com/okdrumdrum/app/
├── Pad.kt                 enum: LOW_TOM…HAT_OPEN (label + asset name)
├── Bank.kt                banks A–D (accent colors: A #1DA8E8, B #46D140, C #F7931E, D #F02FC2)
├── MainActivity.kt        shell + TopBar (Pill/BankButtons) + PadCell (press lifecycle), state wiring,
│                          recorder flow, Vibrator tick(); user-SVG pad/button/pill faces drawn in
│                          drawBehind (Studio geom constants + dot-tile clip) — no bitmaps
├── engine/                audio layer; zero UI imports
│   ├── DrumEngine.kt      thin Kotlin API: start/trigger/stop/onLatency; JNI bridge
│   └── MicRecorder.kt     AudioRecord capture: mono 16-bit @ native rate, 10 s cap,
│                          lead-silence + trailing stop-tap trim (threshold max(900,peak/20) ± 5 ms pad)
├── cpp/ (app/src/main/cpp/)
│   ├── drum_jni.cpp       AAudio low-latency STREAM (MMAP EXCLUSIVE denied by OPPO policy,
│   │                      falls back to SHARED), stereo int16, USAGE_GAME (exp.3 parity),
│   │                      data callback mixes voices (same math as old Kotlin mixer),
│   │                      512-frame ring (2 bursts), latency report via JNI onNativeLatency
│   └── CMakeLists.txt     libdrum_engine (aaudio android log)
├── assets/
│   ├── SampleBank.kt      loads assets/sounds/<rate>/<bank>/<asset>.wav (both rates × 4 banks at
│   │                      start), nearest rate to device native; NO runtime resampler
│   ├── CustomStore.kt     customs in filesDir/custom/<bank>/<asset>.wav; one-time v1→Bank-A migration
│   ├── WavCodec.kt        16-bit mono WAV read/write
│   └── PadSettings.kt     SharedPreferences "okdrummer": vol_<bank>_<PAD>, active_bank, advice_seen;
│                          one-time v1 vol_<PAD> → vol_A_<PAD> migration
└── ui/PadSheet.kt         full-screen panel, accent-parameterized: Record new / Revert-if-custom /
│                          Volume slider (banked)
tools/make_sounds.py       synthesizes 8 × 4 bank renditions at both rates (BANKS pitch/decay dict)
```

Legacy pull-mixer (AudioTrack) was REPLACED by the AAudio callback engine in Phase 7 (no dead code kept).

### Engine specifics (Phase 7, current)

- AAudio low-latency stream: burst=256 frames (~5.3 ms), ring capacity 512 (2 bursts — default 2048 cost ~42.7 ms lag), SHARING_MODE_SHARED (OPPO denies MMAP EXCLUSIVE via its `aaudio-compatible-apps` gate — same denial reference drum apps get).
- Mixing lives in the C++ callback (`drum_jni.cpp`): voices pooled in a mutex'd vector (max 16), mono mixed into an int accumulator, duplicated L/R in the clamp; nothing allocated on the audio thread.
- Latency stat: "app ms" = trigger → absorbed by callback; "output ms" = playhead lag via `AAudioStream_getTimestamp(CLOCK_MONOTONIC)`, recalibrated every 96 callbacks; reported through JNI `onNativeLatency(Float, Float, Float)` → Kotlin `onLatency` → Log.i("OKDrumDrum").
- Measured on the OPPO DN2103 loudspeaker: total ≈53–56 ms, of which our code ≈2 ms; the rest is the device's fast-output chain — the same value AudioFlinger computes for reference drum apps (dumpsys-verified).
- USAGE_GAME (experiment 3) changed nothing; kept — equivalent parity and semantically right next to music apps (still never requests audio focus).


## 3. What is finished (all device-verified on the user's OPPO)

| Phase | Scope | Verified |
|---|---|---|
| 1 | Scaffold: Gradle+Compose, landscape lock, minSdk 26, 8-pad grid | ☑ |
| 2 | Engine core: fast-path AudioTrack, mixer thread, trigger API, latency hook | ☑ |

| 3 | Default sounds: make_sounds.py → dual-rate assets, SampleBank wired | ☑ |
| 4 | Touch layer: per-pad pointer handler, touch-down trigger, flash, multitouch | ☑ |
| 5 | Hold panel: record/10 s cap/trim, volume persisted, revert, advice dialog | ☑ |
| 6 | Polish: latency UI line removed, adaptive icon, v1.0.0 close-out | ☑ |
| 7 | Latency: AAudio C++ callback engine (raw JNI), 512-frame ring, USAGE_GAME round, platform parity proven | ☑ |
| 8 | Banks A–D + user-SVG pad/button/pill visuals + per-bank persistence + v1→A migration | ☑ |

QA-fix history (why some code looks the way it does): `%f`/Int format crash on sheet open · `performHapticFeedback` silenced by OPPO system setting → direct `Vibrator` (VIBRATE permission) · recordings felt late → lead-silence trim · bottom sheet half-height → full-screen panel · stuck pressed flash → removed wait-loop, reset on all gesture exits · v1.2.0 slider stuck at 100 % → tracked state map needs STRUCTURAL writes (mutating an inner plain map doesn't recompose).

## 4. Immediate next steps

1. Phase 8 is closed (v1.2.0 tagged and pushed). Next backlog items exist only in outline — follow the docs-first rule: write the new feature into PROJECT.md §1/§2 BEFORE implementing anything.
2. Candidates the user may raise next: launcher icon from their own assets, snappier in-app feel, recordings polish, or a new feature set (drum patterns/looping, kits export). Ask the user; do not start speculative work.

## 5. Strict rules (must follow)

- **Docs before diff**: every feature/behavior/decision enters `PROJECT.md` (§1 spec / §2 contracts / §3 must-NOT / §4 process / §5 roadmap) BEFORE implementing.
- **No dead/speculative code**: no unused config, no commented-out blocks, delete rather than comment out, no single-implementation abstractions.
- Phase close-out = `./gradlew :app:assembleDebug :app:lintDebug` with **0 Lint errors** + no TODO + `PROJECT.md` updated.
- §3 must-NOT (verbatim): no audio-focus requests · no alloc/resample/decode on the mixer thread · no disk loads on mixer thread · trigger on touch-down only · never block a second-touch pad · keep the fast mixer path (correct usage/contentType/rate).
- **When a major feature is finished → update `CHANGELOG.md`** before committing.

## 6. Environment & tooling (hard facts)

- Repo: `/Users/aaghilone/Projects/OKDrumDrum` (git, remote `git@github.com:Rhaxa/OKDrumDrum.git`, branch `main`, SSH auth as GitHub user `Rhaxa`).
- Builds: `JAVAHOME…` (next line) — CLI builds MUST set:
  `JAVA_HOME="/Users/aaghilone/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home"` — system Java 26 breaks Gradle 8.9. Android Studio unaffected.
  Command: `JAVA_HOME="<jbr-17>" ./gradlew :app:assembleDebug :app:lintDebug`
- Pins: Gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.09.03 · SDK 34 (only android-29/34 platforms installed locally) · minSdk 29 (API-29+ AAudio data callbacks) · versionCode 6 / versionName 1.2.0.
- Device: OPPO DN2103 (`FQ4LLZUWEYXG4XLF`); adb at `$HOME/Library/Android/sdk/platform-tools/adb`; deploy = `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- First diagnostic after any crash: `adb logcat -d -b crash "*:E"`; latency check: `adb logcat | grep OKDrumDrum` (log tag is **OKDrumDrum** everywhere as of v1.0.1).
- `gh` CLI is NOT usable on this machine (auth login fails; brew install hung) — publish via SSH push only (`git push -u origin main --follow-tags`).
- Current APK (in-repo, committed): `dist/OKDrumDrum-1.2.0-debug.apk` (v1.2.0); README states the path explicitly.

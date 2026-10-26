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

1. **Latency reduction** (NEXT, unblocked) — shave the constant ~20 ms overrun. AAudio/Oboe are *candidates* for the replacement, not commitments: the target path (tuned AudioTrack vs engine swap) is decided during Phase 7 from real measurements (user, 2026-10-26).
2. **Sound banks A–B–C–D** — user swaps ALL 8 sounds of a bank to another set; each bank has a color that changes the pads' outline; Bank A = current blue. **BLOCKED**: waiting for the user to provide an interface mockup.
3. **UI refresh of the pads** (with banks) — same **BLOCKED on the mockup**.

## 2. Current architecture

Package/identity is `com.okdrumdrum.app` (renamed from `com.okdrummer.app` in v1.0.1, 2026-10-26; old-ID installs are uninstalled manually and their data does not migrate). Log tag is `OKDrumDrum`.

```
app/src/main/java/com/okdrummer/app/
├── Pad.kt                 enum: LOW_TOM…HAT_OPEN (label + asset name)
├── MainActivity.kt        shell + PadCell (press lifecycle), state wiring, recorder flow,
│                          Vibrator tick(), PadColors (pad palette lives HERE)
├── engine/                ← the ONLY place AudioTrack/AudioRecord are touched; zero UI imports
│   ├── DrumEngine.kt      AudioTrack fast-path pull mixer (details below)
│   └── MicRecorder.kt     AudioRecord capture: mono 16-bit @ native rate, 10 s cap,
│                          lead-silence trim (threshold max(900, peak/20) + 5 ms preroll)
├── assets/
│   ├── SampleBank.kt      loads dual-rate baked WAVs (assets/sounds/{48000,44100}/<asset>.wav),
│   │                      picks nearest to device native rate; NO runtime resampler
│   ├── CustomStore.kt     per-pad customs in filesDir/custom/<asset>.wav
│   ├── WavCodec.kt        16-bit mono WAV read/write
│   └── PadSettings.kt     SharedPreferences "okdrummer": vol_<PAD>, advice_seen
└── ui/PadSheet.kt         full-screen panel: Record new / Revert-if-custom / Volume slider
tools/make_sounds.py       synthesizes the 8 default one-shots at both rates
```

### DrumEngine details (relevant to latency work)

- `AudioTrack`, MODE_STREAM, `PERFORMANCE_MODE_LOW_LATENCY`, `USAGE_MEDIA` + `CONTENT_TYPE_SONIFICATION`, mono 16-bit, native rate via `AudioManager.PROPERTY_OUTPUT_*`.
- Buffer = `max(getMinBufferSize, framesPerBuffer*2*2)` → typically 2× the fast-path buffer. **Prime buffer filled with silence before `play()`** (this affects the measured output lag).
- Always-on mixer thread (URGENT_AUDIO): writes one `framesPerBuffer` chunk per loop; pending voices (ConcurrentLinkedQueue of `Pair<Voice, triggerNanos>`) are absorbed at chunk boundaries; up to 16 voices mixed into an int accumulator, then clamped to shorts.
- Latency hook `onLatency(LatencyStat(totalMs, appMs, outputMs))` fired per trigger from the mixer thread; `MainActivity` logs it via `Log.i("OKDrumDrum", "trigger total=…")` (no UI line anymore).
- Playhead-lag calibration every 96 chunks using `AudioTrack.getTimestamp()`.

Known latency makeup (~47 ms measured on the reference OPPO): touch-driven trigger → UI thread → mixer poll granularity (≤1 chunk, e.g. 256 frames @48 kHz = ~5.3 ms) + buffer depth (~5–10 ms) + output chain (~30 ms?) — the fix likely requires either smaller/primes buffer, per-track fast path, or AAudio low-latency stream.

## 3. What is finished (all device-verified on the user's OPPO)

| Phase | Scope | Verified |
|---|---|---|
| 1 | Scaffold: Gradle+Compose, landscape lock, minSdk 26, 8-pad grid | ☑ |
| 2 | Engine core: fast-path AudioTrack, mixer thread, trigger API, latency hook | ☑ |

| 3 | Default sounds: make_sounds.py → dual-rate assets, SampleBank wired | ☑ |
| 4 | Touch layer: per-pad pointer handler, touch-down trigger, flash, multitouch | ☑ |
| 5 | Hold panel: record/10 s cap/trim, volume persisted, revert, advice dialog | ☑ |
| 6 | Polish: latency UI line removed, adaptive icon, v1.0.0 close-out | ☑ |

QA-fix history (why some code looks the way it does): `%f`/Int format crash on sheet open · `performHapticFeedback` silenced by OPPO system setting → direct `Vibrator` (VIBRATE permission) · recordings felt late → lead-silence trim · bottom sheet half-height → full-screen panel · stuck pressed flash → removed wait-loop, reset on all gesture exits.

## 4. Immediate next steps

1. **Update `PROJECT.md` Phase 7 as findings land** (docs-before-diff). If measurements point to an engine replacement (e.g. AAudio/Oboe), the "no NDK / Kotlin only" rule in §1/§2 must be amended BEFORE coding (Oboe = prebuilt `.so`/AAR, a deliberate rule change).
2. Investigate the ~20 ms: re-verify on device with `adb logcat | grep OKDrumDrum` (hook still live); compare `appMs` vs `outputMs` split; candidate levers: buffer size (currently 2× fast-path buffer), primed-silence depth, chunk granularity, then decide AudioTrack tuning vs engine rewrite (AAFaudio/Oboe = candidates only; decided during Phase 7).
3. Acceptance for phase: steady-state ≤ 33 ms target on the reference device (user said latency sat "constantly 20 ms above target" — treat 47 ms as the baseline to beat).
4. Do NOT start banks/mockup work — blocked until user supplies the interface mockup.

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
- Pins: Gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.09.03 · SDK 34 (only android-29/34 platforms installed locally) · minSdk 26 · versionCode 3 / versionName 1.0.0.
- Device: OPPO DN2103 (`FQ4LLZUWEYXG4XLF`); adb at `$HOME/Library/Android/sdk/platform-tools/adb`; deploy = `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- First diagnostic after any crash: `adb logcat -d -b crash "*:E"`; latency check: `adb logcat | grep OKDrumDrum` (log tag is **OKDrumDrum** everywhere as of v1.0.1).
- `gh` CLI is NOT usable on this machine (auth login fails; brew install hung) — publish via SSH push only (`git push -u origin main --follow-tags`).
- Current APK (in-repo, committed): `dist/OKDrumDrum-1.0.1-debug.apk` (v1.0.1); README states the path explicitly.

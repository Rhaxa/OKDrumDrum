# Changelog

All notable changes to OKDrumDrum are documented here. Update this file every time a major feature is finished.

## [1.1.0] — 2026-10-26

### Changed
- **Audio engine replaced with an AAudio callback client (C++ via JNI, no third-party lib)**: mixing now runs in the device's fast audio callback path (raw AAudio, MMAP-denied devices fall back to a SHARED low-latency stream), stereo 16-bit out; legacy AudioTrack pull mixer removed.
- Ring capacity fixed at 2 bursts (512 frames) — cuts written-ahead audio from ~42.7 ms to ~10.7 ms on the reference device.
- minSdk raised 26 → 29 (AAudio data callbacks are API 29+); stream rides USAGE_GAME (no audio focus is ever requested, unchanged).

### Improved
- Trigger latency: ~53 ms total on the reference device's loudspeaker (was ~47 ms with a deeper queue and worse worst-case), of which ≈2 ms is app code. Independent verification: the framework reports the identical track latency for reference drum apps on the same device — the remainder is the vendor speaker output path, not app-side.
- Log tag/reporting unchanged (`OKDrumDrum`).

### Fixed
- Recorded custom sounds carried an audible "tap" from the stop button — the tail is now trimmed (last sample above threshold + 5 ms pad).

## [1.0.1] — 2026-10-26

### Changed
- Android package/identity renamed `com.okdrummer.app` → `com.okdrumdrum.app` (full source-tree + namespace + applicationId rename; log tags unified to `OKDrumDrum`). Existing recordings/volumes under the old ID do not migrate — the old install was kept until manually uninstalled.
- Prebuilt debug APK now shipped in-repo: `dist/OKDrumDrum-1.0.1-debug.apk`, path documented in README.

## [1.0.0] — 2026-10-25

First public release: https://github.com/Rhaxa/OKDrumDrum

### Added
- 2×4 landscape drumpad grid (low/mid/high tom, cymbal / kick, snare, closed hat, open hat) with touch-down triggering, full multitouch, and pad flash.
- Low-latency audio engine: always-on pull-mixer thread on a fast-path `AudioTrack` (native sample rate/buffer, `PERFORMANCE_MODE_LOW_LATENCY`, USAGE_MEDIA + CONTENT_TYPE_SONIFICATION), never requesting audio focus.
- Per-pad panel on long-press (~300 ms, haptic tick): record a custom sound (mic, 10 s max, lead-silence trim), volume slider (0–100 %, persisted), revert to default.
- Custom sounds persisted as WAV in app storage; per-pad volumes persisted in SharedPreferences; first-launch latency advisory ("don't show again").
- Dual-rate baked default sounds (48 kHz + 44.1 kHz via `tools/make_sounds.py`) with nearest-rate asset loading — no runtime resampling.
- Latency logging hook (logcat) and adaptive launcher icon.

### Fixed
- Crash on opening the panel (`%f` vs Int format) · stuck pressed-state flash · missing haptic on phones where system touch feedback is off · recordings feeling late due to head dead-air.

### Notes
- Measured trigger latency on the reference device: ~47 ms (target ≤ 33 ms — accepted for v1; reduction is planned next).
- MIT License, copyright 2026 Rhaxa.

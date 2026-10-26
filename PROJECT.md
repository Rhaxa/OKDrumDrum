# Drumpad — Project Reference

Single doc for spec, decisions, and roadmap. Keep it terse; update it **before** implementing any change.
Status symbols: ☐ todo · ◐ in progress · ☑ done
**Project status: v1.0.0 released 2026-10-25** (Phases 1–6 ☑). Current work: **Phase 7 — latency reduction** (~20 ms over the ≤ 33 ms target); Phase 8 (banks A–D + pad UI) is BLOCKED on the user's interface mockup. Major-feature log lives in [`CHANGELOG.md`](CHANGELOG.md).

## 1. Product spec

A landscape-mode Android drumpad, 2 rows × 4 pads.

Pads (left→right, top row first):

| # | Top / bottom | Default sound |
|---|--------------|---------------|
| 1 | top | low tom |
| 2 | top | mid tom |
| 3 | top | high tom |
| 4 | top | cymbal |
| 5 | bottom | kick |
| 6 | bottom | snare |
| 7 | bottom | closed hat |
| 8 | bottom | open hat |

### Behaviors
- **Tap** (touch-down, not lift): pad plays instantly; pad visibly flashes.
- **Multitouch**: any number of pads simultaneously; fingers on different pads never block each other.
- **Hold-while-sliding nuance (user-decided, 2026-10-25)**: a finger held on pad A and dragged over pad B does NOT trigger B — holding is reserved for the options sheet, and crossing pads stay silent. Accepted, not a bug.
- **Custom badge**: a small dot on a pad indicates a custom sound is loaded (per active bank; dot takes the bank color).
- **Long-press** (~300 ms): haptic tick → full-screen options panel on that pad with three actions:
  1. **Record new sound** (max 10 s, mic PCM, records into the ACTIVE BANK, live-instant trigger once recorded)
  2. **Revert to default** (only shown if a custom sound exists in this bank; deletes the custom WAV)
  3. **Volume** slider (0–100 % gain for this pad **in the active bank**, applies to default and custom sounds, persisted)
- Custom sounds + volumes persist across restarts per (bank, pad); active bank persists; state survives process death.
- **First launch**: one-time dialog: use phone speaker or wired headphones; Bluetooth adds latency; disable battery-saver for best latency. "Don't show again" supported.

### Banks (v1.2, user-approved 2026-10-26 from user mockup)
- **4 banks: A, B, C, D** — independent full sound sets. Each (bank, pad) starts with the built-in default rendition of that bank.
- **Default sounds vary subtly per bank** (user idea): same 8 instruments, different pitch/ADSR rendition baked by `tools/make_sounds.py` (4 renditions × 2 rates). No drastic differences.
- **Switching**: straight tap, instant; available on BOTH sides of the top bar for left/right-handed symmetry. Selected bank is NOT visualized on the buttons themselves (constant look); **the active bank is communicated solely by the pad outline color** (user-decided).
- **Colors** (from mockup): A = cyan-blue, B = green, C = orange, D = magenta. Bank buttons: letter + thicker colored "bottom lip", constant regardless of selection; brand pill "OKDRUMDRUM" centered between the two button groups.
- **Pad visuals**: rounded rectangles, dark surface, bank-colored outline; **pressed state = bank-colored** (fill ≈15 % bank color + bright bank-colored border); tiny low-contrast label (pad name) kept; custom dot in bank color when that bank has a custom on that pad.
- **Bank switch feedback**: short (~150–200 ms) cross-fade of pad outline colors; no layout shift.
- **Record panel**: themed with the active bank color (title/border/accent) so the recording target is always obvious.
- **Migration**: v1.1 custom sounds + volumes migrate into **Bank A** on first 1.2 launch (one-time, flagged in prefs); active bank defaults to A.

### Non-goals
No sequencer, no samples library browsing UI, no sharing/export, no sound packs UI (custom packs later = pre-built asset swaps, no feature work).

**SPEC AMENDMENT (2026-10-26, user-approved via measurement decision):** the audio client may be a C++ NDK layer using AAudio (raw JNI AAudio at first — no Oboe dependency). Scope: just the audio client; all mixing logic and app code stay pure Kotlin + Compose. Third-party app libraries (incl. Oboe as an AAR) are still not allowed.

## 2. Technical constraints & contracts

- **Latency contract**: touch-down → sound starts playing in ≤ 33 ms on device speaker/wired headphones on a modern device, USB/BT excluded and battery saver off. Verified by in-app latency logging (trigger enqueue time vs `AudioTrack.getTimestamp()` presentation time), not by feel.
- **Audio engine** (sole producer of sound; `engine/` package, zero UI imports; elsewhere zero `AudioTrack`/`AudioRecord`/AAudio imports):
  - Kotlin + Compose everywhere else; the fast output client is a **raw AAudio JNI/C++ layer** (app/src/main/cpp): low-latency MMAP if the device has it, fallback to regular AAudio stream; `USAGE_MEDIA` + `CONTENT_TYPE_SONIFICATION`, device-native sample rate & native frames per callback.
  - Always-on mixer thread (Kotlin pull mixer for AudioTrack fallback) or AAudio data callback filling silence by default, triggered samples mixed in within ≤ 1 chunk.
  - **Never request audio focus** — must mix with any concurrently playing media (song background playing with app open).
  - All mixing at native rate; dual-rate assets (48k/44.1k), near-native variant picked at start, no runtime resampling.
- **Sounds**: 8 instruments × 4 bank renditions generated by `tools/make_sounds.py` → 16-bit PCM mono WAV assets, baked at BOTH 48 kHz and 44.1 kHz (dirs `sounds/<rate>/<bank>/<asset>.wav`); banks differ by subtle pitch/ADSR variation (user request), not by instrument. At startup the variant matching (or nearest to) the device native rate is loaded for the active bank — no runtime resampler. When the bank switches, the pre-loaded other-bank samples are already in memory (all 4 banks × 8 pads loaded at start; small footprint, single allocation pass, never on the audio thread).
- **Recording**: `AudioRecord`, mono, native sample rate, 16-bit PCM, 10 s cap, persisted as WAV in `filesDir/custom/<bank>/<asset>.wav`; recorded capture is pre-trimmed (lead dead-air at head, stop-tap impulse at tail — cut after the last sample above threshold + 5 ms pad) so a recorded hit triggers and ends in time with default sounds; `RECORD_AUDIO` requested only when the user first chooses Record.
- **Per-(bank, pad) volume**: persisted (SharedPreferences `vol_<bank>_<PAD>`), applied as gain in engine. Active bank persisted (`active_bank`), migration flag (`migrated_v1`): first 1.2 run copies v1.1 customs (`filesDir/custom/<asset>.wav`) into Bank A and converts `vol_<PAD>` → `vol_A_<PAD>`.
- **UI**: Jetpack Compose, landscape-locked activity, minSdk 29 (raised 26→29 in Phase 7: AAudio data callbacks are API 29+), no third-party app libs.
- **Toolchain** (fixed): Gradle 8.9 wrapper · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.09.03 · compile/target SDK 34 (an android-34 platform is present locally, so builds never fetch SDK components). CLI builds must set `JAVA_HOME` to the JBR 17 runtime (`/Users/aaghilone/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home`) — system Java 26 is too new and fails; Android Studio handles this automatically.
- **Latency expectations honesty**: BT ≈ +100–200 ms unavoidable; some battery-saver modes drop the fast mixer path; latency target is best-effort per device, logged and reported.
- **Latency status (2026-10-26, CLOSED)**: engine is an AAudio callback client (raw JNI/C++, no Oboe) at platform parity on the reference device: total ≈53 ms on loudspeaker, of which ≈2 ms is our code; the remaining constant is the vendor fast-output path, identical for every low-latency app on this device (dumpsys evidence above). ≤33 ms is not reachable *per-app* on this hardware; documented honestly instead of claimed.
- **Phase 7 findings (2026-10-26, logcat split n≈25)**: app-side (touch→enqueue→next mix chunk) is healthy: 0.1–8.8 ms, median ≈2.3 ms. The entire overrun sits in **output playhead lag: 45–47 ms constant**. Device facts (dumpsys): fast mixer chain exists (FastMixer 256 frames @48 kHz) but with a vendor layer on the speaker path (oplus `dirac` + mono pipe + PipeSink); mic HAL runs 960-frame buffers.
- **Phase 7 measured floor** (AudioTrack era, superseded): requested 768-frame capacity → Android clamps to `getMinBufferSize` = **2052 frames ≈ 42.8 ms** (confirmed live). Client-side AudioTrack pacing cannot reach ≤33 ms here. **DECISION (user-approved, 2026-10-26): replace engine client with raw AAudio via JNI/C++; Kotlin mix logic ported to the callback; minSdk raised 26→29; no third-party lib (raw AAudio, not Oboe).**
- **Phase 7 progress (AAudio era)**: callback engine landed (burst=256, SHARED after OPPO's MMAP allowlist gate `aaudio denied …`). Capacity 2048 → total ~86 ms; capacity set to 2 bursts (512) → speaker **~56 ms** (app ≈3 ms, output ≈52 ms constant). Vendor dirac effect pipeline initially suspected, then ruled the dominant cost — see the A/B probe below.
- **Phase 7 experiment 3 (2026-10-26)**: USAGE_GAME + no content type — measured **no change** (~54 ms speaker output constant). Route unchanged.
- **Phase 7 A/B probe (2026-10-26, user-initiated)**: a well-known third-party drum app runs Oboe on this same phone, hits the *same* OPPO allowlist denial (`aaudio denied … incompatible policy`), and — decisive — `dumpsys media.audio_flinger` shows both apps as fast tracks on the same output (`AudioOut_1D`, HAL 256) with an **identical 52.67 ms** track latency; dirac effects: 0 active tracks on it. Conclusion: the ~52 ms loudspeaker constant is the vendor HAL/DSP path and applies to every low-latency app equally — no software lever remains to beat it per-app. Our engine is at parity with the reference app: app-side ≈2 ms, callback engine optimal. **User A/B feel verdict: "basically the same" (2026-10-26).**

## 3. Negative acceptance (must NOT)

- Must not request audio focus.
- Must not allocate, resample, or decode on the audio/mixer thread loop.
- Must not load custom sounds from disk on the mixer thread (preloaded at start / on record completion).
- Must not trigger pad sounds on touch-up/lift (touch-down only).
- Must not block a second pad's touch while the first finger is still down (pointer splitting, no shared gesture logic).
- Must not ship dead/speculative code: no unused config, no commented-out blocks, no abstraction with one implementation, nothing "for later".
- Must not break the fast mixer path with wrong usage/contentType/sample rate.

## 4. Process & code conventions

- Docs before diff: every feature/behavior change enters this file first.
- Update `CHANGELOG.md` whenever a major feature is finished.
- Delete rather than comment out; git history remembers.
- Phase close-out checklist: Gradle Android Lint passes (0 errors) · no TODO/FIXME · no unused code · this file updated.
- Review loop: agent self-reviews diffs against §3 before calling a task done.

## 5. Roadmap

### Phase 1 — Scaffold ☑
- ☑ Git init; Gradle + Compose skeleton; landscape lock; minSdk 26
- ☑ Empty 8-pad grid renders in landscape (assembleDebug + lintDebug pass, 0 lint errors)
- ☑ Acceptance verified on user's phone (2026-10-25): landscape locked, 8 labeled pads correct
- Note: default system icon + `Theme.Material.NoActionBar` for now; real icon is Phase 6 polish.

### Phase 2 — Audio engine core ☑
- ☑ Native rate/buffer detection; fast-path AudioTrack; mixer thread + trigger API
- ☑ Latency logging hook (per-trigger: app-segment + output-lag estimate → logcat + on-screen debug line)
- ☑ TEMPORARY debug tone wired to pad touch-down (removed in Phase 3) — device readability of latency without adb
- ⚠️ Result (2026-10-25, user's phone): ~47 ms steady state; user accepts as playable; latency revisit deferred to after Phase 4 (see §2 latency status)

### Phase 3 — Default sounds ☑
- ☑ Synthesis script `tools/make_sounds.py` → 8 WAVs per rate variant in assets (48 kHz + 44.1 kHz)
- ☑ `Pad` enum + `SampleBank` loader (synchronous on main, mixer thread never touches disk)
- ☑ Pads wired to their samples; debug tone removed
- ☑ Acceptance verified on user's phone (2026-10-25): correct, distinct sounds; multitouch rolls OK

### Phase 4 — Touch layer ☑
- ☑ Per-pad pointer handling owns full press lifecycle: trigger + visual state on touch-down, release/cancel tracked to all-ups
- ☑ Pressed visual flash (lighter surface + accent edge) per pad, recomposed per-pad only
- ☑ Acceptance verified on user's phone (2026-10-25): rolls OK, flash immediate, slide-across behavior accepted per §1 note

### Phase 5 — Long-press sheet ☑
- ☑ Long-press + haptic + bottom sheet on pad: Record new / Volume / Revert-if-custom
- ☑ `AudioRecord` capture flow (runtime permission incl. `checkSelfPermission` guard, 10 s cap, progress bar, instant trigger once saved)
- ☑ Revert deletes custom WAV; per-pad volume persisted; custom badge dot on pad
- ☑ First-launch advisory dialog ("don't show again")
- ☑ Phone-QA fix round (2026-10-25): %f/Int format crash on sheet open; haptic via direct Vibrator (performHapticFeedback is silenced by system touch-feedback setting); leading-silence trim on recordings (head dead-air made triggers feel late); sheet replaced by full-screen panel (bottom sheet hid controls)
- ☑ Acceptance verified on user's phone (2026-10-25, 0.5.2): record → tap plays in time; revert restores; volume persists; haptic felt; crash-free logcat

### Phase 6 — Polish & verify ☑
- ☑ Remove on-screen latency line (§2 verification now via logcat only) — per user decision "keep till Phase 5"
- ☑ Proper launcher icon (adaptive, drum-pad motif)
- ☑ Version 1.0.0 + final close-out checklist: build ✓ lint 0 errors ✓ no TODO/dead code ✓ docs final pass ✓
- ☑ Acceptance verified on user's phone (2026-10-25): clean fullscreen grid, full flow OK, icon fine
- Status: **v1.0.0** (published at github.com/Rhaxa/OKDrumDrum, tag `v1.0.0`)

### Phase 7 — Latency reduction ☑
- Goal: close the constant ~20 ms gap — steady state is ~47 ms vs the ≤ 33 ms contract (§2).
- ☑ Re-measure baseline & split via logcat hook; app-side healthy (~2 ms), overrun all in output path.
- ☑ Decide path from measurements: raw AAudio via JNI/C++ (no Oboe dependency); "no NDK" rule amended in §1/§2 BEFORE coding (docs-before-diff).
- ☑ Implement + verify: AAudio callback client written in C++ (app/src/main/cpp), AudioTrack path removed; ring shrunk to 2 bursts (2048→512 frames, -42→-11 ms lag); USAGE_GAME parity round; stop-tap tail trim on recordings; minSdk raised 26→29.
- ✅ Accept (closed 2026-10-26): steady state ≈53 ms total / ≈2 ms app-side; ≤33 ms proven unreachable on this device's loudspeaker (vendor constant, identical for the reference drum app — dumpsys evidence). User Feel-verified parity. Phase 7 ☑ with the floor documented in §2.

### Phase 8 — Sound banks A–D + pad UI (design locked 2026-10-26, implementation not started) ◐
Design is locked from the user's mockup + discussion (§1 Banks; button look constant, active bank = pad outline color only, bank-colored pressed flash & dot, ~150 ms color cross-fade, tiny pad labels, per-bank volumes, straight-tap switching on both sides, branded top bar, bank-themed record panel, subtle per-bank default renditions, migration into Bank A).
- ☐ `tools/make_sounds.py`: 4 bank renditions (pitch/ADSR variation), 2 rates → 64 WAVs
- ☐ `SampleBank`/`CustomStore`/`PadSettings` bank-aware (assets `sounds/<rate>/<bank>/`, customs `custom/<bank>/`, prefs keys per bank; v1→A migration)
- ☐ Engine: load all 4 banks at start; trigger = active bank's sample; switch without touching the audio thread
- ☐ UI: top bar (A–D left & right, center pill), pad grid restyle (rounded, bank outline, bank pressed state, bank dot, tiny label, cross-fade animation), bank-themed record panel
- ☐ Version 1.2.0; close-out per §4 (lint 0, docs, CHANGELOG)
- ✅ Accept: switching banks changes pad outline color + all 8 sounds atomically (both sides tappable); per-bank custom + volume persist; defaults differ audibly-but-subtly between banks; migration preserves v1.1 data; no dead code.
</content>
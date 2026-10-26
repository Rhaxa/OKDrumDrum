#!/usr/bin/env python3
"""Generate the default drumpad one-shots, 4 bank renditions (PROJECT.md §1/§2).

Writes 16-bit PCM mono WAVs at 48 kHz and 44.1 kHz into
app/src/main/assets/sounds/<rate>/<bank>/, one per Pad asset name; banks A–D
carry the same 8 instruments with subtly different pitch/ADSR tuning (user
request). Stdlib only.
"""

import math
import os
import random
import struct
import wave

OUT_ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sounds")
PEAK = 0.85

# Per-bank musical tuning: pitch multiplier + decay multiplier. Bank A is the
# v1.1 baseline; B–D drift far enough to be audible close-up, not enough to
# change the kit (user: "No need to be drastic. Pitch, ADSR are enough.").
BANKS = {
    "A": {"pitch": 1.000, "decay": 1.000},
    "B": {"pitch": 1.059, "decay": 0.88},   # a semitone up, slightly tighter
    "C": {"pitch": 0.944, "decay": 1.18},   # slightly down, longer tails
    "D": {"pitch": 1.122, "decay": 1.00},   # a whole step up, same envelopes
}


def write_wav(path, sr, samples):
    peak = max(1e-9, max(abs(s) for s in samples))
    norm = PEAK / peak
    ints = (int(max(-1.0, min(1.0, s * norm)) * 32767) for s in samples)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(struct.pack("<%dh" % len(samples), *ints))


def fade_out(samples, sr, dur=0.005):
    n = min(len(samples), int(sr * dur))
    for i in range(n):
        samples[-1 - i] *= i / max(1, n)
    return samples


def highpass(samples, a):
    """One-pole HP: y[i] = a * (y[i-1] + x[i] - x[i-1]) — passes highs, kills lows."""
    out = [0.0] * len(samples)
    px = 0.0
    py = 0.0
    for i, x in enumerate(samples):
        y = a * (py + x - px)
        out[i] = y
        px = x
        py = y
    return out


def render_metallic(sr, base, n, envelope, noise_decay, hp=0.93, noise_amp=0.45, pitch=1.0, decay=1.0):
    """Six detuned square oscillators (classic metallic core) + optional noise, double-HP'd."""
    freqs = [base * pitch * r for r in (2.0, 3.0, 4.16, 5.43, 6.79, 8.21)]
    phases = [0.0] * 6
    raw = [0.0] * n
    for i in range(n):
        t = i / sr
        s = 0.0
        for k, f in enumerate(freqs):
            phases[k] += 2.0 * math.pi * f / sr
            s += 1.0 if math.sin(phases[k]) >= 0 else -1.0
        s *= 0.3
        if noise_amp > 0:
            s += random.uniform(-1, 1) * math.exp(-t * noise_decay / decay) * noise_amp
        raw[i] = s * envelope(t, decay)
    return highpass(highpass(raw, hp), hp)


def kick(sr, pitch=1.0, decay=1.0):
    n = int(sr * 0.5)
    out = [0.0] * n
    phi = 0.0
    for i in range(n):
        t = i / sr
        f = 42.0 * pitch + 108.0 * pitch * math.exp(-t * 30.0)
        phi += 2.0 * math.pi * f / sr
        body = 0.9 * math.exp(-t * 8.0 / decay) * math.sin(phi)
        click = random.uniform(-1, 1) * math.exp(-t * 500.0) * 0.35
        out[i] = body + click
    return fade_out(out, sr)


def snare(sr, pitch=1.0, decay=1.0):
    n = int(sr * 0.28)
    phi = 0.0
    tone = [0.0] * n
    for i in range(n):
        t = i / sr
        phi += 2.0 * math.pi * 190.0 * pitch / sr
        tone[i] = 0.55 * math.exp(-t * 35.0 / decay) * math.sin(phi)
    noise = highpass(highpass([random.uniform(-1, 1) for _ in range(n)], 0.86), 0.86)
    out = [0.0] * n
    for i in range(n):
        t = i / sr
        attack = random.uniform(-1, 1) * math.exp(-t * 700.0 / decay) * 0.3
        out[i] = tone[i] + 0.8 * math.exp(-t * 26.0 / decay) * noise[i] + attack
    return fade_out(out, sr)


def hat(sr, closed, pitch=1.0, decay=1.0):
    n = int(sr * (0.12 if closed else 0.55) * decay)
    decay_rate = 70.0 if closed else 12.0
    raw = render_metallic(
        sr, 40.0, n, lambda t, d: math.exp(-t * decay_rate / d),
        noise_decay=90.0, noise_amp=0.35, pitch=pitch, decay=decay,
    )
    return fade_out(raw, sr)


def tom(sr, f_start, f_end, k_f, k_a, dur, pitch=1.0, decay=1.0):
    n = int(sr * dur * decay)
    phi = 0.0
    out = [0.0] * n
    for i in range(n):
        t = i / sr
        f = f_end * pitch + (f_start * pitch - f_end * pitch) * math.exp(-t * k_f / decay)
        phi += 2.0 * math.pi * f / sr
        out[i] = 0.85 * math.exp(-t * k_a / decay) * math.sin(phi)
        out[i] += random.uniform(-1, 1) * math.exp(-t * 600.0 / decay) * 0.12
    return fade_out(out, sr)


def cymbal(sr, pitch=1.0, decay=1.0):
    n = int(sr * 1.5 * decay)

    def env(t, d):
        attack = min(1.0, t / 0.003)
        return attack * 0.8 * math.exp(-t * 1.9 / d) + 0.5 * math.exp(-t * 55.0 / d)

    raw = render_metallic(sr, 160.0, n, env, noise_decay=2.2, noise_amp=0.5, hp=0.95, pitch=pitch, decay=decay)
    return fade_out(raw, sr)


SOUNDS = {
    "tom_low": lambda sr, p, d: tom(sr, 110.0, 72.0, 26.0, 8.0, 0.45, pitch=p, decay=d),
    "tom_mid": lambda sr, p, d: tom(sr, 165.0, 104.0, 30.0, 9.0, 0.4, pitch=p, decay=d),
    "tom_high": lambda sr, p, d: tom(sr, 240.0, 174.0, 34.0, 10.0, 0.35, pitch=p, decay=d),
    "cymbal": lambda sr, p, d: cymbal(sr, pitch=p, decay=d),
    "kick": lambda sr, p, d: kick(sr, pitch=p, decay=d),
    "snare": lambda sr, p, d: snare(sr, pitch=p, decay=d),
    "hat_closed": lambda sr, p, d: hat(sr, closed=True, pitch=p, decay=d),
    "hat_open": lambda sr, p, d: hat(sr, closed=False, pitch=p, decay=d),
}


def main():
    for rate in (48_000, 44_100):
        for bank, tune in BANKS.items():
            random.seed(20261025)  # deterministic noise, per rendition
            out_dir = os.path.join(OUT_ROOT, str(rate), bank)
            for name, make in SOUNDS.items():
                samples = make(rate, tune["pitch"], tune["decay"])
                write_wav(os.path.join(out_dir, name + ".wav"), rate, samples)
                print("%s %s -> %s.wav (%d frames)" % (rate, bank, name, len(samples)))


if __name__ == "__main__":
    main()

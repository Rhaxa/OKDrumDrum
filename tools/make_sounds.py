#!/usr/bin/env python3
"""Generate the 8 default drumpad one-shots (PROJECT.md §2).

Writes 16-bit PCM mono WAVs at 48 kHz and 44.1 kHz into
app/src/main/assets/sounds/<rate>/, one per Pad asset name. Stdlib only.
"""

import math
import os
import random
import struct
import wave

OUT_ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sounds")
PEAK = 0.85
random.seed(20261025)


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


def render_metallic(sr, base, n, envelope, noise_decay, hp=0.93, noise_amp=0.45):
    """Six detuned square oscillators (classic metallic core) + optional noise, double-HP'd."""
    freqs = [base * r for r in (2.0, 3.0, 4.16, 5.43, 6.79, 8.21)]
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
            s += random.uniform(-1, 1) * math.exp(-t * noise_decay) * noise_amp
        raw[i] = s * envelope(t)
    return highpass(highpass(raw, hp), hp)


def kick(sr):
    n = int(sr * 0.5)
    out = [0.0] * n
    phi = 0.0
    for i in range(n):
        t = i / sr
        f = 42.0 + 108.0 * math.exp(-t * 30.0)
        phi += 2.0 * math.pi * f / sr
        body = 0.9 * math.exp(-t * 8.0) * math.sin(phi)
        click = random.uniform(-1, 1) * math.exp(-t * 500.0) * 0.35
        out[i] = body + click
    return fade_out(out, sr)


def snare(sr):
    n = int(sr * 0.28)
    phi = 0.0
    tone = [0.0] * n
    for i in range(n):
        t = i / sr
        phi += 2.0 * math.pi * 190.0 / sr
        tone[i] = 0.55 * math.exp(-t * 35.0) * math.sin(phi)
    noise = highpass(highpass([random.uniform(-1, 1) for _ in range(n)], 0.86), 0.86)
    out = [0.0] * n
    for i in range(n):
        t = i / sr
        attack = random.uniform(-1, 1) * math.exp(-t * 700.0) * 0.3
        out[i] = tone[i] + 0.8 * math.exp(-t * 26.0) * noise[i] + attack
    return fade_out(out, sr)


def hat(sr, closed):
    n = int(sr * (0.12 if closed else 0.55))
    decay = 70.0 if closed else 12.0
    raw = render_metallic(sr, 40.0, n, lambda t: math.exp(-t * decay), noise_decay=90.0, noise_amp=0.35)
    return fade_out(raw, sr)


def tom(sr, f_start, f_end, k_f, k_a, dur):
    n = int(sr * dur)
    phi = 0.0
    out = [0.0] * n
    for i in range(n):
        t = i / sr
        f = f_end + (f_start - f_end) * math.exp(-t * k_f)
        phi += 2.0 * math.pi * f / sr
        out[i] = 0.85 * math.exp(-t * k_a) * math.sin(phi)
        out[i] += random.uniform(-1, 1) * math.exp(-t * 600.0) * 0.12
    return fade_out(out, sr)


def cymbal(sr):
    n = int(sr * 1.5)

    def env(t):
        attack = min(1.0, t / 0.003)
        return attack * 0.8 * math.exp(-t * 1.9) + 0.5 * math.exp(-t * 55.0)

    raw = render_metallic(sr, 160.0, n, env, noise_decay=2.2, noise_amp=0.5, hp=0.95)
    return fade_out(raw, sr)


SOUNDS = {
    "tom_low": lambda sr: tom(sr, 110.0, 72.0, 26.0, 8.0, 0.45),
    "tom_mid": lambda sr: tom(sr, 165.0, 104.0, 30.0, 9.0, 0.4),
    "tom_high": lambda sr: tom(sr, 240.0, 174.0, 34.0, 10.0, 0.35),
    "cymbal": cymbal,
    "kick": kick,
    "snare": snare,
    "hat_closed": lambda sr: hat(sr, closed=True),
    "hat_open": lambda sr: hat(sr, closed=False),
}


def main():
    for rate in (48_000, 44_100):
        out_dir = os.path.join(OUT_ROOT, str(rate))
        for name, make in SOUNDS.items():
            samples = make(rate)
            write_wav(os.path.join(out_dir, name + ".wav"), rate, samples)
            print("%s -> %s.wav (%d frames)" % (rate, name, len(samples)))


if __name__ == "__main__":
    main()

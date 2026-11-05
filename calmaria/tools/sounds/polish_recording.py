"""Clean up a short phone recording and turn it into a wide, seamless stereo loop.

Used for "Chuva na estrada" (rain on the road), recorded on a phone for Calmaria: 13 s of mono AAC
with a lot of low road/traffic rumble. Steps:

1. trim the silent start and the very end (record button);
2. zero-phase EQ: a strong low cut that tames the rumble but keeps some body, and a slight lift
   of the drops' detail, bringing the tonal balance close to the app's other rain recordings;
3. re-stitch random 2.5-4.5 s chunks with equal-power crossfades into a ~60 s texture, so 13 s of
   material doesn't audibly repeat (consecutive chunks never come from overlapping source regions,
   which would bump the level in the crossfade);
4. close the texture into a loop (its tail crossfaded into its head) and make the right channel the
   same loop half a turn later: two uncorrelated takes of the same rain, so a mono recording sounds
   wide on headphones and still sums cleanly to mono;
5. loudness-normalize (EBU R128) and encode 48 kHz Ogg Vorbis, then verify what a player decodes.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from scipy.signal import butter, correlate, sosfiltfilt

import audio_analysis as aa
import make_loop as ml

SR = aa.SR

# EQ points (Hz, dB), interpolated on a log-frequency axis.
EQ = [(20, -36), (31, -30), (63, -26), (125, -21), (250, -10), (500, -3), (1000, 0),
      (2000, 2), (4000, 2.5), (8000, 3), (16000, 1), (24000, 0)]


def eq(x, points=EQ, pad_s=1.0):
    """Zero-phase FFT equalizer; reflection padding keeps the filter from wrapping around."""
    pad = int(pad_s * SR)
    y = np.pad(x, ((pad, pad), (0, 0)), mode="reflect")
    n = len(y)
    f = np.fft.rfftfreq(n, 1 / SR)
    pf = np.log10([p[0] for p in points])
    gains = np.interp(np.log10(np.maximum(f, 1.0)), pf, [p[1] for p in points])
    spec = np.fft.rfft(y, axis=0) * (10 ** (gains / 20))[:, None]
    return np.fft.irfft(spec, n=n, axis=0)[pad:pad + len(x)]


def stitch_loop(x, seconds, chunk=(2.5, 4.5), xfade=0.8, seed=11):
    """A seamless loop of about `seconds` made of random chunks of x, crossfaded with equal power."""
    rng = np.random.default_rng(seed)
    n, k = len(x), int(xfade * SR)
    t = np.linspace(0, np.pi / 2, k, endpoint=False)[:, None]
    fade_in, fade_out = np.sin(t), np.cos(t)
    gap = k + int(0.3 * SR)

    def pick(prev_end):
        """A chunk whose source never overlaps what is fading out."""
        for _ in range(500):
            L = int(rng.uniform(*chunk) * SR)
            s = int(rng.uniform(0, n - L))
            if prev_end is None or abs(s - (prev_end - k)) > gap:
                return s, L
        raise RuntimeError("source too short for these chunk sizes")

    s, L = pick(None)
    first = s
    out = x[s:s + L].copy()
    # Where each chunk plays: (start, end, offset) with source position = output position + offset.
    segments = [(0, L, s)]
    target = int(seconds * SR)
    while True:
        prev_end = s + L
        s, L = pick(prev_end)
        if len(out) - k >= target:
            break
        t0 = len(out) - k
        segments.append((t0, t0 + L, s - t0))
        out[-k:] = out[-k:] * fade_out + x[s:s + k] * fade_in
        out = np.concatenate([out, x[s + k:s + L]])
    # Close the loop: the last chunk fades into the first one's opening, which the loop starts with.
    if abs(first - (prev_end - k)) <= gap:
        raise RuntimeError("unlucky seed: last chunk overlaps the first")
    T = len(out) - k
    loop = out[:T].copy()
    loop[:k] = out[T:] * fade_out + x[first:first + k] * fade_in
    return loop, segments


def wrapped(segments, T, shift=0):
    """The segments of a loop of length T played `shift` samples late, with copies one turn
    earlier and later so overlaps across the wrap-around are seen too."""
    return [(a + shift + m * T, b + shift + m * T, o - shift - m * T)
            for a, b, o in segments for m in (-2, -1, 0, 1, 2)]


def min_separation(segments, T, shift):
    """How close (in samples of source position) the left loop and the same loop delayed by
    `shift` ever come to playing the same moment of the recording at the same time."""
    left = [s for s in wrapped(segments, T) if s[1] > 0 and s[0] < T]
    right = [s for s in wrapped(segments, T, shift) if s[1] > 0 and s[0] < T]
    best = np.inf
    for a, b, o in left:
        for c, d, p in right:
            if c < b and a < d:
                best = min(best, abs(o - p))
    return best


def worst_lag_correlation(a, b, win=0.5, max_lag=1.5):
    """Largest normalized cross-correlation (above 300 Hz) between half-second windows of a and b
    at lags up to max_lag: near 0 for independent takes, high where both ears hear the same drops
    at (nearly) the same time, which would sound like a comb filter or an echo."""
    sos = butter(4, 300, btype="highpass", fs=SR, output="sos")
    a, b = sosfiltfilt(sos, a), sosfiltfilt(sos, b)
    w, lag = int(win * SR), int(max_lag * SR)
    energy = np.concatenate([[0.0], np.cumsum(b ** 2)])
    worst = 0.0
    for i in range(lag, len(a) - w - lag, w):
        aw = a[i:i + w]
        c = correlate(b[i - lag:i + lag + w], aw, mode="valid", method="fft")
        eb = energy[i - lag + w:i + lag + w + 1] - energy[i - lag:i + lag + 1]
        norm = np.abs(c) / (np.sqrt((aw ** 2).sum() * np.maximum(eb, 1e-20)))
        worst = max(worst, float(norm.max()))
    return worst


def make(src, out_path, trim=(0.25, 0.1), seconds=60.0, target_lufs=-19.0, seed=11, tags=None):
    x = aa.decode(src, channels=1)
    x = x[int(trim[0] * SR):len(x) - int(trim[1] * SR)]
    x = eq(x)
    for attempt in range(20):
        try:
            loop, segments = stitch_loop(x, seconds, seed=seed + attempt)
        except RuntimeError:
            continue
        T = len(loop)
        # The delay (between a third and two thirds of a turn) that keeps the two ears furthest
        # apart in the recording at every moment.
        shifts = range(T // 3, 2 * T // 3, SR // 10)
        sep, shift = max((min_separation(segments, T, d), d) for d in shifts)
        if sep < 0.6 * SR:
            continue
        right = np.roll(loop, shift, axis=0)
        corr = worst_lag_correlation(loop[:, 0], right[:, 0], max_lag=0.4)
        if corr < 0.3:
            break
    else:
        raise SystemExit("could not find independent left/right takes")
    stereo = np.concatenate([loop, right], axis=1)
    stereo = ml.circular_highpass(stereo, 25.0)
    stereo -= stereo.mean(axis=0)
    gain = target_lufs - aa.integrated_lufs(stereo)
    stereo *= 10 ** (gain / 20)
    limited = 0.0
    if ml.true_peak_db(stereo) > -1.5:
        stereo, limited = ml.lookahead_limit(stereo, 10 ** (-1.5 / 20))
    ml.encode(stereo, out_path, tags or {})
    y = aa.decode(out_path, channels=2)
    jump = np.abs(y[0] - y[-1]).max()
    w = int(0.05 * SR)
    typical = np.percentile(np.abs(np.diff(np.concatenate([y[-w:], y[:w]]), axis=0)).max(axis=1), 90)
    return {
        "seed": seed + attempt, "source_seconds": round(len(x) / SR, 2), "chunks": len(segments),
        "loop_seconds": round(len(y) / SR, 2), "frames_ok": abs(len(y) - len(stereo)) <= 1,
        "right_delay_seconds": round(shift / SR, 2),
        "min_left_right_separation_seconds": round(float(sep) / SR, 2),
        "left_right_correlation": round(float(corr), 3),
        "lufs": round(float(aa.integrated_lufs(y)), 1), "true_peak_db": round(float(ml.true_peak_db(y)), 1),
        "limit_db": round(float(limited), 1), "seam_jump_vs_typical": round(float(jump / (typical + 1e-9)), 2),
        "bytes": os.path.getsize(out_path),
    }


if __name__ == "__main__":
    print(json.dumps(make(**json.loads(sys.argv[1])), indent=1))

"""Turn a segment of a recording into a seamless, loudness-normalized Ogg Vorbis loop."""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from scipy.signal import butter, resample_poly, sosfilt, stft

import audio_analysis as aa

SR = aa.SR


def frame_features(x, hop=0.1):
    """Per-100 ms loudness (dB), spectral flux and 24 log-band energies."""
    mono = x.mean(axis=1)
    n = int(hop * SR)
    _, _, Z = stft(mono, fs=SR, nperseg=2 * n, noverlap=n, boundary=None, padded=False)
    mag = np.abs(Z)
    edges = np.geomspace(60, 18000, 25)
    f = np.linspace(0, SR / 2, mag.shape[0])
    bands = np.stack([mag[(f >= lo) & (f < hi)].mean(axis=0) for lo, hi in zip(edges[:-1], edges[1:])])
    logb = 20 * np.log10(bands + 1e-9)
    loud = 10 * np.log10((mag ** 2).mean(axis=0) + 1e-12)
    flux = np.concatenate([[0], np.maximum(0, np.diff(logb, axis=1)).mean(axis=0)])
    return loud, flux, logb


def extend_texture(x, need_frames, chunk=(5.0, 9.0), xf=1.2, seed=7):
    """Re-stitches random chunks of a short, steady recording (equal-power crossfades) into a longer one,
    so the loop doesn't audibly repeat every few seconds."""
    rng = np.random.default_rng(seed)
    n = len(x)
    lo, hi = min(chunk[0], n / SR * 0.5), min(chunk[1], n / SR * 0.8)
    k = int(xf * SR)
    t = np.linspace(0, np.pi / 2, k, endpoint=False)[:, None]
    first = int(rng.uniform(lo, hi) * SR)
    out = x[:first].copy()
    last = 0
    while len(out) < need_frames:
        L = int(rng.uniform(lo, hi) * SR)
        for _ in range(30):
            s = int(rng.uniform(0, max(1, n - L - 1)))
            if abs(s - last) > L * 0.6:
                break
        seg = x[s:s + L]
        out[-k:] = out[-k:] * np.cos(t) + seg[:k] * np.sin(t)
        out = np.concatenate([out, seg[k:]])
        last = s
    return out


def best_seam(x, start, length, xfade, search=6.0, hop=0.1, jitter=0.1):
    """Picks loop start a and length L near (start, length) so head [a, a+X) and tail [a+L, a+L+X) match."""
    loud, flux, logb = frame_features(x, hop)
    fx = max(1, int(round(xfade / hop)))
    best = None
    s0 = int(round(start / hop))
    for da in range(-int(search / hop), int(search / hop) + 1, 2):
        a = s0 + da
        if a < 0:
            continue
        for dl in (np.arange(-jitter, jitter + 1e-4, 0.02) if jitter > 0 else [0.0]):
            L = int(round(length * (1 + dl) / hop))
            b = a + L
            if b + fx >= len(loud):
                continue
            head, tail = slice(a, a + fx), slice(b, b + fx)
            cost = (abs(loud[head].mean() - loud[tail].mean())
                    + 2.0 * (flux[head].max() + flux[tail].max())
                    + 0.5 * np.abs(logb[:, head].mean(axis=1) - logb[:, tail].mean(axis=1)).mean()
                    + 0.3 * (loud[head].std() + loud[tail].std()))
            if best is None or cost < best[0]:
                best = (cost, a * hop, L * hop)
    return best


def build_loop(x, a, L, X):
    i0, n, k = int(round(a * SR)), int(round(L * SR)), int(round(X * SR))
    seg = x[i0:i0 + n + k]
    t = np.linspace(0, np.pi / 2, k, endpoint=False)[:, None]
    loop = seg[:n].copy()
    # Equal-power crossfade: the loop's first X seconds blend the continuation of its end into its start.
    loop[:k] = seg[:k] * np.sin(t) + seg[n:n + k] * np.cos(t)
    return loop


def circular_highpass(loop, hz):
    if hz <= 0:
        return loop
    sos = butter(2, hz, btype="highpass", fs=SR, output="sos")
    tiled = np.concatenate([loop, loop, loop])
    return sosfilt(sos, tiled, axis=0)[len(loop):2 * len(loop)]


def true_peak_db(x):
    up = resample_poly(x, 4, 1, axis=0)
    return 20 * np.log10(np.abs(up).max() + 1e-12)


def lookahead_limit(x, ceiling, look_ms=5.0, release_ms=80.0):
    """Transparent peak limiter for loops (circular): gain dips just before peaks and recovers smoothly."""
    from scipy.ndimage import minimum_filter1d, uniform_filter1d
    la = max(1, int(look_ms * SR / 1000))
    peak = np.abs(x).max(axis=1)
    req = np.minimum(1.0, ceiling / np.maximum(peak, 1e-9))
    g = minimum_filter1d(req, size=2 * la + 1, mode="wrap")
    # Smooth recovery: one-pole release, started one second before the loop end so the wrap is settled.
    a = np.exp(-1.0 / (release_ms * SR / 1000))
    pre = min(len(g), SR)
    seq = np.concatenate([g[-pre:], g])
    out = np.empty_like(seq)
    state = 1.0
    for i, v in enumerate(seq):
        state = v if v < state else a * state + (1 - a) * v
        out[i] = state
    g2 = uniform_filter1d(out[pre:], size=la, mode="wrap")
    g2 = np.minimum(g2, req)
    return x * np.minimum(g2, 1.0)[:, None], float(20 * np.log10(g2.min()))


def encode(loop, out_path, tags, quality=4):
    ch = loop.shape[1]
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", str(ch), "-i", "-",
           "-c:a", "libvorbis", "-q:a", str(quality)]
    for k, v in tags.items():
        cmd += ["-metadata", f"{k}={v}"]
    subprocess.run(cmd + [out_path], input=loop.astype(np.float32).tobytes(), check=True)


def make(src, start, length, out_path, target_lufs=-18.0, xfade=4.0, hp=30.0, tags=None, quality=4,
         max_seconds=None, gain_db=0.0, channel=None, region=None, extend=False, jitter=0.1, seed=7,
         max_limit=6.0):
    probe = aa.probe(src)
    if region:
        # Only ever use [region[0], region[1]) of the source (skips silences, power-downs, edits).
        pre = region[0]
        x = aa.decode(src, start=pre, max_seconds=region[1] - region[0])
    else:
        pre = max(0.0, start - 8)
        want = length * 1.1 + xfade + 16
        x = aa.decode(src, start=pre, max_seconds=want if max_seconds is None else min(want, max_seconds))
    if channel is not None:
        # Fake-stereo sources (a delayed copy) comb-filter when summed: keep one real channel.
        x = x[:, [channel]]
    stereo = x.shape[1] == 2 and probe["channels"] >= 2 and np.corrcoef(x[:, 0], x[:, 1])[0, 1] < 0.995
    if not stereo:
        x = x.mean(axis=1, keepdims=True)
    extended = False
    if extend and len(x) / SR < length + xfade + 1:
        x = extend_texture(x, int((length * 1.12 + xfade + 2) * SR), seed=seed)
        extended = True
        pre, start = 0.0, 0.0
    avail = len(x) / SR
    length = min(length, avail - xfade - 0.5)
    if region and not extended:
        # Anywhere inside the region that fits; best_seam skips placements that run past the end.
        slack = max(0.0, avail - xfade - 0.5 - length)
        center, search = slack / 2, slack / 2
    else:
        center, search = start - pre, min(6.0, max(0.0, start - pre))
    cost, a, L = best_seam(x, center, length, xfade, search=search, jitter=jitter)
    loop = build_loop(x, a, L, xfade)
    loop = circular_highpass(loop, hp)
    loop -= loop.mean(axis=0)
    lufs = aa.integrated_lufs(loop if loop.shape[1] == 2 else np.repeat(loop, 2, axis=1))
    gain = target_lufs - lufs + gain_db
    # Peaks above the ceiling get up to MAX_LIMIT dB of transparent limiting; beyond that the
    # loop is simply left quieter (the app gain makes up a little, its own limiter never works hard).
    ceiling_db = -1.5
    over = true_peak_db(loop) + gain - ceiling_db
    limited = False
    reduction = 0.0
    if over > 0:
        if over > max_limit:
            gain -= over - max_limit
        loop = loop * 10 ** (gain / 20)
        loop, reduction = lookahead_limit(loop, 10 ** (ceiling_db / 20))
        limited = True
    else:
        loop = loop * 10 ** (gain / 20)
    encode(loop, out_path, tags or {}, quality)
    # Verify what a player will actually decode.
    y = aa.decode(out_path, channels=loop.shape[1])
    jump = np.abs(y[0] - y[-1]).max()
    # Compare the wrap-around step with the steps right around it (±50 ms).
    w = int(0.05 * SR)
    around = np.concatenate([y[-w:], y[:w]])
    typical = np.percentile(np.abs(np.diff(around, axis=0)).max(axis=1), 90)
    return {
        "source_start": None if extended else round(float(pre + a), 2), "extended": extended,
        "loop_seconds": round(len(loop) / SR, 2),
        "decoded_seconds": round(len(y) / SR, 3), "frames_ok": abs(len(y) - len(loop)) <= 1,
        "channels": int(loop.shape[1]), "seam_cost": round(float(cost), 3),
        "seam_jump_vs_typical": round(float(jump / (typical + 1e-9)), 2),
        "lufs": round(float(aa.integrated_lufs(y if y.shape[1] == 2 else np.repeat(y, 2, axis=1))), 1),
        "true_peak_db": round(float(true_peak_db(y)), 1), "peak_limited": bool(limited),
        "limit_db": round(reduction, 1),
        "bytes": os.path.getsize(out_path),
    }


if __name__ == "__main__":
    args = json.loads(sys.argv[1])
    print(json.dumps(make(**args), indent=1))

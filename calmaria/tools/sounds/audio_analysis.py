"""Decode, measure and CLAP-score candidate recordings."""
import json
import os
import subprocess
import sys

import numpy as np

SR = 48000
_clap = None


def decode(path, sr=SR, channels=2, max_seconds=None, start=0.0):
    """Decodes any file to float32 [frames, channels] with ffmpeg (high-quality soxr resampling)."""
    cmd = ["ffmpeg", "-v", "error", "-nostdin"]
    if start:
        cmd += ["-ss", str(start)]
    cmd += ["-i", path]
    if max_seconds:
        cmd += ["-t", str(max_seconds)]
    cmd += ["-vn", "-ac", str(channels), "-ar", str(sr), "-af", "aresample=resampler=soxr:precision=28",
            "-f", "f32le", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, channels).copy()


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", path],
                         capture_output=True, text=True).stdout
    d = json.loads(out or "{}")
    st = next((s for s in d.get("streams", []) if s.get("codec_type") == "audio"), {})
    fmt = d.get("format", {})
    return {
        "codec": st.get("codec_name"), "sample_rate": int(st.get("sample_rate", 0) or 0),
        "channels": int(st.get("channels", 0) or 0), "duration": float(fmt.get("duration", 0) or 0),
        "bit_rate": int(fmt.get("bit_rate", 0) or 0),
    }


def k_weight(x, sr=SR):
    """ITU-R BS.1770 K-weighting (pre-filter + RLB) on [frames, ch]."""
    from scipy.signal import lfilter
    # Coefficients for 48 kHz from BS.1770-4.
    b1, a1 = [1.53512485958697, -2.69169618940638, 1.19839281085285], [1.0, -1.69065929318241, 0.73248077421585]
    b2, a2 = [1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621]
    return lfilter(b2, a2, lfilter(b1, a1, x, axis=0), axis=0)


def lufs_blocks(x, sr=SR, block=0.4, hop=0.1):
    y = k_weight(x, sr)
    n, h = int(block * sr), int(hop * sr)
    p = np.square(y).sum(axis=1)
    c = np.cumsum(np.concatenate([[0.0], p]))
    starts = np.arange(0, max(1, len(p) - n), h)
    ms = (c[starts + n] - c[starts]) / n if len(p) > n else np.array([p.mean()])
    return -0.691 + 10 * np.log10(np.maximum(ms, 1e-12))


def integrated_lufs(x, sr=SR):
    l = lufs_blocks(x, sr)
    l = l[l > -70]
    if len(l) == 0:
        return -70.0
    rel = 10 * np.log10(np.mean(10 ** (l / 10))) - 10
    l = l[l > rel]
    return float(10 * np.log10(np.mean(10 ** (l / 10))))


def bandwidth_hz(x, sr=SR):
    """Highest frequency whose level is within 60 dB of the spectral peak (detects upsampled/lowpassed files)."""
    from scipy.signal import welch
    f, p = welch(x.mean(axis=1), fs=sr, nperseg=8192)
    db = 10 * np.log10(p + 1e-20)
    smooth = np.convolve(db, np.ones(9) / 9, mode="same")
    above = np.where(smooth > smooth.max() - 60)[0]
    return float(f[above[-1]]) if len(above) else 0.0


def sharp_cutoff_hz(x, sr=SR):
    """Lowest frequency above 5 kHz where the spectrum falls off a cliff (>35 dB within ~1 kHz).

    Natural recordings roll off gradually; lossy encodes and low sample rates end in a brick wall.
    Returns 0 when there is no such cliff below 20 kHz.
    """
    from scipy.signal import welch
    f, p = welch(x.mean(axis=1), fs=sr, nperseg=16384)
    db = 10 * np.log10(p + 1e-24)
    db = np.convolve(db, np.ones(5) / 5, mode="same")
    step = int(round(1000 / (f[1] - f[0])))
    for i in range(np.searchsorted(f, 5000), np.searchsorted(f, 20000) - step):
        if db[i] - db[i + step] > 35 and db[i + step:i + 3 * step].max() < db[i] - 30:
            return float(f[i])
    return 0.0


def tech_metrics(x, sr=SR):
    mono = x.mean(axis=1)
    clip = float(np.mean(np.abs(x) > 0.995))
    corr = float(np.corrcoef(x[:, 0], x[:, 1])[0, 1]) if x.shape[1] == 2 and x[:, 0].std() > 0 and x[:, 1].std() > 0 else 1.0
    st = lufs_blocks(x, sr, block=3.0, hop=1.0)
    return {
        "lufs": integrated_lufs(x, sr),
        "short_term_spread": float(np.percentile(st, 95) - np.percentile(st, 10)) if len(st) > 5 else 0.0,
        "bandwidth": bandwidth_hz(x, sr),
        "cutoff": sharp_cutoff_hz(x, sr),
        "clip_ratio": clip,
        "stereo_corr": corr,
        "dc": float(abs(mono.mean())),
        "peak": float(np.abs(x).max()),
    }


def clap():
    global _clap
    if _clap is None:
        import torch
        from transformers import ClapModel, ClapProcessor
        torch.set_num_threads(4)
        name = "laion/larger_clap_general"
        _clap = (ClapModel.from_pretrained(name).eval(), ClapProcessor.from_pretrained(name))
    return _clap


def text_embeddings(texts):
    import torch
    model, proc = clap()
    with torch.no_grad():
        inp = proc(text=texts, return_tensors="pt", padding=True)
        e = model.get_text_features(**inp)
        e = e if isinstance(e, torch.Tensor) else e.pooler_output
    return torch.nn.functional.normalize(e, dim=-1).numpy()


def audio_embeddings(x, sr=SR, win=10.0, hop=10.0, batch=8):
    """CLAP embedding for each window of a [frames, ch] signal."""
    import torch
    model, proc = clap()
    mono = x.mean(axis=1)
    n, h = int(win * sr), int(hop * sr)
    starts = list(range(0, max(1, len(mono) - n + 1), h)) or [0]
    embs = []
    for i in range(0, len(starts), batch):
        clips = [mono[s:s + n] for s in starts[i:i + batch]]
        with torch.no_grad():
            inp = proc(audio=clips, sampling_rate=sr, return_tensors="pt")
            e = model.get_audio_features(**inp)
            e = e if isinstance(e, torch.Tensor) else e.pooler_output
        embs.append(torch.nn.functional.normalize(e, dim=-1).numpy())
    return np.array(starts) / sr, np.concatenate(embs)


if __name__ == "__main__":
    x = decode(sys.argv[1], max_seconds=60)
    print(probe(sys.argv[1]), tech_metrics(x))

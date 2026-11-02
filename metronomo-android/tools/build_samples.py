#!/usr/bin/env python3
"""
Gera os samples reais usados pelo BluesTempo a partir de bibliotecas CC0 (domínio público).

Fontes (todas CC0 1.0, hospedadas em github.com/sfzinstruments e github.com/sgossner):
  - Virtuosity Drums (Versilian Studios / Karoryfer) .... bateria de jazz tocada com baquetas
  - Big Rusty Drums (Karoryfer) .......................... vassourinhas (toque e varrida)
  - Black And Blue Basses, "darkblack" (Karoryfer) ...... baixo elétrico
  - Meatbass (Karoryfer), pizzicato ...................... contrabaixo (Otto Rubner, 1958)
  - VCSL - Versilian Community Sample Library ............ cowbell, shaker, pandeirola, claves

O script baixa só os arquivos necessários (clone parcial do git), mixa os microfones em mono,
recorta silêncio/caudas, mede a afinação real de cada nota de baixo e grava:
  app/src/main/assets/samples/<arquivo>.snd   (cabeçalho "BTS1" + PCM 16 bits mono)
  app/src/main/assets/samples/manifest.json

Requisitos: git, ffmpeg, python3 com numpy.
Uso: python3 tools/build_samples.py [--cache DIR]
"""
import argparse
import json
import os
import struct
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "samples")

REPOS = {
    "virtuosity": "https://github.com/sfzinstruments/virtuosity_drums",
    "rusty": "https://github.com/sfzinstruments/karoryfer.big-rusty-drums",
    "blackblue": "https://github.com/sfzinstruments/karoryfer.black-and-blue-basses",
    "meatbass": "https://github.com/sfzinstruments/karoryfer.meatbass",
    "vcsl": "https://github.com/sgossner/VCSL",
}

DRUM_RATE = 48000
BASS_RATE = 24000


# --------------------------------------------------------------------------- utilidades

def repo_dir(cache, key):
    path = os.path.join(cache, key)
    if not os.path.isdir(os.path.join(path, ".git")):
        subprocess.run(["git", "clone", "-q", "--depth", "1", "--filter=blob:none", "--no-checkout",
                        REPOS[key], path], check=True)
    return path


def fetch(cache, key, paths):
    """Baixa (se preciso) apenas os arquivos pedidos do repositório."""
    base = repo_dir(cache, key)
    missing = [p for p in paths if not os.path.exists(os.path.join(base, p))]
    for i in range(0, len(missing), 60):
        subprocess.run(["git", "-C", base, "checkout", "HEAD", "--"] + missing[i:i + 60], check=True)
    return [os.path.join(base, p) for p in paths]


def load(path, rate, channels=None):
    """Decodifica com ffmpeg em float32; devolve array (frames, canais)."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=channels", "-of", "csv=p=0", path],
                           capture_output=True, text=True, check=True).stdout.strip()
    ch = channels or int(probe.split(",")[0])
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", str(ch), "-ar", str(rate),
                          "-af", "aresample=resampler=soxr:precision=28", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, ch).astype(np.float64)


def channel(x, which):
    if x.shape[1] == 1:
        return x[:, 0]
    if which == "mid":
        return x.mean(axis=1)
    if which == "loud":
        return x[:, int(np.argmax((x ** 2).sum(axis=0)))]
    return x[:, which]


def mix(parts):
    n = max(len(p) for p, _ in parts)
    out = np.zeros(n)
    for p, g in parts:
        out[:len(p)] += p * g
    return out


def trim(x, rate, max_seconds, floor_db=-62.0, pre_ms=1.0):
    """Remove silêncio inicial, corta a cauda abaixo de floor_db (rel. ao pico) e aplica fade."""
    peak = np.max(np.abs(x)) + 1e-12
    start = int(np.argmax(np.abs(x) > peak * 0.02))
    start = max(0, start - int(rate * pre_ms / 1000))
    x = x[start:]
    win = int(rate * 0.01)
    env = np.sqrt(np.convolve(x ** 2, np.ones(win) / win, mode="same"))
    above = np.nonzero(env > peak * 10 ** (floor_db / 20))[0]
    end = min(len(x), (above[-1] + win) if len(above) else len(x), int(rate * max_seconds))
    x = x[:end].copy()
    fade = min(len(x) // 3, int(rate * min(0.25, max_seconds * 0.15)))
    if fade > 0:
        x[-fade:] *= np.linspace(1, 0, fade) ** 2
    return x


def attack_level(x, rate):
    """Nível percebido do ataque (RMS dos primeiros 60 ms)."""
    n = int(rate * 0.06)
    return float(np.sqrt(np.mean(x[:n] ** 2)))


def f0(x, rate):
    """Afinação pelo produto harmônico do espectro (robusto quando a fundamental é fraca)."""
    seg = x[int(0.1 * rate):int(1.1 * rate)]
    seg = (seg - seg.mean()) * np.hanning(len(seg))
    size = 8 * len(seg)
    spec = np.abs(np.fft.rfft(seg, size))
    freqs = np.fft.rfftfreq(size, 1 / rate)
    spec[freqs < 20] = 0
    product = spec.copy()
    for k in (2, 3, 4):
        down = spec[::k]
        product[:len(down)] *= down
    band = (freqs > 20) & (freqs < 700)
    return float(freqs[band][np.argmax(product[band])])


def write_snd(name, x, rate):
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(b"BTS1")
        f.write(struct.pack("<ii", rate, len(pcm)))
        f.write(pcm.tobytes())


NOTE_NAMES = {"c": 0, "db": 1, "d": 2, "eb": 3, "e": 4, "f": 5, "gb": 6, "g": 7, "ab": 8, "a": 9, "bb": 10, "b": 11}


def name_to_midi(name):
    letters = name.rstrip("0123456789")
    return 12 * (int(name[len(letters):]) + 1) + NOTE_NAMES[letters]


# --------------------------------------------------------------------------- bateria (baquetas)

def virtuosity(cache):
    """Mix "basic kit" (microfone do bumbo + da caixa + overhead) em mono.

    Os overheads são um par espaçado: somar L+R causaria filtro-pente, então usa o canal
    mais forte para pratos e a média só para tambores (onde L e R são bem correlacionados).
    """
    def files(folder, name):
        return {m: f"Samples/{m}/{folder}/{m}_{name}.flac" for m in ("kickmic", "snaremic", "oh")}

    spec = {
        # instrumento: (pasta, nome base, camadas, variações, duração máx., canal OH, ganhos kick/snare/oh)
        "KICK": ("kick", "kick_snon", [1, 2, 3, 4], [1, 2], 0.9, "mid", (1.0, 0.35, 0.8)),
        "SNARE": ("snare", "snare_center", [4, 9, 13, 18, 22, 27, 31, 36], [None], 1.1, "mid", (0.35, 1.0, 0.8)),
        "RIM": ("snare", "snare_crossstick", [4, 8, 12, 16], [None], 0.45, "mid", (0.2, 1.0, 0.8)),
        "HAT_CLOSED": ("hh", "hh_closed", [1, 2, 3, 4], [1, 2, 3], 0.6, "loud", (0.15, 0.55, 1.0)),
        "HAT_OPEN": ("hh", "hh_open", [2, 4], [1], 1.6, "loud", (0.15, 0.55, 1.0)),
        "HAT_PEDAL": ("hh", "hh_pedal", [1, 2, 3], [1, 2], 0.5, "loud", (0.15, 0.55, 1.0)),
        "RIDE": ("ride", "ride_ride", [1, 2, 3], [1, 2], 2.2, "loud", (0.15, 0.35, 1.0)),
        "RIDE_BELL": ("ride", "ride_bell", [2, 3], [1], 2.0, "loud", (0.15, 0.35, 1.0)),
        "CRASH": ("crash", "crash_crash", [2, 3], [1], 2.8, "loud", (0.15, 0.35, 1.0)),
        "TOM_HIGH": ("htom", "htom_center", [6, 11, 16], [None], 1.3, "mid", (0.3, 0.6, 1.0)),
        "TOM_LOW": ("ltom", "ltom_center", [6, 11, 16], [None], 1.5, "mid", (0.4, 0.6, 1.0)),
    }
    result = {}
    for inst, (folder, base, layers, rrs, max_s, oh_ch, (gk, gs, go)) in spec.items():
        result[inst] = []
        for vl in layers:
            hits = []
            for rr in rrs:
                name = f"{base}_vl{vl}" + (f"_rr{rr}" if rr else "")
                paths = files(folder, name)
                local = dict(zip(paths.keys(), fetch(cache, "virtuosity", list(paths.values()))))
                k = channel(load(local["kickmic"], DRUM_RATE), 0)
                s = channel(load(local["snaremic"], DRUM_RATE), 0)
                o = channel(load(local["oh"], DRUM_RATE), oh_ch)
                hits.append(trim(mix([(k, gk), (s, gs), (o, go)]), DRUM_RATE, max_s))
            result[inst].append(hits)
    return result


# --------------------------------------------------------------------------- vassourinhas

def rusty_brushes(cache):
    def load_mix(folder, fname):
        mics = {"top": 1.0, "btm": 0.35, "oh": 0.9}
        parts = []
        for mic, g in mics.items():
            path = fetch(cache, "rusty", [f"Samples/snare_14/{folder}/{mic}/{fname}"])[0]
            parts.append((channel(load(path, DRUM_RATE), "loud" if mic == "oh" else 0), g))
        return mix(parts)

    taps = [[trim(load_mix("brush", f"sn_b_vl{vl}_rr{rr}.flac"), DRUM_RATE, 0.8) for rr in (1, 2)]
            for vl in (2, 4, 6, 8)]
    # A varrida é um gesto contínuo: mantém o começo suave (sem recorte agressivo do ataque).
    sweeps = []
    for dl in (2, 4):
        x = load_mix("bstir", f"sn_stir_dl{dl}_rr1.wav")
        sweeps.append([trim(x, DRUM_RATE, 1.2, pre_ms=30)])
    return {"BRUSH_TAP": taps, "BRUSH_SWEEP": sweeps}


# --------------------------------------------------------------------------- percussão (VCSL)

def vcsl(cache):
    base = "Idiophones/Struck Idiophones/"
    spec = {
        "COWBELL": [[base + "Cowbells/Cowbell1_Hit_v2_rr1_Mid.wav"], [base + "Cowbells/Cowbell1_Hit_v4_rr1_Mid.wav"]],
        "TAMBOURINE": [[base + "Tambourine 1/Tamb1_Hit_v1_rr1_Mid.wav"], [base + "Tambourine 1/Tamb1_Hit_v2_rr1_Mid.wav"]],
        "CLAVE": [[base + "Claves/Claves1_Hit_v2_rr1_Mid.wav"], [base + "Claves/Claves1_Hit_v3_rr1_Mid.wav"]],
        "SHAKER": [[base + "Shaker, Small/Mid_ShakerHighFaster_Down_rr1.wav",
                    base + "Shaker, Small/Mid_ShakerHighFaster_Up_rr1.wav",
                    base + "Shaker, Small/Mid_ShakerHighFaster_Down_rr2.wav",
                    base + "Shaker, Small/Mid_ShakerHighFaster_Up_rr2.wav"]],
    }
    max_len = {"COWBELL": 0.8, "TAMBOURINE": 0.6, "CLAVE": 0.3, "SHAKER": 0.25}
    out = {}
    for inst, layers in spec.items():
        out[inst] = [[trim(channel(load(p, DRUM_RATE), "mid"), DRUM_RATE, max_len[inst])
                      for p in fetch(cache, "vcsl", files)] for files in layers]
    return out


# --------------------------------------------------------------------------- baixos

def basses(cache):
    out = {}
    # Elétrico: nomes uma oitava acima do som real (notação de baixo) -> confere pela afinação medida.
    elec_names = ["b1", "d2", "f2", "ab2", "b2", "d3", "f3", "ab3", "b3", "d4", "f4", "ab4", "b4", "d5"]
    elec = []
    for nm in elec_names:
        layers = []
        for dyn in ("mf", "f"):
            path = fetch(cache, "blackblue", [f"Samples/darkblack/reg/darkblack_{nm}_{dyn}_rr1.wav"])[0]
            layers.append(load(path, BASS_RATE)[:, 0])
        elec.append((nm, name_to_midi(nm) - 12, layers))
    up_names = ["a0", "c1", "eb1", "gb1", "a1", "c2", "eb2", "gb2", "a2", "c3", "eb3", "gb3", "a3", "c4"]
    upright = []
    for nm in up_names:
        layers = []
        for vl in (3, 4):
            path = fetch(cache, "meatbass", [f"Samples/pizz/{nm}_vl{vl}_rr1.wav"])[0]
            layers.append(load(path, BASS_RATE)[:, 0])
        upright.append((nm, name_to_midi(nm), layers))
    for key, notes in (("ELECTRIC", elec), ("UPRIGHT", upright)):
        regions = []
        for nm, expected, layers in notes:
            # A nota vem do nome do arquivo (o baixo elétrico usa notação uma oitava acima do som,
            # conferido por medição). A medição só ajusta a afinação fina, em cents.
            measured = 69 + 12 * np.log2(f0(layers[0], BASS_RATE) / 440)
            cents = 0
            if abs(measured - expected) < 0.5:
                cents = int(round((measured - expected) * 100))
            else:
                print(f"  aviso {key} {nm}: esperado {expected}, medido {measured:.2f} (ignorado)", file=sys.stderr)
            regions.append((expected, cents, [trim(x, BASS_RATE, 1.9, floor_db=-55) for x in layers]))
        out[key] = regions
    return out


# --------------------------------------------------------------------------- saída

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", default=os.path.expanduser("~/.cache/bluestempo-samples"))
    args = parser.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".snd") or f == "manifest.json":
            os.remove(os.path.join(OUT, f))

    print("Bateria (Virtuosity Drums)...")
    drums = virtuosity(args.cache)
    # Normalização global da bateria: preserva o equilíbrio natural entre as peças do kit.
    kit_peak = max(np.max(np.abs(h)) for inst in drums.values() for layer in inst for h in layer)
    print("Vassourinhas (Big Rusty Drums)...")
    drums.update(rusty_brushes(args.cache))
    print("Percussão (VCSL)...")
    drums.update(vcsl(args.cache))

    manifest = {"drums": {}, "bass": {}}
    for inst, layers in drums.items():
        # Percussão e vassourinhas vêm de outras gravações: normaliza à parte com nível próprio.
        own = inst in ("BRUSH_TAP", "BRUSH_SWEEP", "COWBELL", "TAMBOURINE", "CLAVE", "SHAKER")
        peak = max(np.max(np.abs(h)) for layer in layers for h in layer) if own else kit_peak
        target = 0.95 if not own else 0.9
        loud = max(np.mean([attack_level(h, DRUM_RATE) for h in layer]) for layer in layers)
        entries = []
        for li, layer in enumerate(layers):
            files = []
            for ri, hit in enumerate(layer):
                name = f"{inst.lower()}_{li}_{ri}.snd"
                write_snd(name, hit * target / peak, DRUM_RATE)
                files.append(name)
            level = np.mean([attack_level(h, DRUM_RATE) for h in layer]) / loud
            entries.append({"level": round(float(level), 4), "files": files})
        entries.sort(key=lambda e: e["level"])
        manifest["drums"][inst] = entries

    print("Baixos (Black And Blue Basses + Meatbass)...")
    for tone, regions in basses(args.cache).items():
        peak = max(np.max(np.abs(x)) for _, _, layers in regions for x in layers)
        loud = max(attack_level(x, BASS_RATE) for _, _, layers in regions for x in layers)
        out = []
        for midi, cents, layers in regions:
            files = []
            levels = []
            for li, x in enumerate(layers):
                name = f"bass_{tone.lower()}_{midi}_{li}.snd"
                write_snd(name, x * 0.95 / peak, BASS_RATE)
                files.append(name)
                levels.append(round(attack_level(x, BASS_RATE) / loud, 4))
            out.append({"key": midi, "cents": cents, "files": files, "levels": levels})
        manifest["bass"][tone] = sorted(out, key=lambda r: r["key"])

    manifest["credits"] = [
        "Virtuosity Drums — Versilian Studios & Karoryfer Samples (CC0)",
        "Big Rusty Drums — Karoryfer Samples (CC0)",
        "Black And Blue Basses — Karoryfer Samples (CC0)",
        "Meatbass — Karoryfer Samples (CC0)",
        "VCSL — Versilian Community Sample Library (CC0)",
    ]
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
    print(f"Pronto: {len(os.listdir(OUT))} arquivos, {total / 1e6:.1f} MB em {OUT}")


if __name__ == "__main__":
    main()

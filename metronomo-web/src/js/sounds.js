// Carrega os instrumentos reais (pacote "BTB1") e sintetiza os cliques do metrônomo.
//
// Formato do pacote (gerado por build.mjs a partir dos samples do app Android), tudo em gzip:
//   "BTB1" | uint32 LE tamanho do manifesto | manifesto JSON | PCM 16 bits em delta (int16 LE)
// O delta + gzip reduz ~57% sem nenhuma perda e não depende de codec do navegador.

const PAKO_URL = 'https://cdnjs.cloudflare.com/ajax/libs/pako/2.1.0/pako.min.js';

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const el = document.createElement('script');
    el.src = src;
    el.onload = resolve;
    el.onerror = () => reject(new Error('Falha ao carregar ' + src));
    document.head.appendChild(el);
  });
}

function base64ToBytes(text) {
  const binary = atob(text.trim());
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function gunzip(buffer) {
  if (typeof DecompressionStream !== 'undefined') {
    const stream = new Blob([buffer]).stream().pipeThrough(new DecompressionStream('gzip'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }
  // iOS anterior ao 16.4: descompacta em JavaScript.
  if (typeof pako === 'undefined') await loadScript(PAKO_URL);
  return pako.ungzip(new Uint8Array(buffer)); // eslint-disable-line no-undef
}

/** Baixa e decodifica o pacote; devolve { manifest, sounds: {nome: {rate, data: Float32Array}} }. */
export async function loadSampleBundle(url, onProgress) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Não foi possível baixar os sons (${response.status})`);
  let compressed;
  const total = Number(response.headers.get('content-length')) || 0;
  if (response.body && total && onProgress) {
    const reader = response.body.getReader();
    const chunks = [];
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.length;
      onProgress(received / total);
    }
    compressed = new Uint8Array(received);
    let o = 0;
    for (const c of chunks) {
      compressed.set(c, o);
      o += c.length;
    }
  } else {
    compressed = new Uint8Array(await response.arrayBuffer());
  }
  if (/\.txt(\?|$)/.test(url)) compressed = base64ToBytes(new TextDecoder().decode(compressed));
  return parseBundle(await gunzip(compressed.buffer));
}

/** Decodifica o pacote já descompactado (também usado nos testes em Node). */
export function parseBundle(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const magic = String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]);
  if (magic !== 'BTB1') throw new Error('Pacote de sons inválido');
  const manifestLength = view.getUint32(4, true);
  const manifest = JSON.parse(new TextDecoder().decode(bytes.subarray(8, 8 + manifestLength)));
  let pos = 8 + manifestLength;
  const sounds = {};
  for (const entry of manifest.sounds) {
    const data = new Float32Array(entry.frames);
    let acc = 0;
    for (let i = 0; i < entry.frames; i++) {
      acc = (acc + view.getInt16(pos, true)) << 16 >> 16; // soma em int16 (desfaz o delta)
      data[i] = acc / 32768;
      pos += 2;
    }
    sounds[entry.name] = { rate: entry.rate, data };
  }
  return { manifest, sounds };
}

// ------------------------------------------------------------------ cliques sintetizados

function makeNoise(seed) {
  let s = seed >>> 0 || 0x9e3779b9;
  return () => {
    s ^= s << 13; s >>>= 0;
    s ^= s >>> 17;
    s ^= s << 5; s >>>= 0;
    return (s / 4294967296) * 2 - 1;
  };
}

function biquad(type, freq, q, rate) {
  const w0 = (2 * Math.PI * freq) / rate;
  const alpha = Math.sin(w0) / (2 * q);
  const c = Math.cos(w0);
  const a0 = 1 + alpha;
  let b0, b1, b2;
  if (type === 'bandpass') { b0 = alpha; b1 = 0; b2 = -alpha; }
  else if (type === 'highpass') { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2; }
  else { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2; }
  const a1 = (-2 * c) / a0, a2 = (1 - alpha) / a0;
  b0 /= a0; b1 /= a0; b2 /= a0;
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  return (x) => {
    const y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1; x1 = x; y2 = y1; y1 = y;
    return y;
  };
}

function onePoleLowPass(cutoff, rate) {
  const a = Math.exp((-2 * Math.PI * cutoff) / rate);
  let y = 0;
  return (x) => (y = (1 - a) * x + a * y);
}

const square = (phase) => (phase - Math.floor(phase) < 0.5 ? 1 : -1);

function render(rate, seconds, peak, fn) {
  const out = new Float32Array(Math.floor(rate * seconds));
  for (let i = 0; i < out.length; i++) out[i] = fn(i / rate);
  let max = 0;
  for (const v of out) max = Math.max(max, Math.abs(v));
  if (max > 0) for (let i = 0; i < out.length; i++) out[i] *= peak / max;
  const fade = Math.min(out.length, Math.floor(rate * 0.006));
  for (let i = 0; i < fade; i++) out[out.length - 1 - i] *= i / fade;
  return out;
}

function cowbell(rate, pitch, peak) {
  const bp = biquad('bandpass', 720 * pitch, 1.6, rate);
  const lp = onePoleLowPass(4500, rate);
  return render(rate, 0.45, peak, (t) =>
    lp(bp(square(562 * pitch * t) + square(845 * pitch * t))) * (0.7 * Math.exp(-t / 0.05) + 0.3 * Math.exp(-t / 0.22)));
}

/** Cliques do metrônomo: [som][nível] (0 = acento, 1 = normal, 2 = subdivisão). Igual ao app Android. */
export function synthesizeClicks(rate) {
  const result = {};
  for (const sound of ['CLASSIC', 'WOOD', 'STICKS', 'COWBELL', 'DIGITAL']) {
    result[sound] = [0, 1, 2].map((level) => {
      const peak = [1, 0.85, 0.6][level];
      switch (sound) {
        case 'CLASSIC': {
          const f = [1760, 1320, 990][level];
          return render(rate, 0.07, peak, (t) =>
            (Math.sin(2 * Math.PI * f * t) + 0.2 * Math.sin(4 * Math.PI * f * t)) * Math.min(1, t / 0.0005) * Math.exp(-t / 0.016));
        }
        case 'WOOD': {
          const f = [1250, 1000, 820][level];
          const noise = makeNoise(71 + level);
          return render(rate, 0.09, peak, (t) =>
            Math.sin(2 * Math.PI * f * t) * Math.exp(-t / 0.024) + Math.sin(2 * Math.PI * f * 2.68 * t) * Math.exp(-t / 0.008) * 0.4 +
            noise() * Math.exp(-t / 0.0012) * 0.3);
        }
        case 'STICKS': {
          const f = [2700, 2250, 1900][level];
          const noise = makeNoise(81 + level);
          const bp = biquad('bandpass', f * 1.3, 2, rate);
          return render(rate, 0.07, peak, (t) =>
            Math.sin(2 * Math.PI * f * t) * Math.exp(-t / 0.012) * 0.7 + bp(noise()) * Math.exp(-t / 0.006) * 1.4);
        }
        case 'COWBELL': {
          const out = cowbell(rate, [1.22, 1, 0.88][level], 1);
          for (let i = 0; i < out.length; i++) out[i] *= peak;
          return out;
        }
        default: {
          const f = [2000, 1500, 1000][level];
          const lp = onePoleLowPass(6000, rate);
          return render(rate, 0.045, peak * 0.8, (t) => lp(square(f * t)) * (t < 0.03 ? 1 : Math.exp(-(t - 0.03) / 0.003)));
        }
      }
    });
  }
  return result;
}

// Confere se o motor web agenda exatamente os mesmos eventos (peça, clique ou nota de baixo,
// no mesmo quadro de áudio) que o motor do app Android, para os cenários de parity-scenarios.json.
// A referência é gerada pelo ParityReferenceTest do app Android.
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');

// Empacota os módulos (eles importam o JSON de dados) para rodar no Node.
const out = await build({
  stdin: {
    contents: "export { Engine } from './js/engine.js'; export { parseBundle } from './js/sounds.js'; export { normalizeSettings } from './js/music.js';",
    resolveDir: join(ROOT, 'src'),
  },
  bundle: true, format: 'esm', platform: 'node', write: false, loader: { '.json': 'json' },
});
const tmp = join(mkdtempSync(join(tmpdir(), 'bt-')), 'engine.mjs');
writeFileSync(tmp, out.outputFiles[0].contents);
const { Engine, parseBundle, normalizeSettings } = await import(pathToFileURL(tmp));

// AudioContext mínimo: só registra o que o motor agenda.
const param = () => ({ value: 0, setValueAtTime() {}, setTargetAtTime() {}, cancelScheduledValues() {} });
const node = (extra = {}) => ({ connect() {}, ...extra });
function fakeContext(rate) {
  return {
    sampleRate: rate,
    currentTime: 0,
    destination: node(),
    createGain: () => node({ gain: param(), channelCount: 2, channelCountMode: 'max', channelInterpretation: 'speakers' }),
    createChannelMerger: () => node(),
    createWaveShaper: () => node({ curve: null }),
    createBufferSource: () => node({ buffer: null, playbackRate: param(), start() {}, stop() {} }),
    createBuffer: (ch, length, sampleRate) => ({ duration: length / sampleRate, getChannelData: () => new Float32Array(length) }),
    startRendering: () => Promise.resolve(),
  };
}

const scenarios = JSON.parse(readFileSync(join(HERE, 'parity-scenarios.json'), 'utf8'));
const reference = JSON.parse(readFileSync(join(HERE, 'parity-reference.json'), 'utf8'));
const bundle = parseBundle(gunzipSync(readFileSync(join(ROOT, 'dist', 'samples.bin'))));

const key = (e) => `${e[1]}|${e[2]}`;
const sortEvents = (list) => [...list].sort((a, b) => a[0] - b[0] || key(a).localeCompare(key(b)));

let failures = 0;
let total = 0;
for (const sc of scenarios) {
  const ref = reference[sc.name];
  const rate = ref.rate;
  const engine = new Engine();
  engine.setBundle(bundle);
  engine.trace = [];
  await engine.renderOffline(fakeContext(rate), normalizeSettings(sc.settings), sc.seconds);
  const got = sortEvents(engine.trace.map(([t, kind, id]) => [Math.round(t * rate), kind, id]).filter((e) => e[0] < sc.seconds * rate));
  const want = sortEvents(ref.events);
  total += want.length;
  let problem = null;
  if (got.length !== want.length) problem = `${got.length} eventos, esperado ${want.length}`;
  else {
    for (let i = 0; i < want.length; i++) {
      if (key(got[i]) !== key(want[i]) || Math.abs(got[i][0] - want[i][0]) > 1) {
        problem = `evento ${i}: web ${JSON.stringify(got[i])} × Android ${JSON.stringify(want[i])}`;
        break;
      }
    }
  }
  if (problem) {
    failures++;
    console.log(`✗ ${sc.name}: ${problem}`);
  } else {
    console.log(`✓ ${sc.name} (${want.length} eventos)`);
  }
}
console.log(`\n${scenarios.length - failures}/${scenarios.length} cenários idênticos ao app Android (${total} eventos).`);
process.exit(failures ? 1 : 0);
